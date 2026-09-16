<!--
SPDX-FileCopyrightText: 2026 BluffWorks LLC
SPDX-License-Identifier: GPL-3.0-only
-->

# The battery extras that are not in the SDK

`BatterySnapshots` reads two extras by their **string keys** rather than through
`BatteryManager` constants:

- `"max_charging_current"`
- `"max_charging_voltage"`

`BatteryManager.EXTRA_MAX_CHARGING_CURRENT` and `EXTRA_MAX_CHARGING_VOLTAGE` are `@hide` framework
constants. They are absent from the public SDK stub jar, so naming the constant does not compile —
but the sticky `ACTION_BATTERY_CHANGED` intent still carries them at runtime, and the keys are
stable in AOSP (`frameworks/base/core/java/android/os/BatteryManager.java`).

So the keys are spelled out as literals on purpose. This is not a missed refactor.

## What to watch

The values are the charger's *negotiated maximum*, not the instantaneous draw, and vendors differ
on units (µA vs mA). `GaugeProfile` owns the unit question; see `docs/ndjson-format.md` for what
is recorded.

A future platform release could drop either extra. The read is null-tolerant and a missing value
is recorded as absent rather than zero — absent means "the device did not say", and zero would be
a measurement claim the recorder never made.
