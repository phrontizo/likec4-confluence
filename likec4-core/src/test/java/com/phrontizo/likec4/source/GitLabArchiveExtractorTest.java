package com.phrontizo.likec4.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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

  /** Build a one-entry tar.gz whose content is the given raw bytes (so we can write invalid UTF-8). */
  private static byte[] tarGzRaw(String name, byte[] data) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bos))) {
      TarArchiveEntry te = new TarArchiveEntry(name);
      te.setSize(data.length);
      tar.putArchiveEntry(te);
      tar.write(data);
      tar.closeArchiveEntry();
    }
    return bos.toByteArray();
  }

  @Test
  void rejects_a_kept_entry_that_is_not_valid_utf8_naming_the_file() throws IOException {
    // A lone 0xFF/0xFE byte is not valid UTF-8. Strict decoding surfaces a clear IOException naming the
    // offending file rather than silently substituting U+FFFD and shipping mojibake to the parser.
    byte[] bad = new byte[] {(byte) 0xFF, (byte) 0xFE, 'm', 'o', 'd', 'e', 'l'};
    byte[] gz = tarGzRaw("repo-main-abc/model.likec4", bad);
    IOException ex =
        assertThrows(IOException.class, () -> GitLabArchiveExtractor.extract(new ByteArrayInputStream(gz), ""));
    assertTrue(ex.getMessage().contains("UTF-8") && ex.getMessage().contains("model.likec4"),
        "the error should name the offending file and the encoding, was: " + ex.getMessage());
  }

  @Test
  void closes_the_input_stream_even_when_the_gzip_header_is_invalid() {
    // A compromised/misbehaving GitLab can return a 200 whose body is NOT gzip.
    // GzipCompressorInputStream validates the magic bytes in its CONSTRUCTOR and throws before the
    // try-with-resources variable is assigned — so unless extract() adopts the raw stream as its own
    // resource, it closes nothing on that path and leaks the caller's stream. Assert the passed-in
    // stream is closed even when the gzip header is invalid.
    AtomicBoolean closed = new AtomicBoolean(false);
    InputStream notGzip = new ByteArrayInputStream(new byte[] {'n', 'o', 't', 'g', 'z', 'i', 'p'}) {
      @Override
      public void close() throws IOException {
        closed.set(true);
        super.close();
      }
    };
    assertThrows(IOException.class, () -> GitLabArchiveExtractor.extract(notGzip, ""));
    assertTrue(closed.get(), "extract() must close the input stream even when the gzip header is invalid");
  }

  @Test
  void a_huge_explicit_budget_saturates_rather_than_overflowing_to_a_negative_limit() throws IOException {
    // REGRESSION: the internal decompression budget is maxTotalBytes + maxEntries*1024 + 64Ki. Via the
    // explicit-cap overload an adversarially large maxTotalBytes/maxEntries would overflow that sum to a
    // NEGATIVE long, making the DecompressionBudget trip on the very first inflated byte and fail a small,
    // perfectly valid archive closed. The sum must saturate to Long.MAX_VALUE so the in-loop caps stay the
    // real bound; a tiny archive under those caps must still extract.
    Map<String, String> entries = new LinkedHashMap<>();
    entries.put("repo-main-abc/model.likec4", "model {}");
    byte[] gz = tarGz(entries);
    Map<String, String> files =
        GitLabArchiveExtractor.extract(
            new ByteArrayInputStream(gz), "", Long.MAX_VALUE - 1, Integer.MAX_VALUE, 16L * 1024 * 1024);
    assertEquals(Map.of("model.likec4", "model {}"), files);
  }

  @Test
  void skips_a_hard_link_entry_even_when_its_name_looks_like_a_likec4_file() throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bos))) {
      byte[] real = "model {}".getBytes(StandardCharsets.UTF_8);
      TarArchiveEntry file = new TarArchiveEntry("repo-main-abc/model.likec4");
      file.setSize(real.length);
      tar.putArchiveEntry(file);
      tar.write(real);
      tar.closeArchiveEntry();
      // A hard-link entry (LF_LINK) named *.likec4 must not be injected as an (empty) file.
      TarArchiveEntry link = new TarArchiveEntry("repo-main-abc/hard.likec4", TarArchiveEntry.LF_LINK);
      link.setLinkName("repo-main-abc/model.likec4");
      tar.putArchiveEntry(link);
      tar.closeArchiveEntry();
    }
    Map<String, String> files = GitLabArchiveExtractor.extract(new ByteArrayInputStream(bos.toByteArray()), "");
    assertEquals(Map.of("model.likec4", "model {}"), files);
    assertFalse(files.containsKey("hard.likec4"));
  }

  @Test
  void drops_a_zip_slip_traversal_entry_end_to_end() throws IOException {
    Map<String, String> entries = new LinkedHashMap<>();
    entries.put("repo-main-abc/model.likec4", "model {}");
    entries.put("repo-main-abc/../../../etc/cron.d/evil.likec4", "PAYLOAD"); // path-traversal name
    Map<String, String> files = GitLabArchiveExtractor.extract(new ByteArrayInputStream(tarGz(entries)), "");
    assertEquals(Map.of("model.likec4", "model {}"), files);
    assertTrue(files.keySet().stream().noneMatch(k -> k.contains("..") || k.contains("etc")),
        "a traversal entry must be dropped, not extracted; kept: " + files.keySet());
  }

  @Test
  void keeps_a_likec4_file_whose_entry_name_exceeds_the_100_byte_ustar_limit() throws IOException {
    // A tar entry name > 100 bytes needs a GNU long-name (././@LongLink) or PAX header; commons-compress
    // reads either transparently, presenting the FULL name. Nothing tested that such a long-named LikeC4
    // file is still normalised by PathSafety and kept by the filter (real GitLab paths rarely exceed 100
    // bytes, but a deep .likec4 tree could). Build one in GNU long-file mode and assert it is extracted.
    String longDir = "d".repeat(120); // 120 + the "repo-main-abc/" prefix + "/model.likec4" >> 100 bytes
    String entryName = "repo-main-abc/" + longDir + "/model.likec4";
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bos))) {
      tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
      byte[] data = "model {}".getBytes(StandardCharsets.UTF_8);
      TarArchiveEntry te = new TarArchiveEntry(entryName);
      te.setSize(data.length);
      tar.putArchiveEntry(te);
      tar.write(data);
      tar.closeArchiveEntry();
    }
    Map<String, String> files = GitLabArchiveExtractor.extract(new ByteArrayInputStream(bos.toByteArray()), "");
    // The leading top-level dir is stripped; the (long) relative path survives, normalised and kept.
    assertEquals(Map.of(longDir + "/model.likec4", "model {}"), files);
  }

  @Test
  void skips_a_symlink_entry_even_when_its_name_looks_like_a_likec4_file() throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bos))) {
      byte[] real = "model {}".getBytes(StandardCharsets.UTF_8);
      TarArchiveEntry file = new TarArchiveEntry("repo-main-abc/model.likec4");
      file.setSize(real.length);
      tar.putArchiveEntry(file);
      tar.write(real);
      tar.closeArchiveEntry();
      // A symlink entry whose NAME ends .likec4 must not be injected as an (empty) file.
      TarArchiveEntry link = new TarArchiveEntry("repo-main-abc/evil.likec4", TarArchiveEntry.LF_SYMLINK);
      link.setLinkName("../../../../etc/passwd");
      tar.putArchiveEntry(link);
      tar.closeArchiveEntry();
    }
    Map<String, String> files = GitLabArchiveExtractor.extract(new ByteArrayInputStream(bos.toByteArray()), "");
    assertEquals(Map.of("model.likec4", "model {}"), files);
    assertFalse(files.containsKey("evil.likec4"));
  }

  @Test
  void strips_a_leading_utf8_bom_from_kept_content() throws IOException {
    Map<String, String> entries = new LinkedHashMap<>();
    entries.put("repo-main-abc/model.likec4", "\uFEFFspecification {}"); // content begins with a BOM
    Map<String, String> files = GitLabArchiveExtractor.extract(new ByteArrayInputStream(tarGz(entries)), "");
    assertEquals("specification {}", files.get("model.likec4")); // BOM removed so the parser sees clean DSL
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

  private static String repeat(char c, int n) {
    char[] a = new char[n];
    java.util.Arrays.fill(a, c);
    return new String(a);
  }

  @Test
  void rejects_an_entry_larger_than_the_per_entry_cap() throws IOException {
    Map<String, String> entries = new LinkedHashMap<>();
    entries.put("repo-main-abc/big.likec4", repeat('a', 5000));
    byte[] gz = tarGz(entries);
    IOException ex = assertThrows(IOException.class,
        () -> GitLabArchiveExtractor.extract(new ByteArrayInputStream(gz), "", 1_000_000, 5000, 1024));
    assertTrue(ex.getMessage().contains("exceeds") && ex.getMessage().contains("per-entry"),
        "message should explain the per-entry cap, was: " + ex.getMessage());
    // The offending KEPT entry is named (by its validated relative path) so an operator can find it,
    // matching the duplicate-entry message. The top "<repo>-<ref>-<sha>/" dir is stripped by PathSafety.
    assertTrue(ex.getMessage().contains("big.likec4"),
        "message should name the offending entry, was: " + ex.getMessage());
  }

  @Test
  void caps_a_skipped_filtered_out_entry_too() {
    Map<String, String> entries = new LinkedHashMap<>();
    // A filtered-OUT entry (not a LikeC4 file) still advances the stream and must be bounded.
    entries.put("repo-main-abc/huge.bin", repeat('z', 5000));
    assertThrows(IOException.class, () -> {
      byte[] gz = tarGz(entries);
      GitLabArchiveExtractor.extract(new ByteArrayInputStream(gz), "", 1_000_000, 5000, 1024);
    });
  }

  @Test
  void rejects_total_uncompressed_bytes_over_the_cap() throws IOException {
    Map<String, String> entries = new LinkedHashMap<>();
    // Each entry is under the per-entry cap, but their sum exceeds the total cap.
    entries.put("repo-main-abc/a.likec4", repeat('a', 800));
    entries.put("repo-main-abc/b.likec4", repeat('b', 800));
    entries.put("repo-main-abc/c.likec4", repeat('c', 800));
    byte[] gz = tarGz(entries);
    IOException ex = assertThrows(IOException.class,
        () -> GitLabArchiveExtractor.extract(new ByteArrayInputStream(gz), "", 1500, 5000, 1024));
    assertTrue(ex.getMessage().contains("exceeds") && ex.getMessage().contains("total"),
        "message should explain the total cap, was: " + ex.getMessage());
  }

  @Test
  void counts_directory_and_symlink_entries_against_the_entry_count_cap() throws IOException {
    // A hostile/compromised GitLab can serve an archive of many zero-body directory/symlink/hard-link
    // header entries. These carry no content, so they never trip the per-entry/total byte caps; without
    // counting them against maxEntries the extract loop would spin once per entry, bounded only by the
    // wall-clock deadline. EVERY iterated entry — regardless of type — must count toward the cap.
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bos))) {
      for (int i = 0; i < 10; i++) {
        tar.putArchiveEntry(new TarArchiveEntry("repo-main-abc/d" + i + "/")); // directory entry (no body)
        tar.closeArchiveEntry();
      }
      TarArchiveEntry link = new TarArchiveEntry("repo-main-abc/s.likec4", TarArchiveEntry.LF_SYMLINK);
      link.setLinkName("model.likec4");
      tar.putArchiveEntry(link);
      tar.closeArchiveEntry();
    }
    IOException ex = assertThrows(IOException.class, () ->
        GitLabArchiveExtractor.extract(new ByteArrayInputStream(bos.toByteArray()), "", 1_000_000, 3, 1024));
    assertTrue(ex.getMessage().contains("exceeds") && ex.getMessage().contains("entry-count"),
        "directory/symlink entries must count toward the entry-count cap, was: " + ex.getMessage());
  }

  @Test
  void rejects_more_entries_than_the_entry_count_cap() throws IOException {
    Map<String, String> entries = new LinkedHashMap<>();
    entries.put("repo-main-abc/a.likec4", "1");
    entries.put("repo-main-abc/b.likec4", "2");
    entries.put("repo-main-abc/c.likec4", "3");
    byte[] gz = tarGz(entries);
    IOException ex = assertThrows(IOException.class,
        () -> GitLabArchiveExtractor.extract(new ByteArrayInputStream(gz), "", 1_000_000, 2, 1024));
    assertTrue(ex.getMessage().contains("exceeds") && ex.getMessage().contains("entry-count"),
        "message should explain the entry-count cap, was: " + ex.getMessage());
    // The message must guide the operator to the actual remedy (narrow path=), mirroring the total-bytes
    // cap: the count includes dirs/non-LikeC4 files, so "N entries" alone is misleading when few are diagrams.
    assertTrue(ex.getMessage().contains("narrow") && ex.getMessage().contains("path"),
        "message should point the operator at narrowing path=, was: " + ex.getMessage());
  }

  /** Uncompressed tar bytes for the given entries (no gzip wrapper). */
  private static byte[] tarBytes(Map<String, String> entries) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bos)) {
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

  /** A single gzip member wrapping data[off..off+len). */
  private static byte[] gzipMember(byte[] data, int off, int len) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (GzipCompressorOutputStream gz = new GzipCompressorOutputStream(bos)) {
      gz.write(data, off, len);
    }
    return bos.toByteArray();
  }

  @Test
  void reads_a_multi_member_concatenated_gzip_in_full() throws IOException {
    // A proxy/CDN can deliver the archive body as two concatenated gzip members. Without
    // decompressConcatenated=true the tar is truncated at the first member boundary and entries are
    // silently lost; with it, all members are read and every entry surfaces.
    Map<String, String> entries = new LinkedHashMap<>();
    entries.put("repo-main-abc/a.likec4", repeat('a', 4000));
    entries.put("repo-main-abc/b.likec4", repeat('b', 4000));
    byte[] tar = tarBytes(entries);
    int mid = tar.length / 2;
    ByteArrayOutputStream concat = new ByteArrayOutputStream();
    concat.write(gzipMember(tar, 0, mid));
    concat.write(gzipMember(tar, mid, tar.length - mid));

    Map<String, String> files =
        GitLabArchiveExtractor.extract(new ByteArrayInputStream(concat.toByteArray()), "");
    assertEquals(2, files.size(), "both members' entries must be read, not just the first");
    assertEquals(repeat('a', 4000), files.get("a.likec4"));
    assertEquals(repeat('b', 4000), files.get("b.likec4"));
  }

  @Test
  void the_default_caps_overload_enforces_the_per_entry_default() throws IOException {
    // The production path uses the 2-arg extract(stream, prefix) overload with the 16 MiB/32 MiB/5000
    // DEFAULTS. Assert those defaults are actually wired through: a filtered-out entry just over the
    // per-entry default is rejected (no sink is buffered for a non-kept entry, so this stays cheap).
    byte[] big = new byte[(int) GitLabArchiveExtractor.DEFAULT_MAX_ENTRY_BYTES + 16];
    java.util.Arrays.fill(big, (byte) 'z');
    byte[] gz = tarGzRaw("repo-main-abc/huge.bin", big);
    IOException ex = assertThrows(IOException.class,
        () -> GitLabArchiveExtractor.extract(new ByteArrayInputStream(gz), ""));
    assertTrue(ex.getMessage().contains("exceeds") && ex.getMessage().contains("per-entry"),
        "the 2-arg overload must apply the per-entry default cap, was: " + ex.getMessage());
  }

  @Test
  void rejects_an_archive_with_two_entries_that_normalize_to_the_same_kept_path() throws IOException {
    // git archive never repeats a path, but a crafted tar can carry the same project-relative path twice.
    // The later entry must NOT silently overwrite the earlier one in the result map (non-deterministic
    // output for the same bytes) — extraction must abort with a named duplicate-entry error instead.
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bos))) {
      for (String body : new String[] {"first", "second"}) {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry te = new TarArchiveEntry("repo-main-abc/dup.likec4");
        te.setSize(data.length);
        tar.putArchiveEntry(te);
        tar.write(data);
        tar.closeArchiveEntry();
      }
    }
    IOException ex = assertThrows(IOException.class,
        () -> GitLabArchiveExtractor.extract(new ByteArrayInputStream(bos.toByteArray()), ""));
    assertTrue(ex.getMessage().contains("duplicate") && ex.getMessage().contains("dup.likec4"),
        "the error should name the duplicated entry, was: " + ex.getMessage());
  }

  @Test
  void tolerates_duplicate_non_kept_entries_and_returns_only_the_kept_files() throws IOException {
    // The duplicate-entry guard is deliberately SCOPED to kept entries (only those enter `out`). A
    // real archive can legitimately repeat a filtered-out path (e.g. two README.md), so a duplicate the
    // filter drops must NOT abort extraction. This pins that scoping so a future widening of the guard
    // to all entries — which would break real archives — is caught by a failing test.
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bos))) {
      for (String name : new String[] {"repo-main-abc/README.md", "repo-main-abc/README.md",
          "repo-main-abc/model.likec4"}) {
        byte[] data = ("body of " + name).getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry te = new TarArchiveEntry(name);
        te.setSize(data.length);
        tar.putArchiveEntry(te);
        tar.write(data);
        tar.closeArchiveEntry();
      }
    }
    Map<String, String> files =
        GitLabArchiveExtractor.extract(new ByteArrayInputStream(bos.toByteArray()), "");
    assertEquals(1, files.size(), "the duplicated non-kept README.md must be dropped, not abort extraction");
    assertEquals("body of repo-main-abc/model.likec4", files.get("model.likec4"));
  }

  /**
   * A raw USTAR header (512 bytes) for {@code name} of type {@code typeFlag} declaring {@code size} body
   * bytes. Hand-rolled because {@link TarArchiveOutputStream} forces size 0 on non-file entries, which is
   * exactly the case we need to forge: a directory/symlink header that claims a large body.
   */
  private static byte[] rawTarHeader(String name, char typeFlag, long size) {
    byte[] h = new byte[512];
    byte[] nm = name.getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(nm, 0, h, 0, Math.min(nm.length, 100));
    putOctal(h, 100, 8, 0);        // mode
    putOctal(h, 108, 8, 0);        // uid
    putOctal(h, 116, 8, 0);        // gid
    putOctal(h, 124, 12, size);    // size
    putOctal(h, 136, 12, 0);       // mtime
    h[156] = (byte) typeFlag;      // typeflag
    System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, h, 257, 6);
    System.arraycopy("00".getBytes(StandardCharsets.US_ASCII), 0, h, 263, 2);
    for (int i = 148; i < 156; i++) h[i] = ' ';    // checksum field is spaces while summing
    int sum = 0;
    for (byte b : h) sum += (b & 0xFF);
    putOctal(h, 148, 7, sum);      // 6 octal digits + NUL
    h[154] = 0;
    h[155] = ' ';
    return h;
  }

  private static void putOctal(byte[] h, int off, int len, long value) {
    String s = Long.toOctalString(value);
    while (s.length() < len - 1) s = "0" + s;
    byte[] b = s.getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(b, 0, h, off, Math.min(b.length, len - 1));
    h[off + len - 1] = 0;
  }

  @Test
  void bounds_the_decompressed_bytes_of_a_symlink_body_the_body_caps_never_see() throws IOException {
    // A crafted archive can declare a LARGE body on a symlink entry (typeflag '2'). commons-compress
    // accepts a symlink with size>0, and the extract loop `continue`s past it (isSymbolicLink) WITHOUT
    // reading its body — so those bytes never pass through the per-entry/total *body* caps (which only
    // count bytes pulled via tar.read on regular files). Yet TarArchiveInputStream must still DECOMPRESS
    // that body to advance to the next header. Without a bound at the decompression boundary a ~KB
    // payload forces the extractor to inflate an unbounded volume (a CPU/latency DoS that pins a request
    // thread for the whole download deadline). The extractor must trip a decompressed-size cap instead.
    //
    // (Empirically: a directory entry '5' with a large body is rejected as "Corrupted TAR archive", and a
    // regular file '0' body is read+counted — the symlink '2' is the one non-file type that is both
    // accepted AND skipped-without-counting, so it is the live vector.)
    int bodyLen = 200_000; // highly compressible; gzips to a few hundred bytes
    byte[] tar = new byte[512 + ((bodyLen + 511) / 512) * 512 + 1024];
    byte[] hdr = rawTarHeader("repo-main-abc/link", '2', bodyLen); // typeflag '2' = symlink
    System.arraycopy(hdr, 0, tar, 0, 512);
    java.util.Arrays.fill(tar, 512, 512 + bodyLen, (byte) 'z'); // the body the extractor never reads
    // trailing bytes stay zero: body padding to the 512 boundary + the two zero blocks that mark tar EOF
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (GzipCompressorOutputStream gz = new GzipCompressorOutputStream(bos)) {
      gz.write(tar);
    }
    // Small caps: 1000 total body bytes, 5 entries. The decompressed-size backstop must fire on the
    // 200 KB the symlink body forces the stream to inflate, even though 0 body bytes reach tar.read.
    IOException ex = assertThrows(IOException.class, () ->
        GitLabArchiveExtractor.extract(new ByteArrayInputStream(bos.toByteArray()), "", 1000, 5, 1_000_000));
    assertTrue(ex.getMessage().contains("exceeds") && ex.getMessage().contains("decompressed"),
        "a symlink entry's large body must trip the decompressed-size cap, was: " + ex.getMessage());
  }

  @Test
  void a_normal_archive_within_caps_still_extracts() throws IOException {
    Map<String, String> entries = new LinkedHashMap<>();
    entries.put("repo-main-abc/model.likec4", "hello world");
    Map<String, String> files = GitLabArchiveExtractor.extract(
        new ByteArrayInputStream(tarGz(entries)), "", 1_000_000, 5000, 1_000_000);
    assertEquals(Map.of("model.likec4", "hello world"), files);
  }

  @Test
  void the_headroom_admits_a_many_small_entry_archive_whose_structure_dwarfs_the_body_budget()
      throws IOException {
    // The decompressed-size budget is maxTotalBytes PLUS a headroom (maxEntries*1024 + 65536) for the tar
    // structural overhead the body caps never see — a 512-byte header + up to 511 padding bytes per entry,
    // the trailer, and block padding. A legitimate archive of many tiny files decompresses to far more
    // gzip-output (all that structure) than its file BODIES total, so WITHOUT the headroom the
    // DecompressionBudget would trip on a perfectly valid archive. Pin that the headroom admits it: 120
    // tiny kept .likec4 files whose bodies total ~1 KiB but whose tar structure decompresses to ~120 KiB,
    // under a deliberately small maxTotalBytes (8 KiB). Only the headroom lets this through — a regression
    // shrinking/removing it (or counting structural bytes against maxTotalBytes) would fail this extract.
    Map<String, String> entries = new LinkedHashMap<>();
    for (int i = 0; i < 120; i++) entries.put("repo-main-sha/f" + i + ".likec4", "specifies");
    byte[] archive = tarGz(entries);
    // The gzip-COMPRESSED archive is tiny (120 near-identical files compress well); the risk this pins is
    // the DECOMPRESSED size, which is what the DecompressionBudget bounds.
    assertTrue(archive.length < 8192, "the archive is highly compressible; was " + archive.length + " bytes");

    Map<String, String> out = GitLabArchiveExtractor.extract(
        new ByteArrayInputStream(archive), "", 8192, 200, 8192);
    assertEquals(120, out.size(), "every tiny file must extract; the headroom must admit the structural overhead");
    assertEquals("specifies", out.get("f0.likec4"));
    assertEquals("specifies", out.get("f119.likec4"));
  }

  @Test
  void decompression_budget_skip_terminates_when_the_delegate_read_returns_zero() {
    // TarArchiveInputStream advances past an entry body it doesn't hand to the caller by calling skip();
    // DecompressionBudget routes that through read() to enforce the budget incrementally. read(byte[],int,
    // int) is contractually allowed to return 0 (no progress) even for len>0, so the bounded-skip loop MUST
    // treat a non-positive read as terminal (r <= 0). A delegate that persistently returns 0 would otherwise
    // spin the loop forever, pinning a request thread — the exact DoS this decompression-bomb guard exists
    // to close. The preemptive timeout turns a regression back to `r < 0` into a fast failure, not a hang.
    InputStream alwaysZero = new InputStream() {
      @Override public int read() { return 0; }
      @Override public int read(byte[] b, int off, int len) { return 0; }
    };
    GitLabArchiveExtractor.DecompressionBudget budget =
        new GitLabArchiveExtractor.DecompressionBudget(alwaysZero, 1024);
    assertTimeoutPreemptively(Duration.ofSeconds(2),
        () -> assertEquals(0L, budget.skip(4096)),
        "skip() must terminate when the delegate read() makes no progress, not loop forever");
  }

  @Test
  void decompression_budget_skip_trips_the_cap_reading_through_read() {
    // skip() must enforce the decompressed-size cap INCREMENTALLY (routing through read()) rather than
    // delegating to the underlying skip() — which would inflate the whole body before any check. A large
    // skip over an endless delegate trips the cap on the first over-limit chunk.
    InputStream endless = new InputStream() {
      @Override public int read() { return 'x'; }
      @Override public int read(byte[] b, int off, int len) {
        java.util.Arrays.fill(b, off, off + len, (byte) 'x');
        return len;
      }
    };
    GitLabArchiveExtractor.DecompressionBudget budget =
        new GitLabArchiveExtractor.DecompressionBudget(endless, 4096);
    IOException ex = assertThrows(IOException.class, () -> budget.skip(1_000_000));
    assertTrue(ex.getMessage().contains("decompressed"),
        "skip() must enforce the decompressed-size cap incrementally, was: " + ex.getMessage());
  }

  @Test
  void decompression_budget_accounting_is_overflow_safe_at_a_saturated_limit() {
    // The explicit-cap overload lets the budget saturate to Long.MAX_VALUE. The running total must be
    // checked overflow-safely (`total > limit - n`), NOT as `total + n > limit`: near a Long.MAX_VALUE
    // limit the latter wraps `total + n` to a NEGATIVE value, so the check reads false forever and the
    // decompression-bomb cap is silently disabled. Pin the pure accounting helper directly (inflating
    // ~9.2 EB through the real stream to trigger the wrap is not feasible in a unit test).
    long max = Long.MAX_VALUE;
    // A saturated budget must not spuriously trip on a legitimate read while there is genuine headroom.
    assertFalse(GitLabArchiveExtractor.DecompressionBudget.wouldExceed(0L, 8192L, max),
        "a fresh read under a Long.MAX_VALUE budget must not trip");
    // The wrap case: total is one short of the limit and the next chunk would push past it. The
    // overflow-safe check must report this as exceeded; the naive `total + n > limit` would wrap negative
    // and (wrongly) report NOT exceeded, disabling the cap.
    assertTrue(GitLabArchiveExtractor.DecompressionBudget.wouldExceed(max - 5L, 10L, max),
        "a read that would carry total past a saturated limit must be reported as exceeding it");
    // Boundary parity with the old inline check: total == limit exactly is still allowed (not exceeded);
    // one byte more is exceeded.
    assertFalse(GitLabArchiveExtractor.DecompressionBudget.wouldExceed(50L, 50L, 100L),
        "landing exactly on the limit must be allowed, as before");
    assertTrue(GitLabArchiveExtractor.DecompressionBudget.wouldExceed(51L, 50L, 100L),
        "exceeding the limit by one byte must trip, as before");
  }

  @Test
  void decompression_budget_single_byte_read_counts_toward_the_cap() throws IOException {
    // The single-arg read() overload accounts each byte too (account(1)); the tar path uses the array
    // overload, but the 1-arg read must not be a hole that lets a caller bypass the cap one byte at a time.
    InputStream data = new java.io.ByteArrayInputStream(new byte[] {'a', 'b', 'c', 'd', 'e'});
    GitLabArchiveExtractor.DecompressionBudget budget =
        new GitLabArchiveExtractor.DecompressionBudget(data, 4);
    assertEquals('a', budget.read()); // total 1
    assertEquals('b', budget.read()); // total 2
    assertEquals('c', budget.read()); // total 3
    assertEquals('d', budget.read()); // total 4 (== limit, still allowed)
    IOException ex = assertThrows(IOException.class, budget::read, // total 5 > 4 -> trips the cap
        "the 5th single-byte read must trip the decompressed-size cap");
    assertTrue(ex.getMessage().contains("decompressed"), "was: " + ex.getMessage());
  }

  @Test
  void decompression_budget_single_byte_read_at_eof_is_not_counted() throws IOException {
    // The single-arg read() must NOT account the -1 EOF sentinel (the `b >= 0` guard). After the budget
    // lands EXACTLY on the limit, the terminating EOF read must return -1 cleanly — accounting it would
    // spuriously trip the cap on a stream whose real content sits precisely at the bound.
    InputStream data = new java.io.ByteArrayInputStream(new byte[] {'a', 'b', 'c', 'd'});
    GitLabArchiveExtractor.DecompressionBudget budget =
        new GitLabArchiveExtractor.DecompressionBudget(data, 4);
    for (int i = 0; i < 4; i++) budget.read(); // total 4 == limit, all allowed
    assertEquals(-1, budget.read(), "EOF (-1) must not be accounted against the cap nor trip it");
  }
}
