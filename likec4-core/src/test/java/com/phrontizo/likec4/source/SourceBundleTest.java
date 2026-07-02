package com.phrontizo.likec4.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SourceBundleTest {

  @Test
  void copies_files_defensively_so_later_mutation_does_not_leak_in() {
    Map<String, String> src = new HashMap<>();
    src.put("model.likec4", "m");
    SourceBundle b = new SourceBundle("0123456789abcdef0123456789abcdef01234567", src);
    src.put("sneaky.likec4", "x"); // mutate the source map AFTER construction
    assertEquals(Map.of("model.likec4", "m"), b.files(), "the bundle must hold an immutable copy");
    assertThrows(UnsupportedOperationException.class, () -> b.files().put("k", "v"));
  }

  @Test
  void rejects_a_null_sha() {
    NullPointerException ex =
        assertThrows(NullPointerException.class, () -> new SourceBundle(null, Map.of()));
    assertTrue(ex.getMessage() != null && ex.getMessage().contains("sha"), "was: " + ex.getMessage());
  }

  @Test
  void rejects_a_null_files_map() {
    assertThrows(NullPointerException.class,
        () -> new SourceBundle("0123456789abcdef0123456789abcdef01234567", null));
  }
}
