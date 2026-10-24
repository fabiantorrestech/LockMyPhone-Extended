# Trim, Simple/Pro mode, setup assistant, Material You refresh

**Status:** approved by the owner on 2026-10-24 (one question round; the rest is left to the builder).
**Branch:** `trim-and-assistant`.
**ADR:** none. Nothing here changes the enforcement or storage approach; every decision below is cheap to reverse.

## 1. Intent

The owner's words: "trim the fat", "it's quite cluttered to navigate and use", "a good setup assistant for new users with sensible defaults and good default profiles", "a nice Material You type design", and later: "sensible whitelists for things almost everyone will need from their phone".

The app has grown to 17 settings cards, 4 tabs and a place editor with six kinds of dialogs. A non-technical user can't tell what matters. This work makes the first run and the daily use simple, keeps every power feature reachable in **Pro mode**, and removes one feature that never worked well.

## 2. Goals · non-goals · anti-goals

**Goals**
1. Remove the paired-Bluetooth-device place signal ("connected to" and "nearby").
2. A **Simple / Pro** switch. Simple shows what a non-technical user needs; Pro shows everything that exists today.
3. A **setup assistant** that gets a new user from install to a working first lock in a few screens, creating starter profiles and, if wanted, a bedtime schedule.
4. An **Essentials** allowed-app set (Phone, Messages, Clock, Camera hidden) that every starter profile and the Lock defaults start from.
5. A Material You refresh: dynamic colour already exists; the screens should look like one designed app (rounded tonal cards, clear hierarchy, big primary actions).
6. A **"Popups from other apps"** Pro setting that generalises today's Gemini-popup rule to a user-picked list of apps.
7. Installed on the owner's Pixel 7 at the end, data kept.

**Non-goals**
- Removing Act as a beacon, custom picker fonts or the Intent API (owner's call: keep, Pro only).
- Whitelisting individual *intents* or activities of arbitrary apps. The accessibility service sees windows, not intents; a per-screen whitelist is possible later (App info already works that way) but not now.
- Dropping legacy DB columns. SQLite on Android 11 can't drop columns without rebuilding the table; the project's precedent (`nfcUnlockEnabled`, `LocationMatch`) is to keep them.
- Emergency mode (M4), M15 editions, the watch.

**Anti-goals** (failure even if it "works")
- Anything the owner has set up stops working after the update: profiles, schedules, places (minus their BT signal), NFC tags, Morning lock, backups.
- A feature disappears with no way to reach it. Everything hidden in Simple is one switch away.
- The assistant asks about things that have a sensible default (device type, timing presets, engines).

## 3. Constraints
- No new dependencies. Kotlin + Compose Material 3, manual DI, Room, SharedPreferences.
- Lock rules stay in the pure `LockEngine`; UI mode is a UI concern and never reaches enforcement.
- The active lock state is in device-protected storage; new per-lock options (popup packages) follow the `assistantPopupAllowed` pattern exactly.
- Owner's CLAUDE.md rules: no tests unless asked; small commits; stage only implementation files.

## 4. Decisions

### 4.1 Remove the paired-Bluetooth-device signal
Delete the UI, logic, receiver and permission plumbing:
- `PlaceSignal.BT_DEVICE`, `BtDetection`, `LocationTrigger.usesBt*`, `nearbyDeviceKey`, `BeaconTracker` device tracks, `Readings.btConnected/btNearby`, `BtConnectionTracker`, `BtConnectionReceiver` (+ manifest entry), `PairedDeviceTools`, `PairedDevicePickerDialog`, `PairedDeviceList`, `SetupKind.DEVICE` (the setup flow becomes beacon-only), `LocationDialog.DEVICE_*`/`RANGE_DEVICE`, `AppPermission.NEARBY_CONNECT`, `SetupStatus.btDevicePlacesExist/bluetoothConnectGranted`, `BLUETOOTH_CONNECT` in the manifest, the `bt_*`/`perm_nearby_connect*`/`setup_paired_devices`/`location_summary_bt_*`/`test_bt_*` strings, the test-checklist items T2/T3, README bullets.
- Keep: `PairedDevices.normalize` (used by the beacon scanner; move it to `BeaconRules` and delete `BluetoothDevices.kt`), `PairedDeviceKind` goes with it.
- DB: the four `bt*` columns stay in the entity as legacy fields with a comment, like `nfcUnlockEnabled`. No version bump. A saved place whose `signals` contains `BT_DEVICE` simply loses that signal (`signalSet` already filters unknown names); if it was the only signal the place never fires and its editor shows it as incomplete. Backups stop writing the fields; reading ignores them.
- `BLUETOOTH_CONNECT`: the beacon scanner reads names from the scan record, not `BluetoothDevice.getName()`, so the permission goes.

### 4.2 Simple / Pro mode
- `SettingsStore.uiMode: SIMPLE | PRO`, key `ui_mode`, in the backup snapshot. Default **SIMPLE** for everyone, including the owner's existing install (that is what "declutter" asks for). The switch is the first card of Settings in both modes, so Pro is one tap away.
- A `LocalUiMode` composition local (`isPro`) so deep composables (place editor, picker appearance) can hide Pro rows without threading parameters everywhere.
- What each mode shows:

| Area | Simple | Pro adds |
|---|---|---|
| Tabs | **Lock · Automations · Settings** (3) | same |
| Lock tab | Starter/your profiles as tiles with ▶; a Quick lock card (length chips + Start + "Quick lock options") | same, plus ↻ Reset to defaults on the options screen |
| Automations | Schedules, Morning lock, Alarm triggers, Places; Calendar and NFC cards only if they have entries, plus a "More kinds…" row to add one | all cards always |
| Settings | Mode switch, Setup checklist, Set-up assistant, Lock defaults, During a lock, Appearance (theme, icons, sizes, status bar, time left), Backup, Permissions | Length presets, Wait 1 minute after changes, Alarm day start, Location engine, Beacon timing, Act as a beacon, Calendar defaults, NFC gate, Popups from other apps, Intent API, Device, picker font, Diagnostics |
| Place editor | Measure (seat/edge) in the beacon setup; Range row | "I'll enter the size" (📐), "Type a value", Match by, Live signal, per-place Timing, Listen harder |

### 4.3 Setup assistant
Replaces the two-step `OnboardingScreen`. Full screen, a progress bar, headline per step, Back/Next at the bottom. Steps:
1. **Welcome** – two sentences on what the app does. *Get started*.
2. **Essentials** – four rows with a status and a button: Device admin, Accessibility service, Notifications, Battery unrestricted. Next is enabled once admin and accessibility are on; *Skip for now* is a text button.
3. **Your situations** – four selectable cards (multi-select; Sleep and Focus preselected): Sleep 🌙, Focus 🎯, Study 📚, Family time 🍽. Each card lists its length and what stays usable.
4. **Apps that stay usable** – the Essentials plus the chosen situations' extra apps, resolved on this phone (icons + names), with a line "Change these later in each profile". Below it the switch **Allow the Gemini / Assistant popup** (off by default, as today).
5. **Bedtime** (only if Sleep is chosen) – "Lock every night?" switch (on), start 22:30, end 07:00, every day.
6. **Done** – "Simple mode is on. Pro mode in Settings unlocks everything." *Finish* writes everything and sets `onboardingDone`.

Device type is no longer asked: it's auto-detected (Pro → Device card still lets you override).

**Starter profiles** (`data/StarterProfiles.kt`, pure resolution + insert): created only if no profile with that name exists. The "Default" profile is no longer created when at least one starter profile is; `ensureDefaultProfile` runs after the assistant, not before.

| Profile | Length | Allowed (besides Essentials) | DND | Early unlock |
|---|---|---|---|---|
| Sleep 🌙 | 9 h (the schedule gives the real length) | – | Priority | 10 min |
| Focus 🎯 | 50 min | Music, Calendar | off | 5 min |
| Study 📚 | 90 min | Calculator, Music, Calendar | off | off |
| Family time 🍽 | 2 h | – | Priority | 5 min |

**Essentials** = default dialer (always added by the engine anyway), default SMS app (`Telephony.Sms.getDefaultSmsPackage`), the clock app (`AlarmClock.ACTION_SHOW_ALARMS` resolver), the camera (hidden; `defaultCameraPackage`). Role apps resolve through Android's `CATEGORY_APP_*` intents (music, calendar, calculator); missing ones are skipped. The Lock defaults are set to Essentials + 1 h + 10-min early unlock when the assistant finishes and the defaults were never edited.

Emergency SOS, the emergency dialer, in-call screens and the power menu are already always allowed (OEM profiles), so "5-tap power" works during every lock with no new setting.

### 4.4 Popups from other apps (Pro)
Generalises the Gemini rule: for each package in `popupPackages`, its **non-activity windows** (dialogs, overlays, bubbles) are allowed over an allowed app; its activities still relock. Stored like `assistantPopupAllowed`: `GlobalLockOptions.popupPackages` → `LockSession.popupPackages` → `LockStateStore` (DE prefs, string set) → `ForegroundPolicy.isAlwaysAllowed(..., popupPackages)`. Settings → During a lock → "Popups from other apps" opens the app picker. Backup carries it. Simple hides the row.

### 4.5 Material You refresh (Compose)
- `Theme.kt`: keep dynamic colour; add `Shapes` (small 12, medium 20, large 28, extraLarge 32) so every `Card` rounds; typography: headline/title weights slightly bolder, `labelLarge` used for section headers already.
- Home: `CenterAlignedTopAppBar`; **Lock tab** hero = profile tiles in a 2-column grid (`ProfileTile`: icon in a tonal circle, name, length, filled ▶) and the Quick lock card below; bottom `NavigationBar` with 3 items.
- Settings: cards grouped under `labelLarge` headers (Setup · Locks · Appearance · Advanced), consistent `SettingsRow` use; the mode switch is a tonal `primaryContainer` card.
- Assistant: `LinearProgressIndicator`, `headlineMedium` titles, selectable cards with a primary outline when chosen, `Button` + `TextButton` footer.
- No new fonts; no custom colours beyond the scheme roles.
- A design mockup page (Artifact) of Assistant · Lock tab · Settings Simple/Pro for the owner to react to in the morning.

### 4.6 Version
`versionName` 0.10.0, `versionCode` 2.

## 5. Left to the builder
- Exact wording of strings (plain, short, matching the existing voice).
- Whether the Quick lock options screen reuses `LockSettingsSections` as is (yes, intended).
- Order of Settings cards within groups.
- README / test-checklist edits for the removed signal and the new screens.
