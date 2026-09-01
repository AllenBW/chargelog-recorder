// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

/** `android.os.BatteryManager.BATTERY_STATUS_*` values, restated so this module's pure code needs no android import. */
object BatteryStatus {
    const val CHARGING = 2
    const val DISCHARGING = 3
    const val NOT_CHARGING = 4
    const val FULL = 5
}
