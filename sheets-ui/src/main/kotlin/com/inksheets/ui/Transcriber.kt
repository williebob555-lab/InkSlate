package com.inksheets.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.inksheets.core.omr.Ink
import com.inksheets.core.omr.Learned
import com.inksheets.core.omr.Measure
import com.inksheets.core.omr.MeasureEvidence
import com.inksheets.core.omr.MeasureSpecialistEvidence
import com.inksheets.core.omr.Net
import com.inksheets.core.omr.DotAudit
import com.inksheets.core.omr.ReadingAuditPlanner
import com.inksheets.core.omr.ReadingAuditVariants
import com.inksheets.core.omr.ReadingConfidence
import com.inksheets.core.omr.ReadingEvidence
import com.inksheets.core.omr.RestAudit
import com.inksheets.core.omr.MultiRestAudit
import com.inksheets.core.omr.Recognizer
import com.inksheets.core.omr.Score
import com.inksheets.core.omr.Scores
import com.inksheets.core.omr.SpecialistEvidenceCodec
import com.inksheets.core.omr.Workers
import java.io.File

/**
 * Reading a part's notes off its pages (experimental): each page drawn so a staff space is
 * about 18 pixels - where the reader does best - read, and the whole kept on this device, so a
 * part is read once and not every time it is wanted.
 */
internal object Transcriber {
    @Volatile private var backgroundState: SheetsState? = null
    @Volatile private var backgroundPaused = false
    @Volatile private var activityUntil = 0L
    @Volatile private var deeperAfter = 0L
    @Volatile private var backgroundToken = ""
    @Volatile private var automaticPass = false
    private val stoppedBackground = java.util.Collections.synchronizedSet(HashSet<String>())
    private val queue = ReadingQueue<Score?> { backgroundAllowed() }
    /** Background activity is separate from an explicitly requested reading and never opens UI. */
    var backgroundStatus by mutableStateOf<String?>(null)
        private set

    private fun backgroundAllowed(): Boolean {
        val s = backgroundState ?: return false
        val now = System.currentTimeMillis()
        val displayBusy = com.inkslate.core.RenderGate.displayBusy()
        // A turn is often followed by another a second or two later. Starting a full recognition
        // page in that gap makes the next turn fight its allocations and native PDF work. Require
        // a real idle stretch after any display/input activity before automatic work resumes.
        if (displayBusy) activityUntil = maxOf(activityUntil, now + 8_000L)
        val occupied = s.homeInFront || !s.platform.inForeground || !s.platform.backgroundReadingAllowed || displayBusy ||
            Recording.playing || Recording.loading || SelfRecorder.recording || SharedMetronome.running || Listener.active ||
            ScoreTools.playing != null || Click.counting > 0
        if (occupied) deeperAfter = maxOf(deeperAfter, now + 15_000L)
        return !backgroundPaused && s.readMusic && s.backgroundReading && !occupied && now >= activityUntil
    }

    /** Annotation input can keep the reader out of the way without putting up any panel. */
    fun activity(ms: Long = 1800) {
        activityUntil = maxOf(activityUntil, System.currentTimeMillis() + ms.coerceAtLeast(0))
        queue.wake()
    }

    fun pauseBackground(state: SheetsState, pause: Boolean) {
        if (backgroundState === state) { backgroundPaused = pause; queue.wake() }
    }

    fun stopBackground(state: SheetsState) {
        if (backgroundState !== state) return
        backgroundPaused = true
        queue.cancelWhere { it.startsWith("background:") }
        backgroundStatus = null
    }

    /** Release everything that can be rebuilt when Android reports heap pressure. Active reading
     * yields at its next page boundary; explicit user-requested work is left intact.
     */
    fun trimMemory(state: SheetsState) {
        if (backgroundState === state) {
            backgroundPaused = true
            queue.cancelWhere { it.startsWith("background:") }
            backgroundStatus = null
        }
        synchronized(drawn) { drawn.clear() }
        synchronized(known) { known.clear() }
        synchronized(hotKnown) { hotKnown.clear() }
        synchronized(looksKnown) { looksKnown.clear() }
        synchronized(specialistsKnown) { specialistsKnown.clear() }
    }

    /** OOM is recoverable only after all rebuildable page state is released and automatic work is
     * stopped. Never immediately retry the same allocation: Android may have native page bitmaps
     * which are not reflected in the Java heap's free count yet.
     */
    private fun memoryFailure(state: SheetsState, file: File, phase: String, error: OutOfMemoryError): Score? {
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        state.platform.log("$phase ${file.name} stopped for low memory: ${error.message ?: "allocation failed"}; " +
            "heap used=${used shr 20}MB max=${rt.maxMemory() shr 20}MB available=${(rt.maxMemory() - used).coerceAtLeast(0) shr 20}MB")
        trimMemory(state)
        return null
    }

    /** Refresh from the viewer lifecycle: current piece first, then unread files in the library.
     * Planning/checksums run on the reader worker, never during composition or on the UI thread.
     */
    fun background(state: SheetsState) {
        val token = "${state.root?.absolutePath}|${state.current?.id}|${state.currentPath}|${state.version}"
        if (backgroundState === state && backgroundToken == token) { backgroundPaused = false; queue.wake(); return }
        backgroundState = state
        backgroundToken = token
        backgroundPaused = false
        // Loading the already-read score is ordinary small-file I/O, not recognition.  Start it
        // immediately on its own low-priority worker so buttons and page turns only consult RAM;
        // automatic recognition still observes the longer idle gate below.
        state.currentPath?.let(::File)?.takeIf { it.isFile }?.let { file ->
            preload(state, file) { score ->
                if (score != null && state.currentPath == file.absolutePath) {
                    shown = file to score
                    ScoreTools.marksMoved()
                }
            }
        }
        // Opening a song/page and its first button presses must settle before even library
        // inventory work begins. Global input extends this through RenderGate.touch().
        activityUntil = maxOf(activityUntil, System.currentTimeMillis() + 5_000L)
        queue.cancelWhere { it.startsWith("background:") }
        val root = state.root ?: return
        val current = state.current
        val songs = state.library?.songs.orEmpty().toList()
        queue.submit("background:plan:$token", ReadingQueue.LIBRARY) { control ->
            val ordered = buildList {
                // Only the part actually in front is current-piece work. Treating every PDF in a
                // 20-part UBand folder as urgent made opening or switching one part immediately
                // launch recognition across the whole arrangement. Whole-band Play explicitly
                // requests the selected parts when they are needed.
                state.currentPath?.let(::File)?.takeIf { it.isFile }?.let { add(it to ReadingQueue.CURRENT_PIECE) }
                songs.forEach { song -> song.parts.forEach { p -> state.partFile(song, p)?.let { add(it to ReadingQueue.LIBRARY) } } }
            }.distinctBy { it.first.absolutePath }
            data class AuditPlan(val file: File, val current: Boolean, val priority: Int)
            val audits = ArrayList<AuditPlan>()
            for ((file, priority) in ordered) {
                if (control.shouldYield()) return@submit null
                if (!file.isFile || file.absolutePath in stoppedBackground || state.root != root) continue
                val saved = loadCached(state, file)
                if (priority == ReadingQueue.CURRENT_PIECE) {
                    publish(file, saved)
                    state.platform.onMain { if (state.currentPath == file.absolutePath) ScoreTools.marksMoved() }
                }
                val unread = saved == null || (0 until saved.pages).any { !saved.hasRead(it) } || olderPages(state, file).isNotEmpty()
                if (unread) queue.submit("background:read:${file.absolutePath}", priority) { work ->
                    if (state.root != root || file.absolutePath in stoppedBackground) return@submit null
                    state.platform.onMain { backgroundStatus = "Reading ${file.nameWithoutExtension}" }
                    automaticPass = true
                    Workers.backgroundSerial = true
                    try {
                        readNow(state, file, quiet = true, mayContinue = { !work.shouldYield() }, pageSaved = {
                            state.platform.onMain {
                                if (state.currentPath == file.absolutePath) { known(file)?.let { shown = file to it }; ScoreTools.marksMoved() }
                            }
                        })
                    } catch (error: OutOfMemoryError) {
                        memoryFailure(state, file, "Background reading", error)
                    } finally { Workers.backgroundSerial = false; automaticPass = false; state.platform.onMain { backgroundStatus = null } }
                }
                // Full confidence used to open every page's audit sidecars during song switches.
                // The idle audit writes one tiny summary instead; planning reads only that integer.
                val storedPriority = runCatching { auditPriorityFile(folder(state, file)).readText().trim().toInt() }.getOrDefault(0)
                audits += AuditPlan(file, priority == ReadingQueue.CURRENT_PIECE, storedPriority)
            }
            // Further looks audit every generated bar, including green ones. They remain shadow
            // evidence and never enter Fix's alternatives. Current music stays first; then the
            // library is ordered by evidence that useful work remains.
            audits.sortedWith(compareByDescending<AuditPlan> { it.current }.thenByDescending { it.priority }
                .thenBy { it.file.absolutePath }).forEach { plan ->
                val file = plan.file
                val completed = HashSet<String>()
                queue.submit("background:deeper:${file.absolutePath}", ReadingQueue.DEEPER) { work ->
                    if (state.root != root || file.absolutePath in stoppedBackground) return@submit null
                    val idleAt = maxOf(activityUntil + 15_000L, deeperAfter)
                    if (System.currentTimeMillis() < idleAt) { work.defer(idleAt - System.currentTimeMillis()); return@submit null }
                    automaticPass = true
                    Workers.backgroundSerial = true
                    state.platform.onMain { backgroundStatus = "Auditing ${file.nameWithoutExtension}" }
                    try { deepenNow(state, file, work, completed) }
                    catch (error: OutOfMemoryError) { memoryFailure(state, file, "Background audit", error) }
                    finally { Workers.backgroundSerial = false; automaticPass = false; state.platform.onMain { backgroundStatus = null } }
                }
            }
            null
        }
    }

    private fun deepenNow(state: SheetsState, file: File, control: ReadingQueue.Control,
                          completed: MutableSet<String>): Score? {
        val score = loadCached(state, file) ?: return null
        if (!keptEdit(state, file, "sheets_structure").isNullOrBlank()) return score
        val dir = folder(state, file)
        val net = Net.shipped ?: return score
        // Fix/look pictures can be redrawn. Do not begin a full-page multi-pass audit while those
        // large cached bitmaps are competing for the same heap.
        synchronized(drawn) { drawn.clear() }
        val peek = state.platform.peek(file) ?: return score
        peek.use {
            val fixedAtStart = Scores.decodeFixes(keptEdit(state, file, "sheets_fixed").orEmpty()).keys
            val batches = ReadingAuditPlanner.batches(score, fixedAtStart)
            fun baseIndex(measures: List<Measure>) = measures.minOfOrNull {
                ReadingConfidence.measure(it, ReadingEvidence.assess(it, emptyList())).index
            } ?: 100
            val pages = batches.groupBy { it.page }.entries.sortedWith(compareBy({ baseIndex(it.value.flatMap { b -> b.measures }) }, { it.key }))
            for ((page, unorderedBatches) in pages) {
                val pageBatches = unorderedBatches.sortedWith(compareBy({ baseIndex(it.measures) }, { it.staff }))
                if (control.shouldYield()) return score
                val stamp = pageFile(dir, page).lastModified().toString()
                // Pass-specific marker: the older red-bars-only deeper marker must not suppress
                // the first audit of measures that the rule reader happened to call green.
                val mark = File(dir, "audit-v6-p${page + 1}")
                if (runCatching { mark.readText() }.getOrNull() == stamp) continue
                val printedPage = try { peek.printed(page) }
                catch (error: OutOfMemoryError) { throw error }
                catch (_: Throwable) { null }
                if (printedPage != null) {
                    runCatching { mark.writeText(stamp) }; continue
                }
                val (grey, ink) = pageInk(peek, page) ?: continue
                val existing = runCatching { Scores.decode(auditLooksFile(dir, page).readText())?.measures.orEmpty() }.getOrDefault(emptyList()).toMutableList()
                val oldKeys = existing.map { Scores.encode(Score(listOf(it), score.pages)) }.toHashSet()
                // A variant page is built once, used for every staff, then falls out of scope before
                // another is allocated. The audit therefore stays at base + one alternate Ink page.
                for (variant in ReadingAuditVariants.all) {
                    if (control.shouldYield()) return score
                    val variantInk = ReadingAuditVariants.ink(ink, grey, variant)
                    for (batch in pageBatches) {
                        if (control.shouldYield()) return score
                        val passKey = "$page:${batch.staff}:${variant.id}"
                        if (passKey in completed) continue
                        // Recheck before every staff: a correction made while this pass was queued
                        // is never reconsidered.
                        val fixed = Scores.decodeFixes(keptEdit(state, file, "sheets_fixed").orEmpty()).keys
                        val bars = batch.measures.filter { it.number !in fixed }
                        if (bars.isEmpty()) { completed += passKey; continue }
                        val first = score.measures.firstOrNull { it.page == page && it.staff == batch.staff } ?: continue
                        val alternatives = Recognizer().lookAgainStaff(variantInk, grey, net, bars, first,
                            looks = listOf(variant.look))
                        for (bar in bars) alternatives[bar.number].orEmpty().forEach { option ->
                            if (oldKeys.add(Scores.encode(Score(listOf(option), score.pages)))) existing += option
                        }
                        completed += passKey
                        // Save after every staff/variant so a foreground request can yield without
                        // losing completed work. Candidates never replace the base or a correction.
                        atomicText(auditLooksFile(dir, page), Scores.encode(Score(existing, score.pages, score.pageWidths, listOf(page))))
                    }
                }
                // Symbol specialists are cheap, local, and deliberately stored apart from full
                // alternate readings. Recheck fixed bars once more before inspecting the page.
                val fixedNow = Scores.decodeFixes(keptEdit(state, file, "sheets_fixed").orEmpty()).keys
                val specialist = pageBatches.flatMap { it.measures }.distinctBy { it.number }
                    .filter { it.number !in fixedNow }
                    .map { MeasureSpecialistEvidence(it.number, DotAudit.inspect(it, grey, ink.width, ink.height),
                        RestAudit.inspect(it, ink), MultiRestAudit.inspect(it, ink)) }
                atomicText(specialistFile(dir, page), SpecialistEvidenceCodec.encode(specialist))
                runCatching { mark.writeText(stamp) }
            }
        }
        // Written only after a complete idle audit. Future song switches can rank a library by
        // confidence without decoding every alternate-reading sidecar in the navigation path.
        val fixed = Scores.decodeFixes(keptEdit(state, file, "sheets_fixed").orEmpty()).keys
        val confidence = ArrayList<com.inksheets.core.omr.MeasureConfidence>()
        for (measure in score.measures) {
            if (control.shouldYield()) return score
            if (measure.number !in fixed) confidence += confidenceFor(state, file, measure)
        }
        atomicText(auditPriorityFile(dir), ReadingConfidence.score(confidence).priority.toString())
        return score
    }

    /** Cache replacement is atomic where supported, with the same-directory rename fallback. */
    private fun atomicText(file: File, value: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(value)
        runCatching { java.nio.file.Files.move(temporary.toPath(), file.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        if (file.name.startsWith("looks-") || file.name.startsWith("audit-looks-")) synchronized(looksKnown) {
            looksKnown.keys.removeAll { it.startsWith(file.path + ":") }
        }
        if (file.name.startsWith("audit-specialists-")) synchronized(specialistsKnown) {
            specialistsKnown.keys.removeAll { it.startsWith(file.path + ":") }
        }
    }
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

    /** Stop pressed: the reading ends after the page in hand (what is read is kept) and does not carry on later by itself. */
    @Volatile var stopAsked = false

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
    private const val READER = 17

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
    /** Shadow audit candidates never enter Fix's offered alternatives. */
    private fun auditLooksFile(dir: File, page: Int) = File(dir, "audit-looks-p${page + 1}.json")
    /** Symbol-specific evidence, also shadow-only and versioned by the audit marker. */
    private fun specialistFile(dir: File, page: Int) = File(dir, "audit-specialists-p${page + 1}.json")
    /** One small scheduling summary; full evidence remains in page sidecars. */
    private fun auditPriorityFile(dir: File) = File(dir, "audit-priority-v6.txt")

    /** The second looks read lately, by folder and page. */
    private val looksKnown = LinkedHashMap<String, Map<Int, List<Measure>>>(16, .75f, true)
    private val specialistsKnown = LinkedHashMap<String, Map<Int, MeasureSpecialistEvidence>>(16, .75f, true)

    /**
     * What the second looks at bar [m] of [file] saw - taken as each page was read, so Fix has them
     * at once - or nothing, where none were taken (a PDF that states its notes; a page read before).
     */
    private fun readingsIn(f: File, number: Int): List<Measure> {
        val key = "${f.path}:${f.lastModified()}"
        val byBar = synchronized(looksKnown) { looksKnown[key] } ?: (runCatching { Scores.decode(f.readText()) }.getOrNull()?.measures?.groupBy { it.number } ?: emptyMap())
            .also { value -> synchronized(looksKnown) {
                looksKnown[key] = value
                while (looksKnown.size > 24) looksKnown.remove(looksKnown.keys.first())
            } }
        return byBar[number].orEmpty()
    }

    fun lookedFor(state: SheetsState, file: File, m: Measure): List<Measure> =
        readingsIn(looksFile(folder(state, file), m.page), m.number)

    /** Alternate image passes kept only for confidence measurement and later candidate ranking. */
    fun auditedFor(state: SheetsState, file: File, m: Measure): List<Measure> =
        readingsIn(auditLooksFile(folder(state, file), m.page), m.number)

    fun specialistFor(state: SheetsState, file: File, m: Measure): MeasureSpecialistEvidence? {
        val f = specialistFile(folder(state, file), m.page)
        val key = "${f.path}:${f.lastModified()}"
        val byBar = synchronized(specialistsKnown) { specialistsKnown[key] } ?: runCatching {
            SpecialistEvidenceCodec.decode(f.readText()).associateBy { it.number }
        }.getOrDefault(emptyMap()).also { value -> synchronized(specialistsKnown) {
            specialistsKnown[key] = value
            while (specialistsKnown.size > 24) specialistsKnown.remove(specialistsKnown.keys.first())
        } }
        return byBar[m.number]
    }

    /** Shadow multi-pass evidence for [m]. It is deliberately not used by [Measure.certainty] yet:
     * benchmark calibration must establish safe thresholds before visible colours change.
     */
    fun evidenceFor(state: SheetsState, file: File, m: Measure): MeasureEvidence =
        ReadingEvidence.assess(m, auditedFor(state, file, m)).let { evidence ->
            specialistFor(state, file, m)?.let { evidence.copy(dot = it.dot, rest = it.rest, multiRest = it.multiRest) } ?: evidence
        }

    /** Ordering evidence for background work and a future confidence UI; never changes certainty. */
    fun confidenceFor(state: SheetsState, file: File, m: Measure) =
        ReadingConfidence.measure(m, evidenceFor(state, file, m))

    /** What was read and when it was looked for: the folder looked at again only every few seconds (another device may add pages). */
    private class Known(val score: Score?, val at: Long, val signature: String)
    private val known = LinkedHashMap<String, Known>(16, .75f, true)

    /**
     * The score currently needed by screen code.  This cache is deliberately keyed only by the
     * already-known path: asking for it must not hash a PDF, enumerate a Syncthing folder or decode
     * JSON.  Those operations can take seconds on Android storage and are never page-turn work.
     */
    private val hotKnown = LinkedHashMap<String, Score?>(4, .75f, true)

    /** A memory-only lookup, safe to call while composing or handling a button. */
    fun known(file: File): Score? = synchronized(hotKnown) { hotKnown[file.absolutePath] }

    private fun publish(file: File, score: Score?) = synchronized(hotKnown) {
        hotKnown[file.absolutePath] = score
        // Tabs may keep a few scores warm, but never let a library scan turn this into a second
        // decoded-score library.  Whole-band preparation owns its temporary scores separately.
        while (hotKnown.size > 4) hotKnown.remove(hotKnown.keys.first())
    }

    private val cacheLoader = java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
        Thread(r, "reading-cache").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    /** Load a displayed score from disk without ever making the caller wait on storage. */
    fun preload(state: SheetsState, file: File, onDone: (Score?) -> Unit = {}) {
        synchronized(hotKnown) {
            if (hotKnown.containsKey(file.absolutePath)) {
                val score = hotKnown[file.absolutePath]
                state.platform.onMain { onDone(score) }
                return
            }
        }
        cacheLoader.execute {
            val began = System.currentTimeMillis()
            val score = loadCached(state, file, refresh = true)
            val elapsed = System.currentTimeMillis() - began
            val message = "PERF score cache ${file.name}: ${elapsed}ms off UI, ${score?.measures?.size ?: 0} bars"
            if (elapsed >= 500) state.platform.warn(message) else state.platform.log(message)
            publish(file, score)
            state.platform.onMain { onDone(score) }
        }
    }

    /** The pages of [file] (0-based) whose reading is an earlier reader's, not read again since - offered to be read again. */
    fun olderPages(state: SheetsState, file: File): Set<Int> {
        val own = folder(state, file).listFiles { f -> PAGE_FILE.matches(f.name) }.orEmpty().map { pageOf(it) }.toSet()
        val older = olderFolder(state, file)?.listFiles { f -> PAGE_FILE.matches(f.name) }.orEmpty().map { pageOf(it) }.toSet()
        return older - own
    }

    private fun pageOf(f: File) = f.name.drop(1).removeSuffix(".json").toIntOrNull()?.minus(1) ?: -1

    /** [file]'s notes as far as they have been read - here or on another device; null if none of it has. */
    /** Potentially slow storage access. Call only from a worker. Screen code uses [known]. */
    fun loadCached(state: SheetsState, file: File, refresh: Boolean = true): Score? {
        val own = folder(state, file)
        // This reader's pages, and an earlier reader's for the pages not read again yet - so reading
        // one page again never loses the rest of the part.
        val older = olderFolder(state, file)
        val dir = own
        val now = System.currentTimeMillis()
        val key = own.path + "|" + (older?.path ?: "")
        synchronized(known) { known[key]?.let { if (!refresh || now - it.at < 3000) return it.score } }
        // Only the pages' own files: not a copy a syncing program kept of one two devices wrote at
        // once (Syncthing's "p1.sync-conflict-....json") - every bar of it would be there twice. Those
        // copies are let go.
        val mine = dir.listFiles { f -> PAGE_FILE.matches(f.name) }.orEmpty()
        val minePages = mine.map { pageOf(it) }.toSet()
        val pages: List<File> = mine.toList() + (older?.listFiles { f: File -> PAGE_FILE.matches(f.name) && pageOf(f) !in minePages }?.toList() ?: emptyList())
        dir.listFiles { f -> f.name.contains(".sync-conflict-") }?.forEach { runCatching { it.delete() } }
        val signature = pages.sortedBy { it.path }.joinToString { "${it.path}:${it.length()}:${it.lastModified()}" }
        synchronized(known) { known[key]?.takeIf { it.signature == signature }?.let {
            known[key] = Known(it.score, now, signature)
            return it.score
        } }
        val parts = pages.mapNotNull { f -> runCatching { Scores.decode(f.readText()) }.getOrNull() }
        val score = if (parts.isEmpty()) null else {
            val count = parts.maxOf { it.pages }
            val read = parts.flatMap { it.readPages.orEmpty() }.distinct().sorted()
            val widths = List(count) { i -> parts.firstNotNullOfOrNull { it.pageWidths.getOrNull(i)?.takeIf { w -> w > 0 } } ?: 0 }
            // (Numbered so no bar number comes twice: see Scores.numberedOnce.)
            Score(Scores.numberedOnce(parts.flatMap { it.measures }.sortedWith(compareBy({ it.page }, { it.staff }, { it.box.left }))), count, widths, read.takeIf { it.size < count })
        }
        synchronized(known) {
            known[key] = Known(score, now, signature)
            // Library planning touches many parts in succession. On phone heaps, retaining 24
            // decoded scores defeats the page-level raster budget even though each score is small.
            val limit = if (Workers.roomy) 24 else 6
            while (known.size > limit) known.remove(known.keys.first())
        }
        return score
    }

    /** Test/source compatibility for the old name. Production screen paths intentionally use
     * [known]; this operation may enumerate and decode files. */
    @Deprecated("Potentially blocking; use known on the UI thread or loadCached on a worker")
    fun cached(state: SheetsState, file: File): Score? = loadCached(state, file).also { publish(file, it) }

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
        busy = "Getting ready..."
        stopAsked = false
        stoppedBackground.remove(file.absolutePath)
        queue.submit("foreground:${file.absolutePath}", ReadingQueue.FOREGROUND) {
            if (again) {
                val dir = folder(state, file)
                if (pages == null) dir.listFiles { f -> PAGE_FILE.matches(f.name) }.orEmpty().forEach { it.delete() }
                else pages.forEach { pageFile(dir, it).delete() }
                synchronized(known) { known.keys.removeAll { it.startsWith(dir.path) } }
                synchronized(hotKnown) { hotKnown.remove(file.absolutePath) }
            }
            try {
                readNow(state, file, pages).also {
                    if (stopAsked) {
                        stoppedBackground.add(file.absolutePath)
                        queue.cancelWhere { key -> key.startsWith("background:") && key.endsWith(file.absolutePath) }
                    }
                }
            } catch (error: OutOfMemoryError) { memoryFailure(state, file, "Reading", error) }
        }.whenComplete { score, error ->
            if (error != null) state.platform.log("Reading ${file.name} failed: ${error.message}")
            state.platform.onMain {
                busy = null
                progress = null
                if (score != null) { publish(file, score); shown = file to score }
                onDone(score)
            }
        }
    }

    /** Left in a part's readings while every page of it is being read: a read cut off carries on ([resume]). */
    init {
        // The reader's worker threads give way to the screen between pieces of work.
        Workers.courtesy = {
            com.inkslate.core.RenderGate.pause()
            // Pause between neural-reader chunks as well as pages while writing or playing.
            // An explicit request/cancellation may finish the current page to release its slot.
            // Fix's explicit look-again jobs share this pool. Let the current background
            // page finish so it cannot hold those foreground jobs behind a paused chunk.
            while (automaticPass && !ScoreTools.checking && !backgroundAllowed() && !queue.hasRequestedWork() && !queue.activeCancelled()) {
                try { Thread.sleep(30) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
            }
        }
    }

    private fun wholeMark(dir: File) = File(dir, "whole")

    /**
     * Carry on reading [file] if a reading of every page was started and cut off part way (the
     * app closed) - from the page in front. The look at the folder is off the UI thread.
     */
    fun resume(state: SheetsState, file: File) {
        if (busy != null) return
        Thread({
            Thread.currentThread().priority = Thread.MIN_PRIORITY
            val dir = folder(state, file)
            if (!wholeMark(dir).isFile) return@Thread
            // A file just opened wants its pages drawn first: the carrying on starts after a pause.
            com.inkslate.core.RenderGate.touch(2500)
            com.inkslate.core.RenderGate.pause()
            val c = loadCached(state, file)
            if (c != null && (0 until c.pages).all { c.hasRead(it) }) { wholeMark(dir).delete(); return@Thread }
            state.platform.onMain { if (busy == null && state.currentPath == file.absolutePath) read(state, file) { com.inkslate.core.Perform.marksChanged() } }
        }, "read-resume").apply { isDaemon = true; start() }
    }

    /**
     * Read [file] whole, off the screen's thread, saying nothing on the screen - several at once
     * (the band's parts before the band plays). Kept like any reading.
     */
    fun readQuietly(state: SheetsState, file: File): Score? = try {
        if (queue.isWorkerThread()) readNow(state, file, null, quiet = true)
        else queue.submit("quiet:${file.absolutePath}", ReadingQueue.REQUESTED_QUIET) { work ->
            try { readNow(state, file, null, quiet = true, mayContinue = { !work.shouldYield() }) }
            catch (error: OutOfMemoryError) { memoryFailure(state, file, "Requested reading", error) }
        }.get()
    } catch (error: OutOfMemoryError) {
        memoryFailure(state, file, "Requested reading", error)
    } catch (error: Throwable) {
        // CompletableFuture wraps worker failures; preserve the underlying low-memory recovery.
        val cause = (error as? java.util.concurrent.ExecutionException)?.cause
        if (cause is OutOfMemoryError) memoryFailure(state, file, "Requested reading", cause)
        else { state.platform.log("Reading ${file.name} failed: ${error.message}"); null }
    }

    private fun readNow(state: SheetsState, file: File, only: Set<Int>? = null,
                        /** Read beside others (the band's parts): no word of it on the screen - the caller says how far they all are. */
                        quiet: Boolean = false, mayContinue: () -> Boolean = { true }, pageSaved: () -> Unit = {}): Score? {
        val peek = state.platform.peek(file) ?: return null
        peek.use {
            val dir = folder(state, file)
            // Only explicit whole-part reads leave a foreground-resume marker. Automatic work
            // resumes from its page cache without reopening a foreground progress flow later.
            if (only == null && !quiet) runCatching { dir.mkdirs(); wholeMark(dir).writeText("") }
            // What was read before (here or elsewhere): a whole read carries on where it left off.
            val before = loadCached(state, file)
            // A whole read starts at the page in front (a long book is opened at the study being
            // worked on) and goes on from there, coming back round to the pages before it last.
            val here = if (only == null && state.currentPath == file.absolutePath) state.pageShown.first.coerceIn(0, maxOf(0, peek.pageCount - 1)) else 0
            // (A page an earlier reader read counts as not read: reading the part reads it again.)
            val stale = olderPages(state, file)
            val todo = (only ?: (0 until peek.pageCount).filter { before == null || !before.hasRead(it) || it in stale }.toSet()).filter { it in 0 until peek.pageCount }
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
            fun await(task: java.util.concurrent.FutureTask<Pair<IntArray, Ink>?>): Pair<IntArray, Ink>? = try {
                task.get()
            } catch (error: java.util.concurrent.ExecutionException) {
                val cause = error.cause
                if (cause is OutOfMemoryError) throw cause
                null
            } catch (error: OutOfMemoryError) { throw error }
            fun drawn(p: Int): Pair<IntArray, Ink>? {
                ahead?.takeIf { it.first == p }?.let {
                    ahead = null
                    // A non-memory render failure can fall back to the ordinary path. OOM is
                    // propagated and never retried while the same page allocations are resident.
                    return await(it.second) ?: pageInk(peek, p)
                }
                return pageInk(peek, p)
            }
            try { for ((i, p) in todo.withIndex()) {
                if (!quiet && stopAsked) { runCatching { wholeMark(dir).delete() }; break }
                if (!mayContinue()) break
                // Between pages the screen goes first: a page just turned to or opened is drawn before the next is read.
                com.inkslate.core.RenderGate.pause()
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
                todo.getOrNull(i + 1)?.takeIf { !quiet }?.let { next ->
                    if (roomForSecondPage()) {
                        val task = java.util.concurrent.FutureTask { pageInk(peek, next) }
                        ahead = next to task
                        Thread(task, "read-ahead").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
                    }
                }
                // A PDF that states its notes is read from them, exactly; a scan by the trained reader.
                val printed = try { com.inkslate.core.RenderGate.reading { synchronized(peek) { peek.printed(p) } } }
                catch (error: OutOfMemoryError) { throw error }
                catch (_: Throwable) { null }
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
                if (net != null && unsure.isNotEmpty()) try {
                    if (!quiet) state.platform.onMain { busy = "Looking again at ${unsure.size} unsure bar${if (unsure.size == 1) "" else "s"} on page ${p + 1}..." }
                    val looked = Workers.map(unsure.groupBy { it.staff }.entries.toList()) { (staff, bars) ->
                        val first = reading.measures.first { it.staff == staff }
                        Recognizer().lookAgainStaff(ink, grey, net, bars, first)
                    }.flatMap { it.values.flatten() }
                    dir.mkdirs()
                    atomicText(looksFile(dir, p), Scores.encode(Score(looked, peek.pageCount, List(peek.pageCount) { if (it == p) ink.width else 0 }, listOf(p))))
                } catch (error: OutOfMemoryError) {
                    // Never swallow OOM and continue into persistence with the heap still exhausted.
                    throw error
                } catch (error: Throwable) {
                    state.platform.log("Looking again at page ${p + 1} of ${file.name} failed: ${error.message}")
                }
                pageTimes += System.currentTimeMillis() - pageStarted
                // Kept as soon as read: a reading stopped part way carries on from here, and the
                // pages read so far reach the other devices.
                runCatching {
                    dir.mkdirs()
                    val one = Score(reading.measures, peek.pageCount, List(peek.pageCount) { if (it == p) ink.width else 0 }, listOf(p))
                    atomicText(pageFile(dir, p), Scores.encode(one))
                }
                synchronized(known) { known.keys.removeAll { it.startsWith(dir.path) } }
                synchronized(hotKnown) { hotKnown.remove(file.absolutePath) }
                pageSaved()
            } } finally {
                // The page ahead finished drawing before the file is closed under it.
                ahead?.second?.let { await(it) }
            }
            val score = loadCached(state, file) ?: Score(measures, peek.pageCount, widths, todo)
            if (only == null && (0 until peek.pageCount).all { score.hasRead(it) }) wholeMark(dir).delete()
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
            val found = try {
                val peek = state.platform.peek(file)
                if (peek == null) emptyList() else peek.use {
                    val net = Net.shipped
                    val printed = try { peek.printed(m.page) }
                    catch (error: OutOfMemoryError) { throw error }
                    catch (_: Throwable) { null }
                    if (net == null || printed != null) return@use emptyList()
                    val img = drawnPage(state, file, m.page, width) ?: return@use emptyList()
                    val px = IntArray(img.width * img.height).also { img.readPixels(it) }
                    Recognizer().lookAgain(Ink.fromArgb(img.width, img.height, px), com.inksheets.core.omr.Strips.grey(px), net, m, deeper)
                }
            } catch (error: OutOfMemoryError) {
                memoryFailure(state, file, "Looking again", error)
                emptyList()
            } catch (error: Throwable) {
                state.platform.log("Looking again at bar ${m.number} of ${file.name} failed: ${error.message}")
                emptyList()
            }
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
            val picture = try {
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
            } catch (error: OutOfMemoryError) {
                memoryFailure(state, file, "Drawing bar picture", error)
                null
            } catch (_: Throwable) { null }
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
                  noneCount: Int = 0,
                  /** What the player identified as wrong before choosing this correction. */
                  focus: String? = null) {
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
                    if (focus != null) append(",\"focus\":").append(kotlinx.serialization.json.JsonPrimitive(focus))
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
    fun recordSkip(state: SheetsState, file: File, m: com.inksheets.core.omr.Measure, width: Int,
                   rejected: List<List<com.inksheets.core.omr.Event>>, noneCount: Int, focus: String? = null) {
        val root = state.root ?: return
        val rel = state.relative(file) ?: return
        looker.execute {
            runCatching {
                val dir = File(root, "${com.inksheets.core.LibraryScan.TRAINING}/Fixes").apply { mkdirs() }
                val line = buildString {
                    append("{\"kind\":\"skip\",\"none\":").append(noneCount)
                    if (focus != null) append(",\"focus\":").append(kotlinx.serialization.json.JsonPrimitive(focus))
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
    private val drawn = LinkedHashMap<String, androidx.compose.ui.graphics.ImageBitmap>(4, .75f, true)

    /** Speculative page copies are an optimisation only. Keep them out unless both the device's
     * total heap and its headroom right now are comfortably above a complete page working set.
     */
    private fun roomForSecondPage(): Boolean {
        if (!Workers.roomy) return false
        val rt = Runtime.getRuntime()
        val available = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        return rt.maxMemory() >= (768L shl 20) && available > (384L shl 20)
    }

    private fun drawnPage(state: SheetsState, file: File, page: Int, width: Int): androidx.compose.ui.graphics.ImageBitmap? {
        val key = "${file.absolutePath}|${file.lastModified()}|$page|$width"
        synchronized(drawn) { drawn[key]?.let { return it } }
        val img = state.platform.peek(file)?.use { it.render(page, width) } ?: return null
        synchronized(drawn) {
            drawn[key] = img
            val maxHeap = Runtime.getRuntime().maxMemory()
            val keep = if (roomForSecondPage()) 2 else 1
            val byteLimit = (maxHeap / 16).coerceIn(16L shl 20, 128L shl 20)
            fun bytesHeld() = drawn.values.sumOf { it.width.toLong() * it.height * 4L }
            while (drawn.isNotEmpty() && (drawn.size > keep || bytesHeld() > byteLimit)) drawn.remove(drawn.keys.first())
        }
        return img
    }

    /** Page [page] of [file] drawn ahead (quietly, after whatever is being looked at), for the next bar in Fix. */
    fun drawAhead(state: SheetsState, file: File, page: Int, width: Int) {
        if (!roomForSecondPage()) return
        looker.execute {
            try { drawnPage(state, file, page, width) }
            catch (error: OutOfMemoryError) { memoryFailure(state, file, "Drawing ahead", error) }
            catch (_: Throwable) { }
        }
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
        val initialPixelBudget = peek.readingPixelBudget
        if (initialPixelBudget <= 0) return null
        var probe: Pair<IntArray, Ink>? = inkOf(peek, page, 1600) ?: return null
        var probeInk: Ink? = probe!!.second
        val space = Recognizer().metrics(probeInk!!)?.second
        val wanted = if (space == null || space <= 0f) 1600 else (1600 * 18f / space).toInt().coerceIn(1000, 5000)
        val probeWidth = probeInk.width
        val probeHeight = probeInk.height
        if (wanted == probeWidth) return probe
        // Do not retain the probe's greyscale and ink while allocating the full reading.
        probe = null
        probeInk = null
        // Rendering the probe can itself populate PDF/bitmap caches. Re-read live headroom before
        // the larger allocation; a budget measured before the probe is not permission to overrun
        // memory which the renderer or foreground acquired in the meantime.
        val pixelBudget = minOf(initialPixelBudget, peek.readingPixelBudget)
        if (pixelBudget <= 0) return null
        val maxWidth = kotlin.math.sqrt(pixelBudget.toDouble() * probeWidth / probeHeight.coerceAtLeast(1)).toInt()
        if (maxWidth < 1000) return null
        val width = wanted.coerceAtMost(maxWidth)
        return inkOf(peek, page, width)
    }

    private fun inkOf(peek: PagePeek, page: Int, width: Int): Pair<IntArray, Ink>? {
        // The reader's draw: after any page the screen wants, one at a time (see RenderGate).
        val raster = run {
            val img = com.inkslate.core.RenderGate.reading { synchronized(peek) { peek.render(page, width) } } ?: return null
            val px = IntArray(img.width * img.height)
            img.readPixels(px)
            Triple(img.width, img.height, com.inksheets.core.omr.Strips.grey(px))
        }
        // Build the monochrome raster from the greys already returned to recognition. Converting
        // from ARGB again used to allocate a second full-page greyscale array.
        return raster.third to Ink.fromGrey(raster.first, raster.second, raster.third)
    }
}
