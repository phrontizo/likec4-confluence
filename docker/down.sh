#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

# `docker compose down` discards the containers and their writable layer — and the set-up Confluence
# home lives in that layer (Postgres is tmpfs, already ephemeral). We deliberately DO NOT pass `-v`:
# the compose file declares no named volumes today, so `-v` is currently a no-op — but if a future
# revision persists e.g. the Confluence home in a named volume, `-v` would silently wipe it on every
# teardown. `--remove-orphans` (kept) only prunes stray containers, never volumes. The stack is
# long-lived and expensive to rebuild (full setup wizard + plugin install), so confirm before wiping
# unless -y/--force (or FORCE=1) is given, or stdin is not a TTY (CI/non-interactive proceeds).
FORCE="${FORCE:-}"
case "${1:-}" in -y|--force) FORCE=1 ;; esac
if [ -z "$FORCE" ] && [ -t 0 ]; then
  printf 'This REMOVES the stack and its set-up Confluence home (Postgres is tmpfs, already ephemeral). Continue? [y/N] '
  # `|| ans=""` so EOF at the prompt (Ctrl-D) is treated as a decline, not a `set -e` abort BEFORE the
  # case below (which would exit non-zero with no "aborted" message — reading as a failure, not a choice).
  read -r ans || ans=""
  # A deliberate "N" is a successful safe choice, not a failure: exit 0 so a wrapping script/CI step
  # does not treat declining the destructive teardown as an error.
  case "$ans" in y|Y|yes|YES) ;; *) echo "aborted (run with -y to skip this prompt)."; exit 0 ;; esac
fi
docker compose down --remove-orphans
echo "torn down."
