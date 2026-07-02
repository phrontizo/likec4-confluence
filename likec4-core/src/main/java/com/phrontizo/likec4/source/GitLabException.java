package com.phrontizo.likec4.source;

import java.io.IOException;

/**
 * A GitLab API call returned a non-success status (or an unusable response).
 *
 * <p><b>Invariant — the message must never embed the upstream response body or the PRIVATE-TOKEN.</b>
 * Callers construct it with only a status + a short, sanitised context (e.g. {@code "resolve ref " + ref}
 * / {@code "archive at " + sha}). The plugin's REST layer logs the full throwable server-side
 * ({@code SourceRestResource}), so a message that ever carried a token or a raw GitLab response fragment
 * would leak it into the Confluence log. Keep messages fixed/sanitised.
 */
public final class GitLabException extends IOException {
  // IOException is Serializable, so this is too; pin the id rather than letting the compiler
  // synthesise one (which would change across builds and break cross-version deserialization).
  private static final long serialVersionUID = 1L;

  private final int status;

  public GitLabException(int status, String message) {
    super(status + ": " + message);
    this.status = status;
  }

  public int status() {
    return status;
  }
}
