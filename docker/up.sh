#!/usr/bin/env bash
# up.sh — full UNATTENDED bring-up of the production stack:
#   atlassian/confluence:10.2.13 + Postgres (direct-JDBC) + mock-GitLab  (image set in docker/.env).
# It builds the stack, health-gates Confluence, runs the setup wizard headlessly (parameterised by a
# timebomb DC licence), then installs + configures the plugin via UPM and seeds a macro page.
# Idempotent: re-running against an already-set-up / already-installed instance is a safe no-op.
#
# CONFLUENCE 10.2 NOTES (verified live 2026-06-29 on atlassian/confluence:10.2.13):
#   * The SDK Developer timebomb licence in docker/.timebomb-license VALIDATES on 10.2.13 (accepted
#     with no confLicenseString-error), same as 9.2.20.
#   * The setup-wizard flow is IDENTICAL to 9.2.20 (license -> cluster/skip -> data/blank ->
#     usermanagement/internal -> administrator -> finishsetup -> RUNNING). 10.2 adds a couple of
#     harmless hidden submit-button fields (setupTypeCustom=Next on the licence form,
#     setup-next-button=Next on the administrator form) which we include defensively below.
#   * 10.2 ships the atlassian-authentication-plugin, which BLOCKS HTTP Basic auth on API calls by
#     default AND enforces a 2SV-capable login mode that rejects raw /dologin.action form POSTs; the
#     UPM (8.0.17) also enforces app-signature verification. compose.yaml therefore sets three JVM
#     properties so this all-HTTP-Basic harness works: basic.auth.filter.force.allow=true,
#     atlassian.upm.signature.check.disabled=true, and the pre-existing upm.plugin.upload.enabled.
#
# PROOF STATUS of the setup-wizard steps — ALL LIVE-PROVEN end-to-end on this stack (2026-06-29,
# Confluence 9.2.20 AND 10.2.13 on Postgres reached RUNNING via exactly these POSTs):
#   * LICENCE       GET / -> /setup/setuplicense.action; POST /setup/dosetuplicense.action with
#                   `confLicenseString` + scraped `atl_token`. Invalid keys re-render with
#                   `<div id="confLicenseString-error" ...>` (we fail loudly on that). LICENCE NUANCE:
#                   the server-ID is NOT enforced, but a FUTURE `CreationDate` IS rejected ("not a
#                   valid license key") — a P3H/future-dated key fails; an Atlassian *SDK Developer*
#                   timebomb (e.g. from an amps `confluence:run` home's `atlassian.license.message`)
#                   or a freshly-generated, non-future-dated DC timebomb works.
#   * DEPLOYMENT    GET / -> /setup/setupcluster-start.action ("Choose your deployment type"). The
#                   "Non-clustered (single node)" path is the form's "Next"/skip-button, which sets
#                   the hidden field `newCluster=skipCluster` and POSTs /setup/setupcluster.action.
#   * DATABASE      AUTO-SATISFIED: the compose `ATL_JDBC_*` env (direct JDBC, Postgres) pre-configures
#                   the DB, so the wizard SKIPS the DB-choice step entirely (deployment -> content).
#   * CONTENT       GET / -> /setup/setupdata-start.action ("Load Content"). Blank/Empty site = POST
#                   /setup/setupdata.action with `contentChoice=blank` + `dbchoiceSelect=Empty Site`.
#   * USER MGMT     GET / -> /setup/setupusermanagementchoice-start.action. Internal = POST
#                   /setup/setupusermanagementchoice.action with `userManagementChoice=internal`.
#   * ADMINISTRATOR GET / -> /setup/setupadministrator-start.action. POST /setup/setupadministrator.action
#                   with `username`/`fullName`/`email`/`password`/`confirm` — this COMPLETES setup
#                   (server advances to finishsetup.action and /status flips to RUNNING).
set -euo pipefail
cd "$(dirname "$0")"

# node parses the UPM plugin-enabled JSON in an `until` loop condition below; if it is missing the loop
# just fails every iteration and burns the full 180s deadline before failing with a misleading
# "plugin did not enable in time". `set -e` can't catch it (it is in a conditional), so check up front.
command -v node >/dev/null 2>&1 || { echo "up.sh: 'node' is required on PATH (Node 20+)" >&2; exit 1; }

# `-n` already makes this a no-op when .env exists (and returns 0), so a genuine failure (e.g. an
# unwritable dir) should surface under `set -e` rather than being masked by `|| true`. When .env is
# retained, say so: .env.example gains keys over time (CONFLUENCE_BIND, POSTGRES_IMAGE,
# CONFLUENCE_MEM_LIMIT, …) and a pre-existing .env silently falls back to the compose ${VAR:-default}
# for any it lacks — safe while those defaults are the safe values, but worth surfacing so drift is
# visible rather than silent.
[ -f .env ] && echo "up.sh: keeping existing docker/.env (compare against .env.example if compose behaves unexpectedly — it may have gained keys)"
cp -n .env.example .env
# CONFLUENCE_PORT lives in .env, which docker-compose reads to PUBLISH the port. Mirror it here when
# it isn't already set in the environment — otherwise compose publishes the chosen port while up.sh
# health-gates + installs against :8090 and hangs until the deadline. Shell env wins over .env (same
# precedence as compose), so we only read .env when the var is unset.
if [ -z "${CONFLUENCE_PORT:-}" ] && [ -f .env ]; then
  # Read the last CONFLUENCE_PORT= line and tolerate a hand-edited .env: strip an inline `# comment`,
  # surrounding whitespace, and a pair of surrounding quotes — otherwise `CONFLUENCE_PORT="8090" # x`
  # would yield the bogus value `"8090"#x` and up.sh would health-gate against the wrong address.
  # `|| true`: a hand-edited .env that DROPS the CONFLUENCE_PORT= line makes grep exit 1, and under this
  # script's `set -euo pipefail` (pipefail surfaces grep's status) the assignment would abort up.sh — the
  # comment above promises we tolerate a hand-edited .env, so swallow the no-match and let `_cp` stay
  # empty (the `${CONFLUENCE_PORT:-8090}` default below then applies).
  _cp="$(grep -E '^[[:space:]]*CONFLUENCE_PORT=' .env | tail -1 | cut -d= -f2- || true)"
  _cp="${_cp%%#*}"                                   # drop an inline # comment
  _cp="$(printf '%s' "$_cp" | tr -d '[:space:]')"    # drop surrounding whitespace
  _cp="${_cp%\"}"; _cp="${_cp#\"}"                   # strip a pair of surrounding double quotes
  _cp="${_cp%\'}"; _cp="${_cp#\'}"                   # strip a pair of surrounding single quotes
  CONFLUENCE_PORT="$_cp"
fi
BASE="http://localhost:${CONFLUENCE_PORT:-8090}"
AUTH="${AUTH:-admin:admin}"
# Initialise here, not only inside the FIRST_RUN block: wstep() references $LAST_STEP and `set -u`
# would abort on the unbound var if wstep ever fired before the setup loop sets it.
LAST_STEP="(none detected)"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-admin}"
LICENSE_FILE="${LICENSE_FILE:-.timebomb-license}"
# baseUrl the PLUGIN (running inside the Confluence container) uses to reach mock-GitLab: the compose
# service name on its port 8080 (the non-root mock's listen port; NOT localhost — that would be the
# Confluence container itself).
MOCK_BASEURL="${MOCK_BASEURL:-http://mockgitlab:8080}"
ALLOWLIST_JSON="${ALLOWLIST_JSON:-[\"acme\"]}"
PLUGIN_KEY="${PLUGIN_KEY:-com.phrontizo.confluence.likec4-confluence}"
SPACE_KEY="${SPACE_KEY:-LIKEC4}"

COOKIES="$(mktemp)"
# Records each silently-failed wizard-step POST (action -> HTTP code) so the final error can name the
# real cause instead of just "did not reach RUNNING" — wstep runs inside a $(...) subshell, so it can't
# set a parent variable, but a file append survives.
WSTEP_LOG="$(mktemp)"
# Private scratch file for the licence key: it is handed to curl via @file so the key never lands on
# curl's argv (/proc/<pid>/cmdline is world-readable on a shared host) — the same keep-secrets-off-argv
# discipline real-gitlab-e2e.sh uses. Even a gitignored dev timebomb licence is a real Atlassian key.
LICTMP="$(mktemp)"; chmod 600 "$LICTMP"
trap 'rm -f "$COOKIES" "$WSTEP_LOG" "$LICTMP"' EXIT
fail() { echo "!! $*" >&2; exit 1; }

# ----------------------------------------------------------------------------------------------------
# 0. licence precondition — fail loudly if absent (everything else is automated).
# ----------------------------------------------------------------------------------------------------
if [ ! -s "$LICENSE_FILE" ]; then
  cat >&2 <<EOF
!! Missing Confluence DC licence: $LICENSE_FILE (gitignored, not present).

   Unattended setup needs a valid Atlassian Data Center *timebomb* licence. LICENCE NUANCE (proven
   2026-06-29): Confluence does NOT enforce the server-ID, but it DOES reject a licence whose
   CreationDate is in the future — so a P3H/future-dated key fails with "That's not a valid license
   key." A working key is either:
     * an Atlassian *SDK Developer* timebomb — e.g. the `atlassian.license.message` from an amps
       \`confluence:run\` home (target/confluence/home/confluence.cfg.xml), or
     * a freshly-generated, NON-future-dated DC timebomb from Atlassian's
       "Timebomb licenses for testing server apps" page.
   Save the key (whitespace/newlines are fine — Confluence strips them) to:
       docker/$LICENSE_FILE
   Then re-run docker/up.sh. Everything after this point is fully automated.
EOF
  exit 1
fi

# ----------------------------------------------------------------------------------------------------
# 1. build + start the stack.
# ----------------------------------------------------------------------------------------------------
echo ">> building + starting stack (Confluence 10.2.13 + Postgres + mock-GitLab)"
docker compose up -d --build

echo ">> waiting for Confluence /status (FIRST_RUN or RUNNING); Confluence DC is RAM-gated (>=6 GB recommended)"
deadline=$(( $(date +%s) + 1200 ))   # 20 min
state=""
# Wait specifically for FIRST_RUN or RUNNING — NOT merely any non-empty state. A transient state that
# the platform serves with HTTP 200 (e.g. STARTING/MAINTENANCE) would otherwise satisfy a bare "-n"
# check, break the loop early, skip the setup wizard, and fail the later plugin install with a much
# less obvious error. Any other non-empty state (and a failed/503 poll) keeps waiting.
until state=$(curl -fsS "$BASE/status" 2>/dev/null | grep -o '"state":"[^"]*"' | cut -d'"' -f4) \
      && { [ "$state" = FIRST_RUN ] || [ "$state" = RUNNING ]; }; do
  [ "$(date +%s)" -gt "$deadline" ] \
    && { echo "!! Confluence did not respond in time (likely RAM-gated)"; \
         echo "!! container state: $(docker inspect -f 'OOMKilled={{.State.OOMKilled}} ExitCode={{.State.ExitCode}} Status={{.State.Status}}' "$(docker compose ps -q confluence)" 2>/dev/null || echo '(could not inspect — is the container up?)')"; \
         echo "!! (OOMKilled=true means the cgroup mem_limit was hit during boot — raise mem_limit or lower JVM_MAXIMUM_MEMORY in compose.yaml)"; \
         docker compose logs --tail=40 confluence; exit 2; }
  # FAST-FAIL on a TERMINAL container state instead of waiting out the 20-min deadline. `compose up`
  # already errors (→ set -e) if a dependency never goes healthy, so by here Confluence has started; but
  # it can still OOM or crash DURING its own boot (compose returned 0, then the JVM dies), after which
  # /status never responds and the loop would otherwise spin the full 20 min. The happy path goes
  # created→running and never reports exited/OOMKilled=true, so this cannot fire spuriously.
  cid=$(docker compose ps -q confluence 2>/dev/null || true)
  if [ -n "$cid" ]; then
    cstate=$(docker inspect -f '{{.State.Status}} {{.State.OOMKilled}}' "$cid" 2>/dev/null || true)
    case "$cstate" in
      exited*|*true)
        echo "!! Confluence container is terminal ($cstate) — it will not become ready"
        echo "!! container state: $(docker inspect -f 'OOMKilled={{.State.OOMKilled}} ExitCode={{.State.ExitCode}} Status={{.State.Status}}' "$cid" 2>/dev/null)"
        echo "!! (OOMKilled=true means the cgroup mem_limit was hit during boot — raise mem_limit or lower JVM_MAXIMUM_MEMORY in compose.yaml)"
        docker compose logs --tail=40 confluence; exit 2 ;;
    esac
  fi
  sleep 10
done
echo ">> Confluence /status: $state"

# ----------------------------------------------------------------------------------------------------
# 2. unattended setup wizard (only if FIRST_RUN; RUNNING means already set up -> skip).
# ----------------------------------------------------------------------------------------------------
scrape_token() {  # extract the atl_token hidden-field value from an HTML page on stdin (either attr order)
  local body t
  body="$(cat)"
  t=$(printf '%s' "$body" | grep -oE 'name="atl_token"[^>]*value="[^"]+"' | head -1 | sed -E 's/.*value="([^"]+)".*/\1/')
  [ -n "$t" ] || t=$(printf '%s' "$body" | grep -oE 'value="[^"]+"[^>]*name="atl_token"' | head -1 | sed -E 's/.*value="([^"]+)".*/\1/')
  printf '%s' "$t"
}
cj() { curl -fsS -c "$COOKIES" -b "$COOKIES" "$@"; }   # cookie-jar curl (XSRF token is session-bound)

# Best-effort wizard-step POST that FOLLOWS its redirect (-L) and echoes the landing page on stdout, so
# the loop can detect the NEXT step from where the POST actually lands rather than from a fresh GET / —
# on 10.2.13 GET / does NOT advance past the user-management step even though that step's POST 302s on to
# the administrator step, so re-deriving the step from GET / each iteration spins there forever. A failed
# step WARNs to STDERR (so it never pollutes the captured page) with the step action + HTTP status, then
# echoes nothing — the loop re-seeds from GET / on an empty page. It never aborts.
wstep() {
  local action="$1"; shift
  local code tmp; tmp=$(mktemp)
  # RETURN trap cleans the scratch file on ANY exit from this function — the EXIT trap above never sees
  # $tmp (it's function-local, and wstep runs inside a $(...) subshell), so an early return/signal would
  # otherwise orphan it. Fires once per invocation; the fixed name means no double-free.
  trap 'rm -f "$tmp"' RETURN
  code=$(curl -fsS -c "$COOKIES" -b "$COOKIES" -L -o "$tmp" -w '%{http_code}' "$action" "$@" 2>/dev/null) || true
  case "$code" in
    2*|3*) cat "$tmp" ;;  # the landing page IS the next step's form
    *) echo ">> WARN: wizard step POST to ${action##*/} returned '${code:-error}' (step=$LAST_STEP)" >&2
       echo "${action##*/} -> ${code:-error} (at step=$LAST_STEP)" >> "$WSTEP_LOG" ;;
  esac
}

if [ "$state" = "FIRST_RUN" ]; then
  echo ">> running unattended setup wizard"

  # --- STEP 1 (LIVE-PROVEN): licence ----------------------------------------------------------------
  TOKEN=$(cj -L "$BASE/setup/setuplicense.action" | scrape_token)
  [ -n "$TOKEN" ] || fail "could not scrape atl_token from the licence page"
  tr -d '\r\n' < "$LICENSE_FILE" > "$LICTMP"   # collapse to one line; Confluence strips whitespace anyway
  # `confLicenseString@file` makes curl read+URL-encode the key from the mode-0600 file, so it stays
  # off argv (unlike `confLicenseString=$LIC`, which would expose the whole key via /proc).
  RESP=$(cj -L "$BASE/setup/dosetuplicense.action" \
            --data-urlencode "confLicenseString@$LICTMP" \
            --data-urlencode "setupTypeCustom=Next" \
            --data-urlencode "atl_token=$TOKEN")
  if printf '%s' "$RESP" | grep -q 'confLicenseString-error'; then
    fail "Confluence REJECTED the licence in docker/$LICENSE_FILE — that is not a valid license key.
   Replace it with a current Atlassian timebomb DC licence (see the message printed at startup)."
  fi
  echo "   licence accepted"

  # --- STEPS 2..N (LIVE-PROVEN): walk the remaining wizard -------------------------------------------
  # GET / always redirects to the *current* setup step; we detect the step from the rendered form's
  # action and POST the EXACT fields proven on 2026-06-29 with a freshly-scraped atl_token. The DB step
  # is satisfied by the compose ATL_JDBC_* env (direct JDBC, Postgres) so the wizard skips it
  # (deployment -> content). Loop is bounded and exits as soon as /status reports RUNNING. Each POST
  # follows redirects (-L); the page it lands on IS the next step's form (GET / can lag by one step).
  LAST_STEP="(none detected)"
  # CHAIN the steps: each wstep follows its POST's redirect and returns the NEXT step's form, reused as
  # PAGE (see wstep). GET / only SEEDS the first iteration (and re-seeds on an empty page), because on
  # 10.2.13 GET / will not advance past the user-management step on its own — that step's POST 302s to
  # the administrator step, but GET / keeps routing back to user-management.
  PAGE=$(cj -L "$BASE/" || true)
  POST_FAILS=0   # cumulative count of wizard-step POSTs that produced no next page (silent failures)
  NO_STEP=0      # consecutive iterations on a page with NO recognized wizard step (unknown/renamed action)
  # A named throwaway (_i), not `_`: `for _ in ...` clobbers bash's `$_` special parameter on each
  # iteration, which is fragile under `set -u` should a future edit inside the loop reference `$_`.
  for _i in $(seq 1 20); do
    state=$(curl -fsS "$BASE/status" 2>/dev/null | grep -o '"state":"[^"]*"' | cut -d'"' -f4 || true)
    [ "$state" = "RUNNING" ] && break
    [ -n "$PAGE" ] || PAGE=$(cj -L "$BASE/" || true)
    TOKEN=$(printf '%s' "$PAGE" | scrape_token)
    # Log which wizard step we're on so a step that fails consistently (e.g. a renamed field on a new
    # Confluence build) is identifiable instead of silently spinning 15× behind a generic failure.
    STEP=$(printf '%s' "$PAGE" \
      | grep -oE 'setup(cluster|data|dbchoice|usermanagementchoice|administrator)\.action|finishsetup' \
      | head -1 || true)
    if [ -n "$STEP" ]; then echo ">> wizard step: $STEP"; LAST_STEP="$STEP"; fi
    # Abort early on an UNRECOGNIZED page too. POST_FAILS below only fires when a KNOWN step's POST
    # produced no next page; a page whose action doesn't match the STEP alternation (a renamed action on a
    # future Confluence build) leaves STEP empty, so POST_FAILS never increments and the loop would spin
    # the full 20-iteration cap (~3 min) behind a generic failure. Count consecutive unrecognized pages
    # and bail once they persist, so an unknown step fails fast and legibly like a known stuck one. The
    # happy path recognizes a step every iteration, so this resets to 0 and never triggers.
    if [ -z "$STEP" ]; then
      NO_STEP=$((NO_STEP + 1))
      [ "$NO_STEP" -ge 6 ] \
        && { echo ">> WARN: $NO_STEP iterations on an unrecognized setup page (no known wizard step) — aborting setup early" >&2; break; }
    else
      NO_STEP=0
    fi
    case "$PAGE" in
      *setupdbchoice*|*dosetupdbchoice*)               # defensive: only if env DID NOT pre-fill the DB
        PAGE=$(wstep "$BASE/setup/dosetupdbchoice.action" \
           --data-urlencode "database=custom" --data-urlencode "atl_token=$TOKEN") ;;
      *setupcluster.action*)                            # deployment type -> Non-clustered (skip-button)
        # newCluster=skipCluster is the single-node path; isClusteringEnabled is ignored by the action
        # when skipCluster is present (it's the field the clustered branch would read) — sent only to
        # mirror the form's full field set. The flow is live-proven to reach RUNNING single-node.
        PAGE=$(wstep "$BASE/setup/setupcluster.action" \
           --data-urlencode "isClusteringEnabled=true" --data-urlencode "newCluster=skipCluster" \
           --data-urlencode "atl_token=$TOKEN") ;;
      *setupdata.action*)                               # Load Content -> Blank/Empty site
        PAGE=$(wstep "$BASE/setup/setupdata.action" \
           --data-urlencode "contentChoice=blank" --data-urlencode "dbchoiceSelect=Empty Site" \
           --data-urlencode "atl_token=$TOKEN") ;;
      *setupusermanagementchoice.action*)               # Configure User Management -> internal
        PAGE=$(wstep "$BASE/setup/setupusermanagementchoice.action" \
           --data-urlencode "userManagementChoice=internal" --data-urlencode "atl_token=$TOKEN") ;;
      *setupadministrator.action*)                      # System Administrator (completes setup)
        PAGE=$(wstep "$BASE/setup/setupadministrator.action" \
           --data-urlencode "username=$ADMIN_USER" --data-urlencode "fullName=Admin" \
           --data-urlencode "email=admin@example.com" \
           --data-urlencode "password=$ADMIN_PASS" --data-urlencode "confirm=$ADMIN_PASS" \
           --data-urlencode "setup-next-button=Next" \
           --data-urlencode "atl_token=$TOKEN") ;;
      *finishsetup*|*setupsucessful*|*setupsuccessful*) # final step (usually auto-reached by the admin POST)
        PAGE=$(wstep "$BASE/setup/finishsetup.action" \
           --data-urlencode "atl_token=$TOKEN") ;;
      *)
        sleep 8; PAGE=$(cj -L "$BASE/" || true) ;;
    esac
    # Fail fast on a genuinely stuck wizard: a known step whose POST returned no next page is a silent
    # failure (wstep WARNed + logged the code). On 10.2.13 the loop then re-seeds from GET /, which can't
    # advance past user-management, so it would otherwise oscillate to the 20-spin cap behind a generic
    # error. Once enough POSTs have failed, abort early so the run ends in ~half a minute with the
    # captured HTTP code surfaced below. The happy path never increments this (POSTs return the next form).
    if [ -n "$STEP" ] && [ -z "$PAGE" ]; then
      POST_FAILS=$((POST_FAILS + 1))
      [ "$POST_FAILS" -ge 6 ] \
        && { echo ">> WARN: $POST_FAILS wizard-step POSTs failed with no progress — aborting setup early" >&2; break; }
    fi
    sleep 2
  done

  state=$(curl -fsS "$BASE/status" 2>/dev/null | grep -o '"state":"[^"]*"' | cut -d'"' -f4 || true)
  if [ "$state" != "RUNNING" ]; then
    HINT=""
    [ -s "$WSTEP_LOG" ] && HINT=" Last step-POST failure: $(tail -1 "$WSTEP_LOG")."
    fail "setup wizard did not reach RUNNING (state=$state, last step=$LAST_STEP).$HINT The licence is
   valid; the post-licence FLOW-BASED steps may need adjusting for this Confluence build — inspect:
   docker compose logs confluence"
  fi
  echo "   setup complete (/status: RUNNING)"
fi

# ----------------------------------------------------------------------------------------------------
# 3. install the plugin via UPM (PROVEN pattern: upm-token header + multipart POST + poll).
#    NB1: a freshly set-up Confluence DC REJECTS UPM file-uploads with HTTP 403 "Plugins cannot be
#    installed via upload" unless `-Dupm.plugin.upload.enabled=true` is on the JVM.
#    NB2: Confluence 10.2's UPM additionally rejects our unsigned dev JAR with "Signature check
#    failed!" unless `-Datlassian.upm.signature.check.disabled=true` is on the JVM.
#    compose.yaml sets both (plus the Basic-auth force-allow) via JVM_SUPPORT_RECOMMENDED_ARGS.
# ----------------------------------------------------------------------------------------------------
# Strict glob (not `ls | grep -v sources | head -1`): reject ambiguity so a stale older JAR in target/
# can't be installed and make the stack lie about what's deployed.
JAR=""
for j in ../target/likec4-confluence-*.jar; do
  case "$j" in *-sources.jar|*-tests.jar) continue;; esac
  [ -e "$j" ] || continue
  [ -z "$JAR" ] || fail "multiple plugin JARs under ../target ($JAR and $j) — clean stale builds"
  JAR="$j"
done
[ -n "${JAR:-}" ] || fail "no plugin JAR under ../target — run './mvnw -B package' (from the repo root) first"
echo ">> installing plugin via UPM: $JAR"

upm_token() {
  curl -s -u "$AUTH" -H 'Accept: application/vnd.atl.plugins.installed+json' \
    "$BASE/rest/plugins/1.0/" -D - -o /dev/null | tr -d '\r' \
    | awk -F': ' 'tolower($1)=="upm-token"{print $2}'
}
upm_detail() {
  curl -s -u "$AUTH" -H 'Accept: application/vnd.atl.plugins.plugin+json' \
    "$BASE/rest/plugins/1.0/$PLUGIN_KEY-key"
}
# Robust "is the plugin enabled?" test: parse the UPM JSON's top-level `enabled` rather than grepping
# for a brittle '"enabled":true' substring (a whitespace/pretty-print change, or a nested `enabled`
# field, would silently never match and burn the whole 180s deadline). node is a documented host prereq.
plugin_enabled() {
  upm_detail | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{process.exit(JSON.parse(s).enabled===true?0:1)}catch{process.exit(1)}})'
}

TOKEN=$(upm_token)
if [ -z "$TOKEN" ]; then
  # An empty token has several distinct causes that the old single message conflated. Probe the status
  # (one extra curl, only on the failure path we're about to abort on anyway) to point at the right one:
  # 401/403 = bad admin creds; a 2xx with no UPM-token header = UPM unavailable / unexpected shape; other
  # = the stack is up-but-broken (e.g. a 500/HTML error page), NOT a credentials problem.
  code=$(curl -s -o /dev/null -w '%{http_code}' -u "$AUTH" \
    -H 'Accept: application/vnd.atl.plugins.installed+json' "$BASE/rest/plugins/1.0/")
  case "$code" in
    401|403) fail "could not obtain UPM token: auth rejected (HTTP $code) — check the admin/admin credentials" ;;
    2*)      fail "could not obtain UPM token: request succeeded (HTTP $code) but no UPM-token header — is UPM available?" ;;
    *)       fail "could not obtain UPM token (HTTP $code) — Confluence is reachable but not serving the plugins API (up-but-broken?)" ;;
  esac
fi
# Fail loudly on an upload rejection (403 "Signature check failed" / 413 / 400) rather than discarding
# the response and then waiting out the 180s enable-poll on a plugin that was never uploaded.
up_code=$(curl -s -u "$AUTH" -X POST "$BASE/rest/plugins/1.0/?token=$TOKEN" \
  -H 'Accept: application/json' \
  -F "plugin=@$JAR;type=application/java-archive" -o /dev/null -w '%{http_code}')
case "$up_code" in
  2*) ;;
  *) fail "plugin upload rejected (HTTP $up_code) — check the dev JVM flags (upload-enabled / signature-check-disabled)";;
esac

echo ">> waiting for the plugin to enable"
deadline=$(( $(date +%s) + 180 ))
until plugin_enabled; do
  [ "$(date +%s)" -gt "$deadline" ] && fail "plugin $PLUGIN_KEY did not reach enabled in time"
  sleep 3
done
echo "   plugin enabled"

# UPM flips "enabled":true a moment BEFORE the plugin's JAX-RS resource finishes registering, so an
# immediate admin-config POST can race a 404 (the endpoint isn't routed yet). Poll the admin GET until
# it returns 200 — resource routed AND config bean wired; a transient 503/5xx must not count as ready,
# else the config POST below could race it (mirrors docker/e2e/c10-gate.sh).
echo ">> waiting for the admin REST resource to register"
restdeadline=$(( $(date +%s) + 60 ))
until [ "$(curl -s -o /dev/null -w '%{http_code}' -u "$AUTH" "$BASE/rest/likec4/1.0/admin")" = "200" ]; do
  [ "$(date +%s)" -gt "$restdeadline" ] && fail "admin REST resource did not register within 60s"
  sleep 2
done

# ----------------------------------------------------------------------------------------------------
# 4. configure admin (baseUrl -> mock-GitLab + allowlist) and seed a macro page.
# ----------------------------------------------------------------------------------------------------
echo ">> configuring admin (baseUrl=$MOCK_BASEURL, allowlist=$ALLOWLIST_JSON)"
# Check the admin-config result: a rejected baseUrl/allowlist would otherwise surface as a confusing
# "diagram won't load" far downstream instead of failing here at the config step.
cfg_code=$(curl -s -u "$AUTH" -X POST "$BASE/rest/likec4/1.0/admin" \
  -H 'Content-Type: application/json' \
  -d "{\"baseUrl\":\"$MOCK_BASEURL\",\"allowlist\":$ALLOWLIST_JSON,\"token\":\"test-token\"}" \
  -o /dev/null -w '%{http_code}')
case "$cfg_code" in 2*) ;; *) fail "admin config POST failed (HTTP $cfg_code)";; esac

echo ">> seeding space '$SPACE_KEY' + a likec4-diagram macro page"
# Mirror the seed-page handling below: tolerate ONLY the idempotent "already exists" case (a re-run);
# a real failure (bad licence/auth, validation) must fail loudly here rather than be swallowed by
# `|| true` and resurface as a confusing downstream error on the seed-page step that references it.
# `curl -s` returns 0 for HTTP 4xx/5xx (they flow into the case below), but a TRANSPORT failure (a
# transient TCP/connection blip — likeliest on a busy/RAM-gated box, exactly here) makes curl exit
# non-zero and would abort the whole bring-up at this assignment under `set -e`, after the expensive
# setup+install already succeeded. Fall back to code 000 so a transport error flows into the structural
# GET check below (which then decides skip-vs-fail) rather than a raw set -e abort.
space_resp=$(curl -s -u "$AUTH" -X POST "$BASE/rest/api/space" -H 'Content-Type: application/json' \
  -d "{\"key\":\"$SPACE_KEY\",\"name\":\"LikeC4\",\"type\":\"global\"}" -w '\n%{http_code}') || space_resp=$'\n000'
space_code=${space_resp##*$'\n'}; space_body=${space_resp%$'\n'*}
case "$space_code" in
  2*) ;;
  *) # A non-2xx may be a benign "already exists" on a re-run, or a real failure. Do NOT key on the error
     # BODY phrasing: Confluence's duplicate-key message varies across versions ("already exists" /
     # "already used" / "already in use" / "key ... not available" / …), so a body-substring match silently
     # fails a benign re-run on an unmatched phrasing. CONFIRM idempotency structurally instead — GET the
     # space and treat a 200 as "already exists" (skip); fail loudly only if it genuinely is not present.
     # Same transport-failure guard as the POST: a connection blip here must fail with the diagnostic
     # below (code 000 ≠ 200), not a raw set -e abort on this assignment.
     space_get_code=$(curl -s -u "$AUTH" -o /dev/null -w '%{http_code}' "$BASE/rest/api/space/$SPACE_KEY") || space_get_code=000
     if [ "$space_get_code" = "200" ]; then
       echo "   (space already exists — idempotent re-run, skipping)"
     else
       fail "space create POST failed (HTTP $space_code): $space_body"
     fi ;;
esac
# Check the seed-page result: a silently-swallowed failure (bad licence/auth, validation error) used
# to let "stack ready" print with no demo page. Tolerate ONLY the idempotent "already exists" case
# (a re-run) — fail loudly on anything else.
# Same transport-failure guard as the space POST above: a connection blip must flow into the structural
# check below (code 000) rather than abort the bring-up at this assignment under `set -e`.
seed_resp=$(curl -s -u "$AUTH" -X POST "$BASE/rest/api/content" -H 'Content-Type: application/json' \
  --data @seed-page.json -w '\n%{http_code}') || seed_resp=$'\n000'
seed_code=${seed_resp##*$'\n'}; seed_body=${seed_resp%$'\n'*}
case "$seed_code" in
  2*) ;;
  *) # A non-2xx may be a benign "already exists" on a re-run, or a real failure. Do NOT key on the error
     # BODY phrasing — Confluence's duplicate-title message varies across versions, so a body-substring
     # match silently fails a benign re-run on an unmatched phrasing (the exact fragility the space check
     # above was hardened against). CONFIRM idempotency structurally instead: query the space for a page
     # with this title and treat a non-empty result set as "already exists" (skip); fail loudly only if it
     # is genuinely absent. The content search returns HTTP 200 with an EMPTY `results` array when the page
     # is missing, so status alone is not enough — inspect `results` with node (the documented host prereq).
     seed_title=$(node -e 'process.stdout.write(JSON.parse(require("fs").readFileSync("seed-page.json","utf8")).title)')
     # A transport failure here yields empty output; the node check below then can't parse it and exits
     # non-zero → fail loudly with the diagnostic, rather than a raw set -e abort on this assignment.
     seed_get=$(curl -s -G -u "$AUTH" "$BASE/rest/api/content" \
       --data-urlencode "spaceKey=$SPACE_KEY" --data-urlencode "title=$seed_title") || seed_get=''
     if printf '%s' "$seed_get" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{process.exit((JSON.parse(s).results||[]).length>0?0:1)}catch{process.exit(1)}})'; then
       echo "   (macro page already exists — idempotent re-run, skipping)"
     else
       fail "seed macro page POST failed (HTTP $seed_code): $seed_body"
     fi ;;
esac

echo ">> stack ready at $BASE  (admin/admin)"
echo "   next: docker/e2e/c10-gate.sh   # the canonical live gate: (re)installs the JAR, runs GATE3/4/5"
echo "   (or: CONFLUENCE_BASE=$BASE docker/e2e/run.sh   # convenience render sweep — NOT the gate)"
echo "   teardown: docker/down.sh"
