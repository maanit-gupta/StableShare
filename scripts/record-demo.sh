#!/usr/bin/env bash
# Records the StableShare demo on a connected device (the shipped demo: the API 37 emulator). Beats and timings: docs/DEMO-SCRIPT.md.
#
# Usage: ANDROID_SERIAL=<device> scripts/record-demo.sh [--dry-run]
#   --dry-run  drives every beat without recording.
#
# Before running:
#   - release build installed, onboarding done, server URL set in Settings, limit = 2,
#     nothing in Transfers
#   - mock server on SERVER (default http://localhost:8080), reachable from the phone,
#     with bench-20MB in its file list
#   - /sdcard/Download/StableShareDemo/demo-clip.bin picked once in the app, so the system
#     picker reopens in that folder (keeps the phone's other files off camera). The script
#     overwrites it with fresh random bytes on every run, so the first upload is never instant.
#   - scrcpy installed on the Mac (brew install scrcpy)
# Output: demo.mp4 in the current directory.
set -euo pipefail

: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the device serial}"
export ANDROID_SERIAL
SERVER=${SERVER:-http://localhost:8080}
PKG=com.maanit.stableshare
UI="$(cd "$(dirname "$0")" && pwd)/demo_ui.py"
CLIP_PATH=/sdcard/Download/StableShareDemo/demo-clip.bin
KBPS=${KBPS:-4096} # server throttle per request: 0.5 MB/s, so progress moves visibly
DRY=0; [[ ${1:-} == --dry-run ]] && DRY=1

log() { printf '[%s] %s\n' "$(date +%T)" "$*"; }
fail() { echo "FAILED: $*" >&2; exit 1; }

# Coordinates always come from a uiautomator dump. A dump takes ~2.5 s, so a screen's
# targets are read in one go and reused while that screen is static.
# locate VAR PATTERN[@index] [VAR PATTERN ...]: waits until every pattern is on screen.
locate() {
  local vars=() pats=() out line i
  while (($#)); do vars+=("$1"); pats+=("$2"); shift 2; done
  for _ in {1..20}; do
    out=$("$UI" many "${pats[@]}")
    if ! grep -qx -- '-' <<<"$out"; then
      i=0
      while IFS= read -r line; do printf -v "${vars[i]}" '%s' "$line"; i=$((i + 1)); done <<<"$out"
      return
    fi
    sleep 0.3
  done
  fail "locate: ${pats[*]} -> $(tr '\n' '|' <<<"$out")"
}
at() { adb shell input tap $1; } # tap an "x y" pair
tap() { local p; locate p "$1"; at "$p"; }
# Waits until a node matching $1 shows up (timeout $2 seconds).
wait_for() {
  local end=$((SECONDS + ${2:-30}))
  until "$UI" find "$1" >/dev/null; do
    ((SECONDS < end)) || fail "wait_for '$1'"
    sleep 0.3
  done
}
# Waits until no node matches $1 (timeout $2 seconds).
wait_gone() {
  local end=$((SECONDS + ${2:-30}))
  while "$UI" find "$1" >/dev/null; do
    ((SECONDS < end)) || fail "wait_gone '$1'"
    sleep 0.3
  done
}
# Opens the detail of the row named $1, checking the title: a row that finishes between the
# dump and the tap shifts the list, and the tap would open the neighbour instead.
open_detail() {
  for _ in {1..4}; do
    tap "$1"
    sleep 1.5
    "$UI" find "$1, .*" >/dev/null && return # detail subtitle: "<name>, <size>"
    adb shell input keyevent BACK
    sleep 1
  done
  fail "open_detail '$1'"
}
# Swipes up until a node matching $1 is on screen.
scroll_to() {
  for _ in {1..12}; do
    "$UI" find "$1" >/dev/null && return
    adb shell input swipe 540 1700 540 1000 300
    sleep 0.4
  done
  fail "scroll_to '$1'"
}
# Opens the Upload screen through the FAB, or the empty state's own button once the list
# has emptied (a transfer can finish between two dumps, so each try reads the screen again).
open_upload_screen() {
  local xy
  for _ in {1..6}; do
    "$UI" find 'Choose a file' >/dev/null && return
    if xy=$("$UI" find 'Upload a file'); then at "$xy"
    elif xy=$("$UI" find 'New transfer'); then at "$xy"
    elif xy=$("$UI" find 'Upload'); then at "$xy"
    fi
    sleep 1
  done
  fail "open_upload_screen"
}
faults() { curl -fsS -X PUT -H 'Content-Type: application/json' -d "$1" "$SERVER/admin/faults" >/dev/null; }
reset_faults() { curl -fsS -X POST "$SERVER/admin/faults/reset" >/dev/null; faults "{\"bandwidthKbps\":$KBPS}"; }
# Process death: kill -9 when adb runs as root (emulator), else `am crash` (a release build on a
# phone has no run-as, and `am kill` is ignored while the foreground service runs).
kill_app() {
  if [[ $(adb shell id -u) == 0 ]]; then
    adb shell 'pid=$(pidof '$PKG') && kill -9 $pid' || true
  else
    adb shell am crash $PKG 2>/dev/null || true
  fi
}
launch() { adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; }
hold() { [[ $DRY == 1 ]] || sleep "$1"; } # reading time, only needed on video

# Recorded from the Mac with scrcpy: works on emulators and phones alike (on a OnePlus with
# ColorOS, `adb shell screenrecord` cannot write its output file).
REC=
rec_start() {
  [[ $DRY == 1 ]] && return
  rm -f demo.mp4
  scrcpy -s "$ANDROID_SERIAL" --no-window --no-audio --video-bit-rate=6M --record=demo.mp4 \
    >scrcpy.log 2>&1 &
  REC=$!
  sleep 2.5
}
rec_stop() {
  [[ $DRY == 1 ]] && return
  kill -TERM "$REC" 2>/dev/null || true # background jobs ignore SIGINT; scrcpy finalises on TERM
  wait "$REC" || true
  REC=
}

cleanup() {
  [[ -n $REC ]] && { kill -TERM "$REC" 2>/dev/null; wait "$REC"; } || true
  adb shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || true
  adb shell cmd notification set_dnd off >/dev/null 2>&1 || true
  curl -fsS -X POST "$SERVER/admin/faults/reset" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# ---- setup (off camera) ------------------------------------------------------
log "setup"
reset_faults
clip=$(mktemp)
head -c 31457280 /dev/urandom >"$clip"
adb push "$clip" $CLIP_PATH >/dev/null
rm -f "$clip"
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$CLIP_PATH >/dev/null
for _ in {1..20}; do # the picker lists the file only once MediaStore has it again
  adb shell content query --uri content://media/external/file --projection _size \
    --where "_display_name=\\'demo-clip.bin\\'" | grep -q _size=31457280 && break
  sleep 0.5
done
adb shell cmd notification set_dnd priority # keeps unrelated notifications off the video
adb shell cmd connectivity airplane-mode disable
adb shell am force-stop $PKG # a fresh start opens on Transfers
launch
locate NAV_T 'Transfers@-1' NAV_H 'History@-1' NAV_S 'Settings@-1'
at "$NAV_T"
sleep 1
"$UI" find 'Nothing in the air' >/dev/null || fail "Transfers is not empty"
adb shell am force-stop $PKG
adb shell input keyevent HOME
sleep 1

rec_start
# ---- 1. cold start -----------------------------------------------------------
log "1 cold start"
launch
locate EMPTY_UP 'Upload'
hold 2

# ---- 2. two uploads (30 MB picked, 200 MB test file) + one download, limit 2 ---
log "2 queue three transfers"
at "$EMPTY_UP"
locate CHOOSE 'Choose a file' BACK 'Back'
at "$CHOOSE"
locate CLIP 'demo-clip.bin'          # system picker, already in StableShareDemo/
at "$CLIP"
wait_for 'Tap Upload to take the shot'
tap 'Upload@-1'
sleep 1.5
tap '200 MB'                         # the screen scrolls once a file is queued: look again
sleep 1
wait_gone 'Generating test file.*' 60
tap 'Upload@-1'
sleep 1.5
at "$BACK"
locate FAB 'New transfer'
at "$FAB"
locate NEW_UP 'Upload a file' NEW_DOWN 'Download from server'
at "$NEW_DOWN"
wait_for 'bench-20MB.bin'
at "$("$UI" near 'bench-20MB.bin' 'Download')"
sleep 1
adb shell input keyevent BACK         # close the sheet
wait_for '2 active, 1 waiting.*' 10
hold 3

# ---- 3. pause at ~30 %, resume, detail ---------------------------------------
log "3 pause / resume"
open_detail 'test-200MB-.*'          # its detail: buttons there don't move when rows finish
wait_for '([5-9]|[1-9][0-9])%' 90
tap 'Pause'
hold 3                               # the waiting download takes the free slot
tap 'Resume'
wait_for 'Uploading\.\.\.' 90        # demo-clip finishes, this upload continues
hold 6

# ---- 4. kill mid-transfer, reopen: restored ----------------------------------
log "4 kill"
adb shell input keyevent BACK         # to the list, which the app reopens on
sleep 1.5
adb shell input keyevent HOME
sleep 1.5
kill_app
sleep 2
launch
wait_for 'Restored after restart' 30
hold 4

# ---- 5. simulator: lost responses ---------------------------------------------
log "5 lost responses"
at "$NAV_S"
scroll_to 'Lost responses'
tap 'Lost responses'
scroll_to 'Apply'
tap 'Apply'
wait_for 'Simulator updated' 10       # the app's PUT has landed; only now restore the throttle
faults "{\"bandwidthKbps\":$KBPS}"    # the preset means unlimited; keep the throttle
sleep 1.5
at "$NAV_T"
open_detail 'test-200MB-.*'
scroll_to 'Piece [0-9]+ confirmed by the server after a lost reply'
hold 5
reset_faults
adb shell input keyevent BACK
sleep 1

# ---- 6. network off and on ----------------------------------------------------
log "6 network off / on"
adb shell cmd connectivity airplane-mode enable
wait_for "Waiting for network|You're offline.*" 15  # row label, or the banner when the stop wins the race (PROGRESS.md)
hold 4
adb shell cmd connectivity airplane-mode disable
wait_for '(Uploading|Downloading)….*|Nothing moving right now' 60
hold 3

# ---- 7. same file again: instant -------------------------------------------
log "7 instant upload"
open_upload_screen
tap 'Choose a file'
locate CLIP 'demo-clip.bin'
at "$CLIP"
wait_for 'Tap Upload to take the shot'
tap 'Upload@-1'
wait_for 'Already on server' 20      # verified at once: no pieces sent
hold 3
at "$BACK"
hold 2

# ---- 8. cancel, kill, reopen: stays cancelled ---------------------------------
log "8 cancel survives a restart"
tap 'Cancel test-200MB-.*'
tap 'Cancel transfer'
hold 2
adb shell input keyevent HOME
sleep 1
kill_app
sleep 2
launch
hold 3

# ---- 9. history ----------------------------------------------------------------
log "9 history"
tap 'History@-1'                  # waits until the app is past its splash
locate F_ALL 'All' F_CANCELLED 'Cancelled@0'
hold 2
at "$F_CANCELLED"
hold 3
at "$F_ALL"
hold 4
rec_stop

log "done"
