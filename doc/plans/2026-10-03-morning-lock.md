# Morning lock (M22): design spec

Approved in chat by the owner on 2026-10-03.

## 1. Why

The owner's lock for the first hour of the day starts when they dismiss their alarm. On weekends and some other days there is no alarm, so no lock starts. Starting it by hand defeats the point.

**Goal:** lock the phone for the first hour after waking, every day, with or without an alarm. It must not fire by accident, such as after a late night out (home at 4–5 am) or a night at their partner's place.

**How it works:** one new automation, **Morning lock**. It fires **once per day**, on the first of two moments:

1. **An alarm is dismissed** inside the wake-up window. This is exact and immediate.
2. **Otherwise, the first unlock** inside the window after a long gap with no unlocks (default 4 h), **but only if at least one check says you were really sleeping.**

## 2. Rules

All of these are on by default.

| Step | Rule | Default |
|---|---|---|
| Day | The day is active (`daysMask`) and Morning lock hasn't fired today | every day |
| Window | Now is inside the wake-up window | 05:00–14:00 |
| Tier 1: Alarm | An alarm from the chosen alarm apps is dismissed. The lock starts with no other check | on; the phone's default clock app |
| Tier 2: Unlock | An unlock (USER_PRESENT) where the previous unlock was at least the minimum sleep ago | 4 h |
| Sleeping checks (Tier 2 only, **any one** is enough) | **a)** charging for at least 2 h in total since the previous unlock. **b)** at a sleep place: **Wi-Fi first** (connected to one of the place's networks), **then GPS** (a fresh fix inside the place's radius, up to 15 s) | both on |
| None of the checks pass | No lock. This fails toward not locking | n/a |
| After firing | `lastFiredDay` = today, so nothing else fires until tomorrow | n/a |

- **Order of the checks:** cheapest first (charging, then Wi-Fi, then GPS). Since any one is enough, the order doesn't change the result; it only saves battery. GPS is used only when Wi-Fi doesn't match.
- **Day boundary:** "today" is the local date of the window. A dismissal or unlock at 13:59 counts for that day; one at 14:01 doesn't count at all.
- **The lock:** a profile (or Quick lock) for the lock length (default 60 min), started through `ProfileLockStarter` with a new `LockKind.WAKE(3)`. It joins the one-lock-at-a-time rules (ADR 0005), the M16 edit hold and the 1-minute wait (catching up while the window is still on), and Test now.
- **Later, not in this build:** a third check, "the watch said you were asleep". It waits for a one-night watch test (§8).

### How the rules play out

| Night | Result |
|---|---|
| Weekday, alarm at 7:00 | Dismiss → lock 7:00–8:00 |
| Weekend, home and charging, first unlock 10:30 | Gap ≥4 h + charging → lock |
| Home at 4:30 using the phone, sleep, first unlock 11:00 | The gap restarts at 4:30; 6.5 h + Home Wi-Fi → lock |
| Partner's place (a sleep place), not charging | Wi-Fi or GPS matches → lock |
| Phone unused at a party until 5:00, then an unlock | Not charging, not at a sleep place → **no lock** |
| Awake at 6:30 before a 7:00 alarm | The unlock tier fires at 6:30; the alarm is ignored (already fired today) |

## 3. Setup (Simple)

A guided setup in the same style as the M21 beacon setup. It opens when Morning lock is added from the Add menu.

```
1  When do you wake up?
   Between [5:00] and [14:00] · Lock for [1 h] · Profile [Quick lock ▾]
2  Where do you sleep?
   [ I'm in bed now – use here ]   ← makes a sleep place from the current Wi-Fi + GPS
   [ Pick a place I already have ]
   + Add another (e.g. partner's place)
3  Done
   "Locks for 1 h the first time you pick up your phone after sleeping.
    Alarm days: when you dismiss it."
   (If alarm locks already exist: "Your alarm lock '<name>' does the same on alarm days.
    Turn it off?"  [Turn off] [Keep both])
```

- **"I'm in bed now – use here"** creates a place in `CONDITION_ONLY` mode named "Sleep: <Wi-Fi name or 'Here'>":
  - Wi-Fi signal: the connected network (ROUTER), when there is one.
  - GPS: the current fix, radius 100 m.
  - Signals combine with OR.
  - It never starts a lock by itself, and it appears in the Places list like any other place.
- **Pick an existing place:** sleep places are ordinary places. Picking one doesn't change its mode.
- **Keep both** is allowed, because one-lock-at-a-time already stops a double lock. **Turn off** is a convenience.

## 4. Editor after setup

```
Morning lock                                  [switch]
Wake-up window       5:00 – 14:00
Lock                 1 h · Quick lock
Sleep places         Home, Partner's place        (tap: add / remove)
Test now
▸ Advanced (Pro)
   Days                    Every day
   Alarm first             ✓  · Clock
   Minimum sleep           4 h        (3–8 h)
   Counts as sleeping      ✓ charging ≥ 2 h   ✓ at a sleep place
```

- **Simple:** window, lock, sleep places, Test now.
- **Pro (Advanced):** days, alarm first and its apps, minimum sleep, the sleeping checks, and the charging length (1–4 h).
- If you turn off every sleeping check, a red banner shows: "Any unlock after <gap> will lock — even after a night out."
- **Missing permissions** show as rows with a fix button, as in the place editor:
  - accessibility (needed to hear unlocks)
  - notification access (alarm tier)
  - location "all the time" (GPS check)
- **Test now** (dry run): "If you unlocked now: last unlock 6 h ago ✓ · charging 3 h ✓ · at Home via Wi-Fi ✓ → would lock for 1 h". It offers the optional 30 s test lock, like the other editors.

## 5. How it fits together

- **Data, Room v23 → v24:** a new table `wake_triggers`, entity `WakeTrigger`:
  - `id, name, enabled, profileId?, lockMinutes=60`
  - `windowStartMin=300, windowEndMin=840, daysMask=127`
  - `alarmFirst=true, alarmPackages: Set<String>`
  - `minSleepMinutes=240`
  - `sleepPlaceIds: Set<Long>`
  - `checkCharging=true, chargingMinutes=120, checkPlace=true`
  - `lastFiredDay?, icon?, sortOrder`
  - The table allows several, but the UI makes one by default.
  - Backups include it.
  - A deleted sleep place is skipped, and the editor flags it.
- **`trigger/wake/WakeRules`** (pure): `decideUnlock(trigger, nowMs, zone, lastUnlockMs, chargedMs, placeCheck)` and `decideAlarm(...)`, giving `Lock(durationMs)` or `Skip(reason)`. The reasons feed Test now and logcat.
- **`trigger/wake/WakeSignalStore`** (SharedPreferences, not backed up):
  - `lastUnlockMs`
  - `chargingSinceMs?` and `chargedMsSinceUnlock`
  - Each unlock resets the charging total (an ongoing charge restarts counting from the unlock).
- **`trigger/wake/WakeWatcher`:** a runtime receiver for USER_PRESENT, POWER_CONNECTED and POWER_DISCONNECTED.
  - **Registered in `LockAccessibilityService.onServiceConnected`**, which runs whenever the accessibility service is on. Today USER_PRESENT is heard only while a lock runs (`LockForegroundService`).
  - On an unlock it reads the store, runs the rules, does the place check (`LocationRules` presence with Wi-Fi only, then GPS through the place engine's current-fix path if needed), starts the lock, then updates the store.
  - ⚠ To verify first (plan Task 1): USER_PRESENT reaches a receiver registered by the accessibility service, with no lock running, on the Pixel 7.
- **Alarm tier:** `AlarmWatchListener` already spots the first alarm dismissed from given apps (`FIRST_ALARM`). Enabled Morning locks join its watched apps, and a dismissal is passed to `WakeRules.decideAlarm`.
- **UI:**
  - `ui/WakeSetupFlow.kt`, `ui/WakeEditorScreen.kt`
  - a Home list row (the M16 switch, icon ☀)
  - an Add-menu item
- **No new dependency.**
- **No ADR:**
  - The new table follows ADR 0003.
  - The lock kind follows ADR 0005.
  - Listening for unlocks inside the accessibility service is cheap to undo.

## 6. Edge cases (the review checks these)

1. **Reboot overnight:**
   - The first unlock after a boot uses the stored `lastUnlockMs`, so the gap stays correct.
   - Charging since boot counts from the boot time when the phone was charging at boot.
2. **No secure lock screen, or Smart Lock:**
   - Smart Lock still fires USER_PRESENT when the keyguard is dismissed.
   - With the lock screen set to None there is no USER_PRESENT, so SCREEN_ON counts as the unlock (the backstop the lock service already uses).
3. **Accessibility off:** the unlock tier can't hear anything. The editor shows the permission row, and Test now says so. The alarm tier still works.
4. **The GPS fix takes 15 s, or there's none:** the place check fails, but charging can still confirm. No lock is started by guessing.
5. **Unlock during the M16 hold or wait:** treated like other automations. It catches up for the rest of the hour if it's still inside the lock period.
6. **An unlock while another lock is running:** ADR 0005 decides. If Morning lock is skipped, `lastFiredDay` is still set, so it doesn't fire later that day.

## 7. Testing (checklist items)

- **T73** Weekend, no alarm: sleep at home, charging → first unlock in the window locks for 1 h; later unlocks don't.
- **T74** Alarm day: dismissing the alarm locks right away; the next unlock doesn't lock again.
- **T75** Night out: no use for 4 h+, not charging, away from sleep places → unlock in the window, no lock (Test now shows why).
- **T76** Sleep place by Wi-Fi vs GPS: at home on Wi-Fi it matches instantly; with Wi-Fi off, GPS matches.
- **T77** "I'm in bed now – use here" creates a condition-only place; the setup offers to turn off an existing alarm lock.
- **T78** Reboot overnight still locks on the first morning unlock; DB v23 → v24 keeps everything; backup → restore keeps Morning lock.

## 8. Watch (separate, before any watch work)

A one-night test with a throwaway Wear OS app that logs Health Services `UserActivityInfo` (asleep, passive, exercise) and its timestamps on the Pixel Watch 3. It needs the `androidx.health:health-services-client` library in a throwaway module only, and the **owner's OK**.

- If the awake time is within about 10 min of real wake-ups, add "watch said asleep" as a sleeping check (and possibly as a trigger) with M15's watch app.
- Otherwise drop it. Health Connect (Fitbit sleep sessions) is not used: it arrives only after sync, often too late.
