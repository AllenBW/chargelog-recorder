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
touches the `SessionStateMachine`, the session's `RawLogWriter` or `wakeLock`. It does so through
`EffectRunner`, which owns the machine's output, the writer and the gauge probe, and reaches the
service's wakelock, event log, sampling mode and recap through `EffectPorts`.

Before this existed, the receiver and the tick loop both ran `machine.on()` and then the effects
it returned, unsynchronized. That crashed the process twice in a 50-trial stress run:
`IOException: Stream closed`, a `close()` landing mid-`append()`.

## Main-thread-confined state

`tickJob` and `quietJob` are plain `var`s, not `@Volatile`, because every read and write of them is
on the main thread. `plugged` is written only there too, and every check-then-act on it runs there;
it is `@Volatile` because the guarded posts ("One post at a time") also read it, off the main
thread, inside the poster's lock. `startTicking` is a check-then-act on `tickJob`, and the pump
runs on `Dispatchers.Default` — so the pump's `SetSampling` effect **posts to `mainHandler`**
rather than touching `tickJob` itself. Without that hop there is no happens-before edge between
the two, and a resumed ticker could survive an unplug while the next plug launched a second loop
beside it.

### The orphan-ticker window

`plugged` closes a narrower version of the same race. A `SetSampling` resume posted just before an
unplug can land just *after* that unplug's `stopTicking`, restarting the loop while the device sits
unplugged. The orphan writes nothing and holds no lock — but it calls
`poster.post(host.content(...))` every tick, so it replaces the idle notification with
live-recording text until the next plug event. Checking `plugged` at post-run time is the guard,
and it needs no memory barrier because both sides are the main thread.

## What `@Volatile` is doing

`screenOn`, `thermalStatus`, `lastChargingStatus`, `samplingMode`, and `QuietWatch`'s
`lastBeatMs`: publication only — a single reference or primitive swap read from another thread. No
compound invariant is defended by any of them. `plugged` is published the same way; the order the
guarded posts rely on comes from the poster's lock, below. The last recap is not among them: which
one is kept is a compare-and-set, not a swap ("Which recap the idle notification names", below).

## One post at a time

`ForegroundPoster` holds the dedupe gate and the two-id swap, moved out of the service so a JVM
test can reach them. `post` and `start` are `@Synchronized`, and that is load-bearing: a post
reads and writes four fields (key, time, channel, current id) and then calls out to `build`,
`notify`, `startForeground` and `cancel`. Callers come from three dispatchers — the receiver on
main, the tick loop on `Dispatchers.Default`, the recap, reseed and record-switch posts on
`Dispatchers.IO` — and before the lock
a tick post could pick the primary id, stall in `build`, and land its `notify` after an unplug's
forced post had already moved to the alternate and cancelled the primary: two ongoing
notifications in the shade, the dead session's one never cancelled. `ForegroundPosterTest` stages
exactly that interleaving.

The lock serializes the gate, not the callers' content: each caller builds its `HostContent`
before it takes the lock, so a stale content object can still reach it after a newer one. The gate
drops an unforced one, because an unforced post inside 5 s of a forced transition never lands.
Every post made off the main thread is guarded as well, and so is the unplug's idle post: it passes
`post` a `superseded` check, which `post` reads inside the lock, after any post that held the lock
has landed.

- **The recaps' idle posts** (the last session's, at the end of `onCreate`, and each closed
  session's) and **the record switch's** are superseded while `plugged` is true: a session is open,
  and its own notification stands.
- **Every idle post**, the unplug's included, is also superseded once a recap newer than the one
  it carries has been kept: that recap's own post lands in its place ("Which recap the idle
  notification names", below).
- **Every idle post** is also superseded once the record switch no longer reads what its content
  shows: the post that the change set off lands in its place ("Which switch state the idle
  notification shows", below).
- **A tick's post** is superseded once `plugged` is false: the unplug that stopped the ticker has
  posted the idle notification, or is about to, or a newer recap's post has in its place.

`plugged` is written on the main thread: set in `openSessionIfEnabled` before the ticker starts,
cleared at the unplug before its own forced idle post. A session's first post therefore reaches
the lock only after `plugged` went true, and a recap that takes the lock after it reads true and
is dropped. A recap that takes the lock first lands first, and the session's forced first post
replaces it. A tick and the unplug are the same order the other way round. The id pair stays
consistent either way: one notification in the shade, never two.

The check is `plugged`, never `LiveFeed`'s `recording`, even inside the lock. `LiveFeed` is the
pump's flag, and the pump can run behind the main thread. A plug-in sets `plugged` and starts the
ticker at once, and the session's first tick posts its recording notification while the pump may
still be finishing the last session's close. Until the pump reads the plug-in, `LiveFeed` says no
session is open although that session's notification is showing, and a recap or switch post that
asked `LiveFeed` would land over it.

Until 2026-10-18 the recaps and the switch read `LiveFeed`'s `recording` before building their
content, and posted without looking again. A plug-in that landed in between opened a session whose
first tick posted the recording notification, also forced; the idle post landed after it, and its
text stood over the session until the gate let a later tick through, five seconds on. On the
charger at service start this was a race against the pump: `LiveFeed` is the pump's flag, and a
session's first tick can post before the pump has opened the session. A closed session's recap
read slowly (a long log) met the next plug-in the same way. In the mirror case, the first tick of
a plug-in unplugged a few milliseconds later could land after the unplug's idle post and leave the
recording text standing until the next post. A test could not wait those five seconds out: under
Robolectric's paused looper `SystemClock.elapsedRealtime` stands still while the test polls, so the
gate never opens.

`ForegroundPosterTest` pins that `superseded` is read inside the lock, after the post that held
it: a recap blocked behind a session's first post reads the session that post opened, and is
dropped.

`start` records the channel of what it posts. A service that restarts mid-charge posts its idle
notification first, so its first recording post is a channel change and swaps ids like any other
— before 2026-10-06 `start` left the channel unrecorded and that one post updated in place across
the channel change, which the two-id trap exists to prevent.

### Which recap the idle notification names

`RecorderState.Idle.recap` is the most recently finished session. The service reads a recap once
at start-up, of the newest log it did not open itself, and once for each session the pump closes,
every read on `Dispatchers.IO`. A read takes as long as its log, so they finish in any order.
`NewestRecap` keeps the one that names the newest charge:

- **The start-up read leaves out every log this service opened.** On the charger, `onCreate` opens
  a session before the start-up read lists the directory, and a plug-in can land before the
  listing too, so the pump may already have created the session's log. Read, that log is the charge
  in progress: a partial recap, or none before its first sample. Every log this service opens gets
  its own recap when it closes, so the start-up read needs none of them. `openSessionIfEnabled`
  records the session's file name (`opening`, from the start time it hands the pump, which names
  the file) before it submits the plug-in, so the name is recorded before the pump can create the
  file. `previousLog` lists the directory first and only then takes the names, so every log in the
  listing that this service opened is among them; from then on nothing more is recorded. The rule
  goes by name, not by timing: it never waits for the pump or asks it what it has open.
- **A recap ranks by when its session closed, never by when its read finished.** The pump numbers
  each close as it runs it (`closing`), so the numbers follow the order the charges ended. The
  start-up read ranks below every close (`STARTUP`): the log it reads closed before this service
  started. `offer` keeps a read only when it ranks above the one kept, in one atomic step, so a
  read of an older charge that finishes late is dropped.
- **The rank is not the wall clock.** `endedAtMs` ranks the same charges the same way until the
  clock steps back between two of them, and it cannot rank a recap that is absent. A closed
  session whose log cannot be read (deleted, unreadable, past `Replay.MAX_SAMPLES`) has no recap,
  and an older charge's must not come back in its place: the idle notification shows none, as it
  did before.
- **An idle post carries the read it was built from, and is dropped once a newer one is kept.** A
  recap's post carries its own read; the record switch's and the unplug's carry the read kept when
  they start. Each `superseded` check, inside the poster's lock, also asks whether that read is
  still the newest. When it is not, a newer read was kept after the content was built, and that
  read's own post, built after it was kept, lands in its place, unless a session has opened and the
  session's notification stands. If the switch changed after that post was built, the change's own
  post, which carries the newest read too, stands in for it (below). Without the check, content
  built just before a newer read was kept could land after that read's post.

Until 2026-10-18 the recap was one `@Volatile` field that each read wrote when it finished. A
start-up read of a long log that finished after a short charge's close put the older charge back
in the idle notification, and in each recording notification's `lastRecap`, until the next close.
An idle post whose content was built before a newer recap was stored (a recap's, the record
switch's, the unplug's) could land after that recap's post and stand until the next idle post.
Display-only, both.

Until 2026-10-18, too, the start-up read took the newest log it listed. On the charger that could
be the session the pump had just opened, and the recap was then of the charge in progress, partial,
or none if no sample had landed yet. The unplug's own idle post goes out before that session's
close is read, so it showed the partial recap as the last charge until the close's own recap
landed. Display-only.

`NewestRecapTest` pins the ranking and the start-up read's choice of log on the JVM, a session
opened while the directory is listed included. The host app's service tests pin each interleaving
on the running service, stopping one side at the moment that used to lose.

### Which switch state the idle notification shows

`RecorderState.Idle.recordEnabled` is the record switch, `CapturePrefs.recordEnabled`, read when an
idle post builds its content. Two rules keep the idle notification on the switch's current state:

- **An idle post is dropped once the switch no longer reads what it shows.** Its `superseded` check
  reads the switch again, inside the poster's lock, beside `plugged` and the newest recap. A post
  built before a change that reaches the lock after it is dropped.
- **Every change of the switch posts, from before the first post on.** `onCreate` registers a
  listener on the switch (`CapturePrefs.watchRecordEnabled`) on the main thread before its first
  post reads the switch, and `onDestroy` removes it. Each change the platform reports launches an
  idle post on `scope`, which reads the switch, and the newest recap, after that change.
- **The switch's post runs on `Dispatchers.IO`, like the recaps' posts.** It calls the host's
  `content`, which can take as long as the host likes. `Dispatchers.Default` has one worker per
  core, two on a two-core device, and the pump and the tick loop run there: a switch post stalled
  in `content` beside a pump stalled in a write left a re-plug's first tick no worker, so the
  session's notification waited on the pump. Until 2026-11-01 it ran on `Default`.

Together they never drop a post without another in its place. A post the first rule drops was built
before a change, and that change's own post was built after it: it shows the switch as it then
stood, and it carries the newest recap, because a recap is kept before its post is built. An
unplug's idle post that is dropped is replaced the same way, since `plugged` went false before it
was built. The post built after the switch's last change lands, unless a session has opened or a
newer recap has been kept, and each of those posts in its turn.

The listener calls back on every change, not on each new value. A change made off the main thread
reaches the listener later, posted to the main thread, so a change and its reversal made before the
first call both read the reversed value. A listener that passed on only a new value would pass on
neither, and a post built between the two, showing the state of a moment ago, would stand without
the first rule, and with it would be dropped with nothing after it: the recap it carried, or the
unplug's idle notification, lost until the next idle post. The listener answers to the switch's key
and to a clear of the whole file, not to the other settings a host keeps in `CapturePrefs.FILE`. A
clear reaches a listener with no key (the platform does so for an app that targets API 30 or later,
and every host targets at least the recorder's minimum, 31), and the switch then reads its default,
on, which the clear's own post shows. The platform holds a preferences listener weakly, so
`watchRecordEnabled` returns a stop that holds it, and the service keeps that until `onDestroy`,
which calls it: a destroyed service no longer listens.

The listener is registered before the first post reads the switch because the platform fixes the
listeners a change will reach when the change is made, not when it reports it. Registered after
that read, it would miss a change made in between, while the first notification is being built, and
the idle notification would show the old state until the start-up recap's post: that waits for the
read of the previous log, which takes as long as the log, and is lost if the host's `content` throws
on it.

Until 2026-10-18 no idle post looked at the switch again before it landed, and the switch's posts
came from one coroutine collecting `CapturePrefs.recordEnabledFlow` without its first value. The
idle notification showed the switch's old state, until the next idle post, three ways:

- A recap's, the start-up read's or the unplug's post built just before the switch changed, which
  reached the lock after the change's own post, landed over it.
- The collector subscribed on a worker thread after `onCreate`'s first post had read the switch. A
  change between the two was the flow's first value, which the collector dropped: no post followed
  the change, and a start-up recap that had posted before it stood.
- The flow passes on only a new value. A change and its reversal made off the main thread, whose
  listener calls both ran after the reversal, passed on nothing, and a post built between them
  stood.

The one collector also ended for good the first time the host's `content` threw on one of its posts
("When anything else on the scope fails", below), and no later change posted at all. Display-only,
all four.

The host app's service tests pin each of these on the running service: a closed charge's recap held
at its post across a change, a change and its reversal made off the main thread across the start-up
recap, a change made before any of the service's work on `Dispatchers.Default` has run, and a change
whose post's host call throws, followed by another. They also pin three parts of the fix: a clear of
`CapturePrefs.FILE` must show the switch back on, a change made while the first notification is
being built must post before the start-up recap's read has finished, and a destroyed service must
no longer listen to the switch.

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
- **The beat is stamped by the pump** — `EffectRunner.handle` calls `EffectPorts.beat` after a
  `Tick` input whose every effect ran — not by the tick loop. A tick loop that keeps sampling into
  a pump stuck on a blocked write is no healthier than one that stopped, and `LiveFeed`, which
  every in-app surface reads, has frozen either way. `startTicking` also stamps it, or the
  instant between the pump writing `TICK` and the ticker starting would see a beat from before the
  settle.
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

## When an effect fails

`EffectRunner.handle` asks `SessionStateMachine` for an input's effects and runs them, each in its
own `try`. An effect that throws — a full disk, a log directory that cannot be written, a host
callback that misbehaves — is logged, and the rest of the batch still runs.

The machine has already moved on by the time it hands a batch over. An unplug's batch is
`power_disconnected` into the event log, then `session_end`, `CloseLog` and `ReleaseWakeLock`, and
the machine is no longer recording. Until 2026-10-17 the batch ran in one loop inside a catch of
`IOException` alone, so an event-log write that failed at unplug skipped the other three: the log
stayed open, the partial wakelock stayed held until `MAX_WAKELOCK_MS`, `LiveFeed` still said
recording. The next plug-in's `OpenLog` then met `RawLogWriter.open`'s "already open"
`IllegalStateException`, which nothing caught on a `Dispatchers.Default` worker, so the process
died, and `START_STICKY` restarted it into the same state. Every sample after a session log that
could not be created threw the same way ("no open session log").

What holds now:

- **Every effect runs**, the closing ones above all. A session whose `session_end` could not be
  written is still closed, and its wakelock still released.
- **A later open succeeds.** `RawLogWriter.open` is all or nothing (a header that cannot be written
  closes the writer again), and `OpenLog` closes a log that is somehow still open before it opens
  the next, so a new session never writes into the previous one's file.
- **Any `Exception` is contained; an `Error` is not.** After an `OutOfMemoryError` there is no state
  worth keeping, and the process should end. `RawLogWriter`'s two `IllegalStateException`s stay:
  they say a caller got the order wrong, and the runner is what keeps them from being fatal.
- **A failed batch is not a beat.** `handle` returns false when any effect failed, and beats the
  quiet watch only after a clean `Tick`; no other input beats it. A disk that refuses every sample
  goes quiet in the shade after a minute, exactly as a stalled ticker does.
- **What was not written is not shown.** A sample whose write failed reaches neither `LiveFeed` nor
  the gauge probe; both read what the log holds.

`EffectRunnerTest` pins each of these on the JVM, against a real `SessionStateMachine` and
`RawLogWriter`; `RawLogWriterTest` pins the writer's all-or-nothing `open` and its two
`IllegalStateException`s.

A log is still open at a plug-in only when the closing effects of the session before it were
skipped. So after an unplug whose event write failed, the next plug-in records into a log of its
own while either of two guards holds: every effect running, or `OpenLog`'s close. Each is pinned
alone. A runner that stops at the first failed effect leaves the session with no `session_end`. An
`OpenLog` that no longer closes a log left open before a plug-in fails to open the new session's
log. Only with both gone does the unplug path itself fail, naming the log the next plug-in should
have opened.

## When anything else on the scope fails

The pump is one of several coroutines on `scope`. The tick loop, the quiet watch, the
record switch's posts and the two recap reads (at service start and after every close) run there
too, and each calls into the host: `RecorderHost.content`, and `build` through the poster. The pump
contains its own failures, above; the scope's `CoroutineExceptionHandler` contains theirs.

- **An `Exception` ends that coroutine alone.** It is logged, and the `SupervisorJob` keeps the
  rest running: the pump still writes, and an unplug still ends the session and releases the
  wakelock. Until 2026-10-17 the scope had no handler, so a host whose `content` threw on the tick
  loop reached the thread's uncaught-exception handler and ended the process.
- **A tick loop ended this way is a ticker gone**, and the quiet watch re-arms it a minute later.
  If the host's `content` throws on the quiet watch's own re-post as well, that loop ends too, and
  the session samples no more until the next plug-in.
- **A record switch post ended this way is that post alone.** Each change launches its own, so the
  next change posts again. Until 2026-10-18 one collector made them all, and the first throw ended
  it until the service restarted.
- **An `Error` is rethrown.** kotlinx.coroutines hands a handler's own throw to the thread's
  uncaught-exception handler, which ends the process, as an `Error` from the pump does.
- **Not every call is on the scope.** The broadcast receiver's posts and `onCreate`'s first post
  run on the main thread outside any coroutine, and a host that throws there still ends the
  process.

## A hold below the target

`SettleDetector`'s first three predicates (status `FULL`, `NOT_CHARGING` at the target, the level
pinned at its running maximum at or above min(target, 95) with trickle current) all assume the
charge stops at or near the user's target. A device that holds it lower meets none of them: a
phone at an OEM charge limit of 80–85 % or in an Adaptive Charging hold under a 100 % target,
still reporting `CHARGING`, or a watch whose battery protection holds 80 % at `NOT_CHARGING`.
Until 2026-10-18 such a session stayed on the one-second tick until the unplug, holding the wake
lock up to its three-hour safety timeout (`MAX_WAKELOCK_MS`, "Teardown" below). The current moves
on almost every tick and `SampleGate` persists any change, so that was 3,600 sample lines an hour,
tens of megabytes a day, and about four days of it passed `Replay.MAX_SAMPLES`.

The fourth rule: **a charge whose level, status, plug type and charging status have all stayed the
same for 30 minutes settles**, at any level and under any target, exactly as a full charge does:
`capture_policy settled`, the event cadence, the wake lock released, `LiveFeed` event-sampled. It
resumes on the first sample in which any of those four differs, as well as on the two resumes the
other predicates have. Every resume restarts the 30 minutes, including a bounce that changed none
of the four, and so does a plug-in: a session never inherits the quiet time of the one before,
even when it is plugged in again in exactly the state the last one ended in. It never withdraws
trust in the pinned predicate, because it was not the pinned predicate that settled.

- **Why those four fields.** Once settled, a sample arrives only with an `ACTION_BATTERY_CHANGED`.
  The platform broadcasts every change of level, status, plug type and charging status, while a
  change of current alone broadcasts nothing. The rule settles only on what its resume is certain
  to see, so a held charge that starts to move is back on the tick at the broadcast that says so.
- **Why 30 minutes.** It has to outlast every level step of a charge that is still climbing, or a
  slow charge would drop to broadcasts between steps. In the recorder's test corpus the longest
  one-level stretch at `CHARGING` is the watch's at 100 % before `FULL`
  (`watch/real/session-1788175545922`): 16.6 minutes from its first sample at 100 % to the
  broadcast that reported `FULL`, 6.2 of them confirmed by a sample still at `CHARGING`. It follows
  a rise past the 99 % a pinned settle stopped at, so today's recorder is back on the tick through
  it and only `FULL` or this rule can settle it. Next come about 10 minutes at 100 % before `FULL`
  (`session-1787605010301`) and the watch's 6 minutes at 99 %, and below 99 % none passes 3. Half
  an hour is nearly twice the longest, and leaves room over every capture the project holds; 15
  minutes would not, and would have settled that watch charge at 100 % before it reported `FULL`.
  A climb slower than half an hour a step is a battery gaining under 2 % an hour, about 100 mA on a
  5,000 mAh cell, and even then the next level step is a broadcast that resumes it, so a slow
  charge mistaken for a hold costs one sparse step, not its record.
- **What it costs.** Each hold runs 30 minutes on the tick, about 1,800 lines, before it settles.
  A thermal pause longer than that settles too, and is sampled on broadcasts until its status
  changes or the level moves. A charge that starts again after a hold is on broadcasts until its
  first level step or status change, a few minutes at any real current. The settle writes the
  same `capture_policy settled` line a full charge writes; the log format does not change.
- **The alternative rejected: rotating sessions.** Closing the log at a size or an age and opening
  the next would bound each file but not what is written: the device would still write 3,600 lines
  an hour and hold the wake lock. It would also split one plug-in into several sessions that every
  reader (the recap, the history, a sync) would have to stitch back together, each with a
  `session_end` claiming an unplug that never happened. Settling stops the writing at its source
  and keeps one plug-in one session.

`HeldChargeSettleTest` drives the machine through a six-hour hold below a 100 % target, sampling
on the tick or on simulated broadcasts as the service does: 3,600 lines in every hour before this
rule, a dozen an hour after the first half hour now. It also pins the boundary (29:59 stays on the
tick, 30:00 settles), the resume on each of the four fields, that a slow climb never settles, that
a bounce off a settle the four fields had been quiet through for 40 minutes still waits a full
half hour, and that a re-plug in the state a 40-minute hold was unplugged in waits one too;
`SettleDetectorTest` pins the other three predicates and their resumes, unchanged.

## Read-once configuration

`tickMs` is read from `CapturePrefs.sampleIntervalS` in `onCreate` and **never re-read**. It feeds
both the tick loop's `delay` and `SamplerProfile.tickMs`, which `SessionStateMachine` passes
straight into `SampleGate.offer`'s gap-detection math — so a value that changed mid-session would
corrupt the sample-gap invariants the pump depends on. A changed setting takes effect on the
*next* service start.

Default: `CapturePrefs.DEFAULT_SAMPLE_INTERVAL_S * 1000` = 1000 ms. Faster than 1 s wastes battery
for no analysis benefit; slower than 5 s starts missing short charge bursts.

## Teardown

`onDestroy` closes the poster first. A post from any coroutine still running is dropped from then
on: the recap that the drain's own close of the session launches, a recap still reading when the
service was stopped, a tick in the middle of its loop. `close` takes the poster's lock, so a post
already in flight when teardown began has landed before it returns. Until 2026-10-18 such a recap
posted from its IO thread after `scope.cancel()`, from a service already destroyed: its coroutine
has no suspension point between the read and the post, so cancelling the scope did not stop it,
and the post put an ordinary notification in the shade that no running service owned.
`ForegroundPosterTest` pins that a closed poster posts nothing and that `close` waits for a post in
flight.

`TEARDOWN_DRAIN_MS` (2 s) bounds how long `onDestroy` blocks draining the pump. Every append is
flushed, so a drain that times out costs the session's `session_end` line — replay marks it
`TRUNCATED` — and never already-written data. An ANR in service teardown would cost far more.

`MAX_WAKELOCK_MS` (3 h) is a safety net sized to a maximum plausible session. The settle detector
normally releases the lock; this bounds the damage if a session somehow never settles and never
sees its unplug.
