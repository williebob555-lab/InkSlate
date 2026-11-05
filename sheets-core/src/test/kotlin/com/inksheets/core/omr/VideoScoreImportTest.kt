package com.inksheets.core.omr

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class VideoScoreImportTest {
    @Test fun `sources budgets progress and page provenance serialize without runtime pixels`() {
        val json = Json { classDiscriminator = "sourceKind" }
        val source: VideoScoreSource = VideoScoreSource.Url("https://example.invalid/score")
        assertEquals(source, json.decodeFromString<VideoScoreSource>(json.encodeToString(source)))
        assertEquals(VideoImportBudget(), Json.decodeFromString<VideoImportBudget>(Json.encodeToString(VideoImportBudget())))
        val progress = VideoImportProgress(VideoImportPhase.SAMPLING, 3, 12, "Finding page turns")
        assertEquals(progress, Json.decodeFromString<VideoImportProgress>(Json.encodeToString(progress)))
        val page = VideoPageCandidate("page-a", source.reference, 1_000, 4_000, .8f)
        assertEquals(ScoreProvenance("video", source.reference, startMs = 1_000, endMs = 4_000), page.provenance())
    }

    @Test fun `frame selector is bounded scene aware deduplicated and chronological`() {
        val evidence = listOf(
            VideoFrameEvidence(0, 0x00, .9f, .9f),
            VideoFrameEvidence(100, 0x01, 1f, 1f),       // too close to 0 and perceptually duplicate
            VideoFrameEvidence(1_000, 0x00, .9f, .8f),  // duplicate even though distant
            VideoFrameEvidence(2_000, 0x0f, .1f, 1f),   // blurry
            VideoFrameEvidence(3_000, 0xff, .8f, .05f), // no scene change
            VideoFrameEvidence(4_000, 0xffff, .7f, .7f),
            VideoFrameEvidence(6_000, -1L, .8f, .8f)
        )
        val selected = VideoFrameSelector.select(evidence, VideoFrameSelection(maxFrames = 2))
        assertEquals(listOf(100L, 6_000L), selected.map { it.timestampMs })
        assertEquals(selected, VideoFrameSelector.select(evidence, VideoFrameSelection(maxFrames = 2)))
    }

    @Test fun `runtime frame validates allocation size and cancellation stays cooperative`() {
        assertEquals(4, ScoreVideoFrame(25, 2, 2, ByteArray(4)).grey.size)
        fun invalid(block: () -> Unit) {
            try { block(); throw AssertionError("Expected invalid video import value") }
            catch (_: IllegalArgumentException) { }
        }
        invalid { ScoreVideoFrame(0, 2, 2, ByteArray(3)) }
        invalid { VideoImportProgress(VideoImportPhase.READING, 3, 2) }
        invalid { VideoImportBudget(maxResidentFrameBytes = 0) }
        VideoImportCancellation.None.throwIfCancelled()
        try {
            VideoImportCancellation { true }.throwIfCancelled()
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) { }
        assertTrue(VideoScoreSource.LocalFile("score.mp4").reference.endsWith(".mp4"))
    }
}
