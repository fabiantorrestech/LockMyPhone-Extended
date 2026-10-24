# Trim · Simple/Pro · Setup assistant · Material You — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the paired-Bluetooth place signal, add a Simple/Pro switch, a setup assistant with starter profiles and an Essentials whitelist, a "popups from other apps" option, and a Material You refresh; install on the Pixel 7.

**Architecture:** UI mode is a `SettingsStore` value exposed through a `LocalUiMode` composition local; it never reaches `LockEngine`. Starter profiles are built by a pure `StarterProfiles` object from app packages that `AppCatalog` resolves on the device. Popup packages travel like `assistantPopupAllowed`: `GlobalLockOptions → LockSession → LockStateStore → ForegroundPolicy`.

**Tech Stack:** Kotlin, Jetpack Compose Material 3 (BOM 2026.02.01), Room 2.8.5, SharedPreferences. No new dependencies.

**Spec:** `doc/plans/2026-10-24-trim-simple-mode-assistant.md`

## Global Constraints
- No tests (owner's rule); each task ends with `./gradlew assembleDebug` passing and a commit.
- No new dependencies. No DB version bump (legacy `bt*` columns stay on the entity).
- Lock rules stay in pure `LockEngine`; UI mode never influences enforcement.
- Every new setting has a working default and is Pro-only unless the spec lists it as Simple.
- Strings in `res/values/strings.xml`, plain voice, matching existing wording.
- Commits on branch `trim-and-assistant`, message ends with the Co-Authored-By / Claude-Session lines from the session.

## Review Focus
1. A saved place whose only signal was `BT_DEVICE` must open in the editor without crashing and show as incomplete (Save disabled), never fire.
2. An existing install (owner's Pixel 7) with `onboardingDone = true` must land on the Lock tab, Simple mode, every profile/automation intact; the assistant must not run unasked.
3. The assistant on a phone with no SMS app / no clock app must still create the profiles (missing roles skipped) and Phone must always be in the list.
4. Running the assistant again from Settings must not duplicate profiles or schedules (match by profile name; schedule only if the Sleep profile is created now).
5. A lock started before the update (no `popup_packages` key) must read back with an empty set, and the Gemini rule must behave exactly as before.

---

### Task 1: Remove the paired-Bluetooth-device signal

**Files:**
- Delete: `trigger/location/BluetoothDevices.kt`
- Modify: `data/db/LocationTrigger.kt` (remove `PlaceSignal.BT_DEVICE`, `usesBt*`; keep `btAddress/btName/btDetection/btRssiThreshold` as legacy fields; `BtDetection` stays as a legacy enum next to `LocationMatch` with a comment), `trigger/location/BeaconRules.kt` (add `fun normalizeAddress(address: String): String`), `trigger/location/BeaconScanner.kt` (use `BeaconRules.normalizeAddress`), `trigger/location/PresenceSignals.kt` (drop device tracks, `nearbyDeviceKey`, `devicePresence`, `status(place, device)` → `status(place)`), `trigger/location/LocationRules.kt` (drop `btConnected/btNearby` from `Readings` and the BT branch), `trigger/location/LocationTriggerManager.kt` (drop `btConnections`, `canSeeConnections`, device refresh calls; `beaconStatus(place)`), `trigger/AutomationTester.kt`, `core/perm/AppPermissions.kt` (drop `NEARBY_CONNECT`), `ui/LocationEditorScreen.kt` (drop `PairedDeviceTools`, the BT section, `DEVICE_*`/`RANGE_DEVICE` dialogs, `PairedDevicePickerDialog`; `SetupKind` removed → `BeaconSetupFlow` beacon-only), `ui/BeaconSetupFlow.kt`, `ui/BeaconPickerDialogs.kt` (drop `PairedDeviceList`, kind icons/labels), `ui/Formatting.kt`, `ui/HomeScreen.kt` (`SetupStatus.btDevicePlacesExist/bluetoothConnectGranted`, `SetupActions.onGrantBluetoothConnect`, checklist row), `ui/PermissionsScreen.kt`, `MainActivity.kt` (launcher, actions, `LaunchedEffect(locations)`, `refreshSetupStatus`, `permissionRows`, `runtimePermissions`, `grantPermission`, imports), `data/backup/BackupJson.kt` (stop writing `bt*`; reading leaves defaults), `AndroidManifest.xml` (`BLUETOOTH_CONNECT`, `BtConnectionReceiver`), `res/values/strings.xml` (remove `bt_*`, `setup_paired_devices`, `perm_nearby_connect*`, `location_summary_bt_*`, `test_bt_*`, `section_bt_device`, `signal_bt_device*`; fix `perm_nearby_scan_why`), `README.md`, `handoffContext/test-checklist.md` (drop T2, T3; note T10 unchanged).

**Interfaces:**
- Produces: `BeaconRules.normalizeAddress(address: String): String`; `BeaconTracker.status(place: LocationTrigger): BeaconStatus?`; `LocationTriggerManager.beaconStatus(place: LocationTrigger): BeaconStatus?`; `BeaconTools.status: (LocationTrigger) -> BeaconStatus?`.

- [ ] Step 1: Make the code changes listed above; `grep -rn "BT_DEVICE\|BtDetection\|PairedDevice\|btConnected\|btNearby\|NEARBY_CONNECT\|bluetoothConnect" app/src/main/java` must return only the legacy entity fields and their comment.
- [ ] Step 2: Run `./gradlew assembleDebug`; expected `BUILD SUCCESSFUL`.
- [ ] Step 3: Remove the strings, manifest entries, README bullets (lines about paired devices in Features, First-time setup table, FAQ, Known limits, Test status) and checklist rows T2/T3.
- [ ] Step 4: Build again; commit `Remove the paired-Bluetooth-device place signal`.

### Task 2: UI mode, Essentials, starter profiles, popup packages (data layer)

**Files:**
- Modify: `data/SettingsStore.kt`, `data/backup/BackupJson.kt` (snapshot fields `uiMode`, `popupPackages`), `core/lock/LockState.kt`, `core/lock/LockEngine.kt:108`, `core/lock/LockStateStore.kt`, `core/lock/LockController.kt:420`, `core/enforce/ForegroundPolicy.kt`, `AppContainer.kt` (`globalLockOptions`, `ensureDefaultProfile` unchanged), `core/apps/AppCatalog.kt`
- Create: `ui/UiMode.kt`, `data/StarterProfiles.kt`

**Interfaces:**
- Produces:
  - `enum class UiMode { SIMPLE, PRO }` (in `data/SettingsStore.kt`); `SettingsStore.uiMode: StateFlow<UiMode>`, `setUiMode(mode: UiMode)`, key `"ui_mode"`, default SIMPLE.
  - `SettingsStore.popupPackages: StateFlow<Set<String>>`, `setPopupPackages(packages: Set<String>)`, key `"popup_packages"`.
  - `GlobalLockOptions.popupPackages: Set<String> = emptySet()`; `LockSession.popupPackages: Set<String> = emptySet()`; store key `"popup_packages"`.
  - `ForegroundPolicy.isAlwaysAllowed(packageName, className, allowAssistantPopup: Boolean = false, popupPackages: Set<String> = emptySet())`; a package in `popupPackages` passes when `!oemDetector.isActivity(packageName, className)`.
  - `val LocalUiMode = compositionLocalOf { UiMode.SIMPLE }`; `@Composable fun isPro(): Boolean` (in `ui/UiMode.kt`).
  - `AppCatalog.defaultSmsPackage(): String?` (`Telephony.Sms.getDefaultSmsPackage`), `AppCatalog.clockPackage(): String?` (resolve `AlarmClock.ACTION_SHOW_ALARMS`), `AppCatalog.categoryApp(category: String): String?` (resolve `Intent(ACTION_MAIN).addCategory(category)`, skip `"android"`).
  - `enum class Situation(val icon: String, val nameRes: Int, val durationSec: Long, val bypassMinutes: Int?, val dnd: Boolean)` with SLEEP("🌙", 9 h, 10, true), FOCUS("🎯", 50 min, 5, false), STUDY("📚", 90 min, null, false), FAMILY("🍽", 2 h, 5, true) (in `data/StarterProfiles.kt`).
  - `data class ResolvedApps(val visible: Set<String>, val hidden: Set<String>)`.
  - `class EssentialApps(private val catalog: AppCatalog)` with `fun essentials(): ResolvedApps` (dialer, SMS, clock visible; camera hidden) and `fun extrasFor(situation: Situation): Set<String>` (FOCUS: music, calendar; STUDY: calculator, music, calendar; others empty) using `Intent.CATEGORY_APP_MUSIC/CALENDAR/CALCULATOR`.
  - `object StarterProfiles { fun profile(situation: Situation, name: String, apps: ResolvedApps, extras: Set<String>): LockProfile; fun bedtimeSchedule(profileId: Long, startMinute: Int, endMinute: Int): LockSchedule }`.
  - `class StarterProfileInstaller(private val database: AppDatabase, private val essentials: EssentialApps, private val settings: SettingsStore, private val nameOf: (Situation) -> String)` with `suspend fun install(situations: Set<Situation>, bedtime: Pair<Int, Int>?): Boolean` (returns whether anything was created; skips names that exist; sets Lock defaults to Essentials + 1 h + 10 min only when `settings.lockDefaults.value.allowedPackages.isEmpty()`).
  - `AppContainer.starterProfileInstaller` (lazy).

- [ ] Step 1: Add the settings, options, store key, policy parameter and backup fields. `LockController.onForegroundApp` passes `session?.popupPackages.orEmpty()`.
- [ ] Step 2: Add the `AppCatalog` resolvers and `data/StarterProfiles.kt` as specified. Strings: `situation_sleep/focus/study/family` names.
- [ ] Step 3: `./gradlew assembleDebug` → `BUILD SUCCESSFUL`; commit `Simple/Pro mode value, Essentials, starter profiles, popup packages (data layer)`.

### Task 3: Home restructure and Material You theme

**Files:**
- Modify: `ui/theme/Theme.kt` (add `Shapes(small 12, medium 20, large 28, extraLarge 32)`), `ui/theme/Type.kt` (headlineMedium/Small and titleLarge `FontWeight.SemiBold`), `ui/HomeScreen.kt`, `MainActivity.kt`, `res/values/strings.xml`
- Create: `ui/LockTab.kt`, `ui/QuickLockOptionsScreen.kt`

**Interfaces:**
- Produces:
  - `enum class HomeTab { LOCK, AUTOMATIONS, SETTINGS }`.
  - `@Composable fun LockTab(profiles, adminActive, accessibilityEnabled, quickLock: LockSettings, presetsSec, granted, onEditProfile: (LockProfile?) -> Unit, onStartProfile: (LockProfile, Long) -> Unit, onReorderProfiles, onQuickDurationChange: (Long) -> Unit, onStartQuick, onOpenQuickOptions, onNewProfile)` — profile tiles in a 2-column grid (`ProfileTile`: tonal circle icon, name, length, filled ▶ that opens `StartProfileDialog`), then a Quick lock card (length row + `DurationPresetChips` + Start + "Quick lock options" text button).
  - `@Composable fun QuickLockOptionsScreen(quickLock, presetsSec, nfcTags, onChange, onEditAllowedApps, onReset, onBack, modifier)` — `EditorScaffold`-like top bar (no Save), `LockSettingsSections`, ↻ reset in the app bar only when `isPro()`.
  - `Screen.QUICK_OPTIONS` in `MainActivity`.
  - `HomeScreen` new params: `uiMode: UiMode, onUiModeChange: (UiMode) -> Unit, onOpenAssistant: () -> Unit, popupPackages: Set<String>, onEditPopupPackages: () -> Unit`; removed: `startActions.onEditQuickApps` stays, `onOpenQuickOptions` added to `StartLockActions`.
  - `Screen.POPUP_APPS` → `AllowedAppsScreen`-style multi-select of packages (reuse `AllowedAppsScreen` with `hidden = emptySet()` and ignore hidden changes).
- Settings tab layout (both modes): `ModeCard` (tonal `primaryContainer`, `SegmentedButton` Simple / Pro, one-line hint) → group **Setup**: `SetupChecklist`, `AssistantCard` (one row "Set-up assistant"), `PermissionsCard` → group **Locks**: `LockDefaultsCard`, `DuringLockCard` (+ Pro row "Popups from other apps" with count summary), Pro: `LengthPresetsCard`, `AutomationCooldownCard` → group **Automations** (Pro only): `AlarmDayStartCard`, `LocationEngineCard`, `BeaconTimingCard`, `BeaconModeCard`, `CalendarDefaultsCard`, `NfcGateCard` → group **Appearance**: `AppearanceCard` → group **Advanced** (Pro only): `IntentApiCard`, `DeviceCard`, `BackupCard` is Simple (under Setup), Diagnostics button.
- Automations tab: Simple hides `CalendarCard` when `calendarTriggers.isEmpty()` and `NfcTagsCard` when `nfcTags.isEmpty()`, and shows `MoreKindsCard` with buttons "Calendar trigger" and "NFC tag" (NFC only when `setup.hasNfc`).
- `MainActivity.setContent` wraps content in `CompositionLocalProvider(LocalUiMode provides uiMode)`; `IdleState.homeTab` default `HomeTab.LOCK`; `requestedTab` mapping unchanged (AUTOMATIONS).

- [ ] Step 1: Theme shapes/typography; `HomeTab` and tabs; `LockTab.kt`; `QuickLockOptionsScreen.kt`; wire `Screen.QUICK_OPTIONS`.
- [ ] Step 2: Settings tab groups with `isPro()` gating; `ModeCard`, `AssistantCard` (`onOpenAssistant` opens the assistant with `rerun = true`), popup row + `Screen.POPUP_APPS`.
- [ ] Step 3: Automations gating + `MoreKindsCard`. Strings: `tab_lock`, `mode_title`, `mode_simple`, `mode_pro`, `mode_hint`, `assistant_card_title`, `assistant_card_hint`, `quick_options`, `popup_apps_title`, `popup_apps_hint`, `popup_apps_count`, `more_kinds_title`, `settings_group_*`.
- [ ] Step 4: `./gradlew assembleDebug` → `BUILD SUCCESSFUL`; commit `Three tabs, Lock tab with profile tiles, Simple/Pro settings, Material You shapes`.

### Task 4: Setup assistant

**Files:**
- Create: `ui/SetupAssistantScreen.kt`
- Delete: `ui/OnboardingScreen.kt`
- Modify: `MainActivity.kt` (replace `OnboardingScreen`; add `assistantOpen` state for re-runs; `ensureDefaultProfile()` moves to run when `onboardingDone` becomes true and after `install`), `AppContainer.kt`, `res/values/strings.xml`

**Interfaces:**
- Produces: `data class SetupResult(val situations: Set<Situation>, val bedtime: Pair<Int, Int>?, val assistantPopup: Boolean)`;
  `@Composable fun SetupAssistantScreen(setup: SetupStatus, setupActions: SetupActions, previewApps: (Set<Situation>) -> List<LaunchableApp>, iconProvider: AppIconProvider, initialAssistantPopup: Boolean, hasNfc: Boolean, rerun: Boolean, onFinish: (SetupResult) -> Unit, onCancel: (() -> Unit)?, modifier)`.
  Steps (enum `AssistantStep { WELCOME, ESSENTIALS, SITUATIONS, APPS, BEDTIME, DONE }`, BEDTIME skipped when SLEEP not chosen): `LinearProgressIndicator(progress)`, `headlineMedium` title, scrolling body, footer `TextButton` Back / `Button` Next. ESSENTIALS rows: admin, accessibility, notifications, battery, each `SettingsRow` with ✓ or an "Enable" `FilledTonalButton`; Next enabled when `adminActive && accessibilityEnabled`, plus a `TextButton` "Skip for now". SITUATIONS: `SituationCard` (selected → `BorderStroke(2.dp, primary)`, `primaryContainer` background), SLEEP and FOCUS preselected on first run, none preselected on rerun. APPS: `AppRow` list from `previewApps`, Gemini popup `SwitchSettingsRow`. BEDTIME: switch "Lock every night" (on), two `SettingsRow` time pickers (22:30 / 07:00) using `TimePickerDialog`. DONE: text + Finish.
- `MainActivity`: `onFinish` → `lifecycleScope.launch { installer.install(result.situations, result.bedtime); settingsStore.setAssistantPopupAllowed(result.assistantPopup); container.ensureDefaultProfile(); scheduleScheduler.sync(); settingsStore.setOnboardingDone(true); assistantOpen = false }`.
- `onCreate` no longer calls `ensureDefaultProfile()` before onboarding; it runs in `IdleContent` once (`LaunchedEffect(Unit)`) and after the assistant.

- [ ] Step 1: Write `SetupAssistantScreen.kt` and its strings (`assist_*`).
- [ ] Step 2: Wire it in `MainActivity`; delete `OnboardingScreen.kt` and its `onboarding_*` strings.
- [ ] Step 3: `./gradlew assembleDebug` → `BUILD SUCCESSFUL`; commit `Setup assistant with starter profiles and a bedtime schedule`.

### Task 5: Pro gating in the place editor and picker appearance

**Files:**
- Modify: `ui/LocationEditorScreen.kt`, `ui/BeaconSetupFlow.kt`, `ui/BeaconRangeDialogs.kt`, `ui/PickerAppearance.kt`

- [ ] Step 1: With `isPro()` false hide: Live signal row, per-place Timing row, Listen harder (boost) row, "I'll enter the size" and "Type a value" in `BeaconRangeDialog` and `BeaconSetupFlow` AREA step, the "Advanced: recognise it by…" (Match by) button, and the picker font "Advanced options" button.
- [ ] Step 2: `./gradlew assembleDebug` → `BUILD SUCCESSFUL`; commit `Hide fine-grained beacon and font controls in Simple mode`.

### Task 6: Version, docs, verification, install

**Files:**
- Modify: `app/build.gradle.kts` (`versionCode = 2`, `versionName = "0.10.0"`), `README.md` (Features: Simple/Pro, assistant, Essentials, popups; First-time setup rewritten around the assistant), `handoffContext/test-checklist.md` (add T101–T106: assistant first run, assistant re-run, Simple/Pro switch, Lock tab start, popup packages, upgrade keeps data), `handoffContext/handoff.md`.

- [ ] Step 1: Build; start the `Pixel_9` AVD (`emulator -avd Pixel_9 -no-window -no-audio -no-boot-anim`), `adb -s emulator-5554 install -r app-debug.apk`, launch, `screencap` the assistant steps, Lock tab, Settings Simple and Pro, Automations; inspect each PNG; fix what's wrong.
- [ ] Step 2: Upgrade check on the AVD: install the baseline APK (`git stash`-free: build from `main` into a scratch copy, or use the pre-change `app-debug.apk` saved at the start of the night), create a profile and a schedule, install the new APK with `-r`, confirm Lock tab shows the profile and the schedule.
- [ ] Step 3: Dispatch one code-review subagent on the branch diff; apply confirmed fixes; build; commit.
- [ ] Step 4: `adb -s 29071FDH200AFV install -r app/build/outputs/apk/debug/app-debug.apk`; `adb shell dumpsys package com.fabiantorrestech.lockmyphone_extended | grep versionName` shows 0.10.0. Do not launch it on the phone.
- [ ] Step 5: Update README, checklist, handoff; commit `0.10.0: docs, checklist, handoff`.

### Task 7: Design mockup artifact

- [ ] Step 1: `Artifact quickstart intent=design`; build a mockup page with four phone frames (Assistant "Your situations", Lock tab, Settings Simple, Settings Pro) in Material You tonal colours; publish; put the link in the final message and handoff.
