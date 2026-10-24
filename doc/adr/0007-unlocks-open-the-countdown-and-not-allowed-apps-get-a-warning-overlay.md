# 7. Unlocks open the countdown, and not-allowed apps get a warning overlay

Date: 2026-10-08

## Status

Accepted

Amends the behaviour described in [2. Enforce locks with Device Admin and an Accessibility service](0002-enforce-locks-with-device-admin-and-accessibility.md); the enforcement mechanism it chose stays.

## Context

ADR 0002 describes two reactions. An unlock during a lock immediately locks the phone again, and leaving the allowed apps during a pass also locks the phone at that moment. In daily use on the owner's Pixel 7, both feel abrupt. After an unlock, the only way into an allowed app is through the lock-screen notification. During a pass, an ordinary action inside an allowed app, such as a link in WhatsApp that opens the browser, throws the user out to the lock screen and loses their place.

The owner asked for two changes. Every unlock during a lock should open the countdown screen, which offers the allowed apps and the phone, and lock the phone after a few seconds unless something is picked. During a pass, a not-allowed app should be covered by a warning that can't be bypassed, with one button that returns to the allowed app, instead of locking the phone. The warning text, a vibration and full brightness while it shows are configurable.

The constraints of ADR 0002 still apply. The app has a Device Admin and an AccessibilityService, but not Device Owner or SYSTEM_ALERT_WINDOW. An accessibility service may draw `TYPE_ACCESSIBILITY_OVERLAY` windows without any further permission. Some things are not yet verified on the Pixel 7: whether such an overlay can raise the screen brightness, and whether opening the countdown from the background is always allowed.

## Decision

We will keep Device Admin and the AccessibilityService as the enforcement mechanism, and change what they do in these two moments.

An unlock during a lock with no pass will open the countdown screen after the existing one-second grace period, with the lock's own choice time, by default three seconds. It reuses the flow of the NFC gate: a pass for our own screen plus a timed relock. If nothing is picked in time, or the screen can't be shown, the phone locks as before.

During a pass, a window of a not-allowed app will be covered by a full-screen accessibility overlay that the app underneath can't receive touches through. It shows the configured text and one Go back button, which reopens the last allowed app. The launcher keeps the Home redirect from M24. If the overlay can't be shown, the phone locks as before. The text and the two switches are saved into each lock when it starts, like the other per-lock options.

## Consequences

An unlock no longer means an instant relock: for a few seconds the countdown screen is usable. It only offers the allowed apps and the phone, which the lock-screen notification already offers. Mistakes inside an allowed app no longer cost the user their place.

The design is now less strict than Lock My Phone's. The overlay is weaker than a relock, because system gestures still work around it. The overlay takes focus so that Back means Go back, but if Android delivers Back to the app underneath instead, the overlay simply stays while that app is in front. The notification shade can also be opened. The protection depends on the overlay window being shown reliably on each device, so a failure to add it must fall back to relocking. Our own overlay's window events must be told apart from real apps, or they would confuse the app-in-front tracking.

Undoing this means restoring the two relock paths in `LockController`. That is cheap in code, but users would notice it as a change in behaviour.
