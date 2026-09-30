# M15 plan: two editions + Pixel Watch companion

**Status:** draft for discussion. Nothing is built yet.
**Date:** 2026-09-29.
**ADRs:** 0005 and 0006 are written once the decisions below are settled (see [ADRs to write](#adrs-to-write)).

---

## 0. Decisions so far (2026-09-29)

These override the recommendations further down where they differ.

| # | Decision |
|---|---|
| D1 | **Build variants** (`gms` / `foss` flavors) on one branch. No fork: both editions live in this repo and are built from the same commit. |
| D3 | **Same package name** for both editions. Backups and data are identical, so you can switch editions either way without losing anything. |
| D6 | **The watch can never pause or end a lock, or start the early-unlock wait.** That must be done consciously on the phone. The watch **can start** locks, but only inside the watch app. |
| D7 | Settled by D6: the **complication shows only the time left**, with no lock name on the watch face. |
| D11 | Keep the **same (debug) key** for the phone and watch apps. Back up `~/.android/debug.keystore`. |
| D14 | **M7 NFC first**, then M7.1, then M15. |

**The watch app** (this replaces D5):
1. **Tapping the complication** opens the watch app, with two choices: **Profile** · **Quick lock**.
2. **Quick lock:**
   - **H : M : S scroll wheels**, defaulting to the phone's Quick lock length.
   - **Hold Start for 3 s:** a bar fills inside the button, and when it's full the lock starts.
3. **Profile:** a list of profiles. **Hold an entry for 3 s** (a fill bar inside the entry) and it starts with its default length.
4. **Once a lock runs** (or when the app is opened during one), a **black screen** shows:
   - the timer
   - the kind of lock: "Quick lock", the profile name, "Schedule", "Calendar", "Alarm", "NFC tag", or **"Location lock active"** (while-here locks have no fixed end)
5. **What this needs:**
   - **A new `LockSession.source`,** set when a lock starts.
   - **Watch → phone messages:** `/start-lock` (profile id, or a Quick lock length). The phone answers "started", "needs device admin", or "phone not unlocked since reboot".
   - **Phone → watch data:** a `/profiles` data item (name, icon, default length) and the Quick lock defaults.
   - **Wear Compose**, a dependency to approve when M15 starts.
   - **Starting from the watch is Google edition only.** The Google-free edition keeps only the basic watch notification.

**Still open** (asked when M15 starts): D4 (basic watch notification in both editions), D8 (idle complication), D9 (early-unlock countdown on the watch), D10 (contract module), D12 (watch minSdk), D13 (names).

---

## 1. Summary

The app will ship as **two editions** with the same features, built from the same code:

| | **Google edition** (`gms`) | **Google-free edition** (`foss`) |
|---|---|---|
| Depends on Google Play services | Yes (location, watch sync) | **No**: no `com.google.android.gms` code at all |
| Every lock feature, trigger, profile, backup, permission page… | ✅ | ✅ identical |
| Location engine | Play Services geofencing (default) or built-in | Built-in only |
| Watch: lock notification on the watch | ✅ (only when the watch app isn't installed) | ✅ |
| Watch: **complication** with a live countdown on the watch face | ✅ via the companion watch app | ❌ |

- **Feature parity rule:** every feature exists in both editions. The only allowed differences are:
  - how something is done (e.g. the location engine)
  - the watch complication, which needs Google's phone-to-watch messaging
- **"Very basic watch functionality"** in the Google-free edition means a countdown notification that Android copies to the watch ("bridging"). There's no watch app and nothing on the watch face.
- The **companion watch app** is a third APK, a new `wear` module in this repo, installed on the watch. It shows the time left as a **complication** you can place on any watch face that supports it.

---

## 2. Goals and non-goals

**Goals**
1. Nobody is forced to use Google Play services: the Google-free edition contains no Play Services code.
2. Watch-face complication on the Pixel Watch 3 that shows the time left, updating by itself between messages.
3. One codebase and one branch, so every feature is written once.

**Non-goals (v1)**
- Controlling locks **from** the watch. The watch is read-only (see D6).
- A Tile, or showing the *next* scheduled lock (possible later, D8).
- Getting the watch app onto the Play Store. Like the phone app, it's sideloaded.
- Non-Wear OS watches (Garmin, Fitbit OS, Apple Watch): out of scope. The bridged notification may still reach some of them through their own phone apps.

---

## 3. Decisions to discuss

Each has a recommendation (★). Nothing is locked in until you answer.

### D1. Two branches, or one branch with two build flavors?
| Option | How | Pros | Cons |
|---|---|---|---|
| A. Two long-lived git branches | `main` = Google-free, `watch` = Google + watch | Simple to picture | Every feature must be merged twice; the branches drift; conflicts in shared files grow over time; releases need two builds from two branches |
| **B. ★ One branch, Gradle product flavors `gms` / `foss`** | Shared code in `app/src/main`; Google-only code in `app/src/gms`; stand-ins in `app/src/foss` | Features written once; both built from the same commit; Android Studio switches with the Build Variants panel | Slightly more Gradle setup; each Google-only feature needs a small interface + a stand-in |
| C. One APK, Play Services optional at runtime | Today's approach for location | No build changes | Still ships Google's libraries in the APK, so not "Google-free" |

- ★ **B.** It gives you "two versions" without the cost of two branches.
- The docs say **editions**, not branches.

### D2. What leaves the Google-free edition?
- ★ **All Play Services code:**
  - `play-services-location`, which is `PlayServicesEngine` and the geofence part of `LocationReceiver`
  - `play-services-wearable`, which is new
- In the Google-free edition:
  - Location uses the built-in engine only, and the **Location engine** setting is hidden.
  - Trade-off: the built-in engine can notice arrivals more slowly (Android delivers its background updates every ~2–5 min; ADR 0004) and uses a little more battery. Exact numbers are measured on the Pixel 7 in M15a, not assumed. Wi-Fi, beacon, charger and paired-device signals are unaffected, because none of them use Play Services.

### D3. The same package name for both editions, or different ones?
| Option | Result |
|---|---|
| **★ Same ID** (`com.fabiantorrestech.lockmyphone_extended`) | Switching editions = installing one over the other; your data stays. Only one can be installed at a time, which is **good**: two lock apps each with device admin + accessibility would fight each other. Intent API macros keep working. The watch app works with the Google edition out of the box. |
| Different IDs (e.g. `….foss`) | Both can be installed side by side, but then both enforce locks, which risks double relocks and two accessibility services. Switching means backup → restore. Macros need the other package name. |

- ★ **Same ID.** The app shows which edition it is in **Settings → About**, and the APK file names say it too.
- ⚠ Switching editions only keeps your data when both APKs are signed with the **same key** (see D11).

### D4. The basic watch feature: which editions get it?
- ★ **Both.**
  - Post one small extra notification while a lock runs: "🔒 Locked · until 5:30 PM", with a countdown if the watch shows one.
  - It's **not pinned**, because pinned (ongoing) notifications normally aren't copied to the watch.
  - It uses no Google code (it's a plain Android notification), so parity holds.
- **Google edition with the watch app installed:** the phone detects the watch app and stops posting it, so you don't get both a notification and the complication.
- **Cost:** during a lock the phone shows a second, silent notification. There's a switch to turn it off (see §6.5).

### D5. What does the watch app contain?
| Option | Needs |
|---|---|
| Complication only; tapping it does nothing | Smallest; no UI library |
| **★ Complication + a tiny status screen when tapped** ("Locked · Deep work · until 5:30 PM · synced 2 min ago") | Wear Compose (new dependency) |
| + a Tile | Tiles library (new dependency); later, if wanted |

### D6. Can the watch control the lock?
- ★ **No, it's read-only.** A watch button that ends or pauses a lock would be an escape hatch.
- **Possible later:** "Start Quick lock" from the watch, since starting a lock is harmless. That would need phone ← watch messages, so it's left out of v1.

### D7. Show the lock's name on the watch face?
- The watch face is visible to people around you ("Therapy", "Exam prep"…).
- ★ **Setting "Show lock name on the watch", off by default.** When it's off, the watch shows only the countdown and a lock icon.

### D8. What does the complication show when no lock is running?
| Option | Effort |
|---|---|
| **★ A lock-open icon + "—"** | Trivial |
| Nothing (the slot looks empty) | Trivial, but the slot looks broken |
| "Next: 9:00" (next scheduled lock) | Hard: the next lock depends on schedules, calendars, places and alarms, and some can't be predicted (arriving somewhere). Later, schedules + calendar only. |

### D9. Early-unlock countdown (bypass) on the watch?
- ★ **Yes.** While an early-unlock wait is running, the complication counts down to *that* time (`LockSession.effectiveEndMs`) with a different icon (hourglass).

### D10. How do the phone and watch agree on the message format?
| Option | |
|---|---|
| Copy one small `WatchContract.kt` into both modules | No new module; the two copies can drift apart |
| **★ A tiny Android library module `:watch-contract`** (paths, keys, a `version` number, encode/decode) | One source of truth; uses `com.android.library` from the same Android Gradle plugin, and AGP 9 builds its Kotlin, so no new plugin or library |

### D11. Signing key (⚠ affects every later update)
- **Today:** release builds are signed with the **debug key**, which is unique to *this* PC (`~/.android/debug.keystore`).
- **The phone app and the watch app must be signed with the same key,** or Google's messaging refuses to connect them. Switching editions over each other needs the same key too.
- **Risk:** build on another PC, or lose that file, and you can't update the installed app. You'd have to uninstall, which loses your data unless you have a backup.
- **Options:**
  1. ★ **Keep the debug key, and back up `debug.keystore`** (e.g. to your password manager). No reinstall needed now.
  2. Create a proper release keystore, kept out of git. ⚠ **Breaking:** the installed app must be uninstalled once (backup → reinstall → restore).

### D12. Lowest watch version (watch app `minSdk`)
- ★ **API 33 (Wear OS 4).** That's needed for the self-updating **progress ring**. Your Pixel Watch 3 shipped with Wear OS 5, so it qualifies.
- API 30 (Wear OS 3) would need a fallback ring that only refreshes every few minutes. That's not worth it for one watch.

### D13. Names
- **Flavors (in code):** `gms` / `foss`.
- **Shown to users:** ★ "Google edition" / "Google-free edition". Your call; for example "Watch edition" / "Standard edition" also work.
- **APK file names:**
  - `LockMyPhone-Extended-vX.Y.Z-google.apk`
  - `LockMyPhone-Extended-vX.Y.Z-googlefree.apk`
  - `LockMyPhone-Extended-Watch-vX.Y.Z.apk`

### D14. When?
- Before or after **M7 NFC / M7.1 check-in nudge**? The flavor split (M15a) touches Gradle and location code, so doing it **before** M7 means NFC is written flavor-aware from the start. NFC itself needs no Google code.

---

## 4. Architecture

### 4.1 Modules
```
LockMyPhone-Extended/
├─ app/                      phone app (both editions)
│  ├─ src/main/              everything shared (≈ all of today's code)
│  ├─ src/gms/               Play Services location engine, geofence events, Data Layer watch sync
│  └─ src/foss/              stand-ins: no Play Services engine, no watch sync
├─ wear/                     NEW: watch app (complication + status screen)
└─ watch-contract/           NEW: shared message format (paths, keys, encode/decode)
```

### 4.2 Keeping Google code out of `main`: the `Edition` seam
- **New `app/src/main/.../edition/EditionServices.kt`** (interface):
  - `name` (GOOGLE / GOOGLE_FREE)
  - `playServicesEngine: PresenceEngine?` (null in foss)
  - `geofenceEvents: GeofenceEventParser?` (null in foss)
  - `watchSync: WatchSync`
- **`app/src/gms/.../edition/Edition.kt`** and **`app/src/foss/.../edition/Edition.kt`**: an object with the **same name and package** in both. Each flavor compiles only its own.
- **`AppContainer`** asks `Edition.create(context)` for these. Nothing in `main` imports `com.google.android.gms`.
- **What moves or changes:**

| File | Change |
|---|---|
| `trigger/location/PlayServicesEngine.kt` | Moves to `src/gms` unchanged |
| `trigger/location/LocationReceiver.kt` | The `ACTION_GEOFENCE` branch calls `geofenceEvents?.parse(intent)`. The two GMS imports move to a small `gms` class. In foss, a leftover geofence intent is ignored (logged). |
| `trigger/location/LocationTriggerManager.kt` | `val playServices = PlayServicesEngine(context)` becomes an injected `PresenceEngine?`. `activeEngine()`, `stop()` of the other engine and the `nearGeofence` check (line ~339) handle null. |
| `MainActivity.kt` (~495) / `HomeScreen.kt` (LocationEngineCard) | `playServicesAvailable` becomes false in foss, and the card is **hidden** there |
| `SettingsStore.locationEngine` + `BackupJson` | The default becomes `Edition.defaultLocationEngine` (PLAY_SERVICES in gms, BUILT_IN in foss). A stored or restored PLAY_SERVICES in foss reads as BUILT_IN. |
| `app/build.gradle.kts` | `flavorDimensions += "services"`, `productFlavors { create("gms"); create("foss") }`, and `"gmsImplementation"(libs.play.services.location)` + `"gmsImplementation"(libs.play.services.wearable)` |

### 4.3 The message format (`:watch-contract`)
- **One Data Layer item at the path `/lock-state`**:

| Key | Type | Meaning |
|---|---|---|
| `v` | Int | Format version (1). The watch ignores versions it doesn't know and shows "update the watch app". |
| `active` | Boolean | A lock is running |
| `startMs` | Long | `LockSession.startedAtMs` (epoch ms) |
| `endMs` | Long | `LockSession.endsAtMs` |
| `bypassEndMs` | Long, or 0 | Early-unlock wait end, if running |
| `label` | String, or "" | Lock name, **only if** "Show lock name on the watch" is on |
| `sentAtMs` | Long | Sent time, for "synced N min ago" |

- **Why a data item, not a message:** Data Layer *data items* are stored and re-synced when the watch reconnects, and the watch can read the latest one at any time. A *message* is lost if the watch is away.
- **Times are wall-clock epoch ms**, the same as the lock store. The phone and watch clocks are both network-synced, so seconds of drift don't matter.

### 4.4 Phone → watch (Google edition): `DataLayerWatchSync`
- **Source of truth:**
  - `LockStateStore.state` (an existing `StateFlow<LockState>`). Every start, extend, merge, early end, bypass start and resume goes through `store.save`, so **one collector covers everything**, and `LockController` needs no hooks.
  - Started in `LockApp` (app scope). It re-publishes on process start, which is idempotent.
- **Dedup:**
  - It maps the state to the §4.3 fields and only sends when those change. Pass on/off doesn't reach the watch.
  - `PutDataMapRequest(...).setUrgent()`, so it's delivered right away instead of batched.
- **Failure handling:**
  - Before the first unlock after a reboot (direct boot), or when Play services is missing or disabled, or no watch is paired: catch the error, log it, and mark "pending".
  - Retry on `BOOT_COMPLETED` (after the first unlock), on app start and on the next state change.
  - A lock is **never** delayed by the watch.
- **Is the watch app installed?**
  - The watch app declares a capability `lockmyphone_watch` (in `res/values/wear.xml`).
  - The phone checks it with `CapabilityClient`, at app start and when the capability changes. That decides:
    - the "Watch app: installed on Pixel Watch 3" line in Settings
    - whether the bridged notification (§4.6) is skipped

### 4.5 The watch app (`:wear`)
- **Same `applicationId`** as the phone app, **signed with the same key**; Data Layer needs both.
- **`namespace`** `com.fabiantorrestech.lockmyphone_extended.wear`.
- **Manifest:**
  - `uses-feature android.hardware.type.watch`
  - meta-data `com.google.android.wearable.standalone = false`: it needs the phone
- **`LockStateListenerService`** (`WearableListenerService`):
  - Filter: `DATA_CHANGED`, `wear://*/lock-state`.
  - Saves the item to local prefs.
  - Calls `ComplicationDataSourceUpdateRequester.create(ctx, ComponentName(ctx, LockComplicationService::class.java)).requestUpdateAll()`.
- **`LockComplicationService`** (a timeline complication data source):
  - It serves **3 types**, chosen by the watch face slot:

    | Type | While locked | After the lock / not locked |
    |---|---|---|
    | SHORT_TEXT | 🔒 + "1h 23m": `TimeDifferenceComplicationText` counting down to `endMs`, drawn by the watch itself (Google now prefers `DynamicComplicationText` for new code; whichever the library version supports cleanly is used) | 🔓 + "—" |
    | RANGED_VALUE | Progress ring from `startMs` to `endMs` with a **dynamic value** (Wear OS 4+), so the ring shrinks by itself | Empty ring + 🔓 |
    | LONG_TEXT | "Deep work · until 5:30 PM" (or "Locked · until 5:30 PM") | "Not locked" |

  - **Timeline:**
    - one entry valid from now → `endMs` with the countdown
    - the **default entry** is "not locked"
    - The watch switches to "not locked" **at the end time by itself**, even with no message and with the phone out of range.
  - **Manifest:**
    - `UPDATE_PERIOD_SECONDS = 0`: no polling; it's push-only
    - `SUPPORTED_TYPES = SHORT_TEXT,RANGED_VALUE,LONG_TEXT`
  - **Tap** → `StatusActivity` (D5).
  - **No data yet** (fresh install, or the phone runs the Google-free edition): "—", and the status screen says "No lock info from the phone yet. The Google-free edition can't send it."
- **`StatusActivity`** (Wear Compose):
  - big countdown, the lock name (if shared), the end time and "synced N min ago"
  - a note that it's read-only

### 4.6 The basic watch notification (both editions)
- **Channel:** a new "On your watch" channel, silent, low importance.
- **Posted** when a lock starts, updated on changes, cancelled when the lock ends.
- **Content:**
  - title "🔒 Locked", or "🔒 Deep work" if the name is shared
  - text "Until 5:30 PM"
  - `setWhen(endMs)` + `setUsesChronometer(true)` + `setChronometerCountDown(true)`
  - `setOnlyAlertOnce(true)`, **not ongoing**
  - `WearableExtender().setBridgeTag("lock_status")`
- **Skipped when:**
  - the setting is off
  - (Google edition) the watch app is installed
- **Swiped away?** It isn't re-posted until the lock changes, so it's not nagging.
- **Default:**
  - on if a paired watch is found: `PairedDevices` already sorts bonded devices into WATCH; this needs BLUETOOTH_CONNECT
  - otherwise off

### 4.7 Settings → Watch card
- **Both editions:**
  - "Show the lock on my watch" switch (the §4.6 notification) + hint "Your watch shows phone notifications; a countdown appears there during a lock."
  - "Show lock name on the watch" switch (D7)
- **Google edition only:**
  - status "Watch app: installed on ‹watch name› / not found"
  - a "How to install the watch app" link to the README section
- **Google-free edition only:** a line saying the complication needs the Google edition.

### 4.8 Backups
- **New settings keys:** `watch_notification`, `watch_show_name`. They're backed up and restore in either edition.
- **Location engine:** a restored PLAY_SERVICES reads as BUILT_IN in foss (§4.2).

---

## 5. Dependencies (need your OK)

| Library | Where | Why |
|---|---|---|
| `com.google.android.gms:play-services-wearable` | phone **gms only** + watch | Data Layer (data items, capabilities, listener service) |
| `androidx.wear.watchface:watchface-complications-data-source-ktx` | watch | Complication data source + timeline |
| `androidx.wear.protolayout:protolayout-expression` | watch | Dynamic ring value. Probably already pulled in by the line above; I'll check before adding it explicitly |
| `androidx.wear.compose:compose-material3` (+ `compose-foundation`) | watch | Status screen (D5). Not needed if D5 = complication only |
| `play-services-location` (existing) | moves to **gms only** | No change for the Google edition |

- **Not adding:** `kotlinx-coroutines-play-services`. Play `Task`s are wrapped with `suspendCancellableCoroutine`, as `PlayServicesEngine` already does.
- **Also not adding:** `wear-phone-interactions` (`BridgingManager`). The duplicate-notification problem is solved on the phone side with `CapabilityClient` instead.

---

## 6. Milestones

| # | Scope | Done when |
|---|---|---|
| **M15a** | Flavors + the Edition seam; `play-services-location` moved to gms; edition shown in About; build docs updated | Both editions build debug + release. The **foss dependency tree and merged manifest contain no `com.google.android.gms`**. Installing gms over today's app keeps all data. Installing foss over gms keeps data, and places switch to the built-in engine. |
| **M15b** | Basic watch notification (§4.6) + Watch card (both editions) | During a lock the watch shows "🔒 Locked · until …"; it disappears at the end; the switch works |
| **M15c** | `:watch-contract` + `:wear` app (complication + status screen) + `DataLayerWatchSync` + capability check | The complication counts down on your watch face; early end → "—" within seconds; the phone out of range → it still ends at the end time; with the watch app installed, the bridged notification stops |
| **M15d** | README (editions, watch install), CLAUDE.md, release with 3 APKs, ADR 0005 + 0006 | Docs match; the GitHub release has 3 APKs |

---

## 7. Spikes (might not work; each is proven before building on it)

| # | Question | How to check | If it fails |
|---|---|---|---|
| S0 | Does today's lock notification already appear on your watch? (Expected: **no**, because it's pinned/ongoing) | **You, no code:** start a 2-min Quick lock and look at the watch | If it already shows, §4.6 can be skipped |
| S1 | Is a **silent, low-importance, non-ongoing** notification bridged to the Pixel Watch 3? Does the countdown tick live there? | M15b debug build | Try default importance with `setSilent(true)`. If there's no live countdown, the text "Until 5:30 PM" is still enough. |
| S2 | Does our DND mode during a lock hide notifications on the watch? (The Pixel Watch mirrors phone DND.) | Lock with DND on, check the watch list | DND normally only silences. If our mode hides them from the list, allow this app through our own DND modes |
| S3 | Install the watch app from Windows: wireless debugging on the watch + Android Studio | **You:** watch Settings → Developer options → Wireless debugging → `adb pair`, then run `:wear` | USB isn't available on the Pixel Watch; if pairing fails, Android Studio's "Pair devices using Wi-Fi" |
| S4 | Which of your watch faces accept SHORT_TEXT vs RANGED_VALUE? Does the dynamic ring move? | After M15c | SHORT_TEXT alone is enough; the ring is a bonus |
| S5 | Does a data item sent while the phone is still locked right after a reboot arrive once unlocked? | Reboot mid-lock | The retry on `BOOT_COMPLETED` covers it |
| S6 | Does Android clear Play Services geofences when foss is installed over gms? If not, their intents hit foss's `LocationReceiver` | Switch editions with a place set | foss ignores them (planned anyway); harmless |

---

## 8. Risks and hiccups (found while reviewing this plan)

1. **Two lock apps at once:** different package names would allow both editions side by side, each enforcing, with two accessibility services and double relocks. **Solved by D3 (same ID).**
2. **Signing key (D11):** the watch app, edition switching and every update all depend on one key that exists only on this PC today. Decide D11 **before** M15c.
3. **Backup from the Google edition restored into the Google-free one:** the location engine value PLAY_SERVICES must fall back, or places silently stop. Both `SettingsStore` and `BackupJson` default to PLAY_SERVICES today (`BackupJson.kt:171`, `SettingsStore.kt:289`), so both need the edition default.
4. **Hidden Play Services leaks:** some other library could pull in `com.google.android.gms` transitively. M15a checks `./gradlew :app:dependencies --configuration fossReleaseRuntimeClasspath` and the merged foss manifest (no `com.google.android.gms.version` meta-data).
5. **Stale watch data:**
   - If the phone ends a lock early while the watch is out of range, the watch keeps counting until it reconnects.
   - A lock that ends **on time** is always right (timeline).
   - The status screen shows "synced N min ago" so you know.
6. **An extended lock while disconnected:** the watch shows "not locked" at the old end time, though the phone is still locked. Same mitigation. This is acceptable because the phone is what enforces the lock.
7. **Duplicate notification on the phone** in basic mode: a second, silent notification during locks. Mitigations: a switch; auto-off with the watch app; low importance, so it sits in the silent section.
8. **Privacy on the watch face** (D7): lock names off by default.
9. **Build time and commands:**
   - `./gradlew assembleDebug` now builds **2 phone variants + the watch app**.
   - Docs and CLAUDE.md switch to `assembleGmsDebug` / `assembleFossDebug` / `:wear:assembleDebug`.
   - In Studio, pick the variant in **Build Variants**.
10. **Future features must respect parity:**
    - Add a CLAUDE.md rule: "Play Services code only in `app/src/gms`. Every feature needs a Google-free path, or is listed as a watch-only difference in this doc."
    - M15a's dependency check can be re-run any time.
11. **The watch app paired with the Google-free phone app:** same ID + key, so the watch app installs fine but never gets data. The status screen explains why (§4.5).
12. **The test checklist grows:** everything in T1–T28 was built as today's (Google) app. After M15a, the Google-free edition needs a smoke test: a lock, a place on the built-in engine, a backup restored from the Google edition.
13. **The Data Layer needs Google's "Pixel Watch" app on the phone:** it's already there, since the watch needs it. Nothing extra to install.
14. **Lock state changes often (passes, resume):** the dedup (§4.4) sends only when watch-visible fields change, so there's no battery cost on the watch.
15. **Short text length:** SHORT_TEXT fits ~7 characters.
    - `SHORT_DUAL_UNIT` gives "1h 23m", or "2d 3h" for multi-day locks.
    - In ambient mode, watch faces refresh once a minute, so seconds aren't shown. That's fine for a countdown in minutes.
16. **Future M4 emergency mode:** the format has a `v` field, so an "emergency" state can be added later without breaking older watch apps.

---

## 9. ADRs to write (once decided)

- **0005: Ship Google and Google-free editions as build flavors** (D1–D3, D13).
  - It amends 0004: Play Services location stays the Google edition's default; the Google-free edition uses built-in location only.
  - 0004 isn't superseded.
- **0006: Wear OS companion over the Data Layer; the watch is read-only** (D4–D7, D10–D12).

---

## 10. Tests to add to the checklist (after building)

- **Google-free edition:**
  - It builds with no Play Services.
  - A lock works.
  - A place works on the built-in engine.
  - A backup from the Google edition restores.
  - Installing it over the Google edition keeps data.
- **The basic watch notification:**
  - It appears on the watch during a lock and leaves at the end.
  - The switch works.
  - It stops when the watch app is installed.
- **The complication:**
  - SHORT_TEXT countdown; RANGED_VALUE ring moves.
  - Early end → "—" within seconds.
  - Phone out of range → it ends on time.
  - Lock name hidden or shown per the setting.
- **Early-unlock countdown on the watch** (hourglass icon).
- **Edition switch:** gms → foss → gms keeps profiles, places and settings.

---

## 11. Review log

The plan was re-read three times. Issues found and fixed in this document:

1. **Pass 1:**
   - The first draft gave the Google-free edition its own package name. That would let both editions enforce locks at once. It's now D3 (same ID, recommended) with the risk spelled out (§8.1).
   - The first draft used `BridgingManager` on the watch to stop duplicate notifications. That's another dependency, and it only works once the watch app has run. Replaced with the phone-side `CapabilityClient` check (§4.4), which ships in `play-services-wearable` anyway.
   - The "not locked" state after the end time originally needed a message from the phone. Switched to a **complication timeline** (§4.5), so the watch flips on time even when disconnected.
2. **Pass 2:**
   - Found that `SettingsStore` and `BackupJson` both default the location engine to PLAY_SERVICES. That would break places in the Google-free edition after a restore. Added the edition default (§4.2, §8.3).
   - Found `LocationTriggerManager` exposes `playServices` publicly and compares against it (`activeEngine() === playServices`). It's listed in the change table so the null case is handled.
   - A leftover geofence intent after switching editions → foss ignores it (S6).
3. **Pass 3:**
   - **Signing:** the release build uses this PC's debug key. That silently couples the watch app, edition switching and updates to one file; now D11 with a non-breaking default.
   - The bridged notification needs a *non-ongoing* notification, since today's lock notification is ongoing (`LockNotifications.kt:78`). S0 lets you confirm on your watch before anything is built.
   - The watch app works with the Google-free phone app too (same ID), but gets no data. The status text covers it (§8.11).
   - Removed a planned `kotlinx-coroutines-play-services` dependency; the existing `suspendCancellableCoroutine` pattern is enough.
