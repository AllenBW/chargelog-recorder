// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import io.github.allenbw.chargelog.capture.log.EventKinds
import io.github.allenbw.chargelog.capture.log.EventLog
import io.github.allenbw.chargelog.capture.log.RawLine

/**
 * BOOT_COMPLETED is both manifest-deliverable and an FGS background-start
 * exemption — the ONLY legal always-on start path.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        EventLog(RecordingService.logDir(context)).append(
            RawLine.Event(System.currentTimeMillis(), SystemClock.elapsedRealtime(), EventKinds.BOOT))
        RecordingService.start(context)
    }
}
