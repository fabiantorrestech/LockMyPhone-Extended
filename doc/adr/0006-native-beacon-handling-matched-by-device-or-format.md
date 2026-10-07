# 6. Native beacon handling, matched by device or format

Date: 2026-10-02

## Status

Accepted

## Context

Places can use a Bluetooth beacon as a signal (M13). The app reads beacon advertisements itself: a background scan with hardware filters delivers sightings through a PendingIntent, a moving average smooths the signal, and "near" and "away" need 45 seconds and 2 minutes of steady signal. It recognised iBeacon, Eddystone-UID, its own beacon mode and named devices by address, and each format of a beacon was a separate entry identified by that format's key.

The owner's beacons (Feasycom FSC-BP106) broadcast up to ten slots at once: iBeacon, Eddystone UID, URL and TLM, and AltBeacon. Each slot has its own transmit power (−19.5 to +2.5 dBm) and interval (1300 ms by default). The picker therefore showed one physical beacon as two or three entries, and readings depended on which slot had been picked. The owner asked whether the app should use a protocol choice or the open-source AltBeacon Android library, which supports these formats and estimates distance.

We evaluated the library from its own documentation. Its default background strategy uses the job scheduler: about 5 seconds to the first detection, but around 450 seconds between later detections, and up to 1500 seconds to notice that a beacon has gone. Its intent strategy is the same mechanism we already use, with about 450 seconds to notice that a beacon is gone. Only its foreground-service strategy notices departures within 30 seconds, and that requires a second always-on foreground service next to the lock's own, which Android 13 and later usually won't start from the background. Its distance formula is a curve fitted per phone model, tuned for the Nexus 4 and 5; other phones, including the owner's Pixel 7, fall back to Nexus 5 values. Its signal filter averages 20 seconds of readings after dropping the top and bottom 10%. The protocol inside a packet does not change the signal strength the phone measures, so the library would not make readings stronger.

## Decision

We will keep reading beacons ourselves, with no new dependency, and adopt the parts of the library's approach that help. We will parse the AltBeacon format natively, next to iBeacon and Eddystone-UID. We will replace the moving average with a 20-second trimmed mean (the top and bottom 10% dropped), keeping the 45-second and 2-minute waits and the immediate Refresh check.

A place will match a physical beacon rather than a format. The picker groups every frame from the same Bluetooth address into one entry and asks how to recognise it. "Any signal from this beacon" is the default; it uses the address (`device:<address>`), so every slot counts, whatever its format. Alternatively the place can use one format's ID (iBeacon, Eddystone UID or AltBeacon). When Android reports that the address rotates (Android 15 and later can tell), only the format IDs are offered. A place stores the beacon's address and its signal at 1 m (read from the beacon or measured), so nicknames, distance estimates and the "Near means" guidance refer to that physical beacon. This needs database version 20.

## Consequences

The picker shows one entry per beacon, and matching by address gets the most readings regardless of how the slots are set up. "You left" detection stays at about 2 minutes, or seconds with Refresh, instead of the library's 7–25 minutes. There's no third-party code in the enforcement path and no second foreground service. Estimates are tied to the owner's own beacon rather than another phone model's curve.

We own more code: parsing for each format, the filter and the estimates. New formats (for example Eddystone-EID) are ours to add. Matching by address only works for beacons with a fixed address; on Android 14 and earlier the app can't check that, so a beacon whose address rotates would stop being recognised until it's picked again by one of its format IDs. The trimmed mean keeps a short history of readings per place in device-protected storage. Adopting the library later would mean replacing the background scan path and the near/away rule, and moving existing places from format keys to the library's identifiers.
