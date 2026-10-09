package com.inksheets.desktop.review

import com.inksheets.core.LibraryScan
import com.inksheets.core.LibraryTrash
import com.inksheets.desktop.review.T1.say
import org.junit.Test
import java.io.File

/** T1 rounds 1-4 on the library engine: scanning, trash, merge/split, setlists, folders. No UI. */
class T1CoreTest {

    @Test
    fun `r1 first scan, second scan, sort idempotence`() {
        val root = T1.copyLib("r1")
        val l = T1.Lib(root)
        lateinit var r1: LibraryScan.Report
        T1.timed("first scan of ${root.walkTopDown().count { it.isFile }} files") { r1 = l.scan.run() }
        say("first scan: added=${r1.added.size} moved=${r1.moved.size} removed=${r1.removed.size} merged=${r1.merged.size} sorted=${r1.sorted.size} songs=${l.library.songs.size} parts=${l.parts()}")
        l.library.songs.forEach { s -> say("SONG '${s.title}' parts=${s.parts.size} dup=${s.duplicates.size} audio=${s.audio.size} instr=${s.parts.map { it.instrument }}") }
        lateinit var r2: LibraryScan.Report
        T1.timed("second scan") { r2 = l.scan.run() }
        say("second scan changed=${r2.changed}: added=${r2.added} merged=${r2.merged} sorted=${r2.sorted}")
        say("strays: ${l.scan.strays()}")
        say("missing: ${l.scan.missing().map { it.second.file }}")
    }

    @Test
    fun `r3 files change underneath the app`() {
        val root = T1.copyLib("r3")
        val l = T1.Lib(root)
        l.scan.run(); l.scan.run()
        val before = l.library.songs.size
        File(root, "MobileSheets/Chester.pdf").copyTo(File(root, "MobileSheets/Brand New Tune - Trombone 1.pdf"))
        var r = l.scan.run()
        say("new file: added=${r.added}")
        File(root, "MobileSheets/Brand New Tune - Trombone 1.pdf").renameTo(File(root, "MobileSheets/Brand Newer Tune - Trombone 1.pdf"))
        r = l.scan.run()
        say("renamed: added=${r.added} moved=${r.moved} removed=${r.removed} songs=${l.titles().filter { it.startsWith("Brand") }}")
        File(root, "Moved").mkdirs()
        File(root, "MobileSheets/Brand Newer Tune - Trombone 1.pdf").renameTo(File(root, "Moved/Brand Newer Tune - Trombone 1.pdf"))
        r = l.scan.run()
        say("moved to Moved/: moved=${r.moved} added=${r.added} removed=${r.removed}")
        say("songs named Brand*: ${l.library.songs.filter { it.title.startsWith("Brand") }.map { it.title to it.parts.map { p -> p.file } }}")
        T1.touch(File(root, "MobileSheets/.syncthing.Zed - Trombone 1.pdf.tmp"))
        T1.touch(File(root, "MobileSheets/~syncthing~Zed.pdf.tmp"))
        File(root, "MobileSheets/Chester.pdf").copyTo(File(root, "MobileSheets/Chester.sync-conflict-20261008-101010-ABCDEFG.pdf"))
        File(root, "MobileSheets/Chester.pdf").copyTo(File(root, "MobileSheets/Chester (conflicted copy).pdf"))
        File(root, "MobileSheets/Chester.pdf").copyTo(File(root, "MobileSheets/Chester - Trombone 1 (1).pdf"))
        r = l.scan.run()
        say("syncthing debris + copies: added=${r.added}")
        File(root, "MobileSheets/Chesapeake.pdf").delete()
        r = l.scan.run()
        say("deleted Chesapeake: removed=${r.removed} song still listed=${l.titles().any { it.contains("Chesapeake") }}")
        File(root, "MobileSheets/Colonial Song.pdf").renameTo(File(root, "MobileSheets/colonial song.pdf"))
        r = l.scan.run()
        say("case rename: moved=${r.moved} added=${r.added} removed=${r.removed}")
        T1.touch(File(root, "MobileSheets/Empty One.pdf"), "")
        File(root, "MobileSheets/Junk One.pdf").writeBytes(ByteArray(2000) { (it * 7).toByte() })
        r = l.scan.run()
        say("empty and junk files: added=${r.added}")
        val long = "L".repeat(200) + " - Trombone 1.pdf"
        T1.touch(File(root, "MobileSheets/Dvořák Syméphonie – Trombone 1.pdf"))
        runCatching { T1.touch(File(root, "MobileSheets/$long")) }.onFailure { say("cannot create 217-char name: ${it.message}") }
        T1.touch(File(root, "MobileSheets/ブラス バンド - Euphonium.pdf"))
        r = l.scan.run()
        say("unicode/long: added=${r.added.map { it.take(70) }}")
        r = l.scan.run()
        say("final no-op scan changed=${r.changed} ${r.added} ${r.merged} ${r.sorted}")
        say("songs before=$before after=${l.library.songs.size}")
    }

    @Test
    fun `r3 mass deletion and unmounted folder`() {
        val root = T1.copyLib("r3mass")
        val l = T1.Lib(root)
        l.scan.run(); l.scan.run()
        val songs0 = l.library.songs.size
        File(root, "Imported").deleteRecursively()
        var r = l.scan.run()
        say("deleted Imported/: removed=${r.removed.size} heldBack=${r.heldBack} songs $songs0 -> ${l.library.songs.size}")
        val l2 = T1.Lib(T1.copyLib("r3empty"))
        l2.scan.run()
        l2.root.listFiles()!!.filter { it.name != ".inksheets" }.forEach { it.deleteRecursively() }
        r = l2.scan.run()
        say("emptied folder: removed=${r.removed.size} heldBack=${r.heldBack} songs now=${l2.library.songs.size}")
        val l3 = T1.Lib(T1.copyLib("r3ten"))
        l3.scan.run()
        l3.library.songs.flatMap { it.parts }.take(10).forEach { File(l3.root, it.file).delete() }
        r = l3.scan.run()
        say("deleted 10 files: removed=${r.removed.size} heldBack=${r.heldBack}")
    }

    @Test
    fun `r3 trash remove and restore`() {
        val root = T1.copyLib("r3trash")
        val l = T1.Lib(root)
        l.scan.run(); l.scan.run()
        val trash = LibraryTrash(root, l.library)
        val song = l.library.songs.first { it.title.contains("Call Me") }
        val files = song.parts.map { it.file }
        say("removing ${song.title} parts=$files")
        trash.remove(song)
        say("after remove: listed=${l.titles().contains(song.title)} files still on disk=${files.filter { File(root, it).isFile }}")
        var r = l.scan.run()
        say("scan after remove: added=${r.added} removed=${r.removed}")
        say("entries: ${trash.entries().map { it.title }}")
        val ok = trash.restore(trash.entries().first())
        say("restore ok=$ok files back=${files.filter { File(root, it).isFile }} song back=${l.titles().contains(song.title)} parts=${l.library.song(song.id)?.parts?.size}")
        r = l.scan.run()
        say("scan after restore: added=${r.added} removed=${r.removed} merged=${r.merged}")
        val song2 = l.library.songs.first { it.title.contains("Blinding") }
        val f0 = song2.parts.first().file
        trash.remove(song2)
        File(root, f0).parentFile.mkdirs()
        File(root, f0).writeText("A DIFFERENT NEWER FILE")
        val entry = trash.entries().first { it.songId == song2.id }
        val ok2 = trash.restore(entry)
        say("restore with name clash: ok=$ok2 trash folder still exists=${File(root, ".inksheets/trash/${entry.folder}").exists()} file on disk now='${File(root, f0).readText().take(30)}'")
        say("   -> copies of the trashed original left anywhere: ${File(root, ".inksheets/trash").walkTopDown().filter { it.isFile && it.name == File(f0).name }.toList().size}")
        val s = l.library.songs.first { it.title.contains("Bye Bye Bye") }
        val p1 = s.parts[0]; val p2 = s.parts[1]
        trash.removePart(s, p1); trash.removePart(l.library.song(s.id)!!, p2)
        say("after 2 part removals: song=${l.library.song(s.id)?.title} parts=${l.library.song(s.id)?.parts?.size} trash entries=${trash.entries().map { it.title }}")
        r = l.scan.run()
        say("scan after part removals: added=${r.added} removed=${r.removed} song=${l.library.song(s.id)?.title}")
        val n = trash.purge(System.currentTimeMillis() + 31L * 86_400_000)
        say("purge after 31 days removed $n entries; trash left=${trash.entries().size}")
    }

    @Test
    fun `r1 r3 merge split and move parts survive a rescan`() {
        val root = T1.copyLib("r3merge")
        val l = T1.Lib(root)
        l.scan.run(); l.scan.run()
        val lib = l.library
        val chester = lib.songs.first { it.title == "Chester" }
        val belgian = lib.songs.first { it.title.startsWith("Belgian") }
        say("merge '${belgian.title}' into '${chester.title}'")
        lib.mergeSongs(belgian.id, chester.id)
        var r = l.scan.run()
        say("after merge+scan: songs=${lib.songs.size} Belgian exists=${lib.songs.any { it.title.startsWith("Belgian") }} chester parts=${lib.song(chester.id)?.parts?.map { it.file }} scan added=${r.added} merged=${r.merged} sorted=${r.sorted}")
        val part = lib.song(chester.id)!!.parts.first { it.file.contains("Belgian") }
        val newId = lib.movePart(part.id, null, "Belgian Parachutists")
        r = l.scan.run()
        say("split back: new=$newId songs named Belgian=${lib.songs.filter { it.title.startsWith("Belgian") }.map { it.id.take(6) to it.parts.size }} scan merged=${r.merged} sorted=${r.sorted}")
        r = l.scan.run()
        say("rescan after split: merged=${r.merged} sorted=${r.sorted}")
        val a = lib.songs.first { it.title.startsWith("24K") }
        val b = lib.songs.first { it.title.startsWith("99") }
        val pp = b.parts.first()
        lib.movePart(pp.id, a.id)
        r = l.scan.run()
        say("moved part ${pp.file} 99->24K; after scan it is in: ${lib.songs.filter { s -> s.parts.any { it.id == pp.id } }.map { it.title }} sorted=${r.sorted} merged=${r.merged}")
        lib.mergeSongs(chester.id, chester.id)
        lib.mergeSongs(chester.id, "no-such")
        say("self/ghost merge ok; chester exists=${lib.song(chester.id) != null}")
        val x = lib.songs.first { it.title == "Arbans 1" }; val y = lib.songs.first { it.title == "Arbans 2" }
        lib.mergeSongs(x.id, y.id); lib.mergeSongs(x.id, y.id); lib.mergeSongs(y.id, x.id)
        say("A->B, A->B again, B->A: arbans left=${lib.songs.filter { it.title.startsWith("Arbans") }.map { it.title to it.parts.size }}")
    }
}
