# 5. One lock at a time, ranked by kind

Date: 2026-09-30

## Status

Accepted

## Context

Until now, a lock that started while another was running was merged into it. The merged lock ended at the later of the two end times, took the stricter of each setting (early unlock, Do Not Disturb level, what happens after the screen turns off) and allowed the apps of both. That kept the phone locked, but it made the running lock hard to predict. Its name, allowed apps and rules could be a mix of two setups, and nothing could deliberately switch from one lock to another.

The owner asked for exactly one lock at a time, with a clear order deciding which lock may replace which, and for NFC tags that can "take over" a running lock with their own. The owner set the order: automatic commitments set up in advance (schedules, calendar triggers, places and alarm triggers) outrank taps (an NFC tag with take-over on, and the Intent API used by MacroDroid and similar apps), which outrank manual starts (Quick lock and a profile's Start button). Emergency mode, not built yet, will rank above everything. The owner also chose that a replacing lock runs for its own length, even if that ends sooner than the lock it replaced. A start that can't win is refused, with a message where there's a screen to show it on, and skipped for background automations. Conflicts between commitments that can be predicted are shown in the editors and lists.

## Decision

We will keep a single `LockSession` and record where it came from in a `LockSource`: its kind, the profile or Quick lock it uses, the automation or tag that started it, and a name. Each kind has a weight: manual 1, NFC and Intent 2, schedule, calendar, place and alarm 3, and emergency 4 later. `LockEngine.start` becomes a decision between Started (no lock is running), Replaced (the new lock's weight is higher: a new session with its own settings and length) and Refused (equal or lower weight, including the same automation's own running lock). The merge logic is removed. Automations use the source instead of their own bookkeeping, for example "leaving a place ends only the lock this place started".

## Consequences

The running lock is always exactly one setup, so its name, apps and rules are predictable, and "which lock is this" is available for messages and for the planned watch companion. NFC take-over tags work without special cases in the engine.

Two automations can no longer both apply at once: the first commitment that starts wins, and an overlapping one of equal rank is skipped rather than combined, which is why predictable overlaps get warnings. A stronger lock can end a weaker one sooner than planned, for example a two-hour schedule replacing an eight-hour manual lock, which the owner accepted. Going back to merging would mean restoring the merge rules in the engine and dropping the refusal paths in every trigger; the stored session keeps working either way, because the source is an additional field.
