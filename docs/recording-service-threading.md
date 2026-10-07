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

`tickJob`, `quietJob` and `plugged` are plain `var`s, not `@Volatile`, because every read and write
of them is on the main thread. `startTicking` is a check-then-act on `tickJob`, and the pump runs on
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

`screenOn`, `thermalStatus`, `lastChargingStatus`, `samplingMode`, `lastRecap`,
`lastBeatUptimeMs`: publication only — a single reference or primitive swap read from another
thread. No compound invariant is defended by any of them.

## One post at a time

`ForegroundPoster` holds the dedupe gate and the two-id swap, moved out of the service so a JVM
test can reach them. `post` and `start` are `@Synchronized`, and that is load-bearing: a post
reads and writes four fields (key, time, channel, current id) and then calls out to `build`,
`notify`, `startForeground` and `cancel`. Callers come from three dispatchers — the receiver on
main, the tick loop on `Dispatchers.Default`, the recap/reseed IO callbacks — and before the lock
a tick post could pick the primary id, stall in `build`, and land its `notify` after an unplug's
forced post had already moved to the alternate and cancelled the primary: two ongoing
notifications in the shade, the dead session's one never cancelled. `ForegroundPosterTest` stages
exactly that interleaving.

The lock serializes the gate, not the callers' content: each caller builds its `HostContent`
before it takes the lock, so a stale content object can still be posted after a newer one. The
gate drops most of these — an unforced post inside 5 s of a forced transition never lands — but
the first tick of a session is forced, so a plug and unplug a few milliseconds apart can leave the
first tick's recording text over the idle one until the next post. The id pair stays consistent
either way: one notification in the shade, never two.

`start` records the channel of what it posts. A service that restarts mid-charge posts its idle
notification first, so its first recording post is a channel change and swaps ids like any other
— before 2026-10-06 `start` left the channel unrecorded and that one post updated in place across
the channel change, which the two-id trap exists to prevent.

## The quiet re-post

Every notification the recorder posts while recording is built from a sample taken that instant,
so a host that judges freshness from the sample's age always sees it fresh — and when the tick loop
stops, nothing calls `RecorderHost.content` again. The last notification then stands, progress bar
and system chronometer included, as if the recorder were fine. `quietJob` is the second caller.

It runs on the main thread from the session's open (`openSessionIfEnabled`) to the unplug (and is
cancelled in `onDestroy` before the drain), waking every `QuietWatch.PERIOD_MS` (60 s). If the
session is open, sampling is `TICK`, and no **beat** has landed in that long, it hands
`RecorderHost.content` the last sample `LiveFeed` holds and posts the result through the usual
gate, never forced. If the ticker is also gone (`tickJob` not active), it logs and starts it again.
The host decides what an old sample means; the recorder only guarantees the call. What carries the
weight:

- **Tick sampling only.** After settle the recorder samples on battery broadcasts, and silence
  there means the battery has not changed — real captures go hours without one. The rule would
  cry wolf every night.
- **The beat is stamped by the pump**, after it has processed a `Tick` input without an
  `IOException` — not by the tick loop. A tick loop that keeps sampling into a pump stuck on a
  blocked write is no healthier than one that stopped, and `LiveFeed`, which every in-app surface
  reads, has frozen either way. `startTicking` also stamps it, or the instant between the pump
  writing `TICK` and the ticker starting would see a beat from before the settle.
- **The sample is `LiveFeed`'s**, the last one the pump persisted — the same reading the app's own
  rows judge, so the shade and the app agree. `LiveFeed.onOpen` clears it, so a session can never
  re-post the previous one's reading.
- **It reads `samplingMode`, not `tickJob`, and outlives `stopTicking`.** The pump's
  `SetSampling(EVENT)` calls `stopTicking` on every settle; a check that died with the ticker could
  never notice a missing one. A resume whose main-thread hop never started a ticker leaves the mode
  `TICK` with no ticker, and that is exactly what it sees — and re-arms.
- **On main.** `plugged`, `tickJob` and `startTicking` are main-thread-confined, and the unplug's
  forced idle post runs on main, so the check can never interleave with it.
- **Silence is measured in awake time** (`SystemClock.uptimeMillis`; `delay` on `Dispatchers.Main`
  is a `Handler` timer on the same clock). A suspended CPU stalls the ticker and this loop alike;
  measuring on `elapsedRealtime` would let the first check after a wake beat the first resumed tick
  and hold a false "quiet" for five seconds.

The loop sets no alarm and holds no wakelock, so it adds no wakeups: a session is open only while
plugged in, where Doze and App Standby do not apply.

`samplingMode` is decided by `SessionStateMachine` alone: the pump writes it from `SetSampling`,
the broadcast receiver reads it on the main thread to know whether a `BATTERY_CHANGED` is itself
the sample. Nothing outside the pump ever decides it.

Opening a session declares `TICK` itself, so a stale `SetSampling(EVENT)` from the session that just
closed can never leave the next one tickerless: the open's own effect comes after it in the queue.

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
