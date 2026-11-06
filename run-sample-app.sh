#!/usr/bin/env bash
# Install and launch LiveKit sample-app (ViewBinding demo) without Android Studio.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

APP_ID="io.livekit.android"
ACTIVITY="io.livekit.android.sample.MainActivity"

if ! command -v adb >/dev/null 2>&1; then
  echo "error: adb not found. Add Android SDK platform-tools to PATH." >&2
  exit 1
fi

echo "==> Checking device..."
adb start-server >/dev/null
DEVICES="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
if [[ -z "$DEVICES" ]]; then
  echo "error: no online device/emulator. Connect one, then retry." >&2
  adb devices -l >&2 || true
  exit 1
fi
echo "$DEVICES" | while read -r d; do echo "    device: $d"; done

echo "==> Building & installing :sample-app (debug)..."
./gradlew :sample-app:installDebug

echo "==> Launching $APP_ID/$ACTIVITY ..."
adb shell am start -n "${APP_ID}/${ACTIVITY}"

echo "==> Done."
