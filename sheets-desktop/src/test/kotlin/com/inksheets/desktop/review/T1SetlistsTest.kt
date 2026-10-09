package com.inksheets.desktop.review

import com.inksheets.core.BulkImport
import com.inksheets.core.Library
import com.inksheets.core.LibraryLog
import com.inksheets.core.MobileSheetsImport
import com.inksheets.core.PartChoice
import com.inksheets.core.SetlistBundle
import com.inksheets.desktop.DesktopSheetsPlatform
import com.inksheets.desktop.review.T1.say
import org.junit.Test
import java.io.File

/** T1: setlists and folders, two devices, bundles, bulk and MobileSheets imports, 300-song cost. */
class T1SetlistsTest {

    @Test
    fun `r1 r3 setlists folders nesting merge reorder`() {
        val root = T1.copyLib("sl1")
        val l = T1.Lib(root); l.scan.run()
        val lib = l.library
        val a = lib.addFolder("Pep Band"); val b = lib.addFolder("2026", a.id); val c = lib.addFolder("Fall", b.id)
        val s1 = lib.addSetlist("Game 1", c.id); val s2 = lib.addSetlist("Game 2", c.id); val s3 = lib.addSetlist("Loose")
        val songs = lib.songs.take(6)
        songs.forEach { lib.addToSetlist(s1.id, it.id) }
        lib.addToSetlist(s1.id, songs[0].id)          // the same song twice
        say("setlist with a song twice: entries=${lib.setlist(s1.id)!!.entries.size} distinct=${lib.setlist(s1.id)!!.entries.map { it.songId }.distinct().size}")
        songs.take(3).forEach { lib.addToSetlist(s2.id, it.id) }
        // moveFolder into own child; into itself
        say("move Pep Band into Fall (its own grandchild) allowed=${lib.moveFolder(a.id, c.id)}; into itself=${lib.moveFolder(a.id, a.id)}")
        // reorder
        val e = lib.setlist(s1.id)!!.entries
        lib.moveInSetlist(s1.id, e.last().id, 0)
        lib.moveInSetlist(s1.id, e.first().id, 999)
        lib.moveInSetlist(s1.id, "nope", 2)
        say("after moves: ${lib.setlist(s1.id)!!.entries.map { lib.song(it.songId)?.title }}")
        // merge setlists
        lib.mergeSetlists(listOf(s2.id, s3.id, s1.id), s1.id)
        say("merge s2,s3,s1 into s1: s2 alive=${lib.setlist(s2.id) != null} s3 alive=${lib.setlist(s3.id) != null} entries=${lib.setlist(s1.id)!!.entries.size}")
        // delete folders
        lib.deleteFolder(b.id)
        say("delete middle folder: Fall parent=${lib.folders.first { it.id == c.id }.parentId == a.id} setlist still in Fall=${lib.setlistsIn(c.id).map { it.name }}")
        // delete a song that is in a setlist; the setlist keeps its entry (hidden)?
        val victim = songs[1]
        lib.deleteSong(victim.id)
        say("deleted song in setlist: setlist entries=${lib.setlist(s1.id)!!.entries.size} songs present=${lib.setlist(s1.id)!!.entries.count { lib.song(it.songId) != null }}")
        // folder name edge cases
        val f1 = lib.addFolder(""); val f2 = lib.addFolder("   "); val f3 = lib.addFolder("Pep Band")
        say("blank folder names allowed: '${f1.name}' '${f2.name}' duplicate name allowed: ${lib.foldersIn(null).count { it.name == "Pep Band" }}")
        val bigName = lib.addSetlist("X".repeat(500))
        say("500-char setlist name stored: ${lib.setlist(bigName.id)!!.name.length}")
    }

    @Test
    fun `r3 two devices edit the same setlist`() {
        val root = T1.copyLib("two")
        val l = T1.Lib(root, "laptop"); l.scan.run()
        val set = l.library.addSetlist("Shared")
        val s = l.library.songs
        l.library.addToSetlist(set.id, s[0].id)
        val tablet = T1.Lib(root, "tablet")
        tablet.library.refresh()
        say("tablet sees set with ${tablet.library.setlist(set.id)?.entries?.size} entries")
        // Now both add a song while apart (no refresh between)
        l.library.addToSetlist(set.id, s[1].id)
        tablet.library.addToSetlist(set.id, s[2].id)
        l.library.refresh(); tablet.library.refresh()
        say("after both add one: laptop sees ${l.library.setlist(set.id)!!.entries.map { l.library.song(it.songId)?.title }}; tablet sees ${tablet.library.setlist(set.id)!!.entries.map { tablet.library.song(it.songId)?.title }}")
        // Both remove / edit bookmarks of one song
        val song = s[3]
        l.library.editSong(song.id) { bookmarks = listOf(com.inksheets.core.Bookmark("A", null, 1)) }
        tablet.library.editSong(song.id) { bookmarks = listOf(com.inksheets.core.Bookmark("B", null, 2)) }
        l.library.refresh(); tablet.library.refresh()
        say("bookmarks added on two devices: laptop ${l.library.song(song.id)!!.bookmarks.map { it.label }} tablet ${tablet.library.song(song.id)!!.bookmarks.map { it.label }}")
        // One device deletes a setlist while the other edits it
        l.library.deleteSetlist(set.id)
        tablet.library.addToSetlist(set.id, s[4].id)
        l.library.refresh(); tablet.library.refresh()
        say("delete vs edit: laptop sees setlist=${l.library.setlist(set.id) != null} tablet sees=${tablet.library.setlist(set.id) != null}")
        // song renamed on both
        l.library.editSong(s[5].id) { title = "Renamed on laptop" }
        tablet.library.editSong(s[5].id) { title = "Renamed on tablet" }
        l.library.refresh(); tablet.library.refresh()
        say("rename on both: laptop '${l.library.song(s[5].id)!!.title}' tablet '${tablet.library.song(s[5].id)!!.title}'")
        // Both scan the same new file independently
        File(root, "MobileSheets/Chester.pdf").copyTo(File(root, "MobileSheets/Fresh Tune - Trombone 1.pdf")); File(root, "MobileSheets/Fresh Tune - Trombone 1.pdf").appendBytes(ByteArray(777) { 3 })
        l.scan.run()
        val tabletScan = com.inksheets.core.LibraryScan(root, tablet.library, File(root.parentFile, "two-tablet-memory.json"))
        tabletScan.run()
        l.library.refresh(); tablet.library.refresh()
        say("both scan a new file: songs named Fresh: laptop ${l.library.songs.count { it.title.startsWith("Fresh") }} tablet ${tablet.library.songs.count { it.title.startsWith("Fresh") }}")
    }

    @Test
    fun `r1 r3 setlist bundle export import`() {
        val root = T1.copyLib("bundle")
        val l = T1.Lib(root); l.scan.run()
        val lib = l.library
        val set = lib.addSetlist("Spring Pops")
        lib.songs.take(5).forEach { lib.addToSetlist(set.id, it.id) }
        val out = File(root.parentFile, "bundle.zip")
        out.delete()
        var n = 0
        T1.timed("export bundle") { n = SetlistBundle.export(lib, root, set.id, out) }
        say("bundle exported songs=$n size=${out.length() / 1024} KB")
        val root2 = File("build/t1work/bundle-dest").apply { deleteRecursively(); mkdirs() }
        val dest = T1.Lib(root2, "friend")
        T1.timed("import bundle") { val imp = SetlistBundle.import(dest.library, root2, out); say("imported ${imp.setlist.name} added=${imp.songsAdded} matched=${imp.songsMatched}") }
        say("dest songs=${dest.titles()} files=${root2.walkTopDown().filter { it.isFile && !it.path.contains(".inksheets") }.map { it.relativeTo(root2).path }.toList()}")
        val again = SetlistBundle.import(dest.library, root2, out)
        say("import same bundle again: added=${again.songsAdded} matched=${again.songsMatched} setlists named Spring Pops=${dest.library.setlists.count { it.name == "Spring Pops" }}")
        // corrupt bundle
        val bad = File(root.parentFile, "bad.zip"); bad.writeBytes(ByteArray(500) { it.toByte() })
        say("corrupt bundle: " + runCatching { SetlistBundle.import(dest.library, root2, bad) }.fold({ "ok?! $it" }, { "threw ${it.javaClass.simpleName}: ${it.message}" }))
        val half = File(root.parentFile, "half.zip"); half.writeBytes(out.readBytes().copyOf((out.length() / 2).toInt()))
        say("truncated bundle: " + runCatching { SetlistBundle.import(dest.library, root2, half) }.fold({ "ok: songs ${it.songsAdded}" }, { "threw ${it.javaClass.simpleName}: ${it.message}" }))
    }

    @Test
    fun `r1 bulk import of a folder tree`() {
        val root = T1.copyLib("bulk")
        val l = T1.Lib(root)
        val src = File("../build/t1lib/Imported/PEP BAND")
        val source = BulkImport.FolderSource(src)
        var plan: BulkImport.Plan? = null
        T1.timed("bulk plan of ${source.list().size} files") { plan = BulkImport.plan("PEP BAND", source.list()) }
        say("plan songs=${plan!!.songs.size} setlists=${plan!!.setlists.size}: ${plan!!.songs.map { it.title + "/" + it.parts.size }}")
        val dest = File("build/t1work/bulk-dest").apply { deleteRecursively(); mkdirs() }
        val l2 = T1.Lib(dest)
        T1.timed("bulk apply") { val r = BulkImport.apply(plan!!, source, l2.library, dest, true); say("result $r") }
        // apply again: duplicates?
        val r2 = BulkImport.apply(plan!!, source, l2.library, dest, true)
        say("apply again: $r2 songs=${l2.library.songs.size} setlists=${l2.library.setlists.size}")
        // scan afterwards: does the scan agree with the import?
        val rep = l2.scan.run()
        say("scan after import: added=${rep.added.size} merged=${rep.merged} sorted=${rep.sorted} moved=${rep.moved.size} songs=${l2.library.songs.size}")
        // A different download with the same name and same-named files but different content
        val other = File("build/t1work/bulk-other/PEP BAND/Music/24K Magic").apply { mkdirs() }
        File(other, "24K Magic - Trombone 1.pdf").writeText("DIFFERENT CONTENT")
        val src2 = BulkImport.FolderSource(File("build/t1work/bulk-other/PEP BAND"))
        val p2 = BulkImport.plan("PEP BAND", src2.list())
        BulkImport.apply(p2, src2, l2.library, dest, true)
        val got = File(dest, "Imported/PEP BAND/Music/24K Magic/24K Magic - Trombone 1.pdf")
        say("second download with same name: file on disk still the first one's? length=${got.length()} (different content would be 17)")
    }

    @Test
    fun `r1 mobilesheets import from the exposed db`() {
        val root = T1.copyLib("ms")
        val dbSrc = File("C:/Users/willi/Music/Sheet Music/InkSheets/.inksheets/mobilesheets-MobileSheets_2026-09-25.db")
        org.junit.Assume.assumeTrue(dbSrc.isFile)
        val db = File(root, "MobileSheets/mobilesheets.db"); dbSrc.copyTo(db)
        val l = T1.Lib(root)
        val tables = DesktopSheetsPlatform {}.openMobileSheets(db)!!
        val msFolder = File(root, "MobileSheets")
        val byName = msFolder.walkTopDown().filter { it.isFile }.groupBy { it.name.lowercase() }
        val resolve = { p: String -> MobileSheetsImport.locate(p, msFolder, byName)?.relativeTo(root)?.invariantSeparatorsPath }
        lateinit var planned: MobileSheetsImport.Planned
        T1.timed("MS plan") { planned = MobileSheetsImport.plan(tables, l.library, resolve) }
        say("planned groups=${planned.groups.size} members=${planned.groups.sumOf { it.members.size }} skipped=${planned.skipped}")
        planned.groups.take(8).forEach { g -> say("  group '${g.title}' ${g.members.map { it.title + ":" + it.parts.size }} into=${g.into}") }
        lateinit var res: MobileSheetsImport.Result
        T1.timed("MS apply") { res = MobileSheetsImport.apply(tables, l.library, resolve, planned, planned.groups) }
        say("result songs=${res.songs} setlists=${res.setlists} skipped=${res.skipped} missing=${res.missing.size} e.g. ${res.missing.take(3)}")
        say("library songs=${l.library.songs.size} setlists=${l.library.setlists.map { it.name + ":" + it.entries.size }.take(12)} folders=${l.library.folders.map { it.name }}")
        val rep = l.scan.run()
        say("scan after MS import: added=${rep.added.size} merged=${rep.merged.size} sorted=${rep.sorted.size} removed=${rep.removed.size} songs=${l.library.songs.size}")
        // running again only adds what is new
        val p2 = MobileSheetsImport.plan(tables, l.library, resolve)
        val r2 = MobileSheetsImport.apply(tables, l.library, resolve, p2, p2.groups)
        p2.groups.forEach { g -> say("  again-group '${g.title}' ${g.members.map { it.title }} into=${g.into}") }
        say("MS import again: groups=${p2.groups.size} songs=${r2.songs} setlists=${r2.setlists}; library songs=${l.library.songs.size} setlists=${l.library.setlists.size}")
    }

    @Test
    fun `r2 three hundred songs`() {
        val dir = File("build/t1work/big").apply { deleteRecursively(); mkdirs() }
        val rnd = java.util.Random(7)
        val words = listOf("Star", "Liberty", "Bell", "Night", "Dance", "Blue", "Gold", "Fire", "River", "Moon", "Stars", "March", "Rhapsody", "Hymn", "Jazz", "Funk", "Brass", "Wind", "Glory", "Suite")
        val inst = listOf("Trombone 1", "Trombone 2", "Euphonium", "Electric Bass", "Tuba", "Trumpet 1", "Flute 1", "Clarinet 1", "Alto Sax 1", "Score")
        var serial = 0
        val titles = LinkedHashSet<String>()
        while (titles.size < 300) titles += listOf(words[rnd.nextInt(20)], words[rnd.nextInt(20)], words[rnd.nextInt(20)]).distinct().joinToString(" ")
        titles.forEachIndexed { i, t ->
            val folder = if (i % 3 == 0) "MobileSheets" else if (i % 3 == 1) "Imported/Pack ${i / 30}/$t" else "Scans"
            val n = if (i % 3 == 2) 1 else 2 + rnd.nextInt(5)
            for (k in 0 until n) {
                val name = if (i % 3 == 2) "$t.pdf" else "$t - ${inst[(k + i) % 10]}.pdf"
                val f = File(dir, "$folder/$name"); f.parentFile.mkdirs(); T1.pool.copyTo(f); f.appendBytes(ByteArray(1 + (serial++)) { (it * 31 + serial).toByte() })
            }
        }
        val files = dir.walkTopDown().count { it.isFile }
        val l = T1.Lib(dir)
        T1.timed("first scan of $files files / 300 songs") { l.scan.run() }
        say("songs=${l.library.songs.size} parts=${l.parts()}")
        T1.timed("second scan (no-op)") { l.scan.run() }
        T1.timed("third scan (no-op)") { l.scan.run() }
        File(dir, "MobileSheets/new one - Tuba.pdf").also { T1.pool.copyTo(it); it.appendBytes(ByteArray(5000) { b -> b.toByte() }) }
        T1.timed("scan with one new file") { l.scan.run() }
        T1.timed("songs x100") { repeat(100) { l.library.songs } }
        val prof = l.library.profiles().first { it.id == "baritone" || it.name.contains("Euph", true) }
        T1.timed("songsFor(profile) x20") { repeat(20) { PartChoice.songsFor(l.library.songs, prof) } }
        // a keystroke of search in the song list
        val all = l.library.songs
        T1.timed("search filter 'str' x100") { repeat(100) { all.filter { s -> (listOf(s.title) + s.composers + s.arrangers + s.artists + s.genres + s.tags).any { it.lowercase().contains("str") } } } }
        // edit one song: version bump then songs rebuild
        T1.timed("edit a song then read songs (rebuild)") { l.library.editSong(all[5].id) { notes = "x" }; l.library.songs }
        T1.timed("addToSetlist x50") { val s = l.library.addSetlist("big"); all.take(50).forEach { l.library.addToSetlist(s.id, it.id) } }
        val big = l.library.addSetlist("everything")
        T1.timed("set with 300 entries built via editSetlist") { l.library.editSetlist(big.id) { entries = all.map { com.inksheets.core.SetlistEntry(songId = it.id) } } }
        T1.timed("setlist(id) x100") { repeat(100) { l.library.setlist(big.id) } }
        say("log size: ${File(dir, ".inksheets/log").walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1024} KB")
        // Open the library from cold: log read
        T1.timed("cold open library (read log)") { val x = Library(LibraryLog(dir, "t1-laptop")); x.songs }
    }
}
