# LikeC4 Confluence — Java Server Side Implementation Plan

> **Superseded-platform note (post-2026-06-29):** this is a historical planning record. It was written
> for Confluence **9.2.20** (`javax.*`, Java 17 bytecode, the bootstrapped `~/.local/bin/jmvn`); the
> product was later retargeted to Confluence **10.2.13 / Jakarta EE / Java 21**, built with the committed
> **`./mvnw`**, and jackson/commons-compress were realigned to the platform. Any build command, platform,
> or dependency version below reflects that earlier era — for the current facts see **README.md** and
> **CLAUDE.md**. Kept as the original plan record.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the thin Java server side (spec §4 components 1–5): an Atlassian-independent **`likec4-core`** library that fetches a LikeC4 source subtree from GitLab (filtered, path-safe), caches it (ref→sha short-TTL + source-bundle LRU/single-flight/stale-while-revalidate), encrypts the service token, and validates input (allowlist/traversal); plus a Confluence P2 wrapper (macro, REST resource, AdminConfig, `atlassian-plugin.xml`) that exposes `/resolve` + `/source` to the Plan 2 browser bundle.

**Architecture:** Two Maven modules. **`likec4-core/`** is a standalone JAR (own pom, Maven Central deps only) holding all the logic-heavy, security-critical code — **built and unit-tested here** with the bootstrapped `~/.local/bin/jmvn` (Maven 3.9.9 + Temurin JDK 21, `release 17` bytecode). The repo-root **`pom.xml`** evolves from its Plan 2 stub into the Confluence P2 plugin (packaging `atlassian-plugin`), depending on `likec4-core` and bundling the Plan 2 frontend as a web-resource — **authored and committed but SDK-gated** (it needs amps + a Confluence runtime to build/run, deferred to the Atlassian SDK / Plan 4 Docker, exactly like Plan 2's CI-gated Playwright e2e). The Plan 2 frontend at `src/main/frontend/` stays put.

**Tech Stack:** Java 21 (target 17 bytecode), Maven 3.9.9 via `~/.local/bin/jmvn`, JUnit 5, JDK `java.net.http.HttpClient` + `com.sun.net.httpserver.HttpServer` (mock GitLab in tests), `commons-compress` (tar.gz), `jackson-databind` (GitLab JSON), `javax.crypto` AES-GCM (token). Confluence side (gated): Atlassian P2 SDK (amps `maven-confluence-plugin`), Confluence 9.2.20 + SAL APIs, JAX-RS.

**Two execution tiers (like Plan 2's local-green vs CI-gated split):**
- **Executed here (the green gate):** every `likec4-core` task is TDD with `~/.local/bin/jmvn -pl likec4-core test` (or `cd likec4-core && jmvn test`) passing.
- **Authored + SDK-gated:** the Confluence wrapper tasks write complete, best-effort code (committed) but are **not compiled here** — they need the Atlassian repo's amps packaging + Confluence runtime. Each such task says so explicitly and uses the best-known Confluence APIs; that is intentional, not a placeholder (the spike/Plan-2 precedent the writing-plans self-review accepts for genuinely environment-gated code).

---

## Scope

**In scope (this plan):** spec §4 components 1–5, §7 caching, §8 security (token encryption, allowlist, traversal/zip-slip, least privilege, file-type guarantee), §9 server failure mapping, §11 Java fast-layer unit tests (`GitLabSourceClient`, `CacheLayer`, macro HTML output, REST auth/allowlist/traversal, token round-trip).

**Out of scope (Plan 4):** the Docker Confluence+Postgres+mock-GitLab stack, UPM install/upgrade, performance/k6, the timebomb-licence harness, and the live e2e/contract test against recorded GitLab responses. The Confluence wrapper's *compilation/packaging/runtime* verification lands there too.

**Carried contract from Plan 2 (the browser bundle consumes these):**
- `GET /rest/likec4/1.0/resolve?project&ref` → `{ "sha": "<40-hex>" }`
- `GET /rest/likec4/1.0/source?project&ref&path` → `{ "sha": "<40-hex>", "files": { "<relPath>": "<content>" } }` — only `*.c4` / `*.likec4` / `.likec4/*.likec4.snap`, relative paths preserved.
- The macro emits `<div class="likec4-diagram" data-project data-ref data-path data-view data-instance>` and requires the web-resource built from `src/main/frontend` into `src/main/resources/likec4-web/`.

---

## Module layout

```
confluence-likec4/
├── pom.xml                      # Plan 2 STUB → becomes the Confluence P2 plugin (GATED)
├── src/main/frontend/           # Plan 2 (unchanged) — built into ↓
├── src/main/resources/likec4-web/   # Plan 2 build output (gitignored)
├── src/main/java/com/phrontizo/confluence/likec4/   # wrapper Java (GATED): macro, rest, admin
├── src/main/resources/atlassian-plugin.xml          # wrapper (GATED)
├── src/test/java/...            # wrapper tests (GATED)
└── likec4-core/                 # NEW standalone module — BUILT & TESTED HERE
    ├── pom.xml                  # jar, release 17, Maven Central deps only
    ├── src/main/java/com/phrontizo/likec4/source/
    │   ├── SourceFile.java, SourceBundle.java     # value types
    │   ├── LikeC4FileFilter.java                  # keep .c4/.likec4/.likec4.snap
    │   ├── PathSafety.java                        # zip/tar-slip guard
    │   ├── GitLabArchiveExtractor.java            # tar.gz → SourceBundle
    │   ├── GitLabSourceClient.java                # resolve(ref→sha) + fetchSubtree
    │   ├── GitLabException.java                   # typed errors (status)
    │   ├── TokenCipher.java                       # AES-GCM encrypt/decrypt
    │   ├── ProjectAllowlist.java                  # group/prefix match
    │   ├── InputValidation.java                   # ref/path sanitise
    │   └── cache/
    │        ├── Clock.java                        # testable time
    │        ├── RefShaCache.java                  # ref→sha, short TTL
    │        └── SourceBundleCache.java            # (project,sha,path)→bundle; LRU + single-flight + SWR + disk
    └── src/test/java/com/phrontizo/likec4/source/...
```

The root `pom.xml` does NOT make `likec4-core` a `<module>` (no reactor) — `likec4-core` is a self-contained project built/installed independently (`cd likec4-core && jmvn install`), and the root plugin declares it as a normal `<dependency>`. This keeps the executable core's build free of any Atlassian dependency.

---

## File Structure (responsibilities)

`likec4-core` (executed):
- `SourceFile` / `SourceBundle` — immutable records: a file is `(relPath, content)`; a bundle is `(sha, Map<relPath,content>)`.
- `LikeC4FileFilter` — single rule `keep(name)` = ends with `.c4` | `.likec4` | `.likec4.snap`. The §8 file-type guarantee lives here.
- `PathSafety` — `safeRelative(entryName, stripPrefix)`: reject `..` segments / absolute / drive paths; return the repo-relative POSIX path or empty if filtered.
- `GitLabArchiveExtractor` — pure: `extract(InputStream tarGz, String pathPrefix) → Map<relPath,content>` applying filter + path-safety; strips GitLab's `<top>/` archive dir and the requested `path` prefix.
- `GitLabSourceClient` — HTTP: `resolveSha(project, ref) → sha`, `fetchSubtree(project, sha, path) → SourceBundle`; injectable `HttpClient` + base URL + token supplier; Jackson for the commit JSON; maps non-2xx to `GitLabException`.
- `TokenCipher` — `encrypt(plaintext) → base64(iv|ct)`, `decrypt(...)`; AES-256-GCM; key supplied as bytes (admin-config-managed).
- `ProjectAllowlist` — `isAllowed(project)` against configured group/prefix entries.
- `InputValidation` — `sanitizeRef` / `sanitizePath`: allow safe charset, reject traversal/control chars; used before any HTTP call.
- `cache/Clock` — `nowMillis()`; real + manual (test) impls.
- `cache/RefShaCache` — `get(project, ref, loader) → sha` with per-entry TTL (default 60s); full-40-hex refs cached permanently.
- `cache/SourceBundleCache` — `get(project, sha, path, loader) → SourceBundle`: bounded LRU, single-flight (per-key lock coalesces concurrent misses), stale-while-revalidate (serve last-good on loader failure), disk-backed (survives restart).

Confluence wrapper (gated):
- `LikeC4DiagramMacro` (xhtml `Macro`) — validate params → emit the `data-*` div + require web-resource. §4 component 1.
- `SourceRestResource` (JAX-RS `@Path("/")`; the `1.0` comes from `<rest version="1.0">`) — `/resolve`, `/source`; authenticated user required; allowlist-checked; delegates to `GitLabSourceClient` + caches. §4 component 2.
- `AdminConfigResource` + `AdminConfig` — GitLab base URL, encrypted token (via `TokenCipher`), allowlist, TTLs in `PluginSettings`; admin REST + cache-flush. §4 component 5.
- `atlassian-plugin.xml` — wires macro, REST, web-resource (from the Vite build), admin page, i18n. §4.
- root `pom.xml` — amps `maven-confluence-plugin` for Confluence 9.2.20, `likec4-core` dependency, frontend build hook (npm) before package.

---

## Task 1: Scaffold the `likec4-core` module

**Files:**
- Create: `likec4-core/pom.xml`
- Create: `likec4-core/src/test/java/com/phrontizo/likec4/source/BuildSmokeTest.java`

- [ ] **Step 1: Create `likec4-core/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.phrontizo.confluence</groupId>
  <artifactId>likec4-source-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>jar</packaging>
  <name>LikeC4 Source Core</name>
  <description>Atlassian-independent GitLab source fetch, caching, token crypto, and validation.</description>

  <properties>
    <maven.compiler.release>17</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>

  <dependencies>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
      <version>2.17.2</version>
    </dependency>
    <dependency>
      <groupId>org.apache.commons</groupId>
      <artifactId>commons-compress</artifactId>
      <version>1.26.1</version>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>5.10.2</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.2.5</version>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Create a build smoke test — `likec4-core/src/test/java/com/phrontizo/likec4/source/BuildSmokeTest.java`**

```java
package com.phrontizo.likec4.source;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BuildSmokeTest {
  @Test
  void toolchain_builds_on_java_17_or_newer() {
    assertTrue(Runtime.version().feature() >= 17);
  }
}
```

- [ ] **Step 3: Build and run with the bootstrapped Maven (first run downloads deps from Maven Central)**

Run: `cd likec4-core && ~/.local/bin/jmvn -B test`
Expected: `BUILD SUCCESS`, `Tests run: 1, Failures: 0, Errors: 0`. (Subsequent runs can use `~/.local/bin/jmvn -B -o test` offline.) If Maven cannot reach Maven Central, STOP and report BLOCKED (the whole core-execution tier depends on it).

- [ ] **Step 4: Commit**

```bash
git add likec4-core/pom.xml likec4-core/src/test/java/com/phrontizo/likec4/source/BuildSmokeTest.java
git commit -m "plan3: scaffold likec4-core module (JDK 21 / release 17)"
```
End every commit message with a blank line then:
`Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`

---

## Task 2: Value types + the file-type guarantee (`LikeC4FileFilter`)

**Files:**
- Create: `likec4-core/src/main/java/com/phrontizo/likec4/source/SourceFile.java`
- Create: `likec4-core/src/main/java/com/phrontizo/likec4/source/SourceBundle.java`
- Create: `likec4-core/src/main/java/com/phrontizo/likec4/source/LikeC4FileFilter.java`
- Test: `likec4-core/src/test/java/com/phrontizo/likec4/source/LikeC4FileFilterTest.java`

- [ ] **Step 1: Write the failing test — `LikeC4FileFilterTest.java`**

```java
package com.phrontizo.likec4.source;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LikeC4FileFilterTest {
  @Test
  void keeps_only_c4_likec4_and_snapshots() {
    assertTrue(LikeC4FileFilter.keep("model.c4"));
    assertTrue(LikeC4FileFilter.keep("model.likec4"));
    assertTrue(LikeC4FileFilter.keep("index.likec4.snap"));
    assertTrue(LikeC4FileFilter.keep(".likec4/index.likec4.snap"));
  }

  @Test
  void drops_everything_else_including_secrets_and_bare_snap() {
    assertFalse(LikeC4FileFilter.keep("secrets.env"));
    assertFalse(LikeC4FileFilter.keep(".env"));
    assertFalse(LikeC4FileFilter.keep("README.md"));
    assertFalse(LikeC4FileFilter.keep(".gitlab-ci.yml"));
    assertFalse(LikeC4FileFilter.keep("layout.snap")); // bare .snap, not .likec4.snap
    assertFalse(LikeC4FileFilter.keep("model.c4.bak"));
  }
}
```

- [ ] **Step 2: Run to confirm it fails**

Run: `cd likec4-core && ~/.local/bin/jmvn -B -o test`
Expected: compilation failure (`LikeC4FileFilter` not found). Confirm before implementing.

- [ ] **Step 3: Create `SourceFile.java`**

```java
package com.phrontizo.likec4.source;

/** One delivered source file: a repo-relative POSIX path and its UTF-8 text content. */
public record SourceFile(String path, String content) {}
```

- [ ] **Step 4: Create `SourceBundle.java`**

```java
package com.phrontizo.likec4.source;

import java.util.Map;

/** The filtered LikeC4 source subtree at a commit: sha plus relPath→content (immutable copy). */
public record SourceBundle(String sha, Map<String, String> files) {
  public SourceBundle {
    files = Map.copyOf(files);
  }
}
```

- [ ] **Step 5: Create `LikeC4FileFilter.java`** (the §8 file-type guarantee)

```java
package com.phrontizo.likec4.source;

/** The ONLY files ever fetched/delivered: LikeC4 source and manual-layout snapshots. */
public final class LikeC4FileFilter {
  private LikeC4FileFilter() {}

  public static boolean keep(String name) {
    return name.endsWith(".c4") || name.endsWith(".likec4") || name.endsWith(".likec4.snap");
  }
}
```

- [ ] **Step 6: Run to confirm it passes**

Run: `cd likec4-core && ~/.local/bin/jmvn -B -o test`
Expected: `BUILD SUCCESS`, all tests pass.

- [ ] **Step 7: Commit**

```bash
git add likec4-core/src/main/java/com/phrontizo/likec4/source/SourceFile.java likec4-core/src/main/java/com/phrontizo/likec4/source/SourceBundle.java likec4-core/src/main/java/com/phrontizo/likec4/source/LikeC4FileFilter.java likec4-core/src/test/java/com/phrontizo/likec4/source/LikeC4FileFilterTest.java
git commit -m "plan3: source value types + LikeC4 file-type filter"
```

---

## Task 3: Path safety (zip/tar-slip guard) — `PathSafety`

**Files:**
- Create: `likec4-core/src/main/java/com/phrontizo/likec4/source/PathSafety.java`
- Test: `likec4-core/src/test/java/com/phrontizo/likec4/source/PathSafetyTest.java`

GitLab archive entries look like `<project>-<ref>-<sha>/<path>/<file>`. `safeRelative` strips the single top dir and the requested `path` prefix, returning the project-relative POSIX path — or empty if the entry is a directory, absolute, traversing (`..`), or outside the requested subtree.

- [ ] **Step 1: Write the failing test — `PathSafetyTest.java`**

```java
package com.phrontizo.likec4.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class PathSafetyTest {
  @Test
  void strips_top_dir_and_path_prefix() {
    assertEquals(Optional.of("model.likec4"),
        PathSafety.safeRelative("myrepo-main-abc123/diagrams/model.likec4", "diagrams"));
    assertEquals(Optional.of(".likec4/index.likec4.snap"),
        PathSafety.safeRelative("myrepo-main-abc123/diagrams/.likec4/index.likec4.snap", "diagrams"));
  }

  @Test
  void empty_prefix_keeps_full_subpath() {
    assertEquals(Optional.of("model.likec4"),
        PathSafety.safeRelative("myrepo-main-abc/model.likec4", ""));
    assertEquals(Optional.of("model.likec4"),
        PathSafety.safeRelative("myrepo-main-abc/model.likec4", null));
  }

  @Test
  void rejects_traversal_absolute_dirs_and_outside_subtree() {
    assertTrue(PathSafety.safeRelative("myrepo-main-abc/../etc/passwd", "").isEmpty());
    assertTrue(PathSafety.safeRelative("/etc/passwd", "").isEmpty());
    assertTrue(PathSafety.safeRelative("myrepo-main-abc/diagrams/", "diagrams").isEmpty()); // the dir itself
    assertTrue(PathSafety.safeRelative("myrepo-main-abc/other/x.likec4", "diagrams").isEmpty()); // outside subtree
    assertTrue(PathSafety.safeRelative("myrepo-main-abc/diagrams/sub/", "diagrams").isEmpty()); // nested dir
  }
}
```

- [ ] **Step 2: Run to confirm it fails**

Run: `cd likec4-core && ~/.local/bin/jmvn -B -o test`
Expected: compilation failure (`PathSafety` not found).

- [ ] **Step 3: Create `PathSafety.java`**

```java
package com.phrontizo.likec4.source;

import java.util.Optional;

/** Maps a GitLab archive entry name to a safe project-relative POSIX path, or empty if it must be dropped. */
public final class PathSafety {
  private PathSafety() {}

  public static Optional<String> safeRelative(String entryName, String pathPrefix) {
    String norm = entryName.replace('\\', '/');
    if (norm.startsWith("/")) return Optional.empty();                 // absolute
    for (String seg : norm.split("/", -1)) {
      if (seg.equals("..")) return Optional.empty();                   // traversal
    }
    int firstSlash = norm.indexOf('/');
    if (firstSlash < 0) return Optional.empty();                       // no top dir
    String afterTop = norm.substring(firstSlash + 1);                  // strip "<top>/"
    if (afterTop.isEmpty()) return Optional.empty();

    String prefix = pathPrefix == null ? "" : pathPrefix.replace('\\', '/').replaceAll("^/+|/+$", "");
    if (!prefix.isEmpty()) {
      if (afterTop.equals(prefix)) return Optional.empty();            // the requested dir entry itself
      if (!afterTop.startsWith(prefix + "/")) return Optional.empty(); // outside the requested subtree
      afterTop = afterTop.substring(prefix.length() + 1);
    }
    if (afterTop.isEmpty() || afterTop.endsWith("/")) return Optional.empty(); // directory entry
    return Optional.of(afterTop);
  }
}
```

- [ ] **Step 4: Run to confirm it passes**

Run: `cd likec4-core && ~/.local/bin/jmvn -B -o test`
Expected: `BUILD SUCCESS`, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add likec4-core/src/main/java/com/phrontizo/likec4/source/PathSafety.java likec4-core/src/test/java/com/phrontizo/likec4/source/PathSafetyTest.java
git commit -m "plan3: tar-slip-safe archive path normalisation"
```

---

## Task 4: GitLab archive extraction — `GitLabArchiveExtractor`

**Files:**
- Create: `likec4-core/src/main/java/com/phrontizo/likec4/source/GitLabArchiveExtractor.java`
- Test: `likec4-core/src/test/java/com/phrontizo/likec4/source/GitLabArchiveExtractorTest.java`

- [ ] **Step 1: Write the failing test — `GitLabArchiveExtractorTest.java`** (builds a tar.gz in-memory, asserts filtering + path stripping)

```java
package com.phrontizo.likec4.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;

class GitLabArchiveExtractorTest {

  private static byte[] tarGz(Map<String, String> entries) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bos))) {
      for (Map.Entry<String, String> en : entries.entrySet()) {
        byte[] data = en.getValue().getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry te = new TarArchiveEntry(en.getKey());
        te.setSize(data.length);
        tar.putArchiveEntry(te);
        tar.write(data);
        tar.closeArchiveEntry();
      }
    }
    return bos.toByteArray();
  }

  @Test
  void keeps_only_likec4_files_relative_to_the_requested_path() throws IOException {
    Map<String, String> entries = new LinkedHashMap<>();
    entries.put("myrepo-main-abc/diagrams/spec.likec4", "spec {}");
    entries.put("myrepo-main-abc/diagrams/.likec4/index.likec4.snap", "{snap}");
    entries.put("myrepo-main-abc/diagrams/secrets.env", "TOKEN=hunter2");
    entries.put("myrepo-main-abc/diagrams/README.md", "# docs");
    entries.put("myrepo-main-abc/other/elsewhere.likec4", "nope"); // outside subtree

    Map<String, String> files =
        GitLabArchiveExtractor.extract(new ByteArrayInputStream(tarGz(entries)), "diagrams");

    assertEquals(2, files.size());
    assertEquals("spec {}", files.get("spec.likec4"));
    assertEquals("{snap}", files.get(".likec4/index.likec4.snap"));
    assertFalse(files.containsKey("secrets.env"));
    assertFalse(files.containsKey("README.md"));
    assertTrue(files.keySet().stream().noneMatch(k -> k.contains("elsewhere")));
  }

  @Test
  void empty_path_takes_the_whole_repo() throws IOException {
    Map<String, String> entries = new LinkedHashMap<>();
    entries.put("myrepo-main-abc/model.likec4", "m");
    entries.put("myrepo-main-abc/.env", "secret");
    Map<String, String> files =
        GitLabArchiveExtractor.extract(new ByteArrayInputStream(tarGz(entries)), "");
    assertEquals(Map.of("model.likec4", "m"), files);
  }
}
```

- [ ] **Step 2: Run to confirm it fails**

Run: `cd likec4-core && ~/.local/bin/jmvn -B -o test`
Expected: compilation failure (`GitLabArchiveExtractor` not found).

- [ ] **Step 3: Create `GitLabArchiveExtractor.java`**

```java
package com.phrontizo.likec4.source;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/** Extracts a GitLab archive.tar.gz into the filtered, path-safe relPath→content map. */
public final class GitLabArchiveExtractor {
  private GitLabArchiveExtractor() {}

  public static Map<String, String> extract(InputStream tarGz, String pathPrefix) throws IOException {
    Map<String, String> out = new LinkedHashMap<>();
    try (TarArchiveInputStream tar = new TarArchiveInputStream(new GzipCompressorInputStream(tarGz))) {
      TarArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        if (entry.isDirectory()) continue;
        Optional<String> rel = PathSafety.safeRelative(entry.getName(), pathPrefix);
        if (rel.isEmpty()) continue;
        String name = rel.get();
        if (!LikeC4FileFilter.keep(name)) continue;
        out.put(name, new String(tar.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    return out;
  }
}
```

- [ ] **Step 4: Run to confirm it passes**

Run: `cd likec4-core && ~/.local/bin/jmvn -B -o test`
Expected: `BUILD SUCCESS`. (`TarArchiveInputStream.readAllBytes()` returns only the current entry's bytes — commons-compress bounds the stream per entry.)

- [ ] **Step 5: Commit**

```bash
git add likec4-core/src/main/java/com/phrontizo/likec4/source/GitLabArchiveExtractor.java likec4-core/src/test/java/com/phrontizo/likec4/source/GitLabArchiveExtractorTest.java
git commit -m "plan3: tar.gz archive extraction with filter + path safety"
```

---

## Task 5: GitLab HTTP client — `GitLabSourceClient` + `GitLabException`

**Files:**
- Create: `likec4-core/src/main/java/com/phrontizo/likec4/source/GitLabException.java`
- Create: `likec4-core/src/main/java/com/phrontizo/likec4/source/GitLabSourceClient.java`
- Test: `likec4-core/src/test/java/com/phrontizo/likec4/source/GitLabSourceClientTest.java`

Resolves `ref→sha` via `GET /api/v4/projects/:enc/repository/commits/:ref` (JSON `{id}`), fetches the subtree via `GET /api/v4/projects/:enc/repository/archive.tar.gz?sha=&path=`, authenticating with the `PRIVATE-TOKEN` header. Tested against an in-process `com.sun.net.httpserver.HttpServer` — no real network.

- [ ] **Step 1: Write the failing test — `GitLabSourceClientTest.java`**

```java
package com.phrontizo.likec4.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitLabSourceClientTest {
  private HttpServer server;
  private String base;
  private volatile String lastToken;

  private static byte[] tarGz(Map<String, String> entries) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bos))) {
      for (Map.Entry<String, String> en : entries.entrySet()) {
        byte[] data = en.getValue().getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry te = new TarArchiveEntry(en.getKey());
        te.setSize(data.length);
        tar.putArchiveEntry(te);
        tar.write(data);
        tar.closeArchiveEntry();
      }
    }
    return bos.toByteArray();
  }

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", ex -> {
      lastToken = ex.getRequestHeaders().getFirst("PRIVATE-TOKEN");
      String raw = ex.getRequestURI().getRawPath();
      String query = ex.getRequestURI().getRawQuery();
      byte[] body;
      int status = 200;
      if (raw.contains("/repository/commits/")) {
        if (raw.endsWith("/missing")) { status = 404; body = "{}".getBytes(); }
        else body = "{\"id\":\"0123456789abcdef0123456789abcdef01234567\"}".getBytes();
      } else if (raw.contains("/repository/archive.tar.gz")) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("myrepo-main-sha/diagrams/model.likec4", "M");
        entries.put("myrepo-main-sha/diagrams/secret.env", "X");
        try { body = tarGz(entries); } catch (IOException e) { throw new RuntimeException(e); }
        assertTrue(query != null && query.contains("path=diagrams"));
      } else { status = 404; body = "{}".getBytes(); }
      ex.sendResponseHeaders(status, body.length);
      ex.getResponseBody().write(body);
      ex.close();
    });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stop() { server.stop(0); }

  private GitLabSourceClient client() {
    return new GitLabSourceClient(HttpClient.newHttpClient(), base, () -> "secret-token");
  }

  @Test
  void resolves_sha_and_sends_token() throws Exception {
    String sha = client().resolveSha("grp/repo", "main");
    assertEquals("0123456789abcdef0123456789abcdef01234567", sha);
    assertEquals("secret-token", lastToken);
  }

  @Test
  void fetches_and_filters_the_subtree() throws Exception {
    SourceBundle bundle = client().fetchSubtree("grp/repo", "0123456789abcdef0123456789abcdef01234567", "diagrams");
    assertEquals(Map.of("model.likec4", "M"), bundle.files());
    assertEquals("0123456789abcdef0123456789abcdef01234567", bundle.sha());
  }

  @Test
  void maps_non_2xx_to_GitLabException_with_status() {
    GitLabException ex = assertThrows(GitLabException.class, () -> client().resolveSha("grp/repo", "missing"));
    assertEquals(404, ex.status());
  }
}
```

- [ ] **Step 2: Run to confirm it fails**

Run: `cd likec4-core && ~/.local/bin/jmvn -B -o test`
Expected: compilation failure (`GitLabSourceClient` / `GitLabException` not found).

- [ ] **Step 3: Create `GitLabException.java`**

```java
package com.phrontizo.likec4.source;

import java.io.IOException;

/** A GitLab API call returned a non-success status (or an unusable response). */
public class GitLabException extends IOException {
  private final int status;

  public GitLabException(int status, String message) {
    super(status + ": " + message);
    this.status = status;
  }

  public int status() {
    return status;
  }
}
```

- [ ] **Step 4: Create `GitLabSourceClient.java`**

```java
package com.phrontizo.likec4.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;

/** Fetches LikeC4 source from a self-managed GitLab via the REST + archive endpoints. */
public final class GitLabSourceClient {
  private final HttpClient http;
  private final String baseUrl;
  private final Supplier<String> token;
  private final ObjectMapper json = new ObjectMapper();

  public GitLabSourceClient(HttpClient http, String baseUrl, Supplier<String> token) {
    this.http = http;
    this.baseUrl = baseUrl.replaceAll("/+$", "");
    this.token = token;
  }

  /** Resolve a branch/tag/sha ref to a full commit sha. */
  public String resolveSha(String project, String ref) throws IOException, InterruptedException {
    String enc = URLEncoder.encode(project, StandardCharsets.UTF_8);
    String refEnc = URLEncoder.encode(ref, StandardCharsets.UTF_8);
    URI uri = URI.create(baseUrl + "/api/v4/projects/" + enc + "/repository/commits/" + refEnc);
    HttpResponse<byte[]> res = send(uri);
    if (res.statusCode() != 200) throw new GitLabException(res.statusCode(), "resolve ref " + ref);
    JsonNode node = json.readTree(res.body());
    String sha = node.path("id").asText("");
    if (sha.isEmpty()) throw new GitLabException(200, "commit response had no id");
    return sha;
  }

  /** Fetch the LikeC4 subtree at a sha, filtered to LikeC4 source + snapshots. */
  public SourceBundle fetchSubtree(String project, String sha, String path)
      throws IOException, InterruptedException {
    String enc = URLEncoder.encode(project, StandardCharsets.UTF_8);
    StringBuilder u = new StringBuilder(baseUrl)
        .append("/api/v4/projects/").append(enc)
        .append("/repository/archive.tar.gz?sha=").append(URLEncoder.encode(sha, StandardCharsets.UTF_8));
    if (path != null && !path.isEmpty()) {
      u.append("&path=").append(URLEncoder.encode(path, StandardCharsets.UTF_8));
    }
    HttpResponse<byte[]> res = send(URI.create(u.toString()));
    if (res.statusCode() != 200) throw new GitLabException(res.statusCode(), "archive at " + sha);
    Map<String, String> files = GitLabArchiveExtractor.extract(new ByteArrayInputStream(res.body()), path);
    return new SourceBundle(sha, files);
  }

  private HttpResponse<byte[]> send(URI uri) throws IOException, InterruptedException {
    HttpRequest req = HttpRequest.newBuilder(uri)
        .header("PRIVATE-TOKEN", token.get())
        .GET()
        .build();
    return http.send(req, HttpResponse.BodyHandlers.ofByteArray());
  }
}
```

- [ ] **Step 5: Run to confirm it passes**

Run: `cd likec4-core && ~/.local/bin/jmvn -B -o test`
Expected: `BUILD SUCCESS`, all tests pass (resolve, fetch+filter, 404 mapping; token header asserted). If `HttpServer` context routing behaves unexpectedly with the encoded `%2F` project path, note the handler is a single catch-all on `/` matching `getRawPath()`, so encoding does not affect routing.

- [ ] **Step 6: Commit**

```bash
git add likec4-core/src/main/java/com/phrontizo/likec4/source/GitLabException.java likec4-core/src/main/java/com/phrontizo/likec4/source/GitLabSourceClient.java likec4-core/src/test/java/com/phrontizo/likec4/source/GitLabSourceClientTest.java
git commit -m "plan3: GitLab source client (resolve sha + fetch filtered subtree)"
```

---

## Task 6: Service-token encryption — `TokenCipher`

**Files:**
- Create: `likec4-core/src/main/java/com/phrontizo/likec4/source/TokenCipher.java`
- Test: `likec4-core/src/test/java/com/phrontizo/likec4/source/TokenCipherTest.java`

AES-256-GCM (JDK `javax.crypto`, no deps). The encrypted token is what `AdminConfig` persists; the plaintext never reaches the browser (§8).

- [ ] **Step 1: Write the failing test — `TokenCipherTest.java`**

```java
package com.phrontizo.likec4.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TokenCipherTest {
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
  void rejects_tampered_ciphertext() {
    byte[] key = TokenCipher.newKey();
    TokenCipher c = new TokenCipher(key);
    String enc = c.encrypt("glpat-secret-123");
    // Flip a character in the base64 payload near the end (the GCM tag) — auth must fail.
    char[] chars = enc.toCharArray();
    chars[chars.length - 2] = (chars[chars.length - 2] == 'A') ? 'B' : 'A';
    String tampered = new String(chars);
    assertThrows(IllegalStateException.class, () -> c.decrypt(tampered));
  }
}
```

- [ ] **Step 2: Run to confirm it fails**

Run: `cd likec4-core && ~/.local/bin/jmvn -B -o test`
Expected: compilation failure (`TokenCipher` not found).

- [ ] **Step 3: Create `TokenCipher.java`**

```java
package com.phrontizo.likec4.source;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-256-GCM symmetric encryption for the GitLab service token. Output is base64(iv || ciphertext||tag). */
public final class TokenCipher {
  private static final String TRANSFORM = "AES/GCM/NoPadding";
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKey key;
  private final SecureRandom rng = new SecureRandom();

  public TokenCipher(byte[] keyBytes) {
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
    try {
      byte[] in = Base64.getDecoder().decode(base64);
      byte[] iv = Arrays.copyOfRange(in, 0, IV_BYTES);
      byte[] ct = Arrays.copyOfRange(in, IV_BYTES, in.length);
      Cipher c = Cipher.getInstance(TRANSFORM);
      c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      return new String(c.doFinal(ct), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException("decrypt failed (bad key or tampered ciphertext)", e);
    }
  }
}
```

- [ ] **Step 4: Run to confirm it passes**

Run: `cd likec4-core && ~/.local/bin/jmvn -B -o test`
Expected: `BUILD SUCCESS`. (GCM auth-tag verification throws `AEADBadTagException` → wrapped as `IllegalStateException` on tamper.)

- [ ] **Step 5: Commit**

```bash
git add likec4-core/src/main/java/com/phrontizo/likec4/source/TokenCipher.java likec4-core/src/test/java/com/phrontizo/likec4/source/TokenCipherTest.java
git commit -m "plan3: AES-GCM token cipher"
```

---

## Task 7: Allowlist + input validation — `ProjectAllowlist`, `InputValidation`

**Files:**
- Create: `likec4-core/src/main/java/com/phrontizo/likec4/source/ProjectAllowlist.java`
- Create: `likec4-core/src/main/java/com/phrontizo/likec4/source/InputValidation.java`
- Test: `likec4-core/src/test/java/com/phrontizo/likec4/source/ProjectAllowlistTest.java`
- Test: `likec4-core/src/test/java/com/phrontizo/likec4/source/InputValidationTest.java`

- [ ] **Step 1: Write the failing tests**

`ProjectAllowlistTest.java`:
```java
package com.phrontizo.likec4.source;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectAllowlistTest {
  private final ProjectAllowlist allow = new ProjectAllowlist(List.of("platform", "team/architecture"));

  @Test
  void allows_projects_under_an_allowed_group_or_exact_match() {
    assertTrue(allow.isAllowed("platform/arch"));
    assertTrue(allow.isAllowed("platform/sub/repo"));
    assertTrue(allow.isAllowed("team/architecture"));
  }

  @Test
  void denies_lookalikes_unknowns_and_blanks() {
    assertFalse(allow.isAllowed("platform-evil/x")); // not under platform/
    assertFalse(allow.isAllowed("other/repo"));
    assertFalse(allow.isAllowed("team/architecture-secret"));
    assertFalse(allow.isAllowed(""));
    assertFalse(allow.isAllowed(null));
  }
}
```

`InputValidationTest.java`:
```java
package com.phrontizo.likec4.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class InputValidationTest {
  @Test
  void accepts_normal_refs_and_paths() {
    assertEquals("main", InputValidation.sanitizeRef("main"));
    assertEquals("v1.2.3", InputValidation.sanitizeRef("v1.2.3"));
    assertEquals("feature/x", InputValidation.sanitizeRef("feature/x"));
    assertEquals("diagrams/c4", InputValidation.sanitizePath("/diagrams/c4/"));
    assertEquals("", InputValidation.sanitizePath(null));
    assertEquals("", InputValidation.sanitizePath("  "));
  }

  @Test
  void rejects_traversal_and_injection() {
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeRef("../etc"));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeRef("a; rm -rf /"));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeRef("a\nb"));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeRef(""));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizePath("../../secret"));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizePath("a b"));
  }
}
```

- [ ] **Step 2: Run to confirm they fail**

Run: `cd likec4-core && ~/.local/bin/jmvn -B -o test`
Expected: compilation failures (`ProjectAllowlist` / `InputValidation` not found).

- [ ] **Step 3: Create `ProjectAllowlist.java`**

```java
package com.phrontizo.likec4.source;

import java.util.Collection;
import java.util.List;

/** Allows a project only if it is, or sits under, a configured group/prefix entry (§8). */
public final class ProjectAllowlist {
  private final List<String> entries;

  public ProjectAllowlist(Collection<String> entries) {
    this.entries = entries.stream().map(s -> s.strip().replaceAll("/+$", "")).filter(s -> !s.isEmpty()).toList();
  }

  public boolean isAllowed(String project) {
    if (project == null || project.isBlank()) return false;
    String p = project.strip();
    for (String entry : entries) {
      if (p.equals(entry) || p.startsWith(entry + "/")) return true;
    }
    return false;
  }
}
```

- [ ] **Step 4: Create `InputValidation.java`**

```java
package com.phrontizo.likec4.source;

import java.util.regex.Pattern;

/** Sanitises untrusted ref/path params before any GitLab call (rejects traversal/injection). */
public final class InputValidation {
  private InputValidation() {}

  private static final Pattern REF = Pattern.compile("[A-Za-z0-9._/\\-]{1,255}");
  private static final Pattern PATH = Pattern.compile("[A-Za-z0-9._/\\-]{1,1024}");

  public static String sanitizeRef(String ref) {
    if (ref == null || ref.isBlank()) throw new IllegalArgumentException("ref is required");
    String r = ref.strip();
    if (r.contains("..") || !REF.matcher(r).matches()) throw new IllegalArgumentException("invalid ref: " + ref);
    return r;
  }

  public static String sanitizePath(String path) {
    if (path == null) return "";
    String p = path.strip().replaceAll("^/+|/+$", "");
    if (p.isEmpty()) return "";
    if (p.contains("..") || !PATH.matcher(p).matches()) throw new IllegalArgumentException("invalid path: " + path);
    return p;
  }
}
```

- [ ] **Step 5: Run to confirm they pass**

Run: `cd likec4-core && ~/.local/bin/jmvn -B -o test`
Expected: `BUILD SUCCESS`, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add likec4-core/src/main/java/com/phrontizo/likec4/source/ProjectAllowlist.java likec4-core/src/main/java/com/phrontizo/likec4/source/InputValidation.java likec4-core/src/test/java/com/phrontizo/likec4/source/ProjectAllowlistTest.java likec4-core/src/test/java/com/phrontizo/likec4/source/InputValidationTest.java
git commit -m "plan3: project allowlist + ref/path input validation"
```

---

## Task 8: Cache tier 1 — `Clock` + `RefShaCache`

**Files:**
- Create: `likec4-core/src/main/java/com/phrontizo/likec4/source/cache/Clock.java`
- Create: `likec4-core/src/main/java/com/phrontizo/likec4/source/cache/RefShaCache.java`
- Test: `likec4-core/src/test/java/com/phrontizo/likec4/source/cache/ManualClock.java` (test util)
- Test: `likec4-core/src/test/java/com/phrontizo/likec4/source/cache/RefShaCacheTest.java`

- [ ] **Step 1: Write the failing test + clock util**

`ManualClock.java`:
```java
package com.phrontizo.likec4.source.cache;

/** Test clock with controllable time. */
public final class ManualClock implements Clock {
  private long now;
  public ManualClock(long start) { this.now = start; }
  public void advance(long ms) { now += ms; }
  @Override public long nowMillis() { return now; }
}
```

`RefShaCacheTest.java`:
```java
package com.phrontizo.likec4.source.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RefShaCacheTest {
  @Test
  void caches_within_ttl_then_reloads_after_expiry() throws Exception {
    ManualClock clock = new ManualClock(1_000);
    RefShaCache cache = new RefShaCache(clock, 60_000);
    AtomicInteger calls = new AtomicInteger();
    RefShaCache.ShaLoader loader = (p, r) -> { calls.incrementAndGet(); return "sha-" + calls.get(); };

    assertEquals("sha-1", cache.get("grp/repo", "main", loader));
    assertEquals("sha-1", cache.get("grp/repo", "main", loader)); // cached
    assertEquals(1, calls.get());

    clock.advance(60_001);
    assertEquals("sha-2", cache.get("grp/repo", "main", loader)); // expired -> reload
    assertEquals(2, calls.get());
  }

  @Test
  void a_full_40_hex_ref_bypasses_the_loader_entirely() throws Exception {
    RefShaCache cache = new RefShaCache(new ManualClock(0), 60_000);
    String full = "0123456789abcdef0123456789abcdef01234567";
    assertEquals(full, cache.get("grp/repo", full, (p, r) -> { throw new AssertionError("loader must not run"); }));
  }
}
```

- [ ] **Step 2: Run to confirm it fails**

Run: `cd likec4-core && ~/.local/bin/jmvn -B -o test`
Expected: compilation failure (`Clock` / `RefShaCache` not found).

- [ ] **Step 3: Create `Clock.java`**

```java
package com.phrontizo.likec4.source.cache;

/** Abstraction over wall-clock time so TTL logic is unit-testable. */
public interface Clock {
  long nowMillis();

  Clock SYSTEM = System::currentTimeMillis;
}
```

- [ ] **Step 4: Create `RefShaCache.java`**

```java
package com.phrontizo.likec4.source.cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Caches ref->sha for a short TTL. Full 40-hex shas are immutable and bypass the cache. */
public final class RefShaCache {
  @FunctionalInterface
  public interface ShaLoader {
    String load(String project, String ref) throws Exception;
  }

  private static final Pattern FULL_SHA = Pattern.compile("[0-9a-fA-F]{40}");

  private record Entry(String sha, long expiresAt) {}

  private final Clock clock;
  private final long ttlMillis;
  private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

  public RefShaCache(Clock clock, long ttlMillis) {
    this.clock = clock;
    this.ttlMillis = ttlMillis;
  }

  public String get(String project, String ref, ShaLoader loader) throws Exception {
    if (FULL_SHA.matcher(ref).matches()) return ref;
    String key = project + " " + ref;
    long now = clock.nowMillis();
    Entry e = entries.get(key);
    if (e != null && e.expiresAt() > now) return e.sha();
    String sha = loader.load(project, ref);
    entries.put(key, new Entry(sha, now + ttlMillis));
    return sha;
  }
}
```

- [ ] **Step 5: Run to confirm it passes**

Run: `cd likec4-core && ~/.local/bin/jmvn -B -o test`
Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add likec4-core/src/main/java/com/phrontizo/likec4/source/cache/Clock.java likec4-core/src/main/java/com/phrontizo/likec4/source/cache/RefShaCache.java likec4-core/src/test/java/com/phrontizo/likec4/source/cache/ManualClock.java likec4-core/src/test/java/com/phrontizo/likec4/source/cache/RefShaCacheTest.java
git commit -m "plan3: ref->sha cache with short TTL"
```

---

## Task 9: Cache tier 2 — `SourceBundleCache` (LRU + single-flight + SWR + disk)

**Files:**
- Create: `likec4-core/src/main/java/com/phrontizo/likec4/source/cache/SourceBundleCache.java`
- Test: `likec4-core/src/test/java/com/phrontizo/likec4/source/cache/SourceBundleCacheTest.java`

Keyed by `(project, sha, path)` (sha-immutable -> never stale). Bounded access-order LRU; single-flight per key; stale-while-revalidate keyed by `(project, path)` (serve last-good when a fresh load fails — §7 resilience); disk-backed (survives restart). Exposes a package-private `memSize()` test seam.

- [ ] **Step 1: Write the failing test — `SourceBundleCacheTest.java`**

```java
package com.phrontizo.likec4.source.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.phrontizo.likec4.source.SourceBundle;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceBundleCacheTest {

  private static SourceBundle bundle(String sha) {
    return new SourceBundle(sha, Map.of("model.likec4", "content-" + sha));
  }

  @Test
  void serves_from_disk_on_a_second_instance_without_calling_loader(@TempDir Path dir) throws Exception {
    SourceBundleCache c1 = new SourceBundleCache(dir, 10);
    c1.get("grp/repo", "shaA", "diagrams", (p, s, pa) -> bundle("shaA"));

    SourceBundleCache c2 = new SourceBundleCache(dir, 10);
    SourceBundle got = c2.get("grp/repo", "shaA", "diagrams",
        (p, s, pa) -> { throw new AssertionError("loader must not run - disk hit expected"); });
    assertEquals("content-shaA", got.files().get("model.likec4"));
  }

  @Test
  void bounds_in_memory_entries_by_lru(@TempDir Path dir) throws Exception {
    SourceBundleCache c = new SourceBundleCache(dir, 2);
    c.get("grp/repo", "s1", "d", (p, s, pa) -> bundle("s1"));
    c.get("grp/repo", "s2", "d", (p, s, pa) -> bundle("s2"));
    c.get("grp/repo", "s3", "d", (p, s, pa) -> bundle("s3"));
    assertEquals(2, c.memSize());
  }

  @Test
  void single_flight_coalesces_concurrent_misses(@TempDir Path dir) throws Exception {
    SourceBundleCache c = new SourceBundleCache(dir, 10);
    AtomicInteger calls = new AtomicInteger();
    Callable<SourceBundle> task = () -> c.get("grp/repo", "sha", "d", (p, s, pa) -> {
      calls.incrementAndGet();
      Thread.sleep(50);
      return bundle("sha");
    });
    ExecutorService pool = Executors.newFixedThreadPool(2);
    Future<SourceBundle> f1 = pool.submit(task);
    Future<SourceBundle> f2 = pool.submit(task);
    f1.get();
    f2.get();
    pool.shutdown();
    assertEquals(1, calls.get());
  }

  @Test
  void serves_stale_on_loader_failure(@TempDir Path dir) throws Exception {
    SourceBundleCache c = new SourceBundleCache(dir, 10);
    SourceBundle good = c.get("grp/repo", "shaOld", "d", (p, s, pa) -> bundle("shaOld"));
    SourceBundle stale = c.get("grp/repo", "shaNew", "d", (p, s, pa) -> { throw new RuntimeException("gitlab down"); });
    assertEquals(good.files(), stale.files()); // last-good for (project,path) served
  }

  @Test
  void rethrows_when_no_stale_available(@TempDir Path dir) throws Exception {
    SourceBundleCache c = new SourceBundleCache(dir, 10);
    assertThrows(RuntimeException.class,
        () -> c.get("grp/repo", "shaX", "d", (p, s, pa) -> { throw new RuntimeException("down"); }));
  }
}
```

- [ ] **Step 2: Run to confirm it fails**

Run: `cd likec4-core && ~/.local/bin/jmvn -B -o test`
Expected: compilation failure (`SourceBundleCache` not found).

- [ ] **Step 3: Create `SourceBundleCache.java`**

```java
package com.phrontizo.likec4.source.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phrontizo.likec4.source.SourceBundle;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Disk-backed, LRU-bounded source-bundle cache with single-flight and stale-while-revalidate. */
public final class SourceBundleCache {
  @FunctionalInterface
  public interface BundleLoader {
    SourceBundle load(String project, String sha, String path) throws Exception;
  }

  private final Path dir;
  private final int maxEntries;
  private final ObjectMapper json = new ObjectMapper();
  private final Map<String, SourceBundle> mem;
  private final Map<String, SourceBundle> lastGood = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

  public SourceBundleCache(Path dir, int maxEntries) throws IOException {
    this.dir = dir;
    this.maxEntries = maxEntries;
    Files.createDirectories(dir);
    this.mem = new LinkedHashMap<>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<String, SourceBundle> eldest) {
        return size() > SourceBundleCache.this.maxEntries;
      }
    };
  }

  public SourceBundle get(String project, String sha, String path, BundleLoader loader) throws Exception {
    String key = key(project, sha, path);
    String projectPath = project + " " + nz(path);
    synchronized (mem) {
      SourceBundle hit = mem.get(key);
      if (hit != null) return hit;
    }
    SourceBundle disk = readDisk(key);
    if (disk != null) {
      store(key, projectPath, disk);
      return disk;
    }
    Object lock = locks.computeIfAbsent(key, k -> new Object());
    synchronized (lock) {
      synchronized (mem) {
        SourceBundle hit = mem.get(key);
        if (hit != null) return hit;
      }
      try {
        SourceBundle loaded = loader.load(project, sha, path);
        store(key, projectPath, loaded);
        writeDisk(key, loaded);
        return loaded;
      } catch (Exception ex) {
        SourceBundle stale = lastGood.get(projectPath);
        if (stale != null) return stale; // stale-while-revalidate
        throw ex;
      } finally {
        locks.remove(key, lock);
      }
    }
  }

  int memSize() {
    synchronized (mem) {
      return mem.size();
    }
  }

  private void store(String key, String projectPath, SourceBundle b) {
    synchronized (mem) {
      mem.put(key, b);
    }
    lastGood.put(projectPath, b);
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }

  private String key(String project, String sha, String path) {
    return project + " " + sha + " " + nz(path);
  }

  private Path fileFor(String key) {
    return dir.resolve(sha256Hex(key) + ".json");
  }

  private SourceBundle readDisk(String key) {
    Path f = fileFor(key);
    if (!Files.exists(f)) return null;
    try {
      return json.readValue(Files.readString(f, StandardCharsets.UTF_8), SourceBundle.class);
    } catch (IOException e) {
      return null; // corrupt entry -> treat as miss
    }
  }

  private void writeDisk(String key, SourceBundle b) {
    try {
      Files.writeString(fileFor(key), json.writeValueAsString(b), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String sha256Hex(String s) {
    try {
      byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(h.length * 2);
      for (byte x : h) {
        sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
```

- [ ] **Step 4: Run to confirm it passes**

Run: `cd likec4-core && ~/.local/bin/jmvn -B -o test`
Expected: `BUILD SUCCESS`, all tests pass (disk re-serve, LRU bound = 2, single-flight = 1 call, SWR serves stale, rethrow when no stale). Jackson 2.17 deserialises the `SourceBundle` record via its canonical constructor; if it fails, confirm `jackson-databind` >= 2.12 (records support) — 2.17.2 is pinned.

- [ ] **Step 5: Commit**

```bash
git add likec4-core/src/main/java/com/phrontizo/likec4/source/cache/SourceBundleCache.java likec4-core/src/test/java/com/phrontizo/likec4/source/cache/SourceBundleCacheTest.java
git commit -m "plan3: disk-backed source-bundle cache (LRU + single-flight + SWR)"
```

---

## Task 10: Macro HTML emit + escaping — `DiagramHtmlRenderer` (core, executed)

The §11 "macro HTML output" unit test and the §8 XSS-escaping concern live in a pure renderer in `likec4-core`; the gated Confluence `Macro` (Task 12) just delegates to it.

**Files:**
- Create: `likec4-core/src/main/java/com/phrontizo/likec4/source/DiagramHtmlRenderer.java`
- Test: `likec4-core/src/test/java/com/phrontizo/likec4/source/DiagramHtmlRendererTest.java`

- [ ] **Step 1: Write the failing test — `DiagramHtmlRendererTest.java`**

```java
package com.phrontizo.likec4.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DiagramHtmlRendererTest {
  @Test
  void emits_the_macro_div_with_data_attributes() {
    String html = DiagramHtmlRenderer.render("grp/repo", "main", "diagrams", "index", "7");
    assertTrue(html.contains("class=\"likec4-diagram\""));
    assertTrue(html.contains("data-project=\"grp/repo\""));
    assertTrue(html.contains("data-ref=\"main\""));
    assertTrue(html.contains("data-path=\"diagrams\""));
    assertTrue(html.contains("data-view=\"index\""));
    assertTrue(html.contains("data-instance=\"7\""));
  }

  @Test
  void omits_blank_optional_attributes() {
    String html = DiagramHtmlRenderer.render("grp/repo", null, "", null, "1");
    assertFalse(html.contains("data-ref"));
    assertFalse(html.contains("data-path"));
    assertFalse(html.contains("data-view"));
    assertTrue(html.contains("data-project=\"grp/repo\""));
  }

  @Test
  void escapes_attribute_values_to_prevent_html_injection() {
    String html = DiagramHtmlRenderer.render("\"><script>alert(1)</script>", "main", "", "", "1");
    assertFalse(html.contains("<script>"));
    assertTrue(html.contains("&lt;script&gt;"));
    assertTrue(html.contains("&quot;&gt;"));
  }
}
```

- [ ] **Step 2: Run to confirm it fails**

Run: `cd likec4-core && ~/.local/bin/jmvn -B -o test`
Expected: compilation failure (`DiagramHtmlRenderer` not found).

- [ ] **Step 3: Create `DiagramHtmlRenderer.java`**

```java
package com.phrontizo.likec4.source;

/** Emits the macro placeholder `<div class="likec4-diagram" data-...>` with escaped attributes. */
public final class DiagramHtmlRenderer {
  private DiagramHtmlRenderer() {}

  public static String render(String project, String ref, String path, String view, String instance) {
    StringBuilder sb = new StringBuilder("<div class=\"likec4-diagram\"");
    attr(sb, "data-project", project);
    attr(sb, "data-ref", ref);
    attr(sb, "data-path", path);
    attr(sb, "data-view", view);
    attr(sb, "data-instance", instance);
    sb.append("></div>");
    return sb.toString();
  }

  private static void attr(StringBuilder sb, String name, String value) {
    if (value == null || value.isEmpty()) return;
    sb.append(' ').append(name).append("=\"").append(escape(value)).append('"');
  }

  static String escape(String s) {
    StringBuilder out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '"' -> out.append("&quot;");
        case '\'' -> out.append("&#39;");
        default -> out.append(c);
      }
    }
    return out.toString();
  }
}
```

- [ ] **Step 4: Run to confirm it passes**

Run: `cd likec4-core && ~/.local/bin/jmvn -B -o test`
Expected: `BUILD SUCCESS`, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add likec4-core/src/main/java/com/phrontizo/likec4/source/DiagramHtmlRenderer.java likec4-core/src/test/java/com/phrontizo/likec4/source/DiagramHtmlRendererTest.java
git commit -m "plan3: macro HTML renderer with attribute escaping"
```

---

## Task 11: Server orchestration facade — `SourceService` (core, executed)

The server-side analog of Plan 2's `loadModel`: allowlist + validation + ref→sha (cached) + subtree fetch (cached). Atlassian-independent — depends on functional `ShaResolver`/`SubtreeFetcher` (which `GitLabSourceClient::resolveSha` / `::fetchSubtree` satisfy), so it is unit-tested with lambdas. The gated REST resource (Task 12) is a thin JAX-RS adapter over this.

**Files:**
- Create: `likec4-core/src/main/java/com/phrontizo/likec4/source/ShaResolver.java`
- Create: `likec4-core/src/main/java/com/phrontizo/likec4/source/SubtreeFetcher.java`
- Create: `likec4-core/src/main/java/com/phrontizo/likec4/source/SourceService.java`
- Test: `likec4-core/src/test/java/com/phrontizo/likec4/source/SourceServiceTest.java`

- [ ] **Step 1: Write the failing test — `SourceServiceTest.java`**

```java
package com.phrontizo.likec4.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.phrontizo.likec4.source.cache.Clock;
import com.phrontizo.likec4.source.cache.RefShaCache;
import com.phrontizo.likec4.source.cache.SourceBundleCache;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceServiceTest {

  private SourceService service(Path dir, ShaResolver resolver, SubtreeFetcher fetcher) throws Exception {
    return new SourceService(
        new ProjectAllowlist(List.of("grp")),
        new RefShaCache(Clock.SYSTEM, 60_000),
        new SourceBundleCache(dir, 10),
        resolver,
        fetcher);
  }

  @Test
  void resolves_then_fetches_with_caching(@TempDir Path dir) throws Exception {
    AtomicInteger resolves = new AtomicInteger();
    AtomicInteger fetches = new AtomicInteger();
    ShaResolver resolver = (p, r) -> { resolves.incrementAndGet(); return "0123456789abcdef0123456789abcdef01234567"; };
    SubtreeFetcher fetcher = (p, s, pa) -> { fetches.incrementAndGet(); return new SourceBundle(s, Map.of("m.likec4", "x")); };
    SourceService svc = service(dir, resolver, fetcher);

    assertEquals("0123456789abcdef0123456789abcdef01234567", svc.resolve("grp/repo", "main"));
    SourceBundle b = svc.source("grp/repo", "main", "diagrams");
    assertEquals(Map.of("m.likec4", "x"), b.files());
    // second source() reuses both caches
    svc.source("grp/repo", "main", "diagrams");
    assertEquals(1, resolves.get());
    assertEquals(1, fetches.get());
  }

  @Test
  void rejects_a_project_outside_the_allowlist(@TempDir Path dir) throws Exception {
    AtomicInteger resolves = new AtomicInteger();
    SourceService svc = service(dir, (p, r) -> { resolves.incrementAndGet(); return "s"; }, (p, s, pa) -> null);
    assertThrows(SourceService.NotAllowedException.class, () -> svc.resolve("secret/repo", "main"));
    assertEquals(0, resolves.get());
  }

  @Test
  void rejects_an_invalid_ref(@TempDir Path dir) throws Exception {
    SourceService svc = service(dir, (p, r) -> "s", (p, s, pa) -> null);
    assertThrows(IllegalArgumentException.class, () -> svc.resolve("grp/repo", "../etc"));
  }
}
```

- [ ] **Step 2: Run to confirm it fails**

Run: `cd likec4-core && ~/.local/bin/jmvn -B -o test`
Expected: compilation failure (`SourceService` / `ShaResolver` / `SubtreeFetcher` not found).

- [ ] **Step 3: Create `ShaResolver.java` and `SubtreeFetcher.java`**

```java
package com.phrontizo.likec4.source;

@FunctionalInterface
public interface ShaResolver {
  String resolveSha(String project, String ref) throws Exception;
}
```

```java
package com.phrontizo.likec4.source;

@FunctionalInterface
public interface SubtreeFetcher {
  SourceBundle fetchSubtree(String project, String sha, String path) throws Exception;
}
```

- [ ] **Step 4: Create `SourceService.java`**

```java
package com.phrontizo.likec4.source;

import com.phrontizo.likec4.source.cache.RefShaCache;
import com.phrontizo.likec4.source.cache.SourceBundleCache;

/** Server-side orchestration: allowlist + validation + cached ref→sha + cached subtree fetch. */
public final class SourceService {
  public static final class NotAllowedException extends RuntimeException {
    public NotAllowedException(String project) {
      super("project not in allowlist: " + project);
    }
  }

  private final ProjectAllowlist allowlist;
  private final RefShaCache refCache;
  private final SourceBundleCache bundleCache;
  private final ShaResolver resolver;
  private final SubtreeFetcher fetcher;

  public SourceService(ProjectAllowlist allowlist, RefShaCache refCache, SourceBundleCache bundleCache,
                       ShaResolver resolver, SubtreeFetcher fetcher) {
    this.allowlist = allowlist;
    this.refCache = refCache;
    this.bundleCache = bundleCache;
    this.resolver = resolver;
    this.fetcher = fetcher;
  }

  public String resolve(String project, String ref) throws Exception {
    requireAllowed(project);
    String safeRef = InputValidation.sanitizeRef(ref);
    return refCache.get(project, safeRef, resolver::resolveSha);
  }

  public SourceBundle source(String project, String ref, String path) throws Exception {
    requireAllowed(project);
    String safeRef = InputValidation.sanitizeRef(ref);
    String safePath = InputValidation.sanitizePath(path);
    String sha = refCache.get(project, safeRef, resolver::resolveSha);
    return bundleCache.get(project, sha, safePath, fetcher::fetchSubtree);
  }

  private void requireAllowed(String project) {
    if (!allowlist.isAllowed(project)) throw new NotAllowedException(project);
  }
}
```

- [ ] **Step 5: Run to confirm it passes**

Run: `cd likec4-core && ~/.local/bin/jmvn -B -o test`
Expected: `BUILD SUCCESS`, all tests pass.

- [ ] **Step 6: Run the WHOLE core suite and install it for the gated module**

Run: `cd likec4-core && ~/.local/bin/jmvn -B -o test && ~/.local/bin/jmvn -B -o install -DskipTests`
Expected: `BUILD SUCCESS`; the core jar is installed to `~/.m2` (so the gated Confluence module can resolve `com.phrontizo.confluence:likec4-source-core` when it is later built in the SDK).

- [ ] **Step 7: Commit**

```bash
git add likec4-core/src/main/java/com/phrontizo/likec4/source/ShaResolver.java likec4-core/src/main/java/com/phrontizo/likec4/source/SubtreeFetcher.java likec4-core/src/main/java/com/phrontizo/likec4/source/SourceService.java likec4-core/src/test/java/com/phrontizo/likec4/source/SourceServiceTest.java
git commit -m "plan3: SourceService orchestration facade (allowlist + cached resolve/fetch)"
```

---

## Task 12: Confluence P2 wrapper (AUTHORED — SDK-GATED, not built here)

**This task authors and commits the Confluence plugin but does NOT compile/run it** — it needs the Atlassian repo's amps packaging + a Confluence 9.2.20 runtime (deferred to the Atlassian SDK / Plan 4 Docker, like Plan 2's CI-gated e2e). The code below uses the best-known Confluence/SAL/JAX-RS APIs; verify/compile it in the SDK environment. Each class is a thin adapter over the already-tested `likec4-core`.

**Files:**
- Modify: `pom.xml` (root — Plan 2 stub → real Confluence plugin)
- Create: `src/main/java/com/phrontizo/confluence/likec4/LikeC4DiagramMacro.java`
- Create: `src/main/java/com/phrontizo/confluence/likec4/SourceRestResource.java`
- Create: `src/main/java/com/phrontizo/confluence/likec4/AdminConfig.java`
- Create: `src/main/java/com/phrontizo/confluence/likec4/AdminConfigResource.java`
- Create: `src/main/resources/atlassian-plugin.xml`
- Create: `src/main/resources/com/phrontizo/confluence/likec4/i18n.properties`

- [ ] **Step 1: Replace root `pom.xml`** with the Confluence P2 plugin POM

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.phrontizo.confluence</groupId>
  <artifactId>likec4-confluence</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>atlassian-plugin</packaging>
  <name>LikeC4 Confluence Diagram Plugin</name>

  <properties>
    <confluence.version>9.2.20</confluence.version>
    <amps.version>9.1.1</amps.version>
    <plugin.testrunner.version>2.0.1</plugin.testrunner.version>
    <atlassian.spring.scanner.version>2.2.4</atlassian.spring.scanner.version>
    <maven.compiler.release>17</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>

  <dependencies>
    <dependency>
      <groupId>com.phrontizo.confluence</groupId>
      <artifactId>likec4-source-core</artifactId>
      <version>0.1.0-SNAPSHOT</version>
    </dependency>
    <dependency>
      <groupId>com.atlassian.confluence</groupId>
      <artifactId>confluence</artifactId>
      <version>${confluence.version}</version>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>com.atlassian.sal</groupId>
      <artifactId>sal-api</artifactId>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>javax.ws.rs</groupId>
      <artifactId>jsr311-api</artifactId>
      <version>1.1.1</version>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>com.atlassian.plugins</groupId>
      <artifactId>atlassian-plugins-webresource</artifactId>
      <scope>provided</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>com.atlassian.maven.plugins</groupId>
        <artifactId>maven-confluence-plugin</artifactId>
        <version>${amps.version}</version>
        <extensions>true</extensions>
        <configuration>
          <productVersion>${confluence.version}</productVersion>
          <enableQuickReload>true</enableQuickReload>
          <!--
            Build the Plan 2 frontend into src/main/resources/likec4-web before packaging.
            Plan 4 wires a frontend-maven-plugin (or exec) execution: `npm ci && npm run build`
            in src/main/frontend, then the web-resource below serves the emitted assets/ dir.
          -->
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Create `LikeC4DiagramMacro.java`** (delegates HTML to the tested core renderer)

```java
package com.phrontizo.confluence.likec4;

import com.atlassian.confluence.content.render.xhtml.ConversionContext;
import com.atlassian.confluence.macro.Macro;
import com.atlassian.confluence.macro.MacroExecutionException;
import com.atlassian.plugin.spring.scanner.annotation.component.Scanned;
import com.atlassian.webresource.api.assembler.PageBuilderService;
import com.phrontizo.likec4.source.DiagramHtmlRenderer;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Named;

/** The "LikeC4 Diagram" macro: validates params, requires the web-resource, emits the placeholder div. */
@Scanned
@Named("likeC4DiagramMacro")
public class LikeC4DiagramMacro implements Macro {

  private final PageBuilderService pageBuilder;

  @Inject
  public LikeC4DiagramMacro(@Named("pageBuilderService") PageBuilderService pageBuilder) {
    this.pageBuilder = pageBuilder;
  }

  @Override
  public String execute(Map<String, String> params, String body, ConversionContext context)
      throws MacroExecutionException {
    String project = trimToNull(params.get("project"));
    if (project == null) {
      return "<div class=\"aui-message aui-message-error\">LikeC4 diagram: 'project' is required.</div>";
    }
    pageBuilder.assembler().resources().requireWebResource(
        "com.phrontizo.confluence.likec4-confluence:likec4-web");
    return DiagramHtmlRenderer.render(
        project,
        trimToNull(params.get("ref")),
        trimToNull(params.get("path")),
        trimToNull(params.get("view")),
        trimToNull(params.get("instance")));
  }

  @Override
  public BodyType getBodyType() {
    return BodyType.NONE;
  }

  @Override
  public OutputType getOutputType() {
    return OutputType.BLOCK;
  }

  private static String trimToNull(String s) {
    if (s == null) return null;
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }
}
```

- [ ] **Step 3: Create `SourceRestResource.java`** (thin JAX-RS adapter over `SourceService`)

```java
package com.phrontizo.confluence.likec4;

import com.atlassian.plugin.spring.scanner.annotation.component.Scanned;
import com.atlassian.sal.api.user.UserManager;
import com.phrontizo.likec4.source.SourceBundle;
import com.phrontizo.likec4.source.SourceService;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * GET /rest/likec4/1.0/resolve and /source — authenticated; delegates to the core SourceService.
 * The "1.0" version segment comes from the {@code <rest version="1.0">} module, so the class path
 * is "/" (NOT "/1.0", which would double the version to /rest/likec4/1.0/1.0/...).
 */
@Scanned
@Path("/")
public class SourceRestResource {

  private final UserManager userManager;
  private final SourceServiceProvider serviceProvider; // builds SourceService from AdminConfig

  @Inject
  public SourceRestResource(@Named("salUserManager") UserManager userManager,
                            SourceServiceProvider serviceProvider) {
    this.userManager = userManager;
    this.serviceProvider = serviceProvider;
  }

  @GET
  @Path("/resolve")
  @Produces(MediaType.APPLICATION_JSON)
  public Response resolve(@QueryParam("project") String project, @QueryParam("ref") String ref) {
    if (userManager.getRemoteUserKey() == null) return Response.status(Response.Status.UNAUTHORIZED).build();
    try {
      String sha = serviceProvider.get().resolve(project, defaultRef(ref));
      return Response.ok(Map.of("sha", sha)).build();
    } catch (SourceService.NotAllowedException e) {
      return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", "not allowed")).build();
    } catch (IllegalArgumentException e) {
      return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
    } catch (Exception e) {
      return Response.status(Response.Status.BAD_GATEWAY).entity(Map.of("error", "cannot reach repository")).build();
    }
  }

  @GET
  @Path("/source")
  @Produces(MediaType.APPLICATION_JSON)
  public Response source(@QueryParam("project") String project, @QueryParam("ref") String ref,
                         @QueryParam("path") String path) {
    if (userManager.getRemoteUserKey() == null) return Response.status(Response.Status.UNAUTHORIZED).build();
    try {
      SourceBundle bundle = serviceProvider.get().source(project, defaultRef(ref), path);
      return Response.ok(Map.of("sha", bundle.sha(), "files", bundle.files())).build();
    } catch (SourceService.NotAllowedException e) {
      return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", "not allowed")).build();
    } catch (IllegalArgumentException e) {
      return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
    } catch (Exception e) {
      return Response.status(Response.Status.BAD_GATEWAY).entity(Map.of("error", "cannot reach repository")).build();
    }
  }

  private static String defaultRef(String ref) {
    return (ref == null || ref.isBlank()) ? "HEAD" : ref;
  }
}
```

- [ ] **Step 4: Create `AdminConfig.java` + `AdminConfigResource.java` + a `SourceServiceProvider`**

`AdminConfig.java` (persists GitLab URL, encrypted token, allowlist, TTLs in PluginSettings):
```java
package com.phrontizo.confluence.likec4;

import com.atlassian.plugin.spring.scanner.annotation.component.Scanned;
import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import com.phrontizo.likec4.source.TokenCipher;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Named;

/** Admin-managed config: GitLab base URL, AES-encrypted service token, allowlist, TTLs. */
@Scanned
@Named("likeC4AdminConfig")
public class AdminConfig {
  private static final String NS = "com.phrontizo.confluence.likec4:";
  private final PluginSettingsFactory settingsFactory;

  @Inject
  public AdminConfig(@Named("pluginSettingsFactory") PluginSettingsFactory settingsFactory) {
    this.settingsFactory = settingsFactory;
  }

  private PluginSettings settings() { return settingsFactory.createGlobalSettings(); }

  public String getBaseUrl() { return (String) settings().get(NS + "baseUrl"); }
  public void setBaseUrl(String v) { settings().put(NS + "baseUrl", v); }

  public List<String> getAllowlist() {
    String raw = (String) settings().get(NS + "allowlist");
    return (raw == null || raw.isBlank()) ? List.of() : Arrays.asList(raw.split("\\s*,\\s*"));
  }
  public void setAllowlist(List<String> entries) { settings().put(NS + "allowlist", String.join(",", entries)); }

  public long getRefTtlMillis() {
    String raw = (String) settings().get(NS + "refTtlMillis");
    return raw == null ? 60_000L : Long.parseLong(raw);
  }

  /** The AES key is generated once and stored (base64) — in production prefer a secrets store. */
  public TokenCipher cipher() {
    String b64 = (String) settings().get(NS + "cipherKey");
    byte[] key;
    if (b64 == null) {
      key = TokenCipher.newKey();
      settings().put(NS + "cipherKey", Base64.getEncoder().encodeToString(key));
    } else {
      key = Base64.getDecoder().decode(b64);
    }
    return new TokenCipher(key);
  }

  public void setToken(String plaintext) { settings().put(NS + "token", cipher().encrypt(plaintext)); }
  public String getToken() {
    String enc = (String) settings().get(NS + "token");
    return enc == null ? null : cipher().decrypt(enc);
  }
}
```

`SourceServiceProvider.java` (builds a `SourceService` from current `AdminConfig` + a shared cache):
```java
package com.phrontizo.confluence.likec4;

import com.atlassian.plugin.spring.scanner.annotation.component.Scanned;
import com.phrontizo.likec4.source.GitLabSourceClient;
import com.phrontizo.likec4.source.ProjectAllowlist;
import com.phrontizo.likec4.source.SourceService;
import com.phrontizo.likec4.source.cache.Clock;
import com.phrontizo.likec4.source.cache.RefShaCache;
import com.phrontizo.likec4.source.cache.SourceBundleCache;
import java.net.http.HttpClient;
import java.nio.file.Path;
import javax.inject.Inject;
import javax.inject.Named;

/** Assembles a SourceService from current AdminConfig; caches are process-singletons. */
@Scanned
@Named("likeC4SourceServiceProvider")
public class SourceServiceProvider {
  private final AdminConfig config;
  private final HttpClient http = HttpClient.newHttpClient();
  private final RefShaCache refCache;
  private final SourceBundleCache bundleCache;

  @Inject
  public SourceServiceProvider(AdminConfig config) throws Exception {
    this.config = config;
    this.refCache = new RefShaCache(Clock.SYSTEM, config.getRefTtlMillis());
    this.bundleCache = new SourceBundleCache(
        Path.of(System.getProperty("java.io.tmpdir"), "likec4-bundle-cache"), 200);
  }

  public SourceService get() {
    GitLabSourceClient client = new GitLabSourceClient(http, config.getBaseUrl(), config::getToken);
    return new SourceService(new ProjectAllowlist(config.getAllowlist()), refCache, bundleCache,
        client::resolveSha, client::fetchSubtree);
  }
}
```

`AdminConfigResource.java` (admin-only REST to read/update config):
```java
package com.phrontizo.confluence.likec4;

import com.atlassian.plugin.spring.scanner.annotation.component.Scanned;
import com.atlassian.sal.api.user.UserManager;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Scanned
@Path("/admin")
public class AdminConfigResource {
  private final UserManager userManager;
  private final AdminConfig config;

  @Inject
  public AdminConfigResource(@Named("salUserManager") UserManager userManager, AdminConfig config) {
    this.userManager = userManager;
    this.config = config;
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Response get() {
    if (!isAdmin()) return Response.status(Response.Status.FORBIDDEN).build();
    return Response.ok(Map.of(
        "baseUrl", nz(config.getBaseUrl()),
        "allowlist", config.getAllowlist(),
        "tokenSet", config.getToken() != null)).build();
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response update(Map<String, Object> body) {
    if (!isAdmin()) return Response.status(Response.Status.FORBIDDEN).build();
    if (body.get("baseUrl") != null) config.setBaseUrl((String) body.get("baseUrl"));
    if (body.get("allowlist") instanceof List<?> l) config.setAllowlist(l.stream().map(String::valueOf).toList());
    if (body.get("token") != null) config.setToken((String) body.get("token"));
    return Response.ok(Map.of("ok", true)).build();
  }

  private boolean isAdmin() {
    var key = userManager.getRemoteUserKey();
    return key != null && userManager.isSystemAdmin(key);
  }

  private static String nz(String s) { return s == null ? "" : s; }
}
```

- [ ] **Step 5: Create `src/main/resources/atlassian-plugin.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<atlassian-plugin key="${atlassian.plugin.key}" name="${project.name}" plugins-version="2">
  <plugin-info>
    <description>${project.description}</description>
    <version>${project.version}</version>
    <vendor name="Phrontizo" url="https://phrontizo.com"/>
    <param name="plugin-icon">images/pluginIcon.png</param>
  </plugin-info>

  <resource type="i18n" name="i18n" location="com/phrontizo/confluence/likec4/i18n"/>

  <!-- Browser bundle (Plan 2 Vite build output). Plan 4 finalises the resource list from the Vite manifest. -->
  <web-resource key="likec4-web" name="LikeC4 Web Bundle">
    <resource type="download" name="likec4-web/" location="likec4-web/"/>
    <context>likec4</context>
  </web-resource>

  <xhtml-macro name="likec4-diagram" class="com.phrontizo.confluence.likec4.LikeC4DiagramMacro"
               key="likec4-diagram-macro" icon="images/pluginIcon.png">
    <description key="likec4.macro.desc"/>
    <category name="visuals"/>
    <parameters>
      <parameter name="project" type="string" required="true"/>
      <parameter name="ref" type="string"/>
      <parameter name="path" type="string"/>
      <parameter name="view" type="string"/>
      <parameter name="instance" type="string"/>
    </parameters>
  </xhtml-macro>

  <rest key="likec4-rest" path="/likec4" version="1.0">
    <description>LikeC4 source resolve/source + admin endpoints.</description>
  </rest>

  <web-item key="likec4-admin-link" name="LikeC4 Admin" section="system.admin/configuration" weight="200">
    <label key="likec4.admin.label"/>
    <link linkId="likec4-admin-link">/plugins/servlet/likec4/admin</link>
  </web-item>
</atlassian-plugin>
```

- [ ] **Step 6: Create `src/main/resources/com/phrontizo/confluence/likec4/i18n.properties`**

```properties
likec4.macro.desc=Embed an interactive LikeC4 diagram sourced from GitLab.
likec4.admin.label=LikeC4 Diagrams
```

- [ ] **Step 7: Commit (authored; NOT built here — SDK-gated)**

```bash
git add pom.xml src/main/java/com/phrontizo/confluence src/main/resources/atlassian-plugin.xml src/main/resources/com/phrontizo/confluence/likec4/i18n.properties
git commit -m "plan3: Confluence P2 wrapper (macro, REST, admin) — SDK-gated"
```

Do NOT run `mvn`/`jmvn` at the repo root — it requires the Atlassian repo + amps and a Confluence runtime (Plan 4). The local green gate for Plan 3 is `likec4-core` only.

---

## Task 13: README + final verification + self-review

**Files:**
- Create: `likec4-core/README.md`

- [ ] **Step 1: Create `likec4-core/README.md`**

````markdown
# LikeC4 Confluence — Source Core (Plan 3)

Atlassian-independent server-side core: fetch a filtered, path-safe LikeC4 source subtree from
GitLab; cache it (ref→sha TTL + source-bundle LRU/single-flight/stale-while-revalidate, disk-backed);
encrypt the service token (AES-GCM); validate input (allowlist/traversal); render the macro
placeholder HTML (escaped). Built/tested with the bootstrapped `~/.local/bin/jmvn` (Maven 3.9.9 +
Temurin JDK 21, `release 17`).

## Commands
- `cd likec4-core && ~/.local/bin/jmvn -B test` — full JUnit suite (offline: add `-o`).
- `cd likec4-core && ~/.local/bin/jmvn -B install -DskipTests` — install the jar for the gated plugin.

## REST contract (served by the gated Confluence module, consumed by the Plan 2 browser bundle)
- `GET /rest/likec4/1.0/resolve?project&ref` → `{ "sha" }`
- `GET /rest/likec4/1.0/source?project&ref&path` → `{ "sha", "files": { relPath: content } }`

## Confluence wrapper status
The Confluence P2 plugin (root `pom.xml` + `src/main/java/com/phrontizo/confluence`) is **authored
but SDK-gated** — it needs the Atlassian repo's amps packaging + a Confluence 9.2.20 runtime, built
and verified in the Atlassian SDK / Plan 4 Docker stack (analogous to Plan 2's CI-gated e2e).
````

- [ ] **Step 2: Run the full core suite (the green gate)**

Run: `cd likec4-core && ~/.local/bin/jmvn -B -o test`
Expected: `BUILD SUCCESS`. Record the total `Tests run:` count (sum across: BuildSmoke 1, FileFilter 2, PathSafety 3, ArchiveExtractor 2, GitLabSourceClient 3, TokenCipher 3, Allowlist 2, InputValidation 2, RefShaCache 2, SourceBundleCache 5, DiagramHtmlRenderer 3, SourceService 3 ≈ 31).

- [ ] **Step 3: Commit**

```bash
git add likec4-core/README.md
git commit -m "plan3: core README + verified test gate"
```

---

## Self-Review

**Spec coverage (Java server side, §4 components 1–5):**
- §4.1 macro (validate params, emit data-div, require web-resource) → Task 10 (`DiagramHtmlRenderer`, executed/escaped) + Task 12 (`LikeC4DiagramMacro`, gated).
- §4.2 REST `/resolve` + `/source` (auth, allowlist, delegate) → Task 11 (`SourceService`, executed) + Task 12 (`SourceRestResource`, gated).
- §4.3 `GitLabSourceClient` (resolve sha, archive subtree, keep only `.c4`/`.likec4`/`.likec4.snap`, relative paths, error mapping) → Tasks 2, 3, 4, 5 (all executed).
- §4.4 `CacheLayer` (ref→sha TTL; source-bundle LRU/single-flight/SWR/disk) → Tasks 8, 9 (executed).
- §4.5 `AdminConfig` (URL, encrypted token, allowlist, TTLs, flush) → Task 6 (`TokenCipher`, executed) + Task 12 (`AdminConfig`, gated).
- §8 security: file-type guarantee → Task 2 (filter, executed); tar-slip → Task 3 (executed); token encryption → Task 6 (executed); allowlist + ref/path validation → Task 7 (executed); XSS-escaping of macro attributes → Task 10 (executed).
- §9 server failure mapping (not-found/unreachable/forbidden/bad-request) → Task 11 (`SourceService` throws) + Task 12 (REST status mapping, gated).
- §11 Java fast-layer units (GitLabSourceClient, CacheLayer, macro HTML output, token round-trip, allowlist/traversal) → all executed in `likec4-core`.
- *Deferred to Plan 4 (correctly):* Docker stack, UPM install/upgrade, performance, licence, recorded-response GitLab contract test, and the wrapper's compile/package/runtime verification.

**Placeholder scan:** No "TODO/handle later". The Task 12 gated code is complete and committed; its "compiled in the SDK" note is the honest analog of Plan 2's CI-gated e2e — real best-effort code, not hand-waving.

**Type/name consistency:** `SourceBundle(sha, files)` flows from `GitLabArchiveExtractor`/`GitLabSourceClient` → `SourceBundleCache` (Jackson round-trip) → `SourceService` → gated `SourceRestResource` (`{sha, files}` JSON, matching the Plan 2 `restClient` contract). `SourceService` consumes `ShaResolver`/`SubtreeFetcher` which `GitLabSourceClient::resolveSha`/`::fetchSubtree` satisfy by signature (verified by `SourceServiceProvider` method refs). `TokenCipher` is constructed identically in the executed test and the gated `AdminConfig`. The REST paths (`/rest/likec4/1.0/resolve|source`) match Plan 2's `restClient` base. The macro emits `class="likec4-diagram"` + `data-*` exactly as Plan 2's `parseDataAttrs`/`boot` expect.

**Execution-tier note:** Tasks 1–11 + 13 are TDD-executed with `~/.local/bin/jmvn` (the green gate). Task 12 is authored-and-committed but SDK-gated; do NOT run `mvn` at the repo root in this environment.
