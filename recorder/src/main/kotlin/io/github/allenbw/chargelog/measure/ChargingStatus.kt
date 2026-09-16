// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.measure

/**
 * The platform's own attribution of how a charge is going — `BatteryManager.EXTRA_CHARGING_STATUS`
 * (`android.os.extra.CHARGING_STATUS`, public from API 36; the key itself is stable on 31+), the
 * HAL's `BatteryChargingState`, recorded raw in `RawLine.Sample.chargingStatus`.
 *
 * Constants rather than an enum, deliberately: a value the platform adds later must still decode
 * and be showable as its number. The numbering was verified on a Pixel 11 Pro Fold's sticky intent
 * and is pinned by `ChargingStatusTest`.
 *
 * This is the field that lets a reader tell *the charger is slow* from *the phone chose to charge
 * slowly*: a TOO_HOT or ADAPTIVE span is the phone's own doing and must never be
 * blamed on the charger.
 */
object ChargingStatus {
    /** The HAL's own "unset": present in the extra but never assigned by this device's health
     *  HAL. A reader treats it as "no attribution", never as a sixth kind of charging. */
    const val INVALID = 0
    const val NORMAL = 1
    const val TOO_COLD = 2
    const val TOO_HOT = 3
    const val LONG_LIFE = 4
    const val ADAPTIVE = 5
}
