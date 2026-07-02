package com.phrontizo.likec4.source;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM symmetric encryption for the GitLab service token. Output is base64(iv || ciphertext||tag).
 *
 * <p>Thread-safe and intended to be reused as a singleton: the {@link SecretKey} is immutable, the
 * {@link SecureRandom} is thread-safe, and a fresh {@link Cipher} is obtained per call (Cipher itself is
 * stateful and must never be shared across concurrent operations).
 *
 * <p><b>Nonce-reuse bound (intended low-volume use only):</b> each {@link #encrypt} draws a fresh random
 * 96-bit IV. Random GCM nonces carry a birthday bound — after roughly 2<sup>32</sup> encryptions under a
 * single key the probability of a nonce collision (catastrophic for GCM) stops being negligible. This is
 * NOT enforced or counted in code because the sole caller encrypts the GitLab token only on an admin
 * config-save (a handful of times over a key's lifetime), so the bound is never approached. Do not reuse
 * this class for high-volume per-message encryption without adding a deterministic/counter nonce or a
 * key-rotation trigger.
 */
public final class TokenCipher {

  /**
   * A stored token ciphertext could not be decrypted — a lost/rotated key, or a tampered/oversized
   * blob. A subtype of {@link IllegalStateException} so existing callers and tests that catch/expect
   * {@code IllegalStateException} are unaffected, while a caller that needs to distinguish a local
   * key/config fault from an upstream outage (e.g. to map it to a config-error status rather than a
   * misleading "cannot reach repository") can catch this specific type.
   */
  public static final class DecryptException extends IllegalStateException {
    public DecryptException(String message) { super(message); }
    public DecryptException(String message, Throwable cause) { super(message, cause); }
  }

  private static final String TRANSFORM = "AES/GCM/NoPadding";
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  /**
   * Upper bound on the base64 ciphertext length accepted by {@link #decrypt}. A real encrypted GitLab
   * PAT is ~100 base64 chars; 8 KiB allows for any plausible token while rejecting a multi-megabyte
   * blob (DB tampering / a botched settings migration) before {@code Base64.decode} allocates an
   * unbounded {@code byte[]}. The token store is not covered by the GitLab-archive size caps.
   */
  private static final int MAX_CIPHERTEXT_CHARS = 8192;

  private final SecretKey key;
  private final SecureRandom rng = new SecureRandom();

  public TokenCipher(byte[] keyBytes) {
    if (keyBytes == null
        || !(keyBytes.length == 16 || keyBytes.length == 24 || keyBytes.length == 32)) {
      throw new IllegalArgumentException("AES key must be 128/192/256-bit");
    }
    this.key = new SecretKeySpec(keyBytes, "AES");
  }

  public static byte[] newKey() {
    try {
      KeyGenerator kg = KeyGenerator.getInstance("AES");
      kg.init(256);
      return kg.generateKey().getEncoded();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES keygen unavailable", e);
    }
  }

  public String encrypt(String plaintext) {
    Objects.requireNonNull(plaintext, "plaintext");
    try {
      byte[] iv = new byte[IV_BYTES];
      rng.nextBytes(iv);
      Cipher c = Cipher.getInstance(TRANSFORM);
      c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] out = new byte[iv.length + ct.length];
      System.arraycopy(iv, 0, out, 0, iv.length);
      System.arraycopy(ct, 0, out, iv.length, ct.length);
      return Base64.getEncoder().encodeToString(out);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("encrypt failed", e);
    }
  }

  public String decrypt(String base64) {
    Objects.requireNonNull(base64, "ciphertext");
    if (base64.length() > MAX_CIPHERTEXT_CHARS) {
      throw new DecryptException("decrypt failed (ciphertext too large)");
    }
    byte[] in;
    // Scope the IllegalArgumentException catch to JUST the base64 decode. Base64.decode throws IAE on a
    // non-base64 stored value (DB corruption / a botched migration) — a genuine "tampered blob" outcome.
    // The cipher operations below can ALSO throw IllegalArgumentException (e.g. a bad GCMParameterSpec),
    // and folding them into one broad catch would mislabel a future programming error in the IV/tag
    // slicing as "tampered ciphertext"; keeping the catches separate lets such a defect surface as its
    // own exception instead of being silently absorbed here.
    try {
      in = Base64.getDecoder().decode(base64);
    } catch (IllegalArgumentException e) {
      throw new DecryptException("decrypt failed (bad key or tampered ciphertext)", e);
    }
    // A valid payload is iv(12) || ciphertext || tag(16): anything shorter than iv+tag cannot be
    // authentic. Guard explicitly so the rejection of a truncated blob does not rest on the subtle
    // zero-pad / from>to edge behaviour of Arrays.copyOfRange below.
    if (in.length < IV_BYTES + TAG_BITS / 8) {
      throw new DecryptException("decrypt failed (bad key or tampered ciphertext)");
    }
    try {
      byte[] iv = Arrays.copyOfRange(in, 0, IV_BYTES);
      byte[] ct = Arrays.copyOfRange(in, IV_BYTES, in.length);
      Cipher c = Cipher.getInstance(TRANSFORM);
      c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      return new String(c.doFinal(ct), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new DecryptException("decrypt failed (bad key or tampered ciphertext)", e);
    }
  }
}
