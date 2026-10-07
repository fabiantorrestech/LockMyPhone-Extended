# LockMyPhone-Extended

An Android app that recreates Thomas Kahn's *Lock My Phone*: during a lock period, any unlock immediately locks the phone again. It adds programmable triggers (NFC, MacroDroid intents, schedules, alarm dismissal), allowed-app passes, a timed-bypass challenge, emergency mode and OEM-aware whitelisting. The app is sideloaded only; it can't go on Google Play because of how it uses accessibility.

## Build / run
- Built with Android Studio 2026.1 on native Windows. The CLI needs `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`, then `./gradlew assembleDebug`.
- Test targets: the `Pixel_9` AVD (API 36) and the owner's Pixel 7 over USB adb. `adb` is on PATH.
- NFC, QRAlarm and Emergency SOS can only be tested on real hardware. **Never place a real emergency call when testing**; cancel the SOS countdown.

## Stack
- Kotlin + Jetpack Compose. AGP 9 provides Kotlin built in, so there is no separate `kotlin-android` plugin.
- minSdk 30, target/compileSdk 37. Package `com.fabiantorrestech.lockmyphone_extended`.
- Dependency injection is a manual `AppContainer` (no Hilt).
- Every new dependency needs the owner's OK. Approved so far: Room + KSP, DataStore Preferences, Navigation Compose, lifecycle-service, lifecycle-viewmodel-compose.

## Architecture
- Decisions are recorded in `doc/adr/`; read them before changing the enforcement or storage approach.
- Code packages under the app package:
  - `core.lock`: lock state machine and its storage
  - `core.enforce`: Device Admin, relocking, the accessibility service
  - `core.service`: foreground service, boot handling
  - `core.notify`: notifications
  - `core.oem`: per-manufacturer package profiles
  - `pass`, `bypass`, `emergency`, `trigger`, `data`, `ui`: one package per feature area
- The active lock state lives in **device-protected** SharedPreferences, because it must be readable before the first unlock after a reboot. End times are wall-clock epoch milliseconds.

## Conventions
- Keep code explicit and readable. Put lock rules in the pure-Kotlin `LockEngine`, not in Android components.
- **Planned: Simple vs Pro mode.** The owner finds the number of settings can be overwhelming. Simple mode should show only what non-technical users need, with sensible defaults. Pro mode exposes the fine-grained controls. Examples of Pro-level controls: beacon responsiveness Advanced values, Calibrate at the edge, the 📐 calculator, the live signal meter, and Match by.
  - Until it's built, give every new setting a sensible default that works without touching it, and put fine-grained controls behind an "Advanced" option, so they can later be hidden in Simple mode.
  - When planning a feature, say which of its settings are Simple and which are Pro.
- No tests unless asked.
- Don't commit `handoffContext/`.
