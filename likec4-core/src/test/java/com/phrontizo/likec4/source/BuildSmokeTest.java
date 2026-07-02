package com.phrontizo.likec4.source;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BuildSmokeTest {
  @Test
  void toolchain_builds_on_the_documented_java_21_floor_or_newer() {
    // The target platform is Java 21 (Confluence 10.2.13 / CLAUDE.md). Assert that floor, not 17 —
    // a stale >=17 check would pass on an under-spec JDK and fail to guard the documented platform.
    assertTrue(Runtime.version().feature() >= 21,
        "build/test must run on JDK 21+ (the Confluence 10.2.13 platform); was " + Runtime.version());
  }
}
