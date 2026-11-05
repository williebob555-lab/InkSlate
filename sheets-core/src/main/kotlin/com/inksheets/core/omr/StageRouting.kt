package com.inksheets.core.omr

import java.util.Collections

/** A direct path from one scene performer to one microphone, computed off the audio thread.
 * Indices refer to the original scene lists and distinguish repeated part or microphone IDs.
 * [gain] includes the signed microphone response; pan coefficients are applied separately.
 */
data class StageRoute(
    val performerIndex: Int,
    val partId: String,
    val microphoneIndex: Int,
    val microphoneId: String,
    val outputBus: String,
    val distanceMeters: Double,
    val delaySamples: Int,
    val microphoneResponse: Double,
    val gain: Double,
    val panLeft: Double,
    val panRight: Double
)

/** Builds routes in performer-major, microphone-minor scene order. Each performer is one
 * source, including performers with multiple players; player expansion is a separate concern.
 */
object StageRouting {
    fun build(scene: StageScene, sampleRate: Int): List<StageRoute> {
        require(sampleRate > 0) { "Sample rate must be positive" }
        require(scene.room.speedOfSound.isFinite() && scene.room.speedOfSound > 0.0)
        require(scene.room.nearDistance.isFinite() && scene.room.nearDistance > 0.0)
        val routes = ArrayList<StageRoute>()
        val performerSolo = scene.performers.any { it.solo && !it.muted }
        val microphoneSolo = scene.microphones.any { it.solo && !it.muted }
        scene.performers.forEachIndexed performer@{ performerIndex, performer ->
            require(performer.x.isFinite() && performer.y.isFinite() && performer.height.isFinite() && performer.gain.isFinite())
            if (performer.muted || (performerSolo && !performer.solo)) return@performer
            scene.microphones.forEachIndexed microphone@{ microphoneIndex, microphone ->
                require(microphone.x.isFinite() && microphone.y.isFinite() && microphone.height.isFinite() &&
                    microphone.facing.isFinite() && microphone.gain.isFinite() && microphone.pan.isFinite())
                if (microphone.muted || (microphoneSolo && !microphone.solo)) return@microphone
                val distance = SpatialMath.distance(performer.x, performer.y, performer.height,
                    microphone.x, microphone.y, microphone.height)
                val response = SpatialMath.microphoneResponse(microphone, performer.x, performer.y)
                val gain = performer.gain * microphone.gain *
                    SpatialMath.distanceAttenuation(distance, scene.room.nearDistance) * response
                require(gain.isFinite()) { "Route gain must be finite" }
                routes.add(StageRoute(performerIndex, performer.partId, microphoneIndex, microphone.id,
                    microphone.outputBus, distance,
                    SpatialMath.propagationDelaySamples(distance, sampleRate, scene.room.speedOfSound),
                    response, gain, SpatialMath.panLeft(microphone.pan), SpatialMath.panRight(microphone.pan)))
            }
        }
        return Collections.unmodifiableList(routes)
    }
}
