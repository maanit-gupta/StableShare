# Phase 8 — Final build

## 8.1 Release build and smoke test [ASK]

STEP 0 (ASK): ask me whether to keep versionName 1.0.0 or bump to 1.1.0 (versionCode 2), since features were added after the first release build. Wait for my answer. Record it in PROGRESS.md.

DO
1. Confirm `android/keystore.properties` exists. If it does not, STOP and tell me; never create a keystore and never print its contents.
2. Apply the version I chose in `android/app/build.gradle.kts` (and anywhere the UI-SPEC or the About screen reads it).
3. Run the full unit suite and the fuzz suite once, then `./gradlew assembleRelease` in `android/`. Keep minify settings as they are unless the earlier decision in DESIGN.md or CLAUDE.md says otherwise.
4. Copy the APK to `release/StableShare-<versionName>.apk`. Remove older APKs from `release/` only if I say so. Write `release/CHECKSUMS.txt` with the file name, size in bytes, and SHA-256 (`shasum -a 256`).
5. `adb install -r` it on an emulator or device and smoke-test the RELEASE build: a fresh install shows the splash and onboarding; one 200 MB upload through the hoop screen and one 200 MB download under the Flaky Wi-Fi preset; one `kill -9` of the process mid-transfer, confirming the "Restored after restart" pill; both end COMPLETED with the solid ring and Verified in History. Also check: Wi-Fi only toggles and waits; the second upload of the same file is instant; "Pieces at once" is settable; on an API 34+ device a transfer started in the app shows in the Task Manager.
6. Report what passed and anything that did not.

DONE WHEN: APK built, checksummed, smoke test reported, PROGRESS.md updated with the version, APK path and size.

## 8.2 Screenshots

DO: capture with `adb exec-out screencap -p > docs/screenshots/<name>.png` from the installed release build, replacing the Phase 4 set where the UI changed. Keep each file under about 400 KB (reduce with `sips -Z 1080` or `pngquant` if available; do not install heavy tools).

SET (use these exact file names)
`01-splash.png`, `02-onboarding-1.png`, `03-onboarding-2.png`, `04-onboarding-3.png`, `05-transfers-active.png` (active and waiting rows), `06-upload-hoop.png`, `07-detail-transferring.png` (chunk map with several in-flight cells if parallel is set to 2 or 4), `08-detail-paused.png`, `09-detail-failed.png`, `10-detail-completed.png`, `11-history.png` (with a Verified row and an "Already on server" pill), `12-settings.png` (Wi-Fi only and Pieces at once visible), `13-simulator.png`, `14-notification.png`, `15-waiting-wifi.png` (a row or detail showing "Waiting for Wi-Fi").

If a state is hard to capture (for example Failed), use the Network simulator or the Cancel and Retry flow to reach it, and tell me if you cannot.

DONE WHEN: all files exist, PROGRESS.md lists any missing ones.
