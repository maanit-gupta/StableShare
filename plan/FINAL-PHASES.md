# StableShare — Final phases (compact)

This file replaces phase files 6d, 7, 8, 9 and 10. Goal: ship a release APK, a clean README, a GitHub release with the APK attached, and one real-device test. Work through phases A–F in order. Steps marked [ASK] need my answer; steps marked [USER] are mine, so stop and tell me.

GLOBAL RULES
- Do not build new features. The user-initiated transfer job (old 6.4) is dropped.
- Run tests only where listed. No fuzz suite, no resilience report, no emulator matrix.
- After every phase, update PROGRESS.md (tick the step, one line of notes). Keep it short.
- Never create, print or commit a keystore, `.env` or any secret.

---

## A. Scope reset (docs only, ~10 min)

1. PROGRESS.md: mark these as "Deferred (time)" with one line each: 6.4 UIDT job, 7.1 resilience report, 8.2 full screenshot set, 10.4 design notes. (The demo video is kept, see phase D2.) Add a line "Scope reset: shipping path = A–F in FINAL-PHASES.md".
2. Root CLAUDE.md, Decisions: add "UIDT host deferred; WorkManager coordinator is the only host."
3. docs/DESIGN.md section 9: if it mentions a UIDT host, a `TransferRunLoop` or a lease as built, change it to "planned, not implemented". Make sure no other doc (UI-SPEC, CLAUDE.md files, benchmarks.md) claims the UIDT job or the Task Manager behaviour exists. List every file you changed.

DONE WHEN: no doc claims a feature that is not in the code.

## B. Release build [ASK]

1. [ASK] Keep versionName 1.0.0 or bump to 1.1.0 (versionCode 2)? Wait for my answer, record it in PROGRESS.md.
2. Confirm `android/keystore.properties` exists. If not, STOP and tell me.
3. Apply the version in `android/app/build.gradle.kts` and wherever the About screen reads it.
4. Run the Android unit suite once (`./gradlew testDebugUnitTest`). If it fails, fix and rerun; do not skip failures.
5. `./gradlew assembleRelease`. Keep minify settings as they are.
6. Copy to `release/StableShare-<versionName>.apk`. Write `release/CHECKSUMS.txt` (file name, size in bytes, `shasum -a 256`). Do not delete older APKs unless I say so.

DONE WHEN: APK and CHECKSUMS.txt exist; PROGRESS.md has version, path and size.

## C. Real-device test [USER + Claude]

The phone must reach the server, so `10.0.2.2` will not work.
1. Claude: print my computer's LAN IP (`ipconfig getifaddr en0`) and confirm the network security config allows cleartext to that host. If it only allows `10.0.2.2`/localhost, tell me the minimal change and wait. If the server address is a build-time constant, tell me before rebuilding.
2. Claude: start the server (`npm run dev` in `server/`, bound to `0.0.0.0`).
3. [USER] Connect the phone (USB, or `adb pair` + `adb connect` for wireless debugging), same Wi-Fi as the computer. Tell Claude "device connected".
4. Claude: `adb install -r release/StableShare-<versionName>.apk`, set the server address in the app if it has a setting, then I run this checklist and you record results:
   - Fresh install shows splash and onboarding.
   - One upload (~100–200 MB) and one download complete with Verified in History.
   - Pause and resume one transfer.
   - Kill mid-transfer (`adb shell am kill com.maanit.stableshare` after backgrounding, or swipe away), reopen: "Restored after restart" and it completes.
   - Second upload of the same file is instant.
5. Report pass/fail per line in PROGRESS.md. Fix only blockers; list anything cosmetic under Known limitations.

## D. Screenshots (minimal)

Capture from the release build on the device with `adb exec-out screencap -p > docs/screenshots/<name>.png`, each under ~400 KB (`sips -Z 1080`). Only these four, replacing older files of the same name:
`05-transfers-active.png`, `07-detail-transferring.png`, `11-history.png`, `12-settings.png`.
Keep any older screenshots that still match the UI; delete none.

## D2. Demo video (Claude records it)

Goal: a ~90-second recording from the connected device. Claude drives it with adb; I only step in where a step says [USER].

1. Write `scripts/record-demo.sh` (and a short `docs/DEMO-SCRIPT.md` listing the beats and timings). The script:
   - Starts `adb shell screenrecord --bit-rate 6000000 --time-limit 170 /sdcard/demo.mp4 &` (the 3-minute limit per file is why the time limit is set).
   - Drives the UI with `adb shell input tap/swipe/text`. Find tap coordinates from `adb shell uiautomator dump` + `adb pull`, matching by visible text or content description, never hard-coded guesses. Add `sleep` between beats so each is readable on video.
   - Stops recording (`adb shell pkill -INT screenrecord`), waits 2 s, then `adb pull /sdcard/demo.mp4`.
2. Beats, in order (skip a beat and note it in PROGRESS.md if it cannot be automated reliably):
   1. Cold start: splash, then Transfers (onboarding already done).
   2. Queue two uploads and one download with the limit at 2; the third waits.
   3. Pause one at about 30%, resume, open its detail: the chunk map continues.
   4. Kill mid-transfer (`adb shell am kill` after backgrounding, or `kill -9` if `adb root` works), reopen: "Restored after restart", it carries on.
   5. Network simulator to "Lost responses": activity log shows pieces confirmed after a lost reply.
   6. Network off and on (`adb shell cmd connectivity airplane-mode enable/disable`; if the device refuses, use `adb shell svc wifi disable/enable`): "Waiting for network", then resumes by itself.
   7. Upload the same file again: instant, "Already on server" pill.
   8. Cancel a transfer, kill and reopen: it stays cancelled.
   9. End on History with Verified rows.
3. Do a dry run first without recording. If taps land wrong or timing is off, fix and rerun. If a beat needs a human (for example a system file picker that cannot be scripted), mark it [USER] in the script, print a clear prompt in the terminal, and wait for Enter while recording continues.
4. Use small files (about 20–50 MB) for the demo so transfers move visibly within the time; seed them on the server and device beforehand.
5. Post-process with `ffmpeg` (if missing, `brew install ffmpeg` only after asking me):
   - MP4 under 25 MB: `ffmpeg -i demo.mp4 -vf "scale=720:-2" -c:v libx264 -crf 28 -preset slow -an docs/demo/stableshare-demo.mp4`
   - GIF under 10 MB, sped up: `ffmpeg -i docs/demo/stableshare-demo.mp4 -vf "setpts=0.5*PTS,fps=10,scale=360:-1:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse" docs/demo/stableshare-demo.gif`
   - Check both sizes; lower the scale or fps and redo if over the limits.
6. Show me the final durations and sizes and tell me to watch the MP4 before moving on. [USER] I reply "demo ok" or list retakes.

DONE WHEN: both files exist within size limits, I approved the video, PROGRESS.md notes any skipped beat.

## E. README (one pass)

Write `README.md`, about 300 lines, from docs/DESIGN.md and the code. Every claim must point to real code or a test; leave out anything you cannot point to. Relative links to files. Mermaid for diagrams. Do not re-read the whole README after writing; use `grep -n '^#' README.md` for the outline.

Sections, in order (the five bold ones must use these exact headings):
1. Title, one-paragraph summary, 3–4 screenshots in a row, CI badge (`.github/workflows/ci.yml`), Download line linking the APK with its SHA-256 from CHECKSUMS.txt. Then a "Demo" heading embedding `docs/demo/stableshare-demo.gif` and linking the MP4.
2. Requirements covered: one table of the 12 requirements (requirement, where implemented, how tested). Short list of extras that actually exist (no UIDT).
3. Quick start: server (`npm install`, `npm run seed`, `npm run dev`), install the APK, emulator `10.0.2.2:8080` vs phone LAN IP, the Network simulator.
4. **Architecture**: one Mermaid diagram, one line per layer, why each technology.
5. **Transfer protocol**: endpoint table (method, path, purpose, status codes) checked against the server routes; one sequence diagram for upload incl. the lost-response case.
6. **Persistence strategy**: the three tables, `transition()`, write order (bytes, fsync, database) and why, restart reconciliation.
7. **Retry and recovery logic**: error classes, backoff numbers, attempt limits, network/Wi-Fi waiting, why nothing loops forever, corrupted-partial recovery.
8. Data integrity and lifecycle: short paragraphs (SHA-256, atomic rename, ETag/If-Range; process death, reboot, force-stop limitation).
9. **Important edge cases handled**: table (scenario, behaviour, where handled, test). Verify each test name with grep; drop rows without a real test.
10. Testing: commands for server tests, Android unit tests, fuzz tests with replay; link `docs/benchmarks.md` with its headline numbers.
11. Known limitations and future work: cleartext HTTP, no auth, hash-only instant upload, single-process mock server, UIDT job not implemented, anything found in phase C.
12. Project structure: short tree.

Checks before finishing: the five headings exist exactly; every relative link and image path resolves; Mermaid blocks reviewed by eye. Report the line count.

## F. Push and release

1. Claude, secrets check: `git ls-files | grep -iE 'keystore|\.jks|\.env'` and `git log --all --diff-filter=A --name-only | grep -iE 'keystore|\.jks'` both print nothing. List any files over 5 MB that are not the APK. If something fails, STOP and tell me.
2. Claude: commit everything (README, docs, CHECKSUMS.txt, screenshots, PROGRESS.md) and `git push`. Ask me to confirm CI is green if you cannot see it.
3. Claude: write a short factual `CHANGELOG.md` entry for this version from `git log` and PROGRESS.md. Commit and push.
4. [USER] Release:
   ```
   git tag v<versionName>
   git push origin v<versionName>
   gh release create v<versionName> release/StableShare-<versionName>.apk --title "StableShare <versionName>" --notes-file CHANGELOG.md
   ```
   (No `gh`? Create the release on GitHub and attach the APK.) Tell Claude "release done".
5. Claude: update the README Download line to the release URL if useful, check the APK size and SHA-256 on the release match CHECKSUMS.txt, and write "Submission ready: yes/no" in PROGRESS.md with any open items.

DONE WHEN: release published with the APK, README live on the default branch, PROGRESS.md says Submission ready.
