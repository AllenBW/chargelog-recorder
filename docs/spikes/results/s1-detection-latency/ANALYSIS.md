<!--
SPDX-FileCopyrightText: 2026 BluffWorks LLC
SPDX-License-Identifier: CC0-1.0
-->

# S1 Analysis: Plug-in Detection Latency

Device: Pixel 11 Pro Fold (`yogi`). Protocol:
`S1-detection-latency.md`. Raw data under this directory
(`s1-detection-latency/`) and its `reboot-stratum/` and `force-stop-stratum/`
subdirectories.

> **Curation note.** This directory is a reduced copy of a larger private
> capture. Four things were done to it before publication, and each one is
> stated here so a reader can tell exactly what is missing and satisfy
> themselves that what remains still checks out.
>
> 1. **`logcat.txt` captures are not published** (nor, for the reboot
>    stratum, `dead-window-logcat.txt`). Device logcat carries other apps'
>    data and personally identifying values. This analysis genuinely drew on
>    them, so the findings below are the record of what those captures
>    showed at the time — but **the logcat-clock figures are not
>    independently re-derivable from the files kept here.** Everything on the
>    host clock and the device event clock is.
> 2. **The per-session `session-*.ndjson` logs were removed** — 213 files,
>    9.2 MB, 99.2% of this evidence tree's bytes. No claim in this document
>    or in any published spike doc cites one: every quantitative result here
>    is a join of `host-events.txt` (or `recovery-timeline.txt`) against
>    `events.ndjson`, both of which are kept in full. They were second-by-second
>    samples of the owner's own phone, and publishing 9 MB of them to
>    corroborate nothing was the wrong trade. Contributors who want
>    session-shaped NDJSON to work against have the eight curated fixtures in
>    `recorder/src/testFixtures/resources/corpus/`, which is what those are for.
> 3. **Wall-clock timestamps were re-baselined.** Every epoch timestamp in
>    this directory — host clock and device clock alike — has had one single
>    constant subtracted from it, so that `t=0` is the earliest instant
>    anywhere in the published evidence and all values are offsets in seconds
>    (host files) or milliseconds (`events.ndjson`) from that origin. **They
>    are no longer epochs.** One constant applied to both clocks preserves
>    every delta exactly, including the host↔device skew reported in §2; every
>    number in this document is a difference, so nothing here changes. What it
>    removes is the day and hour at which a private individual was at home
>    plugging in a phone, which no finding needs. The absolute timestamps
>    survive only in the unpublished private originals.
>
>    **The `e` (`elapsedRealtime`) column was re-baselined too**, for the
>    first of the three boot sessions below. Left raw it would have undone
>    (3): `e` is monotonic-since-boot, so subtracting it from a wall-clock
>    `t` yields the boot instant, and any reader who has a raw `t`/`e` pair
>    from this handset — the corpus fixtures under
>    `recorder/src/testFixtures/resources/corpus/` are published with both —
>    could recover that instant and, with it, the constant subtracted above.
>    For that first session `e` is now milliseconds from **its own first
>    published event**, not from boot. The two later sessions begin at a real
>    reboot, their `e` is already bounded by this run, and they are unchanged
>    — which is why §3's `e=64798` and `e=70461` still read as they were
>    recorded. Deltas within every session are preserved exactly.
> 4. **The directory was renamed** from its capture-timestamped original to
>    `s1-detection-latency/`, since the old name re-encoded the wall-clock
>    date that (3) removes.
>
> The sibling `results/s0-recon/` node dumps additionally had a set of
> device-identifying battery fields removed; `S0-recon.md`'s curation note
> lists them by name.

## 1. Alive-service stratum (n=50)

Source: `host-events.txt`, `logcat.txt` (~24 MB, `-v epoch`, continuous
capture for the full run), `device-logs/events.ndjson`. Joined by nearest
`power_connected` event within an 8 s window of each host `plug` command
(one-off join script, not committed — see §6).

- **50/50 trials joined.** One additional `power_connected` event
  (t=21865.447) fell outside every trial window — it lands 6.6 s after
  the final `unplug 50` and matches the mechanism S0 recon documented:
  the script's closing `cmd battery reset` restores the real (still-plugged)
  hardware state and itself fires one more `POWER_CONNECTED`. Not a miss,
  not a trial — the harness's own teardown.
- **manifest_receiver count: 0** (also 0 across every other pull in this
  run — reboot-stratum, force-stop-stratum). Confirms that no
  manifest-registered broadcast receiver fired on this API level: the
  app's only path to `power_connected` is the context-registered receiver
  in the always-on service.

| Clock | n | median | p90 | max | min |
|---|---|---|---|---|---|
| Host (`event.t` − adb plug-cmd timestamp) | 50 | 2.563 s | 2.582 s | 2.629 s | 2.535 s |
| Logcat (`event.t` − earliest `ACTION_POWER_CONNECTED` dispatch line) | 50 | 0.499 s | 0.503 s | 0.506 s | 0.490 s |

**Pass: p90 < 3 s on both clocks**, comfortably. The logcat-clock number
(~0.5 s) is the app's own receiver-to-log latency; the host-clock number
(~2.56 s) additionally carries adb round-trip and the ~2 s framework
propagation lag S0 recon established. Both distributions are tight (host
range 2.535–2.629 s, logcat range 0.490–0.506 s) — no long tail across 50
trials.

### Finding: two mid-run process crashes (real bug, not a measurement artifact)

`events.ndjson` shows two `service_start` entries inside the run window
(t=21412725, t=21842465) with no `boot` event nearby — i.e. the
app process died and Android restarted it mid-run, not via reboot. Cross-
referencing `logcat.txt` around both timestamps found the cause of the
second one directly:

```
FATAL EXCEPTION: DefaultDispatcher-worker-1
Process: io.github.allenbw.chargelog.debug, PID: 29092
java.io.IOException: Stream closed
	at java.io.BufferedWriter.ensureOpen(BufferedWriter.java:107)
	at java.io.BufferedWriter.write(BufferedWriter.java:224)
	at java.io.Writer.write(Writer.java:249)
	at io.github.allenbw.chargelog.capture.log.RawLogWriter.append(RawLogWriter.kt:33)
	at io.github.allenbw.chargelog.capture.RecordingService.execute(RecordingService.kt:184)
	at io.github.allenbw.chargelog.capture.RecordingService.access$execute(RecordingService.kt:37)
	at io.github.allenbw.chargelog.capture.RecordingService$startTicking$1.invokeSuspend(RecordingService.kt:169)
```

The first crash (t≈21411.130, PID 26017) shows the identical stack
(same `RawLogWriter.append` → `RecordingService.execute` → `startTicking$1`
chain, truncated in logcat but same site). Android logged both as
`ActivityManager: Process ... has died: prcp FGS` followed by `Scheduling
restart of crashed service ... in 1000ms for start-requested`, and both
restarts landed within ~1 s, matching `RecordingService`'s FGS restart
scheduling.

**Root cause (diagnosed from this run; fixed in the commit that carries
this update — line numbers below are anchored to the code as it stood for
this run and will drift from the current tree):** the capture core had two
independent threads driving it with nothing serializing them.

- **Main thread.** `powerReceiver` is registered with `registerReceiver(...)`
  and no `Handler` (`RecordingService.kt:123`), so `onReceive` — and the
  `machine.on(...)` + `execute(...)` pair it runs on every plug and unplug
  (`:77`, `:82`) — executes on the main thread. So do the thermal listener
  (dispatched via `mainExecutor`, `:62-68`/`:114`), the hinge callback
  (`SensorManager`'s default main-looper delivery, `:107-112`), the
  already-charging path at the end of `onCreate` (`:137-146`), and
  `onDestroy`'s terminal close (`:151`).
- **A `Dispatchers.Default` worker.** The 1 Hz tick loop runs its own
  `execute()` inside `scope` — `CoroutineScope(SupervisorJob() +
  Dispatchers.Default)`, a pool, not a confined thread (`:52`, `:169`).

Both paths drove the same `RawLogWriter`
(`RawLogWriter.kt:31-42`),
the same `SessionStateMachine.recording` flag, and the same `wakeLock`
field, with no lock, no confinement, and no volatile. Under S1's rapid
plug/unplug cadence the main thread's state-machine-driven `CloseLog`/
`OpenLog` pair lands close in time to the tick loop's `Append`: the main
thread's `close()` sets `writer = null` and closes the underlying stream
while the pool thread is mid-`append()`, producing exactly this
`IOException: Stream closed`. The `FATAL EXCEPTION:
DefaultDispatcher-worker-1` header names the *victim* (the tick loop,
caught mid-write), not both racers — the other racer was the main thread,
which is why the stack shows only the tick side. Crashing is the loud half
of the failure; an interleaving that loses a line instead of throwing is
silent data loss.

**Fix applied:** one serialization domain for the whole input path. Every
producer — broadcast receiver, thermal listener, hinge callback, tick loop,
service lifecycle — now only enqueues a `CaptureInput` onto an unbounded
`Channel`, and a single pump coroutine draining that channel is the only
code that touches the writer, the state machine, or the wake lock.
Teardown joins the cancelled tick job and drains the channel before the
terminal `CloseLog`, so the session's `session_end` is genuinely last. This
was a genuine concurrency bug — the kind S1's rapid-trial volume load is
designed to surface — not a measurement artifact.

**Impact on this run's results: none observed.** Every one of the 50
plug trials still captured its `power_connected` event (50/50 joined,
tight latency distribution above); the FGS auto-restart happened to land
between trials both times. It needed fixing before shipping a reliable
capture path (a session log line lost to this race is silent data loss,
not just a crash) and now is, but it did not compromise the S1 alive-service
latency measurement. Filed here as a finding, not filed upstream (it's this
app's own bug, not a platform issue).

## 2. Reboot stratum

**Fix-round update (review findings 1 and 2 below):** the original pass
here ran 10 simulated-toggle trials only *after* the service had already
recovered, which validates post-recovery behavior but says nothing about
event loss during the dead window itself — the direct analog of what the
force-stop stratum tests (§3). It also reported a recovery-latency table
whose headline rows (reboot-issued, boot_completed, CE-unlock) were
computed from timestamps that were never committed to a file, unlike
every other timing figure in this document. Both are fixed with one
additional reboot pass below: a traceable, host-timestamped timeline
(`reboot-stratum/recovery-timeline.txt`, committed) plus toggle attempts
made *during* the dead window itself (continuous logcat capture this
time, `reboot-stratum/dead-window-logcat.txt` — not published, per the
curation note above), mirroring the force-stop methodology. The original
post-recovery 10-trial data is kept below, relabeled, as what it actually
is: post-recovery validation, not dead-window evidence.

**Operationalization note** (the S1 protocol's table is ambiguous on trial
count per reboot): one reboot, attempting simulated toggles throughout the
dead window at the same cadence as the alive/force-stop strata (6 s
plugged, 4 s unplugged), continuing until recovery is detected. This is the
direct force-stop analog the review called for. Ten independent reboot
cycles (each requiring a physical PIN unlock) remains out of scope for the
reasons in the original note: it would cost far more wall-clock/owner-
attention time than this pass's dispatch implies, and the dead-window
duration itself (bounded by how long recovery actually takes, not by a
target trial count) is part of what's being characterized.

### Dead-window toggle attempts (n=3, traceable)

Source: `reboot-stratum/recovery-timeline.txt` (host-timestamped, every
line committed) joined against `reboot-stratum/device-logs/events.ndjson`
(device-side `boot`/`service_start`) and
`reboot-stratum/dead-window-logcat.txt` (continuous capture started right
after `adb wait-for-device`, never dumped/rolled — not published).

This reboot's dead window ran from `prime-unplug` (t=32292.596) to
`service_start` (t=32322.535, from `events.ndjson`) — **29.9 s**.
At the 10 s toggle cadence, 3 full plug/unplug pairs fit inside that
window before recovery (attempt 4's `plug` command, issued at
t=32325.787, landed after `service_start` and is therefore a
post-recovery trial, not a dead-window one — reclassified into the
post-recovery data below).

- **3 toggles attempted during the dead window, 0 captured** —
  `events.ndjson` has zero `power_connected` events in
  `[32292596, 32322535)` (ms). Same null result as the
  force-stop stratum, for the same reason: no receiver is registered yet
  to catch the broadcast.
- **The broadcasts were real**: `dead-window-logcat.txt` shows 13
  `ACTION_POWER_CONNECTED` and 26 `ACTION_POWER_DISCONNECTED` lines in
  that same window (multiple system receivers logging each toggle,
  same multi-line-per-broadcast pattern seen elsewhere in this run) —
  the simulated plugs fired at the system level throughout the dead
  window; the app just wasn't alive to see them.
- **The recovery mechanism is visible directly in logcat**: `Start proc
  ...chargelog.debug/uXXXX for broadcast {...}` at t=32321.656 —
  0.88 s before `service_start` (32322.535) — i.e. `BOOT_COMPLETED`
  spun up the process, which then started `RecordingService`. No process
  activity for the app appears anywhere earlier in the dead window.

**3/3 lost during the dead window is the expected, force-stop-analogous
result** — there is no bridge for the boot-to-first-unlock-to-first-
BOOT_COMPLETED gap any more than there is for force-stop. The window here
happened to be short enough (30 s) that only 3 cycles fit; a longer dead
window (as in the first pass, below) would show more losses at the same
0% capture rate, not a different rate.

### Recovery latency (n=2 reboots, traceable)

Device-side `boot`/`service_start` timestamps come from `events.ndjson`
and are always traceable. Host-side stage timestamps (reboot issued,
`sys.boot_completed`, CE-unlock proxy) are traceable **only for the
second pass**, via the committed `recovery-timeline.txt`; the first
pass's equivalent host timestamps were never committed and should not be
relied on for anything beyond the boot→service_start row, which was
independently backed by `events.ndjson` both times.

| From | Pass 1 (uncommitted host timestamps — informational only) | Pass 2 (`recovery-timeline.txt`, committed) |
|---|---|---|
| `adb reboot` issued → `service_start` | 77.075 s | 82.030 s |
| `sys.boot_completed`=1 → `service_start` | 51.425 s | 54.401 s |
| CE-unlock proxy (`run-as` first succeeds) → `service_start` | 40.735 s | 21.368 s |
| `boot` event (device-side) → `service_start` (device-side) | 0.215 s | 0.302 s |

Both passes' `boot`→`service_start` gap is small and consistent
(BootReceiver → RecordingService.onCreate() is effectively instant once
triggered), and both passes independently show a substantial
(tens-of-seconds) delay between the user unlocking (or `sys.boot_completed`
turning up) and the app actually recovering — 21 s and 41 s from the
CE-unlock proxy across the two passes, a real but variable gap, not a
fixed constant. **This means the always-on service is not available for
a variable window (tens of seconds, at least 21 s and up to 41 s observed
across two reboots) after a user unlocks post-reboot; any charging session
starting in that window is missed** — corroborated directly by the
dead-window toggle-loss result above, not just inferred from a timing gap.
n=2 reboots; a tighter distribution would need more repeats, which remains
out of scope per the operationalization note above.

### Post-recovery volume trials (n=10, post-recovery validation only — not dead-window evidence)

Same simulated-toggle method as the alive stratum, run immediately after
the reboot (pass 1) recovered. This section validates that capture
behaves normally once the service is back — it says nothing about event
loss during the dead window, which is covered separately above.

**Methodology self-review finding (host/device clock skew):** the
host-clock join for these 10 trials produced *negative* latencies
(median ≈ −0.81 s — device event apparently before the host command,
which is not physically possible as a causal latency). Cross-checked
directly: `adb shell date +%s.%N` vs host `date +%s.%N` taken ~4 minutes
after the reboot showed the device clock running **1.31 s behind** the
host clock; the reboot-stratum trials (taken ~30–90 s after the same
reboot) show a smaller ~0.80–0.83 s skew, consistent with the offset
still growing during that window (imperfect NTP resync shortly after
boot, still drifting). **Host-clock latency numbers from any measurement
taken in the first few minutes after a reboot should be treated as
unreliable** — they conflate real network/adb/propagation latency with
an unknown, time-varying device/host clock offset. This wasn't controlled
for in the original protocol doc and is worth adding as a caveat there.

The **device-internal** (logcat-clock) comparison is immune to this, since
both `logcat.txt` and `events.ndjson` use the device's own clock. Only 2 of
10 trials' `ACTION_POWER_CONNECTED` dispatch lines survived to capture time —
self-review finding: this stratum's `logcat.txt` was captured with `adb
logcat -d` (dump-the-ring-buffer *after* the 10 trials) rather than the
alive-stratum's continuous `adb logcat -v epoch > file &` capture, and the
buffer rolled most of the early trials' lines out before the dump. The two
that survived (trials 9 and 10) show logcat-clock latency of **0.502 s and
0.499 s** — indistinguishable from the alive-stratum's 0.499 s median.
**No evidence of degraded post-recovery latency**, but this is a thin (n=2)
sample; treat as corroborating, not conclusive.

Raw host-clock deltas (n=10, informational only given the skew above):
median −0.81 s, range −0.78 s to −0.83 s — the tight spread itself (∼50 ms)
indicates the underlying real latency is consistent trial-to-trial, just
riding on top of the ~0.8 s clock offset.

**Pass 2's post-recovery toggles (n=12, device-side counts only).** Pass 2
kept toggling at the same cadence after recovery, so attempt 4 — the one
reclassified out of the dead-window set above — and every attempt after it
are post-recovery trials. `reboot-stratum/device-logs/events.ndjson`
records 13 `power_connected` events at or after that pass's `service_start`
(t=32322535), spanning t=32326452 to t=32450372: twelve pair
one-to-one with `recovery-timeline.txt`'s `toggle-plug 4` … `toggle-plug
15`, and the thirteenth (t=32450372) trails `battery-reset-applied`
(t=32449.856) by ~0.5 s — the same harness-teardown reconnect §1
documents, not a trial. So **12/12 post-recovery attempts captured**,
matching pass 1's post-recovery result; no latency figure is quoted for
them because the post-reboot clock-skew caveat above applies equally.

**Characterization, not pass/fail** (per the protocol doc): `BootReceiver`
recovers on its own after reboot, but with a substantial (21–41 s across
two reboots) delay past user-unlock before the service is live — during
which dead-window toggles are lost (3/3, above) exactly like the
force-stop stratum — and once live, capture behavior (device-internal
latency) is indistinguishable from the steady-state alive stratum.

## 3. Force-stop stratum

10 simulated plug/unplug trials while the app was force-stopped
(`adb shell am force-stop io.github.allenbw.chargelog.debug`), continuous
logcat capture this time (`force-stop-stratum/logcat.txt`, `recovery-*`
for the post-relaunch confirmation — logcat not published, per the
curation note above).

- **0/10 events captured during the dead window** — `events.ndjson`'s
  `power_connected` count was unchanged (63 before and after the 10-trial
  dead-window run).
- **The system-level broadcasts were real**: `logcat.txt` shows 41
  `ACTION_POWER_CONNECTED` lines during the same window (multiple system
  receivers logging each of the simulated toggles), confirming the
  simulated plugs genuinely fired — the app simply had no receiver alive
  to catch them.
- **No process restart during the dead window**: zero `Start proc
  ...chargelog` lines in `logcat.txt` for the whole 10-trial run.
- **Recovery confirmed** after `adb shell am start -n
  io.github.allenbw.chargelog.debug/io.github.allenbw.chargelog.ui.MainActivity`:
  `service_start` appeared at t=31284927, essentially simultaneous
  with the `am start` command (raw delta is small and negative, consistent
  with the clock-skew finding above — recovery reads as near-instant, well
  under 1 s, not the tens-of-seconds boot-broadcast delay seen in the
  reboot stratum). The mechanism was in fact `BOOT_COMPLETED`, delivered
  immediately rather than staged behind a device boot: Android 15+
  re-delivers `ACTION_BOOT_COMPLETED` to an app when it leaves the
  force-stopped state, and `force-stop-stratum/device-logs/events.ndjson`
  carries `{"kind":"boot","t":31284863,"e":464175}` 64 ms ahead of
  that `service_start`. The broadcast was real — `BootReceiver` returns
  early on any other action — and it was *not* a reboot: the elapsed clock
  runs straight through it. The preceding `boot` (t=30885486,
  e=64798) sits exactly 399 377 ms earlier on *both* clocks, so the device
  never restarted; a real reboot instead resets `e` to near zero, as the
  pass-2 reboot's `boot` at t=32322233 / e=70461 does.
- **3/3 confirmation trials captured** after relaunch (not counted toward
  the mandated "10" — these are a recovery sanity check per the protocol
  doc's "re-run trials to confirm recovery"). Two of three show
  logcat-clock latency matching the established ~0.5 s pattern (0.493 s,
  0.499 s); the third reads as 0.001 s, an outlier that looks like a
  logcat multi-line-burst timestamp artifact rather than a real capture —
  not investigated further given n=3 and that it's outside the formal
  trial count.

**Pass, exactly as the protocol doc predicts**: zero captured events until
manual relaunch is the correct, expected platform behavior for a
force-stopped app (Android intentionally does not resurrect a
force-stopped app's context-registered receivers or FGS), and full
recovery after `am start` confirms there's no lingering damage from the
force-stop. This is a design-relevant finding already anticipated
in the S1 protocol doc: there is no bridge for this state short of a
manual launch.

## 4. Summary against pass/fail criteria

| Stratum | Criterion | Result |
|---|---|---|
| Alive-service | p90 < 3 s (logcat clock) | **Pass** — 0.503 s |
| Reboot | Characterize recovery | Recovers 21–41 s after unlock (n=2, traceable); 3/3 dead-window toggles lost (matches force-stop's 0% pattern); post-recovery capture behavior normal |
| Force-stop | 0 events until manual relaunch, then full recovery | **Pass** — 0/10, then 3/3 after `am start` |

Device left in clean state: `cmd battery reset` applied after every run,
no override banner in `dumpsys battery`, no stray `adb logcat` process,
`RecordingService` running (relaunched after the force-stop stratum).

## 5. Directory map

```
s1-detection-latency/
├── host-events.txt, logcat.txt*, device-logs/    — alive stratum (n=50), Step 4
├── reboot-stratum/
│   ├── host-events.txt                           — 10 post-recovery volume trials (pass 1)
│   ├── logcat.txt*                               — pass 1, dumped post-hoc (§2 self-review caveat)
│   ├── recovery-timeline.txt                     — pass 2, committed host timeline: reboot-issued,
│   │                                                boot-completed, ce-unlock-proxy, every toggle
│   │                                                attempt, recovery detection — traceable evidence
│   │                                                for §2's dead-window and recovery-latency claims
│   ├── dead-window-logcat.txt*                   — pass 2, continuous capture from adb-reconnect
│   │                                                through recovery (not dumped/rolled)
│   └── device-logs/                              — final pull (after both passes), cumulative
│       └── events.ndjson                            boot/service_start for both reboots verifiable
│                                                    directly from this file
├── force-stop-stratum/
│   ├── host-events.txt, logcat.txt*               — 10 dead-window trials, continuous capture
│   ├── recovery-host-events.txt, recovery-logcat.txt* — 3 post-relaunch confirmation trials
│   └── device-logs/                              — final pull (post-relaunch + confirm trials)
│       └── events.ndjson                            shows the zero-event gap during the dead
│                                                    window directly (§3)
└── ANALYSIS.md                                    — this file
```

`*` — logcat captures are referenced above as the source this analysis drew
on, but are not part of the published evidence (see the curation note at
the top of this file). The `session-*.ndjson` files each `device-logs/` pull
also contained are not published either, for the different reason given in
the curation note: nothing cites them.

Note: each stratum's `device-logs/` is kept as a single final pull rather
than one snapshot per intermediate step — `events.ndjson` is
cumulative/append-only, so the final pull is a superset of every earlier one
and independently verifies every claim above via event timestamps
(intermediate pulls taken during analysis were redundant and removed before
commit). That nesting holds *across* strata too, and can be checked directly:
the base `device-logs/events.ndjson` (63 lines) is a byte-exact prefix of
`force-stop-stratum/`'s (82 lines), which is a byte-exact prefix of
`reboot-stratum/`'s (100 lines). All three are kept rather than just the
longest, because §1 and §3 each cite their own stratum's pull as the state of
the log *at that point in the run*, which the later supersets no longer show
on their own.

## 6. Analysis method note

The join (host-events × events.ndjson × logcat.txt) was done with a
one-off Python script, not committed to the repo (it's disposable
analysis glue, not a reusable harness component like
`scripts/s1-sim-plug.sh`/`scripts/pull-logs.sh`). Method: for each trial,
match the nearest `power_connected` event within an 8 s window of the host
plug command; match the nearest `ACTION_POWER_CONNECTED` logcat dispatch
cluster (lines within 0.5 s of each other collapsed to their earliest
timestamp) the same way. Median/p90/max computed on the resulting
per-trial latency lists.
