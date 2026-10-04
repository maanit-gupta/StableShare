# Phase 6, feature 1 — Wi-Fi only

Steps: 6.1a (engine and tests), 6.1b (UI, copy and docs).

GOAL: a Settings switch "Wi-Fi only". When on, StableShare never moves data over a metered network. Transfers that lose Wi-Fi wait, then resume by themselves when an unmetered network returns.

## 6.1a Engine and tests

READ: `ConnectivityMonitor`, `ErrorClassifier`, `TransferScheduler`, `TransferCoordinatorWorker` (and its wake-up logic), `SettingsRepository`. Do not read the UI yet.

BEHAVIOUR
1. Setting: DataStore key `wifiOnly`, default false, exposed as a Flow with a suspend setter.
2. `ConnectivityMonitor` exposes `NetworkState {Offline, Metered, Unmetered}`: Offline = no network with NET_CAPABILITY_INTERNET; Unmetered = has NET_CAPABILITY_NOT_METERED; anything else is Metered. Add a derived `usableNetwork: StateFlow<Boolean>`: Offline gives false, Metered gives `!wifiOnly`, Unmetered gives true. Replace every "is the network available" check in the engine (coordinator promotion, ErrorClassifier's ConnectivityChecker, wake-up scheduling) with `usableNetwork`.
3. New ErrorCode `METERED_NETWORK`. When a request fails and the network is unusable, ErrorClassifier returns WaitForNetwork carrying NETWORK_UNAVAILABLE if Offline, or METERED_NETWORK if Metered with wifiOnly on. Check how ErrorCode is persisted. If it is not stored by name, add a Room migration with an exported schema and a migration test.
4. Proactive stop: every running job collects `usableNetwork`. When it has stayed false for 1500 ms (a debounce, so brief Wi-Fi roaming blips don't interrupt), cancel the in-flight call immediately so no more bytes use mobile data, transition TRANSFERRING to RETRYING with the matching code, consume no attempts, and end the job to free the slot. Turning the switch on while transfers run over mobile data triggers the same path.
5. Coordinator: claims no QUEUED rows while `usableNetwork` is false. Wake-up work uses NetworkType.UNMETERED when wifiOnly is on, CONNECTED otherwise. Changing the switch calls `ensureRunning()` and re-schedules the wake-up. When `usableNetwork` becomes true, promote RETRYING rows coded NETWORK_UNAVAILABLE or METERED_NETWORK to QUEUED, then `ensureRunning()`.
6. CLAUDE.md rules still hold: nothing here may revive a CANCELLED row, and PAUSED rows are never auto-resumed.

TESTS (fake ConnectivityMonitor, virtual time)
(a) wifiOnly off with Metered: the transfer runs. (b) wifiOnly on with Metered at start: nothing is claimed and rows stay QUEUED. (c) Unmetered to Metered mid-chunk: the in-flight call is cancelled, state is RETRYING with METERED_NETWORK, no attempts consumed, DONE chunks intact. (d) Back to Unmetered: promoted, resumes, completes with a verified hash. (e) A 1000 ms blip does not interrupt. (f) Switching wifiOnly on mid-transfer stops it. (g) Metered to Offline changes the code to NETWORK_UNAVAILABLE. (h) CANCELLED and PAUSED rows are never promoted. (i) ErrorClassifier mapping for every case.
Run the Phase 5 fuzz tests as part of the full suite at the end.

DONE WHEN: new tests and the full unit suite are green; DESIGN.md sections 7 and 9 updated; PROGRESS.md updated.

## 6.1b UI, copy and docs

READ: UI-SPEC.md sections 5.4, 5.10, 6, 7 and 8 by heading, and the existing Settings, Transfers and detail composables that those sections describe.

EXACT COPY — add to docs/UI-SPEC.md in the same commit, and to strings.xml
- Settings, Transfers card: a switch "Wi-Fi only" below "Retry automatically", with the helper "Transfers wait for Wi-Fi and won't use mobile data."
- Transfers banner (section 5.4): a new banner between "offline" and "server unreachable" in priority. Shown when wifiOnly is on and NetworkState is Metered. Icon `wifi_off`, text "Wi-Fi only is on. Transfers will continue on Wi-Fi."
- Section 6, RETRYING with METERED_NETWORK: list label "Waiting for Wi-Fi" (inkSecondary), bar fill `neutral.muted`, detail title "Waiting for Wi-Fi", stats line "Wi-Fi only is on. This continues when you connect to Wi-Fi."; mascot, ring and plane exactly as RETRYING with NETWORK_UNAVAILABLE.
- Section 6, QUEUED rows while wifiOnly is on and NetworkState is not Unmetered: the label "Waiting for Wi-Fi" replaces "Waiting, #n in line".
- Section 7: METERED_NETWORK short reason "Waiting for Wi-Fi"; long reason "Wi-Fi only is on, so this continues when you connect to Wi-Fi. You can change this in Settings."
- Section 8: STATE_CHANGE to RETRYING with METERED_NETWORK shows "Waiting for Wi-Fi" (not "Hit a problem: ...").

TESTS: Compose tests for the new labels, the banner and the persisted switch; unit tests for the state-to-presentation mapping rows added.

MANUAL CHECK: on the emulator, flip between Wi-Fi and mobile data with `adb shell svc wifi disable` and `adb shell svc wifi enable` during a 200 MB upload. Confirm from `/admin/stats` that no requests arrive while on mobile data. If the emulator cannot simulate this, tell me and I will use a physical phone.

DOCS: README is written later (Phase 9); here update only DESIGN.md and UI-SPEC.md and the root CLAUDE.md Decisions.
DONE WHEN: tests green, manual check reported, docs updated, PROGRESS.md updated.
