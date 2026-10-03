#!/usr/bin/env bash
# Chaos test: proves the protocol end to end under injected faults.
#   1. seed sample files, start the server on CHAOS_PORT (default 18080) with STORAGE_DIR (default ./storage)
#   2. enable faults: latency 20±20 ms, 10% 503s, 5% lost responses (dropAfterProcess), 5% mid-body drops, 2% corrupt
#   3. upload the 200 MB file with the CLI, kill -9 it mid-transfer, rerun it to completion
#   4. download the 200 MB file the same way (kill -9 + resume)
#   5. assert the server-assembled upload and the downloaded file both match the seed's SHA-256
set -euo pipefail
cd "$(dirname "$0")/.."

PORT="${CHAOS_PORT:-18080}"
SERVER="http://127.0.0.1:${PORT}"
FILE_ID="${CHAOS_FILE:-sample-200MB}"
KILL_AFTER="${CHAOS_KILL_AFTER:-30}" # chunks completed before the kill
export STORAGE_DIR="${STORAGE_DIR:-./storage}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/stableshare-chaos.XXXXXX")"
SERVER_PID=""
CLI=(node scripts/cli-client.js --server "$SERVER")

step() { printf '\n==> %s\n' "$*"; }
fail() { printf '\nCHAOS TEST FAILED: %s\n' "$*" >&2; exit 1; }
cleanup() {
  local code=$?
  if [ -n "$SERVER_PID" ]; then kill "$SERVER_PID" 2>/dev/null || true; wait "$SERVER_PID" 2>/dev/null || true; fi
  if [ "$code" -eq 0 ]; then rm -rf "$WORK"; else echo "Logs kept in $WORK" >&2; fi
}
trap cleanup EXIT

json_field() { node -e 'const o=JSON.parse(require("fs").readFileSync(0,"utf8"));console.log(process.argv[1].split(".").reduce((a,k)=>a[k],o))' "$1"; }
sha_of() { shasum -a 256 "$1" | cut -d' ' -f1; }
progress_count() { grep -c '^progress' "$1" 2>/dev/null || true; }

# Runs the CLI once, kills it with SIGKILL after KILL_AFTER chunks, then reruns it to completion.
run_with_kill() {
  local name="$1"; shift
  local run1="$WORK/$name-run1.log" run2="$WORK/$name-run2.log"
  "${CLI[@]}" "$@" >"$run1" 2>&1 &
  local pid=$!
  while [ "$(progress_count "$run1")" -lt "$KILL_AFTER" ]; do
    kill -0 "$pid" 2>/dev/null || { cat "$run1"; fail "$name finished or died before it could be killed"; }
    sleep 0.1
  done
  kill -9 "$pid"
  wait "$pid" 2>/dev/null || true
  echo "killed $name (SIGKILL) after $(progress_count "$run1") chunks; $(grep -c '^\[retry\]' "$run1" || true) retries before the kill"
  "${CLI[@]}" "$@" >"$run2" 2>&1 || { tail -20 "$run2"; fail "$name did not complete after restart"; }
  local resumed
  resumed="$(grep '^resume:' "$run2" | head -1)"
  echo "restarted $name: $resumed"
  case "$resumed" in "resume: 0/"*) fail "$name restarted from scratch instead of resuming";; esac
  echo "$(grep -c '^\[retry\]' "$run2" || true) retries and $(grep -c 'recovered$' "$run2" || true) lost-response recoveries after restart"
  grep '^RESULT' "$run2" | sed 's/^RESULT //' >"$WORK/$name.result.json"
}

step "Seeding sample files into $STORAGE_DIR"
node scripts/seed.js
EXPECTED="$(json_field sha256 <"$STORAGE_DIR/files/$FILE_ID.meta.json")"
echo "expected sha256 of $FILE_ID: $EXPECTED"

step "Starting server on $SERVER"
PORT="$PORT" HOST=127.0.0.1 node src/server.js >"$WORK/server.log" 2>&1 &
SERVER_PID=$!
for _ in $(seq 1 50); do curl -sf "$SERVER/health" >/dev/null && break; sleep 0.1; done
curl -sf "$SERVER/health" >/dev/null || fail "server did not become healthy"

step "Enabling faults"
curl -sf -X PUT -H 'Content-Type: application/json' "$SERVER/admin/faults" -d '{
  "enabled": true, "seed": 20261003,
  "latencyMs": 20, "latencyJitterMs": 20,
  "errorRate": 0.10, "dropAfterProcessRate": 0.05, "dropMidBodyRate": 0.05, "corruptRate": 0.02
}'
echo

step "Upload $FILE_ID (kill -9 after $KILL_AFTER chunks, then resume)"
SRC="$WORK/$FILE_ID.upload-source.bin"
cp "$STORAGE_DIR/files/$FILE_ID.bin" "$SRC"
run_with_kill upload upload "$SRC"
UPLOAD_ID="$(json_field uploadId <"$WORK/upload.result.json")"
UPLOADED_SHA="$(sha_of "$STORAGE_DIR/completed/$UPLOAD_ID.bin")"
echo "server-assembled file: $UPLOADED_SHA"
[ "$UPLOADED_SHA" = "$EXPECTED" ] || fail "uploaded file hash mismatch"

step "Download $FILE_ID (kill -9 after $KILL_AFTER chunks, then resume)"
OUT="$WORK/$FILE_ID.downloaded.bin"
run_with_kill download download "$FILE_ID" "$OUT"
DOWNLOADED_SHA="$(sha_of "$OUT")"
echo "downloaded file:       $DOWNLOADED_SHA"
[ "$DOWNLOADED_SHA" = "$EXPECTED" ] || fail "downloaded file hash mismatch"

step "Server fault stats"
STATS="$(curl -sf "$SERVER/admin/stats")"
echo "$STATS"
for f in error dropAfterProcess dropMidBody; do
  n="$(echo "$STATS" | json_field "faults.$f")"
  [ "$n" -gt 0 ] || fail "expected some '$f' faults to be injected, got $n"
done

step "Cleanup"
curl -sf -X POST "$SERVER/admin/faults/reset" >/dev/null
curl -sf -X DELETE "$SERVER/api/uploads/$UPLOAD_ID" -o /dev/null -w 'DELETE upload session: %{http_code}\n'

printf '\nCHAOS TEST PASSED: upload and download of %s verified (sha256 %s) under faults with kill -9 + resume\n' "$FILE_ID" "$EXPECTED"
