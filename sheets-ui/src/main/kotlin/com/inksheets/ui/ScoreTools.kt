package com.inksheets.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.inkslate.core.PageMark
import com.inkslate.core.Perform
import com.inksheets.core.omr.Engraver
import com.inksheets.core.omr.Measure
import com.inksheets.core.omr.Midi
import com.inksheets.core.omr.MusicGlyphs
import com.inksheets.core.omr.Score
import com.inksheets.core.omr.ScorePlayer
import com.inksheets.core.omr.Synth
import java.io.File

/**
 * The music tools (with "Read the music" on in Settings), working on the part in front from the
 * notes read off it:
 *
 *  - the clean reading laid over the print, faint, where it can be checked against it;
 *  - the clean-up pen: bars drawn over are shown clean, the print under them hidden - undone
 *    by drawing over them again, or Undo;
 *  - bars selected by a press and drag, played at the metronome's tempo by an instrument like
 *    the part's (or another's), round and round if wanted, a little faster each time;
 *  - going straight to a bar by its number.
 *
 * Everything is drawn through [Perform.pageMarks], over the page and under the handwriting, and
 * nothing is laid over the music but the reading itself and a light tint for a selection.
 */
internal object ScoreTools {
    enum class Tool { NONE, SELECT, CLEAN }

    /** The tools' lane is out. */
    var open by mutableStateOf(false)
    var tool by mutableStateOf(Tool.NONE)
        private set

    /** The clean reading laid faintly over the print. */
    var underlay by mutableStateOf(false)
        private set

    /** Bars chosen, by number (first to last); null for none. */
    var selection by mutableStateOf<IntRange?>(null)
        private set

    /** Played round and round; and each time faster, from [rampFrom] percent of the tempo up to it. */
    var loop by mutableStateOf(false)
    var ramp by mutableStateOf(false)
    var rampFrom by mutableIntStateOf(70)
    var rampStep by mutableIntStateOf(4)

    /** Heard as this instrument (an id), rather than the part's own; null for the part's. */
    var soundAs by mutableStateOf<String?>(null)

    /** Playing now: which bar, how fast, which time round. */
    var playing by mutableStateOf<Triple<Int, Int, Int>?>(null)
        private set

    /** A bar just gone to, lit for a moment. */
    private var found: Pair<Int, Long>? = null

    /** What was said last (a bar not found, nothing read yet). */
    var said by mutableStateOf<String?>(null)

    /** Bumped at every change, so the pages are drawn again and the marks made afresh. */
    private var version = 0
    private val marksCache = HashMap<Pair<String, Int>, Pair<Int, List<PageMark>>>()

    private fun changed() {
        version++
        Perform.marksChanged()
    }

    /** Something drawn on the page changed elsewhere (bars that sounded off). */
    fun marksMoved() = changed()

    // ---- which part, and its notes ----------------------------------------------------------

    private var state: SheetsState? = null

    fun install(s: SheetsState) {
        state = s
        Perform.pageMarks = { path, page, w, h -> marks(path, page, w, h) }
        Perform.musicGesture = { path, page, pts, done -> gesture(path, page, pts, done) }
    }

    /** Where a part's notes come from; a test gives its own. */
    internal var scoreSource: ((String) -> Score?)? = null

    private fun scoreOf(path: String): Score? = scoreSource?.invoke(path) ?: state?.let { Transcriber.cached(it, File(path)) }

    fun scoreHere(s: SheetsState): Score? = s.currentPath?.let { scoreOf(it) }

    // ---- the tools -----------------------------------------------------------------------------

    fun choose(t: Tool) {
        tool = if (tool == t) Tool.NONE else t
        Perform.musicTool = tool != Tool.NONE
    }

    fun showUnderlay(on: Boolean) { underlay = on; changed() }

    fun clearSelection() { selection = null; changed() }

    /** Choose bars [range] (a test, or going to a passage). */
    internal fun select(range: IntRange?) { selection = range; changed() }

    /** Show bars [numbers] of [path] cleaned up (a test). */
    internal fun cleanUp(path: String, numbers: Collection<Int>) { cleanedIn(path) += numbers; changed() }

    fun close(s: SheetsState) {
        stop(s)
        open = false
        tool = Tool.NONE
        Perform.musicTool = false
        underlay = false
        selection = null
        changed()
    }

    // ---- bars cleaned up, kept on this device by part ---------------------------------------

    private val cleaned = HashMap<String, MutableSet<Int>>()
    private val undo = ArrayList<Pair<String, Set<Int>>>()

    private fun keyOf(path: String) = "sheets_clean:" + (state?.relative(File(path)) ?: path)

    private fun cleanedIn(path: String): MutableSet<Int> = cleaned.getOrPut(path) {
        state?.platform?.pref(keyOf(path))?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toMutableSet() ?: HashSet()
    }

    private fun saveCleaned(path: String) {
        val set = cleanedIn(path)
        state?.platform?.setPref(keyOf(path), if (set.isEmpty()) null else set.sorted().joinToString(","))
    }

    fun canUndo(s: SheetsState) = undo.any { it.first == s.currentPath }

    fun undoClean(s: SheetsState) {
        val path = s.currentPath ?: return
        val i = undo.indexOfLast { it.first == path }
        if (i < 0) return
        val (_, before) = undo.removeAt(i)
        cleaned[path] = before.toMutableSet()
        saveCleaned(path)
        changed()
    }

    fun cleanCount(s: SheetsState) = s.currentPath?.let { cleanedIn(it).size } ?: 0

    // ---- presses on the page ---------------------------------------------------------------

    private var pressStart: Int? = null
    private var cleaning: Boolean? = null   // true adding, false taking away
    private var before: Set<Int>? = null
    private var pressed: IntRange? = null

    /** The bar under ([x], [y]) - page points - on [page]; null when none. */
    private fun barAt(score: Score, page: Int, x: Float, y: Float, k: Float): Measure? {
        val px = x / k; val py = y / k
        return score.measures.filter { it.page == page }.firstOrNull { m ->
            px >= m.box.left && px <= m.box.right && py >= m.box.top - m.space * 3.5f && py <= m.box.bottom + m.space * 3.5f
        }
    }

    private fun scaleOf(score: Score, page: Int, width: Float): Float? {
        val px = score.pageWidths.getOrNull(page) ?: return null
        return if (px <= 0) null else width / px
    }

    /** Page widths in points, as the editor last asked for the marks: for turning a press into bars. */
    private val pageWidth = HashMap<Pair<String, Int>, Float>()

    private fun gesture(path: String, page: Int, pts: FloatArray, done: Boolean) {
        val score = scoreOf(path) ?: run { said = "Read the music first"; return }
        val k = scaleOf(score, page, pageWidth[path to page] ?: return) ?: return
        val bars = (0 until pts.size / 2).mapNotNull { i -> barAt(score, page, pts[2 * i], pts[2 * i + 1], k)?.number }
        when (tool) {
            Tool.SELECT -> {
                if (pressStart == null) pressStart = bars.firstOrNull()
                val a = pressStart ?: return
                val b = bars.lastOrNull() ?: a
                val range = minOf(a, b)..maxOf(a, b)
                if (done) {
                    // A tap on the one bar chosen lets it go.
                    selection = if (pressed == null && selection == range && range.first == range.last) null else range
                    pressStart = null; pressed = null
                } else {
                    if (bars.size > 1) pressed = range
                    selection = range
                }
                changed()
            }
            Tool.CLEAN -> {
                val set = cleanedIn(path)
                if (cleaning == null) {
                    val first = bars.firstOrNull() ?: return
                    before = set.toSet()
                    cleaning = first !in set
                }
                val adding = cleaning ?: return
                val touched = bars.flatMap { n -> score.measures.first { it.number == n }.let { m -> (m.number until m.number + m.bars) } }
                if (adding) set += touched else set -= touched.toSet()
                if (done) {
                    before?.let { if (it != set) undo += path to it }
                    before = null; cleaning = null
                    saveCleaned(path)
                }
                changed()
            }
            Tool.NONE -> Unit
        }
    }

    // ---- what is drawn on the page -----------------------------------------------------------

    private const val INK = 0xFF000000.toInt()
    private const val PAPER = 0xFFFFFFFF.toInt()
    private const val UNDER = 0x8C1565C0.toInt()     // the reading over the print: blue, see-through
    private const val DOUBT = 0xD0E65100.toInt()     // a bar that may be read wrong
    private const val CHOSEN = 0x2E1E88E5            // a selection's tint
    private const val CHOSEN_EDGE = 0xCC1E88E5.toInt()
    private const val NOW = 0x4043A047               // the bar playing
    private const val FOUND = 0x55FFB300             // the bar gone to
    private const val OFF = 0xE0D32F2F.toInt()       // a bar that sounded off

    internal fun marks(path: String, page: Int, width: Float, height: Float): List<PageMark>? {
        pageWidth[path to page] = width
        val score = scoreOf(path) ?: return null
        val clean = cleanedIn(path)
        val live = playing?.first
        val flash = found?.takeIf { System.currentTimeMillis() - it.second < 2_500 }?.first
        val off = Listener.offBars.toSet()
        if (!underlay && clean.isEmpty() && selection == null && live == null && flash == null && off.isEmpty()) return null
        val key = path to page
        marksCache[key]?.let { (v, m) -> if (v == version) return m }
        val k = scaleOf(score, page, width) ?: return null
        val out = ArrayList<PageMark>()
        for (m in score.measures.filter { it.page == page }) {
            val sp = m.space * k
            val left = m.box.left * k; val right = m.box.right * k
            val top = m.box.top * k; val bottom = m.box.bottom * k
            val numbers = m.number until m.number + m.bars
            val sel = selection
            if (sel != null && numbers.any { it in sel }) {
                out += PageMark.rect(left, top - sp * 2f, right, bottom + sp * 2f, CHOSEN)
                out += PageMark.line(left, top - sp * 2.4f, right, top - sp * 2.4f, sp * 0.3f, CHOSEN_EDGE)
            }
            if (live != null && live in numbers) out += PageMark.rect(left, top - sp * 2f, right, bottom + sp * 2f, NOW)
            if (flash != null && flash in numbers) out += PageMark.rect(left, top - sp * 2.5f, right, bottom + sp * 2.5f, FOUND)
            // A bar that sounded off when practised: a red bar over it, above the staff - not over the notes.
            if (numbers.any { it in off }) out += PageMark.rect(left + sp * 0.3f, top - sp * 3.4f, right - sp * 0.3f, top - sp * 2.9f, OFF)
            when {
                numbers.first in clean -> {
                    // Cleaned up: the print hidden, the reading in its place.
                    out += PageMark.rect(left + sp * 0.15f, top - sp * 3f, right - sp * 0.15f, bottom + sp * 3f, PAPER)
                    out += engraved(m, left, top, sp, INK)
                }
                underlay -> {
                    out += engraved(m, left, top, sp, UNDER)
                    if (!m.sure) {
                        // A bar that may be read wrong: a small mark above it, not over the notes.
                        val cx = (left + right) / 2
                        out += PageMark(PageMark.Kind.FILL, listOf(floatArrayOf(cx - sp * 0.5f, top - sp * 3.2f, cx + sp * 0.5f, top - sp * 3.2f, cx, top - sp * 2.4f)), DOUBT)
                    }
                }
            }
        }
        marksCache[key] = version to out
        return out
    }

    /** [m] drawn cleanly where it is, as shapes on the page: [x], [top] its left and top line, [sp] a staff space. */
    private fun engraved(m: Measure, x: Float, top: Float, sp: Float, color: Int): List<PageMark> {
        if (m.bars > 1) return emptyList()
        val d = Engraver.aligned(m)
        val out = ArrayList<PageMark>()
        for (mark in d.marks) when (mark) {
            is Engraver.Stroke -> {
                // The staff lines are the print's own: only drawn where the bar is cleaned (opaque).
                if (mark.y1 == mark.y2 && mark.x2 - mark.x1 >= d.width - 0.01f && color != INK) continue
                out += PageMark.line(x + mark.x1 * sp, top + mark.y1 * sp, x + mark.x2 * sp, top + mark.y2 * sp, (mark.w * sp).coerceAtLeast(0.4f), color)
            }
            is Engraver.Symbol -> out += PageMark(PageMark.Kind.FILL, MusicGlyphs[mark.name].polygons(sp, x + mark.x * sp, top + mark.y * sp), color)
            is Engraver.Slab -> out += PageMark(PageMark.Kind.FILL, listOf(FloatArray(8) { i -> if (i % 2 == 0) x + mark.points[i] * sp else top + mark.points[i] * sp }), color)
        }
        return out
    }

    // ---- going to a bar -------------------------------------------------------------------------

    /** Show bar [n] of the part in front, lit a moment; false when there is no such bar. */
    fun goTo(s: SheetsState, n: Int): Boolean {
        val path = s.currentPath ?: return false
        val score = scoreOf(path) ?: run { said = "Read the music first"; return false }
        val m = score.measures.firstOrNull { n >= it.number && n < it.number + it.bars } ?: run { said = "No bar $n in this part"; return false }
        Perform.jumpTo?.invoke(path, m.page)
        found = n to System.currentTimeMillis()
        said = null
        changed()
        // The light goes after a moment.
        java.util.Timer("bar-found", true).schedule(object : java.util.TimerTask() { override fun run() = s.platform.onMain { changed() } }, 2_600L)
        return true
    }

    // ---- playing bars ----------------------------------------------------------------------------

    private var player: ScorePlayer? = null
    private var watcher: java.util.Timer? = null
    private const val WHO = "score"

    val isPlaying get() = playing != null

    /** The part's instrument: its id and how far it is written above where it sounds. */
    private fun instrumentOf(s: SheetsState): Pair<String?, Int> {
        val id = s.partShown()?.instrument?.let { com.inksheets.core.PartChoice.seat(it).first }
        return id to (id?.let { com.inksheets.core.Instruments.byId[it]?.transpose } ?: 0)
    }

    /**
     * Play the bars chosen - or, with none, from the page in front to the end - at the metronome's
     * tempo, as the part's instrument sounds (or [soundAs]).
     */
    fun play(s: SheetsState) {
        stop(s)
        val path = s.currentPath ?: return
        val score = scoreOf(path) ?: run { said = "Read the music first"; return }
        val rate = Sound.rate(s).takeIf { it > 0 } ?: run { said = "No sound output here"; return }
        val (id, transpose) = instrumentOf(s)
        val range = selection ?: run {
            val first = score.measures.firstOrNull { it.page >= s.pageShown.first }?.number ?: 1
            first..(score.measures.lastOrNull()?.let { it.number + it.bars - 1 } ?: first)
        }
        val tempo = SharedMetronome.bpm
        val start = if (ramp && loop) tempo * rampFrom / 100.0 else tempo
        val sounding = soundAs ?: id
        // From the page on: as it is played, repeats and all. A passage chosen: straight through, for practising it.
        val source = if (selection == null) com.inksheets.core.omr.PlayOrder.unrolled(score) else score
        val p = ScorePlayer(Synth(rate), source, range.first, range.last, start, transpose, Synth.patchFor(Midi.program(sounding)),
            loop = loop, rampTo = if (ramp && loop) tempo else null, rampStep = rampStep.toDouble())
        player = p
        playing = Triple(range.first, start.toInt(), 0)
        Sound.play(s, WHO) { buf -> p.fill(buf) }
        var lastBar = -1
        watcher = java.util.Timer("score-play", true).apply {
            schedule(object : java.util.TimerTask() {
                override fun run() {
                    val now = Triple(p.bar, p.bpm.toInt(), p.round)
                    s.platform.onMain {
                        if (player !== p) return@onMain
                        if (p.finished) { stop(s); return@onMain }
                        if (playing != now) { playing = now; changed() }
                        // The page follows the music.
                        if (now.first != lastBar) {
                            lastBar = now.first
                            score.measures.firstOrNull { it.number == now.first }?.let { m -> if (m.page != s.pageShown.first) Perform.jumpTo?.invoke(path, m.page) }
                        }
                    }
                }
            }, 100L, 100L)
        }
    }

    // ---- the band without you ------------------------------------------------------------------

    /** Play the other parts, not this one. */
    var band by mutableStateOf(false)

    /** Reading the band's parts: "3 of 11" - null when not. */
    var bandReading by mutableStateOf<String?>(null)
        private set

    /** The song's other parts that have files of their own here: (part, file). */
    private fun otherParts(s: SheetsState): List<Pair<com.inksheets.core.Part, File>> {
        val song = s.current ?: return emptyList()
        val mine = s.partShown()
        return song.parts.filter { it.id != mine?.id && it.firstPage == null && it.lastPage == null && !(it.label ?: "").contains("score", true) &&
            it.instrument?.contains("score", true) != true }
            .mapNotNull { p -> s.fileOf(p.file)?.takeIf { f -> f.isFile && f.absolutePath != s.currentPath }?.let { p to it } }
            .distinctBy { it.second.absolutePath }
    }

    /** Read whichever of the band's parts are not read yet, one after another, then [then]. */
    private fun readBand(s: SheetsState, then: () -> Unit) {
        val todo = otherParts(s).filter { Transcriber.cached(s, it.second) == null }
        if (todo.isEmpty()) { bandReading = null; then(); return }
        val total = otherParts(s).size
        fun next(i: Int) {
            if (i >= todo.size) { bandReading = null; then(); return }
            bandReading = "${total - todo.size + i + 1} of $total"
            Transcriber.read(s, todo[i].second) { next(i + 1) }
        }
        next(0)
    }

    /** The band - every other part read - from the bars chosen or the page in front, without this part. */
    fun playBand(s: SheetsState) {
        stop(s)
        val path = s.currentPath ?: return
        val mine = scoreOf(path) ?: run { said = "Read the music first"; return }
        readBand(s) {
            val voices = otherParts(s).mapNotNull { (p, f) ->
                val sc = Transcriber.cached(s, f) ?: return@mapNotNull null
                val id = p.instrument?.let { com.inksheets.core.PartChoice.seat(it).first }
                val tr = id?.let { com.inksheets.core.Instruments.byId[it]?.transpose } ?: 0
                com.inksheets.core.omr.EnsemblePlayer.Voice(sc, tr, Synth.patchFor(Midi.program(id)))
            }
            if (voices.isEmpty()) { said = "No other parts of this song here to play"; return@readBand }
            val rate = Sound.rate(s).takeIf { it > 0 } ?: run { said = "No sound output here"; return@readBand }
            val range = selection ?: run {
                val first = mine.measures.firstOrNull { it.page >= s.pageShown.first }?.number ?: 1
                first..(mine.measures.lastOrNull()?.let { it.number + it.bars - 1 } ?: first)
            }
            val p = com.inksheets.core.omr.EnsemblePlayer(Synth(rate), mine, voices, range.first, range.last, SharedMetronome.bpm)
            said = "The band: ${voices.size} parts"
            playing = Triple(range.first, SharedMetronome.bpm.toInt(), 0)
            ensemble = p
            Sound.play(s, WHO) { buf -> p.fill(buf) }
            var lastBar = -1
            watcher = java.util.Timer("band-play", true).apply {
                schedule(object : java.util.TimerTask() {
                    override fun run() {
                        s.platform.onMain {
                            if (ensemble !== p) return@onMain
                            if (p.finished) { stop(s); return@onMain }
                            val now = Triple(p.bar, SharedMetronome.bpm.toInt(), 0)
                            if (playing != now) { playing = now; changed() }
                            if (now.first != lastBar) {
                                lastBar = now.first
                                mine.measures.firstOrNull { now.first >= it.number && now.first < it.number + it.bars }?.let { m -> if (m.page != s.pageShown.first) Perform.jumpTo?.invoke(path, m.page) }
                            }
                        }
                    }
                }, 100L, 100L)
            }
        }
    }

    private var ensemble: com.inksheets.core.omr.EnsemblePlayer? = null

    fun stop(s: SheetsState) {
        ensemble?.let { ensemble = null; Sound.stop(WHO) }
        watcher?.cancel(); watcher = null
        if (player != null) Sound.stop(WHO)
        player = null
        if (playing != null) { playing = null; changed() }
    }
}
