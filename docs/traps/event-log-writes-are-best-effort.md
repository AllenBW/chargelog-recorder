<!--
SPDX-FileCopyrightText: 2026 BluffWorks LLC
SPDX-License-Identifier: GPL-3.0-only
-->

# A diagnostic write must never take the process down

Every `eventLog.append(...)` on a broadcast-receiver path is wrapped and swallowed to a `Log.w`.
That is deliberate, and it is the rule the receivers exist to protect.

## The rule

An event line is **diagnostics**. The service start beside it is **the point**.

A `BroadcastReceiver.onReceive` that throws crashes the process. On a boot or
`MY_PACKAGE_REPLACED` broadcast, the throw would take down the very service start the receiver was
delivered to perform — turning a full disk, or storage not yet mounted at boot, into "the recorder
never came back after an update".

Storage genuinely is not always writable at these moments: boot completes before the user unlocks
a device with FBE, and the log directory can be unreadable on the first broadcast.

## Where it applies

`BootReceiver`, `PackageReplacedReceiver`, and the `ACTION_POWER_CONNECTED` branch of
`RecordingService`'s power receiver. That last one was the place in the module that *stated* the
rule and then broke it — its plug marker was a direct unwrapped write until PR #107.

The plug marker is the rolling log's ground-truth plug edge and is worth keeping, which is exactly
why it is tempting to let it throw. It still must not.

## What this does not cover

Session data written by the input pump is not best-effort. A failed sample write is a real failure
and is allowed to surface: replay marks a session `TRUNCATED` rather than pretending it ended.
