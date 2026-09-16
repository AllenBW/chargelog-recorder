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
 * An app update kills the process, and nothing else brings the recorder back: `START_STICKY`
 * only covers a system-initiated kill, not the process death that comes with a package replace.
 * On a device nobody opens by hand — a watch, say — the recorder would otherwise stay dead from
 * the moment the store installs an update until the next reboot. This is the update-time twin of
 * `BootReceiver`.
 *
 * MY_PACKAGE_REPLACED is both manifest-deliverable and an FGS background-start exemption, which
 * is why it's legal to call `startForegroundService` from here.
 *
 * Like its twin, it starts nothing until the host says the user has consented
 * ([RecorderHost.captureConsented]) — an update must not be the moment recording begins for
 * someone who backed out of the first-run flow. The decision is [BackgroundStart.decide].
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val consented = { (context.applicationContext as? RecorderHost)?.captureConsented() ?: true }
        when (BackgroundStart.decide(intent.action, Intent.ACTION_MY_PACKAGE_REPLACED, consented)) {
            BackgroundStart.Decision.IGNORE -> return
            BackgroundStart.Decision.NOT_CONSENTED -> {
                Log.w(BackgroundStart.TAG, "MY_PACKAGE_REPLACED: recorder not started, the host reports no consent yet")
                return
            }
            BackgroundStart.Decision.START -> Unit
        }
        // TRAP docs/traps/event-log-writes-are-best-effort.md — a throw here kills the service start
        try {
            EventLog(RecordingService.logDir(context)).append(
                RawLine.Event(System.currentTimeMillis(), SystemClock.elapsedRealtime(), EventKinds.PACKAGE_REPLACED))
        } catch (_: IOException) { }
        RecordingService.start(context)
    }
}
