#!/usr/bin/env bash
# Runs the Markets list performance suite and archives results for release-to-release comparison.
#
# Usage:  perf/run-benchmarks.sh [label]          e.g. perf/run-benchmarks.sh v1.0
#         perf/compare.py perf/results/v1.0 perf/results/v1.1
#
# Results land in perf/results/<label>/: *-benchmarkData.json (metrics) + *.perfetto-trace files.
#
# For numbers you can compare, use the same physical device each time, charged, screen on,
# and nothing else running. Emulator results are only good for checking that the suite works.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LABEL="${1:-$(date +%Y%m%d-%H%M%S)}"
OUT="$ROOT/perf/results/$LABEL"
ADB="${ADB:-$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")}"
PORT=8080

# adb and Gradle both honor ANDROID_SERIAL; require it when several devices are attached so the
# suite never runs on (and mixes results from) more than one device.
if [ -z "${ANDROID_SERIAL:-}" ] && [ "$("$ADB" devices | grep -c -w device$)" -gt 1 ]; then
  echo "Multiple devices attached; pick one: ANDROID_SERIAL=<serial> $0 $LABEL" >&2
  "$ADB" devices -l >&2
  exit 1
fi

if lsof -iTCP:$PORT -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Port $PORT is already in use. Stop the running mock server first." >&2
  exit 1
fi

# Deterministic backend: no added latency, no injected failures, long list for scrolling.
# (Network latency is out of scope here; we measure the app's own rendering/CPU cost.)
MOCK_PORT=$PORT LATENCY_MS=0 JITTER_MS=0 FAILURE_RATE=0 EXTRA_STOCKS=300 \
  node "$ROOT/mock-server/server.js" > "$ROOT/perf/mock-server.log" 2>&1 &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null || true' EXIT
sleep 1

# The benchmark build type talks to localhost; forward it to this machine.
"$ADB" reverse tcp:$PORT tcp:$PORT

EXTRA_ARGS=()
if [ "$("$ADB" shell getprop ro.kernel.qemu | tr -d '\r')" = "1" ]; then
  echo "WARNING: running on an emulator. Results are NOT comparable across releases." >&2
  EXTRA_ARGS+=("-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR")
fi

cd "$ROOT"
rm -rf benchmark/build/outputs/connected_android_test_additional_output
./gradlew :benchmark:connectedBenchmarkAndroidTest "${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}"

mkdir -p "$OUT"
find benchmark/build/outputs/connected_android_test_additional_output \
  \( -name '*benchmarkData.json' -o -name '*.perfetto-trace' \) -exec cp {} "$OUT/" \;
"$ADB" shell getprop ro.product.model > "$OUT/device.txt"
"$ADB" shell getprop ro.build.fingerprint >> "$OUT/device.txt"

echo
echo "Saved results to $OUT"
python3 "$ROOT/perf/compare.py" "$OUT" 2>/dev/null || true
