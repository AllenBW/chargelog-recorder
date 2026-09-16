// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NotificationIdsTest {

    @Test fun `a channel change moves to the other id, never the same one`() {
        val a = NotificationIds.next(current = NotificationIds.PRIMARY, channelChanged = true)
        assertNotEquals(NotificationIds.PRIMARY, a)
        val b = NotificationIds.next(current = a, channelChanged = true)
        assertEquals(NotificationIds.PRIMARY, b)
    }

    @Test fun `no channel change keeps the current id`() {
        assertEquals(NotificationIds.PRIMARY, NotificationIds.next(NotificationIds.PRIMARY, channelChanged = false))
        assertEquals(NotificationIds.ALTERNATE, NotificationIds.next(NotificationIds.ALTERNATE, channelChanged = false))
    }

    @Test fun `only the two ids are ever produced`() {
        var id = NotificationIds.PRIMARY
        repeat(10) {
            id = NotificationIds.next(id, channelChanged = true)
            assert(id == NotificationIds.PRIMARY || id == NotificationIds.ALTERNATE) { "unexpected id $id" }
        }
    }
}
