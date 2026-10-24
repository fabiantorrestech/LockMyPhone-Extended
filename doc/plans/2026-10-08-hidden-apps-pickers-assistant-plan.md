# M24: hidden apps, pickers, assistant popup, Home redirect, time left: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** during a pass, the camera (hidden from the list), the system pickers and, optionally, the assistant popup no longer relock. Home opens the allowed apps instead of relocking, and the allowed-apps screen shows the time left.

**Architecture:**
- Enforcement stays a pure-Kotlin rule in `LockEngine`, plus the Android-side `ForegroundPolicy` / `LockController`.
- Every new per-lock or global option is saved into `LockSession` when the lock starts (`LockStateStore`, device-protected prefs), so enforcement never reads the settings mid-lock.
- Hidden apps are a subset of the allowed apps. They change only what the picker and the notification list, never enforcement.

**Tech stack:** Kotlin, Jetpack Compose (Material 3, material-icons-extended), Room (hand-written migrations), SharedPreferences. No new dependencies.

**Spec:** `doc/plans/2026-10-08-hidden-apps-pickers-assistant.md`

## Global constraints

- Package: `com.fabiantorrestech.lockmyphone_extended` (paths below are relative to `app/src/main/java/com/fabiantorrestech/lockmyphone_extended/`).
- minSdk 30, target/compileSdk 37.
- No new dependencies.
- **Owner rules:**
  - no tests (verify with a build plus the Pixel 7);
  - no commits unless the owner asks;
  - `git add` only new source files;
  - add each file to `owned_files` in `handoffContext/handoff.md` before editing it.
- **Build:** `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug -q` from the repo root.
- **Install:** `adb -s 29071FDH200AFV install -r app/build/outputs/apk/debug/app-debug.apk`
- **Room:** v25 → v26. The migration is hand-written, and after the bump, grep the generated SQL (`app/build/generated/ksp/debug/java/.../AppDatabase_Impl.java`) for the `profiles` CREATE TABLE so the migration's column matches it exactly.
- **Direct boot:** `SettingsStore` (credential-protected) must not be read before the first unlock. Global options are read through `AppContainer.globalLockOptions()`, which returns the defaults before the first unlock.
- **Defaults:**
  - assistant popup **off**;
  - Home opens **Allowed apps**;
  - **Show time left** on;
  - camera **checked and hidden**;
  - a lock saved before this update reads `homeAction = LOCK`, `hiddenPackages = ∅`, `assistantPopupAllowed = false`.
- Keep the TEMP `ReplyProbe` log in `LockAccessibilityService` (M22.2 still needs it).

## Review focus

1. **Home pressed several times quickly, or the launcher reporting several windows:** exactly one screen should open, with no stack of pickers. This is covered by the 1-second debounce plus `SINGLE_TOP | CLEAR_TOP` (Task 4, Step 4 on the device).
2. **A non-allowed app tapped on the home screen before the redirect lands:** it must relock. That app's own window event goes through the normal RELOCK path (Task 4, Step 4 on the device).
3. **The camera app was already a visible allowed app in a profile:** the one-time seed must **not** hide it (Task 6, `seedCameraOnce` hides only apps that weren't allowed before).
4. **Unchecking a hidden app:** it must also leave `hiddenPackages`, so it doesn't reappear hidden when checked again (Task 7 toggle logic).
5. **The assistant popup opens the full Gemini app or Google Search:** it must relock, because those are activities that don't match the popup's class (Task 3, Step 3 on the device).

---

### Task 1: System pickers always allowed

**Files:**
- Modify: `core/oem/OemProfiles.kt` (`COMMON_ALLOWED`)

- [ ] **Step 1: Add the picker packages to `COMMON_ALLOWED`**

```kotlin
    val COMMON_ALLOWED: Set<String> = setOf(
        "android", // system dialogs, the old share sheet
        "com.android.systemui", // notification shade, volume, keyguard, power menu
        "com.android.server.telecom", // call handling
        "com.android.phone", // emergency dialer, SIM dialogs
        "com.android.emergency", // emergency information
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        // Pickers an allowed app opens ("attach a file", "choose photos", "share"). Opening a file
        // or sharing into another app brings that app to the front, which still relocks.
        "com.google.android.documentsui", "com.android.documentsui", // file picker
        "com.google.android.photopicker", "com.android.photopicker", // photo picker (Android 14+)
        "com.google.android.providers.media.module", "com.android.providers.media.module", // photo picker before 14
        "com.android.intentresolver", // share sheet (Android 14+)
    )
```

- [ ] **Step 2: Build.** Expected: `exit=0`.

---

### Task 2: Session fields (hidden apps, assistant, Home action)

**Files:**
- Modify: `core/lock/LockState.kt` (new `HomeAction`, `GlobalLockOptions`, three `LockSession` fields)
- Modify: `core/lock/LockStateStore.kt` (save/read the three fields)
- Modify: `core/lock/LockEngine.kt` (`start` params)
- Modify: `core/lock/LockController.kt` (`startLock` params)
- Modify: `trigger/ProfileLockStarter.kt` (pass the values; temporary empty hidden set until Task 5)
- Modify: `AppContainer.kt` (`globalLockOptions()`, pass it to `ProfileLockStarter`)

**Interfaces:**
- Produces:
  - `enum class HomeAction { ALLOWED_APPS, COUNTDOWN, LOCK }`;
  - `data class GlobalLockOptions(val assistantPopupAllowed: Boolean = false, val homeAction: HomeAction = HomeAction.ALLOWED_APPS)`;
  - `LockSession.hiddenPackages: Set<String>`, `.assistantPopupAllowed: Boolean`, `.homeAction: HomeAction`;
  - `LockController.startLock(..., hiddenPackages: Set<String>, options: GlobalLockOptions, source)`;
  - `AppContainer.globalLockOptions(): GlobalLockOptions`.

- [ ] **Step 1: `LockState.kt`, add the types under `UnlockResumeMode`**

```kotlin
/** What Home (or the app switcher) does during a pass. */
enum class HomeAction {
    /** Opens the allowed-apps list again. */
    ALLOWED_APPS,

    /** Opens the countdown screen. */
    COUNTDOWN,

    /** Locks the phone (the behaviour before M24). */
    LOCK,
}

/**
 * The global "During a lock" settings, saved into each lock when it starts. Before the first
 * unlock after a boot the settings can't be read, so the defaults apply.
 */
data class GlobalLockOptions(
    val assistantPopupAllowed: Boolean = false,
    val homeAction: HomeAction = HomeAction.ALLOWED_APPS,
)
```

Add these to `LockSession` after `source`, and add KDoc lines for them:

```kotlin
    val source: LockSource = LockSource.UNKNOWN,
    val hiddenPackages: Set<String> = emptySet(),
    val assistantPopupAllowed: Boolean = false,
    val homeAction: HomeAction = HomeAction.LOCK,
```

KDoc lines to add to the `LockSession` block:

```kotlin
 * @param hiddenPackages allowed apps not shown in the lock-screen list or the notification (e.g.
 *   the camera). They're still in [allowedPackages], so enforcement treats them the same.
 * @param assistantPopupAllowed the Gemini / Assistant popup may open during a pass.
 * @param homeAction what Home does during a pass. Locks saved before M24 read [HomeAction.LOCK].
```

- [ ] **Step 2: `LockStateStore.kt`, save and read them**

In `save` (inside `is LockState.Locked ->`), after `putString(KEY_SOURCE_NAME, ...)`:

```kotlin
                    putStringSet(KEY_HIDDEN_PACKAGES, state.session.hiddenPackages)
                    putBoolean(KEY_ASSISTANT_POPUP, state.session.assistantPopupAllowed)
                    putString(KEY_HOME_ACTION, state.session.homeAction.name)
```

In `read`, after `source = readSource(),`:

```kotlin
                hiddenPackages = prefs.getStringSet(KEY_HIDDEN_PACKAGES, null)?.toSet() ?: emptySet(),
                assistantPopupAllowed = prefs.getBoolean(KEY_ASSISTANT_POPUP, false),
                // A lock saved before M24 keeps the old behaviour: Home locks.
                homeAction = prefs.getString(KEY_HOME_ACTION, null)
                    ?.let { saved -> HomeAction.entries.firstOrNull { it.name == saved } }
                    ?: HomeAction.LOCK,
```

In the companion:

```kotlin
        const val KEY_HIDDEN_PACKAGES = "hidden_packages"
        const val KEY_ASSISTANT_POPUP = "assistant_popup"
        const val KEY_HOME_ACTION = "home_action"
```

- [ ] **Step 3: `LockEngine.start`, add the params and pass them into the new `LockSession`**

Add after `source: LockSource,`:

```kotlin
        hiddenPackages: Set<String> = emptySet(),
        options: GlobalLockOptions = GlobalLockOptions(),
```

In the `LockSession(...)` built inside `start`, after `source = source,`:

```kotlin
                // Only allowed apps can be hidden.
                hiddenPackages = hiddenPackages intersect allowedPackages,
                assistantPopupAllowed = options.assistantPopupAllowed,
                homeAction = options.homeAction,
```

KDoc: `@param hiddenPackages allowed apps left out of the lock-screen list.` and `@param options the global "During a lock" settings.`

- [ ] **Step 4: `LockController.startLock`, add the same two params** (after `source: LockSource,`): `hiddenPackages: Set<String>, options: GlobalLockOptions,`. Pass them on as `hiddenPackages = hiddenPackages, options = options,` in the `engine.start(...)` call. KDoc: `@param hiddenPackages allowed apps not listed on the lock screen (e.g. the camera).` and `@param options the global "During a lock" settings, saved into the lock.`

- [ ] **Step 5: `AppContainer.kt`, add `globalLockOptions()` next to `activeOemProfile()`**

```kotlin
    /** The "During a lock" settings for a lock starting now; the defaults before the first unlock after a boot. */
    fun globalLockOptions(): GlobalLockOptions {
        val userUnlocked = appContext.getSystemService(UserManager::class.java).isUserUnlocked
        if (!userUnlocked) return GlobalLockOptions()
        return GlobalLockOptions(
            assistantPopupAllowed = settingsStore.assistantPopupAllowed.value,
            homeAction = settingsStore.homeAction.value,
        )
    }
```

`settingsStore.assistantPopupAllowed` and `.homeAction` only exist after Task 5. So in this task, write the function with its last statement as `return GlobalLockOptions()`, which gives Home → Allowed apps and the assistant off. Task 5, Step 7 swaps in the version above.

Change the `ProfileLockStarter` construction to `ProfileLockStarter(lockController, appCatalog, automationHolds, { settingsStore }, ::globalLockOptions)`.

- [ ] **Step 6: `ProfileLockStarter`, add the constructor param `private val globalOptions: () -> GlobalLockOptions,` after `settingsStore`**

In `start(...)`, add to the `startLock` call:

```kotlin
            hiddenPackages = profile.hiddenPackages, // added in Task 5; use emptySet() until then
            options = globalOptions(),
```

In `startQuick(...)`:

```kotlin
            hiddenPackages = settings.quickHiddenPackages.value, // added in Task 5; use emptySet() until then
            options = globalOptions(),
```

- [ ] **Step 7: Build.** Expected: `exit=0`.

---

### Task 3: Assistant popup rule

**Files:**
- Modify: `core/oem/OemProfiles.kt`
- Modify: `core/enforce/ForegroundPolicy.kt`
- Modify: `core/lock/LockController.kt` (`onForegroundApp`)

**Interfaces:**
- Consumes: `LockSession.assistantPopupAllowed` (Task 2), `OemDetector.isActivity(pkg, cls)` (exists).
- Produces: `OemProfiles.isAssistantPopupClass(packageName, className): Boolean`, `ForegroundPolicy.isAlwaysAllowed(packageName, className, allowAssistantPopup: Boolean = false)`.

- [ ] **Step 1: `OemProfiles`, add these below `COMMON_ALLOWED`**

```kotlin
    /** The Google app, which hosts the Gemini / Assistant popup (power-button hold, corner swipe). */
    const val ASSISTANT_PACKAGE = "com.google.android.googlequicksearchbox"

    /** The popup's activities. Recorded on a Pixel 7 on 2026-10-08: `…floaty.activity.FloatyActivity`. */
    private const val ASSISTANT_POPUP_CLASS_PREFIX =
        "com.google.android.apps.search.assistant.surfaces.voice.robin.ui.floaty."

    fun isAssistantPopupClass(packageName: String, className: String?): Boolean =
        packageName == ASSISTANT_PACKAGE && className?.startsWith(ASSISTANT_POPUP_CLASS_PREFIX) == true
```

- [ ] **Step 2: `ForegroundPolicy.isAlwaysAllowed`, add the param and the rule**

```kotlin
    /**
     * @param allowAssistantPopup the lock allows the Gemini / Assistant popup: its activity, and
     *   the Google app's non-activity windows (the popup's dialogs). The Google app's other
     *   activities (Search, Discover) still relock.
     */
    fun isAlwaysAllowed(packageName: String, className: String?, allowAssistantPopup: Boolean = false): Boolean {
        val profile = activeProfile()
        // An app switcher hosted inside an allowed package (e.g. SystemUI recents) still relocks.
        if (className != null && className in profile.recentsClassNames) return false
        return packageName == context.packageName ||
            packageName in OemProfiles.COMMON_ALLOWED ||
            packageName in profile.alwaysAllowedPackages ||
            packageName in oemDetector.inputMethodPackages() ||
            isAccessibilityOverlay(packageName, className) ||
            (allowAssistantPopup && isAssistantPopup(packageName, className))
    }

    private fun isAssistantPopup(packageName: String, className: String?): Boolean =
        OemProfiles.isAssistantPopupClass(packageName, className) ||
            (packageName == OemProfiles.ASSISTANT_PACKAGE && !oemDetector.isActivity(packageName, className))
```

- [ ] **Step 3: `LockController.onForegroundApp`, pass the session flag.** Replace its first line:

```kotlin
        val session = (store.current as? LockState.Locked)?.session
        val alwaysAllowed = foregroundPolicy.isAlwaysAllowed(
            packageName,
            className,
            allowAssistantPopup = session?.assistantPopupAllowed == true,
        )
```

- [ ] **Step 4: Build.** Expected: `exit=0`. (This is checked on the device in Task 10, since the switch arrives in Task 8.)

---

### Task 4: Home during a pass opens the allowed apps

**Files:**
- Modify: `core/lock/LockEngine.kt` (`ForegroundDecision.REDIRECT`, `decideOnForeground`)
- Modify: `core/lock/LockController.kt` (redirect + debounce + callback)
- Modify: `AppContainer.kt` (set `onRedirectHome`)

**Interfaces:**
- Consumes: `LockSession.homeAction` (Task 2), `ForegroundPolicy.isLauncher(pkg)` (exists), `PassActivity.pickerIntent(context)` (exists).
- Produces: `ForegroundDecision.REDIRECT`, `LockEngine.decideOnForeground(state, nowMs, packageName, isAlwaysAllowed, isLauncher: Boolean)`, `LockController.onRedirectHome: ((HomeAction) -> Unit)?`.

- [ ] **Step 1: `LockEngine`**

```kotlin
/** What to do when a new app window comes to the front while the device is unlocked. */
enum class ForegroundDecision {
    IGNORE,
    RELOCK,

    /** The launcher (Home, app switcher) during a pass: open the allowed apps or countdown instead. */
    REDIRECT,
}
```

```kotlin
    /**
     * Called for each app that comes to the front while the device is unlocked.
     * Without a pass, it ignores the event: the unlock handler already relocks.
     * With a pass, only allowed apps and system components ([isAlwaysAllowed]) may be in
     * front. The launcher (home screen and app switcher) redirects, unless the lock's
     * [HomeAction] is LOCK. Anything else relocks.
     */
    fun decideOnForeground(
        state: LockState,
        nowMs: Long,
        packageName: String,
        isAlwaysAllowed: Boolean,
        isLauncher: Boolean,
    ): ForegroundDecision {
        if (!isActive(state, nowMs)) return ForegroundDecision.IGNORE
        val session = (state as LockState.Locked).session
        if (!session.passActive) return ForegroundDecision.IGNORE
        if (isAlwaysAllowed || packageName in session.allowedPackages) return ForegroundDecision.IGNORE
        if (isLauncher && session.homeAction != HomeAction.LOCK) return ForegroundDecision.REDIRECT
        return ForegroundDecision.RELOCK
    }
```

- [ ] **Step 2: `LockController`**
  - Add the callback next to `onShowLockScreen`:

```kotlin
    /** Opens the allowed apps or the countdown when Home is pressed during a pass. Set by the app container. */
    var onRedirectHome: ((HomeAction) -> Unit)? = null

    /** When the last Home redirect fired; the launcher reports several windows per Home press. */
    private var lastRedirectMs = 0L
```

  - In `onForegroundApp`, pass `isLauncher = foregroundPolicy.isLauncher(packageName)` to `engine.decideOnForeground(...)`.
  - Log the redirect as not a relock, with a note. In the `foregroundLog.add(...)` entry, change `relocked = relock,` to:

```kotlin
                relocked = relock,
                note = if (decision == ForegroundDecision.REDIRECT) "home → ${session?.homeAction}" else null,
```

  - After the existing `if (relock) { ... }` block, add:

```kotlin
        if (decision == ForegroundDecision.REDIRECT) redirectHome(session!!.homeAction)
```

  - Add the private function:

```kotlin
    private fun redirectHome(action: HomeAction) {
        val now = clock()
        if (now - lastRedirectMs < REDIRECT_DEBOUNCE_MS) return
        lastRedirectMs = now
        Log.i(TAG, "Home during a pass; opening $action")
        onRedirectHome?.invoke(action)
    }
```

  - In `LockController`'s companion object (next to `const val RELOCK_GRACE_MS = 1_000L`), add:

```kotlin
        /** The launcher reports several windows per Home press; only the first one redirects. */
        const val REDIRECT_DEBOUNCE_MS = 1_000L
```

- [ ] **Step 3: `AppContainer`, set the callback next to `onShowLockScreen`**

```kotlin
        lockController.onRedirectHome = { action ->
            // Allowed from the background because the accessibility service is bound by the system.
            val intent = when (action) {
                HomeAction.ALLOWED_APPS -> PassActivity.pickerIntent(appContext)
                HomeAction.COUNTDOWN -> Intent(appContext, MainActivity::class.java)
                HomeAction.LOCK -> null
            }
            intent?.let {
                appContext.startActivity(
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
        }
```

- [ ] **Step 4: Build, install and check on the device.** `globalLockOptions()` already returns the default `HomeAction.ALLOWED_APPS` (Task 2), so a **new** lock redirects. A lock started before this install still reads `LOCK`. Then:
  1. Start a lock → Allowed apps → WhatsApp → Home. The allowed-apps list opens.
  2. Press Home 3 times quickly: still one list. Back goes to the countdown.
  3. On the home screen, quickly tap a non-allowed app before the redirect: relocks.
  4. `adb logcat -s LockController`: shows `Home during a pass; opening ALLOWED_APPS`.
  5. ⚠ If nothing opens, look for a `Background activity start` warning in logcat. If you see it, stop and report it, because the redirect needs another route.

---

### Task 5: Data (Room v26, settings, backup)

**Files:**
- Modify: `data/db/LockProfile.kt` (new column)
- Modify: `data/db/AppDatabase.kt` (version 26, `MIGRATION_25_26`, add it to `addMigrations`)
- Modify: `data/SettingsStore.kt` (`LockDefaults.hiddenPackages`, Quick lock hidden, assistant, homeAction, cameraSeeded)
- Modify: `data/backup/BackupJson.kt` (profile + settings fields)
- Modify: `trigger/ProfileLockStarter.kt` (real hidden sets) and `AppContainer.globalLockOptions()` (real body)

**Interfaces:**
- Produces:
  - `LockProfile.hiddenPackages: Set<String>`;
  - `LockDefaults.hiddenPackages: Set<String>`;
  - `SettingsStore.quickHiddenPackages: StateFlow<Set<String>>` and `setQuickHiddenPackages(Set<String>)`;
  - `assistantPopupAllowed: StateFlow<Boolean>` and `setAssistantPopupAllowed(Boolean)`;
  - `homeAction: StateFlow<HomeAction>` and `setHomeAction(HomeAction)`;
  - `cameraSeeded: Boolean` and `setCameraSeeded()`.

- [ ] **Step 1: `LockProfile`**, after `nfcUnlockTagIds`:

```kotlin
    @ColumnInfo(defaultValue = "") val hiddenPackages: Set<String> = emptySet(),
```

KDoc: `@param hiddenPackages allowed apps not shown in the lock-screen list (e.g. the camera); a subset of [allowedPackages]. Added in DB version 26.`

- [ ] **Step 2: `AppDatabase`**
  - Set `version = 26`.
  - Append `MIGRATION_25_26` to `addMigrations(...)`.
  - Add the migration above `MIGRATION_24_25`:

```kotlin
        /** v26: allowed apps can be hidden from the lock-screen list (M24). */
        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN hiddenPackages TEXT NOT NULL DEFAULT ''")
            }
        }
```

  - Build, then check that the generated schema matches:
    `grep -o "CREATE TABLE IF NOT EXISTS \`profiles\`[^\"]*" app/build/generated/ksp/debug/java/com/fabiantorrestech/lockmyphone_extended/data/db/AppDatabase_Impl.java`
    It must contain `` `hiddenPackages` TEXT NOT NULL DEFAULT '' ``.

- [ ] **Step 3: `SettingsStore`, `LockDefaults`**
  - Add `val hiddenPackages: Set<String> = emptySet(),` after `allowedPackages`.
  - Map it in `toProfileDraft` (`hiddenPackages = hiddenPackages,`) and in `fromProfile` (`hiddenPackages = profile.hiddenPackages,`).
  - In `setLockDefaults`, add `putStringSet(KEY_DEFAULT_HIDDEN_PACKAGES, defaults.hiddenPackages)`.
  - In `readLockDefaults`, add `hiddenPackages = prefs.getStringSet(KEY_DEFAULT_HIDDEN_PACKAGES, null)?.toSet() ?: emptySet(),`.
  - In `resetQuickToDefaults`, add `setQuickHiddenPackages(defaults.hiddenPackages)` after `setAllowedPackages(...)`.

- [ ] **Step 4: `SettingsStore`, add the new settings** after `setAllowedPackages`:

```kotlin
    /** Quick lock's allowed apps that aren't shown in the lock-screen list (e.g. the camera). */
    private val _quickHiddenPackages = MutableStateFlow(
        prefs.getStringSet(KEY_QUICK_HIDDEN_PACKAGES, null)?.toSet() ?: _lockDefaults.value.hiddenPackages
    )
    val quickHiddenPackages: StateFlow<Set<String>> = _quickHiddenPackages.asStateFlow()

    fun setQuickHiddenPackages(packages: Set<String>) {
        prefs.edit { putStringSet(KEY_QUICK_HIDDEN_PACKAGES, packages) }
        _quickHiddenPackages.value = packages
    }

    /** During a lock: the Gemini / Assistant popup may open in an allowed app. Off by default. */
    private val _assistantPopupAllowed = MutableStateFlow(prefs.getBoolean(KEY_ASSISTANT_POPUP, false))
    val assistantPopupAllowed: StateFlow<Boolean> = _assistantPopupAllowed.asStateFlow()

    fun setAssistantPopupAllowed(allowed: Boolean) {
        prefs.edit { putBoolean(KEY_ASSISTANT_POPUP, allowed) }
        _assistantPopupAllowed.value = allowed
    }

    /** During a lock: what Home does. Opens the allowed apps by default. */
    private val _homeAction = MutableStateFlow(
        prefs.getString(KEY_HOME_ACTION, null)
            ?.let { saved -> HomeAction.entries.firstOrNull { it.name == saved } }
            ?: HomeAction.ALLOWED_APPS
    )
    val homeAction: StateFlow<HomeAction> = _homeAction.asStateFlow()

    fun setHomeAction(action: HomeAction) {
        prefs.edit { putString(KEY_HOME_ACTION, action.name) }
        _homeAction.value = action
    }

    /** True once the camera was added (hidden) to every allowed-apps list ([AppContainer.seedCameraOnce]). */
    val cameraSeeded: Boolean get() = prefs.getBoolean(KEY_CAMERA_SEEDED, false)

    fun setCameraSeeded() {
        prefs.edit { putBoolean(KEY_CAMERA_SEEDED, true) }
    }
```

  - Companion constants:

```kotlin
        const val KEY_DEFAULT_HIDDEN_PACKAGES = "default_hidden_packages"
        const val KEY_QUICK_HIDDEN_PACKAGES = "quick_hidden_packages"
        const val KEY_ASSISTANT_POPUP = "assistant_popup"
        const val KEY_HOME_ACTION = "home_action"
        const val KEY_CAMERA_SEEDED = "camera_seeded"
```

  - Add `"camera_seeded"` to `BACKUP_SETTING_KEYS`, since it isn't part of a backup.
  - Import `com.fabiantorrestech.lockmyphone_extended.core.lock.HomeAction` in both `SettingsStore.kt` and `BackupJson.kt`.

- [ ] **Step 5: `SettingsSnapshot` + `snapshot()` / `restore()`**
  - Fields: `val quickHiddenPackages: Set<String>`, `val assistantPopupAllowed: Boolean`, `val homeAction: HomeAction`.
  - In `snapshot()`, add `quickHiddenPackages = quickHiddenPackages.value, assistantPopupAllowed = assistantPopupAllowed.value, homeAction = homeAction.value,`.
  - In `restore()`, after `setAllowedPackages(...)`, add `setQuickHiddenPackages(s.quickHiddenPackages)`. At the end, add `setAssistantPopupAllowed(s.assistantPopupAllowed)` and `setHomeAction(s.homeAction)`.

- [ ] **Step 6: `BackupJson`**
  - In `settingsToJson`, add `put("quickHiddenPackages", strings(s.quickHiddenPackages))`, `put("assistantPopupAllowed", s.assistantPopupAllowed)` and `put("homeAction", s.homeAction.name)`. Inside `lockDefaults`, add `put("hiddenPackages", strings(d.hiddenPackages))`.
  - In `settingsFromJson`, add:
    - `quickHiddenPackages = stringSet(o, "quickHiddenPackages"),`
    - `assistantPopupAllowed = o.optBoolean("assistantPopupAllowed", false),`
    - `homeAction = enumOr(o.optString("homeAction"), HomeAction.ALLOWED_APPS),`
    - in `LockDefaults(...)`: `hiddenPackages = stringSet(d, "hiddenPackages"),`
  - `profileToJson`: `put("hiddenPackages", strings(p.hiddenPackages))`. `profileFromJson`: `hiddenPackages = stringSet(o, "hiddenPackages"),`.
  - Old backups have none of these keys, so they read as empty / defaults.

- [ ] **Step 7: Use the real values.**
  - `ProfileLockStarter.start`: `hiddenPackages = profile.hiddenPackages,`.
  - `startQuick`: `hiddenPackages = settings.quickHiddenPackages.value,`.
  - `AppContainer.globalLockOptions()`: restore the full body from Task 2, Step 5.

- [ ] **Step 8: Build, install over the existing app** (keeps data) **and check.**
  - Open the app: the profiles, schedules and places are all still listed.
  - `adb -s 29071FDH200AFV logcat -d | grep -iE "IllegalStateException|Migration"` shows no Room error.

---

### Task 6: Camera seed (one time)

**Files:**
- Modify: `core/apps/AppCatalog.kt` (`defaultCameraPackage()`)
- Modify: `app/src/main/AndroidManifest.xml` (`<queries>` IMAGE_CAPTURE)
- Modify: `AppContainer.kt` (`seedCameraOnce()`)
- Modify: `MainActivity.kt` (call it after `ensureDefaultProfile()`)

**Interfaces:**
- Produces: `AppCatalog.defaultCameraPackage(): String?`, `AppContainer.seedCameraOnce()` (suspend).

- [ ] **Step 1: `AppCatalog`**, next to `defaultDialerPackage()`:

```kotlin
    /** The app that takes a photo for other apps ("take a photo" in WhatsApp), or null if there's none. */
    fun defaultCameraPackage(): String? =
        context.packageManager
            .resolveActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
            // "android" means several cameras and no default (the chooser would be shown).
            ?.takeIf { it != "android" }
```

Imports: `android.provider.MediaStore`, and `android.content.pm.PackageManager` if it's missing.

- [ ] **Step 2: Manifest `<queries>`**, add:

```xml
        <intent>
            <action android:name="android.media.action.IMAGE_CAPTURE" />
        </intent>
```

- [ ] **Step 3: `AppContainer.seedCameraOnce()`**, below `ensureDefaultProfile()`:

```kotlin
    /**
     * Once per install (and once after the M24 update): the camera becomes an allowed, hidden app in
     * every profile, Quick lock and Lock defaults, so taking a photo from an allowed app doesn't
     * relock. A list that already allowed the camera keeps it visible. Needs the user to have
     * unlocked since boot.
     */
    suspend fun seedCameraOnce() {
        if (settingsStore.cameraSeeded) return
        val camera = appCatalog.defaultCameraPackage()
        if (camera != null) {
            val dao = database.profileDao()
            dao.getAll().forEach { profile ->
                dao.update(
                    profile.copy(
                        allowedPackages = profile.allowedPackages + camera,
                        hiddenPackages = hideIfNew(profile.allowedPackages, profile.hiddenPackages, camera),
                    )
                )
            }
            val store = settingsStore
            store.setQuickHiddenPackages(hideIfNew(store.allowedPackages.value, store.quickHiddenPackages.value, camera))
            store.setAllowedPackages(store.allowedPackages.value + camera)
            val defaults = store.lockDefaults.value
            store.setLockDefaults(
                defaults.copy(
                    allowedPackages = defaults.allowedPackages + camera,
                    hiddenPackages = hideIfNew(defaults.allowedPackages, defaults.hiddenPackages, camera),
                )
            )
        }
        settingsStore.setCameraSeeded()
    }

    /** [hidden] plus [app], unless [app] was already allowed (then the user chose to see it). */
    private fun hideIfNew(allowed: Set<String>, hidden: Set<String>, app: String): Set<String> =
        if (app in allowed) hidden else hidden + app
```

- [ ] **Step 4: `MainActivity.onCreate`**, change the line to `lifecycleScope.launch { container.ensureDefaultProfile(); container.seedCameraOnce() }`.

- [ ] **Step 5: Build, install and open the app once.** Then start a lock with a profile, open WhatsApp → camera → take a photo: no relock. Logcat shows no `Left allowed apps (foreground=com.google.android.GoogleCamera…)`.

---

### Task 7: Hide toggle in the allowed-apps lists + counts

**Files:**
- Modify: `ui/AllowedAppsScreen.kt` (signature, eye toggle, hint)
- Modify: `MainActivity.kt` (the three `AllowedAppsScreen` call sites, Quick lock count)
- Modify: `ui/LockSettingsSections.kt` (`LockSettings.hiddenAppCount`, summary)
- Modify: `ui/ProfileEditorScreen.kt` (`toLockSettings`)
- Modify: `ui/Formatting.kt` (`formatProfileSummary`)
- Modify: `res/values/strings.xml`

**Interfaces:**
- Produces: `AllowedAppsScreen(loadApps, iconProvider, selected: Set<String>, hidden: Set<String>, onSelectionChange: (selected: Set<String>, hidden: Set<String>) -> Unit, onBack, modifier)`, `LockSettings.hiddenAppCount: Int = 0`.

- [ ] **Step 1: `AllowedAppsScreen`**
  - Add the param `hidden: Set<String>,` after `selected`.
  - Change `onSelectionChange` to `(selected: Set<String>, hidden: Set<String>) -> Unit`.
  - KDoc: `Checked apps can be opened from the lock screen during a lock; 👁 hides a checked app from that list (it stays allowed).`
  - Under the `allowed_phone_note` text, add a second note: `Text(stringResource(R.string.allowed_hide_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))`.
  - Pass `hidden` to both `appItems(...)` calls.

```kotlin
/** A checkbox row per app, with 👁 to hide a checked app from the lock-screen list. */
private fun LazyListScope.appItems(
    apps: List<LaunchableApp>,
    selected: Set<String>,
    hidden: Set<String>,
    onSelectionChange: (Set<String>, Set<String>) -> Unit,
    iconProvider: AppIconProvider,
) {
    items(apps, key = { it.packageName }, contentType = { "app" }) { app ->
        val packageName = app.packageName
        val checked = packageName in selected
        val isHidden = packageName in hidden
        val toggle = {
            // Unchecking also un-hides, so checking it again starts visible.
            if (checked) onSelectionChange(selected - packageName, hidden - packageName)
            else onSelectionChange(selected + packageName, hidden)
        }
        AppRow(app, iconProvider, onClick = toggle) {
            if (checked) {
                IconButton(onClick = {
                    onSelectionChange(selected, if (isHidden) hidden - packageName else hidden + packageName)
                }) {
                    Icon(
                        if (isHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = stringResource(if (isHidden) R.string.allowed_hidden else R.string.allowed_shown),
                    )
                }
            }
            Checkbox(checked = checked, onCheckedChange = { toggle() })
        }
    }
}
```

Imports: `androidx.compose.material.icons.Icons`, `.filled.Visibility`, `.filled.VisibilityOff`, `androidx.compose.material3.Icon`, `IconButton`.

- [ ] **Step 2: `MainActivity` call sites**
  - `Screen.QUICK_APPS`: `selected = quickApps, hidden = quickHidden, onSelectionChange = { apps, hidden -> container.settingsStore.setAllowedPackages(apps); container.settingsStore.setQuickHiddenPackages(hidden) },`. Add `val quickHidden by container.settingsStore.quickHiddenPackages.collectAsState()` next to `quickApps`.
  - `Screen.DEFAULT_APPS`: `selected = draft.allowedPackages, hidden = draft.hiddenPackages, onSelectionChange = { apps, hidden -> defaultsDraft = draft.copy(allowedPackages = apps, hiddenPackages = hidden) },`
  - `Screen.PROFILE_APPS`: `selected = draft.allowedPackages, hidden = draft.hiddenPackages, onSelectionChange = { apps, hidden -> profileDraft = draft.copy(allowedPackages = apps, hiddenPackages = hidden) },`
  - In `quickLock = LockSettings(...)`: `allowedAppCount = (quickApps - quickHidden).size, hiddenAppCount = (quickHidden intersect quickApps).size,`

- [ ] **Step 3: Counts**
  - `LockSettings`: add `val hiddenAppCount: Int = 0,` after `allowedAppCount`.
  - `LockSettingsSections` summary: insert `(if (settings.hiddenAppCount > 0) " · " + stringResource(R.string.apps_hidden_count, settings.hiddenAppCount) else "") +` before `" · " + stringResource(R.string.profile_phone_always)`.
  - `ProfileEditorScreen.toLockSettings`: `allowedAppCount = (allowedPackages - hiddenPackages).size, hiddenAppCount = (hiddenPackages intersect allowedPackages).size,`.
  - `Formatting.formatProfileSummary`: count only the visible apps, then add the hidden count:

```kotlin
fun formatProfileSummary(context: Context, profile: LockProfile): String {
    val visibleCount = (profile.allowedPackages - profile.hiddenPackages).size
    val hiddenCount = (profile.hiddenPackages intersect profile.allowedPackages).size
    val parts = mutableListOf(
        formatDurationLabel(profile.defaultDurationSec * 1_000),
        context.resources.getQuantityString(R.plurals.apps_count, visibleCount, visibleCount),
    )
    if (hiddenCount > 0) parts += context.getString(R.string.apps_hidden_count, hiddenCount)
    // … the rest of the function is unchanged (bypass, DND, grayscale, …)
```

- [ ] **Step 4: Strings**

```xml
    <string name="allowed_hide_note">👁 hides a checked app from the lock-screen list; it stays allowed (e.g. the camera, used from inside other apps).</string>
    <string name="allowed_hidden">Hidden from the lock-screen list</string>
    <string name="allowed_shown">Shown in the lock-screen list</string>
    <string name="apps_hidden_count">%1$d hidden</string>
```

- [ ] **Step 5: Build, install and check.**
  - Profile → Allowed apps: the camera is checked with 👁-off.
  - Unchecking it and checking it again leaves it shown.
  - The profile summary reads "N allowed apps · 1 hidden".

---

### Task 8: Picker and notification skip hidden apps; time left; "During a lock" card

**Files:**
- Modify: `pass/PassActivity.kt` (filter hidden, time left)
- Modify: `core/notify/LockNotifications.kt` (Allowed apps button)
- Modify: `data/PickerStyle.kt` (`showTimeLeft`)
- Modify: `data/SettingsStore.kt`, `data/backup/BackupJson.kt` (`showTimeLeft`)
- Modify: `ui/PickerAppearance.kt` (`TimeLeft`, switch)
- Modify: `ui/HomeScreen.kt` (`DuringLockCard`, params), `MainActivity.kt` (wiring)
- Modify: `res/values/strings.xml`

**Interfaces:**
- Consumes: `LockSession.hiddenPackages`, `SettingsStore.assistantPopupAllowed/homeAction` (Task 5), `formatDurationClock(ms)` (exists, `ui/DurationFormat.kt`).
- Produces: `PickerStyle.showTimeLeft: Boolean = true`, `@Composable TimeLeft(endMs: Long, style: PickerStyle, fontFamily: FontFamily, modifier: Modifier = Modifier)`.

- [ ] **Step 1: `PassActivity.showPicker`**, change the apps filter to `.filter { it != dialer && it !in state.session.hiddenPackages }`.
  - Replace the clock line with a row:

```kotlin
                        val lockNow by container.lockController.state.collectAsState()
                        val endMs = (lockNow as? LockState.Locked)?.session?.effectiveEndMs
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            // Pinned above the list, so it stays in the corner while the apps scroll.
                            PickerClock(pickerStyle, font, Modifier.weight(1f))
                            if (pickerStyle.showTimeLeft && endMs != null) TimeLeft(endMs, pickerStyle, font)
                        }
```

  - Imports: `Row`, `ui.TimeLeft`.

- [ ] **Step 2: `LockNotifications`**, change the condition to `if (!refreshable && session.allowedPackages.any { it != dialer && it !in session.hiddenPackages }) {`.

- [ ] **Step 3: `PickerStyle`**
  - Add `val showTimeLeft: Boolean = true,` with the KDoc `/** Show the time left in the top-right of the allowed-apps list. */`.
  - `SettingsStore`: read `showTimeLeft = prefs.getBoolean(KEY_PICKER_SHOW_TIME_LEFT, true),`, write `putBoolean(KEY_PICKER_SHOW_TIME_LEFT, clamped.showTimeLeft)`, and add the constant `KEY_PICKER_SHOW_TIME_LEFT = "picker_show_time_left"`.
  - `BackupJson`: `put("pickerShowTimeLeft", s.pickerStyle.showTimeLeft)` and `showTimeLeft = o.optBoolean("pickerShowTimeLeft", true),`.

- [ ] **Step 4: `PickerAppearance.kt`, add `TimeLeft`** below `PickerClock`:

```kotlin
/** "0:47:12 left", ticking each second, for the top-right of the allowed-apps list. */
@Composable
fun TimeLeft(endMs: Long, style: PickerStyle, fontFamily: FontFamily, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endMs) {
        while (now < endMs) {
            delay(1_000 - System.currentTimeMillis() % 1_000)
            now = System.currentTimeMillis()
        }
    }
    val sizeSp = style.clockSizeSp * DATE_SIZE_RATIO
    Text(
        stringResource(R.string.picker_time_left, formatDurationClock((endMs - now).coerceAtLeast(0))),
        fontSize = sizeSp.sp,
        lineHeight = (sizeSp * 1.2f).sp,
        fontFamily = fontFamily,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
```

  - In `PickerAppearanceSection`, under the status-bar switch, add:

```kotlin
        SwitchRow(stringResource(R.string.picker_show_time_left), style.showTimeLeft) {
            appearance.onStyleChange(style.copy(showTimeLeft = it))
        }
```

- [ ] **Step 5: `HomeScreen`, add the "During a lock" card**
  - Add the params after `pickerAppearance`: `assistantPopupAllowed: Boolean, onAssistantPopupChange: (Boolean) -> Unit, homeAction: HomeAction, onHomeActionChange: (HomeAction) -> Unit,`.
  - Show the card right before `AppearanceCard(...)`: `DuringLockCard(assistantPopupAllowed, onAssistantPopupChange, homeAction, onHomeActionChange)`.

```kotlin
/** Global rules for every lock: what Home does during a pass, and the assistant popup. */
@Composable
private fun DuringLockCard(
    assistantPopupAllowed: Boolean,
    onAssistantPopupChange: (Boolean) -> Unit,
    homeAction: HomeAction,
    onHomeActionChange: (HomeAction) -> Unit,
) {
    var choosingHome by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                stringResource(R.string.during_lock_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SettingsRow(
                icon = Icons.Filled.Home,
                title = stringResource(R.string.home_action_title),
                summary = stringResource(homeActionLabel(homeAction)),
                onClick = { choosingHome = true },
            )
            SwitchSettingsRow(
                icon = Icons.Filled.Assistant,
                title = stringResource(R.string.assistant_popup_title),
                summary = stringResource(R.string.assistant_popup_hint),
                checked = assistantPopupAllowed,
                onCheckedChange = onAssistantPopupChange,
            )
        }
    }
    if (choosingHome) {
        ChoiceDialog(
            title = stringResource(R.string.home_action_title),
            options = HomeAction.entries,
            selected = homeAction,
            label = { stringResource(homeActionLabel(it)) },
            onSelect = {
                onHomeActionChange(it)
                choosingHome = false
            },
            onDismiss = { choosingHome = false },
        )
    }
}

private fun homeActionLabel(action: HomeAction): Int = when (action) {
    HomeAction.ALLOWED_APPS -> R.string.home_action_allowed_apps
    HomeAction.COUNTDOWN -> R.string.home_action_countdown
    HomeAction.LOCK -> R.string.home_action_lock
}
```

  - Imports: `Icons.Filled.Home`, `Icons.Filled.Assistant`, `core.lock.HomeAction`.

- [ ] **Step 6: `MainActivity` wiring** (in the `HomeScreen(...)` call, after `pickerAppearance = ...`):

```kotlin
                assistantPopupAllowed = container.settingsStore.assistantPopupAllowed.collectAsState().value,
                onAssistantPopupChange = container.settingsStore::setAssistantPopupAllowed,
                homeAction = container.settingsStore.homeAction.collectAsState().value,
                onHomeActionChange = container.settingsStore::setHomeAction,
```

- [ ] **Step 7: Strings**

```xml
    <string name="picker_time_left">%1$s left</string>
    <string name="picker_show_time_left">Show the time left (top right)</string>
    <string name="during_lock_title">During a lock</string>
    <string name="home_action_title">Home opens…</string>
    <string name="home_action_allowed_apps">Allowed apps</string>
    <string name="home_action_countdown">Countdown screen</string>
    <string name="home_action_lock">Lock the phone</string>
    <string name="assistant_popup_title">Allow the Gemini / Assistant popup</string>
    <string name="assistant_popup_hint">Only the popup from the power button or a corner swipe. The full Google and Gemini apps still lock the phone. Applies to locks started after you change it.</string>
```

- [ ] **Step 8: Build, install and check.**
  - The picker shows "0:MM:SS left" top-right, ticking.
  - The camera isn't in the picker.
  - The notification has no "Allowed apps" button when only the dialer and hidden apps are allowed.
  - Settings shows the "During a lock" card.

---

### Task 9: Docs

**Files:**
- Modify: `README.md` (feature list: hidden apps, pickers, assistant popup, Home opens…, time left)
- Modify: `handoffContext/test-checklist.md` (T92–T97 from spec §10; not committed)
- Modify: `handoffContext/handoff.md` (M24 summary; not committed)

- [ ] **Step 1:** Add a short "During a lock" subsection to the README's features. Follow its existing style: plain bullets, no marketing.
- [ ] **Step 2:** Add T92–T97 to the checklist, worded as in spec §10.

---

### Task 10: Device verification (Pixel 7, owner)

- [ ] **Step 1:** `adb -s 29071FDH200AFV logcat -v time -s LockController ReplyProbe > <scratchpad>/m24.log` in the background.
- [ ] **Step 2:** The owner runs T92–T97 (spec §10).
- [ ] **Step 3:** Read the log. For every `Left allowed apps` line, confirm it's an expected relock. For the assistant, if the popup's window class differs from `…floaty.`, update `ASSISTANT_POPUP_CLASS_PREFIX`.
- [ ] **Step 4:** Report the results. Commit only when the owner asks.
