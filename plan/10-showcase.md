# Phase 10 — Showcase and submission

## 10.1 Demo script

Write `docs/DEMO-SCRIPT.md`: a shot-by-shot script for a recording of about 90 seconds, with the exact on-screen steps, the adb commands, and timings. It must include these beats in order:
1. Cold start: splash, then the Transfers screen (skip onboarding by using an installed build that already passed it, or show page 3 only).
2. Queue three transfers (two uploads and one download) with the limit at 2; point out the third waits in line.
3. Pause one at about 30% and resume it; open its detail and show the chunk map continuing from where it stopped.
4. At about 40% on another, kill the app: `adb shell kill -9 $(adb shell pidof com.maanit.stableshare)` (needs a rooted emulator; give the alternative if not). Reopen: it is still at 40%, shows "Restored after restart", and carries on.
5. Switch the simulator to "Lost responses" and show the activity log recording pieces confirmed after a lost reply, with no duplicates.
6. Toggle airplane mode: "Waiting for network", then back on: it resumes by itself. Then turn Wi-Fi only on and off mobile data to show "Waiting for Wi-Fi".
7. Upload the same file twice: the second is instant and shows the "Already on server" pill.
8. Cancel a transfer, kill and reopen: it stays cancelled.
9. Finish on History with Verified rows.
Include recording instructions for the Mac: `adb shell screenrecord` has a three-minute limit per file (note the flags), or use scrcpy; how to trim and export an MP4 under 25 MB and a GIF under 10 MB (suggest tools already on a Mac, such as QuickTime for trimming, and `ffmpeg` if installed).

DONE WHEN: the script exists and PROGRESS.md notes anything in it that depends on a rooted emulator.

## 10.2u [USER] Record the demo

Your step. Follow `docs/DEMO-SCRIPT.md`. Put the files at `docs/demo/stableshare-demo.mp4` and `docs/demo/stableshare-demo.gif` and tell Claude Code "10.2u done". If you are Claude Code and the next step is 10.2u: tell the user this and stop.

## 10.3 Embed the demo and write the changelog

DO
1. Under the README's "Demo" heading, embed the GIF and link the MP4. Check both files exist and the sizes are within the limits above; if not, tell me.
2. Add a "Download" line near the top linking `release/StableShare-<versionName>.apk` and its SHA-256 from `release/CHECKSUMS.txt`.
3. Write `CHANGELOG.md`: a factual entry for each released version built from `git log` and PROGRESS.md (features added, bugs found by fuzzing, fixes). No marketing language.

DONE WHEN: README embeds work, CHANGELOG committed, PROGRESS.md updated.

## 10.4u [USER] Write the design notes in your own words

Your step, and Claude Code should not write it. Create `docs/MY-DESIGN-NOTES.md` and answer these in your own words, in plain sentences (a few lines each):
1. What happens, step by step, when the reply to a chunk upload is lost?
2. Why write the bytes, fsync, and only then update the database? What breaks if it is reversed?
3. Why is CANCELLED terminal, and what in the code enforces it?
4. Why one coordinator, and what is the APPEND_OR_REPLACE race?
5. After a restart, how does the app know what is already uploaded? What about downloads?
6. What stops retries from looping forever?
7. Why limit concurrency, and what changes with parallel chunks?
8. What does verification prove, and what does it not?
9. What would you change first before using this in production?
When done, tell Claude Code "10.4u done". Optionally ask it to fact-check the notes against the code; it may point out inaccuracies but must not rewrite your words. If you are Claude Code and the next step is 10.4u: tell the user this and stop.

## 10.5u [USER] GitHub release

Your step. After 10.3 and 10.6 pass and CI is green on the default branch:
```
git tag v<versionName>
git push origin v<versionName>
gh release create v<versionName> release/StableShare-<versionName>.apk --title "StableShare <versionName>" --notes-file CHANGELOG.md
```
(`gh` must be installed and logged in; otherwise create the release in the GitHub web UI and attach the APK.) Tell Claude Code "10.5u done". If you are Claude Code and the next step is 10.5u: tell the user this and stop.

## 10.6 Submission audit

Run and report each check, fixing what you can and listing what needs me:
1. Secrets: `git ls-files | grep -iE 'keystore|\.jks|\.env'` prints nothing; scan the history quickly with `git log --all --diff-filter=A --name-only | grep -iE 'keystore|\.jks'` prints nothing.
2. `grep -rnE 'TODO|FIXME|XXX' android/app/src server/src` prints nothing (or list each and decide with me).
3. README headings present exactly (five required) and links resolve.
4. Requirements checklist: write `docs/REQUIREMENTS-CHECKLIST.md` with all 12 assignment requirements and the optional enhancements, each with one line of evidence (a test name, a script, or a screenshot) that you have verified exists.
5. The APK in `release/` installs, matches `release/CHECKSUMS.txt`, and its version matches the README and About screen.
6. CI is green on the default branch (ask me to confirm if you cannot see it).
7. All checklist items in PROGRESS.md are ticked except 10.5u if I have not done it yet.
8. The repository root contains no stray build output or large files (`git count-objects -vH`, and list files over 5 MB).

DONE WHEN: every check reported with a pass or a concrete fix list; PROGRESS.md updated with "Submission ready: yes/no".
