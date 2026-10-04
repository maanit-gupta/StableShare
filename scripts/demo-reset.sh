#!/usr/bin/env bash
# Cancels every transfer left on the Transfers screen (between demo dry runs).
# --clear-history also empties History, so the demo's last beat shows only its own rows.
set -euo pipefail
: "${ANDROID_SERIAL:?set ANDROID_SERIAL}"
export ANDROID_SERIAL
UI="$(cd "$(dirname "$0")" && pwd)/demo_ui.py"
adb shell am force-stop com.maanit.stableshare
adb shell monkey -p com.maanit.stableshare -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 3
while xy=$("$UI" find 'Cancel .*'); do
  adb shell input tap $xy
  sleep 1
  adb shell input tap $("$UI" find 'Cancel transfer')
  sleep 1.5
done
"$UI" find 'Nothing in the air' >/dev/null && echo "Transfers is empty"
if [[ ${1:-} == --clear-history ]]; then
  adb shell input tap $("$UI" find 'History' -1)
  sleep 1.5
  if xy=$("$UI" find 'Clear all'); then
    adb shell input tap $xy
    sleep 1
    adb shell input tap $("$UI" find 'Clear')
    sleep 1
  fi
  adb shell input tap $("$UI" find 'Transfers' -1)
  echo "History cleared"
fi
