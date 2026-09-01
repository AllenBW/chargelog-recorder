<!--
SPDX-FileCopyrightText: 2026 BluffWorks LLC
SPDX-License-Identifier: GPL-3.0-only
-->

# Spike S3: Hinge Sensor Behavior During Charging

Hinge sensor behavior during charging with screen off.

## Device note (S0-confirmed, binds this protocol)

`S0-recon.md` §c: this Pixel 11 Pro Fold has **exactly one**
hinge-angle sensor, `Hinge Angle Sensor (wake-up)` — there is **no
non-wake-up hinge sensor on this device, and no fallback variant to compare
against**. `HingeMonitor.start()` (`HingeMonitor.kt`) already encodes a
wake-up-preferred, then-any-available selection (`candidates.firstOrNull {
it.isWakeUpSensor } ?: candidates.firstOrNull()`), but on this hardware that
fallback branch is dead code by construction — there is nothing to fall
back to. Any S3 result about "wake-up vs non-wake-up variant behavior" on
this device is necessarily a report of the wake-up variant alone; a genuine
non-wake-up comparison would need a different physical device. Do not read
a clean S3 result on this Fold as validating the fallback branch — it
validates only that the branch compiles and doesn't crash when unexercised.

## Objective

1. Hinge event delivery during an active charging session with the screen
   off (does the wake-up sensor's partial wakelock actually get transitions
   through to the app while the AP would otherwise suspend).
2. Confirm which sensor variant was selected at service start (`service_start`'s
   `hinge=` detail, written in `RecordingService.onCreate()`).
3. The documented "present but silent" negative path: `HingeMonitor` is
   registered (present) but delivers zero `onSensorChanged` calls despite
   real fold/unfold transitions occurring — a known Pixel Fold failure mode
   this app's design explicitly anticipates and never gates behavior on
   (see `HingeMonitor`'s own KDoc).

## Prerequisites

- Foss debug build installed, service running, both onboarding permissions
  granted.
- Physical access to fold/unfold the device by hand — this spike is **not**
  adb-drivable; there is no `cmd` surface for hinge-angle sensor events
  (unlike battery state), so it requires a human physically operating the
  hinge on a timed cadence.
- A stopwatch/clock visible to the human operator, or a second device to
  call out `date +%s`-aligned cues, since transitions are hand-timed.

## Method

1. Start a charging session (physical plug, or the S1 simulated-toggle pair
   if a real charger isn't convenient — either way, confirm a `session_start`
   line exists in the pulled `session-*.ndjson` before proceeding).
2. Turn the screen off.
3. Run a scripted fold/unfold sequence: 10 transitions total (alternating
   fold→unfold→fold→…), each hand-timed by running `adb shell date +%s.%N`
   immediately before or after the physical transition and recording it to a
   local `hand-timed.txt` (`<epoch> <fold|unfold> <transition-number>`).
   Space transitions a few seconds apart so they're individually
   resolvable in the log.
4. Pull logs: `./scripts/pull-logs.sh results/s3-<timestamp>/device-logs`.
5. Compare `hinge` event timestamps (`t`, wall-clock ms, `detail=deg=<angle>`)
   in the session's `session-*.ndjson` against the hand-timed transitions —
   join by nearest wall-clock match, allowing for human reaction-time slop
   on the hand-timed side (this is not a precision latency measurement,
   unlike S1; the ground truth itself has human-scale jitter).
6. Check the run's `service_start` event in `events.ndjson` for its
   `hinge=` detail — confirm it names the wake-up sensor (`hinge=Hinge Angle
   Sensor (wake-up)`), not `hinge=absent`.
7. Check `eventsSeen` behavior: `HingeMonitor.eventsSeen` isn't itself
   written to the log today, so the negative-path check here is behavioral —
   if 10 physical transitions occurred and the number of `hinge` events
   recorded is 0 (or far fewer than 10, allowing for the ≥1° debounce
   threshold in `HingeMonitor.onSensorChanged` collapsing small moves), that
   *is* the "present but silent" reproduction the class's KDoc anticipates.
   Note the exact fold-open-to-close angle range covered by the hand-timed
   sequence, since a full close-to-open sweep should easily clear the 1°
   debounce many times over if the sensor is delivering at all.

## What to record

- `hand-timed.txt`, the pulled `session-*.ndjson` and `events.ndjson`, under
  `results/s3-<timestamp>/`.
- The `service_start` → `hinge=` detail value for this run.
- Count of `hinge` events captured vs. count of hand-timed transitions.
- Per matched pair: event `t` minus nearest hand-timed transition epoch
  (informational only, given the hand-timing jitter noted above — do not
  hold this to S1's ≤3 s bar, it is a different kind of measurement).
- If zero (or near-zero) `hinge` events are captured despite confirmed
  physical transitions: **reproduce it a second time** before concluding
  it's the known failure mode rather than a one-off miss, and file it
  upstream (Pixel Fold hinge-angle sensor silent-failure reports are a
  documented issue class) rather than only recording it locally.

## Pass/fail and characterization criteria

- **Delivery check (pass/fail):** at least one `hinge` event must be
  recorded corresponding to a real transition, with `service_start` naming
  the wake-up sensor. Zero events across two independent 10-transition runs
  is a **fail** for this device/OS combination and should block relying on
  hinge state for any feature until reproduced/triaged upstream.
- **Everything else is characterization**, not pass/fail: event count vs.
  transition count, rough latency, and the wake-up-only caveat above all
  feed future design decisions rather than gating this spike.
