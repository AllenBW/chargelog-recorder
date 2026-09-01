#!/bin/bash
# SPDX-FileCopyrightText: 2026 BluffWorks LLC
# SPDX-License-Identifier: GPL-3.0-only
#
# S1 volume trials: simulated plug/unplug cycles with host-side timestamps
# and epoch logcat as ground truth. Design doc §3/S1.
#
# DEVIATION FROM THE ORIGINAL BRIEF (S0 recon finding, docs/spikes/S0-recon.md
# §d): on a device that is genuinely USB-plugged the whole time (as this rig
# is), `cmd battery set ac 1` alone is a no-op — the framework already
# believes it's on AC, so forcing it to "AC" again produces no transition and
# no POWER_CONNECTED broadcast. The working simulated toggle pairs the two
# override calls: `cmd battery unplug` (forces the perceived state away from
# "plugged", firing POWER_DISCONNECTED) followed by `cmd battery set ac 1`
# (forces it back to "plugged", firing POWER_CONNECTED). So this script:
#   1. Opens with one `unplug` + settle sleep, to leave the simulated state
#      in "unplugged" before the trial loop starts.
#   2. Each trial then does `set ac 1` (the plug event under test) then,
#      after a dwell, `unplug` (the unplug event, and setup for the next
#      trial's plug).
#   3. Ends with `cmd battery reset`, which drops the override and lets the
#      framework re-read the real (still-plugged) hardware state.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# Resolve the repo root from git rather than a fixed depth: in the private monorepo that is
# the monorepo root (where docs/spikes/results/ lives today); in a public clone of the
# mirrored recorder it is the clone root, whose own docs/spikes/results/ holds the curated
# captures and is the natural place for fresh ones. A fixed "../.." was correct only for
# the first case and walked out of the second.
cd "$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
TRIALS="${1:-50}"
TS=$(date +%Y%m%d-%H%M%S)
OUT="docs/spikes/results/s1-$TS"
mkdir -p "$OUT"

adb logcat -c
adb logcat -v epoch > "$OUT/logcat.txt" &
LOGCAT_PID=$!
# Cleanup must also drop the battery override: an abnormal exit mid-loop
# would otherwise leave the device in the simulated "unplugged" state, i.e.
# silently not charging until someone notices. `reset` is idempotent, so the
# normal path's own reset below is unaffected.
trap 'kill $LOGCAT_PID 2>/dev/null || true; adb shell cmd battery reset >/dev/null 2>&1 || true' EXIT
sleep 1

# Prime the simulated state to "unplugged" so trial 1's `set ac 1` is a real
# transition rather than a no-op against an already-plugged device.
echo "$(date +%s.%N) prime-unplug" >> "$OUT/host-events.txt"
adb shell cmd battery unplug
sleep 2

for i in $(seq 1 "$TRIALS"); do
  echo "$(date +%s.%N) plug $i" >> "$OUT/host-events.txt"
  adb shell cmd battery set ac 1
  sleep 6
  echo "$(date +%s.%N) unplug $i" >> "$OUT/host-events.txt"
  adb shell cmd battery unplug
  sleep 4
done
adb shell cmd battery reset

"$SCRIPT_DIR/pull-logs.sh" "$OUT/device-logs"
echo "S1 run complete: $TRIALS trials in $OUT"
