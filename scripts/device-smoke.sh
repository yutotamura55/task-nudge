#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
adb_bin="${ADB:-adb}"
gradle_runner="${TASK_NUDGE_GRADLE:-$repo_root/gradlew}"

device_count="$("$adb_bin" devices | awk '$2 == "device" { count++ } END { print count+0 }')"
if [[ "$device_count" != 1 ]]; then
  echo "Expected exactly one authorized Android device; found $device_count. Start an emulator or connect a device, then check 'adb devices'." >&2
  exit 1
fi

original_font_scale="$("$adb_bin" shell settings get system font_scale | tr -d '\r')"
restore_font_scale() {
  local result=$?
  trap - EXIT
  if [[ "$original_font_scale" == "null" || -z "$original_font_scale" ]]; then
    if ! "$adb_bin" shell settings delete system font_scale >/dev/null; then
      echo "Unable to restore the original font scale" >&2
      if [[ "$result" -eq 0 ]]; then result=1; fi
    fi
  else
    if ! "$adb_bin" shell settings put system font_scale "$original_font_scale"; then
      echo "Unable to restore the original font scale" >&2
      if [[ "$result" -eq 0 ]]; then result=1; fi
    fi
  fi
  exit "$result"
}
trap restore_font_scale EXIT
"$adb_bin" shell settings put system font_scale 1.0

for name in tasks history settings; do
  "$adb_bin" shell rm -f "/sdcard/Pictures/TaskNudgeTest/$name.png"
done

test_status=0
"$gradle_runner" --no-daemon --max-workers=1 :app:connectedDebugAndroidTest || test_status=$?

screenshot_source="/sdcard/Pictures/TaskNudgeTest"
screenshot_target="$repo_root/build/device-screenshots"
mkdir -p "$screenshot_target"
for name in tasks history settings; do
  rm -f "$screenshot_target/$name.png"
done
if ! "$adb_bin" pull "$screenshot_source/." "$screenshot_target/"; then
  if [[ "$test_status" -eq 0 ]]; then
    echo "Unable to retrieve emulator screenshots from $screenshot_source" >&2
    exit 1
  fi
  exit "$test_status"
fi
for name in tasks history settings; do
  if [[ ! -s "$screenshot_target/$name.png" ]]; then
    echo "Missing emulator screenshot: $screenshot_target/$name.png" >&2
    if [[ "$test_status" -eq 0 ]]; then exit 1; fi
  fi
done
exit "$test_status"
