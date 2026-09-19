<!--
SPDX-FileCopyrightText: 2026 BluffWorks LLC
SPDX-License-Identifier: GPL-3.0-only
-->

# Changelog

Notable changes to the ChargeLog recorder. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## Versioning

Three version numbers travel with this library, and they move independently.

**The library's own version** follows semantic versioning against the `RecorderHost` seam and the
public API of `capture/`, `data/`, and `measure/`. A major bump means a host has to change code.
There is no published Maven artifact yet — consumers vendor the source or depend on the module
directly — so today the number is documentation, not coordinates.

**The NDJSON schema version** (`schema` in every session log's header, currently **2**) is a plain
integer with its own contract, described in `docs/ndjson-format.md`: decoding is tolerant, so an
added nullable field does *not* bump it. It bumps only for a change an older reader cannot handle
safely. A fixture recorded years from now must still be readable, so this number is the one that
actually constrains us.

**The Room database version** (currently **5**) is a private implementation detail. The database
is a projection rebuilt from the NDJSON logs, which are the source of truth; a host that wipes it
loses nothing.

`BuildConfig.VERSION_NAME` from the *host* app — not from this library — is what the recorder
writes into each session header's `appVersion`. A fixture therefore records which build of which
app produced it, which is why the field is there.

## [Unreleased]

Nothing yet.

## [0.3.3] — 2026-09-17

Stamped with the ChargeLog app release that first ships it; the library has no version of its
own yet (see Versioning).

### Added

- The session header carries `cycleCount`: `BatteryManager.EXTRA_CYCLE_COUNT` as the sticky
  battery intent reported it at plug-in, nullable and absent when the platform does not supply
  the key. Additive under the tolerant-decode contract, so the NDJSON schema stays 2 and the
  Room projection is unchanged. Nothing host-visible: `RecorderHost` is untouched.

## [0.3.2] — 2026-09-17

Stamped with the ChargeLog app release that first ships it; the library has no version of its
own yet (see Versioning).

### Changed

- `SettleDetector` resumes when the level rises above the level it settled at while the status is
  still `CHARGING`, and from then on only a status (`FULL`, or `NOT_CHARGING` at the target) can
  settle the session. The pinned-level predicate's 120 s hold is shorter than a top-of-charge level
  increment (1.9–5.2 min measured on a Pixel Fold from 94 %), so it was settling in late CV and
  releasing the wake lock ~7 min before the charge ended: the last ~2 % of every charge was
  sampled at ~80 s instead of 1 s. The first pinned settle is unchanged, so a gauge that never
  reports `FULL` still settles; only a settle contradicted by a subsequent rise is withdrawn. The
  `capture_policy` event detail `"resumed"` already existed; no schema change.


First public release in preparation. Everything below describes the state of the code as it is
published, not a change from a previous public version — there isn't one.

### The starting point

- `RecorderHost`: the ten-member seam a host implements. Foreground-service notification
  content and rendering, channel labels, charge-target level, raw battery-state callbacks, the
  app version stamped into session headers, the device kind and gauge profile (both with phone
  defaults, so the seam can grow without breaking an existing host), and the consent answer
  the background start paths ask for (default `true`, see "Fixed" below), and the cell's design
  capacity in mAh (default `null`).

  **Host-visible:** `RecorderHost` gains `fun onGaugeRefined(profile: GaugeProfile) = Unit`,
  called once when the recorder resolves an `UNKNOWN` gauge's current scale from a session's own
  first samples. The recorder persists the answer itself (`CapturePrefs.learnedGaugeId`) and every
  later session it opens uses it, so a host that does nothing here stays correct; the callback
  exists so a host that also *reads* the gauge — live watts, its own analysis — can stop being a
  thousand times wrong before its next process start rather than after it. Additive, with a
  default that describes what the recorder did before the member existed.

  **Host-visible:** `RecorderHost` gains `fun designCapacityMah(): Int? = null`, written into each
  session header. The recorder cannot determine it — the kernel's `charge_full_design` is refused
  to an unprivileged app by SELinux — so a host that knows the figure (a `PowerProfile` read, a
  value the user typed) supplies it, and one that does not returns `null`. Additive, with a default
  that describes what the recorder did before the member existed.
- `RecordingService`: always-on `specialUse` foreground service, started from `BOOT_COMPLETED`,
  1 Hz sampling while plugged, wake lock held only for an unsettled session. Two notification
  ids alternating, never leaving the foreground — see "Notifications" in `BUILDING.md`.
- NDJSON capture log, **schema 2** — one append-only file per session plus a rolling
  `events.ndjson`. Format documented in `docs/ndjson-format.md`.

  The `gauge_scale` event is additive and does **not** bump the schema: an older reader that does
  not know the kind skips it and reads the header, which is what it did before. A reader that does
  know it must prefer it over the header's `gaugeProfileId` — the header is written at plug-in and
  records what was assumed; the event records what the samples measured. See
  `docs/ndjson-format.md`.
- Room projection (**database version 5**) rebuilt from the logs by `data/Replay.kt`, with
  `Replay.reconcile()` on the recording → idle edge.
- `measure/`: `SessionFacts` (duration, level delta, energy, peak power, peak temperature, charge
  source, and the gauge's counter kind), `Units` raw-unit conversion, `AnalyzerSample`, CSV export.

  `Units.levelPct` folds a sample's `scale` into its `level` at ingest — in `Replay` and in the
  live `RawLine.Sample -> AnalyzerSample` adapter — so a level reaches the projection and the
  analyzer as a percent whatever maximum the gauge reported it against. Every gauge observed so
  far says 100 (42,607 of 42,607 samples) and is unchanged by this; the log keeps both fields raw.

  **Host-visible:** `SessionFacts` carries `counterKind`, defaulted to `null`, so a consumer can
  tell whether `energyAh` was integrated by a real coulomb counter or computed from the level. It
  matters: on a `SOC_DERIVED` gauge the counter *is* capacity x level, so inferring capacity from
  `energyAh / levelGain` cancels to the gauge's declared constant rather than measuring the cell.
  Additive — a host that constructs `SessionFacts` positionally still compiles. When the header
  declared no kind (schema-1 files), it is resolved from the gauge catalog for the session's
  profile, the same way the current scale is; null only when neither knows.

  **Host-visible:** `SessionFacts` also carries `currentScale`, defaulted to
  `CurrentScale.MICRO_AMP`, so a consumer holding the facts never has to re-derive the gauge's
  units from the profile id to read a watt. Additive — a host that constructs `SessionFacts`
  positionally still compiles — and no schema change: the scale was always resolvable from
  `gaugeProfileId`, this only stops every consumer resolving it again. It exists because the two
  that did not both read a milliamp gauge 1000x low.
- Fuel-gauge quirk profiles — declarative only, per `CONTRIBUTING.md`'s hard rule.
- `:sample`: a minimal Compose host app that implements `RecorderHost` end to end.
- Real-session NDJSON fixtures and device evidence from the S0–S3 spikes, CC0-1.0.
- Licensing: GPL-3.0-only, REUSE 3.3 compliant, contributor CLA for code (not for evidence).

### Added

- **The platform's charging attribution, per sample.** `RawLine.Sample.chargingStatus` records
  `BatteryManager.EXTRA_CHARGING_STATUS` raw (1 normal · 2 too cold · 3 too hot · 4 long life ·
  5 adaptive — constants in `measure/ChargingStatus`), a `charging_status` event marks each
  change, and the header's capability block declares `reportsChargingStatus`. This is the field
  that lets a reader tell a slow charger from a phone that chose to charge slowly. Nullable and
  additive: the NDJSON schema stays **2**; the Room projection is now **version 5** (destructive,
  as always — the logs are the source of truth). The CSV export gains a last `charging_status`
  column.

  **Host-visible:** `RawLine.Sample`, `Capabilities`, `SampleEntity`, `SessionEntity` and
  `AnalyzerSample` each gain a defaulted property; `SamplerProfiles.forHost` gains a defaulted
  `reportsChargingStatus` parameter. A host that constructs any of them still compiles.
- **A measured current-sign convention per gauge.** `GaugeProfile.chargingPositive` (default
  `null`) says whether a positive raw current is charge flowing into the battery: `true` on the
  phone gauge and the Pixel Watch gauge, both measured; `null` on the Samsung and unknown
  profiles. The header declares it per session as `Capabilities.chargingPositive`, and
  `SessionFacts.chargingPositive` resolves it declared-else-catalog, exactly as `counterKind` is.
  `Units.signedWatts` reads it — positive into the battery, negative out, `null` on an unmeasured
  gauge rather than a guessed sign — and `Units.watts` is unchanged.

### Fixed

- **The boot and update starts now ask the host for consent.** `BootReceiver` and
  `PackageReplacedReceiver` started `RecordingService` unconditionally, so a host's own consent
  gate — a first-run flow its launch activity waits for — held on launch and leaked on the next
  reboot or store update: a user who had opened the app once and backed out of the first page
  got a permanent foreground service and recorded sessions before seeing what would be recorded.
  Both receivers now return without starting (one warning line in logcat) when the host says no,
  and write no event line either, so an unconsented device gets no log directory on its behalf.

  **Host-visible:** `RecorderHost` gains `fun captureConsented(): Boolean = true`. Additive — an
  existing host compiles unchanged and keeps starting on every boot and update, exactly as
  before. A host with a consent step overrides it with the same read its launch gate makes.

- **A channel transition no longer leaves the foreground.** `RecordingService` used to remove its
  notification with `stopForeground(STOP_FOREGROUND_REMOVE)` and post a fresh one on every
  recording ↔ idle transition, because an `OngoingActivity` chip only clears on a genuine
  replacement. Leaving the foreground drops the service's foreground-start grant, so the
  `startForeground` right after it is re-checked against Android 12+'s background-start
  restriction and throws `ForegroundServiceStartNotAllowedException` whenever the app is
  backgrounded — which is the normal case at the end of a charge, and unavoidable on Wear, where
  no host can hold a battery-optimization exemption. The service died on the main thread while
  closing the session it was recording, truncating it, and then crash-looped on restart.

  The replacement is now done by calling `startForeground` with a *second* notification id while
  still foreground and cancelling the old one — no background-start check, and the same visible
  removal. `NotificationIds` owns the pair (`PRIMARY` **1**, `ALTERNATE` **8**) and the pure
  `next(current, channelChanged)` alternation between them.

  **Host-visible:** the recorder now reserves **two** notification ids rather than one, and the
  id a post lands on alternates. `HostContent` carries a new `notificationId` field, set by the
  recorder: a host that binds anything to the id — a Wear `OngoingActivity`, say — must read it
  from the content rather than assume `RecordingService.NOTIFICATION_ID`. A host must not post,
  update or cancel either reserved id itself; the collision is silent — `ALTERNATE` was 2 before
  release, which is ChargeLog's own low-battery reminder, and the reminder simply stopped firing.

- **A session's charge source is read from the first sample that reports a plug, not from the
  first sample.** `sessionFacts` labelled a session UNKNOWN whenever its first sample said
  `plugged=0`, which happens when the sticky `BATTERY_CHANGED` the recorder samples predates the
  plug — seen on 4 of 14 real phone sessions. Existing logs are re-read at display time, so the
  label corrects itself with no data change.

- **A session's first sample now carries the plug it is about.** `RecordingService` sampled the
  sticky `BATTERY_CHANGED` it had last received, which can predate `POWER_CONNECTED` by seconds
  or minutes; the session's first sample then said unplugged and discharging, with a voltage and
  level that old. The service now re-reads the system's sticky at the plug edge, as it already
  did at start. No format change: `voltageAgeMs` still measures the intent-side values' age
  against the property read.
