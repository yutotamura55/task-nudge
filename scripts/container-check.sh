#!/usr/bin/env bash
# Runs scripts/check.sh (or the given command) in the build container.
# Usage: scripts/container-check.sh [command...]
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
image="task-nudge-build:local"
cache_volume="task-nudge-build-cache"

# Rootless Docker maps the host user to container UID 0, so --user would not keep
# generated files owned by the host user.
if docker info --format '{{.SecurityOptions}}' | grep -q rootless; then
  echo "rootless Docker is not supported; use the system Docker daemon." >&2
  exit 1
fi

# The Dockerfile copies nothing, so it is built without a context.
docker build --quiet --tag "$image" - < "$repo_root/Dockerfile" >/dev/null

uid="$(id -u)"
gid="$(id -g)"
# The cache holds Gradle init scripts and plugins that run as the host user, so
# only that user may write to it.
docker run --rm --user 0 --volume "$cache_volume:/cache" "$image" \
  sh -c "install -d -o $uid -g $gid -m 700 /cache /cache/gradle /cache/home"

if [ "$#" -eq 0 ]; then
  set -- scripts/check.sh
fi

tty_flags=()
if [ -t 0 ] && [ -t 1 ]; then
  tty_flags=(--interactive --tty)
fi

exec docker run --rm "${tty_flags[@]}" \
  --user "$uid:$gid" \
  --volume "$repo_root:/work" \
  --volume "$cache_volume:/cache" \
  "$image" "$@"
