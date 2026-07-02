package com.phrontizo.likec4.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class TokenCipherTest {
  @Test
  void decrypts_a_pinned_known_answer_vector_locking_the_wire_format() {
    // A known-answer test (KAT) that locks the persisted wire format: base64( iv(12) || ciphertext ||
    // tag(16) ), AES-256-GCM with a 128-bit tag. Every OTHER test generates a RANDOM key, so encrypt and
    // decrypt could drift together (reordered iv/tag, a changed tag length, a wrong key size) and still
    // round-trip — hiding a format break that would make already-stored tokens undecryptable in the field.
    // This vector was produced once by a reference AES-256-GCM with a FIXED key (0x00..0x1f) and FIXED IV
    // (0xa0..0xab); decrypting it pins that exact layout. If the format is ever changed intentionally, this
    // vector must be regenerated AND any stored ciphertext migrated.
    byte[] key = HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    TokenCipher c = new TokenCipher(key);
    String blob = "oKGio6SlpqeoqaqrgXQMTDHmSf42SPG2ZA6vrF2ebiGqhXpdpDwezgB0nTcQFZfhMbJVrx8ODA==";
    assertEquals("glpat-KAT-vector-2718281828", c.decrypt(blob));
  }

  @Test
  void round_trips_a_token() {
    TokenCipher c = new TokenCipher(TokenCipher.newKey());
    String enc = c.encrypt("glpat-secret-123");
    assertEquals("glpat-secret-123", c.decrypt(enc));
  }

  @Test
  void uses_a_random_iv_so_ciphertexts_differ() {
    TokenCipher c = new TokenCipher(TokenCipher.newKey());
    assertNotEquals(c.encrypt("same"), c.encrypt("same"));
  }

  @Test
  void rejects_a_key_of_the_wrong_length() {
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> new TokenCipher(new byte[10]));
    assertTrue(ex.getMessage().contains("AES key"), "was: " + ex.getMessage());
  }

  @Test
  void rejects_a_null_key() {
    assertThrows(IllegalArgumentException.class, () -> new TokenCipher(null));
  }

  @Test
  void accepts_a_256_bit_key() {
    TokenCipher c = new TokenCipher(new byte[32]);
    assertEquals("ok", c.decrypt(c.encrypt("ok")));
  }

  @Test
  void rejects_malformed_ciphertext_input() {
    TokenCipher c = new TokenCipher(TokenCipher.newKey());
    // Too short to even contain the 12-byte IV: rejected by the explicit iv+tag length guard. Thrown as
    // the specific DecryptException (a subtype of IllegalStateException) so a caller can distinguish a
    // key/ciphertext fault from an upstream outage; assert the concrete type to pin that contract.
    TokenCipher.DecryptException tooShort =
        assertThrows(TokenCipher.DecryptException.class, () -> c.decrypt("AAAA"));
    assertTrue(tooShort.getMessage().contains("decrypt failed"), "was: " + tooShort.getMessage());
    // Not valid base64 at all: the decoder throws IllegalArgumentException → wrapped, not leaked raw.
    TokenCipher.DecryptException notB64 =
        assertThrows(TokenCipher.DecryptException.class, () -> c.decrypt("@@@ not base64 @@@"));
    assertTrue(notB64.getMessage().contains("decrypt failed"), "was: " + notB64.getMessage());
  }

  @Test
  void rejects_ciphertext_that_is_exactly_the_iv_length_with_an_empty_payload() {
    TokenCipher c = new TokenCipher(TokenCipher.newKey());
    // Exactly the 12-byte IV length: an empty ciphertext+tag — an off-by-one boundary the explicit
    // iv+tag length guard must reject as a wrapped "decrypt failed", not throw something unhandled.
    String twelveBytes = java.util.Base64.getEncoder().encodeToString(new byte[12]);
    IllegalStateException ex = assertThrows(IllegalStateException.class, () -> c.decrypt(twelveBytes));
    assertTrue(ex.getMessage().contains("decrypt failed"), "was: " + ex.getMessage());
  }

  @Test
  void rejects_ciphertext_with_an_iv_but_a_truncated_tag() {
    TokenCipher c = new TokenCipher(TokenCipher.newKey());
    // 20 bytes = a full 12-byte IV but only 8 bytes of ciphertext+tag, below the 16-byte GCM tag. The
    // explicit iv+tag guard rejects it up front (rather than relying on GCM's own AEADBadTagException),
    // making the minimum-payload contract self-evident.
    String twentyBytes = java.util.Base64.getEncoder().encodeToString(new byte[20]);
    IllegalStateException ex = assertThrows(IllegalStateException.class, () -> c.decrypt(twentyBytes));
    assertTrue(ex.getMessage().contains("decrypt failed"), "was: " + ex.getMessage());
  }

  @Test
  void decrypt_rejects_null_with_a_named_error_not_a_deep_npe() {
    TokenCipher c = new TokenCipher(TokenCipher.newKey());
    // A null stored ciphertext is a caller contract violation, not "tampered ciphertext". It must
    // surface as a clear, named NPE (requireNonNull) rather than escaping uncaught from deep inside
    // Base64.decode(null) — which is neither caught by the GeneralSecurityException|IAE clause nor
    // diagnosable. (Mirrors RefShaCache's named-argument fail-fast.)
    NullPointerException ex = assertThrows(NullPointerException.class, () -> c.decrypt(null));
    assertTrue(ex.getMessage() != null && ex.getMessage().contains("ciphertext"), "was: " + ex.getMessage());
  }

  @Test
  void encrypt_rejects_null_with_a_named_error() {
    TokenCipher c = new TokenCipher(TokenCipher.newKey());
    NullPointerException ex = assertThrows(NullPointerException.class, () -> c.encrypt(null));
    assertTrue(ex.getMessage() != null && ex.getMessage().contains("plaintext"), "was: " + ex.getMessage());
  }

  @Test
  void decrypt_rejects_an_oversized_ciphertext_before_decoding() {
    TokenCipher c = new TokenCipher(TokenCipher.newKey());
    // The ciphertext is read from the (DB-backed) token store. A real encrypted PAT base64 is ~100
    // chars; a multi-megabyte blob (DB tampering / botched migration) must be rejected up front
    // rather than driving an unbounded byte[] allocation via Base64.decode. Cheap memory-amplification
    // guard, wrapped like every other malformed input.
    String huge = "A".repeat(1_000_000);
    IllegalStateException ex = assertThrows(IllegalStateException.class, () -> c.decrypt(huge));
    assertTrue(ex.getMessage().contains("decrypt failed"), "was: " + ex.getMessage());
    // The rejection must be the up-front size guard, not a decode-then-auth-fail (which would have
    // already allocated the multi-hundred-KB byte[] this guard exists to prevent).
    assertTrue(ex.getMessage().contains("too large"), "expected size guard, was: " + ex.getMessage());
  }

  @Test
  void round_trips_an_empty_plaintext() {
    // Empty plaintext is a valid GCM case: the output is IV(12) || tag(16) with a zero-length
    // ciphertext. It must round-trip to "" rather than tripping the minimum-payload guard (28 bytes is
    // exactly IV+tag, which the `< IV+tag` guard admits).
    TokenCipher c = new TokenCipher(TokenCipher.newKey());
    String enc = c.encrypt("");
    assertEquals("", c.decrypt(enc));
  }

  @Test
  void the_max_ciphertext_length_gate_is_an_inclusive_boundary() {
    TokenCipher c = new TokenCipher(TokenCipher.newKey());
    // Exactly MAX_CIPHERTEXT_CHARS (8192) must PASS the up-front size gate and fall through to decode +
    // auth (which then fails as a normal bad/tampered blob) — NOT be rejected as "too large". This pins
    // the boundary as inclusive so an off-by-one that flipped `>` to `>=` would be caught.
    String atLimit = "A".repeat(8192);
    TokenCipher.DecryptException at =
        assertThrows(TokenCipher.DecryptException.class, () -> c.decrypt(atLimit));
    assertTrue(at.getMessage().contains("decrypt failed"), "was: " + at.getMessage());
    assertFalse(at.getMessage().contains("too large"),
        "8192 chars must clear the size gate, not trip it: " + at.getMessage());
    // One char over the limit (8193) must be rejected by the size gate BEFORE Base64.decode allocates.
    String overLimit = "A".repeat(8193);
    TokenCipher.DecryptException over =
        assertThrows(TokenCipher.DecryptException.class, () -> c.decrypt(overLimit));
    assertTrue(over.getMessage().contains("too large"), "was: " + over.getMessage());
  }

  @Test
  void rejects_tampered_ciphertext() {
    byte[] key = TokenCipher.newKey();
    TokenCipher c = new TokenCipher(key);
    String enc = c.encrypt("glpat-secret-123");
    // Flip a character in the base64 payload near the end (the GCM tag) — auth must fail.
    // Use index -5: sits in the last *complete* (non-padded) group so the bit flip lands on
    // an actual GCM-tag byte, not the zero-filler bits that base64 appends before the '='.
    char[] chars = enc.toCharArray();
    chars[chars.length - 5] = (chars[chars.length - 5] == 'A') ? 'B' : 'A';
    String tampered = new String(chars);
    assertThrows(IllegalStateException.class, () -> c.decrypt(tampered));
  }
}
