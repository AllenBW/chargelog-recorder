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
| `cycleCount` *(nullable)* | `BatteryManager.EXTRA_CYCLE_COUNT` as the sticky battery intent carried it at plug-in — the platform's count of full charge cycles the cell has seen, or absent when the platform did not supply the key. Read once per session so a longitudinal reader can pair a measured capacity with the cycle it was measured at. Added 2026-09-17 without a schema bump. |
| `totalMemBytes` *(nullable)* | Kernel-visible total memory in bytes, raw as `ActivityManager.MemoryInfo.totalMem` reports it. With `deviceModel` and `socModel` this identifies the SKU. Added after the first schema-2 builds, so a schema-2 file may lack it. |
| `capabilities` *(schema ≥ 2, nullable)* | An object declaring what this device's gauge provides — `reportsCurrent`, `reportsChargeCounter`, `counterKind` (`"COULOMB"` or `"SOC_DERIVED"`), `hasHinge`, `hasThermal`, and since 2026-09-08 `reportsChargingStatus` (whether the sticky battery intent carried `android.os.extra.CHARGING_STATUS` at service start) and `chargingPositive` (whether a positive `currentRaw` means charge flowing into the battery — the gauge's sign convention, as the recorder's profile table knew it; absent when unmeasured, which since 2026-10-14 includes every phone: phone headers written before then say `true` from a table guess, and a reader derives a phone's convention from the session instead — see "Reading a file whose header says less"). Every field is nullable; `null` means "not declared," never "false." Declared once per session so absence is visible at the session level rather than only as per-sample `null`s. |

The header's `t`/`e` are meaningful to code reading a `RawLine` generically (`t` reads as
`sessionStartWallClockMs`, `e` as `0`) but are not themselves separate keys in the header's own
JSON — they're computed, not serialized.

### One id, one projected session

The Room projection keys a session by `sessionStartWallClockMs`, so two devices that start a
session in the same millisecond produce two files with one id, and only one of them can have a
row. `Replay` never merges them and never lets the order a directory happens to list in decide:

- **What is showing stays showing.** `Replay.reconcile` keeps the row it already has.
  `Replay.projectAll` notes which file each row came from before it clears the database, and
  projects those files first, so a rebuild never swaps one device's session for the other's.
- **With neither projected** (a fresh install, or a schema bump's destructive migration), the
  first in `LogDirs.all()` order wins: this device's own recordings, then synced devices, then
  imported ones, each group by `deviceId`. `reconcile` and `projectAll` both walk that order, so
  they agree.
- The other file stays on disk with no row and an `ingest_conflict` event in `events.ndjson`.
  `Replay.collisionHidden` lists such files (it reads only the headers of files no row names),
  and `Replay.deleteCollisionHidden` deletes them under the projection's lock without touching
  any row, so a host can offer to clear them.

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
app was just updated, and the recorder restarted itself from it), `start_refused` (the
platform refused that `boot` or `package_replaced` start despite the documented exemption — an OEM
battery manager, typically; `detail` is `"action=BOOT_COMPLETED"` or `"action=MY_PACKAGE_REPLACED"`,
and the recorder stays down until the app is opened), `service_start`,
`service_stop`, `hinge`, `screen_on`, `screen_off`, `thermal`, `charging_status` (the platform's
charging attribution changed — `detail` is `"status=N"`, the same numbering as the sample field;
one line per change, so an Adaptive hold or a thermal pause is findable without scanning samples),
`ingest_conflict` (a session file's id collided with a different device's on a projection
pass — skipped, never merged; see "One id, one projected session" below), `capture_policy` (the
sampling policy changed without closing the session: `detail` is `"settled"` or `"resumed"`),
`gauge_scale` (the recorder resolved a gauge's current SCALE from this session's own samples —
`detail` is `"gaugeProfileId=gauge-unknown-ma"` on a watch, `"gaugeProfileId=gauge-phone-ma"` on a
phone; see below), and `cadence` (the effective sample interval from
this line forward, so a reader never has to infer it from timestamp deltas alone).

The rolling `events.ndjson` lives outside every session, and clearing the device's logs deletes it
too: its plug and unplug markers are a record of when the owner is near a charger, so "remove every
log file from this device" has to reach them.

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

`gauge-unknown-ma` has a catalog entry of its own and exists for this line alone: `forDevice` never
selects it. Without the entry the id resolved back to `gauge-unknown` (µA) and the session that
learned the scale was the one session read a thousand times low.

**A phone is probed too, by a stricter rule.** Every phone is `gauge-phone` (µA), and a Samsung-style
phone gauge speaks mA. The watch rule alone cannot judge a phone: the recorder's own Pixel, full on
its charger, reports medians of 1,823 to 4,948 µA — under the threshold — and an mA verdict on a µA
gauge is persisted and reads every later session a thousand times high. So a phone becomes
`gauge-phone-ma` only when two answers agree (`PhoneScaleEvidence`): the median of its first fifteen
samples that are genuinely charging (status `CHARGING`, level under 90 %, non-zero current) reads
mA, **and** the level has since climbed at least two points by more charge than those currents
could carry if they were µA — less than a tenth of what the climb needs on a 500 mAh cell. A hold
never climbs, so no hold, at any current, is evidence. The phone line can come late in a file
(minutes into the charge, not seconds), and is read exactly like the watch's.

**A `gauge-phone` file with no `gauge_scale` line is judged again when it is read.** Raw values are
stored raw, so the same rule over the whole file repairs a log recorded before the phone probe
existed — including one imported from another phone. `Replay.parse` applies it. The projection
only re-reads a file it has not seen, so the rule reached logs already projected through recorder
Room version 6, which changes no column and exists to drop every row once: the launch-time
reconcile then parses every file again. Over every real µA phone recording in the corpus no fifteen-sample charging
window has a median under 611,000 µA. A header with no `gaugeProfileId` (schema 1, written before
the gauge table existed, on the recorder's own Pixel) is never re-judged.

### Reading a file whose header says less

- **An unknown or absent `gaugeProfileId` keeps the microamp, PHONE convention** for the scale,
  the peak watts and the CSV export — the convention every existing file was written under, so no
  already-exported session changes meaning, and an id from a newer recorder or a corrupted line
  falls back rather than to null. An unknown id borrows no sign: it has its header's declaration
  or none.
- **An undeclared capability resolves through the gauge catalog; a declared one wins.** Schema-1
  headers and pre-2026-09-08 watch files declare no `counterKind` or `chargingPositive`, so both
  come from the profile; an unmeasured gauge with no declaration stays null.
- **A phone's sign convention comes from the session, never the header** (`SignEvidence`, applied
  by `sessionFacts` to `gauge-phone`, `gauge-phone-ma` and an absent id). The counter — or, without
  one, the level — says which way charge moved over the session; the time-integrated current must
  have moved the same way, so if it did a positive current means charging, and if it ran opposite a
  negative one does. Until 2026-10-14 every phone header said `true`, copied from a table built on
  the owner's Pixel; on a phone that reports charging as negative, that guess read the peak far
  below the true one. The rule answers null — no signed watts, and a peak that falls back to
  magnitude — when the counter and level did not move, moved opposite ways, or the current mostly
  cancelled (less than half the integrated charge net in one direction). In the corpus every charge clears that share at 0.97 or more, and the five sessions
  it leaves null are all holds at 100 % on the owner's Pixel, which read their peak by magnitude
  again, as every session did before the sign existed.
- **The scale is the one calibration fact the recorder keeps open.** Its own settle rule reads
  current in real watts and cannot work without it. Noise floors, smoothing, cadence and names are
  the host's, keyed by the profile id. `SessionFacts.currentScale` carries the resolved scale, so a
  consumer never re-derives it from the id or assumes microamps (audit 2026-09-14). A profile
  stores `counterKind` as the header's own string, so a reader needs no `capture` import.
- **The session row carries `osRelease` and `appVersion`** from the header since recorder Room
  version 3. Before, nothing persisted them, and a synced watch row was read under the phone's
  Android version.
- **A host that does not know its cell size records no `designCapacityMah`**, never a nominal
  figure.

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

`socModel`, `totalMemBytes`, `designCapacityMah`, `cycleCount`, the sample's `chargingStatus` and the capability
block's `reportsChargingStatus`/`chargingPositive` are the worked example: all were added in
2026-09 as nullable fields **without** bumping the version, so "schema 2" alone does not tell you whether a file has
them. Read them as nullable and absence as "not recorded", exactly as for any other optional key.
