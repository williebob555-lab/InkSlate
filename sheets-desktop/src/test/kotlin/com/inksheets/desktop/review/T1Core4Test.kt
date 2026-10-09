package com.inksheets.desktop.review

import com.inksheets.core.BulkImport
import com.inksheets.core.LibraryScan
import com.inksheets.core.LibraryTrash
import com.inksheets.core.SetlistBundle
import com.inksheets.desktop.review.T1.say
import org.junit.Test
import java.io.File
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** T1 round 4: break it. */
class T1Core4Test {

    @Test
    fun `r4 two scanners and edits at once`() {
        val root = T1.copyLib("c4-race")
        val l = T1.Lib(root); l.scan.run(); l.scan.run()
        val errors = java.util.Collections.synchronizedList(ArrayList<String>())
        val stop = AtomicBoolean(false)
        fun scanner(name: String) = Thread {
            // a new LibraryScan each time, as SheetsState.scanner() does: @Synchronized is per instance
            val mem = File(root.parentFile, "c4-race-mem.json")
            while (!stop.get()) runCatching { LibraryScan(root, l.library, mem).run() }.onFailure { errors += "$name: $it" }
        }.apply { isDaemon = true }
        val a = scanner("A"); val b = scanner("B"); a.start(); b.start()
        val trash = LibraryTrash(root, l.library)
        var removed = 0
        for (i in 0 until 12) {
            File(root, "MobileSheets/Race $i - Trombone 1.pdf").also { File(root, "MobileSheets/Chester.pdf").copyTo(it); it.appendBytes(ByteArray(100 + i) { 7 }) }
            Thread.sleep(150)
            l.library.songs.firstOrNull { it.title == "Race $i" }?.let { runCatching { trash.remove(it); removed++ }.onFailure { e -> errors += "remove: $e" } }
            File(root, "MobileSheets/Race $i - Trombone 1.pdf").delete()
        }
        stop.set(true); a.join(3000); b.join(3000)
        val final = l.scan.run()
        say("race: errors=${errors.take(5)} (${errors.size}); removed=$removed; songs named Race*=${l.library.songs.filter { it.title.startsWith("Race") }.map { it.title }}; trash=${trash.entries().size}; final scan changed=${final.changed}")
        val dupParts = l.library.songs.flatMap { it.parts }.groupBy { it.file }.filter { it.value.size > 1 }.keys
        say("race: duplicate part records for one file: $dupParts")
    }

    @Test
    fun `r4 killed between moving files and writing the trash manifest`() {
        val root = T1.copyLib("c4-kill")
        val l = T1.Lib(root); l.scan.run(); l.scan.run()
        val trash = LibraryTrash(root, l.library)
        val song = l.library.songs.first { it.title == "Call Me" }
        // what remove() does, stopped before it writes removed.json
        val folder = File(root, ".inksheets/trash/20261008-120000 Call Me").apply { mkdirs() }
        song.parts.forEach { p -> val to = File(folder, p.file); to.parentFile.mkdirs(); File(root, p.file).renameTo(to) }
        val r = l.scan.run()
        say("kill mid-remove: scan removed=${r.removed}; song listed=${l.titles().contains("Call Me")}; trash entries visible=${trash.entries().size}; files in orphan folder=${folder.walkTopDown().count { it.isFile }}")
        val purged = trash.purge(System.currentTimeMillis() + 90L * 86_400_000)
        say("kill mid-remove: purge after 90 days removed=$purged; orphan folder still there=${folder.exists()}")
    }

    @Test
    fun `r4 unicode normalisation and renaming a song then more parts arrive`() {
        val root = T1.copyLib("c4-uni")
        val l = T1.Lib(root); l.scan.run(); l.scan.run()
        val nfc = Normalizer.normalize("Café del Mar - Trombone 1.pdf", Normalizer.Form.NFC)
        val nfd = Normalizer.normalize("Café del Mar - Euphonium.pdf", Normalizer.Form.NFD)
        File(root, "MobileSheets/Chester.pdf").copyTo(File(root, "MobileSheets/$nfc")); File(root, "MobileSheets/$nfc").appendBytes(ByteArray(50) { 1 })
        File(root, "MobileSheets/Chester.pdf").copyTo(File(root, "MobileSheets/$nfd")); File(root, "MobileSheets/$nfd").appendBytes(ByteArray(60) { 2 })
        val rn = l.scan.run()
        say("NFC + NFD file names of one song: scan added=${rn.added.map { it.take(60) }}; songs: ${l.library.songs.filter { it.title.contains("Caf") || it.title.contains("del Mar") }.map { it.title.length to it.parts.size }}")
        // A hand-made rename with nothing else changing
        run {
            val b = l.library.songs.first { it.title == "Call Me" }
            l.library.editSong(b.id) { title = "Call Me Maybe (my own title)" }
            val r0 = l.scan.run()
            say("hand rename, plain rescan: title now='${l.library.song(b.id)?.title}' sorted=${r0.sorted}")
            File(root, "Scans/Unrelated Tune.pdf").also { File(root, "MobileSheets/Chester.pdf").copyTo(it); it.appendBytes(ByteArray(31) { 8 }) }
            val r1 = l.scan.run()
            say("hand rename, then an UNRELATED file arrives (sort runs): title now='${l.library.song(b.id)?.title}' sorted=${r1.sorted} merged=${r1.merged}")
        }
        // user renames a song, then a new part with the old name arrives
        val chester = l.library.songs.first { it.title == "Blinding Lights" }
        l.library.editSong(chester.id) { title = "Blinding Lights (pep band)" }
        File(root, "MobileSheets/Blinding Lights - Tuba.pdf").also { File(root, "MobileSheets/Blinding Lights - Trombone 1.pdf").copyTo(it); it.appendBytes(ByteArray(77) { 4 }) }
        val r = l.scan.run()
        say("renamed 'Blinding Lights' by hand, then 'Blinding Lights - Tuba.pdf' arrives: added=${r.added} songs=${l.library.songs.filter { it.title.startsWith("Blinding") }.map { it.title + "/" + it.parts.size }}")
        l.scan.run()
        say("after another scan: ${l.library.songs.filter { it.title.startsWith("Blinding") }.map { it.title + "/" + it.parts.size }}")
    }

    @Test
    fun `r4 hostile zips`() {
        val root = T1.copyLib("c4-zip")
        val l = T1.Lib(root)
        val outside = File("build/t1work/c4-zip-outside").apply { deleteRecursively(); mkdirs() }
        val canary = File(root.parentFile, "evil.pdf")
        val zip = File(outside, "evil.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            for (n in listOf("../../evil.pdf", "..\\..\\evil2.pdf", "/abs/evil3.pdf", "Pack/../../evil4.pdf", "Pack/ok - Trombone 1.pdf", "CON.pdf", "Pack/aux.pdf", "Pack/trailingdot. .pdf")) {
                z.putNextEntry(ZipEntry(n)); z.write("%PDF-1.4 fake".toByteArray()); z.closeEntry()
            }
        }
        val src = BulkImport.ZipSource(zip)
        say("zip names offered by ZipSource: ${src.list()}")
        val plan = BulkImport.plan("evil", src.list())
        BulkImport.apply(plan, src, l.library, root, true)
        say("zip slip: files outside the library created: ${root.parentFile.listFiles()!!.filter { it.name.startsWith("evil") }.map { it.name }} ; ${File(root, "..").canonicalFile.walkTopDown().maxDepth(1).filter { it.name.startsWith("evil") }.toList().size}")
        // bundle with a hostile manifest
        val bundle = File(outside, "evil.inkset.zip")
        ZipOutputStream(bundle.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("setlist.json"))
            z.write("""{"name":"..","songs":[{"title":"Evil","parts":[{"file":"../../evil5.pdf"},{"file":"..\\..\\evil6.pdf"}]}]}""".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("../../evil5.pdf")); z.write("x".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("..\\..\\evil6.pdf")); z.write("x".toByteArray()); z.closeEntry()
        }
        val imp = runCatching { SetlistBundle.import(l.library, root, bundle) }
        say("hostile bundle: ${imp.fold({ "imported ${it.setlist.name}" }, { "threw $it" })}; stray files near root: ${root.parentFile.listFiles()!!.filter { it.name.contains("evil") }.map { it.name }}; in root: ${root.walkTopDown().filter { it.name.contains("evil") }.map { it.relativeTo(root).path }.toList()}")
        canary.delete()
    }

    @Test
    fun `r4 re-importing a bundle overwrites files the person has marked`() {
        val root = T1.copyLib("c4-bundle")
        val l = T1.Lib(root); l.scan.run()
        val set = l.library.addSetlist("Spring")
        l.library.songs.filter { it.title == "Chester" || it.title == "Chesapeake" }.forEach { l.library.addToSetlist(set.id, it.id) }
        val out = File(root.parentFile, "c4-b.zip"); out.delete()
        SetlistBundle.export(l.library, root, set.id, out)
        val dest = File("build/t1work/c4-bundle-dest").apply { deleteRecursively(); mkdirs() }
        val d = T1.Lib(dest, "friend")
        SetlistBundle.import(d.library, dest, out)
        val f = dest.walkTopDown().first { it.isFile && it.name.endsWith(".pdf") }
        val marked = f.readBytes() + "ANNOTATED BY ME".toByteArray()
        f.writeBytes(marked)
        SetlistBundle.import(d.library, dest, out)
        say("bundle imported again after the friend marked a file: marks survive=${f.readBytes().size == marked.size}; setlists named Spring=${d.library.setlists.count { it.name == "Spring" }}")
        // the two zips with same-named parts in different folders
        val two = File(root.parentFile, "c4-two.zip")
        ZipOutputStream(two.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("A/Tune - Trombone 1.pdf")); z.write("AAAA".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("B/Tune - Trombone 1.pdf")); z.write("BBBBBBB".toByteArray()); z.closeEntry()
        }
        val d2 = T1.Lib(File("build/t1work/c4-bundle-dest2").apply { deleteRecursively(); mkdirs() }, "friend2")
        SetlistBundle.import(d2.library, d2.root, two)
        say("a zip with two same-named parts in A/ and B/ as a bundle: songs=${d2.library.songs.map { it.title + "/" + it.parts.size }}; files=${d2.root.walkTopDown().filter { it.isFile && it.extension == "pdf" }.map { it.name + ":" + it.length() }.toList()}")
    }
}
