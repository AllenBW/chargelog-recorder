<!--
SPDX-FileCopyrightText: 2026 BluffWorks LLC
SPDX-License-Identifier: GPL-3.0-only
-->

# NDJSON capture log format

The recorder writes one append-only NDJSON (newline-delimited JSON) file per charging session —
`session-<sessionStartWallClockMs>.ndjson` — plus a rolling `events.ndjson` for boot and
service-lifecycle events that fall outside any session, and a rolling `discharge.ndjson` for the
level as it falls between charges (a different line shape and its own section below). Every line is one JSON object; nothing is
ever rewritten in place, so a reader can tail a file safely while it's still open. Writes are
flushed on every append and fsynced at close and on terminal/gap events — durability is favored
over battery, since write amplification is itself something the project measures (see
`spikes/S1-detection-latency.md`).

## The `y` discriminator

Every line carries a `"y"` key naming its kind: `"h"` (header), `"s"` (sample), or `"e"` (event).
The example lines below are synthetic — every `t`/`e` pair shares the deliberately round
boot instant 1800000000000 — but their shapes and field sets are exact.
This is a `kotlinx.serialization` sealed-interface class discriminator, deliberately renamed from
the library default (`"type"`) to one character — the common case, a sample line, is written and
parsed constantly, so its shape stays small. Two decoding rules apply to every line type: absent
optional fields are omitted from the JSON rather than written as `null`, and an unrecognized key is
ignored on read. Together these mean an older reader tolerates a newer writer's extra fields, and a
newer reader treats an older file's missing fields as simply absent.

Two more fields recur across sample and event lines: `t` is wall-clock milliseconds
(`System.currentTimeMillis()`-like — useful for correlating against host timestamps or logcat, but
subject to clock changes and NTP skew), and `e` is `elapsedRealtime()` milliseconds, monotonic
since boot — the field to use for computing durations and deltas within one boot session.

## Header (`"y":"h"`) — exactly one, first line

```json
{"y":"h","schema":1,"samplerProfileId":"p1-tick1000-wlsession-flush1","deviceModel":"Pixel 11 Pro Fold","osRelease":"17","appVersion":"0.1.0","tickMs":1000,"sessionStartWallClockMs":1800000060000}
```

| Field | Meaning |
|---|---|
| `schema` | Integer format version (see below). |
| `samplerProfileId` | Opaque identifier for the sampling configuration in effect (tick interval, flush policy) — not a device or user identifier. |
| `deviceModel`, `osRelease` | `Build.MODEL` and the OS release string, as reported by the platform. |
| `appVersion` | The host app's version name at capture time. |
| `tickMs` | The nominal sampling interval in milliseconds this session opened with. |
| `sessionStartWallClockMs` | Wall-clock ms when the session opened — also the value embedded in the file's own name. |
| `deviceKind` *(schema ≥ 2, nullable)* | `"PHONE"` or `"WATCH"`; absent on schema-1 files, which predate multi-form-factor capture and mean PHONE. |
| `deviceId` *(schema ≥ 2, nullable)* | Opaque per-installation identifier used to keep one device's sessions from merging with another's on ingest — not a hardware serial. |
| `gaugeProfileId` *(schema ≥ 2, nullable)* | Identifies the fuel-gauge quirk profile applied when interpreting this device's raw readings. |
| `capabilities` *(schema ≥ 2, nullable)* | An object declaring what this device's gauge provides — `reportsCurrent`, `reportsChargeCounter`, `counterKind` (`"COULOMB"` or `"SOC_DERIVED"`), `hasHinge`, `hasThermal`. Every field is nullable; `null` means "not declared," never "false." Declared once per session so absence is visible at the session level rather than only as per-sample `null`s. |

The header's `t`/`e` are meaningful to code reading a `RawLine` generically (`t` reads as
`sessionStartWallClockMs`, `e` as `0`) but are not themselves separate keys in the header's own
JSON — they're computed, not serialized.

## Sample (`"y":"s"`) — zero or more per session

```json
{"y":"s","t":1800003546948,"e":3546948,"currentRaw":-10937,"chargeCounterRaw":4761666,"voltageRaw":4484,"voltageAgeMs":1021,"tempDeciC":248,"level":100,"scale":100,"status":5,"plugged":1,"maxChargingCurrentRaw":3000000,"maxChargingVoltageRaw":5000000,"thermalStatus":0,"screenOn":true,"hingeDeg":0.0}
```

Every field besides `t`/`e` is nullable and omitted when the platform doesn't supply it for that
tick. Values are recorded **raw** — no unit or sign conversion is ever applied at write time; that
happens later, at read time, once the device's own quirks are known (`gaugeProfileId` above is
what a reader keys that transform on).

| Field | Raw source / typical unit |
|---|---|
| `currentRaw` | `current_now`-shaped battery current. Typically µA, but sign convention and true update cadence are device-specific — see `spikes/S0-recon.md` §a/§b for one device's characterization. |
| `chargeCounterRaw` | Coulomb-counter accumulator, typically µAh. |
| `voltageRaw` | Battery voltage, typically mV (`BatteryManager.EXTRA_VOLTAGE`'s convention). |
| `voltageAgeMs` | How stale the voltage reading was when sampled, on platforms that expose it — some gauges return a cached value. |
| `tempDeciC` | Battery temperature in tenths of a degree Celsius (divide by 10 for °C). |
| `level`, `scale` | Raw battery level/scale pair; percentage is `level * 100 / scale`. |
| `status` | `BatteryManager.BATTERY_STATUS_*` integer. |
| `plugged` | `BatteryManager.BATTERY_PLUGGED_*` integer, or `0` when unplugged. |
| `maxChargingCurrentRaw`, `maxChargingVoltageRaw` | The platform's `EXTRA_MAX_CHARGING_CURRENT`/`_VOLTAGE`, where supplied. |
| `thermalStatus` | `PowerManager.THERMAL_STATUS_*` integer. |
| `screenOn` | Screen state at sample time. |
| `hingeDeg` | Hinge-angle sensor reading in degrees, on devices with a hinge sensor (see `spikes/S3-hinge.md`). |

## Event (`"y":"e"`) — zero or more per session, plus the rolling `events.ndjson`

```json
{"y":"e","t":1800000060012,"e":60012,"kind":"hinge","detail":"deg=0.0"}
```

| Field | Meaning |
|---|---|
| `kind` | One of a fixed set of string constants (below). |
| `detail` | Nullable, free-form and `kind`-specific — a `key=value`-shaped string where present, e.g. `"deg=0.0"` for `hinge`, `"tickMs=1000,policy=tick"` for `cadence`, `"hinge=<sensor name>"` for `service_start`. |

Known `kind` values: `session_start`, `session_end`, `gap` (a sampling silence long enough to be
worth marking explicitly), `power_connected`, `power_disconnected`, `boot` (an
`ACTION_BOOT_COMPLETED` broadcast was received — not necessarily a reboot: Android 15+ re-delivers
this when an app leaves the force-stopped state, so a `boot` line can mean either; the elapsed-time
field (`e`) is the discriminator, since it resets to near zero across a real reboot but runs
continuously across a force-stop-exit re-delivery — see `EventKinds.BOOT`'s own KDoc in
`RawLine.kt`), `service_start`, `service_stop`, `hinge`, `screen_on`, `screen_off`,
`thermal`, `ingest_conflict` (a session file's id collided with a different device's on ingest —
skipped, never merged), `capture_policy` (the sampling policy changed without closing the session:
`detail` is `"settled"` or `"resumed"`), and `cadence` (the effective sample interval from this
line forward, so a reader never has to infer it from timestamp deltas alone).

## Discharge line (`discharge.ndjson`)

The charge log's mirror: one line per level change **while unplugged**, appended to a single
rolling `discharge.ndjson` in the same directory. Around 30–60 lines a day on a phone, two to
three times that on a watch. Written and read by `DischargeLog`, which compacts the file to its
newest half once it passes 512 KB, so the file is bounded without any external sweep.

```json
{"t":1800000060012,"e":60012,"level":78,"screenOn":false}
```

| Field | Meaning |
|---|---|
| `t` | Wall-clock milliseconds — what an hour-of-day drain model buckets by. |
| `e` | `elapsedRealtime()` milliseconds, monotonic since boot; recorded for later monotonic-delta use. |
| `level` | Battery percentage at this reading. |
| `screenOn` | Screen state when the platform reported it; omitted when it did not. |

No `y` discriminator: this file only ever holds these lines, so it is not part of the sealed
`RawLine` hierarchy the session files use. The same two decoding rules apply — absent optional
fields are omitted rather than written as `null`, unrecognized keys are ignored on read — and a
torn trailing line (possible only if a crash landed mid-write before the fsync) is skipped on read
rather than failing it.

## Schema versioning

`schema` is a plain integer, currently `2`. New optional fields are added as nullable with no
default requirement, so an old reader ignores keys it doesn't recognize and a schema-1 file simply
has `null` for fields introduced later (`deviceKind`, `deviceId`, `gaugeProfileId`, `capabilities`
were all added going from schema 1 to schema 2, for multi-device and multi-form-factor capture).
The version only needs to bump when a change isn't safely backward-compatible under that
tolerant-decode contract — a genuinely additive field does not require it.
