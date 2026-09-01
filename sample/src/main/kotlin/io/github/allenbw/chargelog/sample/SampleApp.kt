// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.sample

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.allenbw.chargelog.capture.ChannelLabels
import io.github.allenbw.chargelog.capture.HostContent
import io.github.allenbw.chargelog.capture.NotificationChannels
import io.github.allenbw.chargelog.capture.RecorderHost
import io.github.allenbw.chargelog.capture.RecorderState
import io.github.allenbw.chargelog.data.ChargeLogDb
import io.github.allenbw.chargelog.data.LogDirs
import io.github.allenbw.chargelog.measure.Units
import java.util.Locale

/**
 * The whole host side of the recorder, in one class. [RecorderHost] is the seam: the recorder
 * owns recording state, and everything a user sees — channel names, notification text, the tap
 * target, the charge target — is decided here. Nothing in this file knows anything about
 * charging beyond the watts it prints.
 */
class SampleApp : Application(), RecorderHost {

    /** The Room projection that [io.github.allenbw.chargelog.data.Replay] rebuilds from the raw
     *  logs. The recorder declares the schema and its migration policy; a host only opens it, and
     *  then holds it for as long as the process lives. */
    val db: ChargeLogDb by lazy { ChargeLogDb.open(this) }

    /** Where the raw session logs live, for the NDJSON share. */
    val logDirs: LogDirs by lazy { LogDirs.of(this) }

    override val appVersion: String get() = BuildConfig.VERSION_NAME

    override fun channelLabels() = ChannelLabels(
        recording = getString(R.string.channel_recording),
        idle = getString(R.string.channel_idle),
        idleDescription = getString(R.string.channel_idle_desc),
    )

    /** The settle rule's target. A real host reads a user preference; the sample hardcodes it. */
    override fun chargeTargetLevel(): Int = 80

    /**
     * Phase 1, called at 1 Hz from three dispatchers: resource lookups and a format, no I/O.
     * The text doubles as [HostContent.dedupeKey], so the recorder's 5 s gate collapses
     * consecutive ticks that would render the same line.
     */
    override fun content(state: RecorderState): HostContent {
        val text = when (state) {
            is RecorderState.Idle -> getString(R.string.notif_idle)
            is RecorderState.Recording -> {
                val w = Units.watts(state.sample?.currentRaw, state.sample?.voltageRaw)
                if (w == null) getString(R.string.notif_recording_unknown)
                else getString(R.string.notif_recording, format1(w))
            }
        }
        val channel = when (state) {
            is RecorderState.Recording -> NotificationChannels.RECORDING
            is RecorderState.Idle -> NotificationChannels.IDLE
        }
        return HostContent(dedupeKey = text, channelId = channel, payload = text)
    }

    /** Phase 2, at most every 5 s: the PendingIntent and the builder live here, off the tick. */
    override fun build(context: Context, content: HostContent): Notification =
        Notification.Builder(context, content.channelId)
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(content.payload as String)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, SampleActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setOngoing(true)
            .build()

    /** The recorder reports every battery broadcast; a product would fire its own alerts from
     *  these. The sample wants none. */
    override fun onBatteryState(level: Int?, plugged: Boolean, screenOn: Boolean?) = Unit

    override fun onCreate() {
        super.onCreate()
        // Here rather than in the service: the channels — and any Settings deep link to them —
        // must exist from the first launch, before the recorder has ever run.
        NotificationChannels.ensure(this)
    }
}

/** One decimal, locale-independent — these are numbers to compare, not to read aloud. */
internal fun format1(v: Double): String = String.format(Locale.US, "%.1f", v)
