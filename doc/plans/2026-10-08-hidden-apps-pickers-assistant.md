# Hidden allowed apps, system pickers, assistant popup, Home redirect, time left (M24): design spec

Asked for by the owner on 2026-10-08. Choices made in chat:
- a per-lock hide toggle;
- the file picker, photo picker and share sheet always allowed;
- assistant = only the popup;
- Home opens the allowed apps (a setting);
- time left shown as a countdown, on by default.

## 1. Why

During a pass (inside an allowed app), these screens relock the phone even though using them doesn't
leave the allowed app in any real sense:

- **The camera**, whether opened from WhatsApp/Messages ("take a photo"), from the power-button double press, or from a shortcut.
- **The system file picker, photo picker and share sheet**, opened by "attach" or "share" inside an allowed app.
- **The Gemini / Assistant popup**, opened from the power button or a corner swipe.

The owner also wants the camera allowed **without** cluttering the allowed-apps list.

## 2. Hidden allowed apps (per lock)

| Rule | Detail |
|---|---|
| Where | Every allowed-apps list: each profile, Quick lock and Lock defaults |
| What | Each checked app gets a **👁 / hidden** toggle. A hidden app is still allowed, but it's **not shown** in the lock-screen allowed-apps list or the notification's "Allowed apps" button |
| Rule | Hidden ⊆ allowed. Unchecking an app also un-hides it |
| Default | New installs: Lock defaults and Quick lock start with the **phone's camera app checked and hidden**. New profiles copy Lock defaults, as they do today |
| Existing data | **One-time, on the first run of this version:** add the camera, checked and hidden, to every existing profile, Quick lock and Lock defaults. The owner can remove it afterwards. This only runs once (a settings flag) |
| Camera app | Found with `MediaStore.ACTION_IMAGE_CAPTURE` (Pixel 7: `com.google.android.GoogleCamera`). If there's no camera app, nothing is added |
| Enforcement | Unchanged. A hidden app is in `allowedPackages` like any other. `LockSession` gets `hiddenPackages` (a snapshot at lock start, like the allowed apps), used only by the picker and the notification |
| Counts | Profile summary "N apps" counts only the visible ones, plus "+ N hidden" when there are any |

Behaviour this gives:
- **During a pass:** opening the camera from WhatsApp, or with a double press of power, no longer relocks.
- **The lock-screen camera with no pass:** it already works over the lock screen without unlocking. Unchanged: unlocking from it with no pass still relocks.
- **Screen off in the camera during a pass, then unlock:** this resumes into the camera, because the camera is allowed.

## 3. System pickers (always allowed, no setting)

These packages are added to `OemProfiles.COMMON_ALLOWED` and treated like the keyboard:

| Screen | Packages |
|---|---|
| File picker | `com.google.android.documentsui`, `com.android.documentsui` |
| Photo picker | `com.google.android.photopicker`, `com.android.photopicker`, `com.google.android.providers.media.module`, `com.android.providers.media.module` (the photo picker before Android 14) |
| Share sheet | `com.android.intentresolver` (the old one is `android`, already allowed) |

- **What still relocks:** opening a file, or sharing into a non-allowed app. That app comes to the front, so it relocks.
- **Known gap:** the file picker package also contains a basic file browser that can be reached from Settings → Storage. It becomes usable during a pass. That's low risk, because opening a file from it launches another app, which relocks.

## 4. Assistant popup (setting, off by default)

| Rule | Detail |
|---|---|
| Setting | Settings card "During a lock" → **Allow the Gemini / Assistant popup** (off by default). It's global, and saved into each lock when the lock starts (`LockSession.assistantPopupAllowed`) |
| What's allowed | Only the popup: in `com.google.android.googlequicksearchbox`, a window class starting with `com.google.android.apps.search.assistant.surfaces.voice.robin.ui.floaty.` (recorded on the Pixel 7 on 2026-10-08: `…floaty.activity.FloatyActivity`), **or** any window of that package that isn't an activity (the popup's own dialogs and overlays). The Google app's activities still relock |
| Still relocks | The full Google app, Search, Discover, and the Gemini app (`com.google.android.apps.bard`), including when opened from the popup |
| Risk | Google can rename this class in an app update. The popup would then relock again, and Diagnostics would show the new name, which needs a code update. The prefix match softens small renames |

Implementation: `ForegroundPolicy.isAlwaysAllowed` gains `allowAssistantPopup: Boolean`, passed from the session. The class check is a new `OemProfiles.ASSISTANT_POPUP` (package + class prefix).

## 5. Home during a pass opens the allowed apps (setting)

Today, Home or the app switcher during a pass relocks the phone (the launcher counts as leaving).

| Rule | Detail |
|---|---|
| Setting | Settings card "During a lock" → **Home opens…**: **Allowed apps** (default) / **Countdown screen** / **Lock the phone** (today's behaviour). Global, and saved into each lock when the lock starts (`LockSession.homeAction`) |
| When | During an active pass, a launcher window comes to the front (`ForegroundPolicy.isLauncher`: Home, and on a Pixel also the app switcher, which is the launcher's `RecentsActivity`) |
| What happens | `LockEngine.decideOnForeground` returns a new **`REDIRECT`** instead of `RELOCK`. `LockController` starts `PassActivity.pickerIntent` (Allowed apps) or `MainActivity` (Countdown), with `NEW_TASK \| CLEAR_TOP`. The pass stays active |
| Still relocks | Any non-allowed app opened from the launcher before the redirect lands (its own window event relocks). "Lock the phone" keeps today's behaviour |
| Not changed | Switching between allowed apps through the app switcher still isn't possible: the app switcher redirects too |

Notes:
- **Starting the screen from the background:** Android lets an app start an activity from the background while its accessibility service is bound. The app guard is that service, and a pass can't be active without it, so the redirect should be allowed. ⚠ Not verified on the Pixel 7 yet; the first device test confirms it.
- **What you see:** the home screen may flash for a moment before the allowed apps (or countdown) appear.

## 6. Time left on the allowed-apps screen (setting)

| Rule | Detail |
|---|---|
| What | The top-right of the allowed-apps screen shows the time left, e.g. **"0:47:12 left"**, counting down each second (the countdown screen's H:MM:SS format, `formatDurationClock`). It uses the picker font, at about the size of the date line. A pending early unlock counts toward the end (`effectiveEndMs`), as on the countdown screen |
| Setting | Settings → Appearance → Allowed-apps screen → **Show time left** (on by default). It's part of `PickerStyle` (`showTimeLeft`), so it's backed up with the other picker settings |

## 7. Diagnostics (done 2026-10-08)

When a paused pass can't resume, Diagnostics shows `(unlock) → RELOCKED (couldn't resume: app in front X, paused in Y)`, and logcat logs it with tag `LockController`.

## 8. Data changes

- **Room v25 → v26:** `profiles.hiddenPackages TEXT NOT NULL DEFAULT ''`, a set converter like `allowedPackages`, with a hand-written migration.
- **SettingsStore:** Quick lock `hiddenPackages`; `LockDefaults.hiddenPackages`; `assistantPopupAllowed`; `homeAction` (default `ALLOWED_APPS`); `PickerStyle.showTimeLeft` (default on); a `cameraSeeded` flag (one-time).
- **LockStateStore (DE prefs):** `hiddenPackages`, `assistantPopupAllowed`, `homeAction`. A missing value counts as empty / false / `LOCK` (so a lock started before the update keeps today's behaviour).
- **Backup JSON:** profile `hiddenPackages`; settings `quickHiddenPackages`, `lockDefaults.hiddenPackages`, `assistantPopupAllowed`, `homeAction`, `pickerShowTimeLeft`. Old backups restore with none hidden and the defaults for the rest.

## 9. Simple vs Pro

| Setting | Mode |
|---|---|
| Hidden toggle per app | Simple (one tap; the camera is pre-set) |
| Assistant popup switch | Simple |
| Home opens… | Simple |
| Show time left | Simple |
| Pickers | No setting |

## 10. Testing (owner, Pixel 7)

- **T92, Camera:**
  - During a pass, WhatsApp → camera → take a photo: no relock.
  - Double-press power in an allowed app → camera: no relock.
  - The camera isn't in the allowed-apps list.
- **T93, Pickers:**
  - WhatsApp attach → Document: the file picker works.
  - Messages → photo picker: works.
  - Share to an allowed app: works. Share to a non-allowed app: relocks.
- **T94, Assistant:**
  - Switch off: the popup relocks.
  - Switch on: it works. Opening the full Gemini app from it relocks.
- **T96, Home during a pass:**
  - Home → the allowed-apps list appears (the home screen may flash).
  - Setting "Countdown screen" → the countdown appears.
  - Setting "Lock the phone" → relocks, as before.
  - The app switcher behaves the same as Home.
  - Tapping a non-allowed app fast, before the redirect lands, still relocks.
- **T97, Time left:**
  - The allowed-apps screen shows "MM:SS left" top-right, ticking.
  - It matches the countdown screen, including a pending early unlock.
  - The switch hides it.
- **T95, Data:**
  - Existing profiles now list the camera as hidden.
  - DB 25 → 26 keeps everything.
  - Backup → restore keeps the hidden toggles.
