#!/bin/bash
# SPDX-FileCopyrightText: 2026 BluffWorks LLC
# SPDX-License-Identifier: GPL-3.0-only
#
# Pulls a debuggable build's raw session logs off a connected device.
# Usage: ./scripts/pull-logs.sh [dest-dir]        (default: ./pulled-logs)
#        PKG=com.example.myapp ./scripts/pull-logs.sh   for a host app other than :sample
set -euo pipefail
PKG="${PKG:-io.github.allenbw.chargelog.sample}"
DEST="${1:-pulled-logs}"
mkdir -p "$DEST"
for f in $(adb shell run-as "$PKG" ls no_backup/rawlog); do
  adb shell run-as "$PKG" cat "no_backup/rawlog/$f" > "$DEST/$f"
done
echo "Pulled $(ls "$DEST" | wc -l | tr -d ' ') files to $DEST"
