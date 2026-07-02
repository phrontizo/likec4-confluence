#!/usr/bin/env bash
# Performance test runner — spec §11 ("Performance"). See README.md in this dir.
#
# Drives concurrent /resolve + /source load (k6, in the grafana/k6 container) against the live
# Confluence + plugin, then reads the mock-GitLab request counters and ASSERTS that server-side
# caching + single-flight coalesced the load: the GitLab `archive` endpoint is hit at most once per
# (project, sha, path) — i.e. far fewer times than the number of /source requests issued. Prints
# PASS/FAIL and exits non-zero on FAIL.
#
# Prereqs: Confluence up with the plugin installed + admin configured (baseUrl→mock, allowlist
# includes the project), the mock-GitLab running with the §11 counter endpoints (restart it after
# pulling this change so /__count + /__reset exist), Docker, and Node (harness prereq, used to parse
# JSON without a hard jq dependency).
set -euo pipefail
cd "$(dirname "$0")"

# BASE defaults to the docker-compose stack, resolved the SAME way as the e2e gate and render runner
# (resolve-confluence-base.sh honours CONFLUENCE_PORT / docker/.env, else :8090) rather than the retired
# amps `confluence:run` backend (:1990/confluence). The mock-GitLab counters this asserts on live at
# :8099, which the compose stack only PUBLISHES under the compose.mock-publish.yaml overlay — so bring
# the stack up with that overlay first:
#   docker compose -f docker/compose.yaml -f docker/compose.mock-publish.yaml up -d
#   AUTH_PLAIN=admin:<pass> docker/perf/run.sh
# See README.md in this dir. (Override BASE/MOCK to target the historical amps :1990 workflow.)
# shellcheck source=../lib/resolve-confluence-base.sh
. ../lib/resolve-confluence-base.sh
BASE="${BASE:-$CONFLUENCE_BASE}"
AUTH_PLAIN="${AUTH_PLAIN:-admin:admin}"
# base64 admin:admin for HTTP Basic; override AUTH directly to skip the encode. Strip BOTH \n and \r:
# GNU base64 wraps at 76 cols (\n) and some platforms can emit \r too — either would corrupt the header.
AUTH="${AUTH:-$(printf '%s' "$AUTH_PLAIN" | base64 | tr -d '\n\r')}"
MOCK="${MOCK:-http://localhost:8099}"
# Pinned like every other harness image (Playwright v1.48.0-jammy, confluence 10.2.13, postgres:15,
# the mock's node:20-alpine@sha256): :latest drifts, and a breaking k6 release (e.g. another summary-
# output change like the --summary-export one this script already works around) would silently break
# the perf gate. 0.49.0 supports handleSummary() (the digest this script parses from STDOUT).
K6_IMAGE="${K6_IMAGE:-grafana/k6:0.49.0}"
ARCHIVE_MAX="${ARCHIVE_MAX:-1}"   # one (project,sha,path) under test → at most one archive fetch
COMMITS_MAX="${COMMITS_MAX:-1}"   # one ref under test → at most one ref→sha commits lookup (0 if warm)
MIN_SOURCE="${MIN_SOURCE:-100}"   # a 50-VU/20s run issues thousands; guard against a no-op run

echo ">> resetting mock-GitLab counters ($MOCK/__reset)"
curl -fsS "$MOCK/__reset" >/dev/null \
  || { echo "!! mock unreachable at $MOCK — bring the stack up with the compose.mock-publish.yaml overlay (see this script's header)" >&2; exit 2; }

echo ">> driving concurrent /resolve + /source load with k6 ($K6_IMAGE)"
# k6 streams its live progress to STDERR; load-test.js's handleSummary() prints a parseable digest to
# STDOUT (request counts + threshold metrics). We capture STDOUT and parse the counts from it — this
# avoids depending on a summary FILE, which the unprivileged k6 container often cannot write into a
# host-owned bind mount (and which --summary-export no longer produces on current k6 images).
# Capture k6's exit code WITHOUT aborting: k6 returns non-zero (99) when a threshold is breached
# (load-test.js sets http_req_failed<1% and http_req_duration p95<2s) — precisely the error-rate /
# latency regressions this gate exists to catch. Under `set -euo pipefail` an unguarded
# `K6_OUT=$(...)` would abort the script right here, BEFORE the counter read and the caching
# assertions (and the PERF:FAIL summary) ever run — the operator would see a bare aborted run with no
# diagnostics. Fold the rc into the verdict below instead (mirrors zero-git-render.sh's rc capture).
K6_OUT=$(docker run --rm --network host \
  -e BASE="$BASE" -e AUTH="$AUTH" \
  -e VUS="${VUS:-50}" -e DURATION="${DURATION:-20s}" \
  -e PROJECT="${PROJECT:-acme/architecture}" -e REF="${REF:-main}" -e SRC_PATH="${SRC_PATH:-ok}" \
  -v "$PWD:/perf" -w /perf \
  "$K6_IMAGE" run /perf/load-test.js) && K6_RC=0 || K6_RC=$?
echo "$K6_OUT"

echo ">> reading mock-GitLab counters ($MOCK/__count)"
COUNT_JSON=$(curl -fsS "$MOCK/__count")
echo "   counters: $COUNT_JSON"

# k6 request counts from the handleSummary digest; archive/commits from the mock counter endpoint.
# Anchor the metric name to the START of its line (handleSummary emits one "  name:  count" per line),
# so the name can only match its own dedicated line — never as a substring/suffix of a longer metric
# name (e.g. a future likec4_source_reqs_total), which a leading ".*" could otherwise capture wrongly.
digit() { printf '%s\n' "$K6_OUT" | sed -n "s/^[[:space:]]*$1:[[:space:]]*\([0-9][0-9]*\).*/\1/p" | head -1; }
SOURCE_REQS=$(digit likec4_source_reqs);  SOURCE_REQS=${SOURCE_REQS:-0}
SOURCE_OK=$(digit likec4_source_ok);       SOURCE_OK=${SOURCE_OK:-0}
RESOLVE_REQS=$(digit likec4_resolve_reqs); RESOLVE_REQS=${RESOLVE_REQS:-0}
RESOLVE_OK=$(digit likec4_resolve_ok);     RESOLVE_OK=${RESOLVE_OK:-0}
# A non-JSON / empty body (the mock degraded to a 5xx per its own degrade-don't-crash contract, or a
# partial curl read) must not crash the harness: the node parser catches a JSON.parse throw and prints
# "0", and the ${:-0} default covers an empty substitution — otherwise the `[ "$ARCHIVE" -le ... ]`
# test below raises "unary operator expected" instead of reporting a clean PASS/FAIL (mirrors 57-59).
ARCHIVE=$(printf '%s' "$COUNT_JSON" | node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{try{process.stdout.write(String(JSON.parse(d).archive||0))}catch{process.stdout.write("0")}})'); ARCHIVE=${ARCHIVE:-0}
COMMITS=$(printf '%s' "$COUNT_JSON" | node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{try{process.stdout.write(String(JSON.parse(d).commits||0))}catch{process.stdout.write("0")}})'); COMMITS=${COMMITS:-0}

echo ">> issued $RESOLVE_REQS /resolve and $SOURCE_REQS /source request(s)"
echo ">> mock saw $COMMITS commits lookup(s) and $ARCHIVE archive fetch(es)"

FAIL=0

# (0) k6 itself must have exited cleanly. A non-zero rc means a threshold (error-rate or p95 latency)
#     was breached — a real regression, not a harness fault — so it is a FAIL, but we still run the
#     counter assertions below so the operator sees the full picture before PERF:FAIL.
if [ "$K6_RC" -eq 0 ]; then
  echo "PASS: k6 exited 0 — no threshold (http_req_failed / http_req_duration p95) breached"
else
  echo "FAIL: k6 exited $K6_RC — a threshold (error-rate <1% or p95 latency <2s) was breached"
  FAIL=1
fi

# (A) We actually generated a meaningful concurrent load.
if [ "$SOURCE_REQS" -ge "$MIN_SOURCE" ]; then
  echo "PASS: issued $SOURCE_REQS /source requests (≥ $MIN_SOURCE) — real concurrent load"
else
  echo "FAIL: only $SOURCE_REQS /source requests (< $MIN_SOURCE) — load too small to be meaningful"
  FAIL=1
fi

# (B) At most one GitLab archive per (project, sha, path) — server cache + single-flight coalesced
#     thousands of /source requests into ≤1 git fetch (0 if the server cache was already warm).
if [ "$ARCHIVE" -le "$ARCHIVE_MAX" ]; then
  echo "PASS: archive ($ARCHIVE) ≤ $ARCHIVE_MAX, far below /source requests ($SOURCE_REQS) — caching + single-flight hold"
else
  echo "FAIL: archive ($ARCHIVE) > $ARCHIVE_MAX — caching/single-flight did NOT coalesce the load"
  FAIL=1
fi

# (B') Disambiguate a 0-archive PASS: it must mean "coalesced / cache already warm", NOT "every /source
#      errored and never reached GitLab" (which ALSO yields archive=0). The k6 `source 200` check is
#      non-failing, so without this an all-error run could slip past on the loose http_req_failed
#      threshold alone. Require that /source actually produced successful 200 responses.
if [ "$SOURCE_OK" -ge 1 ]; then
  echo "PASS: $SOURCE_OK /source response(s) returned 200 — a 0-archive result means coalesced/warm, not all-failed"
else
  echo "FAIL: 0 successful /source responses (of $SOURCE_REQS issued) — archive=$ARCHIVE is 'all failed', not 'coalesced'"
  FAIL=1
fi

# (C) The whole run targets a SINGLE ref (REF=main), so the ref→sha cache must coalesce every /resolve
#     into at most ONE commits lookup (0 if the cache was already warm) — the direct analogue of (B)'s
#     archive bound. The old `commits ≤ RESOLVE_REQS` bound was near-vacuous: a totally disabled
#     RefShaCache (one commits hit per /resolve) yields commits == RESOLVE_REQS, which passed it — so the
#     regression this assertion names ("ref→sha cache coalesced") went uncaught. Bound to COMMITS_MAX(=1).
#     (The page-render path itself does zero git work: the macro emits only a <div class="likec4-diagram">;
#     all git traffic is the browser-driven REST calls measured above.)
if [ "$COMMITS" -le "$COMMITS_MAX" ]; then
  echo "PASS: commits ($COMMITS) ≤ $COMMITS_MAX, far below /resolve requests ($RESOLVE_REQS) — ref→sha cache coalesced"
else
  echo "FAIL: commits ($COMMITS) > $COMMITS_MAX — ref→sha cache did NOT coalesce the $RESOLVE_REQS /resolve requests"
  FAIL=1
fi

# (C') Disambiguate a 0-commits PASS, exactly as (B') does for a 0-archive PASS: commits=0 must mean
#      "coalesced / cache already warm", NOT "every /resolve errored and never reached GitLab" (which
#      ALSO yields commits=0). The k6 `resolve 200` check is non-failing and http_req_failed is measured
#      across resolve+source combined, so without this an all-resolve-failure masked by healthy sources
#      could slip past (C) on the loose error-rate threshold alone. Require ≥1 successful /resolve.
if [ "$RESOLVE_OK" -ge 1 ]; then
  echo "PASS: $RESOLVE_OK /resolve response(s) returned 200 — a 0-commits result means coalesced/warm, not all-failed"
else
  echo "FAIL: 0 successful /resolve responses (of $RESOLVE_REQS issued) — commits=$COMMITS is 'all failed', not 'coalesced'"
  FAIL=1
fi

if [ "$FAIL" -ne 0 ]; then echo "PERF: FAIL"; exit 1; fi
echo "PERF: PASS"
