// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture.log

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `RawLine.Header.deviceKind` values. Absent (legacy files) means PHONE. */
object DeviceKinds {
    const val PHONE = "PHONE"
    const val WATCH = "WATCH"
}

/** `Capabilities.counterKind` values: a real coulomb counter vs capacity × SOC (Samsung watches). */
object CounterKinds {
    const val COULOMB = "COULOMB"
    const val SOC_DERIVED = "SOC_DERIVED"
}

/**
 * What this device's gauge provides, declared once per session so absence is disclosed at the
 * session level rather than only as per-sample nulls. Every field is nullable:
 * null means "not declared", never "false".
 */
@Serializable
data class Capabilities(
    val reportsCurrent: Boolean? = null,
    val reportsChargeCounter: Boolean? = null,
    val counterKind: String? = null,
    val hasHinge: Boolean? = null,
    val hasThermal: Boolean? = null,
    /** Whether the platform's sticky battery intent carried `android.os.extra.CHARGING_STATUS`
     *  at service start — so "this device never says" is distinguishable from "not this sample".
     *  Added 2026-09-08; nullable with a default, so the schema stays 2. */
    val reportsChargingStatus: Boolean? = null,
    /** The gauge's current-sign convention as the recorder's profile table knew it: `true` when a
     *  positive `currentRaw` means charge flowing INTO the battery, `false` when it means out,
     *  null when unmeasured. Declared per session for the same reason [counterKind] is: a reader
     *  must not have to guess a sign from a catalog it may not have. */
    val chargingPositive: Boolean? = null,
)

/**
 * One line of the append-only raw capture log. Values are RAW as reported by
 * the platform — no unit or sign conversion is EVER applied at write time;
 * quirk transforms happen at read time. `t` is wall-clock
 * ms (external correlation), `e` is elapsedRealtime ms (monotonic deltas).
 */
@Serializable
sealed interface RawLine {
    val t: Long
    val e: Long

    @Serializable
    @SerialName("h")
    data class Header(
        val schema: Int,
        val samplerProfileId: String,
        val deviceModel: String,
        val osRelease: String,
        val appVersion: String,
        val tickMs: Long,
        val sessionStartWallClockMs: Long,
        val deviceKind: String? = null,
        val deviceId: String? = null,
        val gaugeProfileId: String? = null,
        val capabilities: Capabilities? = null,
        val socModel: String? = null,
        val totalMemBytes: Long? = null,
        /** The cell's design capacity in mAh as the HOST knew it at capture time
         *  (`RecorderHost.designCapacityMah`), or absent when it did not. Additive and nullable,
         *  so the schema stays 2. */
        val designCapacityMah: Int? = null,
        /** `BatteryManager.EXTRA_CYCLE_COUNT` as the sticky battery intent carried it at plug-in,
         *  or absent when the platform did not supply the key. Additive and nullable, so the
         *  schema stays 2. */
        val cycleCount: Int? = null,
    ) : RawLine {
        override val t: Long get() = sessionStartWallClockMs
        override val e: Long get() = 0
    }

    @Serializable
    @SerialName("s")
    data class Sample(
        override val t: Long,
        override val e: Long,
        val currentRaw: Long? = null,
        val chargeCounterRaw: Long? = null,
        val voltageRaw: Int? = null,
        val voltageAgeMs: Long? = null,
        val tempDeciC: Int? = null,
        val level: Int? = null,
        val scale: Int? = null,
        val status: Int? = null,
        val plugged: Int? = null,
        val maxChargingCurrentRaw: Int? = null,
        val maxChargingVoltageRaw: Int? = null,
        val thermalStatus: Int? = null,
        val screenOn: Boolean? = null,
        val hingeDeg: Float? = null,
        /** `BatteryManager.EXTRA_CHARGING_STATUS` raw — the platform's own attribution of how the
         *  charge is going (`measure/ChargingStatus`: 1 normal · 2 too cold · 3 too hot · 4 long
         *  life · 5 adaptive). Null when the platform did not supply the key. Additive and
         *  nullable, so the schema stays 2. */
        val chargingStatus: Int? = null,
    ) : RawLine

    @Serializable
    @SerialName("e")
    data class Event(
        override val t: Long,
        override val e: Long,
        val kind: String,
        val detail: String? = null,
    ) : RawLine
}

object EventKinds {
    const val SESSION_START = "session_start"
    const val SESSION_END = "session_end"
    const val GAP = "gap"
    const val POWER_CONNECTED = "power_connected"
    const val POWER_DISCONNECTED = "power_disconnected"

    /**
     * BOOT_COMPLETED was received — which is NOT the same as "the device
     * rebooted". Android 15+ re-delivers ACTION_BOOT_COMPLETED when an app
     * leaves the force-stopped state, so a `boot` line can mean either.
     * The discriminator is the elapsed clock: `e` resets to near zero across
     * a real reboot and runs continuously across a force-stop-exit
     * re-delivery (observed in testing, in a force-stop-then-relaunch scenario).
     */
    const val BOOT = "boot"

    /** An `ACTION_MY_PACKAGE_REPLACED` broadcast was received — the app was just updated — and
     *  the recorder restarted itself from it. */
    const val PACKAGE_REPLACED = "package_replaced"

    const val SERVICE_START = "service_start"
    const val SERVICE_STOP = "service_stop"
    const val HINGE = "hinge"
    const val SCREEN_ON = "screen_on"
    const val SCREEN_OFF = "screen_off"
    const val THERMAL = "thermal"

    /** The platform's charging attribution changed (`Sample.chargingStatus`); `detail` is
     *  `"status=N"`. The `thermal` event's twin, so an Adaptive hold or a thermal pause is
     *  findable in `events.ndjson` without scanning samples. */
    const val CHARGING_STATUS = "charging_status"

    /** A session file arrived whose id (start ms) already belongs to a DIFFERENT device; it was
     *  skipped, never merged. detail: id=…,deviceId=…,existingDeviceId=… */
    const val INGEST_CONFLICT = "ingest_conflict"

    /**
     * The recorder resolved an UNKNOWN gauge's current SCALE from this session's own first
     * samples, and the header's `gaugeProfileId` is therefore provisional. detail:
     * `gaugeProfileId=gauge-unknown-ma`.
     *
     * A header is written at plug-in, before any sample exists, so a scale the samples reveal
     * cannot be in it. This line is how the session it was learned FROM still reads correctly —
     * `Replay` prefers it over the header — and every later session simply opens with the
     * refined profile. Additive: a log without one is a log whose header was right.
     */
    const val GAUGE_SCALE = "gauge_scale"

    /** The header key [GAUGE_SCALE]'s detail carries, and what [GAUGE_SCALE] readers split on. */
    const val GAUGE_SCALE_DETAIL_KEY = "gaugeProfileId="

    /** The recorder changed its capture policy without closing the session:
     *  detail "settled" (wake lock released, sampling becomes event-driven) or "resumed". */
    const val CAPTURE_POLICY = "capture_policy"

    /** The effective sample cadence from this line on: detail "tickMs=<n>,policy=tick|event". Lets
     *  a reader interpret a sparse tail honestly instead of as silence. */
    const val CADENCE = "cadence"
}
