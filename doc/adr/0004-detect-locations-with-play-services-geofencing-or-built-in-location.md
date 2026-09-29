# 4. Detect locations with Play Services geofencing, or built-in location as an option

Date: 2026-09-29

## Status

Accepted

## Context

The owner wants locks that start, or last, while the phone is at a particular place, and wants "being there" to be as exact as possible. GPS alone is usually accurate to 5–20 m outdoors but drifts to 30–100 m indoors. Being connected to one specific Wi-Fi router (its BSSID) is exact to a building or room, but only works where that router is.

Android offers two ways to watch the user's position in the background. Google Play Services geofencing is battery-friendly and keeps working when the app isn't running, but it needs the `play-services-location` dependency and Google Play Services on the phone, and Android recommends geofences of at least 100 m. The platform's own `LocationManager` needs no dependency, but the app must request updates itself; Android throttles background updates to a few per hour, and it costs more battery. The owner chose to have both, selectable in Settings, with Play Services as the default. The app is sideloaded to the owner's phones, which have Play Services.

## Decision

We will add `com.google.android.gms:play-services-location` and detect locations through a small `PresenceEngine` interface with two implementations. The default registers Play Services geofences, using at least a 100 m radius as a cheap wake-up, and then confirms the precise rule with a fresh high-accuracy location fix. The alternative uses `LocationManager` updates delivered to a PendingIntent. Both apply the same pure rules: inside the user's circle, with the fix accurate enough, plus an optional required Wi-Fi BSSID; leaving uses a margin so the state doesn't flap at the edge. Both engines also watch Wi-Fi connect and disconnect through a PendingIntent network callback, so Wi-Fi rules react at once.

## Consequences

Location triggers work in the background with little battery use by default, and they can be exact indoors through the Wi-Fi rule. The app keeps working without Play Services by switching to the built-in engine.

The costs are a Google dependency, which also makes the app harder to publish outside ecosystems with Play Services; background-location permission ("Allow all the time"), which Android asks for separately and which users may find alarming; and two engines to maintain and test. Geofences are cleared by reboots and app updates, so they must be registered again at boot, after an update and whenever the triggers change. Dropping Play Services later means removing one engine and its dependency; the rules, the database table and the UI stay the same.
