// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

/**
 * The two notification ids the recorder's foreground notification alternates between.
 *
 * A channel change (recording <-> idle) has to REPLACE the notification rather than update it in
 * place — on a watch, an in-place update that drops the ongoing state leaves the face chip
 * rendering the dead session's last reading. The obvious replacement, stop-foreground then
 * start-foreground again, is fatal on Android 12+ when the app is in the background: the second
 * start re-runs the background-start check, and a watch has no exemption to pass it, so the
 * service died one sample into every charge. Posting the new notification under the OTHER id
 * while still foreground gives the same visible replacement without ever leaving the foreground;
 * the old id is then cancelled. Pure, so the alternation is pinned by a test.
 */
object NotificationIds {
    const val PRIMARY = 1

    /**
     * 8, not the 2 that "the other one" suggests. A host's own notifications share this id
     * namespace, and the recorder is foreground on one of these two at every moment of the
     * service's life — so an id a host also uses is not a rare clash, it is a permanent one.
     * ChargeLog's low-battery reminder owns 2: with `ALTERNATE = 2` the reminder's own
     * "is it already posted" read answered yes forever and it never fired again, and every
     * other transition put the RECORDING notification on top of it. Ids 2-4 are that app's
     * nudges, 5 is reserved for its charger check, 6/7/106 are its watch sync; 8 is clear of
     * all of them. A host embedding this library must keep both ids to itself.
     */
    const val ALTERNATE = 8

    /** The id to post under next: the other one on a channel change, otherwise [current]. */
    fun next(current: Int, channelChanged: Boolean): Int =
        if (!channelChanged) current else if (current == PRIMARY) ALTERNATE else PRIMARY
}
