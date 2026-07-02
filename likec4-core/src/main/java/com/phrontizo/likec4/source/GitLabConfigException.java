package com.phrontizo.likec4.source;

/**
 * A plugin <em>misconfiguration</em> that GitLab itself is not responsible for — most notably a
 * missing/blank service token. Kept distinct from {@link GitLabException} (a real GitLab response)
 * and from a network {@link java.io.IOException} so the shared {@link CircuitBreaker} does NOT count
 * it as an outage.
 *
 * <p>Without this distinction a blank token makes <em>every</em> call throw, and — because
 * {@link SourceService#COUNTS_AS_OUTAGE} treats any non-{@link GitLabException} as an outage — after
 * {@code failureThreshold} renders the process-shared breaker would trip OPEN and fail EVERY LikeC4
 * diagram on the instance for the open window, while also masking the actionable "token is not
 * configured" message behind a generic {@code circuit breaker is open} error. GitLab is up and
 * answering the whole time; the fault is local config, so it must not count toward the breaker — the
 * same reasoning that keeps a 4xx client error from tripping it.
 *
 * <p>Extends {@link IllegalStateException} so existing callers/tests that catch that broader type
 * still match, and so a direct (non-breaker) caller sees the unchanged clear message.
 */
public final class GitLabConfigException extends IllegalStateException {
  public GitLabConfigException(String message) {
    super(message);
  }
}
