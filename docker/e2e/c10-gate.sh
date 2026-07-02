#!/usr/bin/env bash
# c10-gate.sh — the LIVE per-iteration gate.
#
# Installs the freshly-built plugin JAR into the already-running compose Confluence 10.2.13 and runs
# the Confluence-10 runtime gates (GATE3 macro render, GATE4 admin servlet under WebSudo, GATE5 macro
# editor) via containerised Playwright. Exits non-zero if any gate fails.
#
# Prereqs: the compose stack is up (docker/up.sh) and the plugin JAR is built (./mvnw -B package).
#
# GOTCHA (hard-won): UPM "upgrade" of an unchanged SNAPSHOT version does NOT reliably redeploy the new
# bytecode (OSGi caches it), so this script does a clean UNINSTALL + INSTALL to force the fresh build.
# The uninstall wipes plugin settings, so it re-configures the admin (baseUrl/allowlist/token) after.
set -euo pipefail
cd "$(dirname "$0")/../.."   # repo root

# node parses the UPM plugin-enabled JSON in an `until` loop condition below; if it is missing the loop
# just fails every iteration and burns the full deadline before a misleading "plugin did not enable".
# `set -e` can't catch it (it is in a conditional), so check up front.
command -v node >/dev/null 2>&1 || { echo "c10-gate.sh: 'node' is required on PATH (Node 20+)" >&2; exit 1; }

# Resolve CONFLUENCE_BASE honouring an explicit override, else CONFLUENCE_PORT (env or docker/.env), else
# :8090 — so the gate targets the same port up.sh published when an operator customises CONFLUENCE_PORT,
# instead of hard-coding :8090 and aborting with "Confluence not RUNNING" against a stack published elsewhere.
. docker/lib/resolve-confluence-base.sh
BASE="$CONFLUENCE_BASE"
AUTH="${AUTH:-admin:admin}"
PKEY="${PLUGIN_KEY:-com.phrontizo.confluence.likec4-confluence}"
MOCK_BASEURL="${MOCK_BASEURL:-http://mockgitlab:8080}"
ALLOWLIST_JSON="${ALLOWLIST_JSON:-[\"acme\"]}"

# Pick exactly the plugin JAR — a strict glob (not `ls | grep -v sources | head -1`, which is fragile
# on odd names and silently picks one of several). Reject ambiguity so a stale older JAR can't be run.
JAR=""
for j in target/likec4-confluence-*.jar; do
  case "$j" in *-sources.jar|*-tests.jar) continue;; esac
  [ -e "$j" ] || continue
  [ -z "$JAR" ] || { echo "!! multiple plugin JARs under target/ ($JAR and $j) — clean stale builds" >&2; exit 1; }
  JAR="$j"
done
[ -n "$JAR" ] || { echo "!! no plugin JAR under target/ — run ./mvnw -B package first" >&2; exit 1; }

curl -fsS -m 10 "$BASE/status" | grep -q RUNNING || { echo "!! Confluence not RUNNING at $BASE — run docker/up.sh" >&2; exit 1; }

echo ">> clean reinstall (uninstall + install $JAR)"
curl -s -u "$AUTH" -X DELETE "$BASE/rest/plugins/1.0/$PKEY-key" -o /dev/null || true
# UPM uninstall is async. A fixed sleep can race a half-removed plugin and leave OSGi serving the OLD
# bytecode (the very failure this clean-reinstall exists to avoid) — poll until the plugin is gone.
echo ">> waiting for uninstall to complete"
undeadline=$(( $(date +%s) + 90 ))
until [ "$(curl -s -o /dev/null -w '%{http_code}' -u "$AUTH" "$BASE/rest/plugins/1.0/$PKEY-key")" = "404" ]; do
  # Do NOT proceed past a failed uninstall: installing over a half-removed plugin lets OSGi keep
  # serving the OLD bytecode, so GATE3/4/5 would pass against stale code (the exact failure this clean
  # reinstall exists to prevent). Fail loudly instead, mirroring install-upgrade.sh.
  [ "$(date +%s)" -gt "$undeadline" ] && { echo "!! plugin did not uninstall within 90s — aborting (stale-bytecode hazard)" >&2; exit 1; }
  sleep 2
done
TOKEN=$(curl -s -u "$AUTH" -H 'Accept: application/vnd.atl.plugins.installed+json' "$BASE/rest/plugins/1.0/" -D - -o /dev/null \
  | tr -d '\r' | awk -F': ' 'tolower($1)=="upm-token"{print $2}')
if [ -z "$TOKEN" ]; then
  # Classify the failure (one extra probe, only on the abort path): 401/403 = bad creds; a 2xx with no
  # UPM-token header = UPM unavailable; other = up-but-broken (500/HTML), not a credentials problem.
  code=$(curl -s -o /dev/null -w '%{http_code}' -u "$AUTH" \
    -H 'Accept: application/vnd.atl.plugins.installed+json' "$BASE/rest/plugins/1.0/")
  case "$code" in
    401|403) echo "!! could not obtain UPM token: auth rejected (HTTP $code) — check the admin/admin credentials" >&2 ;;
    2*)      echo "!! could not obtain UPM token: request succeeded (HTTP $code) but no UPM-token header — is UPM available?" >&2 ;;
    *)       echo "!! could not obtain UPM token (HTTP $code) — Confluence reachable but not serving the plugins API (up-but-broken?)" >&2 ;;
  esac
  exit 1
fi
# Fail loudly on an upload rejection (403 "Signature check failed" / 413 / 400) instead of swallowing
# it and then burning the 180s enable-poll on a plugin that was never uploaded.
up_code=$(curl -s -u "$AUTH" -X POST "$BASE/rest/plugins/1.0/?token=$TOKEN" -H 'Accept: application/json' \
  -F "plugin=@$JAR;type=application/java-archive" -o /dev/null -w '%{http_code}')
case "$up_code" in
  2*) ;;
  *) echo "!! plugin upload rejected (HTTP $up_code) — check the dev JVM flags (upload-enabled / signature-check-disabled)" >&2; exit 1;;
esac

echo ">> waiting for plugin enable"
deadline=$(( $(date +%s) + 180 ))
# Parse the UPM JSON's top-level `enabled` rather than grepping a brittle '"enabled":true' substring
# (a whitespace/pretty-print change, or an error/HTML body, would never match and burn the 180s deadline).
plugin_enabled() {
  curl -s -u "$AUTH" -H 'Accept: application/vnd.atl.plugins.plugin+json' "$BASE/rest/plugins/1.0/$PKEY-key" \
    | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{process.exit(JSON.parse(s).enabled===true?0:1)}catch{process.exit(1)}})'
}
until plugin_enabled; do
  [ "$(date +%s)" -gt "$deadline" ] && { echo "!! plugin did not enable in time" >&2; exit 1; }
  sleep 3
done

# UPM reports the plugin "enabled" a moment BEFORE its JAX-RS resource finishes registering, so an
# immediate admin-config POST can race a 404 (the endpoint isn't routed yet). Poll the admin GET until
# it returns 200 — meaning the resource is routed AND the config bean is wired (a transient 503 "plugin
# initialising" or 5xx must NOT count as ready, else the config POST below could race it and 5xx).
echo ">> waiting for the admin REST resource to register"
restdeadline=$(( $(date +%s) + 60 ))
until [ "$(curl -s -o /dev/null -w '%{http_code}' -u "$AUTH" "$BASE/rest/likec4/1.0/admin")" = "200" ]; do
  [ "$(date +%s)" -gt "$restdeadline" ] && { echo "!! admin REST resource did not register within 60s" >&2; exit 1; }
  sleep 2
done

echo ">> re-configuring admin (baseUrl=$MOCK_BASEURL, allowlist=$ALLOWLIST_JSON)"
cfg_code=$(curl -s -u "$AUTH" -X POST "$BASE/rest/likec4/1.0/admin" -H 'Content-Type: application/json' \
  -d "{\"baseUrl\":\"$MOCK_BASEURL\",\"allowlist\":$ALLOWLIST_JSON,\"token\":\"test-token\"}" \
  -o /dev/null -w '%{http_code}')
echo "   admin config HTTP $cfg_code"
# A failed admin config (e.g. baseUrl rejected) must fail here, not surface as a confusing render
# failure downstream in the gates.
case "$cfg_code" in 2*) ;; *) echo "!! admin re-config failed (HTTP $cfg_code)" >&2; exit 1;; esac

# Bust the on-disk bundle cache so the render/editor gates genuinely EXERCISE archive extraction rather
# than serving a stale cached bundle. This cache lives in the Confluence temp dir and is NOT wiped on
# uninstall, so it persists across reinstalls — which once let a completely broken extraction path
# (commons-compress missing from the bundle -> NoClassDefFoundError on every fetch) pass GATE3/5 for a
# long time, because the demo paths were already cached. The gate must test the real fetch+extract path.
# Referenced via the compose file's fixed project name. A compose-exec FAILURE is FATAL by default: the
# whole point of this clear is to prevent a warm-cache false-green (a broken extraction path passing
# GATE3/5 off a stale cache), so silently continuing against a possibly-warm cache would defeat it — and
# the stack being up is a documented prereq, so a failed exec means something is genuinely wrong (not a
# benign "cleared 0"; the inner sh -c always exits 0, so a non-zero exit is the compose layer failing).
# Set ALLOW_WARM_CACHE=1 to downgrade it to a warning for the rare case of targeting a Confluence that is
# NOT managed by this compose file.
echo ">> clearing the on-disk bundle cache (so the gates test real archive extraction, not a stale cache)"
# The plugin creates the cache under System.getProperty("java.io.tmpdir")/likec4-bundle-cache-<user>
# (see SourceServiceProvider). Rather than hard-code one tmpdir path (a base-image change that moved
# java.io.tmpdir would silently make the clear a no-op and re-open the warm-cache false-green this exists
# to prevent), SEARCH the whole confluence + /tmp trees for likec4-bundle-cache* dirs. Match the bare
# prefix so BOTH the per-user dir (likec4-bundle-cache-<user>) and any legacy unsuffixed dir are removed.
# REPORT the count: a silent zero-match would leave a WARM cache, so make it observable rather than
# swallowing it with `|| true` (0 now genuinely means "cold", not "looked in the wrong place").
# maxdepth is 8 (not 4): the cache today sits at /opt/atlassian/confluence/temp/likec4-bundle-cache-<user>
# (depth 4), but a base-image change that nests java.io.tmpdir a level or two deeper must NOT silently
# turn this clear into a no-op and re-open the warm-cache false-green — 8 leaves generous headroom while
# keeping the two-tree scan bounded. The `-name likec4-bundle-cache*` predicate keeps it cheap regardless.
if removed=$(docker compose -f docker/compose.yaml exec -T confluence sh -c '
    before=$(find /opt/atlassian /tmp -maxdepth 8 -type d -name "likec4-bundle-cache*" 2>/dev/null | wc -l)
    find /opt/atlassian /tmp -maxdepth 8 -type d -name "likec4-bundle-cache*" -prune -exec rm -rf {} + 2>/dev/null
    after=$(find /opt/atlassian /tmp -maxdepth 8 -type d -name "likec4-bundle-cache*" 2>/dev/null | wc -l)
    echo "$before"
    # RE-COUNT after the delete and FAIL if any dir survived: the count reported to the operator must be
    # the OUTCOME, not the intent. If rm fails (e.g. a base-image UID change makes the cache owner differ
    # from the exec user), echoing the pre-delete count as "cleared" would mask it and re-open the exact
    # warm-cache false-green this guard exists to prevent — a broken extraction path passing GATE3/5 off a
    # stale-but-"cleared" cache. Only a genuinely empty post-state counts as cleared.
    # Numeric (-eq), not string (= 0): a `wc -l` that pads its output with leading whitespace (BusyBox /
    # some coreutils builds) would make a string compare `" 0" = 0` FALSE and spuriously abort the whole
    # gate on a cache that WAS cleared. -eq compares as integers, tolerating any surrounding whitespace.
    [ "$after" -eq 0 ]' 2>/dev/null); then
  echo "   cleared ${removed:-0} bundle-cache dir(s)"
elif [ "${ALLOW_WARM_CACHE:-0}" = "1" ]; then
  echo "!! WARNING: could not clear the bundle cache (compose exec failed, or some dirs survived rm);" >&2
  echo "   ALLOW_WARM_CACHE=1 set — GATE3/5 may render from a stale cache and NOT exercise archive extraction" >&2
else
  echo "!! could not clear the bundle cache (compose exec failed, or dirs survived the delete) — refusing" >&2
  echo "   to run the gate against a possibly-warm cache (a broken extraction path would pass GATE3/5 off" >&2
  echo "   it). Bring the stack up with docker/up.sh, or set ALLOW_WARM_CACHE=1 to override for a" >&2
  echo "   non-compose target." >&2
  exit 1
fi

echo ">> running Confluence-10 gates (containerised Playwright)"
cd docker/e2e
# Forward the admin creds into the container split as AUTH_USER/AUTH_PASS (the form the spec reads).
# Without this a non-default $AUTH (e.g. a changed admin password) would 401 inside the container while
# this script's own curls succeed — the gate would fail for the wrong reason, defeating the point of
# parameterising the creds. ${AUTH%%:*}=user, ${AUTH#*:}=password (a colon in the password is preserved).
# The Playwright image is pinned by DIGEST, not just the mutable v1.61.1-jammy tag: Microsoft re-pushes
# the -jammy tags with base-OS/browser patches, so the tag alone can silently change the browser build
# behind this gate (the inner version-guard only pins the npm runner, not the image's browser binaries).
# This mirrors the digest discipline in docker/mock-gitlab/Dockerfile. NB: Docker honours the @sha256 and
# IGNORES the tag, so when you bump the 1.48.0 pin you MUST refresh this digest too (docker inspect
# --format '{{.RepoDigests}}' the new image) — a stale digest would keep running the OLD image.
exec docker run --rm --network host -v "$PWD:/e2e" -w /e2e \
  -e CONFLUENCE_BASE="$BASE" \
  -e AUTH_USER="${AUTH%%:*}" -e AUTH_PASS="${AUTH#*:}" \
  mcr.microsoft.com/playwright:v1.61.1-jammy@sha256:7b86926fff94374389e8e1f4fdc5c76d050d4a06a7886bb537bf412b20e2b71e \
  sh -c 'if ! npm ci >/tmp/npm-install.log 2>&1; then echo "!! npm ci (against docker/e2e/package-lock.json) failed (offline, or a broken/partial install) — falling back to the image-bundled runner; install-log tail:" >&2; tail -n 15 /tmp/npm-install.log >&2 || true; fi; ver=$(npx playwright --version 2>/dev/null | grep -oE "[0-9]+\.[0-9]+\.[0-9]+" | head -1); case "$ver" in 1.48.*) : ;; *) echo "!! Playwright runner is \"$ver\", expected 1.48.x — a partial install or image drift left an UNPINNED runner; refusing to run the gate on it (runner<->browser must match the image tag)." >&2; exit 1 ;; esac; npx playwright test c10-gates.spec.ts --reporter=list'
