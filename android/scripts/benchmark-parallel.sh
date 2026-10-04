#!/usr/bin/env bash
# Parallel chunks benchmark (docs/benchmarks.md). Needs the mock server on the host (port 8080)
# and one emulator or device. Usage, from android/:  scripts/benchmark-parallel.sh [results.tsv]
# Each line of the results file: preset, direction, size, N, run, milliseconds.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=${1:-build/benchmark-parallel.tsv}
SERVER=${SERVER:-http://localhost:8080}
RUNS=${RUNS:-3}
PKG=com.maanit.stableshare

# The Slow network download needs a 20 MiB file; seed it next to the samples (skipped if present).
(cd ../server && node -e "
  import('./src/config.js').then(async ({ loadConfig, MiB }) => {
    const { storagePaths } = await import('./src/storage.js');
    const { writeSeededFile } = await import('./src/seedData.js');
    await writeSeededFile(storagePaths(loadConfig().storageDir), 'bench-20MB', 20 * MiB);
  });")

./gradlew -q installDebug installDebugAndroidTest

faults() { curl -sf -X PUT -H 'Content-Type: application/json' -d "$1" "$SERVER/admin/faults" > /dev/null; }
OFF='{"enabled":false}'
SLOW='{"enabled":true,"latencyMs":800,"latencyJitterMs":400,"bandwidthKbps":512,"errorRate":0,"timeoutRate":0,"dropMidBodyRate":0,"dropAfterProcessRate":0,"corruptRate":0}'

mkdir -p "$(dirname "$OUT")"
: > "$OUT"
run() { # preset faults direction size n
  local preset=$1 body=$2 dir=$3 size=$4 n=$5 extra
  if [ "$dir" = upload ]; then extra="-e sizeMb $size"; else extra="-e fileId $([ "$size" = 200 ] && echo sample-200MB || echo bench-20MB)"; fi
  faults "$body"
  echo "$(date +%T) $preset $dir ${size} MiB N=$n" >&2
  adb shell am instrument -w -r -e class $PKG.ParallelChunksBenchmark -e direction "$dir" -e n "$n" -e runs "$RUNS" $extra \
    $PKG.test/androidx.test.runner.AndroidJUnitRunner > build/bench-last.txt
  grep -q 'run_0_ms' build/bench-last.txt || { cat build/bench-last.txt >&2; faults "$OFF"; exit 1; }
  grep -o 'run_[0-9]*_ms=[0-9]*' build/bench-last.txt | while IFS='=' read -r key ms; do
    i=${key#run_}; i=${i%_ms}
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$preset" "$dir" "$size" "$n" "$i" "$ms" | tee -a "$OUT" >&2
  done
}
for n in 1 2 4; do run off "$OFF" upload 200 $n; run off "$OFF" download 200 $n; done
for n in 1 2 4; do run slow "$SLOW" upload 20 $n; run slow "$SLOW" download 20 $n; done
faults "$OFF"
echo "done: $OUT" >&2
