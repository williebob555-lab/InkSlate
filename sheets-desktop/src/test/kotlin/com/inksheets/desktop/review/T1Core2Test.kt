package com.inksheets.desktop.review

import com.inksheets.core.InstrumentSource
import com.inksheets.core.Library
import com.inksheets.core.LibraryScan
import com.inksheets.desktop.review.T1.say
import org.junit.Test
import java.io.File

/** T1 round 3/4: the races and odd orders a real folder produces. */
class T1Core2Test {

    @Test
    fun `r3 song editor save is a stale snapshot`() {
        val root = T1.copyLib("c2-editor")
        val l = T1.Lib(root); l.scan.run(); l.scan.run()
        val lib = l.library
        val song = lib.songs.first { it.title == "Blinding Lights" }
        // The editor opens with this snapshot...
        val snapshot = song.parts
        // ...meanwhile a new part of the song arrives through the folder (Syncthing)
        File(root, "MobileSheets/Blinding Lights - Tuba.pdf").also { File(root, "MobileSheets/Blinding Lights - Trombone 1.pdf").copyTo(it); it.appendBytes(ByteArray(900) { 5 }) }
        l.scan.run()
        say("before save: parts=${lib.song(song.id)!!.parts.map { it.file.substringAfterLast('/') }}")
        // ...and the person presses Save: the editor writes parts = its snapshot (as SongEditorDialog does)
        lib.editSong(song.id) { this.title = "Blinding Lights"; this.parts = snapshot }
        say("after save: parts=${lib.song(song.id)!!.parts.map { it.file.substringAfterLast('/') }}")
        val r = l.scan.run()
        say("after rescan: added=${r.added} parts=${lib.song(song.id)!!.parts.map { it.file.substringAfterLast('/') }}  tuba file still on disk=${File(root, "MobileSheets/Blinding Lights - Tuba.pdf").isFile}")
        // The background instrument reader writes while the editor is open: its result is overwritten by the snapshot
        val s2 = lib.songs.first { it.title == "Chester" }
        val snap2 = s2.parts
        lib.editSong(s2.id) { parts = s2.parts.map { it.copy(instrument = "euphonium", source = InstrumentSource.OCR) } }
        lib.editSong(s2.id) { parts = snap2 }
        say("reader result then stale editor save: instrument now=${lib.song(s2.id)!!.parts.map { it.instrument }}")
    }

    @Test
    fun `r3 a file deleted then copied back keeps its old date`() {
        val root = T1.copyLib("c2-readd")
        val l = T1.Lib(root); l.scan.run(); l.scan.run()
        val f = File(root, "MobileSheets/Chester.pdf")
        val bytes = f.readBytes()
        val oldDate = f.lastModified()
        f.delete()
        var r = l.scan.run()
        say("deleted Chester.pdf: removed=${r.removed} song listed=${l.titles().contains("Chester")}")
        Thread.sleep(1100)
        f.writeBytes(bytes); f.setLastModified(oldDate)       // an Explorer copy keeps the original date
        r = l.scan.run()
        say("copied back with its old date: added=${r.added} song listed=${l.titles().contains("Chester")}")
        f.setLastModified(System.currentTimeMillis())
        r = l.scan.run()
        say("same file touched: added=${r.added} song listed=${l.titles().contains("Chester")}")
    }

    @Test
    fun `r3 a folder renamed keeps hand-set instruments`() {
        val root = T1.copyLib("c2-folder")
        val l = T1.Lib(root); l.scan.run(); l.scan.run()
        val lib = l.library
        val song = lib.songs.first { it.title == "24K Magic" }
        val p = song.parts.first { it.file.contains("Imported") && it.instrument == "trombone" }
        lib.editSong(song.id) { parts = song.parts.map { if (it.id == p.id) it.copy(instrument = "euphonium", source = InstrumentSource.PERSON) else it } }
        File(root, "Imported/PEP BAND/Music/24K Magic").renameTo(File(root, "Imported/PEP BAND/Music/24K Magic (old)"))
        val r = l.scan.run()
        val after = lib.songs.filter { it.title.contains("24K") }
        say("renamed song folder: moved=${r.moved.size} added=${r.added.size} removed=${r.removed.size} merged=${r.merged} songs=${after.map { it.title + "/" + it.parts.size }}")
        val q = after.flatMap { it.parts }.firstOrNull { it.id == p.id }
        say("hand-set part after folder rename: ${q?.let { it.instrument to it.source }} (file=${q?.file})")
        // Folder rename with identical file names in each song folder (Trombone 1.pdf everywhere)
        File(root, "Imported/PEP BAND/Music/Boogie Down").renameTo(File(root, "Imported/PEP BAND/Music/Boogie Down X"))
        val r2 = l.scan.run()
        say("renamed second folder: moved=${r2.moved.size} added=${r2.added.size} removed=${r2.removed.size} merged=${r2.merged}")
    }

    @Test
    fun `r3 a case-only rename of a file`() {
        val root = T1.copyLib("c2-case")
        val l = T1.Lib(root); l.scan.run(); l.scan.run()
        val from = File(root, "MobileSheets/Colonial Song.pdf").toPath()
        val tmp = File(root, "MobileSheets/zz-tmp.pdf").toPath()
        java.nio.file.Files.move(from, tmp)
        java.nio.file.Files.move(tmp, File(root, "MobileSheets/colonial song.pdf").toPath())
        say("on disk now: ${LibraryScan.listMusic(root).map { it.path }.filter { it.contains("olonial") }}")
        var r = l.scan.run()
        say("case-only rename: moved=${r.moved} added=${r.added} removed=${r.removed} song listed=${l.titles().contains("Colonial Song")} parts=${l.library.songs.filter { it.title.contains("olonial") }.map { it.parts.map { p -> p.file } }}")
        r = l.scan.run()
        say("case-only rename, second scan: added=${r.added} removed=${r.removed} song listed=${l.titles().contains("Colonial Song")} missing=${l.scan.missing().map { it.second.file }}")
    }

    @Test
    fun `r3 two songs claiming one title and instrument both ways`() {
        val root = T1.copyLib("c2-dups")
        val l = T1.Lib(root); l.scan.run(); l.scan.run()
        val lib = l.library
        // Same filename in two folders
        File(root, "A/Same Tune - Trombone 1.pdf").also { it.parentFile.mkdirs(); File(root, "MobileSheets/Chester.pdf").copyTo(it); it.appendBytes(ByteArray(11) { 1 }) }
        File(root, "B/Same Tune - Trombone 1.pdf").also { it.parentFile.mkdirs(); File(root, "MobileSheets/Chester.pdf").copyTo(it); it.appendBytes(ByteArray(22) { 2 }) }
        var r = l.scan.run()
        say("same-name files in two folders: added=${r.added} songs=${lib.songs.filter { it.title.startsWith("Same") }.map { it.parts.size to it.duplicates.size }}")
        // Now move one
        File(root, "A/Same Tune - Trombone 1.pdf").renameTo(File(root, "C_Same Tune - Trombone 1.pdf"))
        r = l.scan.run()
        say("one of them moved: moved=${r.moved} removed=${r.removed} added=${r.added}")
        // Scan while Library log has an unreadable line
        File(root, ".inksheets/log").listFiles()!!.first { it.name.endsWith(".jsonl") }.appendText("{this is not json\n")
        val l2 = T1.Lib(root, "t1-other")
        say("library with a corrupt log line opens: songs=${l2.library.songs.size}")
        // log file truncated mid-line
        val own = File(root, ".inksheets/log/t1-laptop.jsonl")
        val bytes = own.readBytes(); own.writeBytes(bytes.copyOf(bytes.size - 20))
        val l3 = T1.Lib(root, "t1-third")
        say("log cut mid-line: songs=${l3.library.songs.size}")
        // Library folder with the .inksheets/log deleted (user cleans up)
        File(root, ".inksheets/log").deleteRecursively()
        val l4 = T1.Lib(root, "t1-fourth")
        val rr = LibraryScan(root, l4.library, File(root.parentFile, "c2-mem4.json")).run()
        say("log wiped: songs=${l4.library.songs.size} after scan added=${rr.added.size}; setlists=${l4.library.setlists.size}")
    }

    @Test
    fun `r4 many setlists many folders deep nesting`() {
        val root = T1.copyLib("c2-deep")
        val l = T1.Lib(root); l.scan.run()
        val lib = l.library
        var parent: String? = null
        val t0 = System.nanoTime()
        repeat(60) { parent = lib.addFolder("Level $it", parent).id }
        say("60 nested folders made in ${(System.nanoTime() - t0) / 1_000_000} ms")
        say("pathTo deepest length=${lib.pathTo(parent).size}")
        val t1 = System.nanoTime()
        repeat(200) { lib.addSetlist("List $it", parent) }
        say("200 setlists made in ${(System.nanoTime() - t1) / 1_000_000} ms; setlistsUnder(top) ${lib.setlistsUnder(lib.folders.first { it.name == "Level 0" }.id).size}")
        // same folder name 100x
        repeat(100) { lib.addFolder("Pep Band") }
        say("100 same-named folders made; foldersIn(null) with that name = ${lib.foldersIn(null).count { it.name == "Pep Band" }}")
    }
}
