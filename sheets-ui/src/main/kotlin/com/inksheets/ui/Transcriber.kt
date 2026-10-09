package com.inksheets.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.inksheets.core.omr.Ink
import com.inksheets.core.omr.Learned
import com.inksheets.core.omr.Measure
import com.inksheets.core.omr.Net
import com.inksheets.core.omr.Recognizer
import com.inksheets.core.omr.Score
import com.inksheets.core.omr.Scores
import com.inksheets.core.omr.Workers
import java.io.File

/**
 * Reading a part's notes off its pages (experimental): each page drawn so a staff space is
 * about 18 pixels - where the reader does best - read, and the whole kept on this device, so a
 * part is read once and not every time it is wanted.
 */
internal object Transcriber {
    /** What it is doing: "Reading page 2 of 4..." - null when idle. */
    var busy by mutableStateOf<String?>(null)

    /** How far the reading in hand has got, 0 to 1 (null: none going on) - for the bar at the screen's side. */
    var progress by mutableStateOf<Float?>(null)
        private set

    /** The reading in hand: pages done of how many, when the page being read began, and how long a page takes here. */
    @Volatile var pagesDone = 0
    @Volatile var pagesToDo = 0
    @Volatile var pageBegan = 0L
    @Volatile var msPerPage = 4_000L

    /**
     * How far the reading has got, 0 to 1, moving all the time: the pages done, and the page in
     * hand by how long pages have been taking here - never quite reaching its end before it does.
     */
    fun smoothProgress(): Float? {
        if (progress == null || pagesToDo <= 0) return null
        val inPage = ((System.currentTimeMillis() - pageBegan).toFloat() / msPerPage).coerceAtLeast(0f)
        val part = 1f - kotlin.math.exp(-inPage * 1.6f)   // eases towards the page's end, never past it
        return ((pagesDone + part * 0.97f) / pagesToDo).coerceIn(0f, 1f)
    }

    /** The part read last, for the notes panel. */
    var shown by mutableStateOf<Pair<File, Score>?>(null)

    /** Bumped whenever the reader reads better: what was read before is read again. */
    private const val READER = 13

    /**
     * Where [file]'s reading is kept: a page to a file, in the library's own `.inksheets/readings`
     * (so it travels to the other devices with the library, and two devices reading different pages
     * of one part add up), in a folder named by the file's contents (the same file is the same
     * wherever it sits). Off the library: this device's own folder.
     */
    private fun folder(state: SheetsState, file: File): File {
        val root = state.root?.takeIf { file.absolutePath.startsWith(it.absolutePath) }
        val base = if (root != null) File(root, ".inksheets/readings") else File(state.platform.localFolder, "readings")
        val dir = File(base, "r$READER-${idOf(file)}")
        // Read before this was keyed by the file's first revision: under the whole file's
        // checksum. Taken over once, not read again.
        if (!dir.isDirectory) {
            val legacy = File(base, "r$READER-${wholeIdOf(file)}")
            if (legacy != dir && legacy.isDirectory) runCatching { legacy.renameTo(dir) }
        }
        return dir
    }

    /**
     * The same file read by an earlier reader: shown until it is read again, rather than the
     * music going unread after every update of the reader. Null when there is none.
     */
    private fun olderFolder(state: SheetsState, file: File): File? {
        val root = state.root?.takeIf { file.absolutePath.startsWith(it.absolutePath) }
        val base = if (root != null) File(root, ".inksheets/readings") else File(state.platform.localFolder, "readings")
        val ids = listOf(idOf(file), wholeIdOf(file)).distinct()
        for (v in READER - 1 downTo 1) for (id in ids) {
            val d = File(base, "r$v-$id")
            if (d.listFiles { f -> PAGE_FILE.matches(f.name) }?.isNotEmpty() == true) return d
        }
        return null
    }

    private val ids = HashMap<String, String>()

    /**
     * [file]'s music in a word - the same however it is marked. Handwriting is written into a PDF
     * as an update appended to its end (see InkSlate's embedder), so the file's first revision -
     * everything up to its first end-of-file mark - is the music as printed: its size and checksum.
     * Marking a part never makes it a file to be read again.
     */
    internal fun idOf(file: File): String = synchronized(ids) {
        ids.getOrPut("first|${file.absolutePath}|${file.length()}|${file.lastModified()}") {
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@getOrPut wholeIdOf(file)
            val end = if (file.extension.equals("pdf", ignoreCase = true)) firstRevisionEnd(bytes) else bytes.size
            val crc = java.util.zip.CRC32()
            crc.update(bytes, 0, end)
            "${java.lang.Long.toHexString(end.toLong())}-${java.lang.Long.toHexString(crc.value)}"
        }
    }

    /** Where a PDF's first revision ends: just past its first "%%EOF" (the whole file when there is none). */
    internal fun firstRevisionEnd(bytes: ByteArray): Int {
        val mark = "%%EOF".toByteArray()
        var i = 0
        outer@ while (i <= bytes.size - mark.size) {
            for (j in mark.indices) if (bytes[i + j] != mark[j]) { i++; continue@outer }
            var end = i + mark.size
            while (end < bytes.size && (bytes[end] == '\r'.code.toByte() || bytes[end] == '\n'.code.toByte())) end++
            return end
        }
        return bytes.size
    }

    /** The whole file's size and checksum: how readings were filed before [idOf]. */
    private fun wholeIdOf(file: File): String = synchronized(ids) {
        ids.getOrPut("${file.absolutePath}|${file.length()}|${file.lastModified()}") {
            val crc = java.util.zip.CRC32()
            runCatching { file.inputStream().buffered().use { input -> val buf = ByteArray(1 shl 16); while (true) { val n = input.read(buf); if (n < 0) break; crc.update(buf, 0, n) } } }
            "${java.lang.Long.toHexString(file.length())}-${java.lang.Long.toHexString(crc.value)}"
        }
    }

    /**
     * What the player put right in [file]'s reading - bars fixed, rests counted, clefs and keys,
     * bars said wrong, bars cleaned ([kind]) - kept beside the reading in the library, so it is
     * there on every device and never lost with a device's settings. One file a device (two
     * devices never write the same file); the newest is what holds. Null when none was kept here;
     * an empty string when it was cleared.
     */
    internal fun keptEdit(state: SheetsState, file: File, kind: String): String? {
        // In a folder of their own for the file, whatever reader read it: a better reader reads the
        // music again, but what the player put right is theirs and stays. (Kept a while beside the
        // reading itself: looked for there too.)
        for (dir in listOf(editsFolder(state, file), File(folder(state, file), "edits"))) {
            val newest = dir.listFiles { f -> f.name.startsWith("$kind-") && f.name.endsWith(".txt") }?.maxByOrNull { it.lastModified() } ?: continue
            return runCatching { newest.readText() }.getOrNull()
        }
        return null
    }

    /** Where what the player put right in [file]'s reading is kept: by the file's music alone, not the reader. */
    private fun editsFolder(state: SheetsState, file: File): File {
        val root = state.root?.takeIf { file.absolutePath.startsWith(it.absolutePath) }
        val base = if (root != null) File(root, ".inksheets/readings") else File(state.platform.localFolder, "readings")
        return File(base, "edits-${idOf(file)}")
    }

    /** Keep [value] as what the player put right of [kind] in [file]'s reading (null: none). */
    internal fun keepEdit(state: SheetsState, file: File, kind: String, value: String?) {
        val dir = editsFolder(state, file).apply { mkdirs() }
        val me = state.platform.deviceId.map { if (it.isLetterOrDigit() || it == '-') it else '_' }.joinToString("")
        runCatching { File(dir, "$kind-$me.txt").writeText(value.orEmpty()) }
    }

    private fun pageFile(dir: File, page: Int) = File(dir, "p${page + 1}.json")
    private val PAGE_FILE = Regex("p\\d+\\.json")

    /** What the second looks at a page's unsure bars saw, kept beside its reading (see [lookedFor]). */
    private fun looksFile(dir: File, page: Int) = File(dir, "looks-p${page + 1}.json")

    /** The second looks read lately, by folder and page. */
    private val looksKnown = HashMap<String, Map<Int, List<Measure>>>()

    /**
     * What the second looks at bar [m] of [file] saw - taken as each page was read, so Fix has them
     * at once - or nothing, where none were taken (a PDF that states its notes; a page read before).
     */
    fun lookedFor(state: SheetsState, file: File, m: Measure): List<Measure> {
        val f = looksFile(folder(state, file), m.page)
        val key = "${f.path}:${f.lastModified()}"
        val byBar = synchronized(looksKnown) { looksKnown[key] } ?: (runCatching { Scores.decode(f.readText()) }.getOrNull()?.measures?.groupBy { it.number } ?: emptyMap())
            .also { synchronized(looksKnown) { looksKnown[key] = it } }
        return byBar[m.number].orEmpty()
    }

    /** What was read and when it was looked for: the folder looked at again only every few seconds (another device may add pages). */
    private class Known(val score: Score?, val at: Long, val signature: String)
    private val known = HashMap<String, Known>()

    /** [file]'s notes as far as they have been read - here or on another device; null if none of it has. */
    fun cached(state: SheetsState, file: File): Score? {
        val own = folder(state, file)
        // Nothing read by this reader yet: an earlier reader's reading, until it is read again.
        val dir = if (own.listFiles { f -> PAGE_FILE.matches(f.name) }?.isNotEmpty() == true) own else olderFolder(state, file) ?: own
        val now = System.currentTimeMillis()
        synchronized(known) { known[dir.path]?.let { if (now - it.at < 3000) return it.score } }
        // Only the pages' own files: not a copy a syncing program kept of one two devices wrote at
        // once (Syncthing's "p1.sync-conflict-....json") - every bar of it would be there twice. Those
        // copies are let go.
        val pages = dir.listFiles { f -> PAGE_FILE.matches(f.name) }.orEmpty()
        dir.listFiles { f -> f.name.contains(".sync-conflict-") }?.forEach { runCatching { it.delete() } }
        val signature = pages.sortedBy { it.name }.joinToString { "${it.name}:${it.length()}:${it.lastModified()}" }
        synchronized(known) { known[dir.path]?.takeIf { it.signature == signature }?.let { known[dir.path] = Known(it.score, now, signature); return it.score } }
        val parts = pages.mapNotNull { f -> runCatching { Scores.decode(f.readText()) }.getOrNull() }
        val score = if (parts.isEmpty()) null else {
            val count = parts.maxOf { it.pages }
            val read = parts.flatMap { it.readPages.orEmpty() }.distinct().sorted()
            val widths = List(count) { i -> parts.firstNotNullOfOrNull { it.pageWidths.getOrNull(i)?.takeIf { w -> w > 0 } } ?: 0 }
            // (Numbered so no bar number comes twice: see Scores.numberedOnce.)
            Score(Scores.numberedOnce(parts.flatMap { it.measures }.sortedWith(compareBy({ it.page }, { it.staff }, { it.box.left }))), count, widths, read.takeIf { it.size < count })
        }
        synchronized(known) { known[dir.path] = Known(score, now, signature) }
        return score
    }

    /**
     * [part]'s own bars out of [whole] - the reading of the file it is in: its pages only (a part
     * in a band pack), numbered from 1; and how far its numbers were moved from the file's.
     */
    fun partOf(whole: Score, part: com.inksheets.core.Part?): Pair<Score, Int> {
        val first = part?.firstPage ?: return whole to 0
        val last = part.lastPage ?: Int.MAX_VALUE
        val bars = whole.measures.filter { it.page + 1 in first..last }
        val offset = (bars.firstOrNull()?.number ?: 1) - 1
        return whole.copy(measures = bars.map { it.copy(number = it.number - offset) }) to offset
    }

    /**
     * Read [file] (off the UI thread) - [again] even if it has been; [onDone] on the UI thread with
     * the notes, or null. With [pages] (0-based), only those - a page or two of a long book, for
     * the passage being worked on - added to whatever of the file has been read already.
     */
    fun read(state: SheetsState, file: File, again: Boolean = false, pages: Set<Int>? = null, onDone: (Score?) -> Unit) {
        if (busy != null) return
        if (again) {
            val dir = folder(state, file)
            if (pages == null) dir.listFiles().orEmpty().forEach { it.delete() } else pages.forEach { pageFile(dir, it).delete() }
            synchronized(known) { known.remove(dir.path) }
        }
        busy = "Getting ready..."
        Thread({
            // A reading gives way to everything else: the sound of the music most of all.
            Thread.currentThread().priority = Thread.MIN_PRIORITY
            val score = runCatching { readNow(state, file, pages) }
                .onFailure { state.platform.log("Reading ${file.name} failed: ${it.message}") }.getOrNull()
            state.platform.onMain {
                busy = null
                progress = null
                if (score != null) shown = file to score
                onDone(score)
            }
        }, "transcribe").apply { isDaemon = true; start() }
    }

    /** Left in a part's readings while every page of it is being read: a read cut off carries on ([resume]). */
    private fun wholeMark(dir: File) = File(dir, "whole")

    /**
     * Carry on reading [file] if a reading of every page was started and cut off part way (the
     * app closed) - from the page in front. The look at the folder is off the UI thread.
     */
    fun resume(state: SheetsState, file: File) {
        if (busy != null) return
        Thread({
            val dir = folder(state, file)
            if (!wholeMark(dir).isFile) return@Thread
            val c = cached(state, file)
            if (c != null && (0 until c.pages).all { c.hasRead(it) }) { wholeMark(dir).delete(); return@Thread }
            state.platform.onMain { if (busy == null && state.currentPath == file.absolutePath) read(state, file) { com.inkslate.core.Perform.marksChanged() } }
        }, "read-resume").apply { isDaemon = true; start() }
    }

    /**
     * Read [file] whole, off the screen's thread, saying nothing on the screen - several at once
     * (the band's parts before the band plays). Kept like any reading.
     */
    fun readQuietly(state: SheetsState, file: File): Score? =
        runCatching { readNow(state, file, null, quiet = true) }.onFailure { state.platform.log("Reading ${file.name} failed: ${it.message}") }.getOrNull()

    private fun readNow(state: SheetsState, file: File, only: Set<Int>? = null,
                        /** Read beside others (the band's parts): no word of it on the screen - the caller says how far they all are. */
                        quiet: Boolean = false): Score? {
        val peek = state.platform.peek(file) ?: return null
        peek.use {
            val dir = folder(state, file)
            if (only == null) runCatching { dir.mkdirs(); wholeMark(dir).writeText("") }
            // What was read before (here or elsewhere): a whole read carries on where it left off.
            val before = cached(state, file)
            // A whole read starts at the page in front (a long book is opened at the study being
            // worked on) and goes on from there, coming back round to the pages before it last.
            val here = if (only == null && state.currentPath == file.absolutePath) state.pageShown.first.coerceIn(0, maxOf(0, peek.pageCount - 1)) else 0
            val todo = (only ?: (0 until peek.pageCount).filter { before == null || !before.hasRead(it) }.toSet()).filter { it in 0 until peek.pageCount }
                .sortedWith(compareBy({ it < here }, { it }))
            val carry = Recognizer.Carry()
            val measures = ArrayList<Measure>()
            // One width a page, whether read or not: a bar's box is on its page's scale.
            val widths = MutableList(peek.pageCount) { before?.pageWidths?.getOrNull(it) ?: 0 }
            var number = 1
            var last = -2
            val t0 = System.currentTimeMillis()
            // The next page drawn while this one is read, on a device with the cores and memory for
            // it (two pages held at once): the reading the same, only sooner.
            var ahead: Pair<Int, java.util.concurrent.FutureTask<Pair<IntArray, Ink>?>>? = null
            fun drawn(p: Int): Pair<IntArray, Ink>? {
                ahead?.takeIf { it.first == p }?.let { ahead = null; return runCatching { it.second.get() }.getOrNull() ?: pageInk(peek, p) }
                return pageInk(peek, p)
            }
            try { for ((i, p) in todo.withIndex()) {
                if (!quiet) {
                    val now = System.currentTimeMillis()
                    // How long a page takes on this device, learned as pages are read.
                    if (i > 0 && pageBegan > 0) msPerPage = ((msPerPage * 2 + (now - pageBegan)) / 3).coerceIn(500L, 60_000L)
                    pagesDone = i; pagesToDo = todo.size; pageBegan = now
                }
                if (!quiet) state.platform.onMain { progress = i.toFloat() / todo.size.coerceAtLeast(1); busy = if (only == null) "Reading page ${p + 1} of ${peek.pageCount}..." else "Reading page ${p + 1}${if (todo.size > 1) " (${i + 1} of ${todo.size})" else ""}..." }
                // A page following one just read, or read before, carries on from it: its clef, key,
                // time and bar numbers. One on its own takes its numbers from the print.
                if (p != last + 1) {
                    val prev = before?.measures?.filter { it.page == p - 1 }?.lastOrNull()
                    carry.alone = prev == null
                    if (prev != null) { carry.clef = prev.clef; carry.key = prev.key; carry.time = prev.time; number = prev.number + prev.bars }
                    else number = 1
                }
                // Read on another device since this read began (the folder syncs): taken as it is.
                if (only == null && pageFile(dir, p).isFile && before?.hasRead(p) != true) { last = -2; continue }
                last = p
                val pageStarted = System.currentTimeMillis()
                val (grey, ink) = drawn(p) ?: continue
                todo.getOrNull(i + 1)?.let { next ->
                    val rt = Runtime.getRuntime()
                    if (Workers.roomy && rt.maxMemory() - (rt.totalMemory() - rt.freeMemory()) > (256L shl 20)) {
                        val task = java.util.concurrent.FutureTask { pageInk(peek, next) }
                        ahead = next to task
                        Thread(task, "read-ahead").apply { isDaemon = true; start() }
                    }
                }
                // A PDF that states its notes is read from them, exactly; a scan by the trained reader.
                val printed = runCatching { synchronized(peek) { peek.printed(p) } }.getOrNull()
                val reading = Recognizer().read(ink, p, number, carry, printed, grey = grey, net = if (printed == null) Net.shipped else null)
                measures += reading.measures
                // The width the page really came back at: a renderer may draw a small page narrower
                // than asked (a cap on how far it magnifies), and every bar is placed by it.
                widths[p] = ink.width
                reading.measures.lastOrNull()?.let { number = it.number + it.bars }
                // Its unsure bars looked at again closely - now, with the page drawn already, before the
                // next page - so Fix has what the looks saw the moment it is opened. Each staff with any
                // read once a look, the staves side by side where there are the cores.
                val net = if (printed == null) Net.shipped else null
                val unsure = reading.measures.filter { !it.sure && it.bars == 1 }
                if (net != null && unsure.isNotEmpty()) runCatching {
                    if (!quiet) state.platform.onMain { busy = "Looking again at ${unsure.size} unsure bar${if (unsure.size == 1) "" else "s"} on page ${p + 1}..." }
                    val looked = Workers.map(unsure.groupBy { it.staff }.entries.toList()) { (staff, bars) ->
                        val first = reading.measures.first { it.staff == staff }
                        Recognizer().lookAgainStaff(ink, grey, net, bars, first)
                    }.flatMap { it.values.flatten() }
                    dir.mkdirs()
                    looksFile(dir, p).writeText(Scores.encode(Score(looked, peek.pageCount, List(peek.pageCount) { if (it == p) ink.width else 0 }, listOf(p))))
                }.onFailure { state.platform.log("Looking again at page ${p + 1} of ${file.name} failed: ${it.message}") }
                pageTimes += System.currentTimeMillis() - pageStarted
                // Kept as soon as read: a reading stopped part way carries on from here, and the
                // pages read so far reach the other devices.
                runCatching {
                    dir.mkdirs()
                    val one = Score(reading.measures, peek.pageCount, List(peek.pageCount) { if (it == p) ink.width else 0 }, listOf(p))
                    val tmp = File(dir, "p${p + 1}.json.tmp"); tmp.writeText(Scores.encode(one))
                    val dest = pageFile(dir, p); dest.delete(); tmp.renameTo(dest)
                }
                synchronized(known) { known.remove(dir.path) }
            } } finally {
                // The page ahead finished drawing before the file is closed under it.
                ahead?.second?.let { runCatching { it.get() } }
            }
            val score = cached(state, file) ?: Score(measures, peek.pageCount, widths, todo)
            if (only == null) wholeMark(dir).delete()
            state.platform.log("Read ${file.name}${if (only != null) " pages ${todo.map { it + 1 }}" else ""}: ${measures.size} bars, ${measures.count { it.sure }} sure, in ${(System.currentTimeMillis() - t0) / 1000}s")
            return score
        }
    }

    /**
     * Bar [m] of [file] looked at again - its staff only, a little larger, smaller, higher and lower
     * ([deeper]: further still) - by the trained reader, off the UI thread: [onDone] on the UI thread
     * with each look's reading of the bar (none for a PDF that states its notes: read exactly already).
     */
    fun lookAgain(state: SheetsState, file: File, m: com.inksheets.core.omr.Measure, width: Int, deeper: Boolean,
                  /** Whether the look is still wanted when its turn comes (the user may have gone on). */
                  wanted: () -> Boolean = { true }, onDone: (List<com.inksheets.core.omr.Measure>) -> Unit) {
        looker.execute {
            // Gone on to another bar before this one's turn: not looked at at all.
            if (!wanted()) return@execute
            val found = runCatching {
                val peek = state.platform.peek(file) ?: return@runCatching emptyList()
                peek.use {
                    val net = Net.shipped
                    if (net == null || runCatching { peek.printed(m.page) }.getOrNull() != null) return@use emptyList()
                    val img = drawnPage(state, file, m.page, width) ?: return@use emptyList()
                    val px = IntArray(img.width * img.height).also { img.readPixels(it) }
                    Recognizer().lookAgain(Ink.fromArgb(img.width, img.height, px), com.inksheets.core.omr.Strips.grey(px), net, m, deeper)
                }
            }.onFailure { state.platform.log("Looking again at bar ${m.number} of ${file.name} failed: ${it.message}") }.getOrNull().orEmpty()
            state.platform.onMain { onDone(found) }
        }
    }

    /**
     * Bar [m] of [file] as printed, cut from its page drawn [width] wide (as it was read): the bar
     * and a space either side, three and a half over and under its staff - off the UI thread, [onDone] on it.
     */
    fun barPicture(state: SheetsState, file: File, m: com.inksheets.core.omr.Measure, width: Int,
                   wanted: () -> Boolean = { true }, onDone: (androidx.compose.ui.graphics.ImageBitmap?) -> Unit) {
        looker.execute {
            if (!wanted()) return@execute
            val picture = runCatching {
                run {
                    val page = drawnPage(state, file, m.page, width) ?: return@run null
                    val sp = m.space
                    // A little of the bars either side, so a bar split or run together where it should not be shows as such.
                    val l = maxOf(0, (m.box.left - sp * 3).toInt()); val r = minOf(page.width, (m.box.right + sp * 3).toInt())
                    // As far up and down as the bar's ink goes - a note high on ledger lines, a slur over it -
                    // to the first clear gap, at most seven spaces from the staff.
                    val px = IntArray(page.width * page.height).also { page.readPixels(it) }
                    fun inkRow(y: Int): Boolean = y in 0 until page.height && (maxOf(0, m.box.left)..minOf(page.width - 1, m.box.right)).any { x ->
                        val c = px[y * page.width + x]; ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) < 3 * 140 }
                    fun reach(from: Int, step: Int): Int {
                        var y = from; var last = from; var gap = 0
                        while (kotlin.math.abs(y - from) < sp * 7 && gap < sp * 1.2f) { y += step; if (inkRow(y)) { last = y; gap = 0 } else gap++ }
                        return last
                    }
                    val t = maxOf(0, minOf(reach(m.box.top, -1), (m.box.top - sp * 2.5f).toInt()) - (sp * 0.6f).toInt())
                    val b = minOf(page.height, maxOf(reach(m.box.bottom, 1), (m.box.bottom + sp * 2.5f).toInt()) + (sp * 0.6f).toInt())
                    if (r - l < 4 || b - t < 4) return@run null
                    val out = androidx.compose.ui.graphics.ImageBitmap(r - l, b - t)
                    val canvas = androidx.compose.ui.graphics.Canvas(out)
                    canvas.drawImageRect(page,
                        androidx.compose.ui.unit.IntOffset(l, t), androidx.compose.ui.unit.IntSize(r - l, b - t),
                        androidx.compose.ui.unit.IntOffset.Zero, androidx.compose.ui.unit.IntSize(r - l, b - t), androidx.compose.ui.graphics.Paint())
                    // The bars either side dimmed: the bar asked about is what is left bright.
                    val dim = androidx.compose.ui.graphics.Paint().apply { color = androidx.compose.ui.graphics.Color(0xB0FFFFFF) }
                    val bl = (m.box.left - l).toFloat(); val br = (m.box.right - l).toFloat()
                    if (bl > 0) canvas.drawRect(0f, 0f, bl, (b - t).toFloat(), dim)
                    if (br < r - l) canvas.drawRect(br, 0f, (r - l).toFloat(), (b - t).toFloat(), dim)
                    // What was read, marked along the picture's foot under each note and rest - green
                    // read clearly, amber unclear - and a ring round each note seen and let go: what
                    // the reader made of the print, and where it struggled (a note with no mark under
                    // it was not read at all).
                    val foot = (b - t) - sp * 0.4f
                    val clear = androidx.compose.ui.graphics.Paint().apply { color = androidx.compose.ui.graphics.Color(0xFF2E7D32) }
                    val unclear = androidx.compose.ui.graphics.Paint().apply { color = androidx.compose.ui.graphics.Color(0xFFE08A00) }
                    val rest = androidx.compose.ui.graphics.Paint().apply { color = androidx.compose.ui.graphics.Color(0xFF6D7F8E) }
                    for (e in m.events) {
                        val x = e.x - l
                        val w = if (e is com.inksheets.core.omr.Note) sp * 1.15f else sp * 0.9f
                        val p = when { e !is com.inksheets.core.omr.Note -> rest; Learned.unclear(e) -> unclear; else -> clear }
                        canvas.drawRoundRect(x, foot - sp * 0.16f, x + w, foot + sp * 0.16f, sp * 0.16f, sp * 0.16f, p)
                    }
                    val ring = androidx.compose.ui.graphics.Paint().apply {
                        color = androidx.compose.ui.graphics.Color(0xFFE08A00); style = androidx.compose.ui.graphics.PaintingStyle.Stroke; strokeWidth = maxOf(1.5f, sp * 0.14f)
                    }
                    for (e in m.maybe) if (e is com.inksheets.core.omr.Note) for (st in e.steps) {
                        val y = m.yAt(st * 0.5f, e.x + sp * 0.6f) - t
                        canvas.drawCircle(androidx.compose.ui.geometry.Offset(e.x - l + sp * 0.6f, y), sp * 0.95f, ring)
                    }
                    out
                }
            }.getOrNull()
            state.platform.onMain { onDone(picture) }
        }
    }

    /**
     * What bar [m] of [file] really is - [events], as picked in Fix - written down for teaching the
     * trained reader: a line in the library's Training folder (which syncs, and is no song), one
     * file a device so two never write the same one.
     */
    fun recordFix(state: SheetsState, file: File, m: com.inksheets.core.omr.Measure, width: Int, events: List<com.inksheets.core.omr.Event>,
                  /** How it was come to: "pick" (one of the readings offered) or "hand" (put right in the editor). */
                  how: String = "pick",
                  /** How many times "None of these" was pressed for the bar first. */
                  noneCount: Int = 0) {
        val root = state.root ?: return
        val rel = state.relative(file) ?: return
        // Whether it adds up to its bar's time: a pick that does not is taken with a grain of salt
        // (never taught from - see StripExport), though kept, as it is what the player chose.
        val adds = kotlin.math.abs(events.sumOf { it.duration.quarters } - m.time.quarters) < 1e-6
        looker.execute {
            runCatching {
                val dir = File(root, "${com.inksheets.core.LibraryScan.TRAINING}/Fixes").apply { mkdirs() }
                val line = buildString {
                    append("{\"how\":\"").append(how).append("\",\"adds\":").append(adds).append(",\"none\":").append(noneCount)
                    append(",\"file\":").append(kotlinx.serialization.json.JsonPrimitive(rel))
                    append(",\"id\":\"").append(idOf(file)).append('"')
                    append(",\"page\":").append(m.page).append(",\"staff\":").append(m.staff).append(",\"number\":").append(m.number)
                    append(",\"width\":").append(width)
                    append(",\"box\":[").append(m.box.left).append(',').append(m.box.top).append(',').append(m.box.right).append(',').append(m.box.bottom).append(']')
                    append(",\"events\":").append(com.inksheets.core.omr.Scores.encodeFixes(mapOf(m.number to events)))
                    append(",\"at\":").append(System.currentTimeMillis()).append('}')
                }
                File(dir, "fixes-${state.platform.deviceId}.jsonl").appendText(line + "\n")
            }.onFailure { state.platform.log("Keeping a fix for teaching failed: ${it.message}") }
        }
    }

    /**
     * Bar [m] of [file] left as it was in Fix ("Skip"): written down beside the fixes with what was
     * turned down for it ([rejected]: each reading offered and not taken) and how many times "None of
     * these" was pressed - the bars no reading offered could put right (a time or key misread, a
     * reading far off), for finding what the readings offered miss.
     */
    fun recordSkip(state: SheetsState, file: File, m: com.inksheets.core.omr.Measure, width: Int, rejected: List<List<com.inksheets.core.omr.Event>>, noneCount: Int) {
        val root = state.root ?: return
        val rel = state.relative(file) ?: return
        looker.execute {
            runCatching {
                val dir = File(root, "${com.inksheets.core.LibraryScan.TRAINING}/Fixes").apply { mkdirs() }
                val line = buildString {
                    append("{\"kind\":\"skip\",\"none\":").append(noneCount)
                    append(",\"file\":").append(kotlinx.serialization.json.JsonPrimitive(rel))
                    append(",\"id\":\"").append(idOf(file)).append('"')
                    append(",\"page\":").append(m.page).append(",\"staff\":").append(m.staff).append(",\"number\":").append(m.number)
                    append(",\"width\":").append(width)
                    append(",\"box\":[").append(m.box.left).append(',').append(m.box.top).append(',').append(m.box.right).append(',').append(m.box.bottom).append(']')
                    append(",\"time\":\"").append(m.time.beats).append('/').append(m.time.beatType).append('"')
                    append(",\"key\":").append(m.key.fifths).append(",\"clef\":\"").append(m.clef.name).append('"')
                    append(",\"doubts\":").append(kotlinx.serialization.json.JsonArray(m.doubts.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                    append(",\"read\":").append(com.inksheets.core.omr.Scores.encodeFixes(mapOf(m.number to m.events)))
                    append(",\"rejected\":[").append(rejected.joinToString(",") { com.inksheets.core.omr.Scores.encodeFixes(mapOf(m.number to it)) }).append(']')
                    append(",\"at\":").append(System.currentTimeMillis()).append('}')
                }
                File(dir, "fixes-${state.platform.deviceId}.jsonl").appendText(line + "\n")
            }.onFailure { state.platform.log("Keeping a skipped bar for teaching failed: ${it.message}") }
        }
    }

    /**
     * The clef, key or time from bar [m] of [file] on is [fix], said in Fix: written down beside the
     * fixes, with what was read, for teaching what reads them.
     */
    fun recordSignature(state: SheetsState, file: File, m: com.inksheets.core.omr.Measure, width: Int, fix: com.inksheets.core.omr.SigFix) {
        val root = state.root ?: return
        val rel = state.relative(file) ?: return
        looker.execute {
            runCatching {
                val dir = File(root, "${com.inksheets.core.LibraryScan.TRAINING}/Fixes").apply { mkdirs() }
                val line = buildString {
                    append("{\"kind\":\"signature\",\"file\":").append(kotlinx.serialization.json.JsonPrimitive(rel))
                    append(",\"id\":\"").append(idOf(file)).append('"')
                    append(",\"page\":").append(m.page).append(",\"staff\":").append(m.staff).append(",\"number\":").append(m.number)
                    append(",\"width\":").append(width)
                    append(",\"box\":[").append(m.box.left).append(',').append(m.box.top).append(',').append(m.box.right).append(',').append(m.box.bottom).append(']')
                    append(",\"read\":{\"clef\":\"").append(m.clef.name).append("\",\"key\":").append(m.key.fifths).append(",\"time\":\"").append(m.time.beats).append('/').append(m.time.beatType).append("\"}")
                    append(",\"is\":").append(com.inksheets.core.omr.Signatures.encode(mapOf(m.number to fix)))
                    append(",\"at\":").append(System.currentTimeMillis()).append('}')
                }
                File(dir, "fixes-${state.platform.deviceId}.jsonl").appendText(line + "\n")
            }.onFailure { state.platform.log("Keeping a signature fix for teaching failed: ${it.message}") }
        }
    }

    /**
     * Pages drawn lately, for Fix's pictures and looks: the bars it goes through are mostly on one
     * page, and each wants it drawn whole and large - drawn once, not once a bar. (One page kept; two
     * where there is the memory: the one ahead drawn while this one is looked at.)
     */
    private val drawn = LinkedHashMap<String, androidx.compose.ui.graphics.ImageBitmap>()

    private fun drawnPage(state: SheetsState, file: File, page: Int, width: Int): androidx.compose.ui.graphics.ImageBitmap? {
        val key = "${file.absolutePath}|${file.lastModified()}|$page|$width"
        synchronized(drawn) { drawn[key]?.let { return it } }
        val img = state.platform.peek(file)?.use { it.render(page, width) } ?: return null
        synchronized(drawn) {
            drawn[key] = img
            val keep = if (Workers.roomy) 2 else 1
            while (drawn.size > keep) drawn.remove(drawn.keys.first())
        }
        return img
    }

    /** Page [page] of [file] drawn ahead (quietly, after whatever is being looked at), for the next bar in Fix. */
    fun drawAhead(state: SheetsState, file: File, page: Int, width: Int) {
        if (!Workers.roomy) return
        looker.execute { runCatching { drawnPage(state, file, page, width) } }
    }

    /** Bar [m] of [file] is a rest of [count] bars, said in Fix: written down beside the fixes, for teaching how a rest's figure is read. */
    fun recordRest(state: SheetsState, file: File, m: com.inksheets.core.omr.Measure, width: Int, count: Int) {
        val root = state.root ?: return
        val rel = state.relative(file) ?: return
        looker.execute {
            runCatching {
                val dir = File(root, "${com.inksheets.core.LibraryScan.TRAINING}/Fixes").apply { mkdirs() }
                val line = buildString {
                    append("{\"kind\":\"rest\",\"file\":").append(kotlinx.serialization.json.JsonPrimitive(rel))
                    append(",\"id\":\"").append(idOf(file)).append('"')
                    append(",\"page\":").append(m.page).append(",\"staff\":").append(m.staff).append(",\"number\":").append(m.number)
                    append(",\"width\":").append(width)
                    append(",\"box\":[").append(m.box.left).append(',').append(m.box.top).append(',').append(m.box.right).append(',').append(m.box.bottom).append(']')
                    append(",\"read\":").append(m.bars).append(",\"is\":").append(count)
                    append(",\"at\":").append(System.currentTimeMillis()).append('}')
                }
                File(dir, "fixes-${state.platform.deviceId}.jsonl").appendText(line + "\n")
            }.onFailure { state.platform.log("Keeping a rest's count for teaching failed: ${it.message}") }
        }
    }

    /** How long each page read on this device lately took, in ms (the last few dozen): shown so how fast it is can be seen. */
    val pageTimes: MutableList<Long> = java.util.Collections.synchronizedList(ArrayList())

    /** "About 2.4 s a page on this device", from the pages read lately; null before any. */
    fun speedSaid(): String? {
        val times = synchronized(pageTimes) { pageTimes.takeLast(30) }
        if (times.isEmpty()) return null
        return "About ${"%.1f".format(times.average() / 1000.0)} s a page on this device (${times.size} page${if (times.size == 1) "" else "s"}, slowest ${"%.1f".format(times.max() / 1000.0)} s)"
    }

    /**
     * Bar [m] of [file] is not one bar as read - its barlines found wrong (a stem taken for one, or one
     * missed) - written down beside the fixes, for finding and teaching barlines.
     */
    fun recordNotABar(state: SheetsState, file: File, m: com.inksheets.core.omr.Measure, width: Int) {
        val root = state.root ?: return
        val rel = state.relative(file) ?: return
        looker.execute {
            runCatching {
                val dir = File(root, "${com.inksheets.core.LibraryScan.TRAINING}/Fixes").apply { mkdirs() }
                val line = buildString {
                    append("{\"kind\":\"not one bar\",\"file\":").append(kotlinx.serialization.json.JsonPrimitive(rel))
                    append(",\"id\":\"").append(idOf(file)).append('"')
                    append(",\"page\":").append(m.page).append(",\"staff\":").append(m.staff).append(",\"number\":").append(m.number)
                    append(",\"width\":").append(width)
                    append(",\"box\":[").append(m.box.left).append(',').append(m.box.top).append(',').append(m.box.right).append(',').append(m.box.bottom).append(']')
                    append(",\"at\":").append(System.currentTimeMillis()).append('}')
                }
                File(dir, "fixes-${state.platform.deviceId}.jsonl").appendText(line + "\n")
            }.onFailure { state.platform.log("Keeping a barline mistake for teaching failed: ${it.message}") }
        }
    }

    /** One look at a time: a phone renders and reads one page at once, never a pile of them. */
    private val looker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "look-again").apply { isDaemon = true } }

    /** Page [page] drawn [width] wide: its grey levels, and in black and white. */
    /**
     * [page] drawn for reading: first small, to measure its staff spaces, then at the size that
     * makes a space 18 pixels. (Drawn one at a time: the page ahead may be drawn on another thread.)
     */
    private fun pageInk(peek: PagePeek, page: Int): Pair<IntArray, Ink>? {
        val first = inkOf(peek, page, 1600)?.second ?: return null
        val space = Recognizer().metrics(first)?.second
        val width = if (space == null || space <= 0f) 1600 else (1600 * 18f / space).toInt().coerceIn(1000, 5000)
        return inkOf(peek, page, width)
    }

    private fun inkOf(peek: PagePeek, page: Int, width: Int): Pair<IntArray, Ink>? {
        val img = synchronized(peek) { peek.render(page, width) } ?: return null
        val px = IntArray(img.width * img.height)
        img.readPixels(px)
        return com.inksheets.core.omr.Strips.grey(px) to Ink.fromArgb(img.width, img.height, px)
    }
}
