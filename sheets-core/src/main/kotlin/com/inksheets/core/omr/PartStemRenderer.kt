package com.inksheets.core.omr

/** A synchronous consumer of one reusable render block. [samples] must be consumed or copied
 * before [write] returns. This keeps full-song arrays out of the renderer and bounds memory.
 */
interface PartStemSink {
    fun begin(partId: String, totalFrames: Long) {}
    fun write(partId: String, offsetFrames: Long, samples: FloatArray, frames: Int)
    fun end(partId: String) {}
}

/** Offline mono stem rendering. It is invoked explicitly by export/stage tools and never by the
 * ordinary reader. One part is rendered at a time with a single reusable block.
 */
object PartStemRenderer {
    fun render(plan: PartPerformance.Plan, sampleRate: Int, sink: PartStemSink,
               blockFrames: Int = 1024, tailMs: Int = 500, cancelled: () -> Boolean = { false }) {
        require(sampleRate > 0 && blockFrames > 0 && tailMs >= 0)
        require(plan.length >= 0)
        val tailFrames = Math.multiplyExact(sampleRate.toLong(), tailMs.toLong()) / 1_000L
        val totalFrames = Math.addExact(plan.length, tailFrames)
        val block = FloatArray(blockFrames)
        for (part in plan.parts) {
            if (cancelled()) return
            val synth = Synth(sampleRate)
            synth.add(part.tones)
            sink.begin(part.id, totalFrames)
            try {
                var offset = 0L
                while (offset < totalFrames) {
                    if (cancelled()) return
                    java.util.Arrays.fill(block, 0f)
                    synth.fill(block)
                    val frames = minOf(block.size.toLong(), totalFrames - offset).toInt()
                    sink.write(part.id, offset, block, frames)
                    offset += frames
                }
            } finally {
                sink.end(part.id)
            }
        }
    }
}
