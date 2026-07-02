package com.phrontizo.likec4.source;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
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

  /** Hard caps that bound the work a single archive can impose (decompression-bomb defence). */
  // Bounds the TOTAL uncompressed bytes read across the whole requested subtree. Note it counts EVERY
  // regular-file entry scanned, INCLUDING non-LikeC4 files the type filter drops (the body is still read
  // through the bounded loop so a skipped huge entry can't inflate memory) — so a subtree with a lot of
  // large non-LikeC4 content (images/binaries) can trip this even when its LikeC4 sources are tiny. The
  // GitLab `path=` pre-filter narrows to the subtree but does not drop non-LikeC4 files; narrowing the
  // macro's path to the diagrams directory is the operator-side remedy. The error message says as much.
  public static final long DEFAULT_MAX_TOTAL_BYTES = 32L * 1024 * 1024; // 32 MiB uncompressed
  public static final int DEFAULT_MAX_ENTRIES = 5000;
  public static final long DEFAULT_MAX_ENTRY_BYTES = 16L * 1024 * 1024; // 16 MiB per entry

  public static Map<String, String> extract(InputStream tarGz, String pathPrefix) throws IOException {
    return extract(tarGz, pathPrefix, DEFAULT_MAX_TOTAL_BYTES, DEFAULT_MAX_ENTRIES, DEFAULT_MAX_ENTRY_BYTES);
  }

  /**
   * Extract with explicit caps. Each entry is read through a bounded loop (never
   * {@code readAllBytes}); the per-entry read is bounded regardless of whether the entry is kept, so
   * a skipped huge entry cannot inflate memory, and the running total is checked across <em>all</em>
   * entries read. The {@code maxEntries} cap counts <em>every</em> iterated entry — files,
   * directories, symlinks and hard links alike — so a flood of zero-body header entries cannot spin
   * the loop unbounded. Exceeding any cap aborts with an {@link IOException}.
   *
   * <p>The per-entry/total <em>body</em> caps only count bytes pulled through {@code tar.read} on
   * regular files. Bytes the tar layer decompresses to <em>advance</em> the stream — record headers,
   * body padding, the trailer, concatenated-member filler, and (crucially) the body of a symlink entry
   * that declares a large size but which the type filter {@code continue}s past without reading — never
   * touch those caps. A crafted archive can therefore force the gzip layer to inflate an unbounded
   * volume from a tiny payload (a CPU/latency DoS). To close that, the whole gzip stream is wrapped in
   * a {@link DecompressionBudget} that trips once the total <em>decompressed</em> output exceeds
   * {@code maxTotalBytes} plus a headroom for structural overhead — so every byte the tar layer pulls,
   * however it pulls it, is bounded.
   */
  public static Map<String, String> extract(
      InputStream tarGz, String pathPrefix, long maxTotalBytes, int maxEntries, long maxEntryBytes)
      throws IOException {
    Map<String, String> out = new LinkedHashMap<>();
    long totalBytes = 0;
    // long, not int: with the explicit-cap overload passing maxEntries == Integer.MAX_VALUE, an int
    // counter would wrap to Integer.MIN_VALUE after 2^31 header entries, making (entryCount > maxEntries)
    // false forever and disabling the entry-count cap for the rest of the stream. A long never wraps in
    // any realistic run (the DecompressionBudget + wall-clock deadline bound it far sooner regardless).
    long entryCount = 0;
    byte[] buf = new byte[8192];
    // Hard ceiling on TOTAL decompressed (gzip-output) bytes: the retained/skipped body budget plus a
    // headroom for tar structural overhead the body caps don't see — a 512-byte header + up to 511
    // padding bytes per entry, the trailer, and record blocking. This is the sole bound on bytes the
    // tar layer decompresses only to advance the stream (see the javadoc): a legitimate archive that
    // trips the in-loop maxTotalBytes body check produces at most this many gzip-output bytes, while a
    // symlink-body / concatenated-filler bomb trips it long before it can inflate gigabytes.
    // Saturating sum: an adversarially large maxTotalBytes/maxEntries (only reachable via this
    // explicit-cap overload) must not overflow the budget to a NEGATIVE limit, which would fail the
    // extract closed with a confusing "exceeds ... (-N bytes)" message. Saturating to Long.MAX_VALUE is
    // safe — the in-loop maxTotalBytes/maxEntryBytes/maxEntries checks remain the real bound on the work
    // done; this budget only bounds the extra bytes the gzip layer inflates to *advance* the stream.
    long headroom = (long) Math.max(0, maxEntries) * 1024L + 65_536L; // int*1024L + const cannot overflow
    long maxDecompressedBytes =
        maxTotalBytes > Long.MAX_VALUE - headroom ? Long.MAX_VALUE : maxTotalBytes + headroom;
    // decompressConcatenated=true so a multi-member gzip stream is read in full. A single GitLab
    // archive is normally one gzip member, but a proxy/CDN (or a future GitLab change) can deliver the
    // body as concatenated members; without this the tar would be silently TRUNCATED at the first
    // member boundary, dropping entries with no error. The existing per-entry/total/count caps still
    // bound the work across all members.
    // Adopt the raw stream as its OWN try-with-resources resource so it is closed on EVERY exit path.
    // GzipCompressorInputStream validates the gzip magic in its constructor and throws on a non-gzip
    // body (a compromised/misbehaving GitLab returning a 200 that is not an archive); that throw happens
    // before `tar` is assigned, so without this the try-with-resources would close nothing and leak the
    // stream. On the success path `tar.close()` also closes the chain down to `in`, but a second close()
    // is a harmless no-op (idempotent), and the production caller already double-closes it via its own
    // try-with-resources.
    try (InputStream in = tarGz;
        TarArchiveInputStream tar =
            new TarArchiveInputStream(
                new DecompressionBudget(new GzipCompressorInputStream(in, true), maxDecompressedBytes))) {
      TarArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        // Count EVERY iterated entry against maxEntries, BEFORE the type filter below. Directory,
        // symlink, hard-link and other non-file entries carry no body bytes, so they never touch the
        // per-entry/total byte caps; a hostile/compromised GitLab could otherwise serve millions of
        // such zero-body header entries and spin this loop once per entry, bounded only by the
        // wall-clock deadline. Counting all entries here bounds the iteration regardless of entry type.
        if (++entryCount > maxEntries) {
          // Like the total-bytes message, spell out that this counts EVERY iterated entry — directories,
          // symlinks and non-LikeC4 files, not only LikeC4 sources — so an operator whose subtree has many
          // files (even if few are diagrams) knows the remedy is to narrow the macro's path=, not that the
          // diagrams alone blew the cap.
          throw new IOException("archive exceeds entry-count limit (" + maxEntries + " entries; counts all"
              + " archive entries — directories and non-LikeC4 files too, not only LikeC4 sources — narrow"
              + " the macro's path to the diagrams directory if the requested subtree contains many files)");
        }
        // Only regular files carry source content. isFile() is permissive (it reports true for any
        // non-directory whose name lacks a trailing slash, INCLUDING symlinks), so exclude links
        // explicitly — otherwise a symlink/hard-link entry named *.likec4 would be injected into the
        // bundle as a bogus (empty) file.
        if (entry.isDirectory() || entry.isSymbolicLink() || entry.isLink() || !entry.isFile()) continue;
        Optional<String> rel = PathSafety.safeRelative(entry.getName(), pathPrefix);
        String name = rel.orElse(null);
        boolean keep = name != null && LikeC4FileFilter.keep(name);
        // A well-formed GitLab archive never repeats a path. A crafted tar with two KEPT entries that
        // normalise to the same relative path would otherwise let the later one SILENTLY overwrite the
        // earlier in `out` (last-write-wins), making the bundle non-deterministic for the same input.
        // The guard is deliberately scoped to kept entries (`out` only ever holds those), so a duplicate
        // that the filter drops can't collide. Reject explicitly rather than pick an arbitrary winner.
        if (keep && out.containsKey(name)) {
          throw new IOException("archive contains a duplicate entry: " + name);
        }

        // Read the entry's bytes through a bounded loop even when it is filtered out, so a skipped
        // huge entry still trips the caps. Kept entries are accumulated; others are counted only.
        ByteArrayOutputStream sink = keep ? new ByteArrayOutputStream() : null;
        long entryBytes = 0;
        int n;
        while ((n = tar.read(buf)) != -1) {
          entryBytes += n;
          if (entryBytes > maxEntryBytes) {
            // Name the offending entry (parity with the duplicate-entry message) so an operator can
            // find the too-big file. Use the validated relative `name`, never the raw archive name:
            // PathSafety.safeRelative already rejected traversal/absolute/control-char names (returning
            // null → no name appended), so a non-null `name` is a control-char-free relative path and
            // can't inject CR/LF into a log line. Present for both kept and filtered-out entries.
            throw new IOException("archive exceeds per-entry size limit (" + maxEntryBytes + " bytes)"
                + (name != null ? " for entry: " + name : ""));
          }
          totalBytes += n;
          if (totalBytes > maxTotalBytes) {
            // This counts EVERY file in the requested subtree, not only LikeC4 sources (see the note on
            // DEFAULT_MAX_TOTAL_BYTES), so a subtree padded with large non-LikeC4 content trips it even
            // when the LikeC4 files are tiny. Say so, so the operator narrows the macro's path=.
            throw new IOException("archive exceeds total uncompressed-size limit (" + maxTotalBytes
                + " bytes; counts all files in the requested subtree, not only LikeC4 sources — narrow the"
                + " macro's path to the diagrams directory if it also contains large non-LikeC4 files)");
          }
          if (sink != null) sink.write(buf, 0, n);
        }
        if (keep) {
          out.put(name, stripBom(decodeUtf8(sink.toByteArray(), name)));
        }
      }
    }
    return out;
  }

  /**
   * Strictly decode a kept entry's bytes as UTF-8. The LikeC4 DSL is expected to be UTF-8; decoding
   * with {@link CodingErrorAction#REPORT} surfaces a clear {@link IOException} naming the offending file
   * rather than the previous lossy behaviour (silent U+FFFD substitution that mangled the DSL and then
   * failed in the parser far from the real cause).
   */
  private static String decodeUtf8(byte[] bytes, String name) throws IOException {
    CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException e) {
      throw new IOException("archive entry is not valid UTF-8: " + name, e);
    }
  }

  /** Drop a leading UTF-8 byte-order mark so the LikeC4 parser sees clean DSL, not a U+FEFF prefix. */
  private static String stripBom(String s) {
    return (!s.isEmpty() && s.charAt(0) == '\uFEFF') ? s.substring(1) : s;
  }

  /**
   * Bounds the total number of DECOMPRESSED bytes drawn from the gzip layer. Interposed between the
   * gzip stream and the tar reader so <em>every</em> byte the tar layer pulls — data, headers, padding,
   * trailer, concatenated-member filler, and the body of a non-file entry it skips over — is counted,
   * not just the file bodies the extract loop reads via {@code tar.read}.
   *
   * <p>Package-private (not {@code private}) so its {@link #skip(long)} bounded-read loop and budget
   * accounting can be unit-tested directly against an adversarial delegate stream (e.g. one that returns
   * {@code 0} from {@code read}, or an endless stream) rather than only through the whole-archive path.
   */
  static final class DecompressionBudget extends FilterInputStream {
    private final long limit;
    private long total;

    DecompressionBudget(InputStream in, long limit) {
      super(in);
      this.limit = limit;
    }

    private void account(long n) throws IOException {
      // Overflow-safe: check BEFORE adding, phrased as `total > limit - n` (never `total + n > limit`).
      // With the explicit-cap overload the limit can saturate to Long.MAX_VALUE (see the maxDecompressedBytes
      // note above), so `total + n` could in principle wrap PAST Long.MAX_VALUE to a negative value, making
      // `total + n > limit` false forever and silently DISABLING the sole decompression-bomb backstop — the
      // same wrap the `long entryCount` choice guards against for the entry cap. `total > limit - n` cannot
      // wrap (n >= 0 at every call site, and limit - n stays non-negative for any realistic n) and is
      // identical to the old check for every non-overflowing input. A long could only wrap after ~9.2 EB,
      // which the wall-clock DeadlineInputStream bounds long before — but the cap is meant to be the hard
      // bound, so it is made literally true rather than "true up to 9.2 EB".
      if (wouldExceed(total, n, limit)) {
        throw new IOException("archive exceeds total decompressed-size limit (" + limit + " bytes)");
      }
      total += n;
    }

    /**
     * Whether adding {@code n} (a non-negative count of decompressed bytes just read) to the running
     * {@code total} would exceed {@code limit}, computed overflow-safely as {@code total > limit - n}.
     * Package-private and static so the overflow-safety can be unit-tested at a saturated (Long.MAX_VALUE)
     * limit without having to actually inflate 9.2 EB through the stream.
     */
    static boolean wouldExceed(long total, long n, long limit) {
      return total > limit - n;
    }

    @Override
    public int read() throws IOException {
      int b = super.read();
      if (b >= 0) account(1);
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int n = super.read(b, off, len);
      if (n > 0) account(n);
      return n;
    }

    @Override
    public long skip(long n) throws IOException {
      // Route skips through read() so the budget is enforced INCREMENTALLY. TarArchiveInputStream
      // advances past an entry body it does not hand to the caller by skipping; delegating to the
      // underlying gzip skip() would inflate the WHOLE body before we could check the cap (the DoS we
      // are defending against). Reading in bounded chunks trips the limit before that happens.
      if (n <= 0) return 0;
      byte[] scratch = new byte[(int) Math.min(n, 8192L)];
      long skipped = 0;
      while (skipped < n) {
        int r = read(scratch, 0, (int) Math.min(scratch.length, n - skipped));
        // r <= 0 is terminal: -1 is EOF, and read() is contractually allowed to return 0 (no progress)
        // even for a positive len. Treating 0 as "keep going" (the naive `r < 0`) would spin this loop
        // forever on a delegate that never advances — pinning a request thread with no deadline reachable
        // from inside this stream. A short/partial skip is fine: the tar reader re-issues skip/read for
        // the remainder, so returning fewer bytes than requested never corrupts the stream position.
        if (r <= 0) break;
        skipped += r;
      }
      return skipped;
    }
  }
}
