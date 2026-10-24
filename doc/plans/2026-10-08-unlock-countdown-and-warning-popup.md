# Unlock → countdown, and a warning popup for not-allowed apps (M25): design spec

Asked for by the owner on 2026-10-08. Choices made in chat:
- **every** unlock during a lock opens the countdown, which locks after the choice time;
- during a pass, **any** not-allowed app gets a popup that can't be bypassed;
- the popup's one button reopens the allowed app you came from;
- the popup has editable text plus switches for vibration and max brightness.

Needs **ADR 0007** (it changes two behaviours that ADR 0002 describes). It's written after this spec is approved.

## 1. Why

- **Unlocking during a lock** today locks the phone again after 1 s. The owner wants it to open the countdown screen (Allowed apps · Phone), which locks after a few seconds unless you pick something.
- **During a pass**, opening a not-allowed app (a link in WhatsApp opening Chrome, recents, a notification tap) today locks the phone. The owner wants a popup over that app that can't be bypassed. It says the app isn't allowed and has one button that takes you back.

## 2. Unlock → countdown (every unlock, no setting)

| Rule | Detail |
|---|---|
| When | An unlock during a lock with no pass. This is `onUserUnlocked` → `UnlockDecision.RELOCK`, whatever was on screen (home screen or any app) |
| What | After the same 1 s grace as today (so tapping an allowed app on the lock screen still wins, without the countdown flashing), the **countdown screen** opens with a "Locking again in N…" timer of `session.unlockChoiceSeconds` (default 3 s; already set per lock). Picking Allowed apps / Phone cancels it; otherwise the phone locks |
| Reuses | The NFC gate's flow (`tryOpenGate`: picker pass + `choiceRelock` + `onShowLockScreen`) without the NFC part. The NFC gate keeps priority when a tag could act on the lock |
| Home on that screen | Same as tapping Allowed apps: it opens the list (M24 Home redirect). Not a bypass, because the list only offers allowed apps |
| If the screen can't open | `choiceRelock` still fires after N s and locks, so it fails safe |
| Unchanged | The lock-screen shortcuts (Phone, Allowed apps), resuming a paused pass, the NFC gate |

## 3. Warning popup during a pass (replaces "the phone locks")

| Rule | Detail |
|---|---|
| When | During an active pass, a window from a not-allowed app comes to the front: any app that isn't allowed, always allowed, our own app, or the launcher. The launcher keeps redirecting (M24). With Home set to *Lock the phone*, the launcher still locks |
| What | The accessibility service draws a full-screen **`TYPE_ACCESSIBILITY_OVERLAY`** window (no extra permission) over the app. It's opaque black, uses the picker font, shows the warning text and has one button, **Go back**. Touches never reach the app underneath |
| Text | Default: **"This app is not allowed in the current lock (LockMyPhone - Extended)."** Editable in Settings → During a lock (global), and can be **overridden per lock** (profile / Quick lock / Lock defaults, in an Advanced row; empty = use the global text) |
| Go back | Closes the popup and reopens the **last allowed app** in front during this pass, using its launch intent so its task comes back as it was. If there's none, it opens the allowed-apps list |
| While shown | **Vibrate** (switch, on by default): a short pulse every 1.5 s. **Max brightness** (switch, on by default): the popup window asks for full brightness (`screenBrightness = 1f`). ⚠ It isn't verified that an accessibility overlay can change brightness; the first device test checks this. If it can't, the switch is removed |
| Closes when | Go back; an allowed app, our app or the launcher comes to the front (e.g. Home → redirect); the screen turns off (the pass pauses as today); the lock ends |
| Can't be bypassed | The popup takes focus, so Back on it means **Go back**. ⚠ It's not verified that a focused accessibility overlay gets Back; if it doesn't, Back reaches the app underneath while the popup stays. Home and the app switcher go to the allowed apps. The notification shade still works (it's needed for the lock notification and early unlock) |
| Unlocking with a not-allowed app in front | The pass can't resume, so §2 applies: countdown, then the phone locks |
| Our own popup's events | Events from the popup window (our package, class `WarningOverlayView`) are ignored, so they don't change `lastAppInFront` |
| If the popup can't be shown | The phone locks, as today |

## 4. Settings

| Setting | Where | Default | Simple / Pro |
|---|---|---|---|
| Warning text | Settings → During a lock | the default text | Simple |
| Warning text for this lock | Profile / Quick lock / Lock defaults → Advanced | empty (use global) | Pro |
| Vibrate while the warning shows | Settings → During a lock | on | Simple |
| Max brightness while the warning shows | Settings → During a lock | on | Simple |

All of these are saved into `LockSession` when the lock starts, like M24's options: `warningText`, `warningVibrate`, `warningMaxBrightness`.

## 5. Data

- **Room v26 → v27:** `profiles.warningText TEXT` (nullable).
- **SettingsStore:** `warningText`, `warningVibrate`, `warningMaxBrightness`, Quick lock `quickWarningText`, `LockDefaults.warningText`.
- **LockStateStore:** the three session fields.
- **Backup:** all of the above. Old backups restore the defaults.
- **Manifest:** `android.permission.VIBRATE` (an install-time permission, no prompt).

## 6. Code shape

- **`core/enforce/WarningOverlay.kt` (new):** shows and hides the overlay inside `LockAccessibilityService`, with classic Views (no Compose lifecycle in a service window). Plus `WarningOverlayView`, and the vibration and brightness handling.
- **`LockEngine.decideOnForeground`:** a new `ForegroundDecision.WARN` for a not-allowed app during a pass. `RELOCK` is kept for when the overlay can't be shown.
- **`LockController`:**
  - `onForegroundApp` handles `WARN` (show the overlay) and hides it when an allowed app, our app or the launcher comes back;
  - remembers `lastAllowedApp`;
  - `onUserUnlocked` → `openChoiceScreen()` (shared with the NFC gate);
  - `onScreenOff` hides the overlay.
- **`LockAccessibilityService`:** exposes `showWarning(...)` / `hideWarning()`. The controller reaches it through a callback set by the container (like `onShowLockScreen`).

## 7. Testing (owner, Pixel 7)

- **T99, Unlock → countdown:**
  - Lock, unlock from the home screen → countdown "Locking again in 3…" → it locks.
  - Unlock → tap Allowed apps within 3 s → the list stays.
  - Tapping an allowed app on the lock screen opens it directly, with no countdown flash.
  - The NFC gate still works.
- **T100, Warning popup:**
  - During a pass, a WhatsApp link opens Chrome → popup (text, vibration, brightness).
  - Taps on the popup don't reach Chrome. Back → still the popup.
  - Go back → WhatsApp, as it was.
  - Home on the popup → allowed-apps list.
  - Screen off on the popup, unlock → countdown → locks.
- **T101, Texts:**
  - Change the global text: the popup shows it.
  - A profile override wins over the global text. Empty uses the global text.
  - Vibration and brightness switches work (or brightness is dropped if it can't be done).
- **T102, Data:**
  - DB 26 → 27 keeps everything.
  - Backup → restore keeps the texts and switches.
