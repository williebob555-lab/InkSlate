package com.inksheets.core.omr

/**
 * Offline/reference stage render. It consumes already-rendered mono part stems; score planning and
 * instrument synthesis stay separate. Routes are precomputed once, then each output bus is mixed
 * by the lightweight direct stereo mixer.
 */
object StageAudio {
    fun render(partStems: Map<String, FloatArray>, scene: StageScene, sampleRate: Int,
               levelOutput: Boolean = true): Map<String, StereoSignal> {
        val routes = StageRouting.build(scene, sampleRate)
        return routes.mapNotNull { route ->
            val samples = partStems[route.partId] ?: return@mapNotNull null
            val microphone = scene.microphones[route.microphoneIndex]
            route.outputBus to StereoStem(
                partId = route.partId,
                samples = samples,
                gain = route.gain.toFloat(),
                pan = microphone.pan.toFloat(),
                delaySamples = route.delaySamples
            )
        }.groupBy({ it.first }, { it.second })
            .mapValues { (_, stems) -> DirectStereoMix.render(stems, sampleRate, levelOutput) }
    }
}
