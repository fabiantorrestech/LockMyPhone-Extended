# Morning lock (M22) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans (owner chose Native; owner asked for it to be built, run on the AVD, then installed on the Pixel 7).

**Goal:** Lock the phone for the first hour after waking, every day, with or without an alarm, without firing by accident.

**Architecture:**
- Pure rules decide; Android code only reads signals and acts.
  - `trigger/wake/WakeRules` (pure) decides.
  - `WakeSignalStore` remembers the last unlock and charging time.
  - `WakeWatcher`, registered by the accessibility service, hears unlocks and power changes.
  - `WakeTriggerManager` runs a decision and starts the lock through `ProfileLockStarter` with `LockKind.WAKE`.
- The alarm tier piggybacks on `AlarmWatchListener`.
- UI follows the calendar trigger's pattern: a Home card, an editor screen and a setup flow.

**Tech Stack:** Kotlin, Compose (Material 3), Room (v23 → v24), manual DI (`AppContainer`).

**Spec:** `doc/plans/2026-10-03-morning-lock.md`

## Global Constraints
- No new dependencies. No tests (project rule); the gate for each task is `./gradlew assembleDebug` with no `e:` lines.
- Room migrations are hand-written (`exportSchema = false`). The SQL must match what Room generates for the entity.
- Strings are added with exact edits, with apostrophes escaped as `\'`.
- Simple vs Pro: Simple = window, lock, sleep places, Test now. Pro (Advanced) = days, alarm first + apps, minimum sleep, the checks, the charging length.
- Don't commit (the owner commits). Stage new source files. Don't stage `handoffContext/` or `.superpowers/`.

## Review Focus
1. Reboot overnight: the gap still counts from the last stored unlock, and charging since boot counts.
2. Lock screen "None": SCREEN_ON stands in for USER_PRESENT; one physical unlock (SCREEN_ON + USER_PRESENT) counts only once.
3. An unlock while another lock is running: refused by ADR 0005, `lastFiredDay` is still set, and it doesn't fire later that day.
4. Accessibility off: no unlocks are heard; Test now and the editor say so; the alarm tier still works.
5. The GPS fix times out: no place match; charging can still confirm.

---

### Task 1: Hear unlocks outside a lock (spike, then keep)
**Files:**
- Create `trigger/wake/WakeSignalStore.kt` and `trigger/wake/WakeWatcher.kt`.
- Modify `core/enforce/LockAccessibilityService.kt` to register the watcher in `onServiceConnected` and unregister it in `onUnbind`.

**Interfaces, produced:**
- `class WakeSignalStore(context)` with:
  - `lastUnlockMs: Long?`
  - `chargedMsSince(lastUnlockMs, nowMs)`
  - `onUnlock(nowMs)`
  - `onPowerConnected(nowMs)`
  - `onPowerDisconnected(nowMs)`
  - `syncChargingAtStart(charging: Boolean, nowMs)`
- `class WakeWatcher(context, onUnlock: (previousUnlockMs: Long?, chargedMs: Long, nowMs: Long) -> Unit)` with `register()` and `unregister()`.
  - It handles USER_PRESENT, SCREEN_ON (only when no keyguard is set) and POWER_CONNECTED/DISCONNECTED.
  - It ignores a second unlock within 5 s.

**Check:** install on the AVD; enable accessibility via adb; with no lock running, sleep, wake and swipe-unlock. Expected: logcat `WakeWatcher: unlock (previous …)`.

### Task 2: Data, Room v24, backups
**Files:**
- Create `data/db/WakeTrigger.kt` with the entity and `WakeTriggerDao`.
- Modify:
  - `data/db/AppDatabase.kt`: the entity, the DAO, a `Set<Long>` converter, version 24, `MIGRATION_23_24` creating `wake_triggers` and its profileId index.
  - `data/backup/BackupJson.kt` and `BackupManager.kt`: `wakeTriggers` array, restore (sleep place IDs remapped like the calendar's `requiredPlaceId`).

**Interfaces, produced:**
- The `WakeTrigger(...)` fields exactly as in spec §5.
- `WakeTriggerDao`:
  - `observeAll`, `getAll`, `getEnabled`, `getById`
  - `insert`, `insertLast`, `update`, `delete`
  - `reorder`, `setLastFiredDay(id, day)`

### Task 3: Rules + manager + lock kind + alarm tier
**Files:**
- Create `trigger/wake/WakeRules.kt`, `trigger/wake/SleepPlaceChecker.kt` and `trigger/wake/WakeTriggerManager.kt`.
- Modify:
  - `core/lock/LockState.kt`: `WAKE(3)`
  - `ui/Formatting.kt`: the lock-kind label
  - `AppContainer.kt`: the manager, plus `onHoldEnded` for WAKE (catch up within the lock period)
  - `trigger/alarm/AlarmWatchListener.kt`: watch Morning lock alarm apps, and call `wakeTriggerManager.onAlarmDismissed(pkg)` after the quiet check
  - `core/enforce/LockAccessibilityService.kt`: wire `onUnlock` to the manager

**Interfaces:**
- `WakeRules.decideUnlock(trigger, nowMs, zone, previousUnlockMs, chargedMs): UnlockStep`, where `UnlockStep` is one of:
  - `Skip(reason)`
  - `LockNow`
  - `NeedPlace` (gap OK, charging not enough, place check on)
- `WakeRules.decideAlarm(trigger, nowMs, zone): Skip | LockNow`
- `WakeRules.day(nowMs, zone): LocalDate`
- `WakeRules.inWindow(...)`
- `WakeRules.lockMs(trigger, nowMs, zone)`: the lock length.
- `SleepPlaceChecker.check(places): SleepMatch?`: Wi-Fi first, then one GPS fix.
- `WakeTriggerManager`:
  - `onUnlock(previousUnlockMs, chargedMs, nowMs)`
  - `onAlarmDismissed(pkg)`
  - `catchUp(id)`
  - `alarmPackages(): Set<String>`
  - `explain(draft, places): WakeExplanation` (for Test now)

### Task 4: UI: Home card, editor, setup, Test now
**Files:**
- Create `ui/WakeEditorScreen.kt` (the editor plus the `WakeSetupFlow` dialog).
- Modify:
  - `ui/HomeScreen.kt`: `MorningLockCard` at the top of Automations, the toggle and reorder actions
  - `MainActivity.kt`: `Screen.WAKE`, the draft, save/delete/hold/Test now; "I'm in bed now" creates a CONDITION_ONLY place
  - `trigger/AutomationTester.kt`: `wake(draft, places)`
  - `core/perm/AppPermissions.kt`: `forWakeTrigger`
  - `res/values/strings.xml`

### Task 5: Build, run, install, checklist, handoff
- `assembleDebug` + `assembleRelease`.
- AVD: install; check DB v24; set up Morning lock with a window that includes now and a sleep place.
- Simulate the gap: on the debug build, use `run-as` to rewrite the `wake_signals` prefs so the last unlock is 5 h ago. Then sleep, wake and unlock. Expected: the lock starts, and logcat shows `WakeTriggers`.
- Repeat with no sleep place and no charging. Expected: no lock, with a "not sleeping" reason.
- Install on the Pixel 7. Add T73–T78. Update the handoff.
