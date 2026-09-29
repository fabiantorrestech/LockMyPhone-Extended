# 3. Store profiles and schedules in Room, in device-protected storage

Date: 2026-09-24

## Status

Accepted

## Context

Milestone 6 introduces lock profiles, which are named sets of allowed apps, a default duration and early-unlock rules. It also introduces weekly schedules that start a profile. Later milestones will add registered NFC tags and learned alarm fingerprints. Until now a single allowed-app list and the early-unlock defaults lived in SharedPreferences. That model can't express several profiles or relationships such as a schedule pointing at a profile.

Schedules fire from an exact alarm and may fire while the phone is still in direct boot, meaning it has rebooted but hasn't yet been unlocked. At that point only device-protected storage can be read. The owner approved Room and KSP as dependencies in the project plan. The data is small, tens of rows at most, and contains no secrets: package names, times and durations.

## Decision

We will store profiles and schedules, and later NFC tags and alarm watches, in a Room database. The database file will live in device-protected storage, so the alarm, boot and intent receivers can read it before the first unlock. Room is compiled with KSP. Plain SharedPreferences stay in use for single values such as the intent-API token and UI defaults.

## Consequences

Profiles and schedules gain typed queries, relationships and reactive Flow reads for the Compose UI. Scheduled locks work even if the phone rebooted overnight and hasn't been unlocked yet.

The cost is an annotation-processing step in the build and schema migrations whenever a table changes. The first ones come when NFC tags (M7) and alarm watches (M8) are added. Because the data is in device-protected storage, it's encrypted only with the device key and not the user's credential. Anyone who can read storage before the first unlock could see which apps are allowed, which is acceptable for this data. Moving to another store later would mean rewriting the DAOs and migrating existing rows.
