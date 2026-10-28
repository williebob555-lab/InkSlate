package com.inksheets.core.omr

import kotlin.math.abs
import kotlin.math.hypot

/** Original page geometry is used only by in-place engraving, never by a newly laid-out score.
 * Absolute observations are converted into the same local staff coordinates as semantic marks,
 * so one final staff transform handles skew, unequal line spacing and page render scale.
 */
internal class AlignedGeometry(private val measure: Measure) {
    fun decoration(source: SourceDecoration): List<Engraver.Mark> {
        val contours = source.contours.filter { it.size >= 6 }
        if(contours.isEmpty())return emptyList()
        // Join loops with doubled, zero-area bridges. Keeping holes in the same
        // filled shape avoids filling a dynamic letter's counters separately.
        val points = ArrayList<Float>()
        val anchorX=x(contours.first()[0].toFloat())
        val anchorY=y(contours.first()[1].toFloat(),contours.first()[0].toFloat())
        points += anchorX;points += anchorY
        for(contour in contours) {
            for(i in contour.indices step 2) {
                points += x(contour[i].toFloat());points += y(contour[i+1].toFloat(),contour[i].toFloat())
            }
            points += x(contour[0].toFloat());points += y(contour[1].toFloat(),contour[0].toFloat())
            points += anchorX;points += anchorY
        }
        return listOf(Engraver.Slab(points.toFloatArray()))
    }
    private fun x(pageX: Float) = (pageX - measure.box.left) / measure.space

    private fun y(pageY: Float, pageX: Float): Float {
        val lines = (0..4).map { measure.lineAt(it, pageX) }
        val band = when {
            pageY <= lines.first() -> 0
            pageY >= lines.last() -> 3
            else -> (0..3).firstOrNull { pageY <= lines[it + 1] } ?: 3
        }
        val gap = lines[band + 1] - lines[band]
        return if (gap.isFinite() && gap > .01f) band + (pageY - lines[band]) / gap
            else (pageY - measure.box.top) / measure.space
    }

    private fun valid(point: SourcePoint): Boolean = measure.space.isFinite() && measure.space > 0f &&
        point.x.isFinite() && point.y.isFinite() &&
        point.x in (measure.box.left - measure.space * 5)..(measure.box.right + measure.space * 5) &&
        point.y in (measure.box.top - measure.space * 24)..(measure.box.bottom + measure.space * 24)

    private fun validSize(width: Float, height: Float): Boolean =
        width.isFinite() && height.isFinite() && width > 0f && height > 0f &&
            width <= measure.space * 20 && height <= measure.space * 24

    private fun glyph(source: SourceGlyph, name: String = source.kind): Engraver.Symbol? {
        if (!valid(source.origin) || !validSize(source.width, source.height)) return null
        val glyph = MusicGlyphs.all[name] ?: return null
        val bounds = glyph.bounds
        val centerX = source.origin.x + source.width / 2
        val sx = (source.width / measure.space) / (bounds[2] - bounds[0])
        val top = y(source.origin.y, centerX)
        val sy = (y(source.origin.y + source.height, centerX) - top) / (bounds[3] - bounds[1])
        if (!sx.isFinite() || !sy.isFinite() || sx <= 0f || sy <= 0f) return null
        return Engraver.Symbol(name, x(source.origin.x) - bounds[0] * sx, top - bounds[1] * sy,
            scaleX = sx, scaleY = sy)
    }

    fun sameSteps(note: Note): Boolean = note.geometry?.heads?.map { it.step }?.sorted() == note.steps.sorted()
    private fun sameDuration(note: Note): Boolean = note.geometry?.duration == note.duration

    fun head(note: Note, step: Int, name: String, fallbackX: Float): Engraver.Symbol {
        val heads = note.geometry?.heads.orEmpty()
        // A corrected pitch keeps that head's horizontal place, but follows its new staff step.
        val source = heads.firstOrNull { it.step == step }
            ?: heads.sortedBy { it.step }.getOrNull(note.steps.sorted().indexOf(step))
        if (source == null || !valid(source.center) || !validSize(source.width, source.height))
            return Engraver.Symbol(name, fallbackX, step * .5f)
        val bounds = MusicGlyphs[name].bounds
        val sourceHeadKind = when (note.geometry?.duration?.base) { 1 -> "noteheadWhole"; 2 -> "noteheadHalf"; else -> "noteheadBlack" }
        val keepSize = note.geometry?.duration == null || sourceHeadKind == name
        val sx = if (keepSize) source.width / measure.space / (bounds[2] - bounds[0]) else 1f
        val sy = if (keepSize) (y(source.center.y + source.height / 2, source.center.x) -
            y(source.center.y - source.height / 2, source.center.x)) / (bounds[3] - bounds[1]) else 1f
        val centerY = if (source.step == step) y(source.center.y, source.center.x) else step * .5f
        return Engraver.Symbol(name, x(source.center.x) - (bounds[0] + bounds[2]) / 2 * sx,
            centerY - (bounds[1] + bounds[3]) / 2 * sy, scaleX = sx, scaleY = sy)
    }

    fun rest(rest: Rest, name: String): Engraver.Symbol? = rest.geometry
        ?.takeIf { it.kind == name && (it.step == null || rest.step == it.step) }
        ?.let { glyph(it, name) }

    fun accidental(note: Note, step: Int, name: String): Engraver.Symbol? {
        // Source spellings from a correction to another pitch/accidental must not be put back.
        if (note.geometry?.heads?.none { it.step == step } != false) return null
        val candidates = note.geometry.accidentals.filter { it.kind == name && (it.step == null || it.step == step) }
        val source = candidates.minByOrNull { abs(y(it.origin.y + it.height / 2, it.origin.x) - step * .5f) } ?: return null
        return glyph(source, name)
    }

    fun dots(note: Note): List<Engraver.Symbol>? {
        if (note.duration.dots == 0) return emptyList()
        if (!sameDuration(note) || !sameSteps(note)) return null
        val source = note.geometry?.dots.orEmpty().filter(::valid)
        if (source.isEmpty()) return null
        val bounds = MusicGlyphs["augmentationDot"].bounds
        return source.map { p -> Engraver.Symbol("augmentationDot", x(p.x) - (bounds[0] + bounds[2]) / 2,
            y(p.y, p.x) - (bounds[1] + bounds[3]) / 2) }
    }

    fun stem(note: Note, heads: List<Engraver.Symbol>): Engraver.Stroke? {
        val line = note.geometry?.stem ?: return null
        if (!valid(line.start) || !valid(line.end) || !line.thickness.isFinite() || line.thickness <= 0f) return null
        if (!sameDuration(note) || !sameSteps(note)) return null
        val up = Engraver.stemUp(note)
        val far = if (up) listOf(line.start, line.end).minBy { it.y } else listOf(line.start, line.end).maxBy { it.y }
        val near = if (far == line.start) line.end else line.start
        val top = heads.minOfOrNull { it.y } ?: return null
        val bottom = heads.maxOfOrNull { it.y } ?: return null
        val tip = y(far.y, far.x)
        if (if (up) tip >= top - .5f else tip <= bottom + .5f) return null
        return Engraver.Stroke(x(near.x), y(near.y, near.x), x(far.x), tip, (line.thickness / measure.space).coerceIn(.03f, .5f))
    }

    fun beamGroup(notes: List<Note>): List<Engraver.Mark>? {
        if (notes.any { !sameDuration(it) || !sameSteps(it) || it.geometry?.stem == null }) return null
        val beams = notes.flatMap { it.geometry?.beams.orEmpty() }.distinct()
        if (beams.isEmpty() || beams.any { !valid(it.start) || !valid(it.end) || !it.thickness.isFinite() || it.thickness <= 0f }) return null
        val out = ArrayList<Engraver.Mark>()
        for (note in notes) {
            val name = if (note.duration.base == 2) "noteheadHalf" else "noteheadBlack"
            val heads = note.steps.map { head(note, it, name, x(note.x)) }
            out += stem(note, heads) ?: return null
        }
        for (beam in beams) {
            val dx = beam.end.x - beam.start.x; val dy = beam.end.y - beam.start.y
            val length = hypot(dx, dy)
            if (length < .01f) return null
            val ox = -dy / length * beam.thickness / 2; val oy = dx / length * beam.thickness / 2
            val points = listOf(SourcePoint(beam.start.x + ox, beam.start.y + oy), SourcePoint(beam.end.x + ox, beam.end.y + oy),
                SourcePoint(beam.end.x - ox, beam.end.y - oy), SourcePoint(beam.start.x - ox, beam.start.y - oy))
            out += Engraver.Slab(points.flatMap { listOf(x(it.x), y(it.y, it.x)) }.toFloatArray())
        }
        return out
    }

    fun preamble(fallback: List<Engraver.Mark>): List<Engraver.Mark> {
        val source = measure.sourceSymbols
        if (source.isEmpty()) return fallback
        val replacement = linkedMapOf<String, List<Engraver.Symbol>>()
        fun replace(category: String, candidates: List<SourceGlyph>, valid: Boolean) {
            if (valid && candidates.isNotEmpty()) {
                val symbols = candidates.mapNotNull { glyph(it) }
                if (symbols.size == candidates.size) replacement[category] = symbols
            }
        }
        val clef = when (measure.clef) { Clef.TREBLE -> "gClef"; Clef.BASS -> "fClef"; else -> "cClef" }
        replace("clef", source.filter { it.kind == clef }, measure.showsClef)
        val key = if (measure.key.fifths > 0) "accidentalSharp" else "accidentalFlat"
        val keySymbols = source.filter { it.kind == key }
        replace("key", keySymbols, measure.showsKey && keySymbols.size == abs(measure.key.fifths))
        val time = source.filter { it.kind.startsWith("timeSig") }
        val digits = (measure.time.beats.toString() + measure.time.beatType).map { "timeSig$it" }.sorted()
        val matchesTime = time.map { it.kind }.sorted() == digits ||
            (time.size == 1 && ((time[0].kind == "timeSigCommon" && measure.time == TimeSig(4, 4)) ||
                (time[0].kind == "timeSigCutCommon" && measure.time == TimeSig(2, 2))))
        replace("time", time, measure.showsTime && matchesTime)
        fun category(name: String) = when { name.endsWith("Clef") -> "clef"; name.startsWith("accidental") -> "key"; name.startsWith("timeSig") -> "time"; else -> "" }
        return fallback.filterNot { it is Engraver.Symbol && category(it.name) in replacement } + replacement.values.flatten()
    }
}
