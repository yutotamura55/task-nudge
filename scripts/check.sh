#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
gradle_runner="${TASK_NUDGE_GRADLE:-$repo_root/gradlew}"

"$gradle_runner" --no-daemon --max-workers=1 \
  :core:test :app:testDebugUnitTest :app:lintDebug :core:detekt :app:detekt \
  :app:assembleDebug :app:assembleDebugAndroidTest
