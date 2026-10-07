# Beacon setup redesign (M21): design spec

Status: draft for the owner's review, 2026-10-02. No code yet.

## 1. Why

Setting up a Bluetooth beacon place grew feature by feature (M13–M19). Today its section has eight
separate controls:
- pick a beacon (then "Recognise this beacon by")
- beacon nickname
- Calibrate at my spot
- Calibrate at the edge
- Live signal
- "Near means −60 dBm" with ⓘ and 📐
- Responsiveness
- Listen harder

The owner found it confusing to set up their desk beacon:
- **too many rows, and no clear order**
- **several ways to set the range**: five controls for one idea, "how close counts as here"
- **jargon**: Match by, Near means, Responsiveness, Listen harder, Live signal

A device check on 2026-10-02 showed what that costs:
- The desk place had "near" at **−60 dBm**, but at the desk the phone hears the beacon at **−69 to
  −74**.
- So the place almost never counted as here. The lock started late, and Refresh ended it.
- Nothing told the owner the range was stricter than the real signal.
- The place had also saved a beacon "1 m signal" of **−3 dBm**, which is physically impossible. The
  beacon announced its transmit-power setting in that field.

**Goal:** a beacon place should be set up in one guided, plain-language flow. Afterwards it should be
summarised in three rows. A non-technical user gets a working setup with sensible defaults; the
fine controls stay available (Simple vs Pro, see CLAUDE.md).

**Success criteria:**
- The owner can set up the desk beacon start to finish without knowing what dBm is.
- The section after setup shows at most three rows plus a check button.
- Every range method lives on one screen.
- A range stricter than what the phone actually hears is called out with a way to fix it.
- No change in how places detect arrival or leaving, other than the 1 m sanity check.

**Out of scope:**
- The background-scan "sparse readings" investigation (only ~11 results in 2 min 41 s at the desk;
  batched scans may drop packets). It's tracked separately; it affects arrival speed, not setup.
- The global Simple / Pro mode switch itself. Here, Pro items sit behind "Advanced" toggles until
  that switch exists.

## 2. The guided setup

**When it opens:**
- the first time a place's Bluetooth beacon signal is turned on and no beacon is chosen
- or when **Change** is tapped on the Beacon row

**How it looks:** a full-screen flow over the place editor, with Back / Next. Cancel leaves the
place's beacon settings exactly as they were. Change pre-fills every step with the current values.

| Step | Screen | Notes |
|---|---|---|
| 1 | **Pick your beacon**: one row per physical beacon (today's grouped picker): name or nickname, `ID` short id, formats, signal, ≈ distance when the 1 m signal is known | Simple: recognised by any signal, or by its first ID when its address rotates (today's default). Pro (behind "Advanced: recognise it by…"): today's Match by choices, inline. |
| 2 | **Name it**: text field, pre-filled with the nickname or broadcast name | The name is shared by every place using this beacon (today's nickname rule). Optional. |
| 3 | **What should count as "here"?** Just my seat / This room or area / I'll enter the size / *Type a value (dBm)* (Pro) | Picks the method for step 4. |
| 4 | **Measure**: 20 s of live readings ("now −71 · average −72") with a progress bar, then the result: "Here within ≈ 1.5 m (−80 dBm)" | Seat → `BeaconRules.calibratedThreshold` (typical −8 dB). Area → `BeaconRules.edgeThreshold`. Size → today's room-size form and `RangeRules.suggest`. Type → today's number field. |
| 5 | **Check**: about 10 s of live signal against the new range: "✓ You'd count as here (signal −71, needs −80)", or "Not yet: the signal here is −84" | Buttons: Measure again (back to 3) / Done. |

**Paired device detected nearby:** the same flow. Step 1 lists paired devices (today's device
picker, with heard / not heard); steps 2–5 are identical and write the device's own threshold.

**1 m signal:**
- Step 1 records the beacon's announced 1 m signal, but only if it's **plausible: −30 to −100 dBm**.
- Anything else (e.g. the owner's −3) is treated as unknown, and estimates use the typical −59,
  labelled "typical".
- The same check applies when reading the value already saved on a place, so the existing −3 is
  ignored without a migration.

## 3. After setup: the beacon section

```
Bluetooth beacon
 Beacon   Desk · any signal · ID 9F3A2C          [Change]   → the guided setup, pre-filled
 Range    Here within ≈1.5 m  (-78 dBm)          [Adjust]   → Range screen
 Timing   Balanced: here after 45 s, gone 2 min  [Change]   → Timing screen
 [ Check signal now ]                                       → today's live meter
 ⚠ too-strict warning (only when it applies, §5)
```

- The ≈ distance uses `RangeRules.estimateDistance` from the place's 1 m signal (or the typical one).
- The dBm stays in brackets for users who think in it.

**Range screen:**
- the current range
- **Measure where I sit**, **Measure at the edge of the area**, **Enter the room size**,
  *Type a value (dBm)* (Pro)
- a collapsible **What the numbers mean**: today's ⓘ guide, computed from the beacon's 1 m signal

The measuring options reuse step 4 and step 5 of the setup.

**Timing screen:**
- Battery saver / Balanced / Fast (Simple)
- **Advanced**: today's custom numbers (Pro)
- **Get ready early**: today's "Listen harder" choices (Pro, inside Advanced)
- There's a "Use the default" option, as today.

## 4. Names

| Today | New |
|---|---|
| Pick a beacon / Beacon nickname | **Beacon** row; setup steps 1–2 |
| Recognise this beacon by (Match by) | **Recognise it by** (Pro, setup step 1) |
| Near means −60 dBm, ⓘ, 📐 | **Range**: "Here within ≈ 1.5 m (−60 dBm)" |
| Calibrate at my spot | **Measure where I sit** |
| Calibrate at the edge | **Measure at the edge of the area** |
| 📐 Work it out from your space | **Enter the room size** |
| ⓘ What "Near means" numbers mean | **What the numbers mean** (inside Range) |
| Responsiveness | **Timing** |
| Listen harder | **Get ready early** (Pro, inside Timing) |
| Live signal | **Check signal now** |

The live-meter, calculator and guide strings get the same vocabulary: Range, here, gone.

## 5. Too-strict warning

- **Rule** (pure, `BeaconRules.rangeTooStrict`), given the place's beacon track and range. Show it
  when all three hold:
  - the beacon was heard in the last 30 minutes
  - the place does **not** count as here
  - the track's 20 s average is **more than 5 dB weaker** than the range
- **Text:** "⚠ Lately this beacon has been heard at about −71, weaker than the −60 you set. You may
  never count as here." with a **[Measure again]** button that opens the Range screen.
- **Where:**
  - in the beacon section (and the paired-device section, from its device track)
  - at the end of **Check signal now**, from the meter's own average, without the 30-minute rule
- **Source:** a new read-only `BeaconTracker.status(place)`: last heard, 20 s average, near.
  `LocationTriggerManager` exposes it, and `BeaconTools` passes it to the editor.

## 6. How it fits together

- **No database change** (stays at version 23). Setup and screens write the existing fields:
  - `beaconKey`, `beaconAddress`, `beaconName`, nickname table
  - `beaconRssiThreshold`, `beaconOneMeterDbm`
  - `beaconPreset` and Advanced numbers, `beaconBoost*`
  - the `bt*` equivalents for paired devices
- **New UI units**, each with one job, in `ui/`:
  - `BeaconSetupFlow.kt`: the 5-step flow. Its step state is a small pure `BeaconSetupState` (current
    step, chosen beacon or device, name, method, measured readings, result).
  - `BeaconRangeScreen.kt`: the Range screen.
  - `BeaconTimingDialog.kt`: grows the Get ready early section. `BeaconBoostDialog.kt` becomes its
    content, no longer a separate row.
- **Reused as content, not rewritten:**
  - today's group picker and Match by (`BeaconPickerDialogs.kt`)
  - the calibration rules
  - the room-size form (`RangeCalculatorDialog.kt` content)
  - the live meter (`LiveSignalDialog.kt`)
- **`LocationEditorScreen.kt`:** the beacon and paired-device sections shrink to the summary rows.
  The old per-row dialogs (separate calibrate rows, nickname row, Near means row) are removed from
  this screen.
- **Pure additions in `BeaconRules`:** `plausibleOneMeter(dbm)`, `rangeTooStrict(...)`.

## 7. Testing

- Debug and release builds; install on the Pixel 7.
- The owner, with the FSC-BP106 at the desk:
  - full setup by seat, by area and by room size; Cancel keeps the old settings
  - Change pre-fills
  - Adjust → each method
  - Timing presets / Advanced / Get ready early
  - Check signal now
  - the warning shows with the current −60 range and disappears after Measure again
  - the paired-device setup works
- New device-test checklist items. No automated tests, per CLAUDE.md.

## 8. Open questions

None at the time of writing. The owner approved the flow (§2), the summary, screens and names
(§3–4) and the warning / structure / testing (§5–7) in chat.
