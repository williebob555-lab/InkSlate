package com.inksheets.core.omr

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Stage coordinates are meters: x increases to the right and y toward the back of the stage.
 * Facing is in radians, clockwise from +y (so PI/2 faces +x). Gains are linear amplitudes.
 */
@Serializable
data class StagePoint(val x: Double = 0.0, val y: Double = 0.0)

@Serializable
data class StageRoom(
    val width: Double = 20.0,
    val depth: Double = 15.0,
    val height: Double = 8.0,
    /** Speed of sound in meters per second. */
    val speedOfSound: Double = 343.0,
    /** Distance at which direct sound reaches unity, also the near-distance clamp, in meters. */
    val nearDistance: Double = 1.0
) {
    init {
        require(width.isFinite() && width > 0.0 && depth.isFinite() && depth > 0.0 &&
            height.isFinite() && height > 0.0) { "Stage dimensions must be finite and positive" }
        require(speedOfSound.isFinite() && speedOfSound > 0.0) { "Speed of sound must be finite and positive" }
        require(nearDistance.isFinite() && nearDistance > 0.0) { "Near distance must be finite and positive" }
    }
}

@Serializable
data class StagePerformer(
    /** Stable ID of the score part to play. */
    val partId: String,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val facing: Double = 0.0,
    val playerCount: Int = 1,
    /** Width occupied by the players, in meters. */
    val spread: Double = 0.0,
    val gain: Double = 1.0,
    val muted: Boolean = false,
    val solo: Boolean = false,
    /** Acoustic source height above the floor, in meters. */
    val height: Double = 1.2
) {
    init {
        require(partId.isNotBlank()) { "A performer must reference a score part" }
        require(playerCount > 0) { "A performer must have at least one player" }
    }
}

@Serializable
enum class MicrophonePattern { OMNI, CARDIOID, FIGURE_EIGHT }

@Serializable
data class StageMicrophone(
    val id: String,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val facing: Double = 0.0,
    val pattern: MicrophonePattern = MicrophonePattern.OMNI,
    val gain: Double = 1.0,
    /** Named destination for routing; pan is -1 (left) to +1 (right). */
    val outputBus: String = "main",
    val pan: Double = 0.0,
    val muted: Boolean = false,
    val solo: Boolean = false,
    /** Capsule height above the floor, in meters. */
    val height: Double = 1.2
) {
    init { require(id.isNotBlank()) { "A microphone must have an ID" } }
}

/** Serializable scene description. Defaults are intentionally silent until sources are added. */
@Serializable
data class StageScene(
    val podium: StagePoint = StagePoint(),
    val listener: StagePoint = StagePoint(),
    val room: StageRoom = StageRoom(),
    val performers: List<StagePerformer> = emptyList(),
    val microphones: List<StageMicrophone> = emptyList()
)

/** Stateless scalar calculations suitable for precomputing a source-to-microphone route. */
object SpatialMath {
    fun distance(x1: Double, y1: Double, x2: Double, y2: Double): Double = hypot(x2 - x1, y2 - y1)

    /** Straight-line distance including height, in meters. */
    fun distance(x1: Double, y1: Double, height1: Double, x2: Double, y2: Double, height2: Double): Double =
        hypot(hypot(x2 - x1, y2 - y1), height2 - height1)

    /** Inverse-distance amplitude, clamped to unity at and inside [nearDistance]. */
    fun distanceAttenuation(distance: Double, nearDistance: Double = 1.0): Double {
        require(distance.isFinite() && distance >= 0.0 && nearDistance.isFinite() && nearDistance > 0.0)
        return nearDistance / maxOf(distance, nearDistance)
    }

    /** Equal-power panning. Inputs outside -1..1 are clamped. */
    fun panLeft(pan: Double): Double = cos((pan.coerceIn(-1.0, 1.0) + 1.0) * PI / 4.0)
    fun panRight(pan: Double): Double = sin((pan.coerceIn(-1.0, 1.0) + 1.0) * PI / 4.0)

    /** Signed pressure response. The rear lobe of a figure-eight reverses polarity. */
    fun polarResponse(pattern: MicrophonePattern, angleFromFront: Double): Double = when (pattern) {
        MicrophonePattern.OMNI -> 1.0
        MicrophonePattern.CARDIOID -> (1.0 + cos(angleFromFront)) / 2.0
        MicrophonePattern.FIGURE_EIGHT -> cos(angleFromFront)
    }

    /** Response to a source at the given coordinates. Coincident positions are on-axis. */
    fun microphoneResponse(microphone: StageMicrophone, sourceX: Double, sourceY: Double): Double {
        val dx = sourceX - microphone.x
        val dy = sourceY - microphone.y
        val distance = hypot(dx, dy)
        if (distance == 0.0) return 1.0
        val cosine = ((dx * sin(microphone.facing) + dy * cos(microphone.facing)) / distance).coerceIn(-1.0, 1.0)
        return when (microphone.pattern) {
            MicrophonePattern.OMNI -> 1.0
            MicrophonePattern.CARDIOID -> (1.0 + cosine) / 2.0
            MicrophonePattern.FIGURE_EIGHT -> cosine
        }
    }

    /** Nearest whole sample of direct-path travel time. */
    fun propagationDelaySamples(distance: Double, sampleRate: Int, speedOfSound: Double = 343.0): Int {
        require(distance.isFinite() && distance >= 0.0 && sampleRate > 0 && speedOfSound.isFinite() && speedOfSound > 0.0)
        return (distance / speedOfSound * sampleRate).roundToInt()
    }
}
