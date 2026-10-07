// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

internal class ForegroundPoster<N>(
    private val build: (HostContent) -> N,
    private val notify: (Int, N) -> Unit,
    private val promote: (Int, N) -> Unit,
    private val cancel: (Int) -> Unit,
    private val clock: () -> Long,
) {
    companion object {
        const val GATE_MS = 5_000L
    }

    private var lastKey: String? = null
    private var lastAtE: Long = 0

    private var lastChannelId: String? = null
    private var currentId: Int = NotificationIds.PRIMARY

    @Synchronized
    fun start(content: HostContent) {
        lastChannelId = content.channelId
        currentId = NotificationIds.PRIMARY
        promote(currentId, build(content.copy(notificationId = currentId)))
    }

    @Synchronized
    fun post(content: HostContent, force: Boolean = false) {
        val key = content.dedupeKey
        if (!force && (key == lastKey || clock() - lastAtE < GATE_MS)) return
        lastKey = key
        lastAtE = clock()
        val channelChanged = lastChannelId != null && lastChannelId != content.channelId
        lastChannelId = content.channelId
        val previousId = currentId
        val id = NotificationIds.next(previousId, channelChanged)
        currentId = id
        val built = build(content.copy(notificationId = id))
        // TRAP docs/traps/foreground-notification-ids.md — swap ids, never stopForeground
        if (channelChanged) {
            promote(id, built)
            cancel(previousId)
        } else {
            notify(id, built)
        }
    }
}
