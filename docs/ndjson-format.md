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
| `socModel` *(nullable)* | The system-on-chip name (`Build.SOC_MODEL`), or absent when the platform reports it as unknown. Names the hardware class, not the unit. Added after the first schema-2 builds, so a schema-2 file may lack it. |
| `designCapacityMah` *(nullable)* | The cell's design capacity in mAh as the host knew it at capture time, or absent when it did not. The recorder cannot read this itself: the kernel's `charge_full_design` is refused to an unprivileged app by SELinux, so the host supplies it (`RecorderHost.designCapacityMah`). Recorded per session so a reader never substitutes the current device's figure for the one that cell actually had. Added after the first schema-2 builds. |
| `totalMemBytes` *(nullable)* | Kernel-visible total memory in bytes, raw as `ActivityManager.MemoryInfo.totalMem` reports it. With `deviceModel` and `socModel` this identifies the SKU. Added after the first schema-2 builds, so a schema-2 file may lack it. |
| `capabilities` *(schema ≥ 2, nullable)* | An object declaring what this device's gauge provides — `reportsCurrent`, `reportsChargeCounter`, `counterKind` (`"COULOMB"` or `"SOC_DERIVED"`), `hasHinge`, `hasThermal`, and since 2026-09-08 `reportsChargingStatus` (whether the sticky battery intent carried `android.os.extra.CHARGING_STATUS` at service start) and `chargingPositive` (whether a positive `currentRaw` means charge flowing into the battery — the gauge's sign convention, as the recorder's profile table knew it; absent when unmeasured). Every field is nullable; `null` means "not declared," never "false." Declared once per session so absence is visible at the session level rather than only as per-sample `null`s. |

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
| `chargingStatus` | `BatteryManager.EXTRA_CHARGING_STATUS` integer, the platform's own attribution of how the charge is going: 0 invalid (present but never set by this device's health HAL — read as "no attribution") · 1 normal · 2 too cold · 3 too hot · 4 long life · 5 adaptive (the HAL's `BatteryChargingState`). Absent when the platform did not supply the key. Added 2026-09-08 without a schema bump. |

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
`RawLine.kt`), `package_replaced` (an `ACTION_MY_PACKAGE_REPLACED` broadcast was received — the
app was just updated, and the recorder restarted itself from it), `service_start`,
`service_stop`, `hinge`, `screen_on`, `screen_off`, `thermal`, `charging_status` (the platform's
charging attribution changed — `detail` is `"status=N"`, the same numbering as the sample field;
one line per change, so an Adaptive hold or a thermal pause is findable without scanning samples),
`ingest_conflict` (a session file's id collided with a different device's on ingest —
skipped, never merged), `capture_policy` (the sampling policy changed without closing the session:
`detail` is `"settled"` or `"resumed"`), `gauge_scale` (the recorder resolved an unknown gauge's
current SCALE from this session's own first samples — `detail` is
`"gaugeProfileId=gauge-unknown-ma"`; see below), and `cadence` (the effective sample interval from
this line forward, so a reader never has to infer it from timestamp deltas alone).

### `gauge_scale` and a provisional `gaugeProfileId`

A header is written at plug-in, before the session has taken a single sample, so anything the
samples themselves reveal about the gauge cannot be in it. One thing is: whether a gauge whose
profile is `gauge-unknown` reports current in µA or mA. Nothing about a manufacturer implies the
answer, and a wrong one is a factor of a thousand on every current in the file.

So the recorder measures it — the median magnitude of the session's first fifteen non-zero
charging currents — and when the answer is mA it appends a `gauge_scale` line naming the resolved
profile. **A reader that sees one must prefer it over the header's `gaugeProfileId`**: the header
recorded what was assumed, this line records what was measured. There is at most one per file, it
never appears in a file whose header was already right, and its absence means the header stands.

Every session after the one that learned it simply opens with the resolved profile in its header,
so this line is rare: at most one file per device ever carries it.

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

`socModel`, `totalMemBytes`, `designCapacityMah`, the sample's `chargingStatus` and the capability
block's `reportsChargingStatus`/`chargingPositive` are the worked example: all were added in
2026-09 as nullable fields **without** bumping the version, so "schema 2" alone does not tell you whether a file has
them. Read them as nullable and absence as "not recorded", exactly as for any other optional key.
