# 9. Accountabilibuddy reads profile names from a signature-guarded content provider

Date: 2026-10-24

## Status

Accepted

## Context

AccountabiliBuddy (ABB) starts an LMP profile by name when its hardcore mode begins, and since ADR 0008 it also keeps a profile of its own in sync. The name it starts is typed by hand in ABB, and the intent API ignores a name that doesn't match, so a typo or a renamed profile means no lock and no error. On 2026-10-24 the owner asked for ABB's field to become a dropdown of LMP's profiles with a refresh button. ABB can't see LMP's profiles today: they live in LMP's private Room database, and the intent API only accepts commands, it never answers.

A broadcast round trip (ABB asks, LMP broadcasts the list back) would need a second receiver in ABB, a timeout, and a guard on the reply. Android's standard way for one app to read another's rows on demand is a content provider, which can be guarded by the same signature permission ADR 0008 introduced.

## Decision

We will add an exported, read-only content provider with authority `com.fabiantorrestech.lockmyphone_extended.profiles`, guarded by `com.fabiantorrestech.lockmyphone_extended.permission.MANAGE_PROFILES` for both reading and writing (signature level, so only an app signed with LMP's key can use it). A query on `content://com.fabiantorrestech.lockmyphone_extended.profiles/profiles` returns one row per profile, in LMP's order, with two columns: `name` and `managedBy` (null unless a companion app keeps that profile in sync). Insert, update and delete are refused. Nothing else about a profile, and nothing about locks, places or settings, is exposed. The provider is direct-boot aware because the database is in device-protected storage.

The owner approved this on 2026-10-24, with the contract proposed by the ABB session.

## Consequences

ABB can offer only names that exist, refresh them when the owner renames or adds a profile, and mark its own synced profile. The columns and the authority become a public contract with ABB, like the SYNC_PROFILE extras; adding a column later is cheap, renaming or removing one breaks ABB.

The guard has the same limit as ADR 0008: if either app is ever signed with a different key, the query fails with a security exception and ABB has to fall back to a typed name. A newly installed provider, unlike a receiver, starts LMP's process on every query, which is cheap here because LMP's process is almost always running.
