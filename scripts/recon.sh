#!/bin/bash
# SPDX-FileCopyrightText: 2026 BluffWorks LLC
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# Resolve the repo root from git rather than a fixed depth: in the private monorepo that is
# the monorepo root (where docs/spikes/results/ lives today); in a public clone of the
# mirrored recorder it is the clone root, whose own docs/spikes/results/ holds the curated
# captures and is the natural place for fresh ones. A fixed "../.." was correct only for
# the first case and walked out of the second.
cd "$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
TS=$(date +%Y%m%d-%H%M%S)
OUT="docs/spikes/results/s0-$TS"
mkdir -p "$OUT"

adb devices | tee "$OUT/adb-devices.txt"

echo "== dumpsys battery =="
adb shell dumpsys battery > "$OUT/dumpsys-battery.txt"

echo "== power_supply nodes =="
adb shell 'ls -1 /sys/class/power_supply/' > "$OUT/power-supply-nodes.txt"
for node in $(cat "$OUT/power-supply-nodes.txt"); do
  adb shell "for f in /sys/class/power_supply/$node/*; do echo \"\$f=\$(cat \$f 2>/dev/null | head -c 200)\"; done" \
    > "$OUT/node-$node.txt" 2>/dev/null || true
done

echo "== sysfs current_now poll (60 s @ 2 Hz) — determines gauge cadence =="
adb shell 'i=0; while [ $i -lt 120 ]; do
  up=$(cut -d" " -f1 /proc/uptime)
  cur=$(cat /sys/class/power_supply/battery/current_now 2>/dev/null || echo NA)
  volt=$(cat /sys/class/power_supply/battery/voltage_now 2>/dev/null || echo NA)
  cc=$(cat /sys/class/power_supply/battery/charge_counter 2>/dev/null || echo NA)
  echo "$up $cur $volt $cc"
  i=$((i+1)); sleep 0.5
done' > "$OUT/sysfs-poll.txt"

echo "== sensorservice (hinge variants, FIFO, wake-up flag) =="
adb shell dumpsys sensorservice > "$OUT/sensorservice.txt"

echo "== simulated plug probe =="
adb logcat -c
adb logcat -v epoch > "$OUT/logcat-simplug.txt" &
LOGCAT_PID=$!
sleep 1
date +%s.%N > "$OUT/host-ts-set-ac.txt"
adb shell cmd battery set ac 1
sleep 3
date +%s.%N > "$OUT/host-ts-unplug.txt"
adb shell cmd battery unplug
sleep 2
adb shell cmd battery reset
sleep 1
kill $LOGCAT_PID || true

echo "Recon archived to $OUT"
