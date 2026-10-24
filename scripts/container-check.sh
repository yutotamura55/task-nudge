#!/usr/bin/env bash
# Runs scripts/check.sh (or the given command) in the build container.
# Usage: scripts/container-check.sh [command...]
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
image="task-nudge-build:local"
cache_volume="task-nudge-build-cache"

# The Dockerfile copies nothing, so it is built without a context.
docker build --quiet --tag "$image" - < "$repo_root/Dockerfile" >/dev/null

if [ "$#" -eq 0 ]; then
  set -- scripts/check.sh
fi

tty_flags=()
if [ -t 0 ] && [ -t 1 ]; then
  tty_flags=(--interactive --tty)
fi

exec docker run --rm "${tty_flags[@]}" \
  --user "$(id -u):$(id -g)" \
  --volume "$repo_root:/work" \
  --volume "$cache_volume:/cache" \
  "$image" "$@"
