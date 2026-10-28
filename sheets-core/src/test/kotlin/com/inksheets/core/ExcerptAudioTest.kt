package com.inksheets.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ExcerptAudioTest {
    @Test fun `excerpt recording association survives round trip and independent playback edits`() {
        val track=AudioTrack("etude.wav",excerptPart="euph",excerptStartPage=12,excerptEndPage=14,excerptTitle="Etude 7")
        val changed=track.copy(loopStartMs=1500,loopEndMs=9000,speed=.8)
        val restored=Json.decodeFromString<AudioTrack>(Json.encodeToString(changed))
        assertEquals("Etude 7",restored.excerptTitle)
        assertEquals(12,restored.excerptStartPage)
        assertEquals(14,restored.excerptEndPage)
        assertEquals("euph",restored.excerptPart)
        val legacy=Json.decodeFromString<AudioTrack>("""{"file":"whole-book.wav"}""")
        assertNull(legacy.excerptStartPage)
        assertEquals(1.0,legacy.speed,0.0)
    }
}
