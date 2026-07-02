# shellcheck shell=bash
# Sourced helper: resolve CONFLUENCE_BASE — the base URL the whole harness targets — honouring, in
# precedence order:
#   1. an explicit CONFLUENCE_BASE (shell env), else
#   2. CONFLUENCE_PORT (shell env, else the value in docker/.env), else
#   3. the :8090 default.
# up.sh reads docker/.env's CONFLUENCE_PORT to PUBLISH the port; the e2e gate (c10-gate.sh) and the
# render runner (e2e/run.sh) source this so they target the SAME port when an operator customises it,
# instead of hard-coding :8090 and failing to reach a stack published elsewhere. .env is located relative
# to THIS file (via BASH_SOURCE), so the sourcing script's cwd is irrelevant. Must be sourced by bash.
_rcb_docker_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Read CONFLUENCE_PORT from docker/.env only when it isn't already set in the environment (shell env wins,
# same precedence as compose). Tolerate a hand-edited .env: strip an inline `# comment`, surrounding
# whitespace, and a pair of surrounding quotes (mirrors up.sh's own parsing).
if [ -z "${CONFLUENCE_PORT:-}" ] && [ -f "${_rcb_docker_dir}/.env" ]; then
  # `|| true`: a hand-edited .env that DROPS the CONFLUENCE_PORT= line makes grep exit 1, and under the
  # sourcing script's `set -euo pipefail` (pipefail surfaces grep's status) that assignment would abort
  # the whole gate/runner — defeating the "tolerate a hand-edited .env" promise. Swallow the no-match so
  # the `[ -n "$_rcb_cp" ]` guard below falls through to the :8090 default instead.
  _rcb_cp="$(grep -E '^[[:space:]]*CONFLUENCE_PORT=' "${_rcb_docker_dir}/.env" | tail -1 | cut -d= -f2- || true)"
  _rcb_cp="${_rcb_cp%%#*}"                               # drop an inline # comment
  _rcb_cp="$(printf '%s' "$_rcb_cp" | tr -d '[:space:]')" # drop surrounding whitespace
  _rcb_cp="${_rcb_cp%\"}"; _rcb_cp="${_rcb_cp#\"}"       # strip a pair of surrounding double quotes
  _rcb_cp="${_rcb_cp%\'}"; _rcb_cp="${_rcb_cp#\'}"       # strip a pair of surrounding single quotes
  if [ -n "$_rcb_cp" ]; then CONFLUENCE_PORT="$_rcb_cp"; fi
fi
CONFLUENCE_BASE="${CONFLUENCE_BASE:-http://localhost:${CONFLUENCE_PORT:-8090}}"
