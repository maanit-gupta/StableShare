# StableShare — UI Specification (Phase 4)

This document is the single source of truth for every pixel, word and motion in the StableShare app. It sits beside `docs/DESIGN.md` (engine behaviour) and only covers presentation.

## 0. Rules for implementing this spec

1. Implement exactly what is written. Do not add colours, fonts, screens, copy or animations that are not listed here.
2. If something you need is not specified, **stop and ask** instead of inventing it. Log the question under "Open issues" in the root `CLAUDE.md`.
3. All copy lives in `res/values/strings.xml`, using the exact wording in this document.
4. The UI never changes transfer state directly. Every action calls the repository or scheduler, and the set of available actions always comes from `StateMachine.allowedActions(state)`.
5. Light theme only. No dynamic colour. Lock both styles to the tokens below.
6. Every animation listed has a reduced-motion variant (section 10). Honour it.

## 1. Two visual styles and where each is used

StableShare has two deliberately different styles that share one mascot.

| Style | Name in code | Look | Screens |
|---|---|---|---|
| A | `Mint` | Mint background, rounded Comfortaa type, line-art mascot, centred layout | Splash, Onboarding, Transfer detail |
| B | `Neutral` | Light grey page, white cards, Inter type, orange accent, left-aligned layout | Transfers, Upload (hoop), Download sheet, History, Settings, all dialogs and sheets |

Implement them as two `CompositionLocal` token sets (`MintTokens`, `NeutralTokens`) wrapped by a single `StableShareTheme`. A screen picks one style; styles never mix inside a screen, except that the mascot artwork may appear on Neutral screens as an illustration (empty and error states).

## 2. Assets

### 2.1 Mascot files

The mascot is **Nimbus**, a cloud, with a paper-plane sidekick called **Dart**. These names are internal only and never appear in the UI. All art is original.

Copy every file in `docs/design/mascot/drawable/` into `android/app/src/main/res/drawable/` unchanged. The SVG sources in `docs/design/mascot/svg/` and the renders in `docs/design/mascot/previews/` are references only; do not ship them.

**Cloud layers.** Every cloud layer is drawn on the same 240 × 200 viewport, so they stack perfectly when drawn at the same size in a `Box`. Draw them in this order (bottom to top):

1. `mascot_arm_left`
2. One right arm: `mascot_arm_right_wave` (raised) or `mascot_arm_right_rest` (lowered)
3. `mascot_cloud_body`
4. One face: `mascot_face_idle`, `_focused`, `_happy`, `_worried`, `_sleepy`, `_searching`, `_sad` or `_calm`
5. Optional `mascot_rain` (failed state only)

Arm rotation pivots, as fractions of the 240 × 200 viewport:

- Right arm (shoulder): (0.85, 0.61)
- Left arm (shoulder): (0.19, 0.64)

**Plane.** `mascot_plane` is a separate 64 × 48 drawable. Its nose points 18.7° above the horizontal (up and to the right). To make the nose follow a heading of `h` degrees (measured clockwise from the +x axis, screen coordinates), set `rotationZ = h + 18.7`.

**Icons.**

- `ic_launcher_foreground` (108 × 108, art inside the 66 dp safe zone) and `ic_launcher_monochrome`: adaptive icon with background colour `#7BBCA5`. Replace the template launcher icons. Provide `mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml` with foreground, background and monochrome layers.
- `ic_stat_plane`: white silhouette for notification small icons.

All other icons come from Material Symbols Outlined (`material-icons-extended`), named in each section.

### 2.2 Fonts

Bundle both families in `res/font` (no downloadable fonts) and include their OFL licence texts in `app/src/main/assets/licenses/`. Download from the `google/fonts` GitHub repository:

- **Comfortaa** (`ofl/comfortaa`), weights Light 300, Regular 400, Bold 700. Mint style only.
- **Inter** (`ofl/inter`), weights Regular 400, Medium 500, SemiBold 600, Bold 700. Neutral style only.

If a weight is only available as a variable font, use the variable file with `FontVariation` settings for each weight.

## 3. Tokens

### 3.1 Mint colours (style A)

| Token | Hex | Use |
|---|---|---|
| `mint.bg` | `#7BBCA5` | Full-screen background |
| `mint.surface` | `#DDFBE2` | Section cards, secondary buttons, plane underside |
| `mint.inkPrimary` | `#102A24` | Titles, percentage, body text |
| `mint.inkSecondary` | `#24403A` | Helper text, stats line, labels |
| `mint.stroke` | `#12201C` | Ring, button borders, card outlines |
| `mint.danger` | `#D92B52` | Cancel button fill, failed ring |
| `mint.onDanger` | `#FFFFFF` | Cancel button label |
| `mint.successSurface` | `#E8FAEC` | Done and Get started button fill |
| `mint.accentYellow` | `#F7E65E` | In-flight chunk cells |
| `mint.accentGreen` | `#6BBF5E` | (Used inside the mascot art only) |

### 3.2 Neutral colours (style B)

| Token | Hex | Use |
|---|---|---|
| `neutral.page` | `#EFF1F5` | Screen background |
| `neutral.card` | `#FFFFFF` | Cards, rows, sheets, bottom bar |
| `neutral.border` | `#CBCED4` | Drop zone dashed border at rest, dividers |
| `neutral.inkPrimary` | `#0F1115` | Titles, numbers, primary button fill |
| `neutral.inkStrong` | `#2A2D33` | File names |
| `neutral.inkSecondary` | `#4B5563` | Subtitles, labels, icons |
| `neutral.inkTertiary` | `#6F7682` | Sizes, meta text, status text at rest |
| `neutral.pill` | `#E3E7ED` | Counter pill, chips, skeletons |
| `neutral.track` | `#E5E7EB` | Progress track |
| `neutral.accent` | `#FF6B2C` | Hoop, progress fill, FAB, "+1", trail dots |
| `neutral.accentTint` | `#FFF3EC` | Drop zone hover fill, icon circles, nav indicator |
| `neutral.accentDash` | `#EE6F3F` | Drop zone dashed border while aiming |
| `neutral.net` | `#A7AEB8` | Net strokes |
| `neutral.success` | `#21A06A` | Completed label, full bar, check badge |
| `neutral.warning` | `#B45309` | Retrying label |
| `neutral.warningFill` | `#F59E0B` | Retrying bar fill |
| `neutral.danger` | `#E5484D` | Failed label and bar, PDF tag, destructive text |
| `neutral.muted` | `#C9CED6` | Queued and waiting-for-network bar fill |
| `neutral.paused` | `#9CA3AF` | Paused bar fill |

### 3.3 Typography

**Mint (Comfortaa, centred unless stated).**

| Style | Size / line height | Weight | Colour |
|---|---|---|---|
| `mint.display` | 30 / 38 sp | Regular | inkPrimary |
| `mint.percent` | 38 / 44 sp | Light | inkPrimary |
| `mint.title` | 18 / 24 sp | Bold | inkPrimary |
| `mint.body` | 14 / 20 sp | Regular | inkSecondary |
| `mint.label` | 13 / 18 sp | Bold | per button |
| `mint.caption` | 12 / 16 sp | Regular | inkSecondary |

**Neutral (Inter, left-aligned unless stated).**

| Style | Size / line height | Weight | Colour |
|---|---|---|---|
| `neutral.title` | 28 / 34 sp | Bold | inkPrimary |
| `neutral.subtitle` | 15 / 22 sp | Regular | inkSecondary |
| `neutral.heading` | 18 / 24 sp | SemiBold | inkPrimary |
| `neutral.rowTitle` | 16 / 22 sp | SemiBold | inkStrong |
| `neutral.body` | 14 / 20 sp | Regular | inkSecondary |
| `neutral.meta` | 13 / 18 sp | Regular | inkTertiary |
| `neutral.status` | 13 / 18 sp | Medium | per state |
| `neutral.button` | 16 / 22 sp | SemiBold | per button |
| `neutral.small` | 12 / 16 sp | Medium | inkSecondary |
| `neutral.hash` | 12 / 16 sp | Regular, monospace (`FontFamily.Monospace`) | inkTertiary |

Use sentence case everywhere. No all-caps text except file extensions inside file tags.

### 3.4 Spacing, shape, elevation

- Spacing scale (dp): 4, 8, 12, 16, 20, 24, 32, 40.
- Screen horizontal padding: 24 dp on Neutral screens, 20 dp on Mint section cards.
- Neutral radii: cards and rows 16 dp, drop zone 16 dp, sheets 24 dp (top corners), chips and pills fully rounded, primary button 14 dp, file chip 6 dp.
- Mint radii: section cards 10 dp, buttons 4 dp.
- Neutral row shadow: y 2 dp, blur 12 dp, 6% black. Drop zone card: no shadow. File chip: y 4, blur 12, 10% black. FAB: elevation 6 dp.
- Mint has no shadows. Outlines are 1.5 dp `mint.stroke` on cards and 1 dp on buttons.
- Minimum touch target 48 × 48 dp everywhere, even when the visual is smaller.

### 3.5 Motion

| Name | Duration | Easing |
|---|---|---|
| `quick` | 150 ms | FastOutSlowIn |
| `standard` | 250 ms | FastOutSlowIn |
| `emphasis` | 400 ms | spring(dampingRatio 0.7, stiffness 300) |
| `long` | 600 ms | FastOutSlowIn |

## 4. Mascot system

### 4.1 Moods by state

The detail screen, splash and onboarding use the full mascot. "Plane" describes where Dart sits relative to the progress ring on the detail screen (section 5.8.3).

| State (and phase) | Face | Right arm | Extra | Plane | Idle motion |
|---|---|---|---|---|---|
| QUEUED | focused | rest | none | Perched on the cloud | Cloud breathes (scale 1 → 1.02 → 1, 3 s loop) |
| TRANSFERRING, phase Preparing | focused | rest | none | Hovers above the cloud, bobbing ±4 dp (1.2 s loop) | Breathe |
| TRANSFERRING | focused | rest | data drops (5.8.2) | On the ring at the progress angle | Breathe |
| VERIFYING | focused | rest | none | Laps the ring once every 1.6 s | Breathe |
| RETRYING (retryable error) | worried | rest | none | On the ring at the progress angle; wobbles ±8° for 1 s every 3 s | Breathe |
| RETRYING (NETWORK_UNAVAILABLE) | searching | rest | none | Perched on the cloud | Face layer slides ±4 dp left/right (2 s loop) |
| PAUSED | sleepy | rest | none | Parked on the ring at the progress angle, still | Breathe slowly (5 s loop) |
| FAILED | sad | rest | `mascot_rain` | At the bottom of the ring (angle 180°), nose tilted 35° down | Rain drops fall 6 dp and fade, staggered 200 ms, 1.4 s loop |
| COMPLETED | happy | wave | none | One fast lap (600 ms), then settles at 12 o'clock | Right arm waves ±12° three times on arrival, then rests raised |
| CANCELLED | calm | rest | none | Fades out over 200 ms and stays hidden | None |
| Splash, onboarding page 1 | idle | wave | none | Per screen spec | Arm waves ±12°, 1.2 s loop |

Face changes cross-fade over `quick`. Arm swaps cross-fade over `quick`.

### 4.2 Accessibility descriptions

The mascot is one accessibility node with a description built as "StableShare cloud, " + mood text:

| Mood | Text |
|---|---|
| idle | "waving hello" |
| focused | "concentrating" |
| worried | "worried, trying again" |
| searching | "looking for a connection" |
| sleepy | "asleep, transfer paused" |
| sad | "sad, transfer failed" |
| happy | "smiling, transfer complete" |
| calm | "resting, transfer cancelled" |

Decorative mascot instances on Neutral screens (empty states) use `contentDescription = null`.

## 5. Screens

### 5.1 Splash (Mint)

1. **System splash:** use `androidx.core:core-splashscreen`. Background `#7BBCA5`, icon `ic_launcher_foreground`. Dismiss it as soon as the first Compose frame is ready.
2. **Animated splash (Compose)**, shown only on a cold start from the launcher. Skip it when the app opens from a notification or a deep link. Total length is at most 1600 ms; a tap anywhere skips to the end.

Sequence, on `mint.bg`, everything centred:

| Time | Element | Motion |
|---|---|---|
| 0–500 ms | Cloud (idle face, arm waving), width 48% of screen, max 200 dp | Scale 0.8 → 1 with `emphasis`, alpha 0 → 1 |
| 300–1000 ms | Plane, 40 × 30 dp | Enters from off-screen left along a curve, loops once around the cloud clockwise, lands perched on top of the cloud (nose following the path) |
| 600–900 ms | Wordmark "StableShare", `mint.display` | Fades in and rises 8 dp |
| 750–1050 ms | Tagline "Big files, landed safely.", `mint.body` | Fades in |
| 1600 ms | — | Navigate (fade, `standard`) |

Layout: cloud vertically centred at 42% of screen height; wordmark 24 dp below the cloud; tagline 8 dp below the wordmark.

Then navigate to Onboarding if `onboardingCompleted` (DataStore, default false) is false, otherwise to Transfers.

### 5.2 Onboarding (Mint, first run only)

A `HorizontalPager` with three pages sharing one layout (based on Design A screen 1). Swipe or use the buttons.

```
┌──────────────────────────────┐
│                       Skip   │  ← text button, mint.body, pages 1–2 only
│                              │
│        ( mascot art )        │  ← upper 45% of the screen, art width 50%
│                              │
│         Title line           │  ← mint.display, 32 dp below art
│     Helper text, two lines   │  ← mint.body, 12 dp below, max width 280 dp
│                              │
│          ●  ○  ○             │  ← page dots, 32 dp below helper
│                              │
│         [  Next  ]           │  ← button, 32 dp above bottom inset
└──────────────────────────────┘
```

| Page | Mascot | Title | Helper |
|---|---|---|---|
| 1 | Idle face, waving; plane loops around the cloud every 2.4 s | "Send big files, calmly" | "StableShare moves large files in small pieces, so a dropped connection never costs you the whole file." |
| 2 | Sleepy face; plane parked on top of the cloud; a 24 dp `mint.surface` circle with a pause glyph (`pause`, 14 dp, mint.stroke) and 1.5 dp outline sits at the cloud's upper right | "Stop anytime, pick up where you left off" | "Pause, close the app or lose signal. Your progress is saved piece by piece." |
| 3 | Happy face, waving; plane perched with a 20 dp `neutral.success` check badge (white `check`, 14 dp) at its tail | "Every file is checked" | "A transfer only shows Completed after the whole file is verified." |

- **Page dots:** inactive 8 dp circles with a 1.5 dp `mint.stroke` outline; the active dot becomes a 20 × 8 dp filled `mint.inkPrimary` pill. Animate width over `standard`.
- **Buttons:** "Next" on pages 1–2 uses the Mint secondary button (section 5.8.5) at 140 × 44 dp. Page 3 shows "Get started" in the Mint success button at 160 × 44 dp.
- **Get started:** on Android 13+, request `POST_NOTIFICATIONS` (the helper text on page 3 is the rationale). Whatever the result, set `onboardingCompleted = true` and go to Transfers, clearing the back stack.
- **Skip:** does the same as Get started.

### 5.3 App shell (Neutral)

- **Bottom navigation** (Material 3 `NavigationBar`): height 64 dp plus the system inset, `neutral.card` background, 1 dp `neutral.track` top border. Three items:
  - "Transfers", icon `swap_vert`
  - "History", icon `history`
  - "Settings", icon `settings`
- Selected item: icon `neutral.accent`, label `neutral.inkPrimary`, indicator pill `neutral.accentTint`. Unselected: icon and label `neutral.inkTertiary`. Labels use `neutral.small`.
- Edge-to-edge with system bars drawn over `neutral.page`; status bar icons dark.
- The Detail and Upload screens are full-screen routes without the bottom bar.

### 5.4 Transfers (Neutral)

```
┌──────────────────────────────┐
│ Transfers          [Limit 2] │  ← title + pill
│ 2 active, 3 waiting          │  ← subtitle
│ ┌──────────────────────────┐ │
│ │ ☁̸ You're offline. ...     │ │  ← banner (only when needed)
│ └──────────────────────────┘ │
│ Active                     2 │  ← section header
│ ┌──────────────────────────┐ │
│ │ [PDF] report_q3.pdf   ‖ ✕│ │
│ │       84 of 200 MB       │ │
│ │       4.1 MB/s, 28 s left│ │
│ │ Uploading…          42%  │ │
│ │ ▬▬▬▬▬▬▬▬────────────     │ │
│ └──────────────────────────┘ │
│ Waiting                    3 │
│  ...                         │
│                         (+)  │  ← FAB
└──────────────────────────────┘
```

**Header.**
- Title "Transfers" (`neutral.title`), 24 dp from the top inset.
- Subtitle (`neutral.subtitle`), 4 dp below: "{a} active, {w} waiting" where a = TRANSFERRING + VERIFYING + RETRYING and w = QUEUED + PAUSED. When both are 0: "Nothing moving right now". When the aggregate speed is above 0, append ", {speed}" (for example "2 active, 3 waiting, 6.3 MB/s").
- Limit pill, top-right and vertically centred on the title: height 28 dp, `neutral.pill`, horizontal padding 12 dp, text "Limit {n}" (label "Limit" in 12 sp `neutral.inkSecondary`, number in 13 sp SemiBold `neutral.inkPrimary`). Tapping it opens Settings scrolled to Transfers.

**Banners** (16 dp below the header, full width, `neutral.card`, 16 dp radius, 16 dp padding, leading 20 dp icon `neutral.inkSecondary`, text `neutral.body`). At most one shows; priority order:
1. No network (from `ConnectivityMonitor`): icon `cloud_off`, text "You're offline. Transfers will continue when you reconnect."
2. Server unreachable (a `GET /health` runs when the screen resumes and every 30 s while visible; banner shows after a failed check): icon `dns`, text "Can't reach the server at {url}." plus a text button "Settings" (14 sp SemiBold, `neutral.inkPrimary`, underlined) that opens Settings.

**Sections**, in this order, each shown only when non-empty:
1. "Active": TRANSFERRING, VERIFYING, RETRYING
2. "Waiting": QUEUED (in queue order), then PAUSED
3. "Needs attention": FAILED

Section header: label in the `neutral.rowTitle` style coloured `neutral.inkSecondary`, with the count right-aligned in `neutral.meta`. 24 dp above, 8 dp below. Rows are 12 dp apart.

**COMPLETED rows** stay in place for 2.5 s showing their success state, then collapse (height and alpha to 0 over `standard`) and appear in History. CANCELLED rows leave immediately with the same collapse.

#### 5.4.1 Transfer row (shared component)

Card: `neutral.card`, 16 dp radius, row shadow, 16 dp padding, full width minus 24 dp screen padding on each side. Tapping the card opens Detail.

Top section (horizontal):
- **File icon**, 28 × 34 dp: a document glyph drawn in Compose (white page, 1.5 dp `neutral.border` outline, 3 grey lines in `neutral.track`) with an extension tag along its bottom edge: 9 sp Bold white text on a 3 dp-radius rectangle. Tag colour `neutral.danger` for PDF, `neutral.inkSecondary` for everything else. Text is the uppercase extension, max 4 characters; files without one show "FILE". Generated test files show "BIN".
- **Direction badge**: a 16 dp white circle with a 1 dp `neutral.track` border overlapping the icon's bottom-right corner, containing `arrow_upward` (upload) or `arrow_downward` (download) at 12 dp, `neutral.inkSecondary`.
- 12 dp gap, then a text column (weight 1):
  - File name, `neutral.rowTitle`, one line, middle ellipsis (keep the extension visible).
  - Size line, `neutral.meta`: "{done} of {total}" while incomplete (for example "84 of 200 MB"), or "{total}" when complete.
  - Live line, `neutral.meta`, only while TRANSFERRING with speed above 0: "{speed}, {eta} left" (for example "4.1 MB/s, 28 s left").
  - "Restored" pill, only when the transfer is in the in-memory restored set (5.4.2): height 20 dp, `neutral.pill`, 8 dp horizontal padding, text "Restored after restart" in 11 sp Medium `neutral.inkSecondary`, 4 dp below the previous line.
- **Action buttons** on the right: up to two icon buttons, 24 dp icons in `neutral.inkSecondary`, 48 dp touch targets, 4 dp apart, top-aligned. Order: primary action, then Cancel. Icons: Pause `pause`, Resume `play_arrow`, Retry `refresh`, Cancel `close`. Content descriptions: "Pause {name}", "Resume {name}", "Retry {name}", "Cancel {name}".

Bottom section, 12 dp below:
- One line: status label (`neutral.status`, left) and percentage (13 sp SemiBold `neutral.inkPrimary`, right).
- 6 dp below: progress bar, full width, 4 dp tall, fully rounded, `neutral.track` background. Fill width animates over `standard`.
- COMPLETED: a 24 dp `neutral.success` circle with a white `check` (16 dp) replaces the percentage, popping in with `emphasis` scale 0 → 1.

Status label, colour and bar fill per state are defined in section 6.

**Cancel confirmation dialog** (Neutral): title "Cancel this transfer?", body "{name} will stop and its partial data will be deleted. This can't be undone.", buttons "Keep going" (text button, `neutral.inkPrimary`) and "Cancel transfer" (text button, `neutral.danger`).

#### 5.4.2 Restored-after-restart set

When the coordinator's reconciliation moves rows back to QUEUED after a process restart, it adds their ids to an in-memory `StateFlow<Set<String>>` exposed by the engine. An id leaves the set when that transfer reaches a terminal state or when the process dies. Rows and the detail screen read this set. The detail screen's Activity list also shows the matching INFO event (section 8).

#### 5.4.3 Empty state

Centred in the space below the header: the mascot (idle face, waving, plane perched) at 140 dp wide, decorative. Then "Nothing in the air" (`neutral.heading`, centred, 16 dp below), "Upload a file from your phone or grab one from the server." (`neutral.body`, centred, 8 dp below, max width 280 dp), then two buttons side by side 24 dp below, 12 dp apart: "Upload" (Neutral primary button, 52 dp tall, auto width with 24 dp padding) and "Download" (Neutral outlined button: 1.5 dp `neutral.inkPrimary` border, transparent fill, `neutral.inkPrimary` label, same size).

#### 5.4.4 FAB and chooser sheet

FAB: 56 dp, 16 dp corner radius, `neutral.accent` fill, `add` icon 24 dp in `neutral.inkPrimary`, 16 dp from the end edge and 16 dp above the bottom bar. Content description "New transfer". Hidden when the empty state is showing (the empty state has its own buttons).

Tapping it opens a `ModalBottomSheet` (`neutral.card`, 24 dp top radius, drag handle) titled "New transfer" (`neutral.heading`, 24 dp padding) with two option rows, each 72 dp tall:
- Leading 40 dp circle in `neutral.accentTint` with a 22 dp icon in `neutral.accent`: `upload` for the first, `download` for the second.
- Title `neutral.rowTitle`, subtitle `neutral.meta`:
  - "Upload a file" / "Send a file from this phone, or a test file, to the server" → opens the Upload screen.
  - "Download from server" / "Save one of the server's files to this phone" → opens the Download sheet.

### 5.5 Primary buttons (Neutral)

- **Primary:** height 52 dp, 14 dp radius, fill `neutral.inkPrimary`, label `neutral.button` in white. Optional leading 20 dp icon in `neutral.accent`, 8 dp before the label. Disabled: fill `neutral.pill`, label `neutral.inkTertiary`, icon hidden.
- **Outlined:** as above, but transparent fill, 1.5 dp `neutral.inkPrimary` border, `neutral.inkPrimary` label.
- **Text:** 14 sp SemiBold, colour per context, 48 dp min height.

### 5.6 Upload screen — the hoop (Neutral)

Full-screen route. Top app bar: back arrow (`arrow_back`, 24 dp, `neutral.inkPrimary`) at the start, no title in the bar.

```
┌──────────────────────────────┐
│ ←                            │
│ Upload files     [In queue 2]│  ← B.1 title + B.3 pill
│ Pick a file, then take the   │  ← B.2 subtitle
│ shot.                        │
│ ┌ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┐  │
│        ⤒ (upload icon)       │  ← B.4 drop zone card
│ │      Choose a file       │ │
│       Tap to browse          │
│ │       ┌────────┐ +1      │ │  ← backboard + "+1"
│         └────────┘           │
│ └ ─ ─ ═══════════════ ─ ─ ┘  │  ← rim overhangs the card edge
│          \\/\\/\\/            │  ← net hangs below the card
│           \\/\\/              │
│ Or use a test file           │
│ (50 MB)(200 MB)(500 MB)(1 GB)│
│ ┌──────────────────────────┐ │
│ │ row added this visit     │ │  ← scrollable list area
│ └──────────────────────────┘ │
│ ┌──┐                         │
│ │▤ │ final_report.pdf        │  ← file chip on the launch pad
│ └──┘ 2.4 MB                  │
│ [   ⤒  Upload              ] │  ← primary button
└──────────────────────────────┘
```

**Header** (24 dp padding):
- B.1 title "Upload files" (`neutral.title`).
- B.2 subtitle "Pick a file, then take the shot." (`neutral.subtitle`), 4 dp below.
- B.3 counter pill, top-right aligned with the title: "In queue {n}", where n counts uploads in QUEUED, TRANSFERRING, RETRYING, VERIFYING or PAUSED. The displayed number is held until the "+1" moment of the throw (below), then updates, so the counter changes exactly when the file lands.

**Drop zone card (B.4)**, 20 dp below the header: width = screen width − 48 dp (max 360 dp), height 224 dp, 16 dp radius, `neutral.card` fill, 1.5 dp dashed border (dash 6, gap 4 dp) in `neutral.border`.
- Upload icon (B.5): `upload`, 24 dp, `neutral.inkSecondary`, centred, 32 dp from the card top.
- Title (B.6), `neutral.heading`, centred, 12 dp below the icon. Subtext (B.7), `neutral.meta`, centred, 4 dp below.
  - No file selected: "Choose a file" / "Tap to browse your phone". Tapping the card opens the system picker (`ActivityResultContracts.OpenDocument`, any MIME type), then takes a persistable URI permission.
  - File selected: "Ready when you are" / "Tap Upload to take the shot". Tapping the card again replaces the selection.
- **Backboard (B.8):** 114 × 68 dp, white fill, 2.5 dp `neutral.accent` stroke, 6 dp radius, horizontally centred, its bottom 18 dp above the card's bottom edge. A 40 × 26 dp inner square (2 dp `neutral.accent` stroke) sits centred on its lower half.
- **Rim (B.9):** an ellipse 140 × 10 dp with a 4 dp `neutral.accent` stroke, centred horizontally, its centre line on the backboard's bottom edge; it extends past the card's bottom edge.
- **Net (B.10):** a trapezoid 120 dp wide at the top (the rim) narrowing to 64 dp at the bottom, 90 dp tall, drawn as crisscross lines (1.5 dp, `neutral.net`), 6 diagonals each way, hanging from the rim over the page background. Draw order: card, flying chip, then net and rim on top, so the chip disappears behind the net.
- Hover state (while aiming): fill `neutral.accentTint`, dashed border `neutral.accentDash`. Cross-fade over `quick`.

**Test files**, 16 dp below the net: label "Or use a test file" (`neutral.small`), then 8 dp below a row of four chips, 8 dp apart: "50 MB", "200 MB", "500 MB", "1 GB". Chips are 32 dp tall, fully rounded, `neutral.pill`, label 13 sp Medium `neutral.inkPrimary`. Tapping one starts generating that file (via `FileStore.generateTestFile`) and puts its chip on the launch pad in the generating state.

**Rows added this visit**, 20 dp below the chips: a scrolling list of Transfer rows (section 5.4.1) for uploads created on this screen during this visit, newest first. When there are none, this area is empty.

**Launch pad**, anchored to the bottom above the button: the file chip at the start with its caption to the right.
- **File chip (B.12):** 44 × 56 dp, white, 6 dp radius, chip shadow; three placeholder lines (2 dp tall, `neutral.track`, widths 24, 28 and 18 dp, starting 10 dp from the top, 6 dp apart); the extension tag from 5.4.1 along the bottom edge.
- Caption, 12 dp right of the chip, vertically centred: file name (`neutral.rowTitle` at 14 sp, one line, middle ellipsis) and below it the size (`neutral.meta`). While generating: "Generating test file, {p}%" instead of the size.
- No file selected: the launch pad shows a 44 × 56 dp dashed outline (1.5 dp `neutral.border`, dash 4 gap 4) with the caption "No file yet" in `neutral.meta`.
- **Upload button:** Neutral primary, full width minus 48 dp, 16 dp above the bottom inset, label "Upload" with leading `upload` icon. Disabled until a file is selected and fully generated, and while a throw is running.

**The throw** (tap Upload). Create the transfer and enqueue it *first* (repository + `ensureRunning()`), then play the animation. If creation throws, return the chip to the pad with `emphasis` and show the snackbar "Couldn't add {name}. {reason}" (reason from section 7, short form).

| Time | Motion |
|---|---|
| 0–80 ms | Chip lifts: scale 1 → 1.06, shadow blur 12 → 20 dp |
| 80–700 ms | Chip flies along a quadratic Bézier from its pad centre (P0) to the rim centre (P2), control point P1 = (midpoint x, rim y − 120 dp). Rotation 0 → −18°, scale 1 → 0.7. Every 24 dp of path, drop a 4 dp `neutral.accent` trail dot (B.13) that fades out over 500 ms |
| 500–700 ms | Drop zone switches to the hover state |
| 700–950 ms | Chip falls 40 dp straight down through the rim, scale 0.7 → 0.4, alpha 1 → 0 (behind the net). Rim pulses scaleX 1 → 1.04 → 1 over 200 ms |
| 760 ms | "+1" (B.11): 20 sp Inter Bold `neutral.accent`, at the backboard's top-right corner; alpha 0 → 1 over 120 ms while rising 16 dp, holds 300 ms, fades over 300 ms. Counter pill updates now. Haptic: `HapticFeedbackConstants.CONFIRM` on API 30+, else `KEYBOARD_TAP` |
| 950–1100 ms | Drop zone returns to rest. The new row slides in at the top of the list (rise 16 dp, fade in, `standard`). The launch pad resets to "No file yet" |

### 5.7 Download sheet (Neutral)

A `ModalBottomSheet` (24 dp top radius, drag handle, up to 90% of screen height) titled "Download from server" (`neutral.heading`), with the subtitle "Files on {host}" (`neutral.meta`).

- **Loading:** three skeleton rows (64 dp tall, `neutral.pill` rounded rectangles for icon, name and size) pulsing alpha 0.5 ↔ 1 over 900 ms.
- **List:** rows 64 dp tall with the file icon (5.4.1), name (`neutral.rowTitle`), size (`neutral.meta`), and a trailing text button "Download" (14 sp SemiBold, `neutral.inkPrimary`). After a tap the button becomes a disabled "Added" with a leading 16 dp `check` in `neutral.success`, and the transfer is created and enqueued. A file that already has a non-terminal download shows "In progress" (disabled) instead.
- **Error:** the mascot (sad face, rain, no plane) at 96 dp wide, decorative; "Can't reach the server" (`neutral.heading`); "{url}" (`neutral.meta`); buttons "Try again" (Neutral primary) and "Open settings" (Neutral outlined), stacked 12 dp apart.
- **Empty:** "The server has no files yet. Run npm run seed on your computer." (`neutral.body`, centred).

### 5.8 Transfer detail (Mint)

Full-screen route, `mint.bg` background, vertically scrolling column. The first part (the hero) is at least the viewport height; information cards follow below it.

```
┌──────────────────────────────┐
│ ←                         ⋮  │
│          Uploading...        │  ← title (animated ellipsis)
│      report_q3.pdf, 200 MB   │  ← file line
│                              │
│         ( cloud art )        │  ← mascot
│           •  •  •            │  ← data drops
│          ╭ ─ ─ ─ ╮  ✈        │  ← dashed ring, plane at progress angle
│         │   42%   │          │
│          ╰ ─ ─ ─ ╯           │
│     4.1 MB/s, about 28 s left│  ← stats line
│                              │
│      [ Pause ]  [ Cancel ]   │  ← buttons
├──────────────────────────────┤
│  Pieces            (card)    │
│  Activity          (card)    │
│  Details           (card)    │
└──────────────────────────────┘
```

#### 5.8.1 Hero

- **Top bar** (56 dp, transparent): back (`arrow_back`, 24 dp, `mint.stroke`) at the start. At the end, an overflow (`more_vert`) shown only when it has items: "Open file" and "Share file" for COMPLETED downloads; "Remove from history" for COMPLETED and CANCELLED transfers (removes the row, then navigates back).
- **Title**, `mint.display`, centred, 8 dp below the top bar. Text per state is in section 6. Titles ending in an ellipsis animate it: show ".", "..", "..." in a 400 ms cycle, with the full "..." width always reserved so the title never shifts.
- **File line**, `mint.body`, 4 dp below: "{name}, {total size}", one line, middle ellipsis.
- **Mascot**, 20 dp below: width 54% of screen width (max 220 dp), height = width × 200 / 240.
- **Data drops**, 8 dp below the mascot: three 6 dp circles, 10 dp apart, `mint.inkPrimary` at 30% alpha. During TRANSFERRING they light up in turn (alpha 0.3 → 1 → 0.3, 300 ms each, staggered 150 ms). Static in every other state. Decorative (no semantics).
- **Ring**, 16 dp below: diameter 38% of screen width, clamped 120–160 dp. Details in 5.8.3.
- **Stats line**, `mint.body`, centred, 16 dp below the ring, max two lines. Text per state in section 6.
- **Buttons**, 24 dp below the stats line, centred, 12 dp apart (5.8.5). The hero ends 32 dp below the buttons.

#### 5.8.2 Percentage

Centred inside the ring: the percentage in `mint.percent`, and below it "of {total size}" in `mint.caption`.

The percentage is `floor(100 × (bytesDone + inFlightBytes) / fileSize)`, **capped at 99 until the state is COMPLETED**, when it shows 100. Zero-byte files show 0 until COMPLETED. CANCELLED shows the last value at 60% alpha. The same rule applies to the Transfers row percentage.

#### 5.8.3 Ring and plane

The ring is a 1.5 dp stroke, drawn with `Canvas`. The default dash pattern is 4 dp on, 4 dp off.

| State | Stroke | Colour | Motion |
|---|---|---|---|
| QUEUED | Dashed | `mint.inkSecondary` at 60% | Static |
| TRANSFERRING (incl. Preparing) | Dashed | `mint.stroke` | Dashes rotate one turn per 8 s |
| VERIFYING | Dashed | `mint.stroke` | One turn per 2 s |
| RETRYING (retryable) | Dashed | `mint.stroke` | Static |
| RETRYING (network) | Dashed | `mint.inkSecondary` at 60% | Static |
| PAUSED | Dashed | `mint.stroke` | Static |
| FAILED | Dashed | `mint.danger` | Static |
| COMPLETED | Solid | `mint.stroke` | On entering COMPLETED, the gap animates 4 → 0 dp over 400 ms, so the ring visibly closes |
| CANCELLED | Dotted (1 dp on, 6 dp off, round caps) | `mint.inkSecondary` at 60% | Static |

**Plane on the ring** (40 × 30 dp, centred on the stroke). Positions use angle θ in degrees, 0 at 12 o'clock and increasing clockwise: `x = cx + R·sin θ`, `y = cy − R·cos θ`. With the plane flying clockwise, its heading is θ degrees from the +x axis, so `rotationZ = θ + 18.7`.

- Progress angle θ = 360 × displayed percentage / 100. Changes animate with `spring(dampingRatio 0.8, stiffness 120)`. A ±2 dp bob runs perpendicular to the ring (1.2 s loop) while TRANSFERRING.
- "Perched on the cloud": centred on the cloud's top bump, 12 dp above the cloud's top edge, `rotationZ = 0`.
- Plane behaviour per state is listed in section 4.1. Moving between positions uses `emphasis`.

#### 5.8.4 Stats and titles

Defined per state in section 6.

#### 5.8.5 Mint buttons

Visual size 104 × 36 dp (48 dp touch target), 4 dp radius, 1 dp `mint.stroke` border, label `mint.label`.

| Button | Fill | Label colour | Text |
|---|---|---|---|
| Secondary (Pause, Resume, Retry, Next, Open) | `mint.surface` | `mint.inkPrimary` | "Pause", "Resume", "Retry", "Next", "Open" |
| Danger (Cancel) | `mint.danger` | `mint.onDanger` | "Cancel" |
| Success (Done, Get started) | `mint.successSurface` | `mint.inkPrimary` | "Done", "Get started" |

The buttons shown come from `StateMachine.allowedActions(state)`, primary action first, then Cancel. COMPLETED shows "Done" (navigates back), plus "Open" first for downloads. CANCELLED shows "Done". Cancel opens the Neutral cancel dialog (5.4.1); dialogs always use the Neutral style.

#### 5.8.6 Information cards

Below the hero, in a column with 16 dp gaps and 20 dp side margins. Each card: `mint.surface` fill, 1.5 dp `mint.stroke` outline, 10 dp radius, 16 dp padding, heading in `mint.title`, left-aligned content.

**Pieces.** Heading "Pieces". A chunk map drawn on a single `Canvas`:
- Cells are squares with 2 dp gaps, filling the card width. Cell size = the largest value from 10 dp down to a 4 dp minimum that fits all chunks in at most 12 rows; if even 4 dp needs more rows, the map simply grows taller.
- Cell colours: DONE `mint.inkPrimary`; PENDING transparent with a 1 dp `mint.bg` outline; FAILED `mint.danger`; in-flight (the chunk currently moving, from the progress tracker) `mint.accentYellow` with a 1 dp `mint.stroke` outline, pulsing alpha 0.6 ↔ 1 over 600 ms.
- Summary under the map (`mint.body`): "{done} of {total} pieces done" plus ", {n} failed" when n > 0. Zero-byte files: "This file has no pieces to send."
- Legend row (`mint.caption`), 8 dp below: 10 dp swatches with labels "Done", "Waiting", "Moving", "Failed".
- Accessibility: the map is one node described by the summary text.

**Activity.** Heading "Activity". Newest first, first 20 entries, then a text button "Show all ({n})" that expands the list. Each entry: an 8 dp dot on a 1.5 dp vertical `mint.stroke` line at the start; message (`mint.body` in `mint.inkPrimary`); time (`mint.caption`) as HH:mm:ss, or "MMM d, HH:mm" for entries from another day. Dot colour: `mint.danger` for ERROR and CHUNK_FAILED, `mint.accentYellow` for RETRY_SCHEDULED, `mint.stroke` otherwise. Messages come from section 8.

**Details.** Heading "Details", collapsed by default (chevron `expand_more` rotates 180° over `standard`). Rows of label (`mint.caption`) and value (`mint.body`, `mint.inkPrimary`), 8 dp apart:
- "Direction": "Upload to server" / "Download from server"
- "Size": exact bytes with grouping (for example "209,715,200 bytes")
- "Pieces": "{count} × {chunk size}"
- "Transfer ID": the id in monospace 12 sp, with a copy icon button (`content_copy`) that copies it and shows a toast "Copied"
- "Server ID": upload session id or remote file id (monospace, copy button)
- "Saved to": local path (downloads), or "Picked file" / "Generated test file" (uploads)
- "Expected SHA-256" and "Verified SHA-256": monospace, wrapped; "Not yet" when absent
- "ETag" (downloads only)
- "Attempts": transfer attemptCount
- "Started", "Finished" (when terminal), "Duration", "Average speed"

### 5.9 History (Neutral)

- Title "History" (`neutral.title`). Top-right text button "Clear all" (`neutral.danger`), shown when the list is not empty; it opens the dialog "Clear history?" / "This removes finished and cancelled transfers from the list. Downloaded files stay on your phone." with buttons "Keep" and "Clear".
- Filter chips 16 dp below the title: "All", "Completed", "Cancelled" (32 dp tall; selected chip filled `neutral.inkPrimary` with white label; unselected `neutral.pill` with `neutral.inkPrimary` label).
- Rows: the shared row layout without action buttons or progress bar. Lines: name; "{size}, took {duration}, averaged {speed}" (`neutral.meta`); finish time (`neutral.meta`, "MMM d, HH:mm"). Status line: "Verified" in `neutral.success` with the 24 dp check badge, plus the first 8 and last 4 hex characters of the SHA-256 in `neutral.hash` ("a3f9c2b1…c21e"); or "Cancelled" in `neutral.inkTertiary` with a 24 dp `neutral.pill` circle containing `close` in `neutral.inkSecondary`.
- An overflow icon button (`more_vert`) per row with "Open file" and "Share file" (completed downloads only, through a configured `FileProvider`) and "Remove from history".
- Newest first; tapping a row opens Detail.
- Empty state: mascot (calm face, no plane) at 120 dp, decorative; "No finished transfers yet" (`neutral.heading`); "Completed and cancelled transfers will show up here." (`neutral.body`).

### 5.10 Settings (Neutral)

Title "Settings" (`neutral.title`). Sections are white cards (16 dp radius, row shadow, 16 dp padding) with a section title above each (`neutral.rowTitle` in `neutral.inkSecondary`, 24 dp above, 8 dp below).

**Server**
- Text field "Server address" (outlined, 12 dp radius, `neutral.border` outline, focused outline `neutral.inkPrimary`), value = serverUrl. Saved on focus loss or the IME Done action; invalid URLs show the supporting text "Enter a full address, like http://10.0.2.2:8080" in `neutral.danger`.
- Helper text (`neutral.meta`): "Use http://10.0.2.2:8080 on the emulator. On a phone, use your computer's address on the same Wi-Fi."
- Button "Test connection" (Neutral outlined, full width). Result line below it: while checking, a 16 dp progress spinner and "Checking…"; success: `check_circle` 18 dp `neutral.success` + "Connected"; failure: `error` 18 dp `neutral.danger` + "Couldn't connect: {short reason}".

**Transfers**
- "Transfers at the same time": a segmented control with 1, 2, 3, 4 (44 dp tall, 12 dp radius, selected segment `neutral.inkPrimary` with white text). Applies immediately.
- "Piece size for new uploads": segmented 1 MB, 2 MB, 5 MB, with the helper "Applies to uploads you add from now on." (`neutral.meta`).
- "Retry automatically": a switch (checked track `neutral.inkPrimary`), helper "When off, a failed piece stops the transfer until you tap Retry."

**Network simulator**
- A notice row at the top of the card: `science` icon 20 dp `neutral.inkSecondary` and "Testing tool. These settings change how the server behaves for every device using it." (`neutral.meta`).
- Preset chips (wrapping row): "Off", "Slow network", "Flaky Wi-Fi", "Lost responses", "Corruption", "Chaos". Selecting one fills the sliders. Values:

| Preset | latencyMs | jitterMs | bandwidthKbps | errorRate | timeoutRate | dropMidBodyRate | dropAfterProcessRate | corruptRate |
|---|---|---|---|---|---|---|---|---|
| Off (enabled = false) | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| Slow network | 800 | 400 | 512 | 0 | 0 | 0 | 0 | 0 |
| Flaky Wi-Fi | 200 | 100 | 0 | 0.15 | 0.03 | 0.05 | 0 | 0 |
| Lost responses | 100 | 50 | 0 | 0 | 0 | 0 | 0.3 | 0 |
| Corruption | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.1 |
| Chaos | 300 | 200 | 2048 | 0.1 | 0.02 | 0.05 | 0.05 | 0.02 |

- "Advanced" expander (collapsed by default) with one slider per parameter: label (`neutral.body`) and current value (`neutral.meta`, right). Ranges: latency 0–3000 ms (step 50), jitter 0–1500 ms (step 50), bandwidth 0–10240 kbps (step 128, 0 shown as "Unlimited"), each rate 0–100% (step 1%). Editing a slider deselects the preset chip.
- Buttons in a row: "Apply" (Neutral primary, `PUT /admin/faults`) and "Reset" (Neutral outlined, `POST /admin/faults/reset`). Confirm with a snackbar "Simulator updated" or "Simulator reset"; failures show "Couldn't reach the server".
- Live stats sub-card (`neutral.page` fill, 12 dp radius, 12 dp padding), refreshed from `GET /admin/stats` every 2 s while this section is on screen: rows "Requests", "Errors injected", "Timeouts injected", "Dropped mid-transfer", "Responses lost", "Bytes corrupted", "Duplicate pieces ignored" with right-aligned numbers (`neutral.rowTitle` at 14 sp).

**About**
- "Version {versionName}" (`neutral.body`).
- Text button "Source code on GitHub" opening https://github.com/maanit-gupta/StableShare in the browser.
- Text button "Show the intro again" (resets `onboardingCompleted` and opens Onboarding).
- Text button "Open-source licences" opening a simple screen that lists the Comfortaa and Inter OFL texts.

### 5.11 Snackbars and toasts

Snackbars use the Neutral style on every screen: `neutral.inkPrimary` background, white `neutral.body` text, optional action in `neutral.accent` (14 sp SemiBold). Show them at the bottom, above the bottom bar where present. Toasts are used only for "Copied".

### 5.12 Notifications

All notifications use the small icon `ic_stat_plane` and accent colour `#7BBCA5`.

| Notification | Channel | Title | Text | Tap |
|---|---|---|---|---|
| Ongoing (coordinator) | "transfers" ("Transfers in progress", low importance) | "Moving {n} file(s)" (plural resource) | "{percent}% overall, {speed}"; with no speed: "{percent}% overall" | Opens Transfers |
| Completed | "results" ("Finished transfers", default importance) | "Upload complete" / "Download complete" | "{name} was verified." | Opens the transfer's Detail |
| Failed | "results" | "Upload failed" / "Download failed" | "{name}: {short reason}. Tap to see options." | Opens the transfer's Detail |

The ongoing notification shows a determinate progress bar (overall bytes) and updates at most once per second.

## 6. State presentation (single table)

"List" columns apply to Transfers rows; "Detail" columns apply to the detail hero. `{dir}` is "Uploading" or "Downloading"; `{done}` is "Uploaded" or "Downloaded".

| State / condition | List status label | Label colour | Bar fill | Detail title | Detail stats line |
|---|---|---|---|---|---|
| QUEUED | "Waiting, #{position} in line" | inkTertiary | `neutral.muted` (shows saved progress) | "Waiting in line" | "#{position} in line. Up to {limit} move at once." |
| TRANSFERRING, phase Preparing | "Preparing, {p}%" (checksum progress) | inkTertiary | `neutral.accent` | "Getting ready..." | "Calculating the file's checksum, {p}%" |
| TRANSFERRING | "{dir}…" | inkTertiary | `neutral.accent` | "{dir}..." | "{speed}, about {eta} left"; before speed is known: "Starting…" |
| VERIFYING | "Verifying…" | inkSecondary | `neutral.accent`, full width, alpha pulsing 0.6 ↔ 1 | "Checking..." | "Making sure every byte matches" |
| RETRYING (retryable) | "Retrying in {s} s" (counts down) | `neutral.warning` | `neutral.warningFill` | "Trying again..." | "Attempt {a} of {max}. Next try in {s} s." |
| RETRYING (NETWORK_UNAVAILABLE) | "Waiting for network" | inkSecondary | `neutral.muted` | "Waiting for signal" | "This continues on its own when you're back online." |
| PAUSED | "Paused" | inkSecondary | `neutral.paused` | "Paused" | "Progress saved: {done} of {total} pieces." |
| FAILED | "Failed: {short reason}" | `neutral.danger` | `neutral.danger` | "Something went wrong" | Long reason (section 7) |
| COMPLETED | "{done}" | `neutral.success` | `neutral.success`, full | "Completed" | "Verified. The SHA-256 checksum matches." |
| CANCELLED | (leaves the list) | — | — | "Cancelled" | "Partial data was deleted." |

Queue position counts QUEUED transfers in claim order, starting at 1.

## 7. Error copy

| ErrorCode | Short reason | Long reason |
|---|---|---|
| NETWORK_UNAVAILABLE | "No connection" | "You're offline. This continues on its own when you're back online." |
| METERED_NETWORK | "Waiting for Wi-Fi" | "Wi-Fi only is on, so this continues when you connect to Wi-Fi. You can change this in Settings." |
| TIMEOUT | "Server too slow" | "The server took too long to answer. Tap Retry to continue from where it stopped." |
| CONNECTION_LOST | "Connection dropped" | "The connection dropped mid-transfer. Tap Retry to continue from where it stopped." |
| SERVER_ERROR | "Server error" | "The server had a temporary problem. Tap Retry to continue." |
| RATE_LIMITED | "Server busy" | "The server asked StableShare to slow down. Tap Retry in a moment." |
| SESSION_NOT_FOUND | "Upload expired" | "The server no longer has this upload. Tap Retry to start a fresh one." |
| SESSION_CONFLICT | "Server mismatch" | "The server has a different version of this upload. Cancel it and upload the file again." |
| CHUNK_HASH_MISMATCH | "Piece damaged" | "A piece kept arriving damaged. Tap Retry to fetch it again." |
| FILE_HASH_MISMATCH | "Checksum didn't match" | "The finished file didn't match its checksum, so it wasn't marked complete. Tap Retry to repair it." |
| REMOTE_FILE_CHANGED | "File changed on server" | "The server's copy changed during the download. Cancel and download it again." |
| SOURCE_CHANGED | "File was edited" | "The original file changed after the upload started. Cancel and upload it again." |
| SOURCE_MISSING | "File not found" | "The original file was moved or deleted. Cancel this upload." |
| DISK_FULL | "Storage full" | "Your phone ran out of space. Free some up, then tap Retry." |
| RETRIES_EXHAUSTED | "Gave up after {max} tries" | "StableShare tried {max} times. Check the server, then tap Retry. Finished pieces are kept." |
| UNKNOWN | "Unexpected error" | "Something unexpected stopped this transfer. Tap Retry to continue." |

## 8. Activity copy

| Event | Message |
|---|---|
| STATE_CHANGE → QUEUED (on create) | "Added to the queue" |
| STATE_CHANGE → QUEUED (manual retry) | "Retry requested" |
| STATE_CHANGE → QUEUED (from PAUSED) | "Resumed" |
| STATE_CHANGE → TRANSFERRING | "Started moving" |
| STATE_CHANGE → PAUSED | "Paused" |
| STATE_CHANGE → VERIFYING | "Checking the finished file" |
| STATE_CHANGE → RETRYING | "Hit a problem: {short reason}" |
| STATE_CHANGE → FAILED | "Stopped: {short reason}" |
| STATE_CHANGE → COMPLETED | "Completed" |
| STATE_CHANGE → CANCELLED | "Cancelled" |
| CHUNK_DONE | Not listed individually. Consecutive CHUNK_DONE events collapse into one entry: "Pieces {first}–{last} done" (or "Piece {n} done") |
| CHUNK_FAILED | "Piece {n} failed: {short reason}" |
| CHUNK_CONFIRMED_AFTER_LOST_RESPONSE | "Piece {n} confirmed by the server after a lost reply" |
| RETRY_SCHEDULED | "Trying again in {s} s (attempt {a} of {max})" |
| VERIFIED | "Checksum verified" |
| ERROR | "{long reason}" |
| INFO "recovered after process restart" | "Restored after the app restarted" |
| INFO (other) | The event message as stored |

Piece numbers are shown 1-based (index + 1).

## 9. Formatting

- **Sizes:** binary units with these labels: "B", "KB", "MB", "GB" (1 KB = 1024 B). One decimal below 10 ("2.4 MB"), none at 10 or above ("200 MB"). In "{done} of {total}", the unit appears once and is chosen by the total ("84 of 200 MB").
- **Speed:** same rules plus "/s" ("4.1 MB/s"). Hidden when unknown or 0.
- **ETA:** under 60 s: "{s} s"; under 60 min: "{m} min {s} s" (drop the seconds above 10 min); otherwise "{h} h {m} min". Hidden when speed is unknown.
- **Durations** (History, Details): same style as ETA.
- **Times:** device locale, 24-hour pattern as written ("HH:mm:ss", "MMM d, HH:mm").
- **Percentages:** whole numbers, capped at 99 until COMPLETED (section 5.8.2).

## 10. Accessibility and reduced motion

- **Reduced motion** is on when `Settings.Global.ANIMATOR_DURATION_SCALE` is 0 or the system "Remove animations" setting is on. Then:
  - Splash: static cloud, plane perched, wordmark and tagline visible, 600 ms, then navigate.
  - Mascot: no breathing, bobbing, wobble, face slide, rain motion or waving. Mood changes still cross-fade.
  - Ring: no rotation; the dash-to-solid change is instant. The plane jumps between positions without laps.
  - Throw: the chip fades out over 150 ms, "+1" appears without movement, and the row appears without sliding.
  - Data drops and in-flight cell pulses are static.
- All interactive elements have content descriptions and 48 dp targets. Progress elements expose `ProgressBarRangeInfo` and a state description such as "Uploading, 42 percent".
- Layouts stay usable at 200% font scale: text wraps instead of truncating where space allows, and the detail hero scrolls.
- Contrast: all text meets WCAG AA (4.5:1) against its background. Colour is never the only signal; every state has a text label and, where relevant, an icon.

## 11. Deviations from the source designs

These are intentional, and Claude Code must not "correct" them back.

1. **Mascot:** the source robot character is replaced with the original Nimbus cloud and Dart plane. The face panel's slot on Screens 2–3 is filled by the cloud, and the decorative controls row becomes the data drops.
2. **Mint cancel button:** source danger `#E2345A` with label `#5A0F20` is about 3.2:1 contrast. The button uses `#D92B52` with a white label (about 4.9:1).
3. **Neutral greys:** `inkSecondary` darkened to `#4B5563` and `inkTertiary` to `#6F7682` so meta text passes AA on white.
4. **Neutral primary button:** a white label on the source orange is about 2.9:1, so the primary button is near-black (`#0F1115`) with an orange icon, and the FAB uses a dark plus on orange.
5. **Design A buttons** grow from 96 × 32 to 104 × 36 dp so labels fit at larger font scales; touch targets are 48 dp.
6. **Hoop layout** is restacked for portrait phones (header, drop zone with hoop, test-file chips, rows, launch pad and button).
7. **Counter pill** shows uploads in the queue rather than a demo score, and the demo's reset loop is dropped.
8. **Upload start:** tapping Upload replaces the drag-and-flick gesture; the throw plays automatically.
