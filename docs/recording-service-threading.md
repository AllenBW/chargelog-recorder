<!--
SPDX-FileCopyrightText: 2026 BluffWorks LLC
SPDX-License-Identifier: GPL-3.0-only
-->

# RecordingService: one serialization domain

Capture state has exactly one owner. Everything else enqueues.

## The rule

Inputs arrive from the main thread (broadcast receiver, thermal listener, hinge callback, service
lifecycle) and from the tick loop on a `Dispatchers.Default` worker. Producers **only enqueue**,
onto an `UNLIMITED` channel so a `trySend` from a broadcast receiver neither blocks nor drops.

`inputPump` — a single coroutine, so it processes one input at a time — is the **only** code that
touches `machine`, `writer` or `wakeLock`.

Before this existed, the receiver and the tick loop both ran `machine.on()` + `execute()`
unsynchronized. That crashed the process twice in a 50-trial stress run:
`IOException: Stream closed`, a `close()` landing mid-`append()`.

## Main-thread-confined state

`tickJob` and `plugged` are plain `var`s, not `@Volatile`, because every read and write of both is
on the main thread. `startTicking` is a check-then-act on `tickJob`, and the pump runs on
`Dispatchers.Default` — so the pump's `SetSampling` effect **posts to `mainHandler`** rather than
touching `tickJob` itself. Without that hop there is no happens-before edge between the two, and a
resumed ticker could survive an unplug while the next plug launched a second loop beside it.

### The orphan-ticker window

`plugged` closes a narrower version of the same race. A `SetSampling` resume posted just before an
unplug can land just *after* that unplug's `stopTicking`, restarting the loop while the device sits
unplugged. The orphan writes nothing and holds no lock — but it calls
`updateNotification(host.content(...))` every tick, so it replaces the idle notification with
live-recording text until the next plug event. Checking `plugged` at post-run time is the guard,
and it needs no memory barrier because both sides are the main thread.

## What `@Volatile` is doing

`screenOn`, `thermalStatus`, `lastChargingStatus`, `samplingMode`, `lastRecap`, `lastNotifiedText`,
`lastNotifiedAtE`, `lastNotifiedChannelId`, `currentNotificationId`: publication only — a single
reference or primitive swap read from another thread. No compound invariant is defended by any of
them.

`updateNotification` runs from three threads (receiver on main, tick loop on `Dispatchers.Default`,
and the recap/reseed IO callbacks), as `RecorderHost.build` always has. A lost race between
concurrent callers costs at most one extra or skipped `notify()` — never a crash, never a stuck
notification.

`samplingMode` is decided by `SessionStateMachine` alone: the pump writes it from `SetSampling`,
the broadcast receiver reads it on the main thread to know whether a `BATTERY_CHANGED` is itself
the sample. Nothing outside the pump ever decides it.

## Read-once configuration

`tickMs` is read from `CapturePrefs.sampleIntervalS` in `onCreate` and **never re-read**. It feeds
both the tick loop's `delay` and `SamplerProfile.tickMs`, which `SessionStateMachine` passes
straight into `SampleGate.offer`'s gap-detection math — so a value that changed mid-session would
corrupt the sample-gap invariants the pump depends on. A changed setting takes effect on the
*next* service start.

Default: `CapturePrefs.DEFAULT_SAMPLE_INTERVAL_S * 1000` = 1000 ms. Faster than 1 s wastes battery
for no analysis benefit; slower than 5 s starts missing short charge bursts.

## Teardown

`TEARDOWN_DRAIN_MS` (2 s) bounds how long `onDestroy` blocks draining the pump. Every append is
flushed, so a drain that times out costs the session's `session_end` line — replay marks it
`TRUNCATED` — and never already-written data. An ANR in service teardown would cost far more.

`MAX_WAKELOCK_MS` (3 h) is a safety net sized to a maximum plausible session. The settle detector
normally releases the lock; this bounds the damage if a session somehow never settles and never
sees its unplug.
