# Demo video script

Recorded on the API 37 emulator (`emulator-5554`) running the signed release 1.1.0, against the mock server at `http://10.0.2.2:8080`. `scripts/record-demo.sh` drives every beat with adb; nobody touches the screen. Output: a real-time `demo.mp4` (5 min 39 s), not kept in the repository.

## Setup (off camera)

- Server throttled with `PUT /admin/faults {"bandwidthKbps": 4096}` (0.5 MB/s per request) so progress moves visibly. Beat 5's preset sets unlimited bandwidth, so once the app's "Simulator updated" snackbar shows, the script puts the throttle back.
- A fresh 30 MB `demo-clip.bin` (random bytes, so the first upload is never instant) in `Download/StableShareDemo/`, a folder the system picker already remembers. No other files appear on camera.
- Do Not Disturb on while recording; transfer limit 2; Transfers and History empty (`scripts/demo-reset.sh --clear-history`).
- Recorded from the Mac with `scrcpy --record`. The same script also runs on a phone, but on a OnePlus (ColorOS) `adb shell screenrecord` can't write its output file.
- Taps come from `scripts/demo_ui.py`, which matches visible text or content descriptions in a `uiautomator dump`. No coordinates are hard-coded. A dump takes about 2.5 s, which is most of the dead time in the video.

## Beats

| # | Beat | What the viewer sees | Starts at |
|---|---|---|---|
| 1 | Cold start | Splash, then an empty Transfers screen | 0:00 |
| 2 | Queue 3 transfers, limit 2 | demo-clip.bin (picked) and a 200 MB test file upload; bench-20MB downloads; header "2 active, 1 waiting" | 0:17 |
| 3 | Pause / resume | 200 MB upload's detail: paused at ~5 % ("Progress saved: N of 100 pieces"), and the waiting download takes the free slot. Resumed, it waits in line, then the piece map fills again | 1:36 |
| 4 | Kill mid-transfer | Home, then a real `kill -9` (adb root), reopen: "Restored after restart", and the upload carries on | 2:21 |
| 5 | Lost responses | Settings → Network simulator → "Lost responses" → Apply. The upload's activity log shows "Piece N confirmed by the server after a lost reply" | 2:51 |
| 6 | Network off / on | Airplane mode: offline banner and "Waiting for network"; back online, the upload resumes by itself | 3:58 |
| 7 | Same file again | demo-clip.bin picked again: "Uploaded" at once with the "Already on server" pill | 4:18 |
| 8 | Cancel survives a restart | The 200 MB upload is cancelled, the app is killed (`kill -9`) and reopened, and it does not come back | 5:05 |
| 9 | History | Cancelled filter, then All: the cancelled upload plus three Verified rows (one "Already on server") | 5:22 |

Deviations from the plan's beats:
- Pause at ~5 %, not ~30 %. The long upload is 200 MB so it is still running in beats 5–6, and pausing early avoids the moment demo-clip finishes and the list shifts.
- Beat 6 waits for "Waiting for network" or the offline banner. In one rehearsal the row read "Waiting, #1 in line" (QUEUED) while offline instead of "Waiting for network". The recorded take shows "Waiting for network".
- Length: 5 min 39 s, not ~90 s, because of the uiautomator dumps and the readable hold times. The GIF plays at 2×.

## Re-running

```sh
ANDROID_SERIAL=emulator-5554 scripts/demo-reset.sh --clear-history  # cancels leftovers, empties History
ANDROID_SERIAL=emulator-5554 scripts/record-demo.sh --dry-run       # no recording
ANDROID_SERIAL=emulator-5554 scripts/record-demo.sh                 # writes ./demo.mp4
```
