<!--
SPDX-FileCopyrightText: 2026 BluffWorks LLC
SPDX-License-Identifier: GPL-3.0-only
-->

# ChargeLog recorder

The recording engine of ChargeLog, an Android charging analyzer ([chargelog.org](https://chargelog.org)).
This module is the open-source part: an always-on foreground service that turns every plug-in
into an NDJSON session log, a Room projection of that log for queries, and a small set of
measured facts derived from it. ~2.5k lines of Kotlin plus tests and real-device fixtures.

## What it is not

No analysis beyond measured facts, no UI, no notification content of its own. The ChargeLog app
— proprietary, closed source — is built on top of this: it supplies the analytics (charge-rate
modeling, charge-phase segmentation, behavior aggregates, correlations, insights), every screen,
and the notification text a user actually sees. None of that lives here.

Concretely, what this module *does* produce is `measure/SessionFacts.kt` — per session: duration,
start and end battery level, energy moved (Ah, from the charge counter), peak power (W), peak
battery temperature (°C), and the charge source (wired / wireless / dock). Plus `measure/Units.kt`
for the raw-unit conversions each gauge needs, `measure/AnalyzerSample.kt` as a neutral per-sample
projection, and `measure/ExportCsv.kt`. Phase segmentation, rate modeling, and anything that
interprets a curve are the closed app's, not this module's.

## The seam

A host application implements `RecorderHost` (`capture/RecorderHost.kt`) and the recorder calls
into it; the recorder never reaches into the host.

- `content(state)` / `build(context, content)` — the host renders the foreground-service
  notification; the recorder only tells it what changed and when (rate-gated).
- `channelLabels()` / `chargeTargetLevel()` — user-visible strings and the settle-detection
  target the host owns.
- `onBatteryState(level, plugged, screenOn)` — every raw battery broadcast, for a host that wants
  its own alerts (a low-battery reminder, say).
- `appVersion` — stamped into every session header, so a log says which build wrote it.
- `deviceKind` and `gaugeProfile()` — what is being measured and how to read its fuel gauge.
  Both ship with phone defaults, so an existing host keeps compiling when the seam grows; a
  watch or another form factor overrides them.

`RecordingService` resolves the host once, as `applicationContext as RecorderHost`, and fails
fast if the cast doesn't hold.

Embedding the library also merges its manifest into yours: five permissions, the recording
service, and the boot receiver. One merged value is a claim about your app rather than a
capability — the `specialUse` foreground-service justification Play requires, which ships with
ChargeLog's own wording. **Replace it with yours before you submit**; `BUILDING.md` has the
`tools:replace` recipe under "Invariants".

## Quick start

```
./gradlew :sample:assembleDebug
```

builds a minimal Compose app that implements `RecorderHost` and shows the recorder running.
`./gradlew :recorder:testDebugUnitTest` runs the library's own unit suite (no device needed).
`BUILDING.md` has the prerequisites, including the Android SDK setup a fresh clone needs.

## Contributing device evidence

The recorder ships with real-session NDJSON fixtures used as golden test data. If you can add a
session from a device/charger combination not already represented, that is the single most useful
contribution to this project — run the sample, plug in, let a session complete, then open a PR
attaching the NDJSON from `no_backup/rawlog/` (`scripts/pull-logs.sh` pulls it; see
`BUILDING.md`). No CLA is needed for evidence — see `CONTRIBUTING.md`.

### Read this before you attach a session log

**Device evidence is contributed under [CC0-1.0](LICENSES/CC0-1.0.txt): a public-domain
dedication. It is permanent and cannot be withdrawn.** Once a session log is merged here, anyone
may copy, republish, or build on it forever, for any purpose, with no attribution owed to you.
Please open the file and read it before you attach it.

A session log is a plain-text NDJSON file. Its first line is a header, and every line after it is
a sample or an event. It contains:

| In the file | What it reveals |
|---|---|
| `deviceModel`, `osRelease` | Your phone's public product name and OS version — e.g. `"Pixel 9 Pro"`, `"16"`. Not a per-unit identifier. |
| `deviceId` | A random 16-byte id minted by the app on first run (`capture/DeviceIdentity.kt`). Not derived from any hardware identifier, but **stable across all sessions from that install** — two logs you contribute are linkable to each other. |
| `sessionStartWallClockMs` and every sample's `t` | Real wall-clock timestamps, in UTC ms. They say what time of day you plugged in and unplugged, on what date. |
| `screenOn`, `screen_on` / `screen_off` events | A roughly 1 Hz trace of whether your screen was on, for the whole session. Over a night's charge this is a picture of when you picked your phone up. |
| `hingeDeg`, `hinge` events | On a foldable, the hinge angle over time — when you opened and closed the device. |
| `level`, `currentRaw`, `voltageRaw`, `tempDeciC`, `thermalStatus`, `plugged` | Battery and charger measurements. This is the part the project actually wants. |
| `boot` events | When the device rebooted (or the app left the force-stopped state) during the session. |

There is no automatic scrub step, and nothing in this repository removes any of the above. If a
session's timing, screen trace, or hinge trace is more than you want in the public domain
permanently, the right move is to record a fresh session deliberately — plug in, leave the device
alone, unplug — rather than sending a log from ordinary use. You are also welcome to hand-edit a
log before attaching it; note in the PR what you removed, so the fixture isn't read as untouched
device output.

If a session log is only meant to explain a bug rather than become a fixture, say so in the
issue — a bug report does not have to be dedicated to the public domain. Use the device-report
issue template, which asks for exactly what a quirk report needs.

## Reporting problems

Bugs and device quirks: open an issue — there are templates for a device-specific quirk report
and for an ordinary bug. Security-sensitive reports go through GitHub private advisories instead;
see `SECURITY.md`.

## License

GPL-3.0-only (see `LICENSE`). Real-session test fixtures and device evidence are CC0-1.0.
"ChargeLog" and the ChargeLog mark are claimed trademarks — see `TRADEMARKS.md`.
Version and format history: `CHANGELOG.md`.
