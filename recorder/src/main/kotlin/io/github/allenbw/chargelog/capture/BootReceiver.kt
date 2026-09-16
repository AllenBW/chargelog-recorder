// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import io.github.allenbw.chargelog.capture.log.EventKinds
import io.github.allenbw.chargelog.capture.log.EventLog
import io.github.allenbw.chargelog.capture.log.RawLine

/**
 * BOOT_COMPLETED is both manifest-deliverable and an FGS background-start
 * exemption — the ONLY legal always-on start path.
 *
 * It starts nothing until the host says the user has consented ([RecorderHost.captureConsented]):
 * the decision is [BackgroundStart.decide], and the refusal comes before the event line as well
 * as before the start, so a device whose owner has not agreed to recording gets no log directory
 * written on their behalf either.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val consented = { (context.applicationContext as? RecorderHost)?.captureConsented() ?: true }
        when (BackgroundStart.decide(intent.action, Intent.ACTION_BOOT_COMPLETED, consented)) {
            BackgroundStart.Decision.IGNORE -> return
            BackgroundStart.Decision.NOT_CONSENTED -> {
                Log.w(BackgroundStart.TAG, "BOOT_COMPLETED: recorder not started, the host reports no consent yet")
                return
            }
            BackgroundStart.Decision.START -> Unit
        }
        // TRAP docs/traps/event-log-writes-are-best-effort.md — a throw here kills the service start
        try {
            EventLog(RecordingService.logDir(context)).append(
                RawLine.Event(System.currentTimeMillis(), SystemClock.elapsedRealtime(), EventKinds.BOOT))
        } catch (_: IOException) { }
        RecordingService.start(context)
    }
}
