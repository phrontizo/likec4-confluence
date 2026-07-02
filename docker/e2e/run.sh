#!/usr/bin/env bash
# run.sh — run the Playwright e2e specs in a container against the COMPOSE stack (Confluence 10.2.13
# at :8090, HTTP Basic auth). NOTE: this is NOT the gate — the authoritative compose gate is
# c10-gate.sh (it (re)installs the JAR and runs GATE3/4/5 via c10-gates.spec.ts). run.sh is a
# convenience sweep.
#
# Two backends, two auth models. The specs that authenticate the BROWSER page via
# `login.action?os_username=...` were written for the dev-amps backend (`atlas-mvn confluence:run`,
# :1990/confluence); on the 10.2 stack a login.action GET does NOT establish a session, so those
# specs cannot pass here. The compose-compatible specs instead preauth with HTTP Basic
# (setExtraHTTPHeaders + os_authType=basic). Running the login.action specs against :8090 would
# produce confusing red, so by default we run only the Basic-auth (compose) specs.
#
# Usage:
#   ./run.sh                       # run the compose-compatible specs listed below
#   ./run.sh sweep.spec.ts ...     # run only the named spec(s)
#   CONFLUENCE_BASE=... ./run.sh   # override the target base (default http://localhost:8090)
#
# Most compose specs below SELF-SEED their pages via REST when their env override (e.g. PAGE_ID) is
# unset, so a default `./run.sh` reproduces with no manual seeding. The EXCEPTION is live.spec.ts: it
# loads the "LikeC4 Test Diagram" page that `up.sh` created from seed-page.json (it targets that page by
# title, not by id), so it needs a prior `up.sh` and cannot self-seed. Pass an env override only to
# point a spec at a specific pre-existing page.
set -euo pipefail
cd "$(dirname "$0")"

# Resolve CONFLUENCE_BASE honouring an explicit override, else CONFLUENCE_PORT (env or docker/.env), else
# :8090 — so the sweep targets the same port up.sh published when an operator customises CONFLUENCE_PORT.
. ../lib/resolve-confluence-base.sh

# Admin creds for the specs, forwarded into the container split as AUTH_USER/AUTH_PASS (the env the
# compose specs read). Default admin:admin matches the throwaway harness; override AUTH to match a
# changed admin password so the whole sweep stays password-portable, not just the gate spec.
AUTH="${AUTH:-admin:admin}"

# Compose-stack (HTTP Basic) specs. The login.action / dev-amps specs (admin-chrome, admin-form,
# editor-loadviews, macro-native) and the real-GitLab spec (gitlab-render — see real-gitlab-e2e.sh) are
# intentionally excluded here. zero-git-browser is ALSO excluded, but NOT because it needs the amps
# backend (it preauths with HTTP Basic and targets :8090 like the members below): it needs the
# before/after mock-GitLab counter reads and the compose.mock-publish.yaml overlay that its dedicated
# runner docker/perf/zero-git-render.sh sets up, so running it bare here would have nothing to assert.
# (live.spec.ts and the xss/xss-notes specs were moved INTO this list — they now preauth with HTTP
# Basic and self-seed their pages, so their :8090 default works on the compose stack.)
# MEMORY-SENSITIVE MEMBERS: big-model.spec.ts and panel-clearance.spec.ts stress the browser's 20s
# compute budget with large cloud views and can TIME OUT on a RAM-constrained box. That is environmental,
# not a regression, and neither is part of the required gate (c10-gate.sh / GATE3-5) — so a timeout from
# just these two on a busy machine is expected noise, not a code failure. Re-run them on a quiet box.
# c10-gates.spec.ts (GATE3/4/5) is DELIBERATELY EXCLUDED from the default sweep: it is the gate spec, and
# it is only MEANINGFUL when run by c10-gate.sh, which reinstalls the freshly-built JAR AND clears the
# bundle cache first. Running it here against whatever JAR is currently deployed (and a possibly-WARM
# cache) would render green without proving the current code — a false-green that reads as "my change is
# proven" when it isn't. Run c10-gate.sh for the gate; pass `c10-gates.spec.ts` explicitly to run.sh only
# if you knowingly want it against the already-deployed JAR.
COMPOSE_SPECS=(postgres-render.spec.ts postgres-render-visible.spec.ts \
               big-model.spec.ts panel-clearance.spec.ts broken-styled.spec.ts sweep.spec.ts live.spec.ts \
               xss.spec.ts xss-notes.spec.ts)

if [ "$#" -gt 0 ]; then
  SPECS=("$@")
  echo ">> running requested spec(s): ${SPECS[*]}"
else
  SPECS=("${COMPOSE_SPECS[@]}")
  echo ">> running compose-compatible specs (Basic auth, :8090): ${SPECS[*]}"
  echo ">> excluded (dev-amps :1990 backend / real GitLab): admin-chrome admin-form" \
       "editor-loadviews macro-native gitlab-render"
  echo ">> excluded (needs the mock-counter harness docker/perf/zero-git-render.sh): zero-git-browser"
fi

# Pass the spec names as positional args (sh -c '... "$@"' sh "${SPECS[@]}") rather than splicing
# ${SPECS[*]} into the command string: each spec then reaches `playwright test` as its own quoted arg,
# so a spec name with a space/metacharacter (possible via the "$@" override above) can't word-split or
# be reinterpreted by the inner shell.
# The Playwright image is pinned by DIGEST, not just the mutable v1.48.0-jammy tag — see c10-gate.sh for
# the rationale. Refresh the @sha256 whenever you bump the 1.48.0 pin (docker honours the digest and
# ignores the tag, so a stale digest would keep running the OLD image despite a new tag).
docker run --rm --network host -v "$PWD:/e2e" -w /e2e \
  -e CONFLUENCE_BASE="$CONFLUENCE_BASE" \
  -e AUTH_USER="${AUTH%%:*}" -e AUTH_PASS="${AUTH#*:}" \
  mcr.microsoft.com/playwright:v1.48.0-jammy@sha256:7dbbf924428aad5c87a5a3a5bc38f23e110cb1f5427fbbc7dbc3231014a4b0db \
  sh -c 'if ! npm ci >/tmp/npm-install.log 2>&1; then echo "!! npm ci (against docker/e2e/package-lock.json) failed (offline, or a broken/partial install) — falling back to the image-bundled runner; install-log tail:" >&2; tail -n 15 /tmp/npm-install.log >&2 || true; fi; ver=$(npx playwright --version 2>/dev/null | grep -oE "[0-9]+\.[0-9]+\.[0-9]+" | head -1); case "$ver" in 1.48.*) : ;; *) echo "!! Playwright runner is \"$ver\", expected 1.48.x — a partial install or image drift left an UNPINNED runner; refusing to run on it (runner<->browser must match the image tag)." >&2; exit 1 ;; esac; npx playwright test "$@"' sh "${SPECS[@]}"
