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

**The Room database version** (currently **2**) is a private implementation detail. The database
is a projection rebuilt from the NDJSON logs, which are the source of truth; a host that wipes it
loses nothing.

`BuildConfig.VERSION_NAME` from the *host* app — not from this library — is what the recorder
writes into each session header's `appVersion`. A fixture therefore records which build of which
app produced it, which is why the field is there.

## [Unreleased]

First public release in preparation. Everything below describes the state of the code as it is
published, not a change from a previous public version — there isn't one.

### The starting point

- `RecorderHost`: the eight-member seam a host implements. Foreground-service notification
  content and rendering, channel labels, charge-target level, raw battery-state callbacks, the
  app version stamped into session headers, and the device kind and gauge profile (both with
  phone defaults, so the seam can grow without breaking an existing host).
- `RecordingService`: always-on `specialUse` foreground service, started from `BOOT_COMPLETED`,
  1 Hz sampling while plugged, wake lock held only for an unsettled session.
- NDJSON capture log, **schema 2** — one append-only file per session plus a rolling
  `events.ndjson`. Format documented in `docs/ndjson-format.md`.
- Room projection (**database version 2**) rebuilt from the logs by `data/Replay.kt`, with
  `Replay.reconcile()` on the recording → idle edge.
- `measure/`: `SessionFacts` (duration, level delta, energy, peak power, peak temperature, charge
  source), `Units` raw-unit conversion, `AnalyzerSample`, CSV export.
- Fuel-gauge quirk profiles — declarative only, per `CONTRIBUTING.md`'s hard rule.
- `:sample`: a minimal Compose host app that implements `RecorderHost` end to end.
- Real-session NDJSON fixtures and device evidence from the S0–S3 spikes, CC0-1.0.
- Licensing: GPL-3.0-only, REUSE 3.3 compliant, contributor CLA for code (not for evidence).
