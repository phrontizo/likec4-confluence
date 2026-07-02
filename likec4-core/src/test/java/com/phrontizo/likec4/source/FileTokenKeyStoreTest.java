package com.phrontizo.likec4.source;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The token-encryption key must live in a file-backed store that is SEPARATE from wherever the
 * ciphertext is kept (the Confluence DB / PluginSettings). These tests pin that contract: a key is
 * generated on first use, persisted with owner-only (0600) perms, reloaded identically across
 * instances, and a different directory yields a different key that cannot decrypt the first's
 * ciphertext. The persisted file holds the raw KEY, never any ciphertext.
 */
class FileTokenKeyStoreTest {

  @Test
  void generates_a_256_bit_key_on_first_use_and_writes_the_file(@TempDir Path dir) {
    FileTokenKeyStore store = new FileTokenKeyStore(dir);
    assertFalse(store.exists(), "no key file should exist before first use");

    byte[] key = store.loadOrCreateKey();

    assertEquals(32, key.length, "AES-256 key is 32 bytes");
    assertTrue(store.exists(), "key file is persisted on first use");
    assertEquals(dir.resolve("likec4-token.key"), store.keyFile());
  }

  @Test
  void reloads_the_same_key_across_instances(@TempDir Path dir) {
    byte[] first = new FileTokenKeyStore(dir).loadOrCreateKey();
    // A brand-new instance over the same directory must read back the persisted key, not mint a new one.
    byte[] second = new FileTokenKeyStore(dir).loadOrCreateKey();
    assertArrayEquals(first, second);
  }

  @Test
  void round_trips_a_token_through_a_cipher_keyed_by_the_store(@TempDir Path dir) {
    TokenCipher cipher = new TokenCipher(new FileTokenKeyStore(dir).loadOrCreateKey());
    String enc = cipher.encrypt("glpat-secret-123");
    // Reopen the store (simulating a JVM restart) and decrypt with the reloaded key.
    TokenCipher reopened = new TokenCipher(new FileTokenKeyStore(dir).loadOrCreateKey());
    assertEquals("glpat-secret-123", reopened.decrypt(enc));
  }

  @Test
  void a_different_directory_yields_a_different_key_that_cannot_decrypt(@TempDir Path dirA, @TempDir Path dirB) {
    TokenCipher a = new TokenCipher(new FileTokenKeyStore(dirA).loadOrCreateKey());
    byte[] keyA = new FileTokenKeyStore(dirA).loadOrCreateKey();
    byte[] keyB = new FileTokenKeyStore(dirB).loadOrCreateKey();
    assertFalse(java.util.Arrays.equals(keyA, keyB), "independent stores must mint independent keys");

    String enc = a.encrypt("glpat-secret-123");
    TokenCipher b = new TokenCipher(keyB);
    // GCM auth fails when keyed by the wrong store -> decrypt throws.
    assertThrows(IllegalStateException.class, () -> b.decrypt(enc));
  }

  @Test
  void persists_the_key_with_owner_only_0600_permissions(@TempDir Path dir) {
    FileTokenKeyStore store = new FileTokenKeyStore(dir);
    store.loadOrCreateKey();

    Path keyFile = store.keyFile();
    assumeTrue(keyFile.getFileSystem().supportedFileAttributeViews().contains("posix"),
        "POSIX permissions assertion only meaningful on a POSIX filesystem");
    try {
      Set<PosixFilePermission> perms = Files.getPosixFilePermissions(keyFile);
      assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms,
          "key file must be 0600 (owner read/write only) — never group/other readable");
    } catch (java.io.IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void persisted_file_contains_the_raw_key_never_ciphertext(@TempDir Path dir) throws Exception {
    FileTokenKeyStore store = new FileTokenKeyStore(dir);
    byte[] key = store.loadOrCreateKey();

    byte[] onDisk = Files.readAllBytes(store.keyFile());
    assertArrayEquals(key, onDisk, "the file on disk is exactly the AES key bytes");
    assertEquals(32, onDisk.length, "no ciphertext/IV/tag is co-located with the key");

    // Encrypting a known token must NOT leave that token (or its ciphertext) anywhere in the key file.
    TokenCipher cipher = new TokenCipher(key);
    String ciphertext = cipher.encrypt("glpat-super-secret");
    byte[] afterEncrypt = Files.readAllBytes(store.keyFile());
    assertArrayEquals(key, afterEncrypt, "encrypting must not mutate the key file");
    String fileAsText = new String(afterEncrypt, StandardCharsets.ISO_8859_1);
    assertFalse(fileAsText.contains("glpat-super-secret"), "plaintext token must never touch the key file");
    assertFalse(fileAsText.contains(ciphertext), "ciphertext must never be co-located with the key");
  }

  @Test
  void store_key_persists_a_supplied_key_for_migration(@TempDir Path dir) {
    // Migration path: a legacy key pulled out of PluginSettings is handed to the store, which must
    // persist it (0600) so existing ciphertext stays decryptable, and reload it identically.
    byte[] legacy = TokenCipher.newKey();
    FileTokenKeyStore store = new FileTokenKeyStore(dir);
    assertFalse(store.exists());

    store.storeKey(legacy);

    assertTrue(store.exists());
    byte[] reloaded = new FileTokenKeyStore(dir).loadOrCreateKey();
    assertArrayEquals(legacy, reloaded, "the migrated key must survive a reload unchanged");

    // And a token encrypted under the legacy key still decrypts after migration to the file store.
    String enc = new TokenCipher(legacy).encrypt("glpat-legacy");
    assertEquals("glpat-legacy", new TokenCipher(reloaded).decrypt(enc));
  }

  @Test
  void concurrent_first_use_from_many_fresh_instances_mints_exactly_one_key(@TempDir Path dir)
      throws Exception {
    // Mirror AdminConfig's usage: a FRESH FileTokenKeyStore per call (a per-instance lock would not
    // serialize these). Many threads racing first-use must converge on ONE key and ONE file, and the
    // key file must never be observed present-but-empty.
    int threads = 24;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    AtomicBoolean sawEmpty = new AtomicBoolean(false);
    Path keyFile = dir.resolve("likec4-token.key");

    // Watcher: while creation races, any time the key file exists it must already be 32 bytes.
    AtomicBoolean watch = new AtomicBoolean(true);
    Thread watcher = new Thread(() -> {
      while (watch.get()) {
        try {
          if (Files.exists(keyFile) && Files.size(keyFile) == 0) {
            sawEmpty.set(true);
          }
        } catch (java.io.IOException ignored) {
          // file may vanish between exists() and size() across a rename — not an empty observation
        }
      }
    });
    watcher.setDaemon(true);
    watcher.start();

    try {
      List<Future<byte[]>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        Callable<byte[]> task = () -> {
          start.await();
          return new FileTokenKeyStore(dir).loadOrCreateKey(); // fresh instance every time
        };
        futures.add(pool.submit(task));
      }
      start.countDown();

      byte[] reference = futures.get(0).get(10, TimeUnit.SECONDS);
      for (Future<byte[]> f : futures) {
        assertArrayEquals(reference, f.get(10, TimeUnit.SECONDS),
            "every concurrent caller must observe the identical key");
      }
    } finally {
      watch.set(false);
      pool.shutdownNow();
    }

    assertFalse(sawEmpty.get(), "the key file must never be observed present-but-empty");

    try (Stream<Path> s = Files.list(dir)) {
      long fileCount = s.filter(Files::isRegularFile).count();
      assertEquals(1, fileCount, "exactly one file (no leftover temp files) must remain");
    }
    assertEquals(32, Files.size(keyFile), "the single persisted key is a 32-byte AES-256 key");
  }

  @Test
  void tightens_a_preexisting_key_file_that_arrived_group_or_world_readable(@TempDir Path dir) throws Exception {
    // A key created by this class is 0600, but one restored/copied out-of-band could be group/world
    // readable (0644) — which silently defeats the separate-key-from-ciphertext threat model. Loading
    // such a file must re-assert owner-only (0600), correcting the exposure rather than using it as-is.
    Path keyFile = dir.resolve("likec4-token.key");
    Files.write(keyFile, TokenCipher.newKey());
    assumeTrue(keyFile.getFileSystem().supportedFileAttributeViews().contains("posix"),
        "POSIX permission tightening is only meaningful on a POSIX filesystem");
    Files.setPosixFilePermissions(keyFile,
        java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--")); // 0644, group/other readable

    byte[] key = new FileTokenKeyStore(dir).loadOrCreateKey();

    assertEquals(32, key.length, "the valid key is still loaded");
    assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        Files.getPosixFilePermissions(keyFile),
        "a pre-existing group/other-readable key file must be tightened back to 0600 on load");
  }

  @Test
  void rejects_an_existing_key_file_of_invalid_length_with_a_clear_error(@TempDir Path dir) throws Exception {
    // A truncated/empty/foreign key file (e.g. a botched external restore) must fail loudly and clearly,
    // naming the key file, rather than surfacing later and obscurely inside TokenCipher's length check.
    Path keyFile = dir.resolve("likec4-token.key");
    Files.write(keyFile, new byte[10]); // not a 16/24/32-byte AES key
    FileTokenKeyStore store = new FileTokenKeyStore(dir);
    IllegalStateException ex = assertThrows(IllegalStateException.class, store::loadOrCreateKey);
    assertTrue(ex.getMessage().contains(keyFile.toString()), "the error must name the offending key file");
  }

  @Test
  void store_key_rejects_an_invalid_length_key_before_writing_the_file(@TempDir Path dir) throws Exception {
    // Defence-in-depth: storeKey must reject a non-AES-length key (e.g. a corrupt legacy value handed to
    // the one-time migration) BEFORE it writes anything. Otherwise a bad key lands on disk AND the caller
    // typically deletes its only other copy — every later loadOrCreateKey then rejects the file, wedging
    // decryption permanently. The rejection mirrors loadOrCreateKey's length check.
    FileTokenKeyStore store = new FileTokenKeyStore(dir);
    assertThrows(IllegalStateException.class, () -> store.storeKey(new byte[10]));
    assertFalse(store.exists(), "an invalid-length key must not be written to disk");
    try (Stream<Path> s = Files.list(dir)) {
      assertEquals(0, s.filter(Files::isRegularFile).count(),
          "no key file or leftover temp file may be left behind on rejection");
    }
  }

  @Test
  void store_key_overwrites_with_owner_only_permissions(@TempDir Path dir) {
    FileTokenKeyStore store = new FileTokenKeyStore(dir);
    store.storeKey(TokenCipher.newKey());
    Path keyFile = store.keyFile();
    assumeTrue(keyFile.getFileSystem().supportedFileAttributeViews().contains("posix"));
    try {
      assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
          Files.getPosixFilePermissions(keyFile));
    } catch (java.io.IOException e) {
      throw new RuntimeException(e);
    }
    assertNotEquals(0, keyFile.toFile().length());
  }
}
