#!/usr/bin/env bash
# Captures README screenshots (Markets, Portfolio, Stock detail, News) from a connected device.
#
# Usage:  ANDROID_SERIAL=<serial> docs/capture-screenshots.sh
#
# The device must be unlocked. Installs the `benchmark` build (talks to localhost) and forwards
# port 8080 to a local mock server with low latency and no injected failures.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/docs/screenshots"
ADB="${ADB:-$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")}"
PKG=com.example.myapplication
mkdir -p "$OUT"

if ! lsof -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1; then
  MOCK_PORT=8080 LATENCY_MS=200 JITTER_MS=200 FAILURE_RATE=0 node "$ROOT/mock-server/server.js" >/dev/null 2>&1 &
  trap 'kill $! 2>/dev/null || true' EXIT
  sleep 1
fi

if "$ADB" shell dumpsys window | grep -q 'isKeyguardShowing=true'; then
  echo "Device is locked. Unlock it and run again." >&2
  exit 1
fi

"$ADB" reverse tcp:8080 tcp:8080
(cd "$ROOT" && ./gradlew -q :app:installBenchmark)

# Taps the centre of the first node whose text or content-desc matches $1.
tap() {
  "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null
  local bounds
  bounds=$("$ADB" shell cat /sdcard/ui.xml | tr '>' '\n' | grep -E "(text|content-desc)=\"$1\"" | head -1 |
    sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
  [ -n "$bounds" ] || { echo "No element matching '$1'" >&2; exit 1; }
  read -r x1 y1 x2 y2 <<<"$bounds"
  "$ADB" shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
}

shot() { sleep "${2:-4}"; "$ADB" exec-out screencap -p >"$OUT/$1.png"; echo "saved docs/screenshots/$1.png"; }

"$ADB" shell am force-stop "$PKG"
"$ADB" shell am start -W -n "$PKG/.MainActivity" >/dev/null
shot markets 6

tap NVDA
shot detail 5
"$ADB" shell input keyevent KEYCODE_BACK

tap Portfolio
shot portfolio 5

tap News
shot news 6
