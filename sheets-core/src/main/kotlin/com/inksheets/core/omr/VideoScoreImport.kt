package com.inksheets.core.omr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.CancellationException

/** Persistable input identity. Platform modules decide whether and how a URL can be resolved. */
@Serializable
sealed class VideoScoreSource {
    abstract val reference: String

    @Serializable
    @SerialName("file")
    data class LocalFile(override val reference: String) : VideoScoreSource() {
        init { require(reference.isNotBlank()) { "A video file path cannot be blank" } }
    }

    @Serializable
    @SerialName("url")
    data class Url(override val reference: String) : VideoScoreSource() {
        init { require(reference.isNotBlank()) { "A video URL cannot be blank" } }
    }
}

@Serializable
enum class VideoImportPhase { RESOLVING, SAMPLING, STABILIZING, ASSEMBLING, READING, ALIGNING, COMPLETE }

@Serializable
data class VideoImportProgress(
    val phase: VideoImportPhase,
    val completed: Int = 0,
    val total: Int? = null,
    val detail: String? = null
) {
    init {
        require(completed >= 0 && (total == null || total >= completed))
    }
}

/** Hard limits owned by the job, independent of decoder implementation. */
@Serializable
data class VideoImportBudget(
    val maxDecodedFrames: Int = 600,
    val maxResidentFrameBytes: Long = 64L * 1024 * 1024,
    val maxOutputPages: Int = 128
) {
    init {
        require(maxDecodedFrames > 0 && maxResidentFrameBytes > 0 && maxOutputPages > 0)
    }
}

/** Platform-neutral decoder information. Runtime frames are deliberately not serializable. */
data class ScoreVideoMetadata(val durationMs: Long, val width: Int, val height: Int) {
    init { require(durationMs >= 0 && width > 0 && height > 0) }
}

/** One bounded greyscale decoder result. No platform bitmap enters core or a saved job. */
data class ScoreVideoFrame(val timestampMs: Long, val width: Int, val height: Int, val grey: ByteArray) {
    init {
        require(timestampMs >= 0 && width > 0 && height > 0)
        require(width.toLong() * height == grey.size.toLong()) { "Frame pixels must match its dimensions" }
    }
}

/** Optional platform adapter. Implementations must honor [maxWidth] before allocating the frame. */
interface SeekableScoreVideo : AutoCloseable {
    val metadata: ScoreVideoMetadata
    fun frameAt(timestampMs: Long, maxWidth: Int): ScoreVideoFrame
}

/** Local-file decoders and URL resolvers are registered outside sheets-core and queried on demand. */
interface ScoreVideoResolver {
    fun supports(source: VideoScoreSource): Boolean
    fun open(source: VideoScoreSource, budget: VideoImportBudget): SeekableScoreVideo
}

fun interface VideoImportCancellation {
    fun isCancelled(): Boolean

    fun throwIfCancelled() {
        if (isCancelled()) throw CancellationException("Video score import cancelled")
    }

    companion object {
        val None = VideoImportCancellation { false }
    }
}

/** Small evidence retained after a decoded frame is released. [fingerprint] is adapter-defined,
 * but is normally a 64-bit perceptual hash; sharpness and change are normalized to 0..1.
 */
data class VideoFrameEvidence(
    val timestampMs: Long,
    val fingerprint: Long,
    val sharpness: Float,
    val change: Float
) {
    init {
        require(timestampMs >= 0 && sharpness.isFinite() && sharpness in 0f..1f)
        require(change.isFinite() && change in 0f..1f)
    }
}

data class VideoFrameSelection(
    val maxFrames: Int = 80,
    val minSpacingMs: Long = 400,
    val minSharpness: Float = .05f,
    val minSceneChange: Float = .1f,
    val duplicateHammingDistance: Int = 4
) {
    init {
        require(maxFrames > 0 && minSpacingMs >= 0)
        require(minSharpness.isFinite() && minSharpness in 0f..1f)
        require(minSceneChange.isFinite() && minSceneChange in 0f..1f)
        require(duplicateHammingDistance in 0..64)
    }
}

/** Deterministically favors scene changes and sharp frames, then returns chronological evidence.
 * It operates on tiny hashes only; decoded images can be released before this step.
 */
object VideoFrameSelector {
    fun select(evidence: List<VideoFrameEvidence>, policy: VideoFrameSelection = VideoFrameSelection()): List<VideoFrameEvidence> {
        val ranked = evidence.filter { it.sharpness >= policy.minSharpness && it.change >= policy.minSceneChange }
            .sortedWith(compareByDescending<VideoFrameEvidence> { it.change }
                .thenByDescending { it.sharpness }.thenBy { it.timestampMs })
        val chosen = ArrayList<VideoFrameEvidence>(minOf(policy.maxFrames, ranked.size))
        for (candidate in ranked) {
            if (chosen.size == policy.maxFrames) break
            if (chosen.any { kotlin.math.abs(it.timestampMs - candidate.timestampMs) < policy.minSpacingMs }) continue
            if (chosen.any { java.lang.Long.bitCount(it.fingerprint xor candidate.fingerprint) <= policy.duplicateHammingDistance }) continue
            chosen += candidate
        }
        return chosen.sortedBy { it.timestampMs }
    }
}

/** Persisted output-page descriptor; pixels live in a bounded cache owned by the import job. */
@Serializable
data class VideoPageCandidate(
    val cacheKey: String,
    val sourceReference: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float
) {
    init {
        require(cacheKey.isNotBlank() && sourceReference.isNotBlank())
        require(startMs >= 0 && endMs >= startMs)
        require(confidence.isFinite() && confidence in 0f..1f)
    }

    fun provenance(): ScoreProvenance = ScoreProvenance(
        kind = "video", reference = sourceReference, startMs = startMs, endMs = endMs
    )
}
