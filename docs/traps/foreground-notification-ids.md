<!--
SPDX-FileCopyrightText: 2026 BluffWorks LLC
SPDX-License-Identifier: GPL-3.0-only
-->

# Two notification ids, and never leaving the foreground

`RecordingService` posts its foreground notification under one of **two** ids
(`NotificationIds.PRIMARY` / `ALTERNATE`) and swaps between them on every channel transition. It
never calls `stopForeground`. Both halves are load-bearing and each was learned on hardware.

## Why a channel transition must REPLACE the notification

The recording notification carries an ongoing-activity chip on a Wear face. Updating in place
across a recording → idle transition drops the ongoing state but leaves the chip rendering the
dead session's last reading — `100% · 0.8 W` still on the watch face hours after the unplug.

Seen on real hardware 2026-09-01.

## Why the replacement must not leave the foreground

The obvious fix — `stopForeground()` then `startForeground()` with the new notification — re-runs
Android 12+'s background-start check. A backgrounded watch cannot pass it, and the service died
one sample into every charge.

Seen on real hardware 2026-09-02, and reproduced on the API-36 emulator.

## What the code does instead

A second `startForeground` with a **new id** while still foreground is allowed, and it swaps which
notification is the foreground one. So:

1. post the new notification under the *other* id, still foreground;
2. cancel the id being left — that is what tears the chip down.

On a phone this costs one silent shade re-post per plug and unplug. That is the whole price.

## What this constrains

- Never `stopForeground` in this service.
- `NOTIFICATION_ID` stays a public name for hosts that already reference it, but the live id is
  whichever of the two the swap last used — a host attaching an ongoing activity reads the id off
  the content it is handed, not this constant.
- A host that posts its own notification under one of these two ids will have it cancelled.

Fixed in PR #106; the id pair and the no-`stopForeground` rule are the fix.
