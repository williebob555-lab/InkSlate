package com.inksheets.desktop.review

import com.inksheets.core.ImportPlan
import com.inksheets.core.InstrumentSource
import com.inksheets.desktop.installInkSheets
import com.inksheets.desktop.review.T1.say
import org.junit.After
import org.junit.Test
import com.inkslate.desktop.AppFlavor

/** T1: how titles are cut from file names, and "Redo automatic assignment" on the test library. */
class T1Core5Test {

    @After
    fun reset() {
        AppFlavor.home = null; AppFlavor.paneOverlay = null; AppFlavor.onHomeShown = null; AppFlavor.onHome = null; AppFlavor.settingsSection = null
        AppFlavor.musicView = false; AppFlavor.fingerPans = false; AppFlavor.alwaysFullscreen = false
    }

    @Test
    fun `titles that end in a word that is also an instrument`() {
        for (n in listOf("MobileSheets/Cafe del Mar - Trombone 1.pdf", "MobileSheets/The Magic Flute - Trombone 1.pdf", "MobileSheets/Tuba Tiger - Tuba.pdf",
            "MobileSheets/French Horn Concerto - Trombone 1.pdf", "MobileSheets/Bass Reeves March - Bass.pdf", "MobileSheets/Saxophone Colossus - Trombone 1.pdf",
            "MobileSheets/Drum Major - Trombone 1.pdf", "MobileSheets/Slide - Trombone 1.pdf", "MobileSheets/The Trumpet Shall Sound - Trombone.pdf", "MobileSheets/Piano Man - Trombone 1.pdf",
            "MobileSheets/Brass Bonanza - Trombone 1.pdf", "MobileSheets/Sing Sing Sing - Trombone 1.pdf", "MobileSheets/Hey Jude Trombone 1.pdf", "MobileSheets/Take Five-Bass.pdf",
            "MobileSheets/Seven Nation Army (Bass).pdf", "MobileSheets/Seven Nation Army (arr. Smith) - Euphonium.pdf", "MobileSheets/Tubular Bells - Tuba.pdf", "MobileSheets/Mar.pdf",
            "MobileSheets/Fanfare for the Common Man Trombone.pdf", "MobileSheets/Trombone Shorty.pdf", "MobileSheets/A Little Night Music Bass.pdf", "MobileSheets/Cafe del Mar.pdf")) {
            say("title of '${n.substringAfterLast('/')}': '${ImportPlan.songTitle(n, false)}' / as folder song: '${ImportPlan.songTitle(n, true)}' instrument=${ImportPlan.readPart(n).instrument}")
        }
    }

    @Test
    fun `r1 redo automatic assignment leaves hand-set parts alone`() {
        val lib = T1.copyLib("c5-redo")
        val sheets = installInkSheets { it.setPref("sheets_library", lib.absolutePath) }
        val s = sheets()
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end && s.library == null) Thread.sleep(100)
        val l = s.library!!
        s.scanFolder()
        val chester = l.songs.first { it.title == "Chester" }
        l.editSong(chester.id) { parts = chester.parts.map { it.copy(instrument = "tuba", source = InstrumentSource.PERSON) } }
        val unknown = l.songs.flatMap { it.parts }.count { it.instrument == null }
        T1.timed("reassignInstruments over ${l.songs.sumOf { it.parts.size }} parts") { s.reassignInstruments() }
        val after = l.songs.flatMap { it.parts }
        say("redo: unknown instruments before=$unknown after=${after.count { it.instrument == null }}; hand-set Chester still tuba=${l.song(chester.id)!!.parts.map { it.instrument }}; reassigned msg='${s.reassigned}'")
    }
}
