package com.inkslate.ink

import com.inkslate.core.Stroke.Kind as StrokeKind
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Ready-made drawings you would otherwise redraw every week.
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
 */
object Stamps {

    /** Sections in the picker. Thirty-odd stamps in one undifferentiated grid is a search. */
    enum class Group(val label: String) {
        MATHS("Maths"),
        GRAPHS("Graphs & grids"),
        MARKING("Marking"),
        WRITING("Writing & music"),
        DIAGRAMS("Diagrams")
    }

    /** Which controls a stamp responds to. Anything not listed is left out of its options panel. */
    enum class Knob { DIVISIONS, LABELS, RANGE, FILLED, VARIANT }

    /**
     * The adjustable part of a stamp.
     *
     * One shared shape rather than a class per stamp: the *meaning* of [divisions] differs - it
     * is ticks on an axis, sides on a polygon, bars in a staff - but the control is the same
     * stepper every time, and [Kind.divisionsLabel] is what tells the user which it is here.
     */
    data class StampOptions(
        val divisions: Int = 8,
        val labels: Boolean = true,
        val rangeFrom: Float = -5f,
        val rangeTo: Float = 5f,
        /** How many parts are shaded, for the fraction stamps. */
        val filled: Int = 0,
        val variant: Int = 0
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
        // ---- maths ----
        AXES(
            "Axes", Group.MATHS, 1f,
            setOf(Knob.DIVISIONS, Knob.LABELS, Knob.VARIANT),
            divisionsLabel = "Ticks per side", divisionsRange = 1..20,
            variants = listOf("Four quadrants", "First quadrant"),
            defaults = StampOptions(divisions = 5)
        ),
        COORD_GRID(
            "Axes on a grid", Group.MATHS, 1f,
            setOf(Knob.DIVISIONS, Knob.LABELS, Knob.VARIANT),
            divisionsLabel = "Squares per side", divisionsRange = 2..20,
            variants = listOf("Four quadrants", "First quadrant"),
            defaults = StampOptions(divisions = 5)
        ),
        NUMBER_LINE(
            "Number line", Group.MATHS, 4.5f,
            setOf(Knob.DIVISIONS, Knob.LABELS, Knob.RANGE),
            divisionsLabel = "Intervals", divisionsRange = 2..40,
            defaults = StampOptions(divisions = 10, rangeFrom = -5f, rangeTo = 5f)
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
            setOf(Knob.DIVISIONS, Knob.LABELS),
            divisionsLabel = "Gridlines", divisionsRange = 2..16,
            defaults = StampOptions(divisions = 6)
        ),
        TIMELINE(
            "Timeline", Group.GRAPHS, 5f,
            setOf(Knob.DIVISIONS, Knob.LABELS, Knob.RANGE),
            divisionsLabel = "Marks", divisionsRange = 2..20,
            defaults = StampOptions(divisions = 6, rangeFrom = 1900f, rangeTo = 2000f)
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
        CHECK("Tick", Group.MARKING, 1f, emptySet(), defaults = StampOptions(labels = false)),
        CROSS("Cross", Group.MARKING, 1f, emptySet(), defaults = StampOptions(labels = false)),
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
            else -> baseAspect
        }
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
        return o.copy(
            divisions = divisions,
            variant = variant,
            filled = o.filled.coerceIn(0, divisions),
            rangeFrom = from,
            rangeTo = to
        )
    }

    /**
     * Build a stamp inside [bounds], on [page].
     * [nextId] mints ids so the result can be dropped straight into the document.
     */
    fun build(
        kind: Kind,
        bounds: RectF,
        page: Int,
        color: Int,
        width: Float,
        options: StampOptions = kind.defaults,
        nextId: () -> String
    ): List<Stroke> {
        val o = sanitise(kind, options)
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
            val boxW = max(size * 1.2f, size * 0.62f * text.length + 6f)
            out.add(
                Stroke(
                    id = nextId(), kind = StrokeKind.TEXT, color = color, baseWidth = 1f,
                    points = listOf(InkPoint(cx - boxW / 2f, top, 1f)),
                    text = text, textSize = size, boxWidth = boxW,
                    align = TextAlign.CENTER, pageIndex = page, updatedUtc = now
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
        val w = bounds.width()
        val h = bounds.height()
        val cx = bounds.centerX()
        val cy = bounds.centerY()
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

        when (kind) {

            // ---- maths -------------------------------------------------------

            Kind.AXES, Kind.COORD_GRID -> {
                val firstQuadrant = o.variant == 1
                val ox = if (firstQuadrant) left + w * 0.10f else cx
                val oy = if (firstQuadrant) bottom - h * 0.10f else cy
                val stepX = (right - ox) / o.divisions
                val stepY = (oy - top) / o.divisions

                if (kind == Kind.COORD_GRID) {
                    // Gridlines first, so the axes sit on top of them rather than under.
                    val back = if (firstQuadrant) 0 else o.divisions
                    for (i in -back..o.divisions) {
                        line(ox + stepX * i, top, ox + stepX * i, bottom, hair)
                        line(left, oy - stepY * i, right, oy - stepY * i, hair)
                    }
                }

                arrow(if (firstQuadrant) ox else left, oy, right, oy)
                arrow(ox, if (firstQuadrant) oy else bottom, ox, top)

                val tick = min(stepX, stepY) * 0.2f
                for (i in 1..o.divisions) {
                    line(ox + stepX * i, oy - tick, ox + stepX * i, oy + tick, thin)
                    line(ox - tick, oy - stepY * i, ox + tick, oy - stepY * i, thin)
                    if (!firstQuadrant) {
                        line(ox - stepX * i, oy - tick, ox - stepX * i, oy + tick, thin)
                        line(ox - tick, oy + stepY * i, ox + tick, oy + stepY * i, thin)
                    }
                }
                if (o.labels) {
                    labelAt("x", right - textSize * 1.4f, oy + tick + textSize * 0.2f, textSize)
                    labelAt("y", ox + tick + textSize * 0.2f, top, textSize)
                    labelAt("O", ox - textSize * 1.1f, oy + tick * 0.6f, textSize)
                    // Numbers only when the ticks are far enough apart to carry them.
                    if (stepX > textSize * 1.1f) {
                        for (i in 1..o.divisions) {
                            label(i.toString(), ox + stepX * i, oy + tick + 1f, textSize * 0.8f)
                            if (!firstQuadrant) {
                                label(
                                    (-i).toString(), ox - stepX * i, oy + tick + 1f,
                                    textSize * 0.8f
                                )
                            }
                        }
                    }
                }
            }

            Kind.NUMBER_LINE -> {
                arrow(left, cy, right, cy)
                arrow(right, cy, left, cy)
                val step = w / o.divisions
                val tick = h * 0.16f
                val size = min(step * 0.62f, h * 0.24f).coerceAtLeast(4f)
                val span = o.rangeTo - o.rangeFrom
                for (i in 0..o.divisions) {
                    val x = left + step * i
                    line(x, cy - tick, x, cy + tick, thin)
                    if (o.labels) {
                        label(
                            num(o.rangeFrom + span * i / o.divisions),
                            x, cy + tick + size * 0.25f, size
                        )
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
                val step = (oy - top) / o.divisions
                for (i in 1..o.divisions) {
                    line(ox, oy - step * i, right, oy - step * i, hair)
                    line(ox - min(w, h) * 0.02f, oy - step * i, ox, oy - step * i, thin)
                    if (o.labels && step > textSize * 0.9f) {
                        label(
                            i.toString(), ox - textSize * 0.9f,
                            oy - step * i - textSize * 0.6f, textSize * 0.8f
                        )
                    }
                }
                line(ox, top, ox, oy)
                line(ox, oy, right, oy)
            }

            Kind.TIMELINE -> {
                arrow(left, cy, right, cy)
                val step = w / o.divisions
                val tick = h * 0.18f
                val size = min(step * 0.5f, h * 0.22f).coerceAtLeast(4f)
                val span = o.rangeTo - o.rangeFrom
                for (i in 0..o.divisions) {
                    val x = left + step * i
                    line(x, cy - tick, x, cy + tick, thin)
                    if (o.labels) {
                        label(num(o.rangeFrom + span * i / o.divisions), x, cy + tick + 1f, size)
                    }
                }
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
        return out
    }
}
