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
import com.inksheets.core.omr.Note
import com.inksheets.core.omr.Rest
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

    /** For pictures that tell the layers apart: what a cleaned bar keeps as printed drawn in this colour (tests). */
    internal var keptColor: Int? = null

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

    /** A part's notes as read - with the bars put right by hand in place of their readings. */
    private fun scoreOf(path: String): Score? =
        (scoreSource?.invoke(path) ?: state?.let { Transcriber.cached(it, File(path)) })?.let { com.inksheets.core.omr.Scores.withFixes(it, fixesOf(path)) }

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

    /** Every bar of [path] shown as printed again (for tests: not kept). */
    internal fun undoAllClean(path: String) { cleaned.remove(path); changed() }

    fun close(s: SheetsState) {
        stop(s)
        open = false
        tool = Tool.NONE
        Perform.musicTool = false
        underlay = false
        selection = null
        changed()
    }

    // ---- bars put right by hand: offered readings of the doubtful ones, one picked -----------

    /** Bars put right, by part: bar number to what it is. Kept with the part's settings (on this device). */
    private val fixes = HashMap<String, MutableMap<Int, List<com.inksheets.core.omr.Event>>>()
    private fun fixKey(path: String) = "sheets_fixed:" + (state?.relative(File(path)) ?: path)
    private fun fixesOf(path: String): MutableMap<Int, List<com.inksheets.core.omr.Event>> = fixes.getOrPut(path) {
        state?.platform?.pref(fixKey(path))?.let { com.inksheets.core.omr.Scores.decodeFixes(it).toMutableMap() } ?: HashMap()
    }

    /** Bar [number] of [path] is [events]: kept, and no longer in doubt. */
    fun fix(path: String, number: Int, events: List<com.inksheets.core.omr.Event>) {
        val f = fixesOf(path)
        f[number] = events
        state?.platform?.setPref(fixKey(path), com.inksheets.core.omr.Scores.encodeFixes(f))
        changed()
    }

    /**
     * Going through the bars in doubt: [checkBars] their numbers, [checkAt] which one is up,
     * [offered] the readings offered for it (best first), [rejected] those turned down.
     */
    var checking by mutableStateOf(false)
        private set
    var checkBars by mutableStateOf<List<Int>>(emptyList())
        private set
    var checkAt by mutableIntStateOf(0)
        private set
    var offered by mutableStateOf<List<com.inksheets.core.omr.BarChoices.Choice>>(emptyList())
        private set
    private var rejected = ArrayList<List<com.inksheets.core.omr.Event>>()
    /** Looked deeper for this bar already (the first readings turned down). */
    var askedAgain by mutableStateOf(false)
        private set
    /** The bar up is being looked at again (other looks at its staff), its readings to join those offered. */
    var looking by mutableStateOf(false)
        private set
    /** What the other looks at the bar up saw. */
    private var looked: List<com.inksheets.core.omr.Measure> = emptyList()
    /** The bar up as printed, cut from its page (null until it is ready). */
    var barPicture by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
        private set
    /** Changed whenever another bar comes up (or the check ends): a look for an earlier one is let go. */
    private var lookToken = 0
    /** Looked further at the bar up already (once is enough: the reader sees the same each time). */
    private var lookedDeeper = false

    /** The bar up looked at again in the background; the readings offered made again with what was seen, if it is still up. */
    private fun lookAt(s: SheetsState, m: com.inksheets.core.omr.Measure, deeper: Boolean) {
        val path = s.currentPath ?: return
        val width = scoreHere(s)?.pageWidths?.getOrNull(m.page)?.takeIf { it > 0 } ?: return
        val number = m.number
        val token = lookToken
        if (deeper) lookedDeeper = true
        looking = true
        Transcriber.lookAgain(s, File(path), m, width, deeper, wanted = { token == lookToken }) { seen ->
            if (token != lookToken || !checking || barUp(s)?.number != number) return@lookAgain
            looking = false
            looked = looked + seen
            val now = barUp(s) ?: return@lookAgain
            // As far as the user has gone with this bar now - not as when this look began.
            offered = com.inksheets.core.omr.BarChoices.of(now, 3, rejected = rejected, deeper = askedAgain, looked = looked)
            // Nothing left to offer, even looked at further: left in doubt, on to the next.
            if (offered.isEmpty()) { said = "No other reading of bar $number - left in doubt"; next(s); return@lookAgain }
            changed()
        }
    }

    /** The bar up now. */
    fun barUp(s: SheetsState): com.inksheets.core.omr.Measure? {
        val n = checkBars.getOrNull(checkAt) ?: return null
        return scoreHere(s)?.measures?.firstOrNull { it.number == n }
    }

    /** The bars in doubt in the part in front, to go through; false when there are none. */
    fun startCheck(s: SheetsState): Boolean {
        val score = scoreHere(s) ?: return false
        // Where you are first: on the page in front, the bars you asked to clean (framed until
        // checked), then its others; then the pages after it, and those before it last.
        val here = s.pageShown.first
        val clean = s.currentPath?.let { cleanedIn(it) }.orEmpty()
        checkBars = score.measures.filter { !it.sure && it.bars == 1 }
            .sortedWith(compareBy({ it.page < here }, { it.page != here }, { it.page }, { it.number !in clean }, { it.number }))
            .map { it.number }
        if (checkBars.isEmpty()) { said = "No bars in doubt"; return false }
        checking = true
        checkAt = 0
        showBar(s)
        return true
    }

    private fun showBar(s: SheetsState) {
        rejected = ArrayList(); askedAgain = false; looked = emptyList(); looking = false; lookedDeeper = false; lookToken++
        barPicture = null; editing = null
        val m = barUp(s) ?: run { endCheck(); return }
        offered = com.inksheets.core.omr.BarChoices.of(m, 3)
        // Its picture first (quick), then the looks again.
        val path = s.currentPath
        val width = scoreHere(s)?.pageWidths?.getOrNull(m.page)?.takeIf { it > 0 }
        if (path != null && width != null) {
            val token = lookToken
            Transcriber.barPicture(s, File(path), m, width, wanted = { token == lookToken }) { pic -> if (token == lookToken) barPicture = pic }
        }
        lookAt(s, m, deeper = false)
        goTo(s, m.number)
        selection = m.number..m.number
        changed()
    }

    /** [choice] is what bar up is: kept, and on to the next. */
    fun pick(s: SheetsState, choice: com.inksheets.core.omr.BarChoices.Choice) {
        val path = s.currentPath ?: return
        val m = barUp(s) ?: return
        fix(path, m.number, choice.events)
        // What it really is, kept for teaching the reader (the bar as it was read: its place on the page).
        scoreHere(s)?.pageWidths?.getOrNull(m.page)?.takeIf { it > 0 }?.let { w ->
            val read = Transcriber.cached(s, File(path))?.measures?.firstOrNull { it.number == m.number } ?: m
            Transcriber.recordFix(s, File(path), read, w, choice.events)
        }
        next(s)
    }

    /** The bar up being put right by hand (none of the readings is it): its events as they stand, or null. */
    var editing by mutableStateOf<List<com.inksheets.core.omr.Event>?>(null)
        private set
    /** Which of [editing]'s events is chosen to change. */
    var editAt by mutableStateOf(0)

    /** Put bar up right by hand, starting from [choice] - the reading nearest what is printed. */
    fun startEdit(choice: com.inksheets.core.omr.BarChoices.Choice) { editing = choice.events; editAt = 0 }

    /** One change made by hand: [change] gives the events anew; the event chosen kept in range. */
    fun edit(change: (List<com.inksheets.core.omr.Event>) -> List<com.inksheets.core.omr.Event>) {
        val now = editing ?: return
        val next = change(now)
        editing = next
        editAt = editAt.coerceIn(0, maxOf(0, next.size - 1))
    }

    fun cancelEdit() { editing = null }

    /** The bar as put right by hand is what it is: kept (and taught), and on to the next. */
    fun finishEdit(s: SheetsState) {
        val events = editing ?: return
        editing = null
        pick(s, com.inksheets.core.omr.BarChoices.Choice(events, listOf("Put right by hand"), 0f))
    }

    /** None of those: others, looked for further; when there are none left, on to the next bar. */
    fun noneOfThese(s: SheetsState) {
        val m = barUp(s) ?: return
        rejected += offered.map { it.events }
        val more = com.inksheets.core.omr.BarChoices.of(m, 3, rejected = rejected, deeper = true, looked = looked)
        askedAgain = true
        // Looked at further still (once), whatever is offered meanwhile: what that sees joins in.
        if (!lookedDeeper) lookAt(s, m, deeper = true)
        if (more.isEmpty() && !looking) { said = "No other reading of bar ${m.number} - left in doubt"; next(s); return }
        offered = more
        changed()
    }

    /** Bar up is not one bar as read (part of one, or two run together): noted for teaching, and on to the next. */
    fun notOneBar(s: SheetsState) {
        val path = s.currentPath ?: return
        val m = barUp(s) ?: return
        scoreHere(s)?.pageWidths?.getOrNull(m.page)?.takeIf { it > 0 }?.let { w ->
            val read = Transcriber.cached(s, File(path))?.measures?.firstOrNull { it.number == m.number } ?: m
            Transcriber.recordNotABar(s, File(path), read, w)
        }
        said = "Bar ${m.number} noted as not one bar"
        next(s)
    }

    /** Leave bar up as it is, and on to the next. */
    fun next(s: SheetsState) {
        if (checkAt + 1 >= checkBars.size) { endCheck(); said = "All the bars in doubt gone through"; return }
        checkAt++
        showBar(s)
    }

    fun endCheck() {
        checking = false
        editing = null
        looking = false
        barPicture = null
        lookToken++
        offered = emptyList()
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
    private const val CUE = 0xD0455A64.toInt()       // cue notes: small, slate
    private const val STAFF = 0xFF404040.toInt()     // a cleaned bar's staff lines: a printed line's grey

    internal fun marks(path: String, page: Int, width: Float, height: Float): List<PageMark>? {
        pageWidth[path to page] = width
        val score = scoreOf(path) ?: return null
        val clean = cleanedIn(path)
        val live = playing?.first
        val flash = found?.takeIf { System.currentTimeMillis() - it.second < 2_500 }?.first
        val off = Listener.offBars.toSet()
        val cueHere = if (cues) cueCache[path].orEmpty() else emptyMap()
        if (!underlay && clean.isEmpty() && selection == null && live == null && flash == null && off.isEmpty() && cueHere.isEmpty()) return null
        val key = path to page
        marksCache[key]?.let { (v, m) -> if (v == version) return m }
        val k = scaleOf(score, page, width) ?: return null
        val out = ArrayList<PageMark>()
        for ((index, m) in score.measures.withIndex()) {
            if (m.page != page) continue
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
            cueHere[index]?.let { out += cueMarks(it, left, right, top, sp) }
            when {
                // A bar in doubt is never redrawn: a guess drawn cleanly is worse than the print. It
                // stays as printed, framed, until it is checked (Fix) - then it is redrawn.
                numbers.first in clean && !m.sure -> {
                    val pad = sp * 0.4f
                    out += PageMark.line(left, top - sp * 2f, right, top - sp * 2f, pad * 0.35f, DOUBT)
                    out += PageMark.line(left, bottom + sp * 2f, right, bottom + sp * 2f, pad * 0.35f, DOUBT)
                    out += PageMark.line(left, top - sp * 2f, left, bottom + sp * 2f, pad * 0.35f, DOUBT)
                    out += PageMark.line(right, top - sp * 2f, right, bottom + sp * 2f, pad * 0.35f, DOUBT)
                }
                numbers.first in clean -> {
                    // Cleaned up: the print hidden, the reading in its place.
                    // The paper over the print reaches as far as what is redrawn in its place (a dynamic under it, a high note).
                    val drawn = engraved(m, k, INK)
                    val (above, below) = reachOf(m)
                    out += paper(m, k, above, below, PAPER)
                    out += drawn
                    // What the redraw does not draw, kept as printed: whole, never cut at the paper's edge.
                    // Each in its own shade: ink black, pencil and highlighter light, as printed. Its own
                    // and its neighbours' on the line that reach into it (each shape is kept by one bar).
                    val paperTop = (m.box.top - m.space * above) ; val paperBottom = (m.box.bottom + m.space * below)
                    val shapes = score.measures.filter { o -> o.page == m.page && o.staff == m.staff && kotlin.math.abs(o.number - m.number) <= 3 }.flatMap { o ->
                        o.kept.indices.filter { i -> o === m || o.kept[i].let { loop ->
                            var l = Int.MAX_VALUE; var r = Int.MIN_VALUE; var t = Int.MAX_VALUE; var b = Int.MIN_VALUE
                            for (j in loop.indices step 2) { l = minOf(l, loop[j]); r = maxOf(r, loop[j]); t = minOf(t, loop[j + 1]); b = maxOf(b, loop[j + 1]) }
                            r >= m.box.left && l <= m.box.right && b >= paperTop && t <= paperBottom
                        } }.map { i -> o.kept[i] to (o.keptShade.getOrNull(i) ?: 0) }
                    }
                    shapes.indices.groupBy { shapes[it].second / 32 }.forEach { (band, ids) ->
                        val grey = (band * 32 + 16).coerceAtMost(220).let { if (band == 0) 0 else it }
                        out += PageMark(PageMark.Kind.FILL, ids.map { i -> shapes[i].first.let { loop -> FloatArray(loop.size) { loop[it] * k } } },
                            keptColor ?: if (band == 0) INK else (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey)
                    }
                }
                underlay -> {
                    out += engraved(m, k, UNDER)
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

    /**
     * [m] drawn cleanly where it is, as shapes on the page at [k] (page units to the reading's
     * pixels). Across the bar each point follows the staff as printed there - its lines' height and
     * spacing at the bar's left and right ends ([Measure.lines]) - so a bar redrawn on a scan whose
     * staff runs aslant meets the print either side of it without a step.
     */
    internal fun engraved(m: Measure, k: Float, color: Int): List<PageMark> {
        // A multi-bar rest redrawn as one: its staff, bar and count (the underlay leaves the print's be).
        if (m.bars > 1 && color != INK) return emptyList()
        val d = if (m.bars > 1) Engraver.multiRest(m) else Engraver.aligned(m)
        val out = ArrayList<PageMark>()
        // A point of the drawing (spaces from the bar's left, spaces down from its top line) on the page.
        fun px(xs: Float) = (m.box.left + xs * m.space) * k
        fun py(xs: Float, ys: Float): Float = m.yAt(ys, m.box.left + xs * m.space) * k
        // A staff space where [xs] is: the lines' spacing there.
        fun spAt(xs: Float): Float { val x = m.box.left + xs * m.space; return (m.bottomAt(x) - m.topAt(x)) / 4f * k }
        for (mark in d.marks) when (mark) {
            is Engraver.Stroke -> {
                val staffLine = mark.y1 == mark.y2 && mark.x2 - mark.x1 >= d.width - 0.01f
                // The staff lines are the print's own: only drawn where the bar is cleaned (opaque) -
                // then each from where it is printed at the bar's left to where at its right, as
                // thick as it is printed, so the print either side runs straight on into it.
                if (staffLine && color != INK) continue
                // A scan's lines print grey and a little lighter than their dark core suggests.
                val w = if (staffLine && m.lineWidth > 0f) m.lineWidth * k * 0.85f else (mark.w * spAt(mark.x1)).coerceAtLeast(0.4f)
                out += PageMark.line(px(mark.x1), py(mark.x1, mark.y1), px(mark.x2), py(mark.x2, mark.y2), w, if (staffLine) STAFF else color)
            }
            is Engraver.Symbol -> out += PageMark(PageMark.Kind.FILL, MusicGlyphs[mark.name].polygons(spAt(mark.x), px(mark.x), py(mark.x, mark.y)), color)
            is Engraver.Slab -> out += PageMark(PageMark.Kind.FILL, listOf(FloatArray(mark.points.size) { i -> if (i % 2 == 0) px(mark.points[i]) else py(mark.points[i - 1], mark.points[i]) }), color)
        }
        return out
    }

    /** How far above the top line and below the bottom one bar [m] redrawn reaches, in spaces: three at least. */
    private fun reachOf(m: Measure): Pair<Float, Float> {
        val d = if (m.bars > 1) Engraver.multiRest(m) else Engraver.aligned(m)
        var top = 0f; var bottom = 4f
        for (mark in d.marks) when (mark) {
            is Engraver.Stroke -> { top = minOf(top, mark.y1, mark.y2); bottom = maxOf(bottom, mark.y1, mark.y2) }
            is Engraver.Symbol -> { top = minOf(top, mark.y - 1.5f); bottom = maxOf(bottom, mark.y + 1.5f) }
            is Engraver.Slab -> for (i in 1 until mark.points.size step 2) { top = minOf(top, mark.points[i]); bottom = maxOf(bottom, mark.points[i]) }
        }
        return maxOf(3f, -top + 0.8f) to maxOf(3f, bottom - 4f + 0.8f)
    }

    /**
     * Paper over bar [m]'s print (page units at [k]): its width less a sliver at each end, and
     * [above] and [below] staff spaces past its lines - following the staff where it runs aslant.
     */
    internal fun paper(m: Measure, k: Float, above: Float, below: Float, color: Int): PageMark {
        val sp = m.space
        val l = m.box.left + sp * 0.15f; val r = m.box.right - sp * 0.15f
        return PageMark(PageMark.Kind.FILL, listOf(floatArrayOf(
            l * k, (m.topAt(l) - sp * above) * k, r * k, (m.topAt(r) - sp * above) * k,
            r * k, (m.bottomAt(r) + sp * below) * k, l * k, (m.bottomAt(l) + sp * below) * k)), color)
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
        return song.parts.filter { it.id != mine?.id && !(it.label ?: "").contains("score", true) &&
            it.instrument?.contains("score", true) != true }
            .mapNotNull { p -> s.fileOf(p.file)?.takeIf { f -> f.isFile }?.let { p to it } }
            // A part sharing this part's pages is this part.
            .filter { (p, f) -> f.absolutePath != s.currentPath || (p.firstPage != null && p.firstPage != mine?.firstPage) }
    }

    /** Read whichever of the band's parts are not read yet, one after another, then [then]. */
    private fun readBand(s: SheetsState, then: () -> Unit) {
        val todo = otherParts(s).filter { Transcriber.cached(s, it.second) == null }.distinctBy { it.second.absolutePath }
        if (todo.isEmpty()) { bandReading = null; then(); return }
        val total = otherParts(s).distinctBy { it.second.absolutePath }.size
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
        val whole = scoreOf(path) ?: run { said = "Read the music first"; return }
        // This part's own bars, numbered as the band's are - and back again for showing.
        val (mine, offset) = Transcriber.partOf(whole, s.partShown())
        readBand(s) {
            val voices = otherParts(s).mapNotNull { (p, f) ->
                // A part in a band pack: its own pages of the pack's reading.
                val sc = Transcriber.cached(s, f)?.let { Transcriber.partOf(it, p).first } ?: return@mapNotNull null
                val id = p.instrument?.let { com.inksheets.core.PartChoice.seat(it).first }
                val tr = id?.let { com.inksheets.core.Instruments.byId[it]?.transpose } ?: 0
                com.inksheets.core.omr.EnsemblePlayer.Voice(sc, tr, Synth.patchFor(Midi.program(id)))
            }
            if (voices.isEmpty()) { said = "No other parts of this song here to play"; return@readBand }
            val rate = Sound.rate(s).takeIf { it > 0 } ?: run { said = "No sound output here"; return@readBand }
            val range = selection?.let { (it.first - offset)..(it.last - offset) } ?: run {
                val first = mine.measures.firstOrNull { it.page >= s.pageShown.first }?.number ?: 1
                first..(mine.measures.lastOrNull()?.let { it.number + it.bars - 1 } ?: first)
            }
            val p = com.inksheets.core.omr.EnsemblePlayer(Synth(rate), mine, voices, range.first, range.last, SharedMetronome.bpm)
            said = "The band: ${voices.size} parts"
            playing = Triple(range.first + offset, SharedMetronome.bpm.toInt(), 0)
            ensemble = p
            Sound.play(s, WHO) { buf -> p.fill(buf) }
            var lastBar = -1
            watcher = java.util.Timer("band-play", true).apply {
                schedule(object : java.util.TimerTask() {
                    override fun run() {
                        s.platform.onMain {
                            if (ensemble !== p) return@onMain
                            if (p.finished) { stop(s); return@onMain }
                            val now = Triple(p.bar + offset, SharedMetronome.bpm.toInt(), 0)
                            if (playing != now) { playing = now; changed() }
                            if (now.first != lastBar) {
                                lastBar = now.first
                                whole.measures.firstOrNull { now.first >= it.number && now.first < it.number + it.bars }?.let { m -> if (m.page != s.pageShown.first) Perform.jumpTo?.invoke(path, m.page) }
                            }
                        }
                    }
                }, 100L, 100L)
            }
        }
    }

    private var ensemble: com.inksheets.core.omr.EnsemblePlayer? = null

    // ---- cue notes ---------------------------------------------------------------------------

    /** Cues shown: before each entry after a long rest, what another part plays, small, over the staff. */
    var cues by mutableStateOf(false)
        private set

    /** For each part (path): the rest bar to draw a cue over (by index), and the cue's bars. */
    private val cueCache = HashMap<String, Map<Int, List<Measure>>>()

    /** Show [map]'s cues on [path] (a test). */
    internal fun showCuesFor(path: String, map: Map<Int, List<Measure>>) { cues = true; cueCache[path] = map; changed() }

    fun showCues(s: SheetsState, on: Boolean) {
        cues = on
        if (!on) { changed(); return }
        val path = s.currentPath ?: return
        readBand(s) {
            cueCache[path] = cuesFor(s, path)
            said = if (cueCache[path].isNullOrEmpty()) "No long rests to cue here" else null
            changed()
        }
    }

    /** The entries after four bars' rest or more, and the busiest other part's two bars before each. */
    private fun cuesFor(s: SheetsState, path: String): Map<Int, List<Measure>> {
        val whole = scoreOf(path) ?: return emptyMap()
        val (mine, _) = Transcriber.partOf(whole, s.partShown())
        val found = cueBars(mine, otherParts(s).mapNotNull { (p, f) -> Transcriber.cached(s, f)?.let { Transcriber.partOf(it, p).first } })
        // Back to the bars of the file, where they are drawn.
        return found.mapKeys { (k, _) -> mine.measures[k].let { m -> whole.measures.indexOfFirst { it.page == m.page && it.box == m.box } } }.filterKeys { it >= 0 }
    }

    /** Cues for [mine] from [others]: by the index of the rest bar each is drawn over. */
    internal fun cueBars(mine: Score, others: List<Score>): Map<Int, List<Measure>> {
        if (others.isEmpty()) return emptyMap()
        val out = HashMap<Int, List<Measure>>()
        var resting = 0
        for ((i, m) in mine.measures.withIndex()) {
            val silent = m.bars > 1 || m.events.all { it is Rest }
            if (silent) { resting += m.bars; continue }
            if (resting >= 4 && i > 0) {
                val want = listOf(m.number - 2, m.number - 1).filter { it >= 1 }
                // The part with the most notes just before the entry: the one to listen for.
                val best = others.maxByOrNull { o -> want.sumOf { n -> o.measures.firstOrNull { it.number == n && it.bars == 1 }?.events?.count { it is Note } ?: 0 } }
                val bars = want.mapNotNull { n -> best?.measures?.firstOrNull { it.number == n && it.bars == 1 } }
                if (bars.any { b -> b.events.any { it is Note } }) out[i - 1] = bars
            }
            resting = 0
        }
        return out
    }

    /** A cue: [bars] engraved small, over the staff above the bar [m] stands in ([x], [top], [sp] as on the page). */
    private fun cueMarks(bars: List<Measure>, left: Float, right: Float, top: Float, sp: Float): List<PageMark> {
        val d = Engraver.line(bars.mapIndexed { k, b -> b.copy(showsClef = k == 0, showsKey = false, showsTime = false) })
        if (d.width <= 0f) return emptyList()
        val cs = minOf(sp * 0.6f, (right - left - sp) / d.width)
        val cueTop = top - sp * 1.8f - cs * 4f
        // Just before the entry, at the rest's end - where the ear needs it, clear of what begins the line.
        @Suppress("NAME_SHADOWING") val left = right - sp * 0.4f - d.width * cs
        val out = ArrayList<PageMark>()
        for (mark in d.marks) when (mark) {
            is Engraver.Stroke -> out += PageMark.line(left + mark.x1 * cs, cueTop + mark.y1 * cs, left + mark.x2 * cs, cueTop + mark.y2 * cs, (mark.w * cs).coerceAtLeast(0.3f), CUE)
            is Engraver.Symbol -> out += PageMark(PageMark.Kind.FILL, MusicGlyphs[mark.name].polygons(cs, left + mark.x * cs, cueTop + mark.y * cs), CUE)
            is Engraver.Slab -> out += PageMark(PageMark.Kind.FILL, listOf(FloatArray(mark.points.size) { i -> if (i % 2 == 0) left + mark.points[i] * cs else cueTop + mark.points[i] * cs }), CUE)
        }
        return out
    }

    fun stop(s: SheetsState) {
        ensemble?.let { ensemble = null; Sound.stop(WHO) }
        watcher?.cancel(); watcher = null
        if (player != null) Sound.stop(WHO)
        player = null
        if (playing != null) { playing = null; changed() }
    }
}
