# M25: unlock → countdown, warning popup for not-allowed apps: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:**
- Every unlock during a lock opens the countdown screen, which locks after the choice time.
- During a pass, a not-allowed app is covered by a popup that can't be bypassed, with Go back, configurable text, vibration and max brightness.

**Architecture:**
- `LockEngine` gets a new `ForegroundDecision.WARN`.
- `LockController` shows or hides the popup through callbacks that the container wires to `LockAccessibilityService`. The popup is a `TYPE_ACCESSIBILITY_OVERLAY` window built with classic Views in the new `core/enforce/WarningOverlay.kt`.
- The unlock countdown reuses the NFC gate's flow, generalised into `openChoiceScreen()`.
- The new options are saved into `LockSession` when the lock starts, like M24's.

**Tech stack:** Kotlin, Android Views (the overlay), Jetpack Compose (settings), Room (hand-written migration v26 → v27), SharedPreferences. No new dependencies; one normal permission (`VIBRATE`).

**Spec:** `doc/plans/2026-10-08-unlock-countdown-and-warning-popup.md` · **ADR:** `doc/adr/0007-unlocks-open-the-countdown-and-not-allowed-apps-get-a-warning-overlay.md`

## Global constraints

- Paths are relative to `app/src/main/java/com/fabiantorrestech/lockmyphone_extended/` unless they start with `app/`.
- **Owner rules:**
  - no tests;
  - no commits unless the owner asks;
  - `git add` only new source files;
  - claim files in `handoffContext/handoff.md` before editing them.
- **Build:** `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug -q` from the repo root. Expected: exit 0, no `e:` lines.
- **Install:** `adb -s 29071FDH200AFV install -r app/build/outputs/apk/debug/app-debug.apk`.
- **Room:** v26 → v27, hand-written. After the build, grep `app/build/generated/ksp/debug/kotlin/.../AppDatabase_Impl.kt` for `` `warningText` TEXT `` (nullable, so there's no `NOT NULL`).
- **Defaults:**
  - warning text empty, which shows `R.string.warning_default_text`: "This app is not allowed in the current lock (LockMyPhone - Extended).";
  - vibrate on;
  - max brightness on;
  - per-lock text null (use the global text).
- **Fail safe:** if the popup can't be added, or the countdown can't take a pass (the accessibility service isn't bound), the phone locks.
- The popup must hide on screen off, on relock and when the lock ends. Accessibility overlays draw above the keyguard.

## Review focus

1. **The popup left over the lock screen** after a screen-off, relock or lock end. It must never show on the keyguard (Task 4: `hideWarning()` on every one of those paths).
2. **Our own popup's window events** being treated as "our app in front". That would hide the popup straight away, or resume a paused pass into the not-allowed app (Task 4: ignore `WarningOverlay.VIEW_CLASS_NAME`).
3. **Tapping an allowed app on the lock screen** must still open it directly, with no countdown flash. The countdown waits for the 1 s grace, which a pass cancels (Task 1).
4. **The countdown opening while the accessibility service isn't bound** would give a pass that can't be enforced. It must lock instead (Task 1: `openChoiceScreen` checks `LockAccessibilityService.isRunning`).
5. **The notification shade or keyboard over the popup** must not hide it. Only an allowed app, our app (not the popup) or a Home redirect hides it (Task 4 hide rule).

---

### Task 1: Every unlock opens the countdown

**Files:**
- Modify: `core/lock/LockController.kt` (`pendingRelock`, `tryOpenGate` → `openChoiceScreen`, `onUserUnlocked` KDoc)

**Interfaces:**
- Produces: `private fun openChoiceScreen(seconds: Int, gate: Boolean): Boolean`.

- [ ] **Step 1: Replace `tryOpenGate` with a shared `openChoiceScreen` plus a thin `tryOpenGate`**

```kotlin
    /**
     * Opens the countdown screen for [seconds] with a pass for our own screen, so Allowed apps and
     * Phone work there; doing nothing relocks ([choiceRelock]). With [gate], the NFC reader is on
     * too. Returns false (relock as usual) when no lock is active or passes can't be enforced.
     */
    private fun openChoiceScreen(seconds: Int, gate: Boolean): Boolean {
        if (!LockAccessibilityService.isRunning) return false
        val current = store.current as? LockState.Locked ?: return false
        val opened = engine.grantPickerPass(current, clock()) ?: return false
        store.save(opened)
        val delayMs = seconds * 1_000L
        _choiceDeadlineMs.value = clock() + delayMs
        _gateActive.value = gate
        mainHandler.removeCallbacks(choiceRelock)
        mainHandler.postDelayed(choiceRelock, delayMs)
        onShowLockScreen?.invoke()
        return true
    }

    /** The NFC gate: the countdown with the reader on, when a tag could act on this lock. */
    private fun tryOpenGate(): Boolean {
        val current = store.current as? LockState.Locked ?: return false
        val seconds = nfcGateSeconds?.invoke(current.session) ?: return false
        if (!openChoiceScreen(seconds, gate = true)) return false
        Log.i(TAG, "Unlocked during a lock; NFC gate open for $seconds s")
        return true
    }
```

- [ ] **Step 2: `pendingRelock` opens the countdown instead of locking**

```kotlin
    /**
     * After an unlock's grace period with no pass: the countdown screen, which locks after the
     * lock's choice time unless an app is picked (ADR 0007). If it can't open, lock now.
     */
    private val pendingRelock = Runnable {
        if (engine.decideOnUnlock(store.current, clock()) != UnlockDecision.RELOCK) return@Runnable
        val seconds = (store.current as LockState.Locked).session.unlockChoiceSeconds
        if (openChoiceScreen(seconds, gate = false)) {
            Log.i(TAG, "Unlocked during a lock; countdown for $seconds s")
        } else {
            Log.i(TAG, "Grace period over without a pass; relocking")
            relocker.lockNow()
        }
    }
```

- [ ] **Step 3: In the `onUserUnlocked` KDoc, change "The relock waits a short grace period." to:**
  "After a short grace period it opens the countdown screen, which locks after the lock's choice time (ADR 0007)."

- [ ] **Step 4: Build.** Expected: exit 0.

---

### Task 2: Data: warning text, vibrate, brightness (session, settings, DB v27, backup)

**Files:**
- Modify: `core/lock/LockState.kt` (`GlobalLockOptions`, `LockSession`)
- Modify: `core/lock/LockStateStore.kt`, `core/lock/LockEngine.kt` (`start`)
- Modify: `data/db/LockProfile.kt`, `data/db/AppDatabase.kt` (v27)
- Modify: `data/SettingsStore.kt` (global + Quick lock + `LockDefaults`)
- Modify: `data/backup/BackupJson.kt`
- Modify: `trigger/ProfileLockStarter.kt`, `AppContainer.kt` (`globalLockOptions`)
- Modify: `app/src/main/AndroidManifest.xml` (`VIBRATE`), `app/src/main/res/values/strings.xml`

**Interfaces:**
- Produces:
  - `GlobalLockOptions.warningText: String = ""`, `.warningVibrate: Boolean = true`, `.warningMaxBrightness: Boolean = true`;
  - `LockSession.warningText: String = ""`, `.warningVibrate`, `.warningMaxBrightness`;
  - `LockProfile.warningText: String?`;
  - `LockDefaults.warningText: String? = null`;
  - `SettingsStore.warningText: StateFlow<String>` + `setWarningText(String)`;
  - `warningVibrate` + `setWarningVibrate(Boolean)`;
  - `warningMaxBrightness` + `setWarningMaxBrightness(Boolean)`;
  - `quickWarningText: StateFlow<String?>` + `setQuickWarningText(String?)`.

- [ ] **Step 1: `GlobalLockOptions`**, add after `homeAction`:

```kotlin
    /** The warning popup's text; empty = the default text. */
    val warningText: String = "",
    val warningVibrate: Boolean = true,
    val warningMaxBrightness: Boolean = true,
```

`LockSession`, add after `homeAction`, and add KDoc lines for the three fields:

```kotlin
    val warningText: String = "",
    val warningVibrate: Boolean = true,
    val warningMaxBrightness: Boolean = true,
```

KDoc lines:

```kotlin
 * @param warningText the popup's text over a not-allowed app during a pass; empty = the default text.
 * @param warningVibrate vibrate while the popup shows.
 * @param warningMaxBrightness full brightness while the popup shows.
```

- [ ] **Step 2: `LockStateStore`.** In `save`, add `putString(KEY_WARNING_TEXT, s.warningText)`, `putBoolean(KEY_WARNING_VIBRATE, s.warningVibrate)` and `putBoolean(KEY_WARNING_BRIGHTNESS, s.warningMaxBrightness)`, writing `state.session.` for `s.`. In `read`, add:

```kotlin
                warningText = prefs.getString(KEY_WARNING_TEXT, null).orEmpty(),
                warningVibrate = prefs.getBoolean(KEY_WARNING_VIBRATE, true),
                warningMaxBrightness = prefs.getBoolean(KEY_WARNING_BRIGHTNESS, true),
```

Constants: `KEY_WARNING_TEXT = "warning_text"`, `KEY_WARNING_VIBRATE = "warning_vibrate"`, `KEY_WARNING_BRIGHTNESS = "warning_brightness"`.

- [ ] **Step 3: `LockEngine.start`.** In the `LockSession(...)`, after `homeAction = options.homeAction,`:

```kotlin
                warningText = options.warningText,
                warningVibrate = options.warningVibrate,
                warningMaxBrightness = options.warningMaxBrightness,
```

- [ ] **Step 4: `LockProfile`.** After `hiddenPackages`:

```kotlin
    val warningText: String? = null,
```

KDoc: `@param warningText this lock's warning popup text, or null for the global text. Added in DB version 27.`

`AppDatabase`:
  - set `version = 27`;
  - add `MIGRATION_26_27` to `addMigrations(...)`;
  - add the migration above `MIGRATION_25_26`:

```kotlin
        /** v27: a lock can have its own warning popup text (M25). */
        private val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN warningText TEXT")
            }
        }
```

- [ ] **Step 5: `SettingsStore`**
  - **`LockDefaults`:** add `val warningText: String? = null,` (KDoc: `/** This lock's warning text, or null for the global text. */`). Map it in `toProfileDraft` (`warningText = warningText,`) and `fromProfile` (`warningText = profile.warningText,`).
  - **`setLockDefaults`:** `putString(KEY_DEFAULT_WARNING_TEXT, defaults.warningText)`.
  - **`readLockDefaults`:** `warningText = prefs.getString(KEY_DEFAULT_WARNING_TEXT, null),`.
  - **`resetQuickToDefaults`:** `setQuickWarningText(defaults.warningText)`.
  - Add after `setHomeAction`:

```kotlin
    /** During a lock: the warning popup's text; empty = the default text. */
    private val _warningText = MutableStateFlow(prefs.getString(KEY_WARNING_TEXT, null).orEmpty())
    val warningText: StateFlow<String> = _warningText.asStateFlow()

    fun setWarningText(text: String) {
        prefs.edit { putString(KEY_WARNING_TEXT, text) }
        _warningText.value = text
    }

    /** During a lock: vibrate while the warning popup shows. On by default. */
    private val _warningVibrate = MutableStateFlow(prefs.getBoolean(KEY_WARNING_VIBRATE, true))
    val warningVibrate: StateFlow<Boolean> = _warningVibrate.asStateFlow()

    fun setWarningVibrate(vibrate: Boolean) {
        prefs.edit { putBoolean(KEY_WARNING_VIBRATE, vibrate) }
        _warningVibrate.value = vibrate
    }

    /** During a lock: full brightness while the warning popup shows. On by default. */
    private val _warningMaxBrightness = MutableStateFlow(prefs.getBoolean(KEY_WARNING_BRIGHTNESS, true))
    val warningMaxBrightness: StateFlow<Boolean> = _warningMaxBrightness.asStateFlow()

    fun setWarningMaxBrightness(max: Boolean) {
        prefs.edit { putBoolean(KEY_WARNING_BRIGHTNESS, max) }
        _warningMaxBrightness.value = max
    }

    /**
     * Quick lock's own warning text, or null for the global text. Stored as "" for "use the global
     * text", so clearing it doesn't fall back to the Lock defaults' text.
     */
    private val _quickWarningText = MutableStateFlow(
        if (prefs.contains(KEY_QUICK_WARNING_TEXT)) {
            prefs.getString(KEY_QUICK_WARNING_TEXT, null)?.takeIf { it.isNotEmpty() }
        } else {
            _lockDefaults.value.warningText
        }
    )
    val quickWarningText: StateFlow<String?> = _quickWarningText.asStateFlow()

    fun setQuickWarningText(text: String?) {
        prefs.edit { putString(KEY_QUICK_WARNING_TEXT, text.orEmpty()) }
        _quickWarningText.value = text?.takeIf { it.isNotEmpty() }
    }
```

  - **Constants:** `KEY_WARNING_TEXT = "warning_text"`, `KEY_WARNING_VIBRATE = "warning_vibrate"`, `KEY_WARNING_BRIGHTNESS = "warning_brightness"`, `KEY_QUICK_WARNING_TEXT = "quick_warning_text"`, `KEY_DEFAULT_WARNING_TEXT = "default_warning_text"`.
  - **`SettingsSnapshot`** fields: `quickWarningText: String?`, `warningText: String`, `warningVibrate: Boolean`, `warningMaxBrightness: Boolean`.
  - **`snapshot()`:** fill them from the flows.
  - **`restore()`:** `setQuickWarningText(s.quickWarningText)`, `setWarningText(s.warningText)`, `setWarningVibrate(s.warningVibrate)`, `setWarningMaxBrightness(s.warningMaxBrightness)`.

- [ ] **Step 6: `BackupJson`**
  - **`settingsToJson`:** `put("quickWarningText", s.quickWarningText ?: JSONObject.NULL)`, `put("warningText", s.warningText)`, `put("warningVibrate", s.warningVibrate)`, `put("warningMaxBrightness", s.warningMaxBrightness)`. Inside `lockDefaults`, add `put("warningText", d.warningText ?: JSONObject.NULL)`.
  - **`settingsFromJson`:** `quickWarningText = o.optStringOrNull("quickWarningText"), warningText = o.optString("warningText", ""), warningVibrate = o.optBoolean("warningVibrate", true), warningMaxBrightness = o.optBoolean("warningMaxBrightness", true),`. In `LockDefaults(...)`, add `warningText = d.optStringOrNull("warningText"),`.
  - **`profileToJson`:** `put("warningText", p.warningText ?: JSONObject.NULL)`.
  - **`profileFromJson`:** `warningText = o.optStringOrNull("warningText"),`.

- [ ] **Step 7: Options at lock start**
  - **`AppContainer.globalLockOptions()`:** add `warningText = settingsStore.warningText.value, warningVibrate = settingsStore.warningVibrate.value, warningMaxBrightness = settingsStore.warningMaxBrightness.value,`.
  - **`ProfileLockStarter.start`:** `options = globalOptions().withWarningText(profile.warningText),`.
  - **`startQuick`:** `options = globalOptions().withWarningText(settings.quickWarningText.value),`.
  - Add a private helper to `ProfileLockStarter`:

```kotlin
    /** A lock's own warning text wins over the global one; null or blank keeps the global text. */
    private fun GlobalLockOptions.withWarningText(own: String?): GlobalLockOptions =
        if (own.isNullOrBlank()) this else copy(warningText = own)
```

- [ ] **Step 8: Manifest:** `<uses-permission android:name="android.permission.VIBRATE" />` next to the other `uses-permission` lines.

**Strings:**

```xml
    <string name="warning_default_text">This app is not allowed in the current lock (LockMyPhone - Extended).</string>
    <string name="warning_go_back">Go back</string>
```

- [ ] **Step 9: Build** and grep the generated `profiles` schema for `` `warningText` TEXT ``. Expected: exit 0, and the column is present with no `NOT NULL`.

---

### Task 3: The popup window (`WarningOverlay`) in the accessibility service

**Files:**
- Create: `core/enforce/WarningOverlay.kt`
- Modify: `core/enforce/LockAccessibilityService.kt` (own the overlay; static `showWarning` / `hideWarning`)
- Modify: `data/FontLibrary.kt` (`typefaceFor`), `ui/PickerAppearance.kt` (`fontFamilyFor` delegates)

**Interfaces:**
- Produces:
  - `data class WarningRequest(val text: String, val vibrate: Boolean, val maxBrightness: Boolean, val typeface: Typeface?, val onGoBack: () -> Unit)`;
  - `class WarningOverlay(service: AccessibilityService)` with `fun show(request: WarningRequest): Boolean`, `fun hide()` and `val isShowing: Boolean`;
  - `WarningOverlay.VIEW_CLASS_NAME: String`;
  - `LockAccessibilityService.showWarning(request): Boolean` and `.hideWarning()` (companion);
  - `FontLibrary.typefaceFor(choice: FontChoice): Typeface?` (null = the default font).

- [ ] **Step 1: `FontLibrary.typefaceFor`**

```kotlin
    /** The typeface for [choice]; null for the default font or an added font whose file is gone. */
    fun typefaceFor(choice: FontChoice): Typeface? = when (choice) {
        is FontChoice.System -> choice.font.familyName?.let { Typeface.create(it, Typeface.NORMAL) }
        is FontChoice.UserFile -> typeface(choice.fileName)
    }
```

`fontFamilyFor` in `ui/PickerAppearance.kt` becomes:

```kotlin
fun fontFamilyFor(choice: FontChoice, fonts: FontLibrary): FontFamily =
    fonts.typefaceFor(choice)?.let { FontFamily(it) } ?: FontFamily.Default
```

- [ ] **Step 2: Create `core/enforce/WarningOverlay.kt`**

```kotlin
package com.fabiantorrestech.lockmyphone_extended.core.enforce

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.fabiantorrestech.lockmyphone_extended.R

/** What the warning popup shows and does. [onGoBack] runs for the button and for Back. */
data class WarningRequest(
    val text: String,
    val vibrate: Boolean,
    val maxBrightness: Boolean,
    val typeface: Typeface?,
    val onGoBack: () -> Unit,
)

/**
 * The full-screen popup over a not-allowed app during a pass (ADR 0007). It's an accessibility
 * overlay, so it needs no extra permission, sits above every app and takes all touches; the app
 * underneath gets none. Built with plain Views: a service window has no Compose lifecycle.
 */
class WarningOverlay(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var view: WarningOverlayView? = null

    val isShowing: Boolean get() = view != null

    /** Shows (or updates) the popup. Returns false if Android refused the window. */
    fun show(request: WarningRequest): Boolean {
        hide()
        val root = WarningOverlayView(service, request)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Focusable on purpose: Back goes to the popup (Go back), not to the app underneath.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        ).apply {
            screenBrightness = if (request.maxBrightness) 1f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        return try {
            windowManager.addView(root, params)
            view = root
            if (request.vibrate) startVibrating()
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Couldn't show the warning popup", e)
            false
        }
    }

    fun hide() {
        val shown = view ?: return
        view = null
        vibrator().cancel()
        try {
            windowManager.removeView(shown)
        } catch (e: IllegalArgumentException) {
            // Already gone (e.g. the service was unbound).
        }
    }

    /** A short pulse every 1.5 s while the popup shows. */
    private fun startVibrating() {
        vibrator().vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 1_300), 0))
    }

    private fun vibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            service.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            service.getSystemService(Vibrator::class.java)
        }

    companion object {
        private const val TAG = "WarningOverlay"

        /** The popup's own window events carry this class name; [LockController] ignores them. */
        val VIEW_CLASS_NAME: String = WarningOverlayView::class.java.name
    }
}

/** The popup's root view: black, the text in the middle, Go back under it. */
class WarningOverlayView(context: Context, private val request: WarningRequest) : FrameLayout(context) {

    init {
        setBackgroundColor(Color.BLACK)
        isFocusableInTouchMode = true
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val padding = dp(32)
            setPadding(padding, padding, padding, padding)
        }
        column.addView(TextView(context).apply {
            text = request.text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            gravity = Gravity.CENTER
            request.typeface?.let { typeface = it }
        })
        column.addView(
            Button(context).apply {
                text = context.getString(R.string.warning_go_back)
                setOnClickListener { request.onGoBack() }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(32)
            },
        )
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
    }

    /** Back on the popup means Go back. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) request.onGoBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
```

- [ ] **Step 3: `LockAccessibilityService`**
  - Add the field `private var warningOverlay: WarningOverlay? = null`. Create it in `onServiceConnected`: `warningOverlay = WarningOverlay(this)`. In `onUnbind`, call `warningOverlay?.hide(); warningOverlay = null` first.
  - In the companion object:

```kotlin
        /** Shows the warning popup; false if the service isn't bound or the window was refused. */
        fun showWarning(request: WarningRequest): Boolean =
            instance?.warningOverlay?.show(request) ?: false

        fun hideWarning() {
            instance?.warningOverlay?.hide()
        }
```

- [ ] **Step 4: Build.** Expected: exit 0.

---

### Task 4: Controller and engine: WARN instead of relock during a pass

**Files:**
- Modify: `core/lock/LockEngine.kt` (`ForegroundDecision.WARN`, `decideOnForeground`)
- Modify: `core/lock/LockController.kt` (`onForegroundApp`, `lastAllowedApp`, warning callbacks, hide on screen off / relock / end)
- Modify: `AppContainer.kt` (wire the callbacks)

**Interfaces:**
- Consumes: `WarningRequest`, `WarningOverlay.VIEW_CLASS_NAME`, `LockAccessibilityService.showWarning/hideWarning` (Task 3), and the `LockSession.warning*` fields (Task 2).
- Produces: `ForegroundDecision.WARN`; `LockController.showWarning: ((LockSession, onGoBack: () -> Unit) -> Boolean)?`, `.hideWarning: (() -> Unit)?`, `.onReturnToApp: ((String?) -> Unit)?`.

- [ ] **Step 1: `LockEngine`**
  - Add to `ForegroundDecision`:

```kotlin
    /** A not-allowed app during a pass: cover it with the warning popup (ADR 0007). */
    WARN,
```

  - In `decideOnForeground`, change the final `return ForegroundDecision.RELOCK` to:

```kotlin
        if (isLauncher) return ForegroundDecision.RELOCK // Home set to "Lock the phone"
        return ForegroundDecision.WARN
```

  - Update its KDoc to: "The launcher redirects (or relocks with HomeAction.LOCK); any other app gets the warning popup."

- [ ] **Step 2: `LockController`**
  - **Fields** (next to `onRedirectHome`):

```kotlin
    /** Shows the warning popup for this lock; false if it can't be shown. Set by the app container. */
    var showWarning: ((LockSession, onGoBack: () -> Unit) -> Boolean)? = null

    /** Hides the warning popup if it's showing. Set by the app container. */
    var hideWarning: (() -> Unit)? = null

    /** Go back: reopens this allowed app, or the allowed-apps list when it's null. Set by the app container. */
    var onReturnToApp: ((String?) -> Unit)? = null

    /** The last allowed app in front during this pass, for the popup's Go back. */
    private var lastAllowedApp: String? = null
```

  - **`onForegroundApp`, first line of the body:**

```kotlin
        // The warning popup's own window isn't an app in front.
        if (packageName == context.packageName && className == WarningOverlay.VIEW_CLASS_NAME) return
```

  - After `lastAppInFront` is set, add: `if (session != null && packageName in session.allowedPackages) lastAllowedApp = packageName`.
  - Replace the tail of `onForegroundApp`, from `val relock = decision == ForegroundDecision.RELOCK` to the end, with:

```kotlin
        // An allowed app, our own screen or a Home redirect means the user is back: drop the popup.
        val backInAllowed = decision == ForegroundDecision.REDIRECT ||
            packageName == context.packageName ||
            (session != null && packageName in session.allowedPackages)
        if (backInAllowed) hideWarning?.invoke()

        val warned = decision == ForegroundDecision.WARN && session != null &&
            showWarning?.invoke(session) { goBackFromWarning() } == true
        val relock = decision == ForegroundDecision.RELOCK || (decision == ForegroundDecision.WARN && !warned)
        foregroundLog.add(
            ForegroundLog.Entry(
                timeMs = clock(),
                packageName = packageName,
                className = className,
                verdict = verdictFor(packageName, alwaysAllowed),
                relocked = relock,
                note = when {
                    decision == ForegroundDecision.REDIRECT -> "home → ${session?.homeAction}"
                    warned -> "warning shown"
                    else -> null
                },
            )
        )
        if (warned) Log.i(TAG, "Not-allowed app during a pass ($packageName/$className); warning shown")
        if (relock) relockFromPass("Left allowed apps (foreground=$packageName/$className); relocking")
        if (decision == ForegroundDecision.REDIRECT && session != null) redirectHome(session.homeAction)
    }

    /** The popup's Go back: close it and reopen the allowed app the user came from. */
    private fun goBackFromWarning() {
        hideWarning?.invoke()
        onReturnToApp?.invoke(lastAllowedApp)
    }

    private fun relockFromPass(reason: String) {
        Log.i(TAG, reason)
        hideWarning?.invoke()
        cancelLockScreenChoice()
        store.save(engine.clearPass(store.current))
        relocker.lockNow()
    }
```

  - **`redirectCheck`:** replace its last four lines (`Log.i(...)` through `relocker.lockNow()`) with `relockFromPass("Still on the launcher after a Home redirect; relocking")`.
  - **`onScreenOff`:** first line `hideWarning?.invoke()`.
  - **`endLock`:** add `hideWarning?.invoke()` and `lastAllowedApp = null` after `cancelLockScreenChoice()`.
  - **`choiceRelock` and `pendingRelock`:** before each `relocker.lockNow()`, add `hideWarning?.invoke()`.
  - **Import:** `core.enforce.WarningOverlay` and `core.enforce.WarningRequest` (the latter is only used in AppContainer, so skip it here if it's unused).

- [ ] **Step 3: `AppContainer`**, next to `onRedirectHome`:

```kotlin
        lockController.showWarning = { session, onGoBack ->
            LockAccessibilityService.showWarning(
                WarningRequest(
                    text = session.warningText.ifBlank { appContext.getString(R.string.warning_default_text) },
                    vibrate = session.warningVibrate,
                    maxBrightness = session.warningMaxBrightness,
                    typeface = fontLibrary.typefaceFor(settingsStore.pickerStyle.value.font),
                    onGoBack = onGoBack,
                )
            )
        }
        lockController.hideWarning = { LockAccessibilityService.hideWarning() }
        lockController.onReturnToApp = { packageName ->
            val intent = packageName?.let(appCatalog::launchIntent) ?: PassActivity.redirectIntent(appContext)
            appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
```

Imports: `core.enforce.LockAccessibilityService` (if missing) and `core.enforce.WarningRequest`. `appCatalog.launchIntent` already adds `NEW_TASK`; adding it again is harmless.

- [ ] **Step 4: Build, install and quick check on the device:**
  1. During a pass, open a not-allowed app (e.g. a link from WhatsApp).
  2. The popup shows, and `adb logcat -s LockController WarningOverlay` logs `warning shown`.
  3. Go back returns to WhatsApp.
  4. Note whether the brightness jumps. If it doesn't, record a ruling: drop the switch in Task 5.

---

### Task 5: Settings UI (global card + per-lock Advanced row)

**Files:**
- Modify: `ui/SettingsKit.kt` (new `TextDialog`)
- Modify: `ui/HomeScreen.kt` (`DuringLockCard` rows; params)
- Modify: `ui/LockSettingsSections.kt` (`LockSettings.warningText`, Advanced toggle + row, `LockDialog.WARNING_TEXT`)
- Modify: `ui/ProfileEditorScreen.kt` (`toLockSettings` / `withLockSettings`)
- Modify: `MainActivity.kt` (wiring; `quickLock` + `saveQuickSettings`)
- Modify: `res/values/strings.xml`

**Interfaces:**
- Produces: `@Composable fun TextDialog(title: String, initial: String, placeholder: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit)`, `LockSettings.warningText: String? = null`.

- [ ] **Step 1: `SettingsKit.TextDialog`**, below `NumberDialog`:

```kotlin
/** Edit one piece of text; [placeholder] shows while it's empty (empty = use the default). */
@Composable
fun TextDialog(
    title: String,
    initial: String,
    placeholder: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MAX_WARNING_TEXT_LENGTH) },
                placeholder = { Text(placeholder) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

private const val MAX_WARNING_TEXT_LENGTH = 200
```

Add any imports that are missing (`OutlinedTextField`).

- [ ] **Step 2: `DuringLockCard`.** Add the params `warningText: String, onWarningTextChange: (String) -> Unit, warningVibrate: Boolean, onWarningVibrateChange: (Boolean) -> Unit, warningMaxBrightness: Boolean, onWarningMaxBrightnessChange: (Boolean) -> Unit`. Thread them through `HomeScreen`'s signature and the `DuringLockCard(...)` call. Under the assistant row add:

```kotlin
            SettingsRow(
                icon = Icons.Filled.Warning,
                title = stringResource(R.string.warning_text_title),
                summary = warningText.ifBlank { stringResource(R.string.warning_default_text) },
                onClick = { editingWarning = true },
            )
            SwitchSettingsRow(
                icon = Icons.Filled.Vibration,
                title = stringResource(R.string.warning_vibrate_title),
                summary = null,
                checked = warningVibrate,
                onCheckedChange = onWarningVibrateChange,
            )
            SwitchSettingsRow(
                icon = Icons.Filled.BrightnessHigh,
                title = stringResource(R.string.warning_brightness_title),
                summary = null,
                checked = warningMaxBrightness,
                onCheckedChange = onWarningMaxBrightnessChange,
            )
```

  - Add `var editingWarning by remember { mutableStateOf(false) }`.
  - The dialog: `if (editingWarning) TextDialog(stringResource(R.string.warning_text_title), warningText, stringResource(R.string.warning_default_text), onConfirm = { onWarningTextChange(it); editingWarning = false }, onDismiss = { editingWarning = false })`.
  - Imports: `Icons.Filled.Warning`, `.Vibration`, `.BrightnessHigh`.
  - **`MainActivity`** wiring after `onHomeActionChange`:

```kotlin
                warningText = container.settingsStore.warningText.collectAsState().value,
                onWarningTextChange = container.settingsStore::setWarningText,
                warningVibrate = container.settingsStore.warningVibrate.collectAsState().value,
                onWarningVibrateChange = container.settingsStore::setWarningVibrate,
                warningMaxBrightness = container.settingsStore.warningMaxBrightness.collectAsState().value,
                onWarningMaxBrightnessChange = container.settingsStore::setWarningMaxBrightness,
```

- [ ] **Step 3: Per-lock row (`LockSettingsSections`)**
  - `LockSettings`: add `val warningText: String? = null,` last (KDoc `/** This lock's warning popup text, or null for the global text (Advanced). */`).
  - `LockDialog`: add `WARNING_TEXT`.
  - At the end of the Unlocking section (after the NFC row), add an Advanced toggle and the row:

```kotlin
        SectionDivider()
        var advanced by rememberSaveable { mutableStateOf(!settings.warningText.isNullOrBlank()) }
        TextButton(onClick = { advanced = !advanced }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(stringResource(R.string.setup_advanced_options))
        }
        if (advanced) {
            SettingsRow(
                icon = Icons.Filled.Warning,
                title = stringResource(R.string.warning_text_lock_title),
                summary = settings.warningText?.takeIf { it.isNotBlank() } ?: stringResource(R.string.warning_text_lock_global),
                onClick = { editing = LockDialog.WARNING_TEXT },
            )
        }
```

  - In the `when (editing)` block:

```kotlin
        LockDialog.WARNING_TEXT -> TextDialog(
            title = stringResource(R.string.warning_text_lock_title),
            initial = settings.warningText.orEmpty(),
            placeholder = stringResource(R.string.warning_text_lock_global),
            onConfirm = {
                onChange(settings.copy(warningText = it.ifBlank { null }))
                editing = null
            },
            onDismiss = { editing = null },
        )
```

  - Imports as needed: `rememberSaveable`, `TextButton`, `Icons.Filled.Warning`.

- [ ] **Step 4: Mappings**
  - **`ProfileEditorScreen`:**
    - `toLockSettings`: `warningText = warningText,`;
    - `withLockSettings`: `warningText = settings.warningText,`.
  - **`MainActivity`:**
    - `quickLock = LockSettings(...)`: `warningText = quickWarningText,`, with `val quickWarningText by container.settingsStore.quickWarningText.collectAsState()` next to `quickHidden`;
    - `saveQuickSettings`: `store.setQuickWarningText(settings.warningText)`.

- [ ] **Step 5: Strings**

```xml
    <string name="warning_text_title">Warning text (not-allowed apps)</string>
    <string name="warning_vibrate_title">Vibrate while the warning shows</string>
    <string name="warning_brightness_title">Full brightness while the warning shows</string>
    <string name="warning_text_lock_title">Warning text for this lock</string>
    <string name="warning_text_lock_global">Same as Settings → During a lock</string>
```

- [ ] **Step 6: Build, install and check:**
  - The settings card shows the three rows. Editing the text shows the new text on the popup.
  - Profile → Advanced → this lock's text wins over the global text. Empty uses the global text.

---

### Task 6: Docs

- [ ] **Step 1: README, "During a lock":**
  - In the **Allowed apps** bullet, replace "Opening any other app locks the phone again." with: "Any other app is covered by a warning popup with **Go back** (text, vibration and brightness in Settings → During a lock; text per lock under Advanced)."
  - Add a bullet: "**Unlocking during a lock** opens the countdown screen, which locks again after a few seconds (the lock's choice time) unless you pick an allowed app."
- [ ] **Step 2: `handoffContext/test-checklist.md`:** add T99–T102 from spec §7.

---

### Task 7: Device round (Pixel 7, owner)

- [ ] **Step 1:** Record: `adb -s 29071FDH200AFV logcat -v time -s LockController WarningOverlay ReplyProbe > <workspace>/m25.log` in the background.
- [ ] **Step 2:** The owner runs T99–T102, plus M24's open T92–T98.
- [ ] **Step 3:** Read the log. Every `relocking` line must be expected. If the brightness didn't change in T101, remove the brightness switch and its setting, and ledger it as a ruling.
- [ ] **Step 4:** Report. Commit only when the owner asks.
