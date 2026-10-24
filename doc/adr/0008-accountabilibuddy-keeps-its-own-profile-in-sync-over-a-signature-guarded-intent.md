# 8. AccountabiliBuddy keeps its own profile in sync over a signature-guarded intent

Date: 2026-10-24

## Status

Accepted

## Context

AccountabiliBuddy (ABB), the owner's companion app, starts an LMP profile by name through the public intent API when a focus session starts on another device ("hardcore" mode). On 2026-10-24 the owner deleted the profiles that name pointed at by accident, and ABB silently did nothing: the intent API logs and ignores an unknown profile name. ABB also has its own list of essential apps ("Essentials") that the owner keeps by hand; an LMP profile maintained separately drifts from it.

The owner chose (2026-10-24) that ABB should own a profile in LMP and keep it in sync with Essentials, rather than LMP copying the list once. The existing intent API is open to any app for starting a lock, and token-checked for stopping one. A sync action is different from both: it can change which apps a lock allows, so an unguarded one would let any installed app weaken a future lock. Both apps are sideloaded and built on the owner's PC, signed with the same debug key; neither will be on Google Play.

## Decision

We will add an intent action, `com.fabiantorrestech.lockmyphone_extended.action.SYNC_PROFILE`, received by a manifest receiver that requires a new permission, `com.fabiantorrestech.lockmyphone_extended.permission.MANAGE_PROFILES`, with `protectionLevel="signature"`. Only an app signed with the same key as LMP can hold it, so no token has to be copied between the apps, and it works on every Android version LMP supports (sender identity from `BroadcastReceiver.getSentFromPackage` needs API 34 and the sender's opt-in).

The intent carries a profile name and the allowed and hidden package lists. LMP creates that profile or updates its app lists, and marks it with the sender's package in a new nullable column, `profiles.managedBy` (Room schema v27). A managed profile's apps are read-only in LMP's editor, which says where to change them; its length, early unlock and other lock settings stay editable in LMP. A sync never changes a lock that is already running: it applies the next time the profile starts. Deleting a managed profile is allowed, and the owner's next sync recreates it.

This was preferred over reusing the intent API's stop token (it would have to be typed into ABB and is shown in LMP's UI) and over LMP reading ABB's data directly (it would couple LMP to ABB's storage format).

## Consequences

ABB's hardcore mode can rely on its profile existing and matching Essentials, and the owner edits that app list in one place. The guard costs nothing at run time and needs no setup.

The signature check ties the feature to both apps sharing one signing key. If either app is ever signed with a different key (a new PC without the old debug keystore, or a store release), the sync silently stops being delivered and the profile keeps its last apps; moving to `knownCerts` or a token would then be needed. A second app now writes LMP's profile table, so the `managedBy` column and the action's extras are a public contract that's expensive to change once ABB ships against it. The schema bump to v27 needs a migration like every earlier one.
