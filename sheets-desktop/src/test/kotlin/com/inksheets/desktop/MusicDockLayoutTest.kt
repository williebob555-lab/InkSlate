package com.inksheets.desktop

import com.inksheets.ui.MusicDockView
import com.inksheets.ui.chooseMusicDock
import com.inksheets.ui.WaveformEnvelope
import org.junit.Assert.*
import org.junit.Test

class MusicDockLayoutTest {
    @Test fun `auto reserves the arrangement with a meaningfully larger fitted score`() {
        assertEquals(MusicDockView.STUDIO, chooseMusicDock(MusicDockView.AUTO,1600f,1000f,.77f,132f))
        assertEquals(MusicDockView.SHELF, chooseMusicDock(MusicDockView.AUTO,800f,1280f,.77f,132f))
        assertEquals(MusicDockView.SHELF, chooseMusicDock(MusicDockView.STUDIO,360f,640f,.77f,132f))
        assertEquals(MusicDockView.SHELF, chooseMusicDock(MusicDockView.STUDIO,640f,220f,.77f,116f))
        assertEquals(MusicDockView.STUDIO, chooseMusicDock(MusicDockView.STUDIO,800f,1280f,.77f,132f))
        assertEquals(MusicDockView.SHELF, chooseMusicDock(MusicDockView.SHELF,1600f,1000f,.77f,132f))
        for ((_,w,h) in UiAudit.SIZES) for (aspect in listOf(.5f,.77f,1.4f,2f)) {
            val choice=chooseMusicDock(MusicDockView.AUTO,w.toFloat(),h.toFloat(),aspect,132f)
            if(choice==MusicDockView.STUDIO) {
                val rail=minOf(w-236f,(h-44)*aspect)
                val shelf=minOf(w.toFloat(),(h-176)*aspect)
                assertTrue("$w x $h $aspect sacrifices score space",rail>shelf*1.08f)
            }
        }
    }

    @Test fun `waveform preserves real peaks through long recording compaction and silent audio`() {
        assertTrue(WaveformEnvelope().finish().isEmpty())
        val silence=WaveformEnvelope().apply{add(FloatArray(20000))}.finish()
        assertEquals(4096,silence.size)
        assertTrue(silence.all{it==0f})
        val envelope=WaveformEnvelope()
        repeat(600){chunk -> envelope.add(FloatArray(4096){if(chunk in 200..250) .8f else .1f})}
        val result=envelope.finish()
        assertEquals(4096,result.size)
        assertTrue(result.all{it.isFinite() && it in 0f..1f})
        assertTrue(result.slice(result.size*25/80..result.size*35/80).any{it>.9f})
        assertTrue(result.first()<.2f)
        assertTrue(result.last()<.2f)
    }
}
