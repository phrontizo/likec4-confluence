#!/usr/bin/env bash
# real-gitlab-e2e.sh — prove the LikeC4 Confluence plugin works end-to-end against a REAL
# self-managed GitLab (gitlab/gitlab-ce), not the stub mock. Bucket-2 live-instance proof on top of
# the recorded-gitlab.com contract test (which already covers API-shape fidelity).
#
# Verified 2026-06-29 against gitlab-ce 19.1.1 on the running compose stack
# (network likec4-confluence-test_default, Confluence 10.2.13 at http://localhost:8090, admin/admin).
# Peak host memory during GitLab first-boot reconfigure+migrate stayed ~2.1 GiB free / swap healthy:
# no OOM. The plugin /resolve sha matched the GitLab Commits API id exactly, /source returned the 12
# LikeC4 files (9 *.c4 + 3 *.likec4.snap; PROVENANCE.md filtered out by the §8 filter), and the
# landscape view rendered live in a Confluence page (docker/e2e/gitlab-render.png, 5 react-flow nodes).
#
# NOTE: tokens are NOT committed. This script mints them at run time. Do not paste live tokens here.
set -euo pipefail

# Derive the Confluence container + its compose network from the RUNNING stack rather than hard-coding
# the default-project literals — those break the moment an operator brings the stack up with `-p <name>`
# (or COMPOSE_PROJECT_NAME set), which renames both the container and the network. `docker compose` is
# run from docker/ (this script's dir) so it targets this compose file. Fall back to the historical
# literals if derivation yields empty (stack down, or an old docker compose without `ps -q <svc>`), so
# the pre-existing behaviour is preserved. Robust under `set -u` (defaults on the command substitutions).
_RG_DOCKER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONF="$( (cd "$_RG_DOCKER_DIR" && docker compose ps -q confluence 2>/dev/null | head -1) || true )"
if [ -n "$CONF" ]; then
  # First compose network attached to the Confluence container (its name, e.g. <project>_default).
  NET="$(docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{"\n"}}{{end}}' "$CONF" 2>/dev/null | head -1 || true)"
  # Prefer the human-readable container NAME over the id for `docker exec` diagnostics; fall back to id.
  CONF="$(docker inspect -f '{{.Name}}' "$CONF" 2>/dev/null | sed 's#^/##' || echo "$CONF")"
fi
NET="${NET:-likec4-confluence-test_default}"
CONF="${CONF:-likec4-confluence-test-confluence-1}"
GL_HOST_PORT=8929
GL_URL="http://localhost:${GL_HOST_PORT}"      # from this host
GL_NET_URL="http://gitlab:${GL_HOST_PORT}"     # from the compose network (Confluence -> GitLab)
GL_API="${GL_URL}/api/v4"
PROJECT_ENC="acme%2Farchitecture"
MODEL_SRC="$(cd "$(dirname "$0")" && pwd)/mock-gitlab/repos/acme/architecture/big"
# Resolve CONF_BASE via the shared helper (honours CONFLUENCE_BASE / CONFLUENCE_PORT / docker/.env), like
# c10-gate.sh and e2e/run.sh — so an operator on a non-default CONFLUENCE_PORT isn't left targeting :8090
# (and the restore-to-mock EXIT trap doesn't POST to the wrong instance).
# shellcheck source=lib/resolve-confluence-base.sh
source "$(cd "$(dirname "$0")" && pwd)/lib/resolve-confluence-base.sh"
CONF_BASE="$CONFLUENCE_BASE"
CONF_AUTH="admin:admin"

# Restore-to-mock values (so the existing demo pages keep working afterwards).
MOCK_BASEURL="http://mockgitlab:8080"
MOCK_TOKEN="test-token"
MOCK_ALLOWLIST='["acme"]'

TMP=""  # temp clone dir (created in step 3); removed by the EXIT trap so a mid-push failure can't leave it
PAT_CURL_CFG=""  # mode-600 curl config carrying the PRIVATE-TOKEN header (created in step 3); removed on EXIT
PLUGIN_ADMIN_BODY=""  # mode-600 file carrying the plugin admin POST body (real token); removed on EXIT

# Restore the plugin admin config to the mock on EXIT — even if the script fails partway through, so
# it never leaves the plugin pointed at a now-orphaned real-GitLab container (which would break the
# existing demo pages). Harmless before step 4 (config is already the mock). Also removes the temp clone
# dir (and its .git/) so a mid-push abort never leaves the working tree on disk.
cleanup() {
  curl -s -u "$CONF_AUTH" -X POST "${CONF_BASE}/rest/likec4/1.0/admin" -H 'Content-Type: application/json' \
    -d "{\"baseUrl\":\"${MOCK_BASEURL}\",\"allowlist\":${MOCK_ALLOWLIST},\"token\":\"${MOCK_TOKEN}\"}" >/dev/null 2>&1 || true
  echo "restored plugin admin config -> mock GitLab"
  [ -n "$TMP" ] && rm -rf "$TMP"
  [ -n "$PAT_CURL_CFG" ] && rm -f "$PAT_CURL_CFG"
  [ -n "$PLUGIN_ADMIN_BODY" ] && rm -f "$PLUGIN_ADMIN_BODY"
  # Reclaim the ~2 GiB gitlab-ce container on EXIT by default. Left running it silently sits on top of the
  # resident Confluence+Postgres dev stack, and the next up.sh / c10-gate.sh then contends for RAM and can
  # OOM on a memory-gated box — attributed to the wrong cause. The next run re-creates it (step 1 does
  # `docker rm -f likec4-gitlab` first regardless), so removing it here loses nothing. Set KEEP_GITLAB=1 to
  # keep it for post-run inspection.
  if [ -n "${KEEP_GITLAB:-}" ]; then
    echo "KEEP_GITLAB set — leaving likec4-gitlab running (reclaim with: docker rm -f likec4-gitlab)"
  else
    docker rm -f likec4-gitlab >/dev/null 2>&1 || true
    echo "removed the likec4-gitlab container to reclaim RAM (set KEEP_GITLAB=1 to keep it)"
  fi
  return 0  # never let cleanup change the script's exit status
}
trap cleanup EXIT

# 1) Run gitlab-ce on the compose network with aggressive low-memory config. The grafana/mattermost
#    keys were REMOVED in GitLab 19.x (hard reconfigure abort) — use prometheus_monitoring as the
#    single master switch for all exporters instead of listing each one.
docker rm -f likec4-gitlab >/dev/null 2>&1 || true
docker run -d --name likec4-gitlab --network "$NET" --hostname gitlab \
  -p 127.0.0.1:${GL_HOST_PORT}:${GL_HOST_PORT} --shm-size 256m \
  -e GITLAB_OMNIBUS_CONFIG="external_url '${GL_NET_URL}'; puma['worker_processes']=0; puma['min_threads']=1; puma['max_threads']=4; sidekiq['concurrency']=5; prometheus_monitoring['enable']=false; gitlab_rails['monitoring_whitelist']=['127.0.0.1/8']; gitlab_kas['enable']=false; registry['enable']=false; gitlab_pages['enable']=false; gitlab_rails['gitlab_default_projects_features_container_registry']=false" \
  gitlab/gitlab-ce:19.1.1-ce.0   # pinned (the verified version); :latest drifts and removes config keys

# 2) Wait for ready. /api/v4/version returns 200 or 401 (Rails answering, authenticated or not) once the
#    app is up; that is the signal. Keep waiting through 000 (no connection) AND the 502/503 that GitLab's
#    bundled nginx serves during the long reconfigure/migrate window before Rails is ready — breaking on
#    those would feed a 502 HTML body into the PAT-mint/groups/projects `python3 json.load(...)` below.
echo "Waiting for GitLab (first boot reconfigure+migrate is slow, ~5-12 min)..."
until case "$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 "${GL_API}/version")" in 200|401) true;; *) false;; esac; do
  case "$(docker ps -a --filter name=likec4-gitlab --format '{{.Status}}')" in
    *Exited*) echo "GitLab container exited:"; docker logs likec4-gitlab 2>&1 | tail -25; exit 1;; esac
  sleep 10
done
echo "GitLab up: $(curl -s -H "PRIVATE-TOKEN: bootstrap" "${GL_API}/version" || true)"

# 3) Mint a root PAT (api scope => full read/write incl. git-over-http) WITHOUT needing the web UI.
#    Use a RANDOM token (not a date-derived, guessable one). Pass it to the runner via `docker exec -e`
#    and read ENV['ROOT_PAT'] in the Ruby, so the secret stays OUT of the container argv / `docker
#    inspect` (matching how the plugin token is handled below). od reads a fixed count (no SIGPIPE).
ROOT_PAT="glpat-$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')"
# `gitlab-rails runner` returns exit 0 even when the embedded Ruby raises, so `set -e` alone would NOT
# catch a failed mint — the failure would only surface much later as a confusing 401 / json.load KeyError
# on the first API call below. Make the Ruby print an explicit PAT_OK sentinel only once the token is
# persisted, capture stdout+stderr, and fail loud here if the sentinel is absent.
MINT_OUT=$(docker exec -e ROOT_PAT="${ROOT_PAT}" likec4-gitlab gitlab-rails runner "
  u = User.find_by_username('root')
  t = u.personal_access_tokens.create!(scopes: ['api','read_repository','write_repository'], name: 'e2e-root', expires_at: 365.days.from_now)
  t.set_token(ENV['ROOT_PAT']); t.save!
  puts(t.persisted? ? 'PAT_OK' : 'PAT_FAIL')" 2>&1) || true
case "$MINT_OUT" in
  *PAT_OK*) : ;;
  *) echo "!! failed to mint root PAT via gitlab-rails runner:"; echo "$MINT_OUT"; exit 1 ;;
esac

# Carry the PAT in a mode-600 curl config file (passed via `curl --config`), NOT `-H "PRIVATE-TOKEN:
# $ROOT_PAT"` on the command line — an argv header lands in /proc/<pid>/cmdline, visible to other host
# users for the duration of each request. This matches the same keep-the-secret-out-of-argv discipline
# already applied to the git push (credential helper) and the plugin token (docker exec -e). Removed on EXIT.
PAT_CURL_CFG=$(mktemp); chmod 600 "$PAT_CURL_CFG"
printf 'header = "PRIVATE-TOKEN: %s"\n' "$ROOT_PAT" > "$PAT_CURL_CFG"

# Group acme + project architecture (use namespace_id, NOT namespace_path — the latter is ignored
# and the project lands under root/). Capture each response and parse it separately so an API error
# body (KeyError on ["id"]) fails with a clear per-step diagnostic naming the failed call and echoing
# the body, not an opaque python traceback + pipefail abort (matching the PAT-mint sentinel above).
GID_RESP=$(curl -s --config "$PAT_CURL_CFG" -X POST "${GL_API}/groups" \
  --data-urlencode name=acme --data-urlencode path=acme --data-urlencode visibility=private)
GID=$(printf '%s' "$GID_RESP" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])' 2>/dev/null) \
  || { echo "!! failed to create group 'acme' (GitLab API response follows):"; echo "$GID_RESP"; exit 1; }
PID_RESP=$(curl -s --config "$PAT_CURL_CFG" -X POST "${GL_API}/projects" \
  --data-urlencode name=architecture --data-urlencode path=architecture \
  --data-urlencode "namespace_id=${GID}" --data-urlencode visibility=private \
  --data-urlencode default_branch=main --data-urlencode initialize_with_readme=false)
PID=$(printf '%s' "$PID_RESP" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])' 2>/dev/null) \
  || { echo "!! failed to create project 'acme/architecture' (GitLab API response follows):"; echo "$PID_RESP"; exit 1; }

# Push the big/ cloud-system model (incl. hidden .likec4/*.snap) to the default branch over HTTP.
# Keep the PAT OUT of the git argv AND .git/config: putting it in the remote URL (oauth2:TOKEN@host)
# lands it in `git remote add`'s /proc/<pid>/cmdline AND persists it in .git/config, contradicting this
# script's own discipline (step 3 passes ROOT_PAT via `docker exec -e`, not argv). Instead feed it via a
# one-shot credential helper that reads the EXPORTED $ROOT_PAT from the environment when git challenges,
# so only the literal string "$ROOT_PAT" appears in argv (single-quoted, not expanded by this shell) and
# the -c override is per-invocation (never written to disk). $TMP is removed by the EXIT trap.
export ROOT_PAT
TMP=$(mktemp -d); cp -a "${MODEL_SRC}/." "$TMP/"; ( cd "$TMP"
  git init -q -b main
  git -c user.email=ci@phrontizo.com -c user.name=CI add -A
  git -c user.email=ci@phrontizo.com -c user.name=CI commit -q -m 'LikeC4 cloud-system model (big)'
  git -c credential.helper='!f() { echo username=oauth2; echo "password=$ROOT_PAT"; }; f' \
      push "http://localhost:${GL_HOST_PORT}/acme/architecture.git" main )

# Confirm via the GitLab API: commit id + archive download.
API_SHA_RESP=$(curl -s --config "$PAT_CURL_CFG" "${GL_API}/projects/${PROJECT_ENC}/repository/commits/main")
API_SHA=$(printf '%s' "$API_SHA_RESP" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])' 2>/dev/null) \
  || { echo "!! failed to read the main commit id (GitLab API response follows):"; echo "$API_SHA_RESP"; exit 1; }
curl -s --config "$PAT_CURL_CFG" -o /dev/null -w 'archive http=%{http_code}\n' \
  "${GL_API}/projects/${PROJECT_ENC}/repository/archive.tar.gz?sha=${API_SHA}"
echo "GitLab API commit id (main) = ${API_SHA}"

# Read-only token for the PLUGIN (scopes read_repository, read_api; Reporter).
# Expiry is computed (+365 days), matching the ROOT_PAT above, so this script does not become a
# time-bomb that mints an already-rejected token after a fixed calendar date.
PLUGIN_TOKEN_EXPIRES="$(date -u -d '+365 days' +%F)"
# Capture + parse separately (see the GID/PID note): a failed mint returns an error JSON with no
# "token" key, so the ["token"] KeyError must surface as a clear diagnostic, not an opaque traceback.
# On the failure path the body is the error response (no token present), so echoing it leaks nothing.
PLUGIN_TOKEN_RESP=$(curl -s --config "$PAT_CURL_CFG" -X POST "${GL_API}/projects/${PID}/access_tokens" \
  -H 'Content-Type: application/json' \
  -d '{"name":"likec4-plugin-ro","scopes":["read_repository","read_api"],"access_level":20,"expires_at":"'"${PLUGIN_TOKEN_EXPIRES}"'"}')
PLUGIN_TOKEN=$(printf '%s' "$PLUGIN_TOKEN_RESP" | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])' 2>/dev/null) \
  || { echo "!! failed to mint the read-only plugin token (GitLab API response follows):"; echo "$PLUGIN_TOKEN_RESP"; exit 1; }

# 4) Point the plugin at REAL gitlab (after verifying reachability FROM the Confluence container).
# Pass the token via `docker exec -e` rather than interpolating it into the sh -c string, so it never
# lands in the container process's argv (/proc/<pid>/cmdline) or `docker inspect`. $TOK is escaped
# (\$TOK) so it expands inside the container from the env var, not on the host.
docker exec -e TOK="${PLUGIN_TOKEN}" "$CONF" sh -c "curl -s -o /dev/null -w 'confluence->gitlab http=%{http_code}\n' --max-time 10 -H \"PRIVATE-TOKEN: \$TOK\" ${GL_NET_URL}/api/v4/projects"
# Carry the plugin token in the POST BODY via a mode-600 temp file (curl --data @file), NOT interpolated
# into `-d "{...token...}"` on the host command line — an argv body lands in /proc/<pid>/cmdline, visible
# to other host users. Mirrors the PAT (--config file) and the reachability check (docker exec -e) above.
PLUGIN_ADMIN_BODY=$(mktemp); chmod 600 "$PLUGIN_ADMIN_BODY"
printf '{"baseUrl":"%s","allowlist":["acme"],"token":"%s"}' "${GL_NET_URL}" "${PLUGIN_TOKEN}" > "$PLUGIN_ADMIN_BODY"
curl -s -u "$CONF_AUTH" -X POST "${CONF_BASE}/rest/likec4/1.0/admin" -H 'Content-Type: application/json' \
  --data @"$PLUGIN_ADMIN_BODY"; echo

# 5) Verify plugin -> real gitlab. The plugin /resolve sha MUST equal the GitLab API commit id.
echo "plugin /resolve = $(curl -s -u "$CONF_AUTH" "${CONF_BASE}/rest/likec4/1.0/resolve?project=acme/architecture&ref=main")"
echo "plugin /source  = $(curl -s -u "$CONF_AUTH" "${CONF_BASE}/rest/likec4/1.0/source?project=acme/architecture&ref=main&path=" | python3 -c 'import sys,json;d=json.load(sys.stdin);print("files=",sorted(d["files"]))')"
# Render proof: docker/e2e/gitlab-render.spec.ts (run via the docker/e2e/run.sh pattern, single spec).
# Capture the spec's exit code EXPLICITLY (via `|| SPEC_RC=$?`, which also shields it from `set -e`)
# rather than relying on it being the last command before the trailing echo — so a future line added
# below can never mask a failing render, and the result is reported unambiguously. Mirrors the rc-capture
# discipline in perf/run.sh and zero-git-render.sh.
SPEC_RC=0
# Playwright image pinned by DIGEST, not just the mutable v1.48.0-jammy tag — see docker/e2e/c10-gate.sh
# for the rationale; refresh the @sha256 whenever you bump the 1.48.0 pin (docker honours the digest and
# ignores the tag).
( cd "$(dirname "$0")/e2e" && docker run --rm --network host -v "$PWD:/e2e" -w /e2e \
    -e CONFLUENCE_BASE="${CONF_BASE}" \
    -e AUTH_USER="${CONF_AUTH%%:*}" -e AUTH_PASS="${CONF_AUTH#*:}" \
    mcr.microsoft.com/playwright:v1.48.0-jammy@sha256:7dbbf924428aad5c87a5a3a5bc38f23e110cb1f5427fbbc7dbc3231014a4b0db \
    sh -c 'if ! npm ci >/tmp/npm-install.log 2>&1; then echo "!! npm ci (against docker/e2e/package-lock.json) failed (offline, or a broken/partial install) — using the image-bundled runner; install-log tail:" >&2; tail -n 15 /tmp/npm-install.log >&2 || true; fi; npx playwright test gitlab-render.spec.ts --reporter=list' ) || SPEC_RC=$?
if [ "$SPEC_RC" -ne 0 ]; then
  echo "FAIL: gitlab-render.spec.ts against real GitLab (rc=$SPEC_RC)" >&2
  exit "$SPEC_RC"
fi
echo "PASS: gitlab-render.spec.ts rendered against real GitLab"

# 6) Restoring the plugin admin config back to the mock (so existing demo pages keep working) is now
#    handled by the restore_mock EXIT trap above — it runs on success AND on any early failure. The
#    ref->sha cache (RefShaCache, default 60s TTL) self-heals; demo pages use ref=main2 anyway.

# The likec4-gitlab container is removed by the EXIT trap above (unless KEEP_GITLAB=1), so RAM is
# reclaimed automatically — no manual cleanup needed on the memory-gated box.
echo "Done."
