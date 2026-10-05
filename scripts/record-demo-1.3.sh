#!/usr/bin/env bash
# Records the StableShare 1.3.0 demo (first launch, server choice, Try a demo, expand a row,
# detail with pause and resume, History filters and menu, switch to the local server) and
# saves README screenshots on the way. Device: the API 37 emulator, release build installed.
# Usage: ANDROID_SERIAL=<device> scripts/record-demo-1.3.sh   (needs scrcpy; local server on
# http://localhost:8080 for the last beat). Clears the app data first. Output: ./demo-1.3/
set -euo pipefail
: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the device serial}"
export ANDROID_SERIAL
PKG=com.maanit.stableshare
UI="$(cd "$(dirname "$0")" && pwd)/demo_ui.py"
OUT=${OUT:-$PWD/demo-1.3}
mkdir -p "$OUT"
log() { printf '[%s] %s\n' "$(date +%T)" "$*"; }
fail() { echo "FAILED: $*" >&2; exit 1; }
find_xy() { python3 "$UI" find "$@"; }
tap() { local xy; for _ in {1..20}; do xy=$(find_xy "$@") && { adb shell input tap $xy; return; }; sleep 0.3; done; fail "tap $*"; }
wait_for() { local end=$((SECONDS + ${2:-30})); until find_xy "$1" >/dev/null; do ((SECONDS < end)) || fail "wait_for $1"; sleep 0.3; done; }
shot() { adb exec-out screencap -p >"$OUT/$1.png"; log "shot $1"; }
hold() { sleep "$1"; }

REC=
cleanup() {
  [[ -n $REC ]] && { kill -TERM "$REC" 2>/dev/null; wait "$REC"; } || true
  adb shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null 2>&1 || true
}
trap cleanup EXIT

log "setup"
adb shell pm clear $PKG >/dev/null
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1030 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command network -e mobile show -e datatype none -e level 4 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command status -e location hide >/dev/null
adb shell input keyevent HOME
sleep 1

rm -f "$OUT/demo.mp4"
scrcpy -s "$ANDROID_SERIAL" --no-window --no-audio --video-bit-rate=6M --record="$OUT/demo.mp4" >"$OUT/scrcpy.log" 2>&1 &
REC=$!
sleep 2.5

log "1 first launch"
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
wait_for 'Send big files, calmly'
hold 2; tap 'Next'; hold 2; tap 'Next'; hold 2; tap 'Get started'
tap 'Allow'; sleep 1.5; tap 'Allow'

log "2 server choice"
wait_for 'Where should files go\?'
wait_for 'Online' 60
hold 1; shot 01-server-choice; hold 2
tap 'Continue'

log "3 try a demo"
wait_for 'Nothing in the air'
hold 2
tap 'Try a demo'
wait_for 'Big upload'
hold 2; shot 02-try-a-demo; hold 1
tap 'Big upload'
wait_for 'demo-200MB-.*' 60
hold 3
tap 'Got it'
hold 1

log "4 add a download"
tap 'New transfer'
tap 'Download from server'
wait_for 'sample-50MB.bin'
hold 1
adb shell input tap $(python3 "$UI" near 'sample-50MB.bin' 'Download')
sleep 1
adb shell input keyevent BACK
hold 2

log "5 expand the row, then detail with pause and resume"
tap 'demo-200MB-.*'
wait_for 'View details'
hold 2; shot 05-transfers-active; hold 2
tap 'View details'
wait_for 'Pause'
hold 2; shot 07-detail-transferring; hold 1
tap 'Pause'
hold 3
tap 'Resume'
hold 4
adb shell input keyevent BACK
log "6 wait for both to finish"
wait_for 'Nothing in the air' 300
hold 2

log "7 history"
tap 'History' -1
wait_for 'Completed 2'
hold 2; shot 11-history; hold 1
tap 'Completed 2'; hold 2
tap 'All 2'; hold 1
tap 'More options'
wait_for 'Copy hash'
hold 2
tap 'Copy hash'
hold 3

log "8 settings: switch to the local server"
tap 'Settings' -1
wait_for 'Local server'
hold 2
tap 'Local server'
wait_for 'Emulator'
tap 'Emulator'
hold 1
tap 'Test connection'
wait_for 'Connected in .*' 20
hold 2; shot 12-settings; hold 2
tap 'Transfers' -1
wait_for 'Local'
hold 3

kill -TERM "$REC" 2>/dev/null || true
wait "$REC" || true
REC=
log "done"
