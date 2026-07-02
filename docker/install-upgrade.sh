#!/usr/bin/env bash
# Install / upgrade test — spec §11 ("Install/upgrade: install prior version → upgrade to new →
# assert macro still renders and admin config persists").
#
# RUN-VERIFIED LIVE (2026-06-28) against the dev amps `confluence:run` instance at
# http://localhost:1990/confluence via the UPM REST API — a real fresh-install + upgrade, with the
# macro page rendering (Playwright `.react-flow__node` = 2) and the admin config persisting across
# the upgrade. See docker/README.md "§11 install/upgrade — RUN-verified live".
#
# Targets EITHER backend (parameterise BASE):
#   * dev amps:  BASE=http://localhost:1990/confluence   (default — note the /confluence context path)
#   * compose:   BASE=http://localhost:8090              (golden-snapshot stack; root context, no path)
#
# The dev amps instance PRE-INSTALLS the plugin, so to exercise a genuine *fresh* install this
# script UNINSTALLS any existing copy first (UNINSTALL_FIRST=true, default). On a clean compose
# instance the uninstall is a harmless no-op.
#
# PREREQUISITE: likec4-core must already be installed to ~/.m2. build_jar runs an OFFLINE (`-o`)
# `package`, and the root build's sole compile-scope dep (likec4-source-core) is a standalone module,
# not a reactor child (see CLAUDE.md). On a tree where core was never installed, run
# `./mvnw -B -ntp -f likec4-core/pom.xml install -DskipTests` first, or the offline package fails
# opaquely on the missing artifact. The normal build/gate flow installs core before this runs.
#
# Flow:
#   1. Build the plugin JAR at the current pom version (the "prior" version).
#   2. Uninstall any pre-existing copy (so step 3 is a true fresh install), then UPM-install the JAR.
#   3. Configure admin (baseUrl → mock-GitLab, allowlist) and seed a space + page with the macro.
#   4. Assert the page's rendered body contains the likec4-diagram macro div.
#   5. Bump the pom <version> (0.1.0-SNAPSHOT → 0.1.1-SNAPSHOT), rebuild.
#   6. UPM-UPGRADE (same POST; UPM replaces by plugin key for an equal/higher version).
#   7. Re-assert the macro page still renders AND the admin config (baseUrl/allowlist) PERSISTED.
#   8. Restore: revert the pom, rebuild the prior JAR; on a persistent (amps) instance optionally
#      uninstall+reinstall the prior version (UPM refuses to *downgrade* in place, so a clean
#      restore needs uninstall→install).
# Clear echo PASS/FAIL gates; exits non-zero on any failure. The pom is restored on exit.
#
# ---- QuickReload caveat (dev amps only) --------------------------------------------------------
# `confluence:run` runs QuickReload, which auto-deploys target/*.jar when the artifact FILENAME
# changes. Bumping the version (step 5) changes the filename, so QuickReload may deploy the new JAR
# a moment before this script's UPM POST — the end state is identical (same key, upgraded in place).
# Re-creating an EXISTING filename (the step-8 prior-version rebuild) does NOT re-trigger QuickReload,
# and UPM will not downgrade in place, which is why the clean restore uses uninstall→install.
set -euo pipefail
cd "$(dirname "$0")/.."   # repo root

# node parses the UPM plugin-enabled JSON in an `until` loop condition below; if it is missing the loop
# just fails every iteration and burns the full deadline before a misleading "plugin did not enable".
# `set -e` can't catch it (it is in a conditional), so check up front.
command -v node >/dev/null 2>&1 || { echo "install-upgrade.sh: 'node' is required on PATH (Node 20+)" >&2; exit 1; }

BASE="${BASE:-http://localhost:1990/confluence}"
AUTH="${AUTH:-admin:admin}"
# Maven invocation: prefer the committed repo wrapper (./mvnw), else `mvn` on PATH.
# Override with JMVN=/path/to/mvn for a custom toolchain.
_REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ -z "${JMVN:-}" ]; then
  if [ -x "$_REPO_ROOT/mvnw" ]; then JMVN="$_REPO_ROOT/mvnw"; else JMVN="mvn"; fi
fi
PLUGIN_KEY="${PLUGIN_KEY:-com.phrontizo.confluence.likec4-confluence}"
# What the plugin (running INSIDE Confluence) uses to reach GitLab. The default http://localhost:8099
# suits the dev-amps backend (BASE=:1990), where Confluence runs on the HOST beside the host-published
# mock. For the COMPOSE backend (BASE=:8090) Confluence runs in a CONTAINER, so localhost:8099 would
# resolve to the container itself and the diagram would never load — set
# MOCK_BASEURL=http://mockgitlab:8080 (the compose service DNS name) when running against compose.
# (Step 4's macro-div check is zero-git and would still PASS, so a wrong value here fails silently.)
MOCK_BASEURL="${MOCK_BASEURL:-http://localhost:8099}"
PROJECT="${PROJECT:-acme/architecture}"
ALLOWLIST_JSON="${ALLOWLIST_JSON:-[\"acme\"]}"          # allowlist entries are project *namespaces*
SPACE_KEY="${SPACE_KEY:-LIKEC4}"
OLD_VERSION="${OLD_VERSION:-0.1.0-SNAPSHOT}"
NEW_VERSION="${NEW_VERSION:-0.1.1-SNAPSHOT}"
UNINSTALL_FIRST="${UNINSTALL_FIRST:-true}"             # uninstall a pre-existing copy for a true fresh install
RESTORE_PRIOR="${RESTORE_PRIOR:-true}"                # leave the prior version installed at the end

# ---- restore the pom (and clean any seed temp) on exit so the test is idempotent ---------------
cp pom.xml pom.xml.iutest.bak
restore_pom() { mv -f pom.xml.iutest.bak pom.xml 2>/dev/null || true; }
SEED_TMP=""
cleanup() { restore_pom; [ -n "$SEED_TMP" ] && rm -f "$SEED_TMP"; }
trap cleanup EXIT

fail() { echo "FAIL: $*"; exit 1; }

# ---- UPM helpers (correct media types — the detail endpoint 406s on Accept: application/json) ---
# Pick the main JAR for a KNOWN version by its exact filename. This test deliberately builds TWO
# versions (prior + upgraded), so two JARs legitimately coexist; the old `ls -t | head -1` (newest by
# mtime) could pick the wrong one if a rebuild's clock ordering surprised us. The script always knows
# the version it just built ($OLD_VERSION/$NEW_VERSION), so select deterministically and fail loud if
# the expected artifact is missing. $1 = wanted version.
plugin_jar() {
  local want="$1" jar="target/likec4-confluence-$1.jar"
  [ -f "$jar" ] || fail "expected plugin JAR not found: $jar (build for $want did not produce it)"
  printf '%s\n' "$jar"
}

upm_token() {
  curl -s -u "$AUTH" -H 'Accept: application/vnd.atl.plugins.installed+json' \
    "$BASE/rest/plugins/1.0/" -D - -o /dev/null | tr -d '\r' \
    | awk -F': ' 'tolower($1)=="upm-token"{print $2}'
}

upm_detail() {  # plugin detail JSON — MUST use the plugin media type, not application/json
  curl -s -u "$AUTH" -H 'Accept: application/vnd.atl.plugins.plugin+json' \
    "$BASE/rest/plugins/1.0/$PLUGIN_KEY-key"
}

upm_upload() {  # $1 = jar path  — install OR upgrade (UPM replaces by plugin key)
  local jar="$1" token up_code code
  token="$(upm_token)"
  if [ -z "$token" ]; then
    # Classify the failure (one extra probe, only on the abort path): 401/403 = bad creds; a 2xx with no
    # UPM-token header = UPM unavailable; other = up-but-broken (500/HTML), not a credentials problem.
    code=$(curl -s -o /dev/null -w '%{http_code}' -u "$AUTH" \
      -H 'Accept: application/vnd.atl.plugins.installed+json' "$BASE/rest/plugins/1.0/")
    case "$code" in
      401|403) fail "could not obtain UPM token: auth rejected (HTTP $code) — check the admin creds" ;;
      2*)      fail "could not obtain UPM token: request succeeded (HTTP $code) but no UPM-token header — is UPM available?" ;;
      *)       fail "could not obtain UPM token (HTTP $code) — Confluence reachable but not serving the plugins API (up-but-broken?)" ;;
    esac
  fi
  # Fail loudly on an upload rejection (403 "Signature check failed" / 413 / 400) rather than discarding
  # the response and then burning the full 180s wait_enabled poll on a plugin that was never uploaded —
  # matching the hardening in up.sh / c10-gate.sh.
  up_code=$(curl -s -u "$AUTH" -X POST "$BASE/rest/plugins/1.0/?token=$token" \
    -H 'Accept: application/json' \
    -F "plugin=@$jar;type=application/java-archive" -o /dev/null -w '%{http_code}')
  case "$up_code" in
    2*) ;;
    *) fail "plugin upload rejected (HTTP $up_code) — check the dev JVM flags (upload-enabled / signature-check-disabled)" ;;
  esac
}

upm_uninstall() {  # DELETE by key; 204 = removed, 404 = was not installed (both fine)
  curl -s -u "$AUTH" -X DELETE -H 'Accept: application/vnd.atl.plugins.plugin+json' \
    "$BASE/rest/plugins/1.0/$PLUGIN_KEY-key" -o /dev/null
}

plugin_version() {  # report the installed plugin's version (proves prior vs upgraded)
  # Parse the UPM JSON's top-level `version` with node rather than `grep '"version"' | head -1 | sed`
  # (the same brittleness the enabled-parse below already avoids): the detail JSON carries several
  # `version` keys — head -1 relies on the plugin's own version serializing first, which a Confluence
  # build could reorder, silently reporting the wrong version. node reads the top-level field directly.
  upm_detail | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{const v=JSON.parse(s).version;process.stdout.write(v==null?"":String(v))}catch{}})'
}

# Parse the UPM JSON's top-level `enabled` rather than grepping a brittle '"enabled":true' substring
# (whitespace/pretty-print changes or an error body would never match). node is a host prereq.
is_enabled() {
  upm_detail | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{process.exit(JSON.parse(s).enabled===true?0:1)}catch{process.exit(1)}})'
}

wait_enabled() {  # poll until enabled (UPM install/upgrade is async); optional $1 = expected version
  local want="${1:-}" deadline=$(( $(date +%s) + 180 )) ver
  until is_enabled && { [ -z "$want" ] || [ "$(plugin_version)" = "$want" ]; }; do
    [ "$(date +%s)" -gt "$deadline" ] \
      && fail "plugin $PLUGIN_KEY did not reach enabled${want:+ @ $want} in time (got $(plugin_version))"
    sleep 3
  done
}

admin_servlet_code() {
  curl -s -o /dev/null -w '%{http_code}' -u "$AUTH" "$BASE/plugins/servlet/likec4/admin"
}

configure_admin() {
  curl -s -u "$AUTH" -X POST "$BASE/rest/likec4/1.0/admin" \
    -H 'Content-Type: application/json' \
    -d "{\"baseUrl\":\"$MOCK_BASEURL\",\"allowlist\":$ALLOWLIST_JSON,\"token\":\"test-token\"}" >/dev/null
}

ensure_space() {  # idempotent: ignore "already exists" (400)
  curl -s -u "$AUTH" -X POST "$BASE/rest/api/space" -H 'Content-Type: application/json' \
    -d "{\"key\":\"$SPACE_KEY\",\"name\":\"LikeC4\",\"type\":\"global\"}" >/dev/null || true
}

seed_page() {  # create a page carrying the macro; echo its id
  local title="LikeC4 Install-Upgrade $(date +%s)" tmp
  tmp="$(mktemp)"; SEED_TMP="$tmp"   # tracked so the EXIT trap removes it if we're interrupted
  cat > "$tmp" <<JSON
{"type":"page","title":"$title","space":{"key":"$SPACE_KEY"},
 "body":{"storage":{"representation":"storage","value":
   "<ac:structured-macro ac:name=\"likec4-diagram\"><ac:parameter ac:name=\"project\">$PROJECT</ac:parameter><ac:parameter ac:name=\"ref\">main2</ac:parameter><ac:parameter ac:name=\"path\">ok</ac:parameter><ac:parameter ac:name=\"view\">index</ac:parameter></ac:structured-macro>"}}}
JSON
  # Parse the created page's TOP-LEVEL id with node (already a host prereq, used for the UPM JSON above)
  # rather than `grep -o '"id":"…"' | head -1`: the POST response nests other "id" fields (space, history,
  # _expandable), and head-1 only works while the top-level id happens to serialise first — brittle across
  # Confluence versions/serialisers. JSON.parse(...).id targets the page id unambiguously; a failed create
  # (error body) yields "" so the caller's render-check fails loudly with "no page id".
  curl -s -u "$AUTH" -X POST "$BASE/rest/api/content" -H 'Content-Type: application/json' \
    --data @"$tmp" \
    | node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{try{process.stdout.write(String(JSON.parse(d).id||""))}catch{process.stdout.write("")}})'
  rm -f "$tmp"; SEED_TMP=""
}

assert_macro_renders() {  # $1 = page id ; fail unless the rendered body has the macro div
  local id="$1" html
  [ -n "$id" ] || fail "no page id to render-check"
  html="$(curl -s -u "$AUTH" -H 'Accept: application/json' \
            "$BASE/rest/api/content/$id?expand=body.view")"
  echo "$html" | grep -q 'likec4-diagram' \
    || fail "rendered page $id does not contain the likec4-diagram macro"
  # NOTE: the live RUN also proved the full browser pixel render (Playwright .react-flow__node = 2,
  # [data-testid=likec4-diagram] visible) via the docker/e2e Playwright container; this gate keeps
  # the script host-portable by checking the server-rendered macro div.
}

admin_config() {  # echo the persisted admin config body (baseUrl + allowlist)
  curl -s -u "$AUTH" -H 'Accept: application/json' "$BASE/rest/likec4/1.0/admin"
}

build_jar() {  # mandated build: drop the stale frontend copy, offline package, skip tests
  # Also clear the unpacked core classes + amps' unpack/bundle markers (see CLAUDE.md's dirty-tree
  # gotcha): this script packages twice on a live/dirty tree (prior + bumped version), and on an
  # incremental tree a stale marker makes amps SKIP re-unpacking likec4-source-core → the JAR ships
  # with ZERO com/phrontizo/likec4 classes (NoClassDefFoundError, "plugin did not enable in time"),
  # while a stale unpacked class shadows the freshly-installed dep. Clearing both halves is mandatory.
  rm -rf target/classes/likec4-web target/classes/com/phrontizo/likec4 \
    target/dependency-maven-plugin-markers target/fe-bundled-metadata-markers
  "$JMVN" -B -o package -DskipTests
}

bump_version() {  # replace ONLY the project <version> (first occurrence) in pom.xml
  sed -i "0,/<version>$OLD_VERSION<\/version>/s//<version>$NEW_VERSION<\/version>/" pom.xml
  grep -q "<version>$NEW_VERSION</version>" pom.xml || fail "pom version bump did not take"
}

# ---- 1. build the PRIOR version -----------------------------------------------------------------
echo ">> [1/8] building prior version ($OLD_VERSION) against $BASE"
build_jar
JAR="$(plugin_jar "$OLD_VERSION")"
echo "   built $JAR"

# ---- 2. fresh install (uninstall any pre-existing copy first) -----------------------------------
if [ "$UNINSTALL_FIRST" = "true" ]; then
  echo ">> [2/8] uninstalling any pre-existing copy (for a genuine fresh install)"
  upm_uninstall
  # wait for it to actually go away — admin servlet should 404
  d=$(( $(date +%s) + 60 ))
  until [ "$(admin_servlet_code)" = "404" ]; do
    [ "$(date +%s)" -gt "$d" ] && fail "plugin did not uninstall (admin servlet still up)"
    sleep 2
  done
  echo "   uninstalled (admin servlet → 404)"
fi
echo ">> [2/8] UPM-installing prior version (fresh)"
upm_upload "$JAR"
wait_enabled "$OLD_VERSION"
[ "$(admin_servlet_code)" = "200" ] || fail "admin servlet not 200 after fresh install"
echo "PASS: prior version installed + enabled (version=$(plugin_version), admin servlet 200)"

# ---- 3. configure admin + seed a macro page -----------------------------------------------------
echo ">> [3/8] configuring admin + seeding a macro page"
configure_admin
ensure_space
PAGE_ID="$(seed_page)"
[ -n "$PAGE_ID" ] || fail "could not seed macro page"
echo "   seeded page id=$PAGE_ID"

# ---- 4. assert it renders on the prior version --------------------------------------------------
echo ">> [4/8] asserting macro renders (prior version)"
assert_macro_renders "$PAGE_ID"
echo "PASS: macro div present on page $PAGE_ID (prior version)"

# ---- 5. bump version + rebuild ------------------------------------------------------------------
echo ">> [5/8] bumping pom version ($OLD_VERSION → $NEW_VERSION) + rebuilding"
bump_version
build_jar
NEW_JAR="$(plugin_jar "$NEW_VERSION")"
echo "   built $NEW_JAR"

# ---- 6. UPM-upgrade -----------------------------------------------------------------------------
echo ">> [6/8] UPM-upgrading to $NEW_VERSION"
upm_upload "$NEW_JAR"          # on amps, QuickReload may have already deployed this — harmless
wait_enabled "$NEW_VERSION"
echo "PASS: plugin upgraded to $(plugin_version)"

# ---- 7. re-assert: macro still renders AND admin config persisted -------------------------------
echo ">> [7/8] re-asserting macro render + admin-config persistence after upgrade"
assert_macro_renders "$PAGE_ID"
echo "PASS: macro div still present on page $PAGE_ID after upgrade"

CFG="$(admin_config)"
echo "   persisted admin config: $CFG"
# -F (fixed string): the allowlist JSON contains regex metacharacters ([ ]).
echo "$CFG" | grep -qF "\"baseUrl\":\"$MOCK_BASEURL\"" \
  || fail "admin config baseUrl did not persist across upgrade (got [$CFG])"
echo "$CFG" | grep -qF "\"allowlist\":$ALLOWLIST_JSON" \
  || fail "admin config allowlist did not persist across upgrade (got [$CFG])"
echo "PASS: admin config (baseUrl + allowlist) persisted across the upgrade"

# ---- 8. restore the prior version ---------------------------------------------------------------
echo ">> [8/8] restoring prior version ($OLD_VERSION)"
restore_pom; trap - EXIT      # pom is back; stop the on-exit restore
build_jar
if [ "$RESTORE_PRIOR" = "true" ]; then
  # UPM will not DOWNGRADE in place, so restore via uninstall → fresh install.
  upm_uninstall
  d=$(( $(date +%s) + 60 ))
  until [ "$(admin_servlet_code)" = "404" ]; do
    if [ "$(date +%s)" -gt "$d" ]; then
      # The uninstall did NOT complete. Re-uploading now races a half-removed plugin and OSGi may keep
      # serving the OLD bytecode — exactly the hazard this harness exists to catch. Fail loudly rather
      # than print a false "INSTALL/UPGRADE: PASS".
      echo "!! prior-version uninstall did not reach 404 within 60s (admin servlet $(admin_servlet_code));" \
           "aborting to avoid a half-removed-plugin restore" >&2
      exit 1
    fi
    sleep 2
  done
  upm_upload "$(plugin_jar "$OLD_VERSION")"
  wait_enabled "$OLD_VERSION"
  echo "   restored running plugin to $(plugin_version) (admin servlet $(admin_servlet_code))"
else
  # The pom is restored (step 8 above) but the prior version was NOT reinstalled, so the live instance is
  # left running the bumped $NEW_VERSION built from a now-reverted pom — a version that no longer matches
  # any source tree. Harmless (c10-gate.sh clean-reinstalls anyway) but a manual check in the meantime
  # would run against a phantom version, so say so loudly rather than exit a clean PASS silently.
  echo "!! RESTORE_PRIOR=false: the running instance is left on the bumped $(plugin_version), built from a" \
       "now-reverted pom (it matches no source tree). Re-run with RESTORE_PRIOR=true, or c10-gate.sh," \
       "to put $OLD_VERSION back." >&2
fi

echo "INSTALL/UPGRADE: PASS"
