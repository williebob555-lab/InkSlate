package com.inkslate.core

import com.inkslate.core.Stroke.Kind as StrokeKind
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Ready-made drawings you would otherwise redraw every week.
 *
 * Lives in `:core` because a stamp is geometry: it produces ordinary strokes, and a number line
 * built one way on the tablet and another way on the laptop would be two different drawings
 * wearing one name. Both builds call this and only the picker around it is written twice.
 *
 * Produced as ordinary strokes rather than images, so a stamp can be moved, resized, recoloured
 * and partly erased like anything else you drew - and so it exports as vectors.
 *
 * Every stamp is *parameterised* rather than fixed. A number line that always runs -5 to 5 is
 * wrong for most of the questions that need one; a pie divided into eighths cannot show thirds;
 * a polygon that is always a triangle is not a polygon. The parameters live in [StampOptions],
 * and each [Kind] declares which of them it actually responds to, so the picker can show the two
 * or three controls that matter for the stamp in hand rather than a wall of settings that mostly
 * do nothing.
 *
 * Every stamp also has its own colour and line weight. They used to come from the pen in hand,
 * which meant a graph drawn after a red correction came out red, and changing the pen to fix it
 * changed the handwriting as well.
 *
 * Each stroke a stamp makes carries a [StampTag] naming the stamp and its settings, so a stamp
 * already on the page can be changed - a range widened, a colour fixed - by building it again in
 * the same place rather than by deleting it and starting over.
 */
object Stamps {

    /** Sections in the picker. Thirty-odd stamps in one undifferentiated grid is a search. */
    enum class Group(val label: String) {
        SHAPES("Shapes"),
        MATHS("Maths"),
        GRAPHS("Graphs & grids"),
        MARKING("Marking"),
        WRITING("Writing & music"),
        DIAGRAMS("Diagrams")
    }

    /** Which controls a stamp responds to. Anything not listed is left out of its options panel. */
    enum class Knob {
        DIVISIONS, LABELS, RANGE, FILLED, VARIANT,
        /** Where the horizontal axis starts and ends, and the value between ticks. */
        X_AXIS,
        /** The same for the vertical axis. */
        Y_AXIS,
        /** Tick marks on or off. */
        TICKS,
        /** Numbers at the ticks on or off. */
        TICK_VALUES,
        /** What the axes are called: x and y, or t and d, or nothing. */
        AXIS_NAMES,
        DASH,
        FILL,
        /** What each end of a line looks like. */
        ENDS
    }

    /** Near-black rather than black, so a stamp sits with handwriting instead of shouting over it. */
    const val INK = 0xFF1F1F1F.toInt()

    /** The most ticks one axis will draw; beyond this a step is widened rather than obeyed. */
    const val MAX_TICKS = 100

    /**
     * The adjustable part of a stamp.
     *
     * One shared shape rather than a class per stamp: the *meaning* of [divisions] differs - it
     * is sides on a polygon, bars in a staff - but the control is the same stepper every time,
     * and [Kind.divisionsLabel] is what tells the user which it is here.
     *
     * Serializable because it travels inside the document, on every stroke a stamp makes, and
     * every field has a default so a build that predates a field reads the rest.
     */
    @Serializable
    data class StampOptions(
        val divisions: Int = 8,
        val labels: Boolean = true,
        /** The horizontal range: from, to and the value between ticks. */
        val rangeFrom: Float = -5f,
        val rangeTo: Float = 5f,
        val step: Float = 1f,
        /** How many parts are shaded, for the fraction stamps. */
        val filled: Int = 0,
        val variant: Int = 0,
        val color: Int = INK,
        /** Line weight in page points. */
        val weight: Float = 1.5f,
        /** Width it was last placed at, in page points; 0 until it has been placed. */
        val size: Float = 0f,
        val yFrom: Float = -5f,
        val yTo: Float = 5f,
        val yStep: Float = 1f,
        val ticks: Boolean = true,
        val tickValues: Boolean = true,
        val xName: String = "x",
        val yName: String = "y",
        val dash: DashStyle = DashStyle.SOLID,
        val fill: FillStyle = FillStyle.NONE,
        val startEnd: LineEnd = LineEnd.NONE,
        val finishEnd: LineEnd = LineEnd.ARROW
    )

    enum class Kind(
        val label: String,
        val group: Group,
        /** Width-to-height ratio the stamp looks right at, before options adjust it. */
        private val baseAspect: Float,
        val knobs: Set<Knob>,
        val divisionsLabel: String = "Divisions",
        val divisionsRange: IntRange = 2..24,
        val variants: List<String> = emptyList(),
        val defaults: StampOptions = StampOptions()
    ) {
        // ---- shapes ----
        // Line, box and oval are stamps like the rest, carrying their own colour and weight and
        // changed afterwards through the same panel. There is no separate arrow: an arrow is a
        // line with an arrow for an end, and either end can be chosen.
        LINE("Line", Group.SHAPES, 6f, setOf(Knob.ENDS, Knob.DASH)),
        BOX("Box", Group.SHAPES, 1.4f, setOf(Knob.DASH, Knob.FILL)),
        OVAL("Oval", Group.SHAPES, 1.4f, setOf(Knob.DASH, Knob.FILL)),

        // ---- maths ----
        AXES(
            "Axes", Group.MATHS, 1f,
            setOf(Knob.X_AXIS, Knob.Y_AXIS, Knob.TICKS, Knob.TICK_VALUES, Knob.AXIS_NAMES)
        ),
        COORD_GRID(
            "Axes on a grid", Group.MATHS, 1f,
            setOf(Knob.X_AXIS, Knob.Y_AXIS, Knob.TICKS, Knob.TICK_VALUES, Knob.AXIS_NAMES)
        ),
        NUMBER_LINE(
            "Number line", Group.MATHS, 4.5f,
            setOf(Knob.X_AXIS, Knob.TICKS, Knob.TICK_VALUES)
        ),
        UNIT_CIRCLE(
            "Unit circle", Group.MATHS, 1f,
            setOf(Knob.DIVISIONS, Knob.LABELS),
            divisionsLabel = "Spokes", divisionsRange = 0..24,
            defaults = StampOptions(divisions = 12)
        ),
        PIE(
            "Fraction circle", Group.MATHS, 1f,
            setOf(Knob.DIVISIONS, Knob.FILLED, Knob.LABELS),
            divisionsLabel = "Slices", divisionsRange = 2..16,
            defaults = StampOptions(divisions = 8, filled = 3)
        ),
        FRACTION_BAR(
            "Fraction bar", Group.MATHS, 5f,
            setOf(Knob.DIVISIONS, Knob.FILLED, Knob.LABELS),
            divisionsLabel = "Parts", divisionsRange = 2..20,
            defaults = StampOptions(divisions = 8, filled = 3)
        ),
        POLYGON(
            "Polygon", Group.MATHS, 1f,
            setOf(Knob.DIVISIONS, Knob.LABELS, Knob.VARIANT),
            divisionsLabel = "Sides", divisionsRange = 3..14,
            variants = listOf("Plain", "With diagonals", "With radii"),
            defaults = StampOptions(divisions = 6, labels = false)
        ),
        TRIANGLE(
            "Triangle", Group.MATHS, 1f,
            setOf(Knob.LABELS, Knob.VARIANT),
            variants = listOf("Isosceles", "Right-angled", "With height"),
            defaults = StampOptions(labels = false)
        ),
        ANGLE(
            "Marked angle", Group.MATHS, 1f,
            setOf(Knob.LABELS, Knob.VARIANT),
            variants = listOf("Acute", "Right", "Obtuse", "Reflex"),
            defaults = StampOptions(labels = true)
        ),
        PROTRACTOR(
            "Protractor", Group.MATHS, 2f,
            setOf(Knob.DIVISIONS, Knob.LABELS),
            divisionsLabel = "Marks", divisionsRange = 4..36,
            defaults = StampOptions(divisions = 18)
        ),
        LONG_DIVISION(
            "Division frame", Group.MATHS, 2.2f, emptySet(),
            defaults = StampOptions(labels = false)
        ),
        BRACE(
            "Brace", Group.MATHS, 0.45f,
            setOf(Knob.VARIANT),
            variants = listOf("Curly", "Square", "Curly, along the top"),
            defaults = StampOptions(labels = false)
        ),

        // ---- graphs and grids ----
        GRID(
            "Grid", Group.GRAPHS, 1f,
            setOf(Knob.DIVISIONS, Knob.VARIANT),
            divisionsLabel = "Squares", divisionsRange = 2..32,
            variants = listOf("Lines", "Dots", "Isometric"),
            defaults = StampOptions(divisions = 8, labels = false)
        ),
        POLAR_GRID(
            "Polar grid", Group.GRAPHS, 1f,
            setOf(Knob.DIVISIONS, Knob.LABELS),
            divisionsLabel = "Rings", divisionsRange = 1..8,
            defaults = StampOptions(divisions = 4)
        ),
        BAR_AXES(
            "Chart frame", Group.GRAPHS, 1.2f,
            setOf(Knob.Y_AXIS, Knob.TICKS, Knob.TICK_VALUES, Knob.AXIS_NAMES),
            defaults = StampOptions(yFrom = 0f, yTo = 10f, yStep = 2f, xName = "", yName = "")
        ),
        TIMELINE(
            "Timeline", Group.GRAPHS, 5f,
            setOf(Knob.X_AXIS, Knob.TICKS, Knob.TICK_VALUES),
            defaults = StampOptions(rangeFrom = 1900f, rangeTo = 2000f, step = 20f)
        ),
        CLOCK(
            "Clock face", Group.GRAPHS, 1f,
            setOf(Knob.LABELS, Knob.VARIANT),
            variants = listOf("Face only", "With hands"),
            defaults = StampOptions()
        ),
        BOX_PLOT(
            "Box plot", Group.GRAPHS, 3f,
            setOf(Knob.LABELS),
            defaults = StampOptions(labels = false)
        ),

        // ---- marking ----
        CHECK(
            "Tick", Group.MARKING, 1f, emptySet(),
            defaults = StampOptions(labels = false, color = 0xFF2E7D32.toInt(), weight = 2f)
        ),
        CROSS(
            "Cross", Group.MARKING, 1f, emptySet(),
            defaults = StampOptions(labels = false, color = 0xFFC62828.toInt(), weight = 2f)
        ),
        STAR(
            "Star", Group.MARKING, 1f,
            setOf(Knob.DIVISIONS),
            divisionsLabel = "Points", divisionsRange = 3..12,
            defaults = StampOptions(divisions = 5, labels = false)
        ),
        GRADE_BOX(
            "Mark box", Group.MARKING, 1.9f,
            setOf(Knob.LABELS, Knob.DIVISIONS),
            divisionsLabel = "Out of", divisionsRange = 1..100,
            defaults = StampOptions(divisions = 10)
        ),
        CHECKLIST(
            "Checklist", Group.MARKING, 1.4f,
            setOf(Knob.DIVISIONS),
            divisionsLabel = "Rows", divisionsRange = 1..12,
            defaults = StampOptions(divisions = 4, labels = false)
        ),
        CALLOUT(
            "Comment box", Group.MARKING, 1.8f,
            setOf(Knob.VARIANT),
            variants = listOf("Box with a tail", "Speech bubble"),
            defaults = StampOptions(labels = false)
        ),

        // ---- writing and music ----
        LINED(
            "Writing lines", Group.WRITING, 1.4f,
            setOf(Knob.DIVISIONS, Knob.VARIANT),
            divisionsLabel = "Lines", divisionsRange = 1..20,
            variants = listOf("Plain", "With a midline", "Four-line"),
            defaults = StampOptions(divisions = 6, labels = false)
        ),
        STAFF(
            "Music staff", Group.WRITING, 3.2f,
            setOf(Knob.DIVISIONS, Knob.VARIANT),
            divisionsLabel = "Bars", divisionsRange = 1..8,
            variants = listOf("Music, five lines", "Guitar tab, six lines"),
            defaults = StampOptions(divisions = 2, labels = false)
        ),
        SIGNATURE(
            "Signature line", Group.WRITING, 6f,
            setOf(Knob.LABELS),
            defaults = StampOptions()
        ),

        // ---- diagrams ----
        VENN(
            "Venn diagram", Group.DIAGRAMS, 1.6f,
            setOf(Knob.LABELS, Knob.VARIANT),
            variants = listOf("Two sets", "Three sets"),
            defaults = StampOptions()
        ),
        TREE(
            "Tree diagram", Group.DIAGRAMS, 1.3f,
            setOf(Knob.DIVISIONS, Knob.LABELS, Knob.VARIANT),
            divisionsLabel = "Branches", divisionsRange = 2..5,
            variants = listOf("One level", "Two levels"),
            defaults = StampOptions(divisions = 2, labels = false)
        ),
        FLOW(
            "Flow boxes", Group.DIAGRAMS, 3.4f,
            setOf(Knob.DIVISIONS),
            divisionsLabel = "Boxes", divisionsRange = 2..6,
            defaults = StampOptions(divisions = 3, labels = false)
        ),
        T_CHART(
            "T-chart", Group.DIAGRAMS, 1.3f,
            setOf(Knob.DIVISIONS, Knob.LABELS),
            divisionsLabel = "Columns", divisionsRange = 2..6,
            defaults = StampOptions(divisions = 2, labels = false)
        ),
        CUBE("Cube", Group.DIAGRAMS, 1.15f, emptySet(), defaults = StampOptions(labels = false));

        /**
         * Width-to-height ratio this stamp looks right at, for these options.
         *
         * A number line squeezed into a square has ten labels fighting for the width of one; a
         * fraction bar in twenty parts needs more width than one in three. The caller sizes the
         * drop area from this.
         */
        fun aspect(o: StampOptions = defaults): Float = when (this) {
            FRACTION_BAR -> (o.divisions * 0.62f).coerceIn(2.5f, 8f)
            FLOW -> (o.divisions * 1.15f).coerceIn(2f, 7f)
            TREE -> if (o.variant == 1) 1.05f else 1.3f
            LINED -> (2.2f - o.divisions * 0.06f).coerceIn(0.9f, 2f)
            CHECKLIST -> (2.6f - o.divisions * 0.12f).coerceIn(0.9f, 2.4f)
            STAFF -> if (o.variant == 1) 2.8f else 3.2f
            // A graph is as wide as its ranges are, relative to each other, when both run in
            // the same units per tick - so -5..5 against 0..10 is square and 0..20 by 0..5 is not.
            AXES, COORD_GRID -> {
                val cols = (o.rangeTo - o.rangeFrom) / o.step.coerceAtLeast(1e-6f)
                val rows = (o.yTo - o.yFrom) / o.yStep.coerceAtLeast(1e-6f)
                if (cols > 0f && rows > 0f && cols.isFinite() && rows.isFinite()) {
                    (cols / rows).coerceIn(0.4f, 2.5f)
                } else {
                    baseAspect
                }
            }
            else -> baseAspect
        }

        val isShape: Boolean get() = group == Group.SHAPES
    }

    /** Kept for callers that only need the natural proportions of a stamp's defaults. */
    fun aspectFor(kind: Kind, options: StampOptions = kind.defaults): Float = kind.aspect(options)

    /**
     * Clamp [o] to what [kind] can actually do.
     *
     * The options are shared, so a value left over from the last stamp - eight slices carried
     * into a triangle, five shaded parts in a three-part bar - is normal rather than exceptional,
     * and every builder below is entitled to assume it has already been dealt with.
     */
    fun sanitise(kind: Kind, o: StampOptions): StampOptions {
        val divisions = o.divisions.coerceIn(kind.divisionsRange.first, kind.divisionsRange.last)
        val variant = if (kind.variants.isEmpty()) 0 else o.variant.coerceIn(0, kind.variants.lastIndex)
        var from = o.rangeFrom
        var to = o.rangeTo
        if (!from.isFinite() || !to.isFinite() || to <= from) {
            from = kind.defaults.rangeFrom
            to = kind.defaults.rangeTo
        }
        var yFrom = o.yFrom
        var yTo = o.yTo
        if (!yFrom.isFinite() || !yTo.isFinite() || yTo <= yFrom) {
            yFrom = kind.defaults.yFrom
            yTo = kind.defaults.yTo
        }
        return o.copy(
            divisions = divisions,
            variant = variant,
            filled = o.filled.coerceIn(0, divisions),
            rangeFrom = from,
            rangeTo = to,
            step = saneStep(o.step, to - from, kind.defaults.step),
            yFrom = yFrom,
            yTo = yTo,
            yStep = saneStep(o.yStep, yTo - yFrom, kind.defaults.yStep),
            weight = if (o.weight.isFinite()) o.weight.coerceIn(0.3f, 12f) else kind.defaults.weight,
            size = if (o.size.isFinite() && o.size > 0f) o.size.coerceIn(8f, 4000f) else 0f,
            // A name is a label, not a paragraph.
            xName = o.xName.take(24),
            yName = o.yName.take(24)
        )
    }

    /**
     * A step that divides [span] into a drawable number of ticks.
     *
     * Zero, negative and non-numbers fall back; a step so fine it would draw thousands of ticks is
     * widened to the most one axis will carry, rather than refused - "0 to 1000 in ones" is a
     * reasonable thing to type, and a solid black bar is a poor answer to it.
     */
    private fun saneStep(step: Float, span: Float, fallback: Float): Float {
        var s = if (step.isFinite() && step > 0f) step else fallback
        if (!(s > 0f)) s = 1f
        if (span / s > MAX_TICKS) s = span / MAX_TICKS
        return s
    }

    /**
     * How wide a label's box is drawn, generously.
     *
     * The box only positions the text - it is centred in it - but a box narrower than the text
     * wraps it, which turned "-4" into a "-" over a "4" and split years in two. Too wide costs
     * nothing, so this errs well on that side of any real font.
     */
    internal fun labelWidth(text: String, size: Float): Float =
        max(size * 1.6f, size * 1.05f * text.length + 14f)

    /**
     * The values ticks fall on between [from] and [to], at multiples of [step].
     *
     * Multiples rather than counted from [from], so an axis from -3.5 still has a tick at 0 and
     * the origin of a graph always lands on one.
     */
    fun tickValues(from: Float, to: Float, step: Float): List<Float> {
        if (!(step > 0f) || !(to > from)) return emptyList()
        val eps = step * 1e-4f
        val first = kotlin.math.ceil(((from - eps) / step).toDouble()).toLong()
        val out = ArrayList<Float>()
        var k = first
        while (out.size <= MAX_TICKS) {
            val v = (k * step.toDouble()).toFloat()
            if (v > to + eps) break
            out.add(if (kotlin.math.abs(v) < eps) 0f else v)
            k++
        }
        return out
    }

    /**
     * Build a stamp inside [bounds], on [page], in its own colour and weight.
     *
     * [nextId] mints ids so the result can be dropped straight into the document. [group] tags
     * every stroke as one stamp so it can be changed later; previews leave it null.
     */
    fun build(
        kind: Kind,
        bounds: Box,
        page: Int,
        options: StampOptions = kind.defaults,
        group: String? = null,
        nextId: () -> String
    ): List<Stroke> {
        val o = sanitise(kind, options)
        val color = o.color
        val width = o.weight
        val now = System.currentTimeMillis()
        val out = ArrayList<Stroke>()

        fun line(x0: Float, y0: Float, x1: Float, y1: Float, w: Float = width) {
            out.add(
                Stroke(
                    id = nextId(), kind = StrokeKind.LINE, color = color, baseWidth = w,
                    points = listOf(InkPoint(x0, y0, w), InkPoint(x1, y1, w)),
                    pageIndex = page, updatedUtc = now
                )
            )
        }

        fun dashed(x0: Float, y0: Float, x1: Float, y1: Float, w: Float = width * 0.7f) {
            out.add(
                Stroke(
                    id = nextId(), kind = StrokeKind.LINE, color = color, baseWidth = w,
                    points = listOf(InkPoint(x0, y0, w), InkPoint(x1, y1, w)),
                    dash = DashStyle.DASHED, pageIndex = page, updatedUtc = now
                )
            )
        }

        fun arrow(x0: Float, y0: Float, x1: Float, y1: Float, w: Float = width) {
            out.add(
                Stroke(
                    id = nextId(), kind = StrokeKind.ARROW, color = color, baseWidth = w,
                    points = listOf(InkPoint(x0, y0, w), InkPoint(x1, y1, w)),
                    pageIndex = page, updatedUtc = now
                )
            )
        }

        fun box(
            l: Float, t: Float, r: Float, b: Float,
            w: Float = width, fill: FillStyle = FillStyle.NONE
        ) {
            out.add(
                Stroke(
                    id = nextId(), kind = StrokeKind.RECT, color = color, baseWidth = w,
                    points = listOf(InkPoint(l, t, w), InkPoint(r, b, w)),
                    fill = fill, fillColor = color, pageIndex = page, updatedUtc = now
                )
            )
        }

        fun oval(
            l: Float, t: Float, r: Float, b: Float,
            w: Float = width, fill: FillStyle = FillStyle.NONE
        ) {
            out.add(
                Stroke(
                    id = nextId(), kind = StrokeKind.ELLIPSE, color = color, baseWidth = w,
                    points = listOf(InkPoint(l, t, w), InkPoint(r, b, w)),
                    fill = fill, fillColor = color, pageIndex = page, updatedUtc = now
                )
            )
        }

        /** A polyline, as consecutive segments. Curves are sampled into these. */
        fun poly(pts: List<FloatArray>, w: Float = width, closed: Boolean = false) {
            for (i in 1 until pts.size) line(pts[i - 1][0], pts[i - 1][1], pts[i][0], pts[i][1], w)
            if (closed && pts.size > 2) {
                line(pts.last()[0], pts.last()[1], pts[0][0], pts[0][1], w)
            }
        }

        /**
         * A label centred horizontally on [cx], with its top at [top].
         *
         * Centring is done by the renderer through a box and an alignment rather than by guessing
         * at the text width here - guessing is what used to leave the digits of a number line
         * visibly off their ticks.
         */
        fun label(text: String, cx: Float, top: Float, size: Float) {
            val boxW = labelWidth(text, size)
            out.add(
                Stroke(
                    id = nextId(), kind = StrokeKind.TEXT, color = color, baseWidth = 1f,
                    points = listOf(InkPoint(cx - boxW / 2f, top, 1f)),
                    text = text, textSize = size, boxWidth = boxW,
                    align = TextAlign.CENTER, pageIndex = page, updatedUtc = now
                )
            )
        }

        /** A label whose right edge is at [right], for numbers up the side of an axis. */
        fun labelRight(text: String, right: Float, top: Float, size: Float) {
            val boxW = labelWidth(text, size)
            out.add(
                Stroke(
                    id = nextId(), kind = StrokeKind.TEXT, color = color, baseWidth = 1f,
                    points = listOf(InkPoint(right - boxW, top, 1f)),
                    text = text, textSize = size, boxWidth = boxW, padding = 1f,
                    align = TextAlign.RIGHT, pageIndex = page, updatedUtc = now
                )
            )
        }

        /** A label whose top-left corner is where you put it. */
        fun labelAt(text: String, x: Float, y: Float, size: Float) {
            out.add(
                Stroke(
                    id = nextId(), kind = StrokeKind.TEXT, color = color, baseWidth = 1f,
                    points = listOf(InkPoint(x, y, 1f)),
                    text = text, textSize = size, pageIndex = page, updatedUtc = now
                )
            )
        }

        val left = bounds.left
        val top = bounds.top
        val right = bounds.right
        val bottom = bounds.bottom
        val w = bounds.width
        val h = bounds.height
        val cx = bounds.centerX
        val cy = bounds.centerY
        val thin = width * 0.6f
        val hair = width * 0.45f
        /** A type size that stays readable without swamping the drawing it annotates. */
        val textSize = (min(w, h) * 0.09f).coerceIn(5f, 26f)

        /** Points of a regular n-gon inscribed in the box, first vertex at the top. */
        fun ngon(n: Int, rx: Float, ry: Float, phase: Double = -PI / 2): List<FloatArray> =
            (0 until n).map { i ->
                val a = phase + 2.0 * PI * i / n
                floatArrayOf(cx + (cos(a) * rx).toFloat(), cy + (sin(a) * ry).toFloat())
            }

        /** Sampled arc, centre (ax, ay), for building the curves the model has no primitive for. */
        fun arcPoints(
            ax: Float, ay: Float, rx: Float, ry: Float,
            fromDeg: Float, toDeg: Float, steps: Int = 18
        ): List<FloatArray> = (0..steps).map { i ->
            val a = Math.toRadians((fromDeg + (toDeg - fromDeg) * i / steps).toDouble())
            floatArrayOf(ax + (cos(a) * rx).toFloat(), ay + (sin(a) * ry).toFloat())
        }

        /** Shade a sector by fanning close radial lines across it. */
        fun shadeSector(ax: Float, ay: Float, r: Float, fromDeg: Float, toDeg: Float) {
            val span = toDeg - fromDeg
            // Spaced enough to read as hatching rather than as a solid block: the lines meet at
            // the centre whatever the spacing, and too many of them turn the middle into ink.
            val lines = max(4, (span / 4f).roundToInt())
            for (i in 1 until lines) {
                val a = Math.toRadians((fromDeg + span * i / lines).toDouble())
                line(
                    ax, ay,
                    ax + (cos(a) * r).toFloat(), ay + (sin(a) * r).toFloat(),
                    hair
                )
            }
        }

        /** Trim trailing zeros so an axis reads "2" and "2.5" rather than "2.0" and "2.50". */
        fun num(v: Float): String {
            val rounded = (v * 100f).roundToInt() / 100f
            return if (kotlin.math.abs(rounded - rounded.roundToInt()) < 0.005f) {
                rounded.roundToInt().toString()
            } else {
                ("%.2f".format(rounded)).trimEnd('0').trimEnd('.')
            }
        }

        /**
         * How many ticks to step over between numbers, so numbers never overlap.
         *
         * Thinned rather than dropped: an axis in ones from -20 to 20 on a small graph reads fine
         * labelled every fifth tick, and reads as nothing at all with every number jammed in.
         */
        fun labelStride(spacing: Float, chars: Int, size: Float): Int {
            val need = size * 0.62f * chars + size * 0.5f
            if (spacing <= 0f) return Int.MAX_VALUE
            val raw = kotlin.math.ceil((need / spacing).toDouble()).toInt().coerceAtLeast(1)
            // Round up to a stride that reads as counting: 1, 2, 5, 10, 20, 50...
            var nice = 1
            val ladder = intArrayOf(1, 2, 5)
            var scale = 1
            while (true) {
                for (m in ladder) {
                    nice = m * scale
                    if (nice >= raw) return nice
                }
                scale *= 10
                if (scale > 100_000) return nice
            }
        }

        /** Whether value [v] is one of the ticks that gets a number, counted from zero. */
        fun multipleOfStride(v: Float, step: Float, stride: Int): Boolean {
            if (stride <= 1) return true
            val k = kotlin.math.round(v / step).toLong()
            return k % stride == 0L
        }

        when (kind) {

            // ---- maths -------------------------------------------------------

            Kind.LINE -> out.add(
                Stroke(
                    id = nextId(), kind = StrokeKind.LINE, color = color, baseWidth = width,
                    points = listOf(InkPoint(left, cy, width), InkPoint(right, cy, width)),
                    dash = o.dash, startEnd = o.startEnd, finishEnd = o.finishEnd,
                    pageIndex = page, updatedUtc = now
                )
            )

            Kind.BOX, Kind.OVAL -> out.add(
                Stroke(
                    id = nextId(),
                    kind = if (kind == Kind.BOX) StrokeKind.RECT else StrokeKind.ELLIPSE,
                    color = color, baseWidth = width,
                    points = listOf(InkPoint(left, top, width), InkPoint(right, bottom, width)),
                    dash = o.dash, fill = o.fill, fillColor = color,
                    pageIndex = page, updatedUtc = now
                )
            )

            Kind.AXES, Kind.COORD_GRID -> {
                // Positions come from values: where 0 falls between from and to is where an axis
                // crosses, so a first-quadrant graph, a four-quadrant one and "x from -2 to 10"
                // are one drawing with different numbers rather than three variants.
                val xs = o.rangeFrom
                val xe = o.rangeTo
                val ys = o.yFrom
                val ye = o.yTo
                fun mapX(v: Float) = left + (v - xs) / (xe - xs) * w
                fun mapY(v: Float) = bottom - (v - ys) / (ye - ys) * h
                val ox = mapX(0f.coerceIn(xs, xe))
                val oy = mapY(0f.coerceIn(ys, ye))
                val xTicks = tickValues(xs, xe, o.step)
                val yTicks = tickValues(ys, ye, o.yStep)
                val spacingX = w * o.step / (xe - xs)
                val spacingY = h * o.yStep / (ye - ys)

                if (kind == Kind.COORD_GRID) {
                    // Gridlines first, so the axes sit on top of them rather than under.
                    xTicks.forEach { v -> line(mapX(v), top, mapX(v), bottom, hair) }
                    yTicks.forEach { v -> line(left, mapY(v), right, mapY(v), hair) }
                }

                arrow(if (xs < 0f) left else ox, oy, right, oy)
                arrow(ox, if (ys < 0f) bottom else oy, ox, top)

                val tick = (min(w, h) * 0.025f).coerceAtMost(min(spacingX, spacingY) * 0.4f)
                if (o.ticks) {
                    xTicks.forEach { v ->
                        if (v != 0f) line(mapX(v), oy - tick, mapX(v), oy + tick, thin)
                    }
                    yTicks.forEach { v ->
                        if (v != 0f) line(ox - tick, mapY(v), ox + tick, mapY(v), thin)
                    }
                }
                val valueSize = textSize * 0.72f
                val below = if (o.ticks) tick else 0f
                if (o.tickValues) {
                    val xChars = xTicks.maxOfOrNull { num(it).length } ?: 1
                    val everyX = labelStride(spacingX, xChars, valueSize)
                    val everyY = labelStride(spacingY, 2, valueSize)
                    xTicks.forEach { v ->
                        if (v != 0f && multipleOfStride(v, o.step, everyX)) {
                            label(num(v), mapX(v), oy + below + 1f, valueSize)
                        }
                    }
                    yTicks.forEach { v ->
                        if (v != 0f && multipleOfStride(v, o.yStep, everyY)) {
                            labelRight(num(v), ox - below - 2f, mapY(v) - valueSize * 0.68f, valueSize)
                        }
                    }
                    if (xs < 0f && 0f < xe && ys < 0f && 0f < ye) {
                        labelAt("0", ox - valueSize * 1.2f, oy + below * 0.6f, valueSize)
                    }
                }
                // Beyond the arrowheads, where a textbook puts them: inside the frame they sit on
                // the gridlines and on whatever gets plotted.
                if (o.xName.isNotBlank()) {
                    labelAt(o.xName, right + textSize * 0.3f, oy - textSize * 0.7f, textSize)
                }
                if (o.yName.isNotBlank()) {
                    label(o.yName, ox, top - textSize * 2.1f, textSize)
                }
            }

            Kind.NUMBER_LINE, Kind.TIMELINE -> {
                arrow(left, cy, right, cy)
                if (kind == Kind.NUMBER_LINE) arrow(right, cy, left, cy)
                // Inset so the first and last ticks sit inside the arrowheads rather than on them.
                val inset = if (kind == Kind.NUMBER_LINE) w * 0.04f else 0f
                val l = left + inset
                val span = o.rangeTo - o.rangeFrom
                val usable = w - inset * 2f
                fun mapX(v: Float) = l + (v - o.rangeFrom) / span * usable
                val ticks = tickValues(o.rangeFrom, o.rangeTo, o.step)
                val spacing = usable * o.step / span
                val tick = h * 0.16f
                val size = min(h * 0.22f, 22f).coerceAtLeast(4f)
                if (o.ticks) ticks.forEach { v -> line(mapX(v), cy - tick, mapX(v), cy + tick, thin) }
                if (o.tickValues) {
                    val chars = ticks.maxOfOrNull { num(it).length } ?: 1
                    val every = labelStride(spacing, chars, size)
                    val below = if (o.ticks) tick else 0f
                    ticks.forEach { v ->
                        if (multipleOfStride(v, o.step, every)) {
                            label(num(v), mapX(v), cy + below + size * 0.25f, size)
                        }
                    }
                }
            }

            Kind.UNIT_CIRCLE -> {
                val r = min(w, h) / 2f
                oval(cx - r, cy - r, cx + r, cy + r)
                arrow(cx - r * 1.14f, cy, cx + r * 1.14f, cy, thin)
                arrow(cx, cy + r * 1.14f, cx, cy - r * 1.14f, thin)
                for (i in 0 until o.divisions) {
                    // Skip the spokes that would lie exactly on an axis; the axes are already there.
                    val a = 2.0 * PI * i / o.divisions
                    if (o.divisions % 4 == 0 && i % (o.divisions / 4) == 0) continue
                    line(
                        cx, cy,
                        cx + (cos(a) * r).toFloat(), cy - (sin(a) * r).toFloat(),
                        hair
                    )
                }
                if (o.labels) {
                    val s = textSize * 0.85f
                    label("1", cx + r, cy + r * 0.06f + s * 0.2f, s)
                    label("-1", cx - r, cy + r * 0.06f + s * 0.2f, s)
                    label("1", cx + r * 0.10f + s, cy - r - s * 1.1f, s)
                    label("-1", cx + r * 0.10f + s, cy + r - s * 0.1f, s)
                }
            }

            Kind.PIE -> {
                val r = min(w, h) / 2f
                val sliceDeg = 360f / o.divisions
                for (i in 0 until o.filled) {
                    shadeSector(cx, cy, r, -90f + sliceDeg * i, -90f + sliceDeg * (i + 1))
                }
                oval(cx - r, cy - r, cx + r, cy + r)
                for (i in 0 until o.divisions) {
                    val a = Math.toRadians((-90f + sliceDeg * i).toDouble())
                    line(cx, cy, cx + (cos(a) * r).toFloat(), cy + (sin(a) * r).toFloat(), thin)
                }
                if (o.labels) {
                    label("${o.filled}/${o.divisions}", cx, bottom + r * 0.06f, textSize)
                }
            }

            Kind.FRACTION_BAR -> {
                val step = w / o.divisions
                for (i in 0 until o.filled) {
                    box(left + step * i, top, left + step * (i + 1), bottom, hair, FillStyle.TINTED)
                }
                box(left, top, right, bottom)
                for (i in 1 until o.divisions) {
                    line(left + step * i, top, left + step * i, bottom, thin)
                }
                if (o.labels) {
                    label("${o.filled}/${o.divisions}", cx, bottom + h * 0.12f, textSize)
                }
            }

            Kind.POLYGON -> {
                val pts = ngon(o.divisions, w / 2f, h / 2f)
                poly(pts, width, closed = true)
                when (o.variant) {
                    1 -> for (i in pts.indices) {
                        for (j in i + 2 until pts.size) {
                            if (i == 0 && j == pts.lastIndex) continue    // that is an edge
                            line(pts[i][0], pts[i][1], pts[j][0], pts[j][1], hair)
                        }
                    }
                    2 -> pts.forEach { line(cx, cy, it[0], it[1], hair) }
                }
                if (o.labels) {
                    pts.forEachIndexed { i, p ->
                        val letter = ('A' + (i % 26)).toString()
                        // pushed outwards from the centre, so a label never sits on its edge
                        val dx = p[0] - cx
                        val dy = p[1] - cy
                        val len = max(1f, kotlin.math.hypot(dx, dy))
                        label(
                            letter,
                            p[0] + dx / len * textSize * 0.9f,
                            p[1] + dy / len * textSize * 0.9f - textSize * 0.6f,
                            textSize
                        )
                    }
                }
            }

            Kind.TRIANGLE -> {
                val apexX = if (o.variant == 0) cx else left
                line(left, bottom, right, bottom)
                line(right, bottom, apexX, top)
                line(apexX, top, left, bottom)
                if (o.variant == 1) {
                    // right-angle marker in the corner it belongs to
                    val m = min(w, h) * 0.12f
                    line(left, bottom - m, left + m, bottom - m, thin)
                    line(left + m, bottom - m, left + m, bottom, thin)
                }
                if (o.variant == 2) {
                    dashed(cx, top, cx, bottom)
                    val m = min(w, h) * 0.1f
                    line(cx, bottom - m, cx + m, bottom - m, thin)
                    line(cx + m, bottom - m, cx + m, bottom, thin)
                }
                if (o.labels) {
                    label("A", apexX, top - textSize * 1.1f, textSize)
                    label("B", left - textSize * 0.3f, bottom + 1f, textSize)
                    label("C", right + textSize * 0.3f, bottom + 1f, textSize)
                }
            }

            Kind.ANGLE -> {
                val deg = when (o.variant) {
                    0 -> 45f
                    1 -> 90f
                    2 -> 130f
                    else -> 290f
                }
                val vx = left + w * 0.15f
                val vy = bottom - h * 0.15f
                val armLength = min(w, h) * 0.82f
                val a = Math.toRadians(-deg.toDouble())
                line(vx, vy, vx + armLength, vy)
                line(vx, vy, vx + (cos(a) * armLength).toFloat(), vy + (sin(a) * armLength).toFloat())
                val r = armLength * 0.28f
                if (o.variant == 1) {
                    // a right angle is squared, not arced - that is what the notation means
                    line(vx + r, vy, vx + r, vy - r, thin)
                    line(vx + r, vy - r, vx, vy - r, thin)
                } else {
                    poly(arcPoints(vx, vy, r, r, 0f, -deg, 20), thin)
                }
                if (o.labels) {
                    val mid = Math.toRadians(-deg / 2.0)
                    label(
                        "${deg.roundToInt()}°",
                        vx + (cos(mid) * r * 1.55f).toFloat(),
                        vy + (sin(mid) * r * 1.55f).toFloat() - textSize * 0.5f,
                        textSize
                    )
                }
            }

            Kind.PROTRACTOR -> {
                // A semicircle sitting on its baseline, which is where a protractor's zero is.
                val r = min(w / 2f, h)
                val bx = cx
                val by = bottom
                poly(arcPoints(bx, by, r, r, 180f, 360f, 30), width)
                line(bx - r, by, bx + r, by)
                for (i in 0..o.divisions) {
                    val deg = 180f + 180f * i / o.divisions
                    val a = Math.toRadians(deg.toDouble())
                    val longMark = o.divisions <= 12 || i % 3 == 0
                    val inner = r * (if (longMark) 0.82f else 0.90f)
                    line(
                        bx + (cos(a) * inner).toFloat(), by + (sin(a) * inner).toFloat(),
                        bx + (cos(a) * r).toFloat(), by + (sin(a) * r).toFloat(),
                        if (longMark) thin else hair
                    )
                    if (o.labels && longMark) {
                        val degrees = (180f * i / o.divisions).roundToInt()
                        label(
                            degrees.toString(),
                            bx + (cos(a) * r * 0.72f).toFloat(),
                            by + (sin(a) * r * 0.72f).toFloat() - textSize * 0.5f,
                            textSize * 0.75f
                        )
                    }
                }
            }

            Kind.LONG_DIVISION -> {
                // The divisor sits left of the bracket, the dividend under the bar.
                val hookX = left + w * 0.28f
                line(hookX, top + h * 0.22f, right, top + h * 0.22f)
                poly(arcPoints(hookX, cy + h * 0.05f, w * 0.14f, h * 0.28f, -78f, 78f, 14), width)
                line(left, top + h * 0.28f, left, bottom)
            }

            Kind.BRACE -> {
                when (o.variant) {
                    1 -> {
                        val x = left + w * 0.25f
                        line(right, top, x, top)
                        line(x, top, x, bottom)
                        line(x, bottom, right, bottom)
                    }
                    2 -> {
                        // the same curly brace, lying along the top of what it groups
                        val y = top + h * 0.6f
                        poly(
                            listOf(
                                floatArrayOf(left, bottom), floatArrayOf(left + w * 0.06f, y),
                                floatArrayOf(cx - w * 0.03f, y), floatArrayOf(cx, top),
                                floatArrayOf(cx + w * 0.03f, y),
                                floatArrayOf(right - w * 0.06f, y), floatArrayOf(right, bottom)
                            )
                        )
                    }
                    else -> {
                        val x = left
                        poly(
                            listOf(
                                floatArrayOf(x + w * 0.6f, top),
                                floatArrayOf(x + w * 0.25f, top + h * 0.1f),
                                floatArrayOf(x + w * 0.25f, cy - h * 0.08f),
                                floatArrayOf(x, cy),
                                floatArrayOf(x + w * 0.25f, cy + h * 0.08f),
                                floatArrayOf(x + w * 0.25f, bottom - h * 0.1f),
                                floatArrayOf(x + w * 0.6f, bottom)
                            )
                        )
                    }
                }
            }

            // ---- graphs and grids --------------------------------------------

            Kind.GRID -> {
                val cells = o.divisions
                when (o.variant) {
                    1 -> {
                        val dx = w / cells
                        val dy = h / cells
                        val dot = max(0.4f, width * 0.8f)
                        for (i in 0..cells) for (j in 0..cells) {
                            val x = left + dx * i
                            val y = top + dy * j
                            line(x, y, x + 0.01f, y, dot)
                        }
                    }
                    2 -> {
                        // isometric: verticals plus two families of 30-degree lines
                        val dx = w / cells
                        val slope = kotlin.math.tan(Math.toRadians(30.0)).toFloat()
                        val rise = w * slope
                        for (i in 0..cells) line(left + dx * i, top, left + dx * i, bottom, hair)
                        // Enough diagonals to cross the box from corner to corner, counted rather
                        // than stepped, so a flat box cannot turn this into a loop that never ends.
                        val rows = (((h + rise * 2f) / dx).toInt() + 2).coerceIn(2, 400)
                        for (i in 0 until rows) {
                            val y0 = top - rise + dx * i
                            line(left, y0, right, y0 + rise, hair)
                            line(left, y0 + rise, right, y0, hair)
                        }
                    }
                    else -> {
                        val dx = w / cells
                        val dy = h / cells
                        for (i in 0..cells) {
                            line(left + dx * i, top, left + dx * i, bottom, thin)
                            line(left, top + dy * i, right, top + dy * i, thin)
                        }
                    }
                }
            }

            Kind.POLAR_GRID -> {
                val r = min(w, h) / 2f
                for (i in 1..o.divisions) {
                    val rr = r * i / o.divisions
                    oval(cx - rr, cy - rr, cx + rr, cy + rr, hair)
                }
                for (i in 0 until 12) {
                    val a = 2.0 * PI * i / 12
                    line(cx, cy, cx + (cos(a) * r).toFloat(), cy + (sin(a) * r).toFloat(), hair)
                }
                if (o.labels) {
                    for (i in 0 until 12) {
                        val deg = i * 30
                        val a = Math.toRadians(-deg.toDouble())
                        label(
                            "$deg°",
                            cx + (cos(a) * r * 1.11f).toFloat(),
                            cy + (sin(a) * r * 1.11f).toFloat() - textSize * 0.5f,
                            textSize * 0.7f
                        )
                    }
                }
            }

            Kind.BAR_AXES -> {
                val ox = left + w * 0.14f
                val oy = bottom - h * 0.12f
                val ys = o.yFrom
                val ye = o.yTo
                fun mapY(v: Float) = oy - (v - ys) / (ye - ys) * (oy - top)
                val values = tickValues(ys, ye, o.yStep)
                val spacing = (oy - top) * o.yStep / (ye - ys)
                val every = labelStride(spacing, 2, textSize * 0.8f)
                val tick = min(w, h) * 0.02f
                values.forEach { v ->
                    val y = mapY(v)
                    if (v != ys) line(ox, y, right, y, hair)
                    if (o.ticks) line(ox - tick, y, ox, y, thin)
                    if (o.tickValues && multipleOfStride(v, o.yStep, every)) {
                        val s2 = textSize * 0.8f
                        labelRight(num(v), ox - tick - 2f, y - s2 * 0.68f, s2)
                    }
                }
                line(ox, top, ox, oy)
                line(ox, oy, right, oy)
                if (o.xName.isNotBlank()) label(o.xName, (ox + right) / 2f, oy + textSize * 0.3f, textSize)
                if (o.yName.isNotBlank()) labelAt(o.yName, ox - tick, top - textSize * 1.5f, textSize)
            }

            Kind.CLOCK -> {
                val r = min(w, h) / 2f
                oval(cx - r, cy - r, cx + r, cy + r)
                for (i in 0 until 60) {
                    val a = Math.toRadians((-90.0 + i * 6.0))
                    val major = i % 5 == 0
                    val inner = r * (if (major) 0.88f else 0.94f)
                    line(
                        cx + (cos(a) * inner).toFloat(), cy + (sin(a) * inner).toFloat(),
                        cx + (cos(a) * r).toFloat(), cy + (sin(a) * r).toFloat(),
                        if (major) thin else hair
                    )
                }
                if (o.labels) {
                    for (i in 1..12) {
                        val a = Math.toRadians(-90.0 + i * 30.0)
                        label(
                            i.toString(),
                            cx + (cos(a) * r * 0.76f).toFloat(),
                            cy + (sin(a) * r * 0.76f).toFloat() - textSize * 0.6f,
                            textSize
                        )
                    }
                }
                if (o.variant == 1) {
                    // ten past ten: the position every catalogue photograph uses, because it
                    // leaves the face readable rather than covering it
                    val hourA = Math.toRadians(-90.0 + 305.0)
                    val minuteA = Math.toRadians(-90.0 + 60.0)
                    line(
                        cx, cy,
                        cx + (cos(hourA) * r * 0.5f).toFloat(),
                        cy + (sin(hourA) * r * 0.5f).toFloat(),
                        width * 1.4f
                    )
                    line(
                        cx, cy,
                        cx + (cos(minuteA) * r * 0.72f).toFloat(),
                        cy + (sin(minuteA) * r * 0.72f).toFloat()
                    )
                }
            }

            Kind.BOX_PLOT -> {
                val boxTop = top + h * 0.2f
                val boxBottom = bottom - h * 0.2f
                val q1 = left + w * 0.3f
                val median = left + w * 0.48f
                val q3 = left + w * 0.72f
                box(q1, boxTop, q3, boxBottom)
                line(median, boxTop, median, boxBottom)
                line(left, cy, q1, cy)
                line(q3, cy, right, cy)
                line(left, boxTop, left, boxBottom, thin)
                line(right, boxTop, right, boxBottom, thin)
                if (o.labels) {
                    val s = textSize * 0.8f
                    label("min", left, bottom + 1f, s)
                    label("Q1", q1, bottom + 1f, s)
                    label("med", median, top - s * 1.2f, s)
                    label("Q3", q3, bottom + 1f, s)
                    label("max", right, bottom + 1f, s)
                }
            }

            // ---- marking ------------------------------------------------------

            Kind.CHECK -> {
                val heavy = width * 1.6f
                line(left + w * 0.08f, cy + h * 0.02f, left + w * 0.36f, bottom - h * 0.1f, heavy)
                line(left + w * 0.36f, bottom - h * 0.1f, right - w * 0.05f, top + h * 0.08f, heavy)
            }

            Kind.CROSS -> {
                val heavy = width * 1.6f
                line(left + w * 0.12f, top + h * 0.12f, right - w * 0.12f, bottom - h * 0.12f, heavy)
                line(right - w * 0.12f, top + h * 0.12f, left + w * 0.12f, bottom - h * 0.12f, heavy)
            }

            Kind.STAR -> {
                val points = o.divisions
                val outer = ngon(points, w / 2f, h / 2f)
                val inner = ngon(points, w * 0.21f, h * 0.21f, -PI / 2 + PI / points)
                val path = ArrayList<FloatArray>()
                for (i in 0 until points) { path.add(outer[i]); path.add(inner[i]) }
                poly(path, width, closed = true)
            }

            Kind.GRADE_BOX -> {
                box(left, top, right, bottom, width)
                line(cx + w * 0.02f, bottom - h * 0.15f, cx + w * 0.16f, top + h * 0.15f, thin)
                if (o.labels) {
                    val s = h * 0.5f
                    label(o.divisions.toString(), left + w * 0.74f, cy - s * 0.55f, s)
                }
            }

            Kind.CHECKLIST -> {
                val rowH = h / o.divisions
                val boxSize = min(rowH * 0.62f, w * 0.14f)
                for (i in 0 until o.divisions) {
                    val rowTop = top + rowH * i + (rowH - boxSize) / 2f
                    box(left, rowTop, left + boxSize, rowTop + boxSize, thin)
                    line(
                        left + boxSize * 1.5f, rowTop + boxSize,
                        right, rowTop + boxSize, hair
                    )
                }
            }

            Kind.CALLOUT -> {
                if (o.variant == 1) {
                    val bubbleBottom = bottom - h * 0.22f
                    oval(left, top, right, bubbleBottom)
                    poly(
                        listOf(
                            floatArrayOf(left + w * 0.24f, bubbleBottom - h * 0.03f),
                            floatArrayOf(left + w * 0.16f, bottom),
                            floatArrayOf(left + w * 0.36f, bubbleBottom + h * 0.02f)
                        ),
                        thin
                    )
                } else {
                    val boxBottom = bottom - h * 0.22f
                    box(left, top, right, boxBottom)
                    line(left + w * 0.2f, boxBottom, left + w * 0.12f, bottom, thin)
                    line(left + w * 0.12f, bottom, left + w * 0.34f, boxBottom, thin)
                }
            }

            // ---- writing and music --------------------------------------------

            Kind.LINED -> {
                val rowH = h / o.divisions
                for (i in 0..o.divisions) line(left, top + rowH * i, right, top + rowH * i, thin)
                if (o.variant == 1) {
                    for (i in 0 until o.divisions) {
                        dashed(left, top + rowH * (i + 0.5f), right, top + rowH * (i + 0.5f), hair)
                    }
                }
                if (o.variant == 2) {
                    // ascender, midline and descender guides, the way handwriting paper is ruled
                    for (i in 0 until o.divisions) {
                        val base = top + rowH * i
                        line(left, base + rowH * 0.3f, right, base + rowH * 0.3f, hair)
                        dashed(left, base + rowH * 0.65f, right, base + rowH * 0.65f, hair)
                    }
                }
            }

            Kind.STAFF -> {
                val lines = if (o.variant == 1) 6 else 5
                val gap = h / (lines - 1)
                for (i in 0 until lines) line(left, top + gap * i, right, top + gap * i, thin)
                // bar lines, including the two ends
                for (i in 0..o.divisions) {
                    val x = left + w * i / o.divisions
                    line(x, top, x, top + gap * (lines - 1), if (i == o.divisions) width else thin)
                }
                if (o.variant == 1) {
                    labelAt("T", left + w * 0.012f, top + gap * 0.1f, gap * 1.1f)
                    labelAt("A", left + w * 0.012f, top + gap * 1.6f, gap * 1.1f)
                    labelAt("B", left + w * 0.012f, top + gap * 3.1f, gap * 1.1f)
                }
            }

            Kind.SIGNATURE -> {
                line(left, cy, left + w * 0.62f, cy)
                line(left + w * 0.70f, cy, right, cy)
                if (o.labels) {
                    labelAt("Signed", left, cy + textSize * 0.3f, textSize)
                    labelAt("Date", left + w * 0.70f, cy + textSize * 0.3f, textSize)
                }
            }

            // ---- diagrams -----------------------------------------------------

            Kind.VENN -> {
                if (o.variant == 1) {
                    val r = min(w * 0.34f, h * 0.4f)
                    val centres = listOf(
                        floatArrayOf(cx - r * 0.55f, cy - r * 0.3f),
                        floatArrayOf(cx + r * 0.55f, cy - r * 0.3f),
                        floatArrayOf(cx, cy + r * 0.62f)
                    )
                    centres.forEach { oval(it[0] - r, it[1] - r, it[0] + r, it[1] + r) }
                    if (o.labels) {
                        label("A", centres[0][0] - r * 0.7f, centres[0][1] - r * 0.9f, textSize)
                        label("B", centres[1][0] + r * 0.7f, centres[1][1] - r * 0.9f, textSize)
                        label("C", centres[2][0], centres[2][1] + r * 0.65f, textSize)
                    }
                } else {
                    val r = min(w * 0.3f, h * 0.48f)
                    val ax = cx - r * 0.6f
                    val bx = cx + r * 0.6f
                    oval(ax - r, cy - r, ax + r, cy + r)
                    oval(bx - r, cy - r, bx + r, cy + r)
                    if (o.labels) {
                        label("A", ax - r * 0.55f, cy - textSize * 0.6f, textSize)
                        label("B", bx + r * 0.55f, cy - textSize * 0.6f, textSize)
                    }
                }
            }

            Kind.TREE -> {
                val levels = if (o.variant == 1) 2 else 1
                val branches = o.divisions
                val colWidth = w / (levels + 1)

                fun spread(index: Int, count: Int): Float = top + h * (index + 0.5f) / count

                var previous = listOf(cy)
                for (level in 1..levels) {
                    val count = previous.size * branches
                    val next = ArrayList<Float>(count)
                    var k = 0
                    for (parentY in previous) {
                        for (b in 0 until branches) {
                            val childY = spread(k, count)
                            line(
                                left + colWidth * level - w * 0.02f, parentY,
                                left + colWidth * (level + 1) - w * 0.06f, childY,
                                if (level == levels) width else thin
                            )
                            next.add(childY)
                            k++
                        }
                    }
                    previous = next
                }
                if (o.labels) {
                    previous.forEachIndexed { i, y ->
                        labelAt(
                            ('A' + (i % 26)).toString(),
                            left + colWidth * (levels + 1) - w * 0.03f,
                            y - textSize * 0.6f, textSize
                        )
                    }
                }
            }

            Kind.FLOW -> {
                val gap = w * 0.06f
                val boxW = (w - gap * (o.divisions - 1)) / o.divisions
                for (i in 0 until o.divisions) {
                    val bl = left + (boxW + gap) * i
                    box(bl, top, bl + boxW, bottom)
                    if (i < o.divisions - 1) {
                        arrow(bl + boxW + gap * 0.12f, cy, bl + boxW + gap * 0.88f, cy, thin)
                    }
                }
            }

            Kind.T_CHART -> {
                val headerY = top + h * 0.18f
                box(left, top, right, bottom)
                line(left, headerY, right, headerY)
                for (i in 1 until o.divisions) {
                    val x = left + w * i / o.divisions
                    line(x, top, x, bottom)
                }
                if (o.labels) {
                    for (i in 0 until o.divisions) {
                        label(
                            ('A' + (i % 26)).toString(),
                            left + w * (i + 0.5f) / o.divisions,
                            top + h * 0.03f, textSize
                        )
                    }
                }
            }

            Kind.CUBE -> {
                val d = min(w, h) * 0.28f
                val fl = left
                val ft = top + d
                val fr = right - d
                val fb = bottom
                box(fl, ft, fr, fb)
                line(fl, ft, fl + d, ft - d)
                line(fr, ft, fr + d, ft - d)
                line(fr, fb, fr + d, fb - d)
                line(fl + d, ft - d, fr + d, ft - d)
                line(fr + d, ft - d, fr + d, fb - d)
                // the three hidden edges, dashed the way a textbook draws them
                dashed(fl, fb, fl + d, fb - d)
                dashed(fl + d, fb - d, fr + d, fb - d)
                dashed(fl + d, fb - d, fl + d, ft - d)
            }
        }
        if (group == null || out.isEmpty()) return out
        val extent = reach(out) ?: return out
        val tag = StampTag(
            group = group,
            kind = kind.name,
            options = o,
            box = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
            extent = listOf(extent.left, extent.top, extent.right, extent.bottom)
        )
        return out.map { it.copy(stamp = tag) }
    }

    /**
     * A line or arrow from one point to another, dragged out rather than tapped.
     *
     * Tagged like any placed stamp, so it is restyled through the same panel afterwards.
     */
    fun buildLine(
        kind: Kind,
        ax: Float, ay: Float, bx: Float, by: Float,
        page: Int,
        options: StampOptions = kind.defaults,
        group: String? = null,
        nextId: () -> String
    ): List<Stroke> {
        require(kind == Kind.LINE) { "Only lines are dragged out" }
        val o = sanitise(kind, options)
        val stroke = Stroke(
            id = nextId(),
            kind = StrokeKind.LINE,
            color = o.color, baseWidth = o.weight,
            points = listOf(InkPoint(ax, ay, o.weight), InkPoint(bx, by, o.weight)),
            dash = o.dash, startEnd = o.startEnd, finishEnd = o.finishEnd,
            pageIndex = page, updatedUtc = System.currentTimeMillis()
        )
        if (group == null) return listOf(stroke)
        val reach = stroke.rawBoundsBox()
        val corners = listOf(reach.left, reach.top, reach.right, reach.bottom)
        return listOf(stroke.copy(stamp = StampTag(group, kind.name, o, corners, corners)))
    }

    /** Whether [kind] is drawn by dragging from one end to the other when it is in hand. */
    fun isDragged(kind: Kind) = kind == Kind.LINE

    /** A lone, unrotated line or arrow, which gets a handle on each end instead of a frame. */
    fun endsOf(selection: Collection<Stroke>): Stroke? = selection.singleOrNull()?.takeIf {
        (it.kind == StrokeKind.LINE || it.kind == StrokeKind.ARROW) &&
            it.rotation == 0f && it.points.size == 2
    }

    /** [line] with end [index] (0 or 1) moved to [x], [y]. */
    fun withEnd(line: Stroke, index: Int, x: Float, y: Float): Stroke = line.copy(
        points = line.points.mapIndexed { i, p -> if (i == index) p.copy(x = x, y = y) else p },
        updatedUtc = System.currentTimeMillis()
    )

    // ---- stamps already on the page -----------------------------------------

    /**
     * The stamp [strokes] make up, when they are exactly one stamp and nothing else.
     *
     * Null for a mixed selection, for strokes from two stamps, and for anything spread over two
     * pages - a pasted copy of a stamp keeps its group only until it is pasted, see [regroup].
     */
    fun stampOf(strokes: Collection<Stroke>): StampTag? {
        val first = strokes.firstOrNull()?.stamp ?: return null
        if (Kind.entries.none { it.name == first.kind }) return null
        val page = strokes.first().pageIndex
        return first.takeIf {
            strokes.all { s -> s.stamp?.group == first.group && s.pageIndex == page }
        }
    }

    /**
     * How far a stamp's drawing reaches, leaving its labels out.
     *
     * Labels are measured by an estimate of their text, and scaling a stamp changes their type size
     * without changing that estimate in step - so a reach that included them would drift a little
     * further from the truth with every resize, and the stamp would creep across the page.
     */
    private fun reach(strokes: Collection<Stroke>): Box? {
        val drawn = strokes.filter { it.kind != StrokeKind.TEXT }.ifEmpty { strokes.toList() }
        return drawn.map { it.rawBoundsBox() }.reduceOrNull { a, b -> a.union(b) }
    }

    fun kindOf(tag: StampTag): Kind? = Kind.entries.firstOrNull { it.name == tag.kind }

    /**
     * Where the stamp's drawing box is now, after it has been moved or resized as strokes.
     *
     * The tag remembers both the box the stamp was built into and how far its strokes actually
     * reached then (labels hang outside the box). Comparing that reach with the strokes' reach
     * now says how the whole thing has been moved and scaled, and the same change applied to the
     * box says where to build it again - without every move and resize on two platforms having
     * to remember to keep the tag up to date.
     */
    fun currentBox(tag: StampTag, strokes: Collection<Stroke>): Box? {
        if (strokes.isEmpty() || tag.box.size != 4 || tag.extent.size != 4) return null
        val now = reach(strokes) ?: return null
        val then = Box(tag.extent[0], tag.extent[1], tag.extent[2], tag.extent[3])
        if (then.width <= 0f || then.height <= 0f) return null
        val sx = now.width / then.width
        val sy = now.height / then.height
        fun x(v: Float) = now.left + (v - then.left) * sx
        fun y(v: Float) = now.top + (v - then.top) * sy
        return Box(x(tag.box[0]), y(tag.box[1]), x(tag.box[2]), y(tag.box[3]))
    }

    /**
     * The same stamp built again with [options], where it now stands.
     *
     * New ids throughout, so this is an ordinary replace as far as undo and sync are concerned.
     * A line or arrow keeps the direction it was dragged to by restyling its stroke in place
     * rather than rebuilding it, which would lay it flat again.
     */
    fun rebuild(
        strokes: List<Stroke>,
        options: StampOptions,
        nextId: () -> String
    ): List<Stroke> {
        val tag = stampOf(strokes) ?: return strokes
        val kind = kindOf(tag) ?: return strokes
        val o = sanitise(kind, options)
        if (kind.isShape) {
            val now = System.currentTimeMillis()
            return strokes.map {
                it.copy(
                    id = nextId(), color = o.color, fillColor = o.color, baseWidth = o.weight,
                    points = it.points.map { p -> p.copy(width = o.weight) },
                    dash = o.dash,
                    fill = if (kind == Kind.BOX || kind == Kind.OVAL) o.fill else it.fill,
                    // A line's ends are its own now, so an old arrow restyled becomes a line
                    // with the ends that were chosen.
                    kind = if (kind == Kind.LINE) StrokeKind.LINE else it.kind,
                    startEnd = if (kind == Kind.LINE) o.startEnd else it.startEnd,
                    finishEnd = if (kind == Kind.LINE) o.finishEnd else it.finishEnd,
                    stamp = tag.copy(options = o), updatedUtc = now
                )
            }
        }
        val box = currentBox(tag, strokes) ?: return strokes
        return build(kind, box, strokes.first().pageIndex, o, tag.group, nextId)
    }

    /**
     * Give pasted or duplicated stamps groups of their own.
     *
     * Two copies of one stamp sharing a group would be edited as one stamp spread over both.
     */
    fun regroup(strokes: List<Stroke>, newGroup: () -> String): List<Stroke> {
        val fresh = HashMap<String, String>()
        return strokes.map { s ->
            val tag = s.stamp ?: return@map s
            s.copy(stamp = tag.copy(group = fresh.getOrPut(tag.group, newGroup)))
        }
    }
}

/**
 * What a stroke that belongs to a stamp remembers about it.
 *
 * The kind by name rather than as the enum, so a document carrying a stamp a later build added
 * still opens on this one - it is just not editable here.
 */
@Serializable
data class StampTag(
    /** Shared by every stroke of one placed stamp. */
    val group: String,
    val kind: String,
    val options: Stamps.StampOptions,
    /** The box it was built into: left, top, right, bottom. */
    val box: List<Float>,
    /** How far its strokes reached when it was built, which the box is measured against. */
    val extent: List<Float>
)
