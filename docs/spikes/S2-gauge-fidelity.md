<!--
SPDX-FileCopyrightText: 2026 BluffWorks LLC
SPDX-License-Identifier: GPL-3.0-only
-->

# Spike S2: `CURRENT_NOW` Gauge Fidelity

> **BLOCKED — PPS-capable PD-logging meter (Power-Z KM003C class) has not
> arrived.** This protocol is written and ready to run, but no results exist
> yet. Do not treat any number in this repo as S2 data until this document
> is amended with a results section referencing an actual
> `results/s2-<timestamp>/` directory. S0 recon
> (`S0-recon.md` §a/§b) covers a partial, meter-free precursor —
> units/sign and a coarse `charge_counter` cadence bound — but explicitly
> could **not** resolve `current_now`'s true update cadence, which is one of
> this spike's five characterization targets.

## Objective

`CURRENT_NOW` fidelity on the Fold — units, sign, update cadence, behavior
in hyper-fast mode; validate against a USB-C inline power meter.
Concretely, characterize:

(i) **scale** — confirm `current_now`/`voltage_now`/`charge_counter` units
    against a ground-truth external meter (S0 established µA/µV/µAh by
    magnitude reasoning only; this spike validates it against a real
    reference).
(ii) **sign** — confirm the charging-positive convention holds across
    chargers/power levels, not just the single AC session S0 observed.
(iii) **cadence** — the register refresh rate S0 could not bound (its
    ~0.629 s userspace poll saw 119/119 consecutive samples differ, which
    only proves the true rate is *at least* that fast).
(iv) **port-to-battery power ratio stability** — how consistently
    `current_now × voltage_now` (battery-side) tracks the meter's
    port-side reading across the charge curve, and whether that ratio is
    stable enough to calibrate.
(v) **PD signature of hyper-fast engagement** — what the negotiation ramp
    looks like on the meter's PD log during the first few seconds of a
    high-wattage session, and whether the battery-side gauge visibly lags
    or smooths it.

## Prerequisites

- PPS-capable PD-logging meter (Power-Z KM003C class) — **not yet
  available**.
- `adb` over Wi-Fi, since the meter sits inline on the cable and a USB host
  connection for `adb` would either be impossible (meter between charger and
  phone, no free USB-host port on the phone) or itself perturb the
  measurement: `adb tcpip 5555` then `adb connect <phone-ip>:5555`.
- Foss debug build installed, service running, both onboarding permissions
  granted.
- A small set of chargers spanning capability classes (e.g. a basic 5 W/10 W
  USB-A brick, a PD-only charger, and a PPS-capable charger) to exercise (v)
  meaningfully — hyper-fast engagement only shows a distinct signature on a
  charger that can actually negotiate it.

## Protocol

1. **Screen off** for the duration of every run (matches real usage and
   avoids display-current confounds in any current draw estimate,
   though the app measures battery current, not device current).
2. **Alignment fiducial**, once per session, before any charger swaps: 3
   plug/unplug cycles at 10 s spacing (same mechanism as S1's simulated
   toggle, or physical if the meter setup makes `cmd battery` unreliable —
   record which). This gives a shared timestamp anchor to align the meter's
   internal clock against the phone's `events.ndjson` wall clock, since the
   meter has no direct access to the phone's clock.
3. **Per charger, two runs:**
   - One 20%→80% run **with the meter inline** (charger → meter → phone).
   - One 20%→80% run **without the meter** (charger → phone directly).
   - Record the wall-clock time delta for the 20→80 transition in each. A
     meaningful gap between the two is itself a finding — it means the meter
     perturbs negotiation (extra cable length, connector resistance, or the
     meter's own PD renegotiation as an inline sink) and any absolute
     current/power numbers from the "with meter" run need that caveat
     attached.
4. **Paired minimal-sampling control**, once per charger: with `RecordingService`
   stopped (`adb shell am force-stop io.github.allenbw.chargelog.debug` for
   the duration of the run — the app's continuous 1 Hz sampling and its
   flush-per-write `RawLogWriter` are themselves a write-amplification load),
   take only a `dumpsys battery` snapshot at the start and end of the 20→80
   run. Compare the delta against the full-sampling run's derived numbers to
   bound how much the app's own sampling activity perturbs the measurement
   it's trying to take.

## What to record

- Meter's raw PD/current/voltage log for every run, associated with the run
  metadata (charger id, with/without-meter, control/full-sampling).
- Phone-side `events.ndjson` + the session's `session-*.ndjson` for every
  full-sampling run, pulled with `scripts/pull-logs.sh`.
- `dumpsys battery` start/end snapshots for the control runs.
- The 20→80 time delta (with vs. without meter) per charger.
- Any visible sample-to-sample step-change pattern in `current_now` that
  would let the true cadence be resolved (i.e. a *repeated* value across
  consecutive 1 Hz samples means the update rate is slower than 1 Hz and is
  now directly observable, unlike S0's faster poll that never repeated).

## Characterization criteria

This spike is explicitly characterization, not pass/fail, except for:

- **(iv) ratio stability** — if the battery-side/port-side power ratio
  varies by more than a rough ±10% band across the charge curve (excluding
  the CV-taper tail, where battery-side current genuinely drops while
  port-side draw may not track 1:1), flag calibration as unreliable without
  per-phase correction — a finding relevant to a future calibration feature.
- **(v) PD signature** — if the meter shows a distinct negotiation-ramp
  shape that the battery-side gauge smooths away entirely (i.e. the phone's
  own sampling can never see the ramp regardless of cadence, because the
  battery-side register is downstream of charge-management IC smoothing),
  that is a hard limit on this app's phase-detection ambitions and should be
  written back into the open questions for future work, not just this
  spike's results.
