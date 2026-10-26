# 10. A companion app can keep more than one synced profile, by slot

Date: 2026-10-26

## Status

Accepted

Amends [8. Accountabilibuddy keeps its own profile in sync over a signature-guarded intent](0008-accountabilibuddy-keeps-its-own-profile-in-sync-over-a-signature-guarded-intent.md)

## Context

Under ADR 0008 a companion app owns exactly one LMP profile: `SYNC_PROFILE` finds it by the sender's package in `profiles.managedBy`, so every sync from the same app updates the same profile. On 2026-10-26 the owner asked AccountabiliBuddy (ABB) for a second essentials list for the night (its "bedtime essentials"), with an LMP profile kept in step with it, the same way the focus essentials keep the "Accountabilibuddy" profile in step. Sent under ADR 0008 as it stands, the bedtime list would overwrite the focus profile's apps, and the two syncs would keep replacing each other. Any change has to keep working for a sender that knows nothing about it, and an older LMP has to be told apart by the sender, because LMP never answers a sync.

## Decision

We will accept an optional `slot` extra on `SYNC_PROFILE`: a short name for one of the sender's profiles (ABB sends `bedtime`). Without it, nothing changes: the profile is found by `managedBy == <package>` as before. With it, the profile is found and marked by `managedBy == "<package>#<slot>"`, so each slot is its own profile with its own name, icon, lock settings and app list. The manager's own app is allowed in every slot's profile, as before. The editor keeps treating any non-null `managedBy` as managed, and the profile provider (ADR 0009) returns the column as stored. The version that adds this is 0.13.0 (versionCode 7); ABB sends a slot only to LMP versionCode 7 or later, because an older LMP ignores the extra and would update the slotless profile.

We preferred this over a second sender identity (a second package can't share ABB's code or prefs) and over a list of profiles in one intent (it would change the extras ABB already sends for its focus profile).

## Consequences

ABB can keep any number of synced profiles without LMP knowing what they're for, and existing senders and profiles need no migration. The `#` form of `managedBy` is now part of the public contract with ABB, so it's costly to change, and the version gate means ABB has to keep checking LMP's versionCode before using a slot. Anything that later reads `managedBy` as a package name has to strip the `#<slot>` part first.
