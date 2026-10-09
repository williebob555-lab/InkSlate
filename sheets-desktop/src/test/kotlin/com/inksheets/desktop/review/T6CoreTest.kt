package com.inksheets.desktop.review

import com.inksheets.core.LibraryScan
import com.inksheets.core.LibraryTrash
import com.inksheets.core.SetlistBundle
import com.inksheets.ui.SheetsState
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** T6: library-level regression checks (no screen): re-sent bundle, case-only rename, fresh-install defaults. */
class T6CoreTest {
    private fun say(t: String) = T3.say("t6-core.txt", t)
    private fun ok(name: String, cond: Boolean, detail: String = "") = say((if (cond) "PASS " else "FAIL ") + name + (if (detail.isNotEmpty()) " - $detail" else ""))

    @Test
    fun `a bundle sent again keeps the marked copy in Recently deleted`() {
        assumeTrue(T1.master.isDirectory)
        val root = T1.copyLib("t6-bundle")
        val l = T1.Lib(root); l.scan.run()
        val set = l.library.addSetlist("Spring")
        l.library.songs.filter { it.title == "Chester" || it.title == "Chesapeake" }.forEach { l.library.addToSetlist(set.id, it.id) }
        val out = File(root.parentFile, "t6-b.zip"); out.delete()
        SetlistBundle.export(l.library, root, set.id, out)
        val dest = File("build/t1work/t6-bundle-dest").apply { deleteRecursively(); mkdirs() }
        val d = T1.Lib(dest, "friend")
        SetlistBundle.import(d.library, dest, out)
        val f = dest.walkTopDown().first { it.isFile && it.name.endsWith(".pdf") && !it.path.contains(".inksheets") }
        val marked = f.readBytes() + "ANNOTATED BY ME".toByteArray()
        f.writeBytes(marked)
        val again = SetlistBundle.import(d.library, dest, out)
        val trash = LibraryTrash(dest, d.library).entries()
        val kept = trash.flatMap { e -> e.files.map { File(File(dest, ".inksheets/trash/${e.folder}"), it.substringAfterLast('/')) } }
        val markedKept = trash.any { e -> e.files.isNotEmpty() && File(dest, ".inksheets/trash/${e.folder}").walkTopDown().any { it.isFile && it.length() == marked.size.toLong() } }
        ok("re-sent bundle: the marked copy is in Recently deleted", markedKept, "trash entries ${trash.map { it.title + " files=" + it.files.size }}")
        ok("re-sent bundle: the file in place is the sent one (not the marked)", f.length() != marked.size.toLong(), "size ${f.length()} vs marked ${marked.size}")
        ok("re-sent bundle: still one Spring setlist", d.library.setlists.count { it.name == "Spring" } == 1, "${d.library.setlists.map { it.name }} (matched ${again.songsMatched})")
        // The same bundle a third time with no marks: nothing more is thrown away.
        val n = LibraryTrash(dest, d.library).entries().size
        SetlistBundle.import(d.library, dest, out)
        ok("re-sent a third time unmarked: trash did not grow", LibraryTrash(dest, d.library).entries().size == n, "$n -> ${LibraryTrash(dest, d.library).entries().size}")
    }

    @Test
    fun `a case-only rename keeps its song`() {
        assumeTrue(T1.master.isDirectory)
        val root = T1.copyLib("t6-case")
        val l = T1.Lib(root); l.scan.run(); l.scan.run()
        val before = l.library.songs.first { it.title == "Colonial Song" }
        val from = File(root, "MobileSheets/Colonial Song.pdf").toPath()
        val tmp = File(root, "MobileSheets/zz-tmp.pdf").toPath()
        java.nio.file.Files.move(from, tmp)
        java.nio.file.Files.move(tmp, File(root, "MobileSheets/colonial song.pdf").toPath())
        val r = l.scan.run()
        val after = l.library.songs.filter { it.title.equals("Colonial Song", true) }
        ok("case-only rename: exactly one song of that name", after.size == 1, after.map { it.title + ":" + it.parts.map { p -> p.file } }.toString() + " scan ${r.added}/${r.removed}/${r.moved}")
        ok("case-only rename: same song id (setlists and notes follow)", after.firstOrNull()?.id == before.id, "${before.id} -> ${after.firstOrNull()?.id}")
        ok("case-only rename: nothing missing", l.scan.missing().isEmpty(), l.scan.missing().map { it.second.file }.toString())
        l.scan.run()
        ok("case-only rename: second scan changes nothing", l.library.songs.count { it.title.equals("Colonial Song", true) } == 1)
        // Trash does not hold a ghost of it.
        ok("case-only rename: not in Recently deleted", LibraryTrash(root, l.library).entries().none { it.title.equals("Colonial Song", true) })
    }

    @Test
    fun `the strip starts folded on a fresh install, and the page colour is remembered`() {
        val root = File("build/t6-fresh").apply { deleteRecursively(); mkdirs() }
        val s = SheetsState(T3Platform(root))
        ok("fresh install: the strip starts folded", s.stripCollapsed)
        s.stripCollapsed = false
        val s2 = SheetsState(T3Platform(root).also { p -> p.prefs.putAll((s.platform as T3Platform).prefs) })
        ok("a second start keeps the strip as left", !s2.stripCollapsed)
    }
}
