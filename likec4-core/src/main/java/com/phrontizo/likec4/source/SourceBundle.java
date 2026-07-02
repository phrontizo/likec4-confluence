package com.phrontizo.likec4.source;

import java.util.Map;
import java.util.Objects;

/** The filtered LikeC4 source subtree at a commit: sha plus relPath→content (immutable copy). */
public record SourceBundle(String sha, Map<String, String> files) {
  public SourceBundle {
    // This record enforces non-null + immutability only. The sha is the key for both caches and the
    // archive `sha=` param, so a null must be rejected here (it would otherwise serialize/cache
    // silently). The stronger "canonical lower-case 40-hex" form is an UPSTREAM contract guaranteed by
    // the producer (GitLabSourceClient.resolveSha/fetchSubtree validate + lower-case), not re-checked
    // here — test doubles legitimately construct bundles with synthetic opaque shas as cache-key tokens.
    // (Map.copyOf already rejects a null files map or any null key/value.)
    Objects.requireNonNull(sha, "sha");
    files = Map.copyOf(files);
  }
}
