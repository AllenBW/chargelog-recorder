// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import android.app.Notification
import android.content.Context
import io.github.allenbw.chargelog.capture.log.DeviceKinds
import io.github.allenbw.chargelog.capture.log.RawLine
import io.github.allenbw.chargelog.measure.GaugeProfile
import io.github.allenbw.chargelog.measure.GaugeProfiles

/**
 * What the recorder needs from the application that hosts it. The recorder owns recording
 * state; the host owns everything a user sees — channel names, notification text, icons,
 * intents. Implemented by the host's [android.app.Application]; [RecordingService] resolves it
 * once as `applicationContext as RecorderHost` and fails fast if it is missing.
 *
 * ## The stability rule
 *
 * **Every new member of this interface ships with a default implementation.** This seam is how
 * the recorder reaches consumers it cannot see — including proprietary ones, whose needs drive
 * most of the additions and whose source a contributor here will never read. A member with a
 * default is additive: every host that compiled yesterday compiles today, and picks up the new
 * behaviour only if it wants it. A member WITHOUT one breaks every existing implementation at
 * once, so it is a breaking change and needs a major version bump and a note in `CHANGELOG.md`.
 *
 * [deviceKind] and [gaugeProfile] are what the rule looks like in practice: both arrived to serve
 * a host that is not in this repository, both landed with a default naming the phone's own
 * behaviour, and neither cost any existing host a line. Prefer a default that describes what the
 * recorder did before the member existed.
 *
 * **The seam is this interface AND every type it names.** [ChannelLabels], [HostContent],
 * [RecorderState] and its subclasses, [SessionRecap], [GaugeProfile], [RawLine.Sample] — a host
 * constructs some of these, reads others, and `copy()`s them. Removing or renaming a property, or
 * adding one without a default, breaks a host exactly as surely as changing a method signature
 * here does, and the compiler in this repository will not tell you: the only consumer it can see
 * is `:sample`, which does not exercise every constructor.
 *
 * This is not hypothetical. The public `data class GaugeProfile` lost six constructor parameters
 * when the analyzer's calibration moved to the closed side. Nothing broke, because nothing outside
 * this repository existed yet — but a host that had written `profile.copy(noiseFloorW = …)`, or
 * constructed a profile for its own gauge, would have stopped compiling, and the rule as it was
 * written then said only "every new MEMBER of this interface", which did not reach the case. Treat
 * a change to a named type as a change to the seam: additive with a default, or a major version
 * bump and a `CHANGELOG.md` note.
 */
interface RecorderHost {
    /** Written into every NDJSON session header. */
    val appVersion: String

    /** Which kind of device this host is (`DeviceKinds`); written into every session header.
     *  Defaults to the phone — see the stability rule above. */
    val deviceKind: String get() = DeviceKinds.PHONE

    /** The gauge this device records with: its id goes into the header, its counter kind into the
     *  capabilities, and its current scale into the settle rule. A host that also analyzes
     *  sessions keys its own per-gauge calibration off [GaugeProfile.id]; the recorder holds no
     *  such table. Defaults to the phone's µA coulomb counter — see the stability rule above. */
    fun gaugeProfile(): GaugeProfile = GaugeProfiles.PHONE

    /** User-visible names for the two notification channels; read by [NotificationChannels.ensure]. */
    fun channelLabels(): ChannelLabels

    /**
     * Phase 1 — cheap. Called on every state transition and on every sampling tick while
     * recording (1 Hz by default), from the main thread, `Dispatchers.Default` and
     * `Dispatchers.IO`. Must be thread-safe and allocation-light. The recorder gates on
     * [HostContent.dedupeKey]: unless the key changed AND at least 5 s passed since the last
     * post (transitions bypass the gate), [build] is not called.
     *
     * "Thread-safe" here means: **compute from [state] and return; do not touch shared mutable
     * state.** Three threads call this, and the recorder makes no promise about which, in what
     * order, or how they interleave. The pattern to copy is `:sample`'s — pure computation from
     * the argument, no fields read or written. So:
     *
     * ```kotlin
     * // NO: two callers race on lastLevel, and the string it produces is nobody's snapshot.
     * private var lastLevel = 0
     * override fun content(state: RecorderState): HostContent {
     *     if (state is RecorderState.Recording) lastLevel = state.sample?.level ?: lastLevel
     *     return HostContent("lvl-$lastLevel", CH_RECORDING, "Charging — $lastLevel%")
     * }
     *
     * // YES: everything comes from the argument, so concurrent calls cannot see each other.
     * override fun content(state: RecorderState): HostContent = when (state) {
     *     is RecorderState.Recording -> {
     *         val level = state.sample?.level
     *         HostContent("rec-$level", CH_RECORDING, "Charging — ${level ?: "…"}%")
     *     }
     *     is RecorderState.Idle -> HostContent("idle-${state.recap?.id}", CH_IDLE, state.recap)
     * }
     * ```
     *
     * If you genuinely need to carry something across calls, make it immutable and published
     * safely (`@Volatile`, an `AtomicReference`, a `StateFlow`) — and expect it to be stale, since
     * nothing orders these calls against each other. This is not a theoretical worry: the
     * concurrency bug in §1 of the S1 analysis (`docs/spikes/results/s1-detection-latency/`) was
     * this exact mistake, made inside the recorder itself.
     */
    fun content(state: RecorderState): HostContent

    /** Phase 2 — builds the [Notification] (PendingIntents, icon, style). Called only after the
     *  gate passes, so at most every 5 s while recording. */
    fun build(context: Context, content: HostContent): Notification

    /** The charge target (%) the settle rule uses; read by the recorder when a session opens. */
    fun chargeTargetLevel(): Int

    /**
     * Every ACTION_BATTERY_CHANGED the recorder sees — including the sticky one delivered when
     * it starts — on the main thread. The recorder derives nothing from it beyond its own
     * sampling; a host that wants an alert (a low-battery reminder, say) owns that logic.
     * [screenOn] is the recorder's last observed screen state, null before the first observation.
     */
    fun onBatteryState(level: Int?, plugged: Boolean, screenOn: Boolean?)
}

data class ChannelLabels(val recording: String, val idle: String, val idleDescription: String)

/** Opaque to the recorder except for [dedupeKey] (the gate) and [channelId]. [payload] carries
 *  whatever the host needs to hand from [RecorderHost.content] to [RecorderHost.build]. */
data class HostContent(val dedupeKey: String, val channelId: String, val payload: Any?)

sealed interface RecorderState {
    /** Not recording. [recap] is the most recently finished session, if any is known. */
    data class Idle(val recap: SessionRecap?, val recordEnabled: Boolean) : RecorderState

    /**
     * Recording. [sample] is the latest reading (null until the first one lands).
     * [sessionStartMs] is null in the brief window between the plug-in and the session file
     * opening; [lastRecap] is the previous session, offered as a fallback identity for a
     * host's "open session" affordance in that window. [recentLevels] is the recent
     * `(elapsedRealtimeMs, level%)` history the host may project an ETA from.
     */
    data class Recording(
        val sample: RawLine.Sample?,
        val sessionStartMs: Long?,
        val lastRecap: SessionRecap?,
        val recentLevels: List<Pair<Long, Int>>,
    ) : RecorderState
}
