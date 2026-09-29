# 2. Enforce locks with Device Admin and an Accessibility service

Date: 2026-09-24

## Status

Accepted

## Context

The app must behave like Thomas Kahn's Lock My Phone. During a lock period, unlocking the phone locks it again immediately, while calls, emergency calls and a whitelist of apps stay usable. It also needs to notice when the user leaves an allowed app, for example through the app switcher, and lock the phone at that moment. The owner tests on a personal Pixel 7 signed in to a Google account and also wants support for Samsung, Nothing and older Android 11 devices. The app will be sideloaded rather than distributed through Google Play.

Android offers three ways to do this. Device Owner lock task mode is the strongest, since it can also block safe-mode boot. However, it can only be provisioned with adb on a device that has no accounts, or through a factory reset. Screen pinning can be undone by the user. The third is a Device Admin, whose force-lock policy provides `DevicePolicyManager.lockNow()`, combined with an AccessibilityService that reports which app is in front.

## Decision

We will enforce locks with a Device Admin, which locks the phone again whenever it is unlocked during a lock, together with an AccessibilityService that watches the foreground app while an allowed-app pass is active. We will not require Device Owner provisioning or use lock task mode.

## Consequences

The app installs and activates on an everyday phone with its accounts intact, and it works from API 30 upward without per-device provisioning. It relies on two user-granted permissions, and the app must guide the user to enable both and warn when either is turned off.

The known escape routes remain open: a safe-mode reboot, ADB access and a factory reset. These cannot be closed without Device Owner. Because the app uses accessibility for purposes other than accessibility, it cannot be published on Google Play. Recognising apps in front depends on device-specific package names, so per-manufacturer profiles have to be maintained. Moving to Device Owner later would mean rewriting the enforcement layer and adding a provisioning flow, so this choice is expensive to reverse.
