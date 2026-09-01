<!--
SPDX-FileCopyrightText: 2026 BluffWorks LLC
SPDX-License-Identifier: GPL-3.0-only
-->

# Spike S1: Plug-in Detection Latency

Plug-in detection on Android 16, Pixel 11 Fold: compare (a) context-registered
`ACTION_POWER_CONNECTED` receiver held by a persistent component vs (b)
WorkManager charging-constraint start. Measure capture latency — must catch
the negotiation ramp (< ~3 s from plug). This app ships only path (a) — an
always-on `RecordingService` with a context-registered `powerReceiver`
(`RecordingService.kt`) — so S1 measures that path's latency and, separately,
how long the *same* path takes to recover after the process is no longer
alive to hold the receiver.

## Objective

1. Distribution of plug→callback latency (host command to app-visible
   `power_connected` event) while the service is alive, across a volume of
   trials.
2. Recovery latency (or lack of it) from each "dead" stratum: does the
   always-on service come back on its own, and if so how fast; if not, what
   does bring it back.

## Prerequisites

- `foss` debug build installed (`io.github.allenbw.chargelog.debug`),
  `POST_NOTIFICATIONS` and battery-optimization exemption both granted
  (the app's own onboarding flow grants both before this spike runs).
- Device USB-attached to the host running `adb` (`adb devices` shows it as
  `device`, not `unauthorized`).
- Screen off (ambient/doze) for the alive-service stratum, matching the
  app's real usage pattern (nightstand charging).
- `chmod +x scripts/*.sh`.

## Simulated-toggle protocol (deviation from a naive reading of `cmd battery`)

S0 recon (`S0-recon.md` §d) found that `adb shell cmd battery set
ac 1` is a **no-op** when the device is genuinely USB-plugged the whole
time, which this rig always is — the framework already believes it's on AC,
so re-asserting AC produces no state transition and no
`ACTION_POWER_CONNECTED` broadcast. The toggle that reliably produces both
directions is:

1. `adb shell cmd battery unplug` — forces the perceived state away from
   "plugged"; fires `ACTION_POWER_DISCONNECTED`.
2. `adb shell cmd battery set ac 1` — forces the perceived state back to
   "plugged"; fires `ACTION_POWER_CONNECTED`.

`scripts/s1-sim-plug.sh` implements this: it primes the simulated state to
"unplugged" once at startup, then each trial does `set ac 1` (the plug event
under measurement) → dwell → `unplug` (the unplug event, doubling as setup
for the next trial's plug) → dwell. It ends with `cmd battery reset`, which
drops the override and lets the framework re-read the real (still-plugged)
hardware state.

S0 recon also found a consistent **~2 s framework propagation lag** between
the host `adb shell cmd battery …` command returning and the corresponding
broadcast appearing in `logcat`. Because of this, every latency number in
this spike is reported against **two** clocks, and the two are not
interchangeable:

- **Host timestamp** (`host-events.txt`, `date +%s.%N` at the moment the adb
  command is issued): includes adb round-trip + the ~2 s framework
  propagation lag. This is the number a *human* triggering a real plug would
  experience.
- **Logcat epoch timestamp** (`logcat.txt`, `adb logcat -v epoch`, the line
  where the system dispatches `ACTION_POWER_CONNECTED`/`DISCONNECTED`):
  device-side ground truth for when the broadcast actually left the
  framework. This isolates the app's own receiver-to-log latency from
  adb/propagation overhead.

`events.ndjson`'s `power_connected` line timestamp (`t`, wall-clock ms) is
compared against **both** clocks; report which comparison is which.

> **Curation note.** This document describes the protocol as you would run
> it. The evidence published from *our* run of it, in
> `results/s1-detection-latency/`, is a reduced copy — `ANALYSIS.md`'s own
> curation note is the authoritative account, and the short version is:
>
> - **`logcat.txt` captures are not published.** They are part of this
>   spike's method (device-side ground truth, per above), but they carry
>   other apps' data and personally identifying values. Anyone reproducing
>   this spike generates their own local `logcat.txt`; ours stays private, so
>   the logcat-clock figures in `ANALYSIS.md` are reported on our word rather
>   than re-derivable from what ships.
> - **The per-session `session-*.ndjson` logs are not published either** —
>   213 files of second-by-second samples from one person's phone, cited by
>   no claim in any of these documents. `events.ndjson` and the
>   host-timestamp files, which every published result actually joins, are
>   kept in full.
> - **The published timestamps are offsets, not epochs.** One constant was
>   subtracted from every wall-clock timestamp in `results/`, on both clocks,
>   so `t=0` is the earliest instant in the published evidence. Every
>   interval — including every latency in `ANALYSIS.md` — is preserved
>   exactly; the calendar date is not. The monotonic clocks were re-baselined
>   with it — `events.ndjson`'s `e` for the pre-reboot boot session, and
>   `s0-recon/sysfs-poll.txt`'s uptime column — because a monotonic value
>   beside a wall-clock one reconstructs the boot instant, and the boot
>   instant gives the constant back. `ANALYSIS.md`'s curation note item 3 and
>   `S0-recon.md`'s item 3 say exactly what was shifted. A fresh run of this
>   protocol produces ordinary wall-clock ms, as described above.

## Method

**Alive-service stratum (volume):**

```
./scripts/s1-sim-plug.sh 50
```

Runs 50 plug/unplug cycles (6 s plugged, 4 s unplugged) against the live
`RecordingService`, capturing `host-events.txt`, `logcat.txt` (epoch
broadcast-dispatch lines), and pulling `events.ndjson` via
`scripts/pull-logs.sh`.

**Dead strata (10 trials each, after re-establishing the alive baseline):**

| Stratum | How to kill/disable | Trigger command |
|---|---|---|
| Reboot | `adb reboot`, wait for boot, human unlocks the PIN keyguard (`BOOT_COMPLETED` — and therefore `BootReceiver` → service start — does not fire until first unlock) | `adb wait-for-device`, poll `adb shell getprop sys.boot_completed` until `1`, then run trials |
| Force-stop | `adb shell am force-stop io.github.allenbw.chargelog.debug` | Run trials immediately after; then `adb shell am start -n io.github.allenbw.chargelog.debug/io.github.allenbw.chargelog.ui.MainActivity` and re-run trials to confirm recovery |
| Before battery exemption *(documented, not executed this pass)* | Revoke `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (`adb shell dumpsys deviceidle whitelist -io.github.allenbw.chargelog.debug` or via Settings) before onboarding grants it | Not run in this execution: this device already has the exemption granted permanently from onboarding, and revoking it mid-fleet-test is a separate destructive step outside this pass's scope. Left as a documented gap for a future pass that starts from a fresh, non-exempted install. |

For the reboot and force-stop strata, use the same simulated-toggle pair
(`set ac 1` / `unplug`) as the alive stratum, run manually or via a trimmed
`s1-sim-plug.sh` invocation, and pull logs afterward with
`scripts/pull-logs.sh`.

## What to record

- `host-events.txt` and the pulled `events.ndjson` / `session-*.ndjson` per
  run, under `results/s1-<timestamp>/` (plus a local, unpublished
  `logcat.txt` per the curation note above). Our own published copy is
  `results/s1-detection-latency/` — named for the spike rather than the
  capture time, and holding only the subset the curation note describes.
- Per trial: `power_connected` event `t` (wall-clock ms) minus (a) the host
  plug-command timestamp and (b) the logcat epoch broadcast-dispatch
  timestamp for that trial.
- Count of `manifest_receiver` lines in the pulled `events.ndjson` — a
  manifest-registered broadcast receiver probe used only for this spike, not
  part of the shipping app — expected 0 on API 26+; a nonzero count is
  itself the finding (would mean an on-demand start architecture becomes
  viable).
- Per dead stratum: whether *any* `power_connected` events were captured at
  all during the dead window, and — for strata that do recover — the gap
  between the recovery trigger (unlock, or `am start`) and the first
  `service_start` / first captured `power_connected` after it.
- Median, p90, and max latency per stratum, per clock (host vs. logcat).

## Pass/fail and characterization criteria

- **Alive-service stratum:** capture latency < 3 s at p90, measured against
  the logcat ground-truth clock. Report the host-clock p90 too, since it is
  what a real user experiences end-to-end, but the ≤3 s bar is a claim about
  the app's own receiver latency, not about adb/framework propagation
  overhead the app has no control over.
- **Reboot stratum:** characterize, not pass/fail — record whether
  `BootReceiver` fires promptly after first unlock and how long
  `service_start` takes to appear relative to the unlock event.
- **Force-stop stratum:** the expected, correct-per-platform-contract result
  is **zero** captured events until the app is manually relaunched — Android
  intentionally does not resurrect a force-stopped app's context-registered
  receivers or FGS. Confirming zero events during the dead window and full
  recovery after `am start` is a **pass**, not a failure; document it as a
  finding relevant to the app's overall design (there is no bridge for this
  state short of a manual launch).
