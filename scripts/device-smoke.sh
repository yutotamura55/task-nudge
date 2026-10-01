#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
adb_bin="${ADB:-adb}"
gradle_runner="${TASK_NUDGE_GRADLE:-$repo_root/gradlew}"

device_count="$($adb_bin devices | awk '$2 == "device" { count++ } END { print count+0 }')"
if [[ "$device_count" != 1 ]]; then
  echo "Expected exactly one authorized Android device; found $device_count. Start an emulator or connect a device, then check 'adb devices'." >&2
  exit 1
fi

"$gradle_runner" --no-daemon --max-workers=1 :app:connectedDebugAndroidTest

screenshot_source="/sdcard/Android/data/dev.tasknudge/files/screenshots"
screenshot_target="$repo_root/build/device-screenshots"
mkdir -p "$screenshot_target"
"$adb_bin" pull "$screenshot_source/." "$screenshot_target/"
for name in tasks history settings; do
  if [[ ! -s "$screenshot_target/$name.png" ]]; then
    echo "Missing emulator screenshot: $screenshot_target/$name.png" >&2
    exit 1
  fi
done
