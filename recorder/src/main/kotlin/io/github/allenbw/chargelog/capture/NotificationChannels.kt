// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * The recorder's two notification channels. Names come from the host
 * ([RecorderHost.channelLabels]); ids are fixed here so a host can deep-link to them.
 *
 * Android requires a foreground service to hold a notification, and [RecordingService] has to be
 * a foreground service the whole time to catch a plug-in as it happens — the alternative, a
 * service that stops when unplugged and is brought back by a charging-constrained job, was
 * measured at about six minutes late, which loses the negotiation ramp the recorder exists to
 * capture. So the notification cannot simply go away between charges. What CAN change is where
 * it lives:
 *
 * - [RECORDING] (`IMPORTANCE_LOW`): the live line while a session is being recorded. Visible.
 * - [IDLE] (`IMPORTANCE_MIN`): the between-charges recap. MIN means no status-bar icon and a
 *   collapsed entry at the bottom of the shade's silent section — and, decisively, a channel the
 *   user can switch OFF in system settings. A foreground service runs fine with its
 *   notification's channel blocked; the system just shows nothing. That is the "only present
 *   when charging" behaviour, one toggle away, without giving up the first minutes of
 *   every charge. Settings deep-links to that toggle.
 *
 * The product's own alerts — the low-battery reminder — are not the recorder's; a
 * host that wants one declares and owns its own channel for it.
 *
 * Idempotent: `createNotificationChannel` on an existing id is a no-op (it never RAISES an
 * importance the user lowered). Called from the Application so the channels exist — and the
 * Settings deep link resolves — before the service has ever run.
 */
object NotificationChannels {
    const val RECORDING = "recording"
    const val IDLE = "idle"

    fun ensure(context: Context) {
        val labels = (context.applicationContext as? RecorderHost
            ?: error("Application must implement RecorderHost")).channelLabels()
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(RECORDING, labels.recording, NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(IDLE, labels.idle, NotificationManager.IMPORTANCE_MIN).apply {
                description = labels.idleDescription
            },
        )
    }

    /** Whether the user has switched the [IDLE] channel off in system settings. False when the
     *  channel doesn't exist yet (nothing to be hidden). */
    fun idleHidden(context: Context): Boolean {
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(IDLE)
        return channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE
    }
}
