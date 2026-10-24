# Accountabilibuddy profile, corner picker, Limited locks, "Going back to sleep"

Date: 2026-10-24 · Status: **draft, waiting for the owner's OK** · Repos: LockMyPhone-Extended (LMP), AccountabiliBuddy (ABB)

The owner's answers (2026-10-24) are marked **(owner)**. Everything else is the builder's call and can be changed.

## 1. Accountabilibuddy profile, owned by ABB (owner: "Synced, owned by ABB")

**Why:** the owner's work profiles were deleted by accident; ABB's hardcore mode starts an LMP profile *by name* and silently does nothing when that name is gone. A profile ABB keeps in sync can't drift or vanish.

- **LMP:** new intent action `…action.SYNC_PROFILE`, guarded by a new **signature-level permission** `…permission.MANAGE_PROFILES`. Both apps are signed with this PC's debug key, so only ABB gets it. No token, works on every Android version.
  - Extras: `name` ("Accountabilibuddy"), `allowed` (package list), `hidden` (package list).
  - LMP upserts that profile and marks it `managedBy = "com.accountabilibuddy.companion"`. Needs a new nullable column `profiles.managedBy`, Room v26 → v27.
  - A sync never changes a lock that's already running. It applies the next time the profile starts.
  - The editor shows "Kept in sync by Accountabilibuddy: change its apps in Essentials". Apps and hidden apps are read-only there. Length, early unlock and DND stay editable in LMP.
  - Deleting it is allowed; ABB's next sync recreates it.
  - LMP always allows Accountabilibuddy itself in this profile.
- **ABB:**
  - Sends `SYNC_PROFILE` on app start and whenever Essentials change.
  - The hardcore profile defaults to "Accountabilibuddy". The current picker stays for choosing another one.
- **ADR:** yes, because a second app now writes LMP data through a public action. `doc/adr/0008-…`.
- Simple: nothing new to set. Pro: nothing.

## 2. Corner picker in both apps (owner: "both apps")

**Why (owner):** both screens are "allowed apps" lists. LMP's is the rich one, ABB's Essentials is the bare one, and they should behave the same.

- **Phone:** a round icon button in a bottom corner instead of a list row.
  - LMP setting under Look and feel → Picker: *Phone button: bottom right (default) · bottom left · in the list*. Simple.
  - ABB already has this ("Phone corner").
- **Search:** an invisible 64 dp tap target in the *other* bottom corner.
  - Builder's call: a faint ⌕ at about 15% white, so you can find it. Fully invisible is a Pro switch.
  - Tapping it opens the keyboard with no text box. What you type shows as plain text centered across the bottom, above the keyboard. Its side padding clears both corner buttons, and the list shrinks so nothing overlaps.
  - Enter shows near-exact matches (the existing `AppMatch`): hidden allowed apps in LMP, all apps in ABB. Unchanged.
  - **Cancel:** system Back, or a tap on any empty part of the screen. That clears the text and closes the keyboard.
- No new settings beyond the Phone position (and the Pro "fully invisible").

## 3. Limited locks: sessions with a rising cooldown (owner: "allowance + rising cooldown, 2, 3, 4, 5 … min")

**Why (owner):** stop reaching for the phone first thing in the morning, but still allow a glance at what's pending.

- **New profile option, *Lock style*:** **Locked** (today: only allowed apps) or **Limited** (any app, in short sessions). Simple.
- **Limited, per lock:**
  - Each unlock starts a **session** of up to **5 min**, counted only while the screen is on and outside exempt apps.
  - When the session ends, a **cooldown** starts and the phone acts like a normal lock: only exempt apps, through the picker. The session ends when you lock the screen, or at 5 min.
  - Cooldowns run **2, 3, 4, 5 … min**: +1 per session, up to 10. They reset when the lock ends.
- **The "4:30 then 30 s" problem (owner question):** there's no shared pool. Every unlock after a cooldown gets a **full** session. What costs you is picking the phone up again.
- **Peeks:** an unlock that ends within **30 s** doesn't count as a session and starts no cooldown. You get **3 per lock**, then they count like sessions. That's the "glance at what's pending" case.
- **Grace:** a screen-off under 10 s, like an accidental power press, continues the same session.
- **Exempt (owner):** Phone, emergency calls and SOS, the in-call screen (all always free today), plus the profile's allowed apps. Those default to Clock.
- **Warnings:** a heads-up notification at 1 min and at 15 s left in a session.
- **Morning:** switch the Morning lock's profile to Limited. Its window is the lock's length, 1 h from the alarm dismissal (fixed today, commit `55b106b`).
- **Engine:** the session rules live in `LockEngine`: start session, peek or session, cooldown length, session over. Session state goes in `LockSession`/`LockStateStore`, with new device-protected keys. No DB change for the state. The profile gets `lockStyle` plus 4 numbers in the same v27 migration.
- **Simple:** Lock style, session length, first cooldown. **Pro:** cooldown step and maximum, peek length and count, grace, warnings.

## 4. Morning lock: "Going back to sleep" (owner, 2026-10-24)

**Why (owner):** waking an hour or two early wastes the Morning lock, because it fires on the early wake.

- **Where:** a button on the Morning lock's notification and countdown screen, plus a row on its Automations card when no lock is running.
- **Confirmation prompt:** "Going back to sleep? Morning lock will start again, for its full hour, the next time you wake up (before 2 pm)."
- **Builder's call, to avoid creating a bypass:** it **doesn't end** the current lock. It re-arms the Morning lock for today:
  - The next wake starts a new, full-length lock, and replaces a lock that's still running.
  - Sleep through the rest of the current lock and it simply ends.
- **Next wake =** an alarm dismissal from its alarm apps, or an unlock after at least **30 min** with the screen off. It doesn't use its usual 4 h minimum sleep, which a 1–2 h nap would never meet. Still inside the window.
- **Once per morning.** Simple; the 30 min is Pro.

## 5. Smoothness

Done today:
- The owner's phone runs the **release** build (R8).
- It's compiled ahead of time on the phone.
- The 99th-percentile frame on tab switches went from 105 ms to 29 ms.

Left: about one dropped frame on every tab switch, which is the tab's first composition.
- Next: trace it on the Pixel 5 and fix what shows up, e.g. heavy work during composition or remembering and reusing tab content.
- **Target:** 99th percentile under 20 ms on tab switches.
- From now on the owner's phone only gets release builds.

## Order and testing

1. Smoothness. 2. Corner picker (LMP, then ABB). 3. "Going back to sleep". 4. Accountabilibuddy profile (ADR, then both repos). 5. Limited locks.

Everything is tested on the **Pixel 5 test phone** (owner's OK for test data). The owner's Pixel 7 only gets a release build when the owner says so. ABB edits follow ABB's own `handoffContext` rules: another session was active there at 09:10.
