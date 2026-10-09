package com.inksheets.desktop.review

import com.inksheets.core.Instrument
import com.inksheets.core.InstrumentProfile
import com.inksheets.core.InstrumentReader
import com.inksheets.core.Instruments
import com.inksheets.core.LibraryTrash
import com.inksheets.core.PartChoice
import com.inksheets.desktop.review.T1.say
import org.junit.Test
import java.io.File
import java.io.FileInputStream

/** T1: instruments, aliases, chairs, part choice, a file held open by the viewer, search semantics. */
class T1Core3Test {

    @Test
    fun `r1 r3 instruments aliases profiles and part choice`() {
        val root = T1.copyLib("c3-inst")
        val l = T1.Lib(root); l.scan.run(); l.scan.run()
        val lib = l.library
        // reading names
        for (n in listOf("Stars and Stripes - Sousaphone.pdf", "Tune - DL - Snare.pdf", "Tune - Drum Line.pdf", "Tune - Trombone 2.pdf", "Tune - Tbn 3.pdf", "Tune - Bone 1.pdf", "Tune - Bari TC.pdf",
            "Tune - Euph.pdf", "Tune - Trombones 1-2.pdf", "Tune - Bass.pdf", "Tune - Bass Trombone.pdf", "Tune - Electric Bass.pdf", "Tune - E Bass.pdf", "Tune - Bb Bass Clef.pdf", "Tune - Full Score.pdf")) {
            val m = InstrumentReader.readFileName(n)
            say("name '$n' -> ${m?.instrument?.id} label='${m?.label}' chair=${com.inksheets.core.ImportPlan.readPart(n).chair}")
        }
        // Teach an instrument with aliases and read again
        val soursa = Instrument(id = "sousaphone", name = "Sousaphone", names = listOf("Sousaphone", "Sousa", "Contrabass Sarrusophone"), clef = "bass")
        lib.saveInstrument(soursa)
        Instruments.use(lib.instruments())
        say("after teaching Sousaphone: 'Marches - Sousa 1.pdf' -> ${InstrumentReader.readFileName("Marches - Sousa 1.pdf")?.instrument?.id}")
        // Adding an instrument that duplicates a built-in name / alias of another
        lib.saveInstrument(Instrument(id = "trombone2", name = "Trombone", names = listOf("Trombone", "Euphonium")))
        Instruments.use(lib.instruments())
        say("duplicate-named instrument allowed: instruments named Trombone=${Instruments.all.count { it.name == "Trombone" }}; 'Tune - Euphonium.pdf' now reads as ${InstrumentReader.readFileName("Tune - Euphonium.pdf")?.instrument?.id}")
        lib.saveInstrument(Instrument(id = "blank", name = "", names = emptyList()))
        Instruments.use(lib.instruments())
        say("blank-named instrument saved: ${Instruments.all.count { it.name.isBlank() }} blank in list")
        lib.deleteInstrument("trombone2"); lib.deleteInstrument("blank"); Instruments.use(lib.instruments())
        say("after deleting the test instruments: ${Instruments.all.count { it.name == "Trombone" }} Trombone; Euphonium reads as ${InstrumentReader.readFileName("Tune - Euphonium.pdf")?.instrument?.id}")
        // Part choice
        val tbn = lib.profiles().first { it.id == "trombone" }
        val bass = lib.profiles().first { it.id == "bass-guitar" }
        val bari = lib.profiles().first { it.name.contains("Baritone") }
        say("profiles: ${lib.profiles().map { it.name + "=" + it.instruments }}")
        for (title in listOf("24K Magic", "Danza Kuduro", "Dance of the Jesters", "Belgian Parachutists", "9 to 5", "Chester", "Blinding Lights")) {
            val s = lib.songs.first { it.title == title }
            for (p in listOf(tbn, bass, bari)) {
                val part = PartChoice.partFor(s, p)
                say("choice '${s.title}' for ${p.name}: ${part?.let { Instruments.partName(it) }} fit=${PartChoice.fit(s, p)} standIn=${PartChoice.standIn(s, p)?.let { Instruments.partName(it) }}")
            }
        }
        val seat = InstrumentProfile("t2", "Trombone 2 only", listOf("trombone:2"))
        lib.saveProfile(seat)
        val s24 = lib.songs.first { it.title == "24K Magic" }
        say("chair profile trombone:2 on 24K Magic -> ${PartChoice.partFor(s24, seat)?.let { Instruments.partName(it) }}; on Blinding Lights -> ${PartChoice.partFor(lib.songs.first { it.title == "Blinding Lights" }, seat)?.let { Instruments.partName(it) }}")
        // empty profile
        val none = InstrumentProfile("t3", "", emptyList())
        lib.saveProfile(none)
        say("profile with no name/instruments shows ${PartChoice.songsFor(lib.songs, none).size} of ${lib.songs.size} songs")
    }

    @Test
    fun `r3 removing a song whose file is held open`() {
        val root = T1.copyLib("c3-lock")
        val l = T1.Lib(root); l.scan.run(); l.scan.run()
        val trash = LibraryTrash(root, l.library)
        val song = l.library.songs.first { it.title == "Call Me" }
        val held = song.parts.map { FileInputStream(File(root, it.file)) }   // the viewer has it open
        val outcome = runCatching { trash.remove(song) }
        say("remove with files open (Windows): ${outcome.fold({ "ok entry=${it.title}" }, { "threw ${it.javaClass.simpleName}: ${it.message}" })}")
        say("  files still in library folder: ${song.parts.count { File(root, it.file).isFile }} of ${song.parts.size}; song listed=${l.titles().contains("Call Me")}; parts of it still referenced=${l.library.songs.sumOf { s -> s.parts.count { p -> song.parts.any { it.id == p.id } } }}")
        held.forEach { it.close() }
        val r = l.scan.run()
        say("  scan after the viewer let go: added=${r.added} removed=${r.removed}; song listed=${l.titles().contains("Call Me")}")
        say("  trash entries=${trash.entries().map { it.title }}; trash files=${File(root, ".inksheets/trash").walkTopDown().count { it.isFile }}")
        // restoring when copies exist in both places
        trash.entries().firstOrNull()?.let { say("  restore: ${trash.restore(it)}; song listed=${l.titles().contains("Call Me")}") }
    }

    @Test
    fun `r3 search semantics of the three lists`() {
        // The Songs tab: the whole query is one substring in one field.
        fun songsTab(q: String, title: String, composers: List<String>, tags: List<String> = emptyList()) =
            (listOf(title) + composers + tags).any { it.lowercase().contains(q.trim().lowercase()) }
        say("Songs tab 'bell liberty' finds 'The Liberty Bell' = ${songsTab("bell liberty", "The Liberty Bell", listOf("Sousa"))}")
        say("Songs tab 'liberty sousa' (title + composer) finds it = ${songsTab("liberty sousa", "The Liberty Bell", listOf("John Philip Sousa"))}")
        say("Songs tab 'dvorak' finds 'Dvořák Symphony' = ${songsTab("dvorak", "Dvořák Symphony", emptyList())}")
        say("Songs tab 'liberty bell' finds 'Liberty Bell, The' = ${songsTab("the liberty bell", "Liberty Bell, The", emptyList())}")
        say("Songs tab 'trombone' finds a song by its instrument = false (instruments not searched); notes not searched; file names not searched")
        say("Songs tab 'ain't' finds 'Ain’t It Fun' (curly apostrophe) = ${songsTab("ain't", "Ain’t It Fun", emptyList())}")
    }
}
