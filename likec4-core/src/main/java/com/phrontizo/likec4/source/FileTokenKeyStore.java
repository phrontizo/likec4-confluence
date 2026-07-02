package com.phrontizo.likec4.source;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Set;

/**
 * File-backed store for the AES-256 token-encryption key (the KEK for {@link TokenCipher}).
 *
 * <p><b>Why a file:</b> the key is persisted to {@code <dir>/likec4-token.key} with owner-only
 * (0600) permissions, deliberately kept <em>off</em> whatever store holds the ciphertext (in the
 * Confluence wrapper that store is PluginSettings, i.e. the database). Separating the key from the
 * ciphertext means a dump of the ciphertext store <em>alone</em> can no longer decrypt tokens —
 * filesystem access to this key directory is additionally required.
 *
 * <p><b>Threat model / residual risk (v1):</b> key-at-rest now lives on the host filesystem (0600)
 * rather than next to the ciphertext. A DB dump alone is no longer sufficient. The residual risks,
 * stated honestly: (1) an attacker who obtains <em>both</em> the ciphertext store and read access to
 * this key file can still decrypt; (2) the key is held in heap (a JCA {@link javax.crypto.SecretKey}
 * cannot be reliably zeroed and the JVM may copy it), so a heap/core dump or swap of the live process
 * can also yield it. A KMS/HSM (where the key never leaves the security boundary) would close both and
 * is out of scope for v1.
 *
 * <p>This class is intentionally Atlassian-independent (it takes a plain {@link Path}); the wrapper
 * supplies the Confluence home directory.
 */
public final class FileTokenKeyStore {

  private static final System.Logger LOG = System.getLogger(FileTokenKeyStore.class.getName());

  /** Filename of the persisted key inside the configured directory. */
  public static final String KEY_FILENAME = "likec4-token.key";

  private static final Set<PosixFilePermission> OWNER_ONLY =
      EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

  private final Path keyFile;

  /** @param dir directory that holds (or will hold) the key file; typically the Confluence home. */
  public FileTokenKeyStore(Path dir) {
    this.keyFile = dir.resolve(KEY_FILENAME);
  }

  /** The absolute path of the key file backing this store. */
  public Path keyFile() {
    return keyFile;
  }

  /** Whether a key file already exists (used to decide generate-vs-migrate-vs-load). */
  public boolean exists() {
    return Files.exists(keyFile);
  }

  /**
   * Loads the existing AES-256 key, or generates and persists a fresh one (0600) on first use.
   *
   * <p>Creation is atomic and race-free <em>across instances and processes</em> (not merely within
   * one instance): {@code AdminConfig} builds a fresh {@code FileTokenKeyStore} per call, so a
   * per-instance lock would not serialize anything. The new key is written to a temp file in the
   * same directory with owner-only perms, then published via an atomic, create-new operation. If
   * another writer wins the race, the temp is discarded and the winner's key is re-read — so all
   * callers observe the SAME key, and {@link #keyFile} is never momentarily present-but-empty.
   */
  public byte[] loadOrCreateKey() {
    try {
      if (Files.exists(keyFile)) {
        return adoptExistingKey();
      }
      byte[] key = TokenCipher.newKey();
      Path parent = ensureParent();
      Path tmp = newOwnerOnlyTemp(parent);
      try {
        writeAndSync(tmp, key);
        if (publishExclusive(tmp)) {
          syncDir(parent);
          reassertOwnerOnly(keyFile);
          return key;
        }
        // Lost the race: keyFile was published between the exists() check above and publishExclusive.
        // Adopt it via the SAME path as the exists() branch — crucially INCLUDING the permission
        // tighten: the create race is normally lost to a peer (which always publishes 0600), but it
        // can also be lost to an out-of-band file that landed 0644 in this window, so it must be
        // tightened here too, not just on the exists() branch.
        return adoptExistingKey();
      } finally {
        Files.deleteIfExists(tmp);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("token key store read/create failed: " + keyFile, e);
    }
  }

  /**
   * Adopt an already-present {@link #keyFile} — found at entry OR published by whoever won the create
   * race — by reading + length-validating it and defensively re-asserting owner-only perms. The tighten
   * matters because the file could have arrived out-of-band group/world-readable (an operator copy, a
   * backup tool that recreates it 0644, a restore), which silently defeats the "separate the key from
   * the ciphertext" threat model. Shared by both adopt paths so the tighten can never diverge between
   * them.
   */
  private byte[] adoptExistingKey() throws IOException {
    byte[] existing = validatedKey(Files.readAllBytes(keyFile));
    tightenIfGroupOrOtherAccessible(keyFile);
    return existing;
  }

  /**
   * Validates a key read back from disk: it must be a valid AES key length (128/192/256-bit). A
   * truncated, empty, or foreign key file (e.g. from a botched external restore that this class did not
   * write) is rejected here with a clear, key-file-named error rather than surfacing later and less
   * clearly inside {@link TokenCipher}'s constructor.
   */
  private byte[] validatedKey(byte[] key) {
    if (key.length != 16 && key.length != 24 && key.length != 32) {
      throw new IllegalStateException(
          "token key file is not a valid AES key (" + key.length + " bytes): " + keyFile);
    }
    return key;
  }

  /**
   * Persists a caller-supplied key (0600), overwriting any existing file. Used by the wrapper's
   * one-time migration to move a legacy key off PluginSettings onto the filesystem so that already
   * encrypted tokens stay decryptable. Overwriting-by-design, but still atomic (temp + atomic move):
   * there is never a present-but-empty {@link #keyFile}.
   */
  public void storeKey(byte[] key) {
    // Reject an invalid-length key BEFORE writing anything: a corrupt value must not land on disk (the
    // migration caller typically removes its only other copy right after, so a bad key file here would
    // wedge every later loadOrCreateKey). Mirrors loadOrCreateKey's read-back length check.
    validatedKey(key);
    try {
      Path parent = ensureParent();
      Path tmp = newOwnerOnlyTemp(parent);
      try {
        writeAndSync(tmp, key);
        atomicReplace(tmp, keyFile);
        syncDir(parent);
        reassertOwnerOnly(keyFile);
      } finally {
        Files.deleteIfExists(tmp);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("token key store write failed: " + keyFile, e);
    }
  }

  private Path ensureParent() throws IOException {
    Path parent = keyFile.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
      return parent;
    }
    return keyFile.toAbsolutePath().getParent();
  }

  private boolean posix() {
    return keyFile.getFileSystem().supportedFileAttributeViews().contains("posix");
  }

  /** A fresh temp file in {@code parent}, created owner-only up-front so the key is never exposed. */
  private Path newOwnerOnlyTemp(Path parent) throws IOException {
    if (posix()) {
      // POSIX (the production Confluence/Linux path): the owner-only mode is applied ATOMICALLY at
      // creation via asFileAttribute, so the key temp is never briefly group/world-readable.
      return Files.createTempFile(parent, KEY_FILENAME, ".tmp",
          PosixFilePermissions.asFileAttribute(OWNER_ONLY));
    }
    // Non-POSIX (best-effort, not the production path): createTempFile has no attribute overload for
    // the File.setReadable/-Writable toggles, so the temp is created under the default umask and then
    // tightened by restrictNonPosix — a brief creation-time window where it may be readable before the
    // key bytes are written into it. Accepted as best-effort on non-POSIX filesystems only.
    Path tmp = Files.createTempFile(parent, KEY_FILENAME, ".tmp");
    restrictNonPosix(tmp);
    return tmp;
  }

  /**
   * Atomically publishes {@code tmp} as {@link #keyFile} with create-new semantics: succeeds only if
   * {@code keyFile} does not yet exist, never overwriting a winner. Returns {@code false} (without
   * throwing) when another writer already created the key file.
   *
   * <p>Implemented with a hard link (the {@code link(2)} primitive is atomic and fails with
   * {@code EEXIST}) so {@code keyFile} appears fully-written in one step — there is no window where
   * it exists but is empty. Falls back to an exclusive move on filesystems without link support.
   */
  private boolean publishExclusive(Path tmp) throws IOException {
    try {
      Files.createLink(keyFile, tmp);
      return true;
    } catch (FileAlreadyExistsException lostRace) {
      return false;
    } catch (UnsupportedOperationException | java.nio.file.FileSystemException linkFailed) {
      // The hard link could not be created. This is the link-unsupported / cross-device (EXDEV) case
      // we want to degrade — but EXDEV's reason string is OS/locale-dependent and not portably
      // distinguishable, so ANY FileSystemException (incl. a genuine I/O/permission error or a vanished
      // parent) falls through to a non-replacing move (create-new) on the same filesystem. That is safe:
      // the move either publishes tmp->keyFile atomically, loses the create-new race
      // (FileAlreadyExistsException -> false), or re-fails on the same condition and propagates the real
      // error — it never silently swallows a true failure.
      try {
        Files.move(tmp, keyFile);
        return true;
      } catch (FileAlreadyExistsException lostRace) {
        return false;
      }
    }
  }

  /** Atomic overwrite (temp -> dest), preferring an atomic move and degrading gracefully. */
  private void atomicReplace(Path tmp, Path dst) throws IOException {
    try {
      Files.move(tmp, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException atomicUnsupported) {
      Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /**
   * If a PRE-EXISTING key file is readable/writable/executable by group or other on POSIX (e.g. an
   * out-of-band restore recreated it 0644), re-assert owner-only (0600) and log a WARNING naming the
   * file. The create/store paths already write 0600, so this only ever corrects a key that arrived
   * permissive out-of-band — closing the "key at rest is group/world-readable" gap the separate-key-
   * from-ciphertext model relies on. Owner-execute alone is not group/other exposure, so it is left as
   * is; a no-op on non-POSIX filesystems.
   */
  private void tightenIfGroupOrOtherAccessible(Path f) throws IOException {
    if (!posix()) return;
    Set<PosixFilePermission> perms = Files.getPosixFilePermissions(f);
    boolean groupOrOther = perms.stream().anyMatch(p ->
        p == PosixFilePermission.GROUP_READ || p == PosixFilePermission.GROUP_WRITE
            || p == PosixFilePermission.GROUP_EXECUTE || p == PosixFilePermission.OTHERS_READ
            || p == PosixFilePermission.OTHERS_WRITE || p == PosixFilePermission.OTHERS_EXECUTE);
    if (groupOrOther) {
      LOG.log(System.Logger.Level.WARNING,
          () -> "token key file had group/other-accessible permissions (" + PosixFilePermissions.toString(perms)
              + "); re-asserting owner-only (0600): " + f);
      Files.setPosixFilePermissions(f, OWNER_ONLY);
    }
  }

  /** Defensively re-assert owner-only perms on POSIX; a no-op elsewhere. */
  private void reassertOwnerOnly(Path f) throws IOException {
    if (posix()) {
      Files.setPosixFilePermissions(f, OWNER_ONLY);
    } else {
      restrictNonPosix(f);
    }
  }

  /**
   * Write {@code key} to {@code tmp} and flush its contents to stable storage BEFORE it is published as
   * {@link #keyFile}. Without the {@code force}, a crash / power-loss after the atomic link/move can leave
   * the key file present but torn or zero-length — the rename's directory metadata reaching disk ahead of
   * the file's data blocks — and because this is the sole KEK, the read-back length check in
   * {@link #validatedKey} then rejects it PERMANENTLY on the next load, wedging decryption of every stored
   * token (fatal on the {@link #storeKey} migration path, which discards its only other copy). The temp is
   * freshly created and empty, but {@code TRUNCATE_EXISTING} is set defensively so the published inode
   * holds exactly the key bytes.
   */
  private static void writeAndSync(Path tmp, byte[] key) throws IOException {
    try (FileChannel ch =
        FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
      ByteBuffer buf = ByteBuffer.wrap(key);
      while (buf.hasRemaining()) {
        ch.write(buf);
      }
      ch.force(true); // fsync contents + file metadata so the published inode always has the complete key
    }
  }

  /**
   * Best-effort fsync of {@code dir} so the newly linked/moved key file's directory ENTRY (its name) is
   * durable too, not only its contents. Opening a directory as a channel is not portable — a
   * JVM/filesystem that rejects it simply skips this; the contents {@code force} in {@link #writeAndSync}
   * is the load-bearing guarantee, and this only closes the narrower window where a crash keeps the file's
   * data but loses the directory entry that names it.
   */
  private static void syncDir(Path dir) {
    if (dir == null) {
      return;
    }
    try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
      ch.force(true);
    } catch (IOException | UnsupportedOperationException ignored) {
      // directory fsync is a portability-dependent nicety; ignore where the platform doesn't allow it
    }
  }

  private static void restrictNonPosix(Path f) {
    java.io.File file = f.toFile();
    // Drop all group/other access, then grant owner-only read/write. Use & (non-short-circuit) so every
    // call runs. On a filesystem that doesn't support these toggles each returns false and the key file
    // may stay more permissive than owner-only — log a warning naming the file rather than silently
    // leaving a token-encryption key group/world-readable. (POSIX — the production Confluence/Linux
    // path — never reaches here; it uses Files.setPosixFilePermissions.)
    boolean ok =
        file.setReadable(false, false)
        & file.setWritable(false, false)
        & file.setExecutable(false, false)
        & file.setReadable(true, true)
        & file.setWritable(true, true);
    if (!ok) {
      LOG.log(System.Logger.Level.WARNING,
          "could not fully restrict token key file permissions to owner-only on this (non-POSIX) "
              + "filesystem; verify it is not group/world-readable: " + f);
    }
  }
}
