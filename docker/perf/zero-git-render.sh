#!/usr/bin/env bash
# Zero-git server-render proof — spec §11 ("the macro render does zero server-side git work").
#
# The LikeC4 macro is a thin shell: at SERVER render time it validates params, requires the
# web-resource, and emits ONLY a <div class="likec4-diagram" data-...>. ALL git work (resolve ref->sha,
# download the source subtree from GitLab) happens later, in the BROWSER, when the web-resource boots
# the worker. This script proves that split against the live mock-GitLab request counters:
#
#   (A) SERVER render, no JS: reset the counters, then `curl` the rendered page-view HTML (no browser,
#       so no web-resource/worker runs). Assert the macro <div> IS present (the macro executed) yet the
#       mock saw commits==0 AND archive==0 (zero git at render time).
#   (B) BROWSER render, JS runs: reset again, load the SAME page in Playwright so the worker fetches
#       /resolve + /source; the mock counters MUST now move (git is browser-driven).
#
# Prereqs: Confluence up with the plugin + admin config (baseUrl->mock, allowlist has the project),
# the mock-GitLab running with the §11 /__count + /__reset endpoints, Docker.
set -euo pipefail
cd "$(dirname "$0")"

# BASE defaults to the docker-compose stack, resolved like the e2e gate/render runner
# (resolve-confluence-base.sh honours CONFLUENCE_PORT / docker/.env, else :8090) rather than the retired
# amps `confluence:run` backend (:1990/confluence). The mock-GitLab live counters this reads sit at
# :8099, which the compose stack only PUBLISHES under the compose.mock-publish.yaml overlay — bring the
# stack up with that overlay and set AUTH_PLAIN; override BASE/MOCK for the historical amps :1990 flow.
# See docker/perf/README.md.
# shellcheck source=../lib/resolve-confluence-base.sh
. ../lib/resolve-confluence-base.sh
BASE="${BASE:-$CONFLUENCE_BASE}"
AUTH_PLAIN="${AUTH_PLAIN:-admin:admin}"
MOCK="${MOCK:-http://localhost:8099}"
PAGE_TITLE="${PAGE_TITLE:-LikeC4 Test Diagram}"
# Pinned by DIGEST, not just the mutable v1.48.0-jammy tag — see docker/e2e/c10-gate.sh for the
# rationale; refresh the @sha256 whenever you bump the 1.48.0 pin (docker honours the digest, ignores
# the tag). Overridable via PW_IMAGE for a local experiment.
PW_IMAGE="${PW_IMAGE:-mcr.microsoft.com/playwright:v1.48.0-jammy@sha256:7dbbf924428aad5c87a5a3a5bc38f23e110cb1f5427fbbc7dbc3231014a4b0db}"
SPACE="${SPACE:-LIKEC4}"

# Robust counter parse (mirrors run.sh's node parser): a missing/non-numeric/malformed/degraded body
# (the mock 5xx'd per its degrade-don't-crash contract, or a partial curl read) must yield "0", not the
# string "undefined" — otherwise the later `[ "$B_COMMITS" -gt 0 ]` raises "integer expression expected".
# JSON.parse throws are caught, a missing key falls back via `||0`, and the callers add a `${:-0}` default.
jget() { node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{try{process.stdout.write(String(JSON.parse(d)[process.argv[1]]||0))}catch{process.stdout.write("0")}})' "$1"; }

echo ">> resolving page id for \"$PAGE_TITLE\" in space $SPACE"
PAGE_ID=$(curl -fsS -u "$AUTH_PLAIN" "$BASE/rest/api/content?spaceKey=$SPACE&title=$(node -e 'process.stdout.write(encodeURIComponent(process.argv[1]))' "$PAGE_TITLE")" \
  | node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{try{const j=JSON.parse(d);process.stdout.write((j.results&&j.results[0]&&j.results[0].id)||"")}catch{process.stdout.write("")}})')
[ -n "$PAGE_ID" ] || { echo "!! could not find page \"$PAGE_TITLE\""; exit 2; }
echo "   page id: $PAGE_ID"

FAIL=0

# ---- (A) SERVER render via REST storage->view (no JS) -----------------------------------------
# Render the stored page to its "view" representation server-side via REST. This executes the macro
# (LikeC4DiagramMacro.execute -> DiagramHtmlRenderer) and returns the rendered HTML in JSON — no
# browser, so the web-resource/worker never run. (viewpage.action is avoided: HTTP Basic auth on it
# redirects to login and yields no rendered body.)
echo ">> (A) resetting counters, then rendering storage->view via REST (no browser, no JS)"
curl -fsS "$MOCK/__reset" >/dev/null \
  || { echo "!! mock unreachable at $MOCK — bring the stack up with the compose.mock-publish.yaml overlay (see this script's header)" >&2; exit 2; }
HTML=$(curl -fsS -u "$AUTH_PLAIN" "$BASE/rest/api/content/$PAGE_ID?expand=body.view" \
  | node -e 'let d="";process.stdin.on("data",c=>c&&(d+=c)).on("end",()=>{try{const j=JSON.parse(d);process.stdout.write((j.body&&j.body.view&&j.body.view.value)||"")}catch{process.stdout.write("")}})')
echo "   server-rendered macro HTML: $(printf '%s' "$HTML" | grep -oE '<div class=\"likec4-diagram[^>]*>' | head -1)"
if printf '%s' "$HTML" | grep -q 'class="likec4-diagram"'; then
  echo "   PASS: server-rendered HTML contains the macro <div class=\"likec4-diagram\"> (macro executed)"
else
  echo "   FAIL: macro <div class=\"likec4-diagram\"> NOT found in server-rendered HTML"; FAIL=1
fi
A_COUNT=$(curl -fsS "$MOCK/__count")
A_COMMITS=$(printf '%s' "$A_COUNT" | jget commits); A_COMMITS=${A_COMMITS:-0}
A_ARCHIVE=$(printf '%s' "$A_COUNT" | jget archive); A_ARCHIVE=${A_ARCHIVE:-0}
echo "   mock counters after SERVER render: commits=$A_COMMITS archive=$A_ARCHIVE"
if [ "$A_COMMITS" = "0" ] && [ "$A_ARCHIVE" = "0" ]; then
  echo "   PASS: ZERO git at server render (commits==0 && archive==0)"
else
  echo "   FAIL: server render hit GitLab (commits=$A_COMMITS archive=$A_ARCHIVE) — macro is NOT zero-git"; FAIL=1
fi

# ---- (B) BROWSER render via Playwright (JS runs) ----------------------------------------------
echo ">> (B) resetting counters, then rendering the SAME page in a real browser (JS runs)"
curl -fsS "$MOCK/__reset" >/dev/null
# Capture the spec's REAL exit code: piping `docker run` straight into `grep -v` both masks the
# container exit (pipefail then reports grep's status) AND lets grep flip the verdict — if every
# output line matches the filter, `grep -v` exits 1 and would FALSELY fail a passing run. So capture
# output + rc separately, then filter only for the human-readable echo.
# zero-git-browser.spec.ts lives in docker/e2e (alongside the other Playwright specs), NOT here in
# docker/perf — so mount the e2e dir, mirroring run.sh / real-gitlab-e2e.sh. That dir carries the
# committed package.json + package-lock.json, so `npm ci` installs the pinned runner from the lockfile,
# with the same fail-loud/fall-back-to-the-image-runner handling as the siblings.
E2E_DIR="$(cd "$(dirname "$0")/../e2e" && pwd)"
B_OUT=$(docker run --rm --network host -v "$E2E_DIR:/work" -w /work \
  -e CONFLUENCE_BASE="$BASE" -e PAGE_TITLE="$PAGE_TITLE" -e AUTH_USER="${AUTH_PLAIN%%:*}" -e AUTH_PASS="${AUTH_PLAIN#*:}" \
  "$PW_IMAGE" sh -c 'if ! npm ci >/tmp/npm-install.log 2>&1; then echo "!! npm ci (against docker/e2e/package-lock.json) failed (offline, or a broken/partial install) — using the image-bundled runner; install-log tail:" >&2; tail -n 15 /tmp/npm-install.log >&2 || true; fi; npx playwright test zero-git-browser.spec.ts --reporter=line') && B_RC=0 || B_RC=$?
printf '%s\n' "$B_OUT" | grep -vE 'Downloading|MiB|npm notice|Changelog|To update' || true
[ "$B_RC" -eq 0 ] || { echo "!! browser render spec failed (exit $B_RC)"; FAIL=1; }
B_COUNT=$(curl -fsS "$MOCK/__count")
B_COMMITS=$(printf '%s' "$B_COUNT" | jget commits); B_COMMITS=${B_COMMITS:-0}
B_ARCHIVE=$(printf '%s' "$B_COUNT" | jget archive); B_ARCHIVE=${B_ARCHIVE:-0}
echo "   mock counters after BROWSER render: commits=$B_COMMITS archive=$B_ARCHIVE"
if [ "$B_COMMITS" -gt 0 ] || [ "$B_ARCHIVE" -gt 0 ]; then
  echo "   PASS: browser render DID drive git traffic (commits=$B_COMMITS archive=$B_ARCHIVE) — all git is browser-side"
else
  echo "   FAIL: browser render drove NO git traffic — proof inconclusive"; FAIL=1
fi

echo
echo "==== SUMMARY ===="
echo "  SERVER (curl, no JS): commits=$A_COMMITS archive=$A_ARCHIVE   <-- must be 0/0"
echo "  BROWSER (JS runs):    commits=$B_COMMITS archive=$B_ARCHIVE   <-- must move"
if [ "$FAIL" -ne 0 ]; then echo "ZERO-GIT-RENDER: FAIL"; exit 1; fi
echo "ZERO-GIT-RENDER: PASS"
