<!--
SPDX-FileCopyrightText: 2026 BluffWorks LLC
SPDX-License-Identifier: GPL-3.0-only
-->

# S0 Recon Findings

Device: Pixel 11 Pro Fold (codename `yogi`).
Results archived at `results/s0-recon/`.
Device was attached to the host via USB for the entire run, so all readings
reflect real USB charging (`AC powered: true`, `status: Charging`, level
86-87%), not a bench/no-load state.

> **Curation note.** `results/s0-recon/` is a reduced copy of a larger private capture. Three
> things were done to it before publication:
>
> 1. **The raw `logcat` capture this spike drew on for §(d) is not published** — device logcat
>    carries other apps' data and personally identifying values that have no place in a public
>    repository. The §(d) findings below are the record of what that capture showed; **they are
>    not independently re-derivable from the files in `results/`.** §(a) and §(b) are: they rest
>    entirely on `sysfs-poll.txt`, published unchanged, and on four fields of `node-battery.txt`
>    that survive the removal in (2).
> 2. **Device-identifying battery fields were removed from the node dumps.** Specifically:
>    `first_usage_date` and `manufacturing_date` (this handset's unboxing and cell-manufacture
>    dates), `cycle_count` / `cycle_counts` and `swelling_data` (lifetime charge history), and
>    `gmsr`, `model_state`, `registers_dump` and `fg_learning_events` (per-cell learned
>    fuel-gauge parameters). Together those are a durable fingerprint of one physical handset
>    rather than a fact about the Pixel 11 Pro Fold, and **no claim in any published document
>    uses any of them.** Removed from `node-battery.txt`, `node-dualbatt.txt`,
>    `node-maxfg_base.txt` and `node-maxfg_secondary.txt`; nothing else in those files was
>    touched, and every field this document cites — `status`, `current_now`, `current_avg`,
>    `voltage_now`, `charge_counter` — is still there to check against.
> 3. **Wall-clock timestamps were re-baselined**, and the directory renamed from its
>    capture-timestamped original for the same reason. Every epoch timestamp under
>    `results/` — host clock and device clock alike — has had one single constant subtracted
>    from it, so `t=0` is the earliest instant anywhere in the published evidence and the values
>    are offsets in seconds, **not epochs**. The ~2 s propagation lag §(d) measures, and every
>    other interval in this document, is a difference between two of them and is preserved
>    exactly. `sysfs-poll.txt`'s first column was re-baselined the same way: it was a raw
>    boot-relative uptime, which is not an epoch but reconstructs one as soon as a reader has the
>    handset's boot instant from anywhere else, so it now counts seconds from **the poll's own
>    first sample**. The span, the cadence and every interval §(a) and §(b) measure are
>    differences and are unchanged.

## (a) `current_now` update cadence

Source: `sysfs-poll.txt` (120 lines, 2 columns of interest are field 2
`current_now` and field 4 `charge_counter`; poll loop nominally ran at 2 Hz
for 60 s but the on-device shell loop overhead stretched this).

Measured poll window: the published time column runs from `0.00` at the
first sample to `74.84` at the last → actual span **74.84 s** over 120 samples (119
transitions), not the nominal 60 s — each `cat` + arithmetic iteration inside
the toybox loop cost ~0.629 s versus the intended 0.5 s `sleep`.

Counted with an awk pass over the 119 consecutive-row transitions:

- `current_now`: changed on **119 of 119** transitions (every single sample
  differed from its predecessor).
- `voltage_now`: changed on 116 of 119 transitions.
- `charge_counter`: changed on **26 of 119** transitions.

Because `current_now` changed on every single poll, this data **cannot
resolve the true gauge update cadence** — it only establishes an upper bound:
the register updates at least as fast as our effective ~0.629 s poll
interval (60 s / 119 ≈ 0.5 s if the loop had hit its nominal rate). We did
not observe a single repeated value to anchor a slower cadence. Do not read
"119/119 changed" as "cadence = poll interval" — it means the reverse: the
true update rate is unresolved and could be faster than we can observe with
a userspace poll loop at this rate.

`charge_counter` (the coulomb-counter accumulator, coarser by design) *did*
show a resolvable cadence: 26 distinct steps over 74.84 s ⇒ **~2.88 s between
charge_counter updates**. This is a data point on gauge-side accumulator
refresh rate, not on `current_now` itself.

**Conclusion for future sampling-cadence constants:** do not encode a
specific `current_now` sample-interval assumption (e.g. "the gauge updates
every N ms") from this data — it is unresolved at ≤0.629 s. A future recon
pass should poll faster (e.g. `usleep` sub-100ms, if available) or watch for
`POWER_SUPPLY_CHANGED` uevents instead of blind polling, to actually bound
the register refresh rate.

## (b) Units and sign while charging

`current_now` values across the 120-sample window ranged from **2,068,488 to
2,238,281** (min/max computed over the full run). These are in the hundreds
of thousands to low millions ⇒ consistent with **microamps (µA)**, i.e.
roughly **2.07–2.24 A** of charge current. `voltage_now` ranged
4,378,125–4,395,078, i.e. **4.378–4.395 V** in µV, matching `dumpsys battery`
(`voltage: 4373`, mV) to three significant figures. `charge_counter` ranged
4,019,166–4,065,833, consistent with µAh (≈4.02–4.07 Ah of counter capacity),
plausible for this device's dual-cell foldable battery.

Sign: all 120 `current_now` samples were **positive** while
`/sys/class/power_supply/battery/status=Charging` (confirmed directly in
`node-battery.txt` line 120) and `dumpsys battery` reported `status: 2`
(`BATTERY_STATUS_CHARGING`, AC powered: true). **On this device, positive
`current_now` means charging** — there is no sign flip to charging-negative
convention here. A single instantaneous snapshot in `node-battery.txt`
(`current_now=1850781`, `current_avg=1869010`) corroborates the same sign
and magnitude outside the poll window.

## (c) Hinge sensors and wake-up variant

This device's full sensor list (49 total h/w sensors, captured via the
platform's sensor-service dump — not itself part of the published evidence,
see the curation note above) contains **exactly one** hinge-angle sensor:

```
0x01010018) Hinge Angle Sensor (wake-up) | Google | ver: 1 | type: android.sensor.hinge_angle(36) | perm: n/a | flags: 0x00000003
	on-change | minRate=10.00Hz | maxRate=50.00Hz | no batching | wakeUp |
```

There is **no separate non-wake-up hinge sensor** on this device — only the
`wake-up` variant exists, so any capture path that needs hinge-angle data
must go through the wake-up sensor (it will hold a partial wakelock while
registered). It is an on-change sensor (event-driven on angle delta, not
continuous-poll), rated 10–50 Hz. Active-client accounting in the same dump
showed 4 concurrent registered clients against that sensor's handle,
including `com.android.server.policy.FoldableDeviceStateProvider` and
`com.android.server.wm.DisplayRotation$FoldController$2` — i.e. the fold
state machinery itself is a permanent consumer, so app-side registration
will coexist with system consumers rather than being exclusive.

## (d) Simulated plug: did `cmd battery set ac 1` produce POWER_CONNECTED activity?

Yes, `ACTION_POWER_CONNECTED`-adjacent log lines appeared in the run's
logcat capture, but **not at the moment `cmd battery set ac 1` ran** —
because the device was already genuinely on USB power (`AC powered: true`
before the probe even started), forcing the simulated override to the same
state produced no observable transition. Timeline reconstructed from host
timestamp files vs. logcat timestamps, both on the re-baselined clock:

| Step | Host command issued at (s from t=0) | Logcat activity observed |
|---|---|---|
| `cmd battery set ac 1` | `host-ts-set-ac.txt` = 310.928 | **none** — no `ACTION_POWER_CONNECTED`/`DISCONNECTED` lines near this timestamp; device state didn't change because it was already plugged |
| `cmd battery unplug` | `host-ts-unplug.txt` = 314.014 | `POWER_DISCONNECTED` activity fires 315.973–316.141 (8 matching lines) — ~2 s after the command |
| `cmd battery reset` (exits simulation, framework re-reads real hardware = still plugged) | issued ~2 s after unplug, i.e. ~316.0 | `POWER_CONNECTED` activity fires 318.096–318.135 (5 matching lines), plus one straggler at 318.595 — ~2 s after `reset` |

So the answer is nuanced: **`cmd battery set ac 1` alone did not produce a
detectable `POWER_CONNECTED` transition** because it didn't change perceived
plug state (real state was already "plugged"). The `POWER_CONNECTED`
activity we *did* capture was produced by `cmd battery reset` restoring the
real (still-plugged) hardware state after the `unplug` override — i.e. the
simulated-unplug-then-reset sequence is what generated the event, not the
simulated-plug step by itself. Both directions have a consistent ~2 s
broadcast-propagation lag from adb command to app-visible logcat line.

**Implication for future spikes:** on a device that's genuinely on
USB power throughout, `cmd battery set ac 1` is not a reliable way to
generate a fresh `POWER_CONNECTED` event — the device must first be in a
state (real or simulated) that differs from "plugged" for the event to fire.
A cleaner simulated-plug probe would run `cmd battery unplug` first, confirm
`DISCONNECTED` activity, then run `cmd battery set ac 1` (or physically
plug/unplug), then check for `CONNECTED` — rather than starting from
`set ac 1` on an already-plugged device.
