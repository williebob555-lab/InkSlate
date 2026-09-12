package com.inkslate.ink

import com.inkslate.core.Stamps
import com.inkslate.core.Stroke.Kind as StrokeKind
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.inkslate.data.StrokeIdGen
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.max
import kotlin.math.min
import com.inkslate.core.Tool
import com.inkslate.core.Box
import com.inkslate.core.EraserMode
import com.inkslate.core.InkCanvas
import com.inkslate.core.StylusButtonAction
import com.inkslate.core.InputMode
import com.inkslate.core.ToolConfig

/**
 * The inking surface.
 *
 * All content lives in page space (PDF points). [pageToView] maps page space onto the screen and
 * its inverse maps touch events back, so zooming in to write small algebra still lands the saved
 * annotation in exactly the right spot.
 *
 * Input priority:
 *  1. Two or more pointers -> pan/zoom, never draws. A stroke in progress is cancelled.
 *  2. A stylus is on the glass -> only the stylus draws; finger and palm contacts are ignored.
 *  3. Otherwise -> the single finger draws.
 */
class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // ---- page ----------------------------------------------------------------

    private val slots = ArrayList<PageSlot>()

    var layout: PageLayout = PageLayout.VERTICAL
        private set

    /** Page the viewport is centred on. Drives SINGLE mode and the page indicator. */
    var currentPage: Int = 0
        private set

    /** Dimensions of [currentPage], for callers that place things relative to "the" page. */
    var pageWidthPt: Float = 612f; private set
    var pageHeightPt: Float = 792f; private set

    /** Which page a stroke in progress belongs to. */
    private var livePage: Int = 0

    var onCurrentPageChanged: ((Int) -> Unit)? = null

    // ---- detail tile -----------------------------------------------------------

    /**
     * A sharp render of the region currently on screen.
     *
     * Page bitmaps are rendered once at a fixed width, so zooming past that magnifies pixels and
     * fine detail - staff lines, grid rules, small type - goes soft. Re-rendering the whole page
     * at zoom resolution would not fit in memory on a large page, so only the visible rectangle
     * is re-rendered, at screen resolution, and drawn over the top.
     *
     * The previous tile is kept until its replacement arrives, so zooming never flashes back to
     * the blurry version.
     */
    private var detailPage: Int = -1
    private var detailRect: RectF? = null
    private var detailBitmap: Bitmap? = null

    /** Asks the host for a sharp render of [rect] on [page], sized to [widthPx]. */
    var onDetailNeeded: ((page: Int, rect: RectF, widthPx: Int) -> Unit)? = null

    private var detailRequestedFor: String? = null

    private val detailRequest = Runnable { requestDetailIfNeeded() }

    fun setDetail(page: Int, rect: RectF, bitmap: Bitmap?) {
        if (bitmap == null) return
        detailBitmap?.takeIf { it !== bitmap }?.recycle()
        detailPage = page
        detailRect = RectF(rect)
        detailBitmap = bitmap
        invalidate()
    }

    private fun clearDetail() {
        detailBitmap?.recycle()
        detailBitmap = null
        detailRect = null
        detailPage = -1
        detailRequestedFor = null
    }

    /** Schedule a detail render once the view stops moving. */
    private fun scheduleDetail() {
        removeCallbacks(detailRequest)
        postDelayed(detailRequest, DETAIL_SETTLE_MS)
    }

    private fun requestDetailIfNeeded() {
        if (slots.isEmpty() || width == 0 || height == 0) return
        val scale = currentScale()

        val page = currentPage.coerceIn(0, slots.size - 1)
        val slot = slots.getOrNull(page) ?: return
        val base = slot.bitmap

        // How many pixels the page is currently occupying across its own width.
        val onScreenWidthPx = slot.width * scale
        val baseWidthPx = (base?.width ?: 0).toFloat()

        // Only worth doing once the base raster is being stretched appreciably.
        if (baseWidthPx > 0f && onScreenWidthPx <= baseWidthPx * DETAIL_TRIGGER) {
            if (detailBitmap != null) { clearDetail(); invalidate() }
            return
        }

        // The visible slice of that page, in its own coordinates, with a little overscan so
        // small movements do not immediately invalidate it.
        val viewport = RectF(0f, 0f, width.toFloat(), height.toFloat())
        viewToPage.mapRect(viewport)
        val o = originOf(page)
        val region = RectF(
            viewport.left - o[0], viewport.top - o[1],
            viewport.right - o[0], viewport.bottom - o[1]
        )
        region.inset(-region.width() * 0.08f, -region.height() * 0.08f)
        if (!region.intersect(0f, 0f, slot.width, slot.height)) return
        if (region.width() < 1f || region.height() < 1f) return

        val targetPx = (region.width() * scale).toInt().coerceIn(400, DETAIL_MAX_PX)

        val key = "$page|${region.left.toInt()}|${region.top.toInt()}|" +
            "${region.width().toInt()}|${region.height().toInt()}|$targetPx"
        if (key == detailRequestedFor) return
        detailRequestedFor = key
        onDetailNeeded?.invoke(page, RectF(region), targetPx)
    }

    /** Pages currently on screen, so the host can render just those and free the rest. */
    var onVisiblePagesChanged: ((List<Int>) -> Unit)? = null
    private var lastVisible: List<Int> = emptyList()

    /**
     * Raised while a visible page still has no bitmap.
     *
     * The visible-page list alone is not enough to drive loading: if it does not change - because
     * the page never moved - a render that failed once is never retried, and the page stays blank
     * until something incidental happens to shift the viewport. Repeating the request while the
     * page is genuinely still empty closes that hole, and it stops of its own accord the moment a
     * bitmap arrives.
     */
    var onPagesNeedRender: ((List<Int>) -> Unit)? = null
    private var lastRetryAt = 0L

    // ---- content -------------------------------------------------------------

    private val strokes = ArrayList<Stroke>()

    /**
     * Strokes grouped by page, rebuilt only when content changes.
     *
     * Filtering the full list per page on every frame allocated a new list for each page each
     * time the view invalidated, which on a busy document is a lot of garbage during the one
     * activity that most needs to stay smooth.
     */
    private var pageIndexDirty = true
    private val strokesByPage = HashMap<Int, MutableList<Stroke>>()

    private fun rebuildPageIndex() {
        if (!pageIndexDirty) return
        strokesByPage.values.forEach { it.clear() }
        for (st in strokes) {
            strokesByPage.getOrPut(st.pageIndex) { ArrayList() }.add(st)
        }
        pageIndexDirty = false
    }
    private val undoStack = ArrayList<Op>()
    private val redoStack = ArrayList<Op>()
    private val selection = LinkedHashSet<String>()

    /**
     * Mints stroke ids. Injected by the host so ids carry this device tag, which is what keeps
     * two devices editing the same synced document from colliding.
     */
    var ids: StrokeIdGen = StrokeIdGen("local")

    /**
     * Which page index this view currently holds strokes for, or -1 for none.
     * Lets the host tell "the page changed" apart from "the page finished rendering", so a slow
     * render completing can never overwrite strokes drawn while it was still loading.
     */
    var loadedPageIndex: Int = -1

    /** An undoable edit, as add/remove sets so erase-many is a single step. */
    private data class Op(val added: List<Stroke>, val removed: List<Stroke>)

    // ---- active tool settings ------------------------------------------------

    /** Settings used when the stylus is drawing. */
    val penConfig = ToolConfig()

    /** Settings used when a finger is drawing. Defaults to the highlighter, which is what a
     *  finger is actually good for on a worksheet. */
    val touchConfig = ToolConfig(
        brush = BrushType.HIGHLIGHTER,
        strokeWidth = BrushType.HIGHLIGHTER.defaultWidth,
        color = Color.parseColor("#FDE047")
    )

    /**
     * Settings used while a pen button is held down.
     *
     * A held button is a whole second pen rather than a single verb - see [StylusButtonAction] -
     * so it gets a config of its own, and the width, colour and brush in it are what the stroke
     * comes out as for exactly as long as the button is down.
     */
    /** A mouse, when one is attached: its own two pens rather than borrowing the stylus's. */
    val mouseConfig = ToolConfig()
    val mouseRightConfig = ToolConfig(tool = Tool.ERASER, eraserRadius = 8f)

    val button1Config = ToolConfig(tool = Tool.ERASER, eraserRadius = 8f)
    val button2Config = ToolConfig(color = Color.parseColor("#DC2626"), strokeWidth = 2f)

    /** Which config the last pointer selected. */
    var activeMode: InputMode = InputMode.PEN
        private set

    /** When on, touching with a finger or pen swaps configs automatically. */
    var autoSwitchInput: Boolean = true

    /**
     * The button profile in force for the stroke being drawn.
     *
     * Latched at the moment the pen lands rather than read per sample: a button released halfway
     * through a stroke would otherwise change the pen halfway through the stroke, which is not
     * something anyone has ever meant to do.
     */
    private var gestureConfig: ToolConfig? = null

    /** The button profile for the button held right now, if any. */
    private var heldButtonConfig: ToolConfig? = null

    private val config: ToolConfig
        get() = gestureConfig ?: heldButtonConfig ?: configFor(activeMode)

    /** Delegating accessors so the rest of the view and the host talk to the active config. */
    var tool: Tool
        get() = config.tool
        set(v) { config.tool = v }
    var brush: BrushType
        get() = config.brush
        set(v) { config.brush = v }
    var color: Int
        get() = config.color
        set(v) { config.color = v }
    var strokeWidth: Float
        get() = config.strokeWidth
        set(v) { config.strokeWidth = v }
    var opacity: Float
        get() = config.opacity
        set(v) { config.opacity = v }
    var dash: DashStyle
        get() = config.dash
        set(v) { config.dash = v }
    var fillStyle: FillStyle
        get() = config.fillStyle
        set(v) { config.fillStyle = v }
    var fillColor: Int
        get() = config.fillColor
        set(v) { config.fillColor = v }
    var eraserRadius: Float
        get() = config.eraserRadius
        set(v) { config.eraserRadius = v }
    var textSize: Float
        get() = config.textSize
        set(v) { config.textSize = v }
    var smoothing: Float
        get() = config.smoothing
        set(v) { config.smoothing = v.coerceIn(0f, 1f) }
    var pressureGamma: Float
        get() = config.pressureGamma
        set(v) { config.pressureGamma = v.coerceIn(0.2f, 3f) }
    var pressureMin: Float
        get() = config.pressureMin
        set(v) { config.pressureMin = v.coerceIn(0.02f, 0.95f) }

    var pressureEnabled: Boolean = true
    var snapShapes: Boolean = false

    /**
     * Something waiting to be put on the page.
     *
     * Stamps used to land in the middle of the viewport at a size the app chose, leaving the user
     * to drag and resize afterwards - and armed symbols did nothing at all. Both now work the way
     * placing an object should: tap where you want it, or drag out the space it should fill.
     *
     * The arming survives placement, so a page of symbols is a series of taps rather than a
     * series of round trips through a palette.
     */
    sealed interface Placement {
        val label: String
        data class StampItem(
            val kind: Stamps.Kind,
            val options: Stamps.StampOptions = kind.defaults
        ) : Placement {
            override val label get() = kind.label
        }
        data class TextItem(val text: String) : Placement {
            override val label get() = text
        }
        /**
         * A picture waiting to go on the page.
         *
         * Armed rather than dropped in the middle of the viewport, for the same reason stamps are:
         * a photograph of a worked answer belongs in the gap you left for it, and the gap is
         * something you point at, not something the app can guess.
         */
        data class ImageItem(
            val imageId: String,
            val aspect: Float,
            override val label: String
        ) : Placement
    }

    private var armedPlacement: Placement? = null

    /** Rectangle being dragged out for the armed item, in page coordinates. */
    private var placing: RectF? = null
    private var placeStart: FloatArray? = null

    /** Told when an armed item is placed or cleared, so the toolbar can reflect it. */
    var onPlacementChanged: ((Placement?) -> Unit)? = null

    fun armStamp(kind: Stamps.Kind, options: Stamps.StampOptions = kind.defaults) {
        armedPlacement = Placement.StampItem(kind, Stamps.sanitise(kind, options))
        onPlacementChanged?.invoke(armedPlacement)
        invalidate()
    }

    /** Arm a stored picture for placement. [aspect] is its width over its height. */
    fun armImage(imageId: String, aspect: Float, label: String) {
        armedPlacement = Placement.ImageItem(
            imageId, if (aspect.isFinite() && aspect > 0f) aspect else 1f, label
        )
        onPlacementChanged?.invoke(armedPlacement)
        invalidate()
    }

    fun armText(text: String) {
        armedPlacement = if (text.isBlank()) null else Placement.TextItem(text)
        onPlacementChanged?.invoke(armedPlacement)
        invalidate()
    }

    fun disarmPlacement() {
        if (armedPlacement == null) return
        armedPlacement = null
        placing = null
        placeStart = null
        onPlacementChanged?.invoke(null)
        invalidate()
    }

    fun armedPlacement(): Placement? = armedPlacement

    /** Correct rough freehand shapes into clean ones on release. */
    var recogniseShapes: Boolean = false

    /**
     * Make the highlighter follow the document's text lines.
     *
     * Supplied by the host because only it can read the PDF. Given the points of a highlighter
     * stroke, it returns the rectangles of the words crossed; a null result means "no text layer
     * here", and the freehand stroke is kept as drawn.
     */
    var textSnapper: ((page: Int, points: List<Pair<Float, Float>>) -> List<RectF>)? = null
    var snapHighlighterToText: Boolean = false

    // ---- ruler ---------------------------------------------------------------

    /**
     * A straightedge that ink snaps to.
     *
     * Held in the coordinates of the page it sits on, so it stays put relative to the work rather
     * than to the screen when you scroll or zoom. Strokes starting within [RULER_SNAP_PT] of the
     * line are projected onto it, which is how a physical ruler behaves: it guides the pen
     * without needing the pen to be exactly on the edge.
     */
    var rulerVisible: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value && rulerA == null) placeRulerAcrossView()
            invalidate()
        }

    private var rulerPage: Int = 0
    private var rulerA: FloatArray? = null
    private var rulerB: FloatArray? = null
    private var rulerGrab = 0            // 0 none, 1 end A, 2 end B, 3 whole bar

    /** Pointer ids driving each end during a two-finger ruler adjustment, or -1. */
    private var rulerPointerA = -1
    private var rulerPointerB = -1
    private val rulerTwoFinger: Boolean get() = rulerPointerA >= 0 && rulerPointerB >= 0
    private var rulerGrabOffset = floatArrayOf(0f, 0f)

    /** Paper and ruling for the part of a growing canvas the document's page does not cover. */
    private val canvasPaperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val canvasRulingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.5f
    }

    private val rulerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(48, 60, 130, 246)
    }
    private val rulerEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(190, 60, 130, 246)
    }
    private val rulerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    /** Drop the ruler horizontally across the middle of what is on screen. */
    fun placeRulerAcrossView() {
        val c = viewCenterInPage()
        rulerPage = livePage
        val halfLength = (pageWidthPt * 0.35f).coerceAtLeast(60f)
        rulerA = floatArrayOf(c[0] - halfLength, c[1])
        rulerB = floatArrayOf(c[0] + halfLength, c[1])
        invalidate()
    }

    /**
     * The straightedge as the shared model sees it.
     *
     * The arithmetic that decides where ruled ink actually lands - the angle, the rotation, the
     * projection - is `core/Ruler`, shared with the Windows build, because a stroke drawn against
     * the ruler is *stored* projected onto it. A ruler that snapped differently on the two builds
     * would put visibly different ink in the same document from the same movement. What stays
     * here is the part that is genuinely this view's: hit-testing in view coordinates, and drawing.
     */
    private fun rulerModel(): com.inkslate.core.Ruler? {
        val a = rulerA ?: return null
        val b = rulerB ?: return null
        return com.inkslate.core.Ruler(a[0], a[1], b[0], b[1], rulerPage)
    }

    private fun setRulerModel(r: com.inkslate.core.Ruler) {
        rulerA = floatArrayOf(r.ax, r.ay)
        rulerB = floatArrayOf(r.bx, r.by)
        invalidate()
    }

    /** Angle of the ruler in degrees, for the protractor readout. */
    private fun rulerAngle(): Float = rulerModel()?.angleDegrees ?: 0f

    fun rotateRuler(byDegrees: Float) {
        setRulerModel(rulerModel()?.rotatedBy(byDegrees) ?: return)
    }

    /** Snap the ruler to the nearest 15 degrees, for clean angles. */
    fun snapRulerAngle() {
        setRulerModel(rulerModel()?.snappedToAngle(15f) ?: return)
    }

    /**
     * Project a page-space point onto the ruler line, if it is close enough to count.
     *
     * [unbounded] skips the distance test, for the samples after the first: once a stroke is
     * being ruled it stays ruled to the end, the same way the pen stays against the edge.
     */
    private fun projectOntoRuler(x: Float, y: Float, unbounded: Boolean = false): FloatArray? {
        if (!rulerVisible || livePage != rulerPage) return null
        val ruler = rulerModel() ?: return null
        val tolerance =
            if (unbounded) -1f else RULER_SNAP_PT / currentScale().coerceAtLeast(0.05f)
        val hit = ruler.project(x, y, tolerance) ?: return null
        return floatArrayOf(hit.first, hit.second)
    }

    /**
     * Is this view point within reach of the ruler for a two-finger adjustment?
     *
     * More generous than the single-finger grab region: taking hold of a straightedge with two
     * hands is a coarse gesture, and demanding precision from both fingers at once would make it
     * fail more often than it worked.
     */
    private fun nearRulerForTwoFinger(vx: Float, vy: Float): Boolean {
        if (!rulerVisible || livePage != rulerPage) return false
        val a = rulerA ?: return false
        val b = rulerB ?: return false
        val o = originOf(rulerPage)
        val pa = floatArrayOf(a[0] + o[0], a[1] + o[1])
        val pb = floatArrayOf(b[0] + o[0], b[1] + o[1])
        pageToView.mapPoints(pa)
        pageToView.mapPoints(pb)

        val reach = RULER_HALF_THICKNESS_PX * 2.6f
        val vlen = hypot(pb[0] - pa[0], pb[1] - pa[1])
        if (vlen < 1f) return hypot(vx - pa[0], vy - pa[1]) <= reach
        val t = (((vx - pa[0]) * (pb[0] - pa[0]) + (vy - pa[1]) * (pb[1] - pa[1])) / (vlen * vlen))
            .coerceIn(0f, 1f)
        val cxp = pa[0] + (pb[0] - pa[0]) * t
        val cyp = pa[1] + (pb[1] - pa[1]) * t
        return hypot(vx - cxp, vy - cyp) <= reach
    }

    /**
     * Begin a two-finger ruler adjustment if both pointers are on it.
     *
     * Each finger takes the end nearest to it, so the ruler follows both hands at once: move,
     * rotate and lengthen become the same gesture, which is how you would handle a real one.
     */
    private fun tryBeginRulerTwoFinger(e: MotionEvent): Boolean {
        if (!rulerVisible || e.pointerCount < 2) return false
        val i0 = 0
        val i1 = 1
        // Fingers only. A pen resting on the ruler while a hand steadies it must not be read as
        // half of a grab, or ruling a line with your other hand on the bar moves the bar.
        if (e.getToolType(i0) == MotionEvent.TOOL_TYPE_STYLUS ||
            e.getToolType(i1) == MotionEvent.TOOL_TYPE_STYLUS
        ) return false
        if (!nearRulerForTwoFinger(e.getX(i0), e.getY(i0))) return false
        if (!nearRulerForTwoFinger(e.getX(i1), e.getY(i1))) return false

        val a = rulerA ?: return false
        val o = originOf(rulerPage)
        val pa = floatArrayOf(a[0] + o[0], a[1] + o[1])
        pageToView.mapPoints(pa)

        val d0 = hypot(e.getX(i0) - pa[0], e.getY(i0) - pa[1])
        val d1 = hypot(e.getX(i1) - pa[0], e.getY(i1) - pa[1])
        if (d0 <= d1) {
            rulerPointerA = e.getPointerId(i0); rulerPointerB = e.getPointerId(i1)
        } else {
            rulerPointerA = e.getPointerId(i1); rulerPointerB = e.getPointerId(i0)
        }
        return true
    }

    private fun updateRulerTwoFinger(e: MotionEvent) {
        val o = originOf(rulerPage)
        fun setEnd(pointerId: Int, into: FloatArray?) {
            if (pointerId < 0 || into == null) return
            val idx = e.findPointerIndex(pointerId)
            if (idx < 0) return
            toDoc(e.getX(idx), e.getY(idx))
            into[0] = tmpPts[0] - o[0]
            into[1] = tmpPts[1] - o[1]
        }
        setEnd(rulerPointerA, rulerA)
        setEnd(rulerPointerB, rulerB)
        invalidate()
    }

    private fun endRulerTwoFinger() {
        rulerPointerA = -1
        rulerPointerB = -1
    }

    /**
     * Which part of the ruler a view point is over, if any.
     *
     * The body lies entirely on one side of the A-B line, and that line *is* the drawing edge.
     * Testing plain distance to the line therefore claimed a band of screen on the drawing side
     * where there is no ruler at all - which is exactly where the pen has to be able to land, and
     * is why every attempt to rule a line dragged the ruler instead. The test is signed: only the
     * side the body is actually drawn on counts.
     *
     * A stylus is held to a stricter version still. You move a real straightedge with your other
     * hand and draw along it with the pen, so the pen may take the end handles - which are small
     * and unambiguous - but never the bar. There is then no position at all where a pen touch is
     * ambiguous between drawing and dragging.
     */
    private fun rulerHandleAt(vx: Float, vy: Float, isStylus: Boolean): Int {
        if (!rulerVisible || livePage != rulerPage) return 0
        val a = rulerA ?: return 0
        val b = rulerB ?: return 0
        val o = originOf(rulerPage)
        val pa = floatArrayOf(a[0] + o[0], a[1] + o[1])
        val pb = floatArrayOf(b[0] + o[0], b[1] + o[1])
        pageToView.mapPoints(pa)
        pageToView.mapPoints(pb)
        val r = handleRadiusPx * 1.6f
        if (hypot(vx - pa[0], vy - pa[1]) <= r) return 1
        if (hypot(vx - pb[0], vy - pb[1]) <= r) return 2
        if (isStylus) return 0

        val dx = pb[0] - pa[0]
        val dy = pb[1] - pa[1]
        val vlen = hypot(dx, dy)
        if (vlen < 1f) return 0
        val ux = dx / vlen
        val uy = dy / vlen
        // unit normal, pointing into the body - the same one drawRuler builds the body from
        val nx = -uy
        val ny = ux
        val along = (vx - pa[0]) * ux + (vy - pa[1]) * uy
        if (along < 0f || along > vlen) return 0
        val across = (vx - pa[0]) * nx + (vy - pa[1]) * ny
        // a couple of pixels in from the edge, so a touch aimed at the line still draws
        return if (across >= 3f && across <= RULER_HALF_THICKNESS_PX * 2f) 3 else 0
    }

    /**
     * Draw the straightedge.
     *
     * Modelled on a real ruler rather than a generic overlay: one long edge is the drawing edge
     * and carries graduations, the body is translucent so the page stays readable underneath, and
     * the angle sits in a pill sized to its own text so it can never spill outside its background.
     */
    private fun drawRuler(canvas: Canvas) {
        if (!rulerVisible) return
        val a = rulerA ?: return
        val b = rulerB ?: return
        val o = originOf(rulerPage)
        val pa = floatArrayOf(a[0] + o[0], a[1] + o[1])
        val pb = floatArrayOf(b[0] + o[0], b[1] + o[1])
        pageToView.mapPoints(pa)
        pageToView.mapPoints(pb)

        val dx = pb[0] - pa[0]
        val dy = pb[1] - pa[1]
        val len = hypot(dx, dy)
        if (len < 2f) return

        val ux = dx / len
        val uy = dy / len
        // unit normal, pointing to the side the body sits on
        val nx = -uy
        val ny = ux
        val thickness = RULER_HALF_THICKNESS_PX * 2f

        canvas.save()
        // work in the ruler's own frame: origin at end A, x along the bar, y across it
        canvas.translate(pa[0], pa[1])
        canvas.rotate(Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat())

        val body = RectF(0f, 0f, len, thickness)

        rulerFill.color = Color.argb(38, 210, 228, 255)
        canvas.drawRoundRect(body, 10f, 10f, rulerFill)
        rulerEdge.color = Color.argb(110, 150, 190, 245)
        rulerEdge.strokeWidth = 1.5f
        canvas.drawRoundRect(body, 10f, 10f, rulerEdge)

        // The drawing edge, drawn solid so it is obvious which side the ink will land on.
        rulerEdge.color = Color.argb(225, 96, 165, 250)
        rulerEdge.strokeWidth = 2.5f
        canvas.drawLine(0f, 0f, len, 0f, rulerEdge)

        // Graduations along the drawing edge, in page units so they mean something. Spacing is
        // chosen so ticks never crowd together at low zoom.
        val scale = currentScale()
        var unit = 10f                               // page points between minor ticks
        while (unit * scale < 7f) unit *= 2f
        val minor = unit * scale
        rulerEdge.strokeWidth = 1f
        rulerEdge.color = Color.argb(150, 150, 195, 250)
        rulerText.textSize = 22f
        var i = 0
        var x = 0f
        while (x <= len) {
            val major = i % 5 == 0
            val h = if (major) thickness * 0.42f else thickness * 0.22f
            canvas.drawLine(x, 0f, x, h, rulerEdge)
            if (major && minor > 14f) {
                val label = (i * unit).toInt().toString()
                rulerText.color = Color.argb(170, 225, 238, 255)
                canvas.drawText(label, x + 3f, thickness * 0.86f, rulerText)
            }
            x += minor
            i++
        }
        canvas.restore()

        // ---- end handles ----
        listOf(pa, pb).forEach {
            canvas.drawCircle(it[0], it[1], handleRadiusPx + 1f, handleFill)
            canvas.drawCircle(it[0], it[1], handleRadiusPx + 1f, handleEdge)
            canvas.drawCircle(it[0], it[1], handleRadiusPx * 0.34f, handleEdge)
        }

        // ---- angle badge ----
        // Sized from the measured text, so the number cannot overflow its background - which it
        // did when the pill was a fixed-radius circle.
        val label = String.format("%.0f", rulerAngle()) + "\u00B0"
        rulerText.textSize = 30f
        val textWidth = rulerText.measureText(label)
        val padH = 14f
        val padV = 9f
        val badgeW = textWidth + padH * 2f
        val badgeH = rulerText.textSize + padV * 2f

        // sit it clear of the bar, on the opposite side from the drawing edge
        val midX = (pa[0] + pb[0]) / 2f + nx * (thickness + badgeH * 0.75f)
        val midY = (pa[1] + pb[1]) / 2f + ny * (thickness + badgeH * 0.75f)
        val badge = RectF(
            midX - badgeW / 2f, midY - badgeH / 2f,
            midX + badgeW / 2f, midY + badgeH / 2f
        )
        rulerFill.color = Color.argb(232, 24, 30, 40)
        canvas.drawRoundRect(badge, badgeH / 2f, badgeH / 2f, rulerFill)
        rulerEdge.color = Color.argb(190, 96, 165, 250)
        rulerEdge.strokeWidth = 1.5f
        canvas.drawRoundRect(badge, badgeH / 2f, badgeH / 2f, rulerEdge)

        rulerText.color = Color.WHITE
        // baseline from font metrics, so the text is optically centred rather than guessed
        val fm = rulerText.fontMetrics
        val baseline = midY - (fm.ascent + fm.descent) / 2f
        canvas.drawText(label, midX - textWidth / 2f, baseline, rulerText)
    }

    /** Rectangles to flash, for showing where a search hit is. Cleared on the next touch. */
    private var highlightRects: List<RectF> = emptyList()
    private var highlightPage: Int = -1

    /** Trim each page to its content, hiding print margins. */
    var cropMargins: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            slots.forEach { it.cropEnabled = value }
            relayout()
            fitWidth()
            invalidate()
        }

    /**
     * Find the printed area of a page by looking at the rendered raster.
     *
     * The rule - what counts as content, how much padding, and when a crop is worth making - is
     * `core/MarginCrop`, shared with the Windows build. Cropping changes the page's visible size,
     * which is what the arrangement lays out and what ink is hit-tested against, so two builds
     * trimming the same scan to two different boxes would lay the same document out two
     * different ways. Only reading the pixels is this platform's.
     */
    private fun detectContentBox(bmp: Bitmap, pageW: Float, pageH: Float): RectF? {
        val box = com.inkslate.core.MarginCrop.detect(
            bmp.width, bmp.height, pageW, pageH
        ) { x, y -> bmp.getPixel(x, y) } ?: return null
        return RectF(box.left, box.top, box.right, box.bottom)
    }

    /** Reading treatment for the page and its ink. */
    var pageFilter: PageFilter = PageFilter.NONE
        set(value) {
            if (field == value) return
            field = value
            bitmapPaint.colorFilter = value.filter
            invalidate()
        }
    var eraseWholeStroke: Boolean = true
    var newTableRows: Int = 3
    var newTableCols: Int = 3

    fun configFor(mode: InputMode): ToolConfig = when (mode) {
        InputMode.PEN -> penConfig
        InputMode.TOUCH -> touchConfig
        InputMode.MOUSE -> mouseConfig
        InputMode.MOUSE_RIGHT -> mouseRightConfig
        InputMode.BUTTON_1 -> button1Config
        InputMode.BUTTON_2 -> button2Config
    }

    /** Force a mode, for the toolbar's manual pen/finger switch. */
    fun setActiveMode(mode: InputMode) {
        if (activeMode == mode) return
        // the new profile may not even be the eraser, so its cursor must not linger
        eraserCursor = null
        activeMode = mode
        onInputModeChanged?.invoke(mode)
        invalidate()
    }

    // ---- callbacks -----------------------------------------------------------

    var onContentChanged: (() -> Unit)? = null
    var onTransformChanged: (() -> Unit)? = null
    var onSelectionChanged: ((Int) -> Unit)? = null

    /** Raised when the pen/finger switch changes which settings are live, so the toolbar follows. */
    var onInputModeChanged: ((InputMode) -> Unit)? = null

    // ---- the stylus barrel button --------------------------------------------

    /** What the pen's barrel button does. Set by the host from the user's settings. */
    var stylusButton: StylusButtonAction = StylusButtonAction.ERASE

    /** The same, for a second button on the pens that have one. */
    var stylusButton2: StylusButtonAction = StylusButtonAction.PROFILE

    /**
     * Fired once per press, for the actions that are an instant rather than a state.
     *
     * Undo and "next pen" are things the button *does*; erase and pan are things it *is*. Only the
     * first kind needs to leave the view, because the second is handled by standing in for the
     * current tool while the button is down.
     */
    var onStylusButtonPressed: ((StylusButtonAction) -> Unit)? = null

    /**
     * Raised the first time each pen button reports itself, with 1 or 2.
     *
     * This is what tells the host that a button exists at all. The button profiles stay out of
     * the toolbar until it fires, so a device whose stylus has no buttons never shows a control
     * for one - and a pen with two ends up with both without anyone configuring anything.
     */
    var onStylusButtonSeen: ((Int) -> Unit)? = null

    /** Which button is currently down: 0, 1 or 2. */
    private var stylusButtonDown = 0

    /**
     * Which of the pen's buttons a button state means.
     *
     * Devices disagree about which bit a barrel is: some report the stylus-specific bits, some
     * reuse the plain secondary and tertiary mouse buttons. Both spellings of each button are
     * accepted, and the second wins when a device sets both.
     */
    private fun buttonIndexOf(state: Int): Int = when {
        (state and (MotionEvent.BUTTON_STYLUS_SECONDARY or MotionEvent.BUTTON_TERTIARY)) != 0 -> 2
        (state and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY)) != 0 -> 1
        else -> 0
    }

    fun actionForButton(index: Int): StylusButtonAction = when (index) {
        1 -> stylusButton
        2 -> stylusButton2
        else -> StylusButtonAction.NONE
    }

    fun configForButton(index: Int): ToolConfig =
        if (index == 2) button2Config else button1Config

    /** Edge-detect the buttons, so a one-shot action fires once and not on every sample. */
    private fun noteStylusButton(index: Int) {
        if (index == stylusButtonDown) return
        stylusButtonDown = index
        if (index == 0) return
        onStylusButtonSeen?.invoke(index)
        val action = actionForButton(index)
        if (!action.isHeld && action != StylusButtonAction.NONE) {
            onStylusButtonPressed?.invoke(action)
        }
    }

    /**
     * Button presses while the pen is hovering rather than touching.
     *
     * A stylus reports its button through hover events when it is off the glass, and that is
     * exactly when you would press it to undo - reaching down to touch the screen first would
     * defeat the point of having the button at all.
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val ty = event.getToolType(0)
        if (ty != MotionEvent.TOOL_TYPE_STYLUS && ty != MotionEvent.TOOL_TYPE_ERASER) {
            return super.onGenericMotionEvent(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_BUTTON_PRESS -> {
                noteStylusButton(buttonIndexOf(event.buttonState)); return true
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                noteStylusButton(buttonIndexOf(event.buttonState)); return true
            }
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER ->
                noteStylusButton(buttonIndexOf(event.buttonState))
            MotionEvent.ACTION_HOVER_EXIT -> noteStylusButton(0)
        }
        return super.onGenericMotionEvent(event)
    }

    /** Raised when the TEXT tool is tapped, or an existing text object is double-tapped. */
    var onTextRequested: ((pageX: Float, pageY: Float, existing: Stroke?) -> Unit)? = null

    /** Raised when a table cell is double-tapped, so the host can prompt for its contents. */
    var onCellRequested: ((table: Stroke, row: Int, col: Int) -> Unit)? = null

    /**
     * Raised when a region has been marked for capture, with the rectangle in page coordinates.
     * The host renders that area and hands back an image id.
     */
    var onRegionCaptured: ((page: Int, rect: RectF) -> Unit)? = null

    // ---- transform -----------------------------------------------------------

    private val pageToView = Matrix()
    private val viewToPage = Matrix()
    private val tmpPts = FloatArray(2)
    private var minScale = 0.2f
    private val maxScale = 16f

    // ---- gesture state -------------------------------------------------------

    private var drawingPointerId = -1
    private var drawingIsStylus = false
    private var live: MutableList<InkPoint>? = null
    private var liveKind: StrokeKind = StrokeKind.FREEHAND
    private var lastVelWidth = 0f
    private var smoothedX = Float.NaN
    private var smoothedY = Float.NaN
    private var lastWidth = Float.NaN
    private var lastX = 0f
    private var lastY = 0f
    private var gesturing = false

    /**
     * Wall-clock time until which new strokes are ignored.
     *
     * Fingers never leave the glass together. After a pinch, the surviving finger would otherwise
     * begin a stroke in the moment before it lifts, leaving a stray mark across the page.
     */
    private var suppressDrawUntil = 0L

    /**
     * A first touch that has landed but has not been acted on yet.
     *
     * Two fingers never reach the glass at the same instant. The first one arrives as an ordinary
     * ACTION_DOWN some tens of milliseconds before the second, and acting on it immediately is
     * what produced a blip of drawing at the start of every pinch - and, worse, silently cleared
     * an active selection, because a tap on empty space is how you deselect.
     *
     * So the first touch is *buffered* rather than delayed. Every sample is kept; nothing is
     * dropped and nothing is approximated. If a second finger turns up, the whole thing is
     * discarded having never existed. If it does not, the buffered samples are replayed in order
     * and the stroke is exactly the stroke the user drew, starting where they put their finger
     * down. The only thing deferred is when it appears, by at most [COMMIT_HOLD_MS] - and any
     * real movement commits it sooner, so ordinary drawing never waits.
     *
     * A stylus skips all of this. It cannot be half of a two-finger gesture, and pen latency is
     * the one thing worth protecting absolutely.
     */
    private class PendingTouch(
        val down: MotionEvent,
        val pointerIndex: Int,
        val isStylus: Boolean,
        /** Eraser tip or barrel button; the tool itself is resolved at commit time. */
        val forceErase: Boolean,
        val at: Long
    ) {
        val startX = down.x
        val startY = down.y
        val moves = ArrayList<MotionEvent>(8)

        fun recycle() {
            down.recycle()
            moves.forEach { it.recycle() }
            moves.clear()
        }
    }

    private var pending: PendingTouch? = null
    private val commitSlopPx = 3f * resources.displayMetrics.density

    /** Below this, a placement drag counts as a tap. In page points, so it is zoom-independent. */
    private val placeSlopPt = 6f

    // ---- panning and momentum -------------------------------------------------

    /**
     * Momentum scrolling.
     *
     * [OverScroller] is used rather than hand-rolled decay so the deceleration curve matches
     * every other scrollable surface on the device; a bespoke curve is immediately noticeable as
     * wrong even when nobody can say why.
     */
    private val scroller = android.widget.OverScroller(context)
    private var velocityTracker: android.view.VelocityTracker? = null
    /** Whether the current pan came from a two-finger gesture rather than the Pan tool. */
    private var loosePanning = false

    private var lastFlingX = 0
    private var lastFlingY = 0

    /** Whether releasing a pan carries on scrolling. */
    var flingEnabled: Boolean = true

    /**
     * How far a flick travels, as a multiplier on the release velocity.
     * Above 1 makes long documents quicker to cross.
     */
    var flingScale: Float = 1.35f

    private val minFlingVelocity =
        android.view.ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    private val maxFlingVelocity =
        android.view.ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var lastSpan = 0f
    private var erasedThisGesture = ArrayList<Stroke>()

    /**
     * Fragments the partial eraser produced during the current gesture.
     *
     * A whole-stroke erase only ever takes things away, so one list was enough. Rubbing out part
     * of a stroke also *creates* the pieces that survive, and undo has to put the original back
     * and take those away in the same step.
     */
    private var addedThisGesture = ArrayList<Stroke>()
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    // selection interaction
    private enum class Grab { NONE, MOVE, TL, TR, BL, BR, ROTATE, MARQUEE }
    private var grab = Grab.NONE
    private var marquee: RectF? = null
    private var grabStartPage = floatArrayOf(0f, 0f)
    private var selectionAtGrab: List<Stroke> = emptyList()

    /** Index of each entry of [selectionAtGrab] within [strokes], captured when the drag begins. */
    private var grabIndices: List<Int> = emptyList()
    private var selBoundsAtGrab = RectF()

    // ---- paint ---------------------------------------------------------------

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val pageEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f; color = Color.argb(40, 0, 0, 0)
    }
    private val eraserRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.argb(160, 220, 60, 60)
    }
    /** Dashed outline showing where an armed stamp or symbol will be placed. */
    private val placeFrame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = Color.parseColor("#4C7DF0")
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    private val placeLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4C7DF0")
        textSize = 12f * resources.displayMetrics.density
    }

    // ---- cropping a picture ------------------------------------------------------
    //
    // The crop is a view of the picture, not a change to it: the stored bytes are shared between
    // devices and by other copies of the same image, so trimming them would alter every one of
    // them at once and could never be undone. While cropping, the whole source is shown in the
    // place it would occupy uncropped, which is what lets a crop be widened again later rather
    // than only ever tightened.
    private var cropTargetId: String? = null
    /** Where the entire source picture sits while cropping, in page points. */
    private val cropFull = RectF()
    /** The part being kept, in the same coordinates. */
    private val cropRect = RectF()
    /** 0 none, 1..4 the corners clockwise from top-left, 5 the whole rectangle. */
    private var cropGrab = 0
    private val cropGrabAt = floatArrayOf(0f, 0f)
    var onCropModeChanged: ((Boolean) -> Unit)? = null

    private val cropShade = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    /** Deliberately untinted: a crop is judged against the real picture, not the reading tint. */
    private val imageOverlayPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val cropFrame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = Color.WHITE
    }

    private val selFrame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f
        color = Color.parseColor("#3B82F6")
        pathEffect = DashPathEffect(floatArrayOf(10f, 7f), 0f)
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.WHITE
    }
    private val handleEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.parseColor("#3B82F6")
    }
    private var eraserCursor: FloatArray? = null

    private val handleRadiusPx = 11f * resources.displayMetrics.density
    private val rotateOffsetPx = 34f * resources.displayMetrics.density

    init {
        // No explicit layer type: Views are already GPU accelerated, and forcing a hardware
        // layer allocates an extra full-screen texture for no benefit here.
        isFocusable = true
    }

    // ---- a canvas that grows -------------------------------------------------

    /**
     * When set, the document is one page that enlarges itself to fit what is drawn on it.
     *
     * See [InkCanvas]. The view holds the current extent and reports growth back through
     * [onCanvasGrew]; it does not write anything, because a canvas that rewrote the document
     * every time somebody wrote near an edge would be unusable on a large file.
     */
    var canvas: InkCanvas? = null
        set(value) {
            field = value
            applyCanvasToSlots()
            relayout()
            // Deliberately no clamp here: growing the canvas must not move the camera.
            //
            // This was the whole bug. The clamp centres a document smaller than the viewport and
            // pins one that is larger, so the moment a growing canvas crossed the viewport's size
            // the view jumped - and a view that moves when the page grows puts the edge of the
            // page back under the pen, which grows it again. A flick along an edge became a page
            // hundreds of feet long in under a second.
            //
            // Nothing needs clamping anyway. Growth only ever adds area, and it adds it without
            // moving a single existing coordinate, so the document cannot end up off screen by
            // getting bigger. Where the user was looking is still exactly where they were looking.
            syncInverse()
            invalidate()
        }

    /** Called when the canvas has had to grow, so the host can record the new extent. */
    var onCanvasGrew: ((InkCanvas) -> Unit)? = null

    /** How many times the canvas has stepped outwards during the gesture in progress. */
    private var canvasGrowthThisGesture = 0

    private fun applyCanvasToSlots() {
        val c = canvas
        val slot = slots.firstOrNull() ?: return
        if (c == null) {
            slot.canvasRect = null
            slot.paperRect = null
            return
        }
        slot.canvasRect = RectF(c.left, c.top, c.right, c.bottom)
        slot.paperRect = RectF(c.paperLeft, c.paperTop, c.paperRight, c.paperBottom)
        docBoundsCache = null
    }

    /**
     * Make room for something that has just been drawn.
     *
     * Called with the bounds of each new object rather than by rescanning the page: on a canvas
     * with a term's worth of work on it, walking every stroke to find the extent after every
     * stroke is quadratic in the size of the document, and the answer only ever gets bigger.
     *
     * ## Two guards, both learned the hard way
     *
     * **A request far outside the canvas is refused.** A pen cannot travel thousands of points
     * beyond the paper between one sample and the next; a coordinate that says it did is a bad
     * coordinate, not a gesture, and growing to meet it produces a page the size of a building in
     * a fraction of a second. Refusing costs nothing when the request was genuine, because the
     * next sample asks again from a sane position.
     *
     * **The canvas has a maximum.** Unbounded is a description of how it feels to use, not a
     * promise about the numbers: past a certain size the page stops being a document and starts
     * being a way to lose your work inside a scroll bar.
     */
    private fun growCanvasFor(bounds: RectF) {
        val current = canvas ?: return

        val reach = MAX_GROWTH_STEP_PT
        if (bounds.left < current.left - reach || bounds.top < current.top - reach ||
            bounds.right > current.right + reach || bounds.bottom > current.bottom + reach
        ) {
            com.inkslate.data.EventLog.warn(
                "canvas",
                "Ignored a growth request ${bounds.left.toInt()},${bounds.top.toInt()} far " +
                    "outside the canvas ${current.left.toInt()},${current.top.toInt()}-" +
                    "${current.right.toInt()},${current.bottom.toInt()}"
            )
            return
        }

        val grown = current.grownTo(
            Box(bounds.left, bounds.top, bounds.right, bounds.bottom),
            margin = CANVAS_MARGIN_PT,
            chunk = CANVAS_CHUNK_PT,
            maxSpan = MAX_CANVAS_SPAN_PT
        )
        if (grown == current) return
        canvas = grown
        onCanvasGrew?.invoke(grown)
    }

    /**
     * Paint the part of a canvas the document's own page does not cover.
     *
     * Only the region outside [paperRect] is drawn, so on a page that has not grown yet this
     * costs nothing and the raster is left to speak for itself. The ruling is laid out from the
     * page's own corner rather than the canvas's, which is what keeps the lines drawn here in
     * step with the ones already printed on the page - the seam between them has to be invisible
     * or the whole illusion falls apart.
     */
    private fun drawCanvasPaper(target: Canvas, canvasRect: RectF, paperRect: RectF) {
        val c = canvas ?: return

        val background = com.inkslate.core.PaperPattern.patternOf(c.background)

        canvasPaperPaint.color = c.paperColor
        canvasPaperPaint.colorFilter = pageFilter.filter
        canvasRulingPaint.color = c.lineColor
        canvasRulingPaint.colorFilter = pageFilter.filter

        val sink = object : com.inkslate.core.PaperPattern.Sink {
            override fun paper(left: Float, top: Float, right: Float, bottom: Float) {
                target.drawRect(left, top, right, bottom, canvasPaperPaint)
            }

            override fun lineWidth(width: Float) {
                canvasRulingPaint.strokeWidth = width
            }

            override fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
                target.drawLine(x0, y0, x1, y1, canvasRulingPaint)
            }

            override fun dot(x: Float, y: Float, r: Float) {
                target.drawCircle(x, y, r, canvasPaperPaint.also { it.color = c.lineColor })
                canvasPaperPaint.color = c.paperColor
            }
        }

        // Everything outside the page, in up to four bands. Painting the whole canvas and letting
        // the raster cover the middle would be simpler and would also repaint a full page of
        // ruling behind an opaque bitmap on every frame.
        target.save()
        target.clipRect(canvasRect)
        target.clipOutRect(paperRect)
        com.inkslate.core.PaperPattern.emit(
            background,
            canvasRect.left, canvasRect.top, canvasRect.right, canvasRect.bottom,
            c.spacing,
            anchorX = paperRect.left,
            anchorY = paperRect.top,
            sink = sink
        )
        target.restore()
        canvasPaperPaint.colorFilter = null
        canvasRulingPaint.colorFilter = null
    }

    /** Make room for objects that have just appeared. Cheap and safe to call for any change. */
    private fun growCanvasForAll(added: Collection<Stroke>) {
        if (canvas == null || added.isEmpty()) return
        var box: RectF? = null
        for (st in added) {
            if (st.pageIndex != (slots.firstOrNull()?.index ?: 0)) continue
            val b = st.bounds()
            if (box == null) box = RectF(b) else box.union(b)
        }
        box?.let { growCanvasFor(it) }
    }

    /** Room to pan into: a canvas can be scrolled past its edge, which is where it grows next. */
    private fun reachBounds(): RectF {
        val b = RectF(docBounds())
        if (slots.getOrNull(currentPage)?.canvasRect != null) {
            val s = currentScale().coerceAtLeast(0.01f)
            b.inset(-(width / s) * CANVAS_REACH_FRACTION, -(height / s) * CANVAS_REACH_FRACTION)
        }
        return b
    }

    // ---- content API ---------------------------------------------------------

    /** Declare the document's pages. Bitmaps arrive later via [setPageBitmap]. */
    fun setPages(dims: List<Pair<Float, Float>>, resetView: Boolean) {
        val keep = slots.associate { it.index to it.bitmap }
        slots.clear()
        dims.forEachIndexed { i, (w, h) ->
            slots.add(PageSlot(i, w, h).also { it.bitmap = keep[i] })
        }
        applyCanvasToSlots()
        if (currentPage >= slots.size) currentPage = maxOf(0, slots.size - 1)
        relayout()
        if (resetView) fitWidth() else { clampTranslation(); syncInverse(); invalidate() }
        reportVisiblePages()
    }

    fun setPageBitmap(index: Int, bitmap: Bitmap?) {
        if (index == detailPage) detailRequestedFor = null
        slots.getOrNull(index)?.let { slot ->
            if (slot.bitmap !== bitmap) slot.bitmap?.recycle()
            slot.bitmap = bitmap
            if (bitmap != null && slot.cropRect == null) {
                slot.cropRect = runCatching {
                    detectContentBox(bitmap, slot.width, slot.height)
                }.getOrNull()
                slot.cropEnabled = cropMargins
                if (cropMargins && slot.cropRect != null) relayout()
            }
            invalidate()
        }
    }

    fun hasBitmap(index: Int) = slots.getOrNull(index)?.bitmap?.takeIf { !it.isRecycled } != null

    fun releasePageBitmap(index: Int) {
        slots.getOrNull(index)?.let { it.bitmap?.recycle(); it.bitmap = null }
    }

    /** How many pages currently hold a bitmap. */
    fun residentPageCount(): Int = slots.count { it.bitmap != null }

    /** Bytes currently held by rasterised pages. */
    fun residentBitmapBytes(): Long =
        slots.sumOf { (it.bitmap?.byteCount ?: 0).toLong() }

    /**
     * Keep [keep] plus whatever nearby pages fit in [budgetBytes]; free the rest.
     *
     * Retention is by memory rather than by a page count, because page sizes vary enormously and
     * a fixed count either wastes memory on small pages or blows the heap on large ones. Keeping
     * a margin around the viewport is what stops scrolling back one page from re-rendering it.
     */
    fun retainBitmaps(keep: List<Int>, budgetBytes: Long) {
        if (slots.isEmpty()) return
        val keepSet = keep.toHashSet()
        val anchor = keep.minOrNull() ?: currentPage

        // nearest-to-the-viewport first, so distant pages are the ones dropped
        val ordered = slots
            .filter { it.bitmap != null }
            .sortedBy { slot ->
                if (slot.index in keepSet) -1 else kotlin.math.abs(slot.index - anchor)
            }

        var used = 0L
        for (slot in ordered) {
            val size = (slot.bitmap?.byteCount ?: 0).toLong()
            // visible pages are never evicted, whatever the budget says
            if (slot.index in keepSet || used + size <= budgetBytes) {
                used += size
            } else {
                slot.bitmap?.recycle()
                slot.bitmap = null
            }
        }
    }

    fun setLayout(newLayout: PageLayout) {
        if (layout == newLayout) return
        layout = newLayout
        relayout()
        fitWidth()
        reportVisiblePages()
    }

    fun goToPage(index: Int) {
        val target = index.coerceIn(0, maxOf(0, slots.size - 1))
        currentPage = target
        syncCurrentDims()
        if (layout == PageLayout.SINGLE) {
            relayout(); fitWidth()
        } else {
            // scroll the page into view without changing zoom
            val slot = slots.getOrNull(target) ?: return
            val v = RectF(slot.rect)
            pageToView.mapRect(v)
            pageToView.postTranslate(-(v.left - 8f), -(v.top - 8f))
            clampTranslation(); syncInverse(); invalidate()
        }
        onCurrentPageChanged?.invoke(target)
        reportVisiblePages()
    }

    val pageCount: Int get() = slots.size

    private var docBoundsCache: RectF? = null

    private fun relayout() {
        PageArranger.arrange(slots, layout, currentPage)
        docBoundsCache = null
        syncCurrentDims()
    }

    private fun syncCurrentDims() {
        slots.getOrNull(currentPage)?.let {
            pageWidthPt = it.width
            pageHeightPt = it.height
        }
    }

    /**
     * Overall document extent, cached.
     *
     * This is read during panning and clamping, so on a thousand-page book recomputing it meant
     * unioning a thousand rectangles on every single frame.
     */
    private fun docBounds(): RectF =
        docBoundsCache ?: PageArranger.bounds(slots, layout, currentPage).also { docBoundsCache = it }

    /** Origin of a page in document space. */
    private fun originOf(pageIndex: Int): FloatArray {
        val slot = slots.getOrNull(pageIndex) ?: return floatArrayOf(0f, 0f)
        // subtract the crop so page-local coordinates stay relative to the whole page
        return floatArrayOf(slot.originX - slot.cropLeft, slot.originY - slot.cropTop)
    }

    /** Page under a document-space point, or the nearest one when between pages. */
    private fun pageAtDoc(dx: Float, dy: Float): Int {
        if (slots.isEmpty()) return 0
        slots.firstOrNull { it.contains(dx, dy) }?.let { return it.index }
        return slots.minByOrNull { it.distanceSq(dx, dy) }?.index ?: 0
    }


    /**
     * Track which page the viewport is centred on.
     *
     * This no longer decides what to load - [onDraw] does, from the pages it actually painted.
     * Keeping the page indicator here is fine because being briefly wrong about the page number
     * is cosmetic, whereas being wrong about what to load leaves the screen blank.
     */
    private fun reportVisiblePages() {
        if (slots.isEmpty()) return

        // the page occupying the middle of the screen is "current" for the indicator
        if (layout.isContinuous) {
            val centre = floatArrayOf(width / 2f, height / 2f)
            viewToPage.mapPoints(centre)
            val centred = pageAtDoc(centre[0], centre[1])
            if (centred != currentPage) {
                currentPage = centred
                syncCurrentDims()
                onCurrentPageChanged?.invoke(centred)
            }
        }
    }

    fun setStrokes(list: List<Stroke>) {
        strokes.clear(); strokes.addAll(list)
        pageIndexDirty = true
        ids.seedFrom(list.map { it.id })
        undoStack.clear(); redoStack.clear(); selection.clear()
        invalidate(); onSelectionChanged?.invoke(0)
    }

    fun strokesSnapshot(): List<Stroke> = ArrayList(strokes)
    fun isEmpty(): Boolean = strokes.isEmpty()
    fun canUndo() = undoStack.isNotEmpty()
    fun canRedo() = redoStack.isNotEmpty()
    fun selectionCount() = selection.size
    fun selectedStrokes(): List<Stroke> = strokes.filter { it.id in selection }

    private fun now() = System.currentTimeMillis()

    fun undo() {
        val op = undoStack.removeLastOrNull() ?: return
        strokes.removeAll(op.added.toSet()); strokes.addAll(op.removed)
        redoStack.add(op); clearSelection(); changed()
    }

    fun redo() {
        val op = redoStack.removeLastOrNull() ?: return
        strokes.removeAll(op.removed.toSet()); strokes.addAll(op.added)
        undoStack.add(op); clearSelection(); changed()
    }

    fun clearPage() {
        if (strokes.isEmpty()) return
        pushOp(Op(emptyList(), ArrayList(strokes)))
        strokes.clear(); clearSelection(); changed()
    }

    fun clearSelection() {
        if (selection.isEmpty()) return
        selection.clear(); onSelectionChanged?.invoke(0); invalidate()
    }

    fun selectAll() {
        selection.clear(); strokes.forEach { selection.add(it.id) }
        onSelectionChanged?.invoke(selection.size); invalidate()
    }

    fun deleteSelection() {
        if (selection.isEmpty()) return
        val removed = selectedStrokes()
        strokes.removeAll(removed.toSet())
        pushOp(Op(emptyList(), removed))
        clearSelection(); changed()
    }

    fun duplicateSelection() {
        if (selection.isEmpty()) return
        val m = Matrix().apply { setTranslate(14f, 14f) }
        val copies = selectedStrokes().map { it.transformed(m, ids.next()).copy(updatedUtc = now()) }
        strokes.addAll(copies)
        growCanvasForAll(copies)
        pushOp(Op(copies, emptyList()))
        selection.clear(); copies.forEach { selection.add(it.id) }
        onSelectionChanged?.invoke(selection.size); changed()
    }

    /** Restyle everything currently selected, as one undo step. */
    fun restyleSelection(
        newColor: Int? = null, newWidth: Float? = null, newOpacity: Float? = null,
        newDash: DashStyle? = null, newFill: FillStyle? = null, newFillColor: Int? = null,
        newBrush: BrushType? = null
    ) {
        if (selection.isEmpty()) return
        val before = selectedStrokes()
        val after = before.map {
            it.copy(
                color = newColor ?: it.color,
                baseWidth = newWidth ?: it.baseWidth,
                opacity = newOpacity ?: it.opacity,
                dash = newDash ?: it.dash,
                fill = newFill ?: it.fill,
                fillColor = newFillColor ?: it.fillColor,
                brush = if (it.isFreehand) (newBrush ?: it.brush) else it.brush,
                updatedUtc = now()
            )
        }
        strokes.removeAll(before.toSet()); strokes.addAll(after)
        growCanvasForAll(after)
        pushOp(Op(after, before)); changed()
    }

    /** True when the selection is exactly one picture, which is the only thing worth cropping. */
    fun croppableSelection(): Boolean {
        val sel = selectedStrokes()
        return sel.size == 1 && sel[0].kind == StrokeKind.IMAGE && sel[0].imageId != null
    }

    fun isCropping(): Boolean = cropTargetId != null

    /**
     * Enters crop mode on the selected picture. Returns false when there is nothing to crop.
     */
    fun beginCrop(): Boolean {
        val s = selectedStrokes().singleOrNull() ?: return false
        if (s.kind != StrokeKind.IMAGE || s.imageId == null) return false

        val shown = s.rectOf()
        // Work back from the visible rectangle to where the whole picture would sit. The stroke
        // shows the sub-rect [cropLeft..cropRight] of it, so the full frame is that much larger.
        val fw = (s.cropRight - s.cropLeft).coerceAtLeast(0.001f)
        val fh = (s.cropBottom - s.cropTop).coerceAtLeast(0.001f)
        val fullW = shown.width() / fw
        val fullH = shown.height() / fh
        val left = shown.left - s.cropLeft * fullW
        val top = shown.top - s.cropTop * fullH
        cropFull.set(left, top, left + fullW, top + fullH)
        cropRect.set(shown)
        cropTargetId = s.id
        cropGrab = 0
        onCropModeChanged?.invoke(true)
        invalidate()
        return true
    }

    fun cancelCrop() {
        if (cropTargetId == null) return
        cropTargetId = null
        cropGrab = 0
        onCropModeChanged?.invoke(false)
        invalidate()
    }

    /** Widens the crop back out to the whole picture, without leaving crop mode. */
    fun resetCrop() {
        if (cropTargetId == null) return
        cropRect.set(cropFull)
        invalidate()
    }

    /**
     * Commits the crop as one undo step. The stroke keeps the position and scale it has on
     * screen: the kept rectangle becomes its new frame, so the picture does not jump or resize.
     */
    fun applyCrop() {
        val id = cropTargetId ?: return
        val before = strokes.firstOrNull { it.id == id }
        if (before == null) { cancelCrop(); return }

        val w = cropFull.width()
        val h = cropFull.height()
        if (w <= 0f || h <= 0f) { cancelCrop(); return }

        // The kept rectangle as fractions of the whole picture. Clamped, because a handle can be
        // dragged past the edge and a crop outside the source is meaningless.
        val l = ((cropRect.left - cropFull.left) / w).coerceIn(0f, 1f)
        val t = ((cropRect.top - cropFull.top) / h).coerceIn(0f, 1f)
        val r = ((cropRect.right - cropFull.left) / w).coerceIn(0f, 1f)
        val b = ((cropRect.bottom - cropFull.top) / h).coerceIn(0f, 1f)
        if (r - l < 0.01f || b - t < 0.01f) { cancelCrop(); return }

        val after = before.copy(
            points = listOf(
                InkPoint(cropRect.left, cropRect.top, 1f),
                InkPoint(cropRect.right, cropRect.bottom, 1f)
            ),
            cropLeft = l, cropTop = t, cropRight = r, cropBottom = b,
            updatedUtc = now()
        )
        strokes.remove(before); strokes.add(after)
        growCanvasForAll(listOf(after))
        pushOp(Op(listOf(after), listOf(before)))
        cropTargetId = null
        cropGrab = 0
        onCropModeChanged?.invoke(false)
        changed()
    }

    /** Handle radius on screen. Comfortable for a fingertip without hiding the corner. */
    private val cropHandleRadius get() = 9f * resources.displayMetrics.density

    /** The four corners of [r], clockwise from top-left. */
    private fun cropCornersOf(r: RectF): List<FloatArray> = listOf(
        floatArrayOf(r.left, r.top), floatArrayOf(r.right, r.top),
        floatArrayOf(r.right, r.bottom), floatArrayOf(r.left, r.bottom)
    )

    /**
     * What is under a touch at page point [px], [py]: a corner (1..4), the rectangle itself (5),
     * or nothing (0). The tolerance is converted from screen to page points, so a handle stays
     * the same size under the finger however far the page is zoomed.
     */
    private fun cropHandleAt(px: Float, py: Float): Int {
        val tol = cropHandleRadius * 1.6f / currentScale().coerceAtLeast(0.01f)
        cropCornersOf(cropRect).forEachIndexed { i, c ->
            if (abs(px - c[0]) <= tol && abs(py - c[1]) <= tol) return i + 1
        }
        return if (cropRect.contains(px, py)) 5 else 0
    }

    /** Moves whatever [cropGrab] took hold of to the page point [px], [py]. */
    private fun dragCrop(px: Float, py: Float) {
        // A crop narrower than this is almost certainly a slip, and one of zero width would
        // render as nothing at all.
        val min = 8f
        when (cropGrab) {
            1 -> { cropRect.left = px.coerceIn(cropFull.left, cropRect.right - min)
                   cropRect.top = py.coerceIn(cropFull.top, cropRect.bottom - min) }
            2 -> { cropRect.right = px.coerceIn(cropRect.left + min, cropFull.right)
                   cropRect.top = py.coerceIn(cropFull.top, cropRect.bottom - min) }
            3 -> { cropRect.right = px.coerceIn(cropRect.left + min, cropFull.right)
                   cropRect.bottom = py.coerceIn(cropRect.top + min, cropFull.bottom) }
            4 -> { cropRect.left = px.coerceIn(cropFull.left, cropRect.right - min)
                   cropRect.bottom = py.coerceIn(cropRect.top + min, cropFull.bottom) }
            5 -> {
                // Moving the whole rectangle: it slides within the picture and stops at the
                // edges rather than being allowed to leave it and crop nothing.
                var dx = px - cropGrabAt[0]
                var dy = py - cropGrabAt[1]
                dx = dx.coerceIn(cropFull.left - cropRect.left, cropFull.right - cropRect.right)
                dy = dy.coerceIn(cropFull.top - cropRect.top, cropFull.bottom - cropRect.bottom)
                cropRect.offset(dx, dy)
                cropGrabAt[0] = px; cropGrabAt[1] = py
            }
        }
    }

    fun bringSelectionToFront() {
        if (selection.isEmpty()) return
        val sel = selectedStrokes()
        strokes.removeAll(sel.toSet()); strokes.addAll(sel); changed()
    }

    fun sendSelectionToBack() {
        if (selection.isEmpty()) return
        val sel = selectedStrokes()
        strokes.removeAll(sel.toSet()); strokes.addAll(0, sel); changed()
    }

    /**
     * Place a text box. [boxWidth] of 0 means the text never wraps and the box hugs its content.
     */
    fun addText(
        pageX: Float,
        pageY: Float,
        text: String,
        size: Float,
        textColor: Int,
        bold: Boolean = false,
        italic: Boolean = false,
        font: TextFont = TextFont.SANS,
        align: TextAlign = TextAlign.LEFT,
        boxWidth: Float = 0f,
        boxFillColor: Int = android.graphics.Color.TRANSPARENT,
        boxBorder: Boolean = false
    ) {
        if (text.isBlank()) return
        val s = Stroke(
            id = ids.next(), kind = StrokeKind.TEXT, color = textColor, baseWidth = 1f,
            points = listOf(InkPoint(pageX, pageY, 1f)), text = text, textSize = size,
            bold = bold, italic = italic, font = font, align = align, pageIndex = livePage,
            boxWidth = boxWidth, boxFillColor = boxFillColor, boxBorder = boxBorder,
            opacity = opacity, updatedUtc = now()
        )
        strokes.add(s); pushOp(Op(listOf(s), emptyList()))
        growCanvasForAll(listOf(s)); changed()
    }

    /** Flash a set of rectangles on a page, to show where a search result sits. */
    fun flashHighlight(page: Int, rects: List<RectF>) {
        highlightPage = page
        highlightRects = rects
        invalidate()
        // fades on its own: a marker that stays put becomes indistinguishable from real ink
        postDelayed({ clearHighlight() }, 4000L)
    }

    fun clearHighlight() {
        if (highlightRects.isEmpty()) return
        highlightRects = emptyList()
        highlightPage = -1
        invalidate()
    }

    /** Place a captured image on [page], at [rect] in that page's coordinates. */
    fun addImage(page: Int, rect: RectF, imageId: String) {
        val s = Stroke(
            id = ids.next(), kind = StrokeKind.IMAGE, color = Color.BLACK, baseWidth = 1f,
            points = listOf(
                InkPoint(rect.left, rect.top, 1f),
                InkPoint(rect.right, rect.bottom, 1f)
            ),
            imageId = imageId, pageIndex = page, opacity = 1f,
            updatedUtc = System.currentTimeMillis()
        )
        strokes.add(s)
        pushOp(Op(listOf(s), emptyList()))
        selection.clear(); selection.add(s.id)
        tool = Tool.SELECT
        onSelectionChanged?.invoke(1)
        changed()
    }

    /**
     * Render an area of the document exactly as it appears, for region capture.
     * Uses the same draw path as the screen, so what is captured is what was seen.
     */
    fun captureRegion(page: Int, rect: RectF, maxPx: Int = 1600): Bitmap? {
        val slot = slots.getOrNull(page) ?: return null
        if (rect.width() <= 0f || rect.height() <= 0f) return null
        val scale = (maxPx / max(rect.width(), rect.height())).coerceIn(0.5f, 4f)
        val w = (rect.width() * scale).toInt().coerceIn(8, 4096)
        val h = (rect.height() * scale).toInt().coerceIn(8, 4096)

        return runCatching {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawColor(Color.WHITE)
            c.scale(scale, scale)
            c.translate(-rect.left, -rect.top)

            slot.bitmap?.takeIf { !it.isRecycled }?.let {
                c.drawBitmap(it, null, RectF(0f, 0f, slot.width, slot.height), bitmapPaint)
            }
            val previousFilter = StrokeRasteriser.colorFilter
            StrokeRasteriser.colorFilter = null    // capture true colours, not the reading tint
            strokesByPage[page].orEmpty()
                .filter { it.kind != StrokeKind.IMAGE || it.imageId != null }
                .forEach { StrokeRasteriser.draw(c, it) }
            StrokeRasteriser.colorFilter = previousFilter
            out
        }.getOrNull()
    }

    /**
     * Arm a stamp for placement rather than dropping it somewhere and hoping.
     *
     * The old behaviour put it in the middle of the viewport at a size the app picked, which
     * meant every single use was followed by a drag and a resize.
     */
    fun insertStamp(kind: Stamps.Kind, options: Stamps.StampOptions = kind.defaults) =
        armStamp(kind, options)

    // ---- clipboard -----------------------------------------------------------

    fun copySelection(): Int {
        val sel = selectedStrokes()
        if (sel.isEmpty()) return 0
        InkClipboard.put(sel, pageWidthPt, pageHeightPt)
        return sel.size
    }

    fun cutSelection(): Int {
        val n = copySelection()
        if (n > 0) deleteSelection()
        return n
    }

    fun canPaste(): Boolean = !InkClipboard.isEmpty

    /**
     * Paste at the centre of what is currently on screen, so the result lands where the user is
     * looking rather than wherever it happened to be copied from.
     */
    fun paste(): Int {
        if (InkClipboard.isEmpty) return 0
        val c = viewCenterInPage()
        val pasted = InkClipboard.paste(ids, c[0], c[1], pageWidthPt, pageHeightPt)
        if (pasted.isEmpty()) return 0
        strokes.addAll(pasted)
        growCanvasForAll(pasted)
        pushOp(Op(pasted, emptyList()))
        selection.clear(); pasted.forEach { selection.add(it.id) }
        tool = Tool.SELECT
        onSelectionChanged?.invoke(selection.size)
        changed()
        return pasted.size
    }

    /** Nudge the selection by whole page points, for keyboard arrows and fine positioning. */
    fun nudgeSelection(dx: Float, dy: Float) {
        if (selection.isEmpty()) return
        val before = selectedStrokes()
        val m = Matrix().apply { setTranslate(dx, dy) }
        val after = before.map { it.transformed(m).copy(updatedUtc = now()) }
        strokes.removeAll(before.toSet()); strokes.addAll(after)
        growCanvasForAll(after)
        selection.clear(); after.forEach { selection.add(it.id) }
        pushOp(Op(after, before)); changed()
    }

    fun deleteStroke(target: Stroke) {
        val i = strokes.indexOfFirst { it.id == target.id }
        if (i < 0) return
        val removed = strokes.removeAt(i)
        selection.remove(removed.id)
        pushOp(Op(emptyList(), listOf(removed)))
        onSelectionChanged?.invoke(selection.size)
        changed()
    }

    fun replaceStroke(old: Stroke, new: Stroke) {
        val i = strokes.indexOfFirst { it.id == old.id }
        if (i < 0) return
        strokes[i] = new
        pushOp(Op(listOf(new), listOf(old)))
        changed()
    }

    /** Drop a table centred in the current viewport, sized to fit comfortably. */
    fun insertTable(rows: Int, cols: Int) {
        val c = viewCenterInPage()
        val w = min(pageWidthPt * 0.72f, cols * 74f)
        val h = min(pageHeightPt * 0.55f, rows * 30f)
        val s = Stroke(
            id = ids.next(), kind = StrokeKind.TABLE, color = color,
            baseWidth = max(0.8f, resolveDrawWidth() * 0.6f),
            points = listOf(
                InkPoint(c[0] - w / 2f, c[1] - h / 2f, 0f),
                InkPoint(c[0] + w / 2f, c[1] + h / 2f, 0f)
            ),
            rows = rows, cols = cols, cells = List(rows * cols) { "" }, pageIndex = livePage,
            dash = dash, opacity = opacity, textSize = textSize, updatedUtc = now()
        )
        strokes.add(s); pushOp(Op(listOf(s), emptyList()))
        selection.clear(); selection.add(s.id)
        tool = Tool.SELECT
        onSelectionChanged?.invoke(1); changed()
    }

    // ---- viewport ------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (ow == 0 || oh == 0) fitWidth() else { clampTranslation(); syncInverse() }
        // A camera asked for before the view had been measured is applied here, the moment it
        // can be. Without this, reopening a document was a race the restore usually lost on a
        // cold start, and the position was silently dropped rather than visibly wrong - which is
        // the harder kind of bug to notice.
        pendingCamera?.let { pendingCamera = null; restoreCamera(it) }
        reportVisiblePages()
    }

    // ---- remembering where you were ------------------------------------------

    /**
     * The camera, as scale and offset in document space.
     *
     * Deliberately not the raw matrix. The view only ever pans and zooms, so three numbers say
     * everything, and they survive the document being reopened at a different window size - which
     * a matrix full of view pixels would not.
     */
    /** A camera asked for before the view had a size, waiting for one. */
    private var pendingCamera: FloatArray? = null

    fun cameraState(): FloatArray? {
        if (slots.isEmpty() || width == 0) return null
        val s = currentScale()
        if (s <= 0f || !s.isFinite()) return null
        // The document point currently under the top-left corner of the viewport.
        toDoc(0f, 0f)
        val x = tmpPts[0]
        val y = tmpPts[1]
        if (!x.isFinite() || !y.isFinite()) return null
        return floatArrayOf(s, x, y)
    }

    /**
     * Put the camera back where [state] says, as far as the document allows.
     *
     * Clamped afterwards, so a position saved against a document that has since been rearranged
     * or grown lands somewhere sensible rather than off in empty space.
     */
    fun restoreCamera(state: FloatArray): Boolean {
        if (state.size < 3) return false
        if (slots.isEmpty() || width == 0 || height == 0) {
            // Not measured yet. Hold on to it rather than refusing: the caller has no better
            // moment to try, and onSizeChanged does.
            pendingCamera = state
            return true
        }
        val s = state[0]
        if (!s.isFinite() || s <= 0f) return false
        val scale = s.coerceIn(minScale.coerceAtMost(s), maxScale)
        pageToView.reset()
        pageToView.postScale(scale, scale)
        pageToView.postTranslate(-state[1] * scale, -state[2] * scale)
        clampTranslation()
        syncInverse()
        invalidate()
        onTransformChanged?.invoke()
        reportVisiblePages()
        return true
    }

    /** Back to how the document opens by default: the start of it, at a readable width. */
    fun resetView() {
        if (slots.isEmpty()) return
        // Asking for the default view outranks a restore that has not landed yet.
        pendingCamera = null
        if (currentPage != 0 && layout == PageLayout.SINGLE) {
            currentPage = 0
            syncCurrentDims()
            relayout()
            onCurrentPageChanged?.invoke(0)
        }
        fitWidth()
        // fitWidth frames the document's width; for a continuous layout that leaves the scroll
        // wherever it was, which is not what "reset" means to anyone.
        val b = docBounds()
        val corner = floatArrayOf(b.left, b.top)
        pageToView.mapPoints(corner)
        pageToView.postTranslate(8f - corner[0], 8f - corner[1])
        clampTranslation()
        syncInverse()
        invalidate()
        onTransformChanged?.invoke()
        reportVisiblePages()
    }

    fun fitToScreen() {
        if (width == 0 || height == 0 || slots.isEmpty()) return
        val b = docBounds()
        val s = min((width - 24f) / b.width(), (height - 24f) / b.height())
        minScale = s * 0.35f
        pageToView.reset(); pageToView.postScale(s, s)
        pageToView.postTranslate(
            (width - b.width() * s) / 2f - b.left * s,
            (height - b.height() * s) / 2f - b.top * s
        )
        // no clamp here: fitting is an explicit request to centre
        syncInverse(); invalidate(); onTransformChanged?.invoke(); reportVisiblePages()
    }

    fun fitWidth() {
        if (width == 0 || height == 0 || slots.isEmpty()) return
        val b = docBounds()
        minScale = min((width - 24f) / b.width(), (height - 24f) / b.height()) * 0.35f
        val s = (width - 16f) / b.width()
        pageToView.reset(); pageToView.postScale(s, s)
        pageToView.postTranslate(8f - b.left * s, 8f - b.top * s)
        clampTranslation(); syncInverse(); invalidate()
        onTransformChanged?.invoke(); reportVisiblePages()
    }

    fun currentScale(): Float {
        val v = FloatArray(9); pageToView.getValues(v)
        return hypot(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y])
    }

    // ---- width that follows the zoom -----------------------------------------

    /**
     * The zoom at which the width slider means literal page points.
     *
     * The document filling the viewport's width - the same framing "Fit width" gives you - is the
     * anchor, for two reasons. It is a property of the document rather than of what the user
     * happened to be doing when they last touched the slider, so a stroke is the same thickness
     * today as it was yesterday; and it makes the dynamic and fixed settings agree exactly at the
     * zoom most reading is done at, so turning the switch on is not a jump.
     */
    private fun widthReferenceScale(): Float {
        if (width == 0 || slots.isEmpty()) return currentScale()
        val b = docBounds()
        if (b.width() <= 0f) return currentScale()
        return ((width - 16f) / b.width()).coerceAtLeast(0.001f)
    }

    /**
     * The page-space width a stroke started right now should be built at.
     *
     * With [ToolConfig.dynamicWidth] off this is simply the slider. With it on, the zoom is
     * divided back out, so what stays constant is the thickness on the glass - which is the thing
     * the hand is actually judging when it decides a line is too fat for the gap it is going in.
     * The slider itself is never touched: it goes on meaning "this thick, as seen".
     */
    private fun resolveDrawWidth(): Float = com.inkslate.core.DynamicWidth.resolve(
        nominal = strokeWidth,
        referenceScale = widthReferenceScale(),
        currentScale = currentScale(),
        enabled = config.dynamicWidth
    )

    /** Same idea for the rubber, so one switch does not leave the two disagreeing. */
    private fun resolveEraserRadius(): Float = com.inkslate.core.DynamicWidth.resolve(
        nominal = eraserRadius,
        referenceScale = widthReferenceScale(),
        currentScale = currentScale(),
        enabled = config.dynamicWidth,
        min = 0.2f,
        max = 900f
    )

    /**
     * The width in force for the stroke being drawn.
     *
     * Latched when the pen lands rather than recomputed per sample: the canvas can grow mid-stroke
     * when you write off the edge of the page, which moves the reference, and a stroke that
     * changes thickness because the page got bigger underneath it is not what anyone drew.
     */
    private var liveWidth: Float = 2f

    /** The eraser's reach for the gesture in progress, on the same terms. */
    private var liveEraser: Float = 8f

    fun zoomBy(factor: Float) {
        val s = currentScale()
        val f = (s * factor).coerceIn(minScale, maxScale) / s
        pageToView.postScale(f, f, width / 2f, height / 2f)
        clampTranslation(); syncInverse(); invalidate()
        onTransformChanged?.invoke(); reportVisiblePages()
    }

    /**
     * Centre of the viewport, in the coordinates of the page beneath it, as [x, y].
     * Also sets the live page, so anything placed here lands on the page being looked at.
     */
    fun viewCentreOnPage(): FloatArray = viewCenterInPage()

    private fun viewCenterInPage(): FloatArray {
        toDoc(width / 2f, height / 2f)
        livePage = pageAtDoc(tmpPts[0], tmpPts[1])
        val o = originOf(livePage)
        return floatArrayOf(tmpPts[0] - o[0], tmpPts[1] - o[1])
    }

    // ---- rendering -----------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frameStart = System.nanoTime()
        var drawnCount = 0
        val drawnPages = ArrayList<Int>(8)
        canvas.drawColor(pageFilter.backdropColor)
        if (slots.isEmpty()) return

        rebuildPageIndex()

        // Read the damaged region BEFORE the canvas is transformed, so it is in view space and
        // can be mapped to document space exactly once. Reading it afterwards yields document
        // coordinates already, and mapping those a second time produces a rectangle unrelated to
        // the screen - which silently culls content that is plainly visible.
        val clip = canvas.clipBounds
        val viewportDoc = RectF(
            clip.left.toFloat(), clip.top.toFloat(),
            clip.right.toFloat(), clip.bottom.toFloat()
        )
        viewToPage.mapRect(viewportDoc)

        // What is on screen, independent of what is being repainted this frame.
        //
        // Culling uses the damaged region, which during a stroke is a small rectangle. Deciding
        // which pages are "visible" from that would under-report them, and the retention pass
        // would then free a page that is plainly on screen - part of the document going white
        // while you draw on the page next to it.
        val fullViewportDoc = RectF(0f, 0f, width.toFloat(), height.toFloat())
        viewToPage.mapRect(fullViewportDoc)
        // Generous margin: stroke outlines extend past their centreline, and a culling test is
        // only ever worth doing if it cannot remove something that should be on screen.
        viewportDoc.inset(-CULL_MARGIN_PT, -CULL_MARGIN_PT)

        // Safety net. Culling is an optimisation, and an optimisation that hides real content is
        // strictly worse than no optimisation. If the computed viewport looks implausible -
        // empty, non-finite, or nowhere near the document - draw everything and say so in the
        // diagnostics rather than quietly dropping what the user can plainly see.
        val cullingSane = viewportDoc.width() > 0f && viewportDoc.height() > 0f &&
            viewportDoc.left.isFinite() && viewportDoc.top.isFinite() &&
            viewportDoc.right.isFinite() && viewportDoc.bottom.isFinite() &&
            RectF.intersects(viewportDoc, docBounds())
        if (!cullingSane) {
            RenderStats.cullingFallbacks++
            viewportDoc.set(docBounds())
            viewportDoc.inset(-CULL_MARGIN_PT, -CULL_MARGIN_PT)
        }

        StrokeRasteriser.colorFilter = pageFilter.filter

        canvas.save()
        canvas.concat(pageToView)

        for (slot in slots) {
            if (RectF.intersects(slot.rect, fullViewportDoc)) drawnPages.add(slot.index)
            if (cullingSane && !RectF.intersects(slot.rect, viewportDoc)) continue

            canvas.save()
            canvas.translate(slot.originX, slot.originY)
            // Shift so the content box starts at the slot origin. Ink keeps full-page
            // coordinates, so cropping never moves a stroke relative to the text it annotates.
            canvas.translate(-slot.cropLeft, -slot.cropTop)

            val canvasRect = slot.canvasRect
            // Where the document's own page actually is. For an ordinary page that is the page;
            // for a canvas that has grown it is the part the raster covers, and everything
            // outside it is paper this view draws itself.
            val pageRect = slot.paperRect?.let { RectF(it) } ?: RectF(0f, 0f, slot.width, slot.height)

            if (canvasRect != null) drawCanvasPaper(canvas, canvasRect, pageRect)

            fillPaint.color = Color.WHITE
            fillPaint.alpha = 255
            fillPaint.colorFilter = pageFilter.filter
            canvas.drawRect(pageRect, fillPaint)
            fillPaint.colorFilter = null
            slot.bitmap?.takeIf { !it.isRecycled }
                ?.let { canvas.drawBitmap(it, null, pageRect, bitmapPaint) }
            // The edge belongs to the whole surface, so on a canvas it goes round the canvas.
            canvas.drawRect(canvasRect ?: pageRect, pageEdge)

            // Clip so ink cannot bleed from one page onto its neighbour - except on a canvas,
            // which has no neighbour and where the clip would instead hide the end of a stroke
            // that has run past the edge in the moment before the canvas catches up with it.
            canvas.save()
            if (canvasRect == null) {
                canvas.clipRect(
                    slot.cropLeft, slot.cropTop,
                    slot.cropLeft + slot.visibleWidth, slot.cropTop + slot.visibleHeight
                )
            }

            // Cull to what is actually on screen. Zoomed in on a dense page this is the
            // difference between drawing a handful of strokes and drawing all of them.
            val localViewport = RectF(
                viewportDoc.left - slot.originX + slot.cropLeft,
                viewportDoc.top - slot.originY + slot.cropTop,
                viewportDoc.right - slot.originX + slot.cropLeft,
                viewportDoc.bottom - slot.originY + slot.cropTop
            )
            val onPage = strokesByPage[slot.index].orEmpty()
            for (st in onPage) {
                if (st.isHighlighter && visible(st, localViewport)) {
                    StrokeRasteriser.draw(canvas, st); drawnCount++
                }
            }
            for (st in onPage) {
                if (!st.isHighlighter && visible(st, localViewport)) {
                    StrokeRasteriser.draw(canvas, st); drawnCount++
                }
            }
            live?.let { pts ->
                if (pts.isNotEmpty() && livePage == slot.index) {
                    StrokeRasteriser.drawLive(canvas, liveStroke(pts))
                }
            }
            if (highlightPage == slot.index && highlightRects.isNotEmpty()) {
                fillPaint.reset(); fillPaint.isAntiAlias = true
                fillPaint.color = Color.argb(90, 255, 196, 0)
                highlightRects.forEach { canvas.drawRect(it, fillPaint) }
            }
            canvas.restore()
            canvas.restore()
        }
        canvas.restore()

        drawSelectionChrome(canvas)

        drawRuler(canvas)

        eraserCursor?.let { c ->
            canvas.drawCircle(c[0], c[1], liveEraser * currentScale(), eraserRing)
        }
        marquee?.let { r ->
            val o = originOf(livePage)
            val v = RectF(r); v.offset(o[0], o[1]); pageToView.mapRect(v)
            canvas.drawRect(v, selFrame)
        }

        // Where the armed stamp or symbol will land, shown while it is being dragged out. Seeing
        // the space it will occupy before letting go is the whole point of dragging to place.
        placing?.let { r ->
            val item = armedPlacement
            val previewAspect = when (item) {
                is Placement.StampItem -> Stamps.aspectFor(item.kind, item.options)
                is Placement.ImageItem -> item.aspect
                else -> 0f
            }
            val box = if (previewAspect > 0f && r.width() > placeSlopPt &&
                r.height() > placeSlopPt
            ) {
                val anchor = placeStart
                if (anchor != null) fitAspect(r, previewAspect, anchor[0], anchor[1])
                else fitAspect(r, previewAspect, r.left, r.top)
            } else RectF(r)

            val o = originOf(livePage)
            val v = RectF(box); v.offset(o[0], o[1]); pageToView.mapRect(v)
            canvas.drawRect(v, placeFrame)
            if (item != null && v.width() > 40f && v.height() > 24f) {
                canvas.drawText(item.label, v.left + 8f, v.top + placeLabel.textSize + 6f, placeLabel)
            }
        }

        // The crop overlay: the whole picture, with everything outside the kept rectangle
        // dimmed. Drawn last so it sits over the page and over any selection frame.
        cropTargetId?.let { id ->
            val target = strokes.firstOrNull { it.id == id }
            if (target != null) {
                val o = originOf(target.pageIndex)
                val full = RectF(cropFull); full.offset(o[0], o[1]); pageToView.mapRect(full)
                val keep = RectF(cropRect); keep.offset(o[0], o[1]); pageToView.mapRect(keep)

                // The source at full extent, so a crop can be widened as well as tightened.
                val bmp = target.imageId?.let { StrokeRasteriser.imageResolver?.invoke(it) }
                if (bmp != null && !bmp.isRecycled) {
                    canvas.drawBitmap(bmp, null, full, imageOverlayPaint)
                }

                // Dimmed in four bands rather than with a clip, which keeps this to plain
                // rectangles and avoids saveLayer on every frame of a drag.
                canvas.drawRect(full.left, full.top, full.right, keep.top, cropShade)
                canvas.drawRect(full.left, keep.bottom, full.right, full.bottom, cropShade)
                canvas.drawRect(full.left, keep.top, keep.left, keep.bottom, cropShade)
                canvas.drawRect(keep.right, keep.top, full.right, keep.bottom, cropShade)

                canvas.drawRect(keep, cropFrame)
                val hr = cropHandleRadius
                for (p in cropCornersOf(keep)) {
                    canvas.drawCircle(p[0], p[1], hr, handleFill)
                    canvas.drawCircle(p[0], p[1], hr, cropFrame)
                }
            }
        }

        // The pages we just drew are, by definition, the pages that need bitmaps. Deriving the
        // request from the draw itself means the two can never disagree - previously the viewport
        // was computed twice by separate code, and when those disagreed a page would render as
        // blank paper while nothing ever asked for its content.
        if (drawnPages.isNotEmpty() && drawnPages != lastVisible) {
            lastVisible = drawnPages
            val callback = onVisiblePagesChanged
            if (callback != null) post { callback(drawnPages) }
        }

        // Anything on screen without a bitmap gets asked for again, at a gentle interval so a
        // page that genuinely cannot render does not spin.
        val missing = drawnPages.filter { slots.getOrNull(it)?.bitmap == null }
        if (missing.isNotEmpty()) {
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastRetryAt > RENDER_RETRY_MS) {
                lastRetryAt = nowMs
                val callback = onPagesNeedRender
                if (callback != null) post { callback(missing) }
            }
            // keep frames coming while something is still outstanding
            postInvalidateDelayed(RENDER_RETRY_MS)
        }

        RenderStats.totalStrokes = strokes.size
        RenderStats.livePoints = live?.size ?: 0
        RenderStats.pageCount = slots.size
        RenderStats.pagesResident = slots.count { it.bitmap != null }
        RenderStats.recordFrame(
            (System.nanoTime() - frameStart) / 1_000_000f,
            drawnCount,
            fullRedraw = clip.width() >= width && clip.height() >= height
        )
        RenderStats.pagesVisible = drawnPages.size
    }

    /**
     * Is this stroke worth drawing for the current damaged region?
     *
     * Degenerate bounds (a single point, or anything non-finite) always draw: a cheap extra draw
     * costs a fraction of a millisecond, whereas wrongly skipping one loses the user's work off
     * the screen.
     */
    private fun visible(st: Stroke, localViewport: RectF): Boolean {
        val b = StrokeRasteriser.boundsOf(st)
        if (!b.left.isFinite() || !b.top.isFinite() || !b.right.isFinite() || !b.bottom.isFinite()) {
            return true
        }
        if (b.width() <= 0f && b.height() <= 0f) return true
        return RectF.intersects(b, localViewport) ||
            localViewport.contains(b.centerX(), b.centerY())
    }

    /**
     * Repaint just the region a stroke segment touched.
     *
     * Invalidating the whole view for every input sample forces every visible stroke through the
     * renderer at the pen's report rate, which is precisely when the device has least headroom.
     */
    private fun invalidateSegment(x0: Float, y0: Float, x1: Float, y1: Float) {
        val pad = (liveWidth * brush.maxFactor * currentScale()) / 2f + 24f
        invalidate(
            (min(x0, x1) - pad).toInt(),
            (min(y0, y1) - pad).toInt(),
            (max(x0, x1) + pad).toInt(),
            (max(y0, y1) + pad).toInt()
        )
    }

    private fun liveStroke(pts: MutableList<InkPoint>) = Stroke(
        id = LIVE_ID, kind = liveKind, color = color,
        baseWidth = liveWidth, points = pts, brush = brush, dash = dash,
        fill = fillStyle, fillColor = fillColor, opacity = opacity,
        rows = newTableRows, cols = newTableCols, pageIndex = livePage
    )

    /**
     * All object rendering goes through [StrokeRasteriser], the same code the exporter and the
     * thumbnail generator use. Keeping one renderer is what guarantees the page you drew on and
     * the file you hand in look identical.
     */
    private fun drawStroke(canvas: Canvas, s: Stroke) = StrokeRasteriser.draw(canvas, s)


    /** Selection frame and handles are drawn in view space so they stay a constant size. */
    private fun drawSelectionChrome(canvas: Canvas) {
        if (selection.isEmpty()) return
        val b = selectionBounds() ?: return
        val v = RectF(b); pageToView.mapRect(v)
        canvas.drawRect(v, selFrame)

        val cx = v.centerX()
        listOf(
            v.left to v.top, v.right to v.top,
            v.left to v.bottom, v.right to v.bottom
        ).forEach { (x, y) ->
            canvas.drawCircle(x, y, handleRadiusPx, handleFill)
            canvas.drawCircle(x, y, handleRadiusPx, handleEdge)
        }
        // rotate handle above the top edge
        canvas.drawLine(cx, v.top, cx, v.top - rotateOffsetPx, handleEdge)
        canvas.drawCircle(cx, v.top - rotateOffsetPx, handleRadiusPx, handleFill)
        canvas.drawCircle(cx, v.top - rotateOffsetPx, handleRadiusPx, handleEdge)
    }

    /** Selection bounds in DOCUMENT space, so the frame lands correctly on any page. */
    private fun selectionBounds(): RectF? {
        val sel = selectedStrokes()
        if (sel.isEmpty()) return null
        var out: RectF? = null
        for (st in sel) {
            val o = originOf(st.pageIndex)
            val r = RectF(st.bounds())
            r.offset(o[0], o[1])
            if (out == null) out = r else out.union(r)
        }
        return out
    }

    /** Selection bounds in the coordinates of [livePage], for transform maths. */
    private fun selectionBoundsLocal(): RectF? {
        val b = selectionBounds() ?: return null
        val o = originOf(livePage)
        return RectF(b.left - o[0], b.top - o[1], b.right - o[0], b.bottom - o[1])
    }

    // ---- input ---------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked

        if (action == MotionEvent.ACTION_DOWN) { stopFling(); clearHighlight() }
        trackVelocity(event)

        // Two fingers on the ruler adjust the ruler rather than the page. Without this
        // exception, the most natural way to grab a straightedge instead zooms the document.
        if (rulerTwoFinger) {
            when (action) {
                MotionEvent.ACTION_MOVE -> updateRulerTwoFinger(event)
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    endRulerTwoFinger()
                    gesturing = false
                    suppressDrawUntil = System.currentTimeMillis() + MULTITOUCH_GUARD_MS
                }
            }
            return true
        }

        if (event.pointerCount >= 2) {
            if ((action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_DOWN) &&
                tryBeginRulerTwoFinger(event)
            ) {
                abandonGesture()
                return true
            }
            abandonGesture()
            suppressDrawUntil = System.currentTimeMillis() + MULTITOUCH_GUARD_MS
            handleTransformGesture(event)
            return true
        }
        if (gesturing) {
            // any multi-touch sequence arms the guard, including while it is still running
            suppressDrawUntil = System.currentTimeMillis() + MULTITOUCH_GUARD_MS
            when (action) {
                // Only transform while two fingers remain. With one left the gesture is winding
                // down: swallow the events so the surviving finger cannot start drawing a stroke
                // halfway through a zoom.
                MotionEvent.ACTION_MOVE -> if (event.pointerCount >= 2) handleTransformGesture(event)
                MotionEvent.ACTION_POINTER_UP -> lastSpan = 0f
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    gesturing = false
                    if (action == MotionEvent.ACTION_UP) {
                        val (vx, vy) = releaseVelocity()
                        startFling(vx, vy)
                    }
                    // This branch is how a pinch ends, and it bypasses onUp entirely. Without
                    // clearing here, the eraser ring stays on screen with nothing touching the
                    // glass, because nothing else ever runs to take it down.
                    abandonGesture()
                }
            }
            return true
        }

        val idx = event.actionIndex
        val toolType = event.getToolType(idx)
        val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
        val isFinger = toolType == MotionEvent.TOOL_TYPE_FINGER

        // Palm rejection: once a stylus is on the glass, skin contacts do nothing. Panning is
        // exempt - resting a hand while scrolling is not the problem palm rejection solves, and
        // blocking it makes a finger-pan profile unusable whenever the pen is nearby.
        val touchWantsToPan = touchConfig.tool == Tool.PAN
        if ((drawingIsStylus || hasStylusPointer(event)) && isFinger && !touchWantsToPan) {
            return true
        }

        // Swap configs before anything reads them, so the very first sample of a stroke already
        // uses the right pen. Doing this after ACTION_DOWN would draw one frame with the wrong
        // tool every time you switched hands.
        //
        // A stylus switches the instant it lands, because it cannot be half of a two-finger
        // gesture. A finger does not: the first finger of a pinch arrives as an ordinary
        // ACTION_DOWN, and switching on it is what made scrolling or zooming silently change the
        // toolbar from Pen to Finger. The switch happens in commitPending() instead - the point
        // at which the touch is known to be a stroke rather than the start of a gesture.
        if (autoSwitchInput && action == MotionEvent.ACTION_DOWN && isStylus) {
            switchInputMode(InputMode.PEN)
        }

        // A mouse, when one is attached - a keyboard case, a desk setup - draws with its own two
        // pens rather than borrowing the stylus's, and its right button is a pen of its own
        // rather than a modifier on the left one. Same rule as the laptop, where it is the only
        // rule there is.
        if (autoSwitchInput && action == MotionEvent.ACTION_DOWN &&
            toolType == MotionEvent.TOOL_TYPE_MOUSE
        ) {
            val secondary = (event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
            switchInputMode(if (secondary) InputMode.MOUSE_RIGHT else InputMode.MOUSE)
        }

        // The eraser tip always erases - that is what the far end of a pencil is. A pen button
        // does whatever the user has assigned to it, which for a held action stands in for the
        // current tool and for a one-shot action is handled on the press itself.
        //
        // Only the pen's own events say anything about the pen's buttons. A finger landing while
        // a button is held reports no buttons at all, and reading that as a release then a fresh
        // press would fire a one-shot action twice.
        val heldButton = if (isStylus) buttonIndexOf(event.buttonState) else 0
        if (isStylus) noteStylusButton(heldButton)
        val buttonAction = actionForButton(heldButton)
        // A held button brings its whole profile with it, so the stroke it draws is that profile's
        // colour, brush and width rather than the current pen's at a different tool.
        heldButtonConfig =
            if (heldButton > 0 && buttonAction.isHeld) configForButton(heldButton) else null
        val forceErase = toolType == MotionEvent.TOOL_TYPE_ERASER ||
            buttonAction == StylusButtonAction.ERASE
        val t = when {
            forceErase -> Tool.ERASER
            buttonAction == StylusButtonAction.PROFILE -> configForButton(heldButton).tool
            buttonAction.heldTool != null -> buttonAction.heldTool!!
            else -> tool
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                // A finger arriving right after a pinch is almost always the tail of that
                // gesture. A stylus is deliberate, so it is never suppressed.
                if (!isStylus && System.currentTimeMillis() < suppressDrawUntil) return true
                gestureConfig = heldButtonConfig
                if (isStylus) {
                    onDown(event, idx, isStylus, t)
                } else {
                    // Hold the first finger until we know whether a second one is joining it.
                    discardPending()
                    pending = PendingTouch(
                        MotionEvent.obtain(event), idx, isStylus, forceErase,
                        System.currentTimeMillis()
                    )
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val p = pending
                if (p != null) {
                    p.moves.add(MotionEvent.obtain(event))
                    val movedFar = hypot(event.x - p.startX, event.y - p.startY) >= commitSlopPx
                    val heldLong = System.currentTimeMillis() - p.at >= COMMIT_HOLD_MS
                    if (movedFar || heldLong) commitPending()
                } else {
                    onMove(event, isStylus, t)
                }
            }
            MotionEvent.ACTION_UP -> {
                // A tap has to keep working: placing a stamp, selecting an object, deselecting.
                commitPending()
                onUp(cancelled = false, t = t)
                gestureConfig = null
            }
            MotionEvent.ACTION_CANCEL -> { abandonGesture(); gestureConfig = null }
        }
        armIndicatorWatchdog()
        return true
    }

    private fun onDown(e: MotionEvent, idx: Int, isStylus: Boolean, t: Tool) {
        // Resolve the page first: everything below works in that page's local coordinates.
        // Confining a gesture to one page keeps erasing and selecting predictable when several
        // pages are on screen at once.
        toDoc(e.x, e.y)
        livePage = pageAtDoc(tmpPts[0], tmpPts[1])
        if (layout.isContinuous && livePage != currentPage) {
            currentPage = livePage
            syncCurrentDims()
            onCurrentPageChanged?.invoke(livePage)
        }
        drawingPointerId = e.getPointerId(idx)
        drawingIsStylus = isStylus
        // Both widths are resolved once here; see [resolveDrawWidth].
        liveWidth = resolveDrawWidth()
        liveEraser = resolveEraserRadius()
        canvasGrowthThisGesture = 0
        lastX = e.x; lastY = e.y
        parent?.requestDisallowInterceptTouchEvent(true)

        rulerGrab = rulerHandleAt(e.x, e.y, isStylus)
        if (rulerGrab != 0) {
            toDoc(e.x, e.y)
            val o = originOf(rulerPage)
            rulerGrabOffset = floatArrayOf(tmpPts[0] - o[0], tmpPts[1] - o[1])
            return
        }

        val isDoubleTap = System.currentTimeMillis() - lastTapTime < 320 &&
            hypot(e.x - lastTapX, e.y - lastTapY) < 40f
        lastTapTime = System.currentTimeMillis(); lastTapX = e.x; lastTapY = e.y

        // Cropping owns every touch while it is on: the handles and the rectangle are the only
        // things a touch can mean, and letting a stray drag draw on the page underneath would
        // be a mark you then have to find and undo.
        if (cropTargetId != null) {
            toPage(e.x, e.y)
            val px = tmpPts[0]; val py = tmpPts[1]
            cropGrab = cropHandleAt(px, py)
            cropGrabAt[0] = px; cropGrabAt[1] = py
            invalidate()
            return
        }

        // An armed item takes precedence over the current tool: while something is waiting to be
        // placed, that is unambiguously what the next touch is for.
        if (armedPlacement != null) {
            toPage(e.x, e.y)
            placeStart = floatArrayOf(tmpPts[0], tmpPts[1])
            placing = RectF(tmpPts[0], tmpPts[1], tmpPts[0], tmpPts[1])
            invalidate()
            return
        }

        when (t) {
            Tool.ERASER -> {
                erasedThisGesture = ArrayList()
                addedThisGesture = ArrayList()
                eraserCursor = floatArrayOf(e.x, e.y)
                eraseAt(e.x, e.y)
            }
            Tool.TEXT -> {
                toPage(e.x, e.y)
                val hit = topmostAt(tmpPts[0], tmpPts[1])
                onTextRequested?.invoke(tmpPts[0], tmpPts[1], hit?.takeIf { it.kind == StrokeKind.TEXT })
            }
            Tool.PAN -> {
                // nothing to set up: the drag itself is the gesture
                stopFling()
            }
            Tool.REGION -> {
                liveKind = StrokeKind.RECT
                live = ArrayList()
                resetStrokeFilters()
                rulerEngaged = false
                appendLive(e.x, e.y, e.pressure, isStylus, Tool.RECT)
            }
            Tool.SELECT -> beginSelectGesture(e, isDoubleTap)
            Tool.TABLE -> {
                liveKind = StrokeKind.TABLE
                live = ArrayList()
                resetStrokeFilters()
                rulerEngaged = false
                appendLive(e.x, e.y, e.pressure, isStylus, t)
            }
            else -> {
                liveKind = kindFor(t)
                live = ArrayList()
                resetStrokeFilters()
                toPage(e.x, e.y)
                rulerEngaged = t == Tool.DRAW && projectOntoRuler(tmpPts[0], tmpPts[1]) != null
                appendLive(e.x, e.y, e.pressure, isStylus, t)
            }
        }
        invalidate()
    }

    private fun onMove(e: MotionEvent, isStylus: Boolean, t: Tool) {
        if (e.getPointerId(0) != drawingPointerId) return

        if (cropTargetId != null) {
            if (cropGrab != 0) {
                toPage(e.x, e.y)
                dragCrop(tmpPts[0], tmpPts[1])
                invalidate()
            }
            return
        }

        placeStart?.let { start ->
            toPage(e.x, e.y)
            placing = RectF(
                min(start[0], tmpPts[0]), min(start[1], tmpPts[1]),
                max(start[0], tmpPts[0]), max(start[1], tmpPts[1])
            )
            invalidate()
            return
        }

        if (rulerGrab != 0) {
            toDoc(e.x, e.y)
            val o = originOf(rulerPage)
            val px = tmpPts[0] - o[0]
            val py = tmpPts[1] - o[1]
            when (rulerGrab) {
                1 -> rulerA = floatArrayOf(px, py)
                2 -> rulerB = floatArrayOf(px, py)
                3 -> {
                    val dx = px - rulerGrabOffset[0]
                    val dy = py - rulerGrabOffset[1]
                    rulerA?.let { it[0] += dx; it[1] += dy }
                    rulerB?.let { it[0] += dx; it[1] += dy }
                    rulerGrabOffset = floatArrayOf(px, py)
                }
            }
            invalidate()
            return
        }

        when {
            t == Tool.PAN -> {
                pageToView.postTranslate(e.x - lastX, e.y - lastY)
                lastX = e.x; lastY = e.y
                loosePanning = false
                scheduleDetail()
                clampTranslation(); syncInverse(); reportVisiblePages()
                onTransformChanged?.invoke()
                invalidate()
                return
            }
            t == Tool.ERASER -> {
                // Walk the interpolated path so a fast swipe cannot skip strokes, but gather the
                // points first and erase along all of them in a single pass over the page.
                val steps = max(1, (hypot(e.x - lastX, e.y - lastY) / 6f).toInt())
                eraseScratch.clear()
                for (i in 1..steps) {
                    val f = i.toFloat() / steps
                    toPage(lastX + (e.x - lastX) * f, lastY + (e.y - lastY) * f)
                    eraseScratch.add(tmpPts[0]); eraseScratch.add(tmpPts[1])
                }
                eraseAlong(eraseScratch)
                eraserCursor = floatArrayOf(e.x, e.y)
                lastX = e.x; lastY = e.y
            }
            t == Tool.SELECT -> updateSelectGesture(e)
            t == Tool.REGION -> {
                appendLive(e.x, e.y, e.pressure, isStylus, Tool.RECT)
            }
            t == Tool.TEXT -> Unit
            else -> {
                // Stylus digitisers report far faster than the display refreshes; consuming the
                // historical samples is most of the smoothness win on handwriting.
                for (h in 0 until e.historySize) {
                    appendLive(e.getHistoricalX(h), e.getHistoricalY(h), e.getHistoricalPressure(h), isStylus, t)
                }
                appendLive(e.x, e.y, e.pressure, isStylus, t)
                invalidateSegment(lastX, lastY, e.x, e.y)
                lastX = e.x; lastY = e.y
                return
            }
        }
        invalidate()
    }

    /**
     * Put the armed item down.
     *
     * A drag defines the space it fills; a tap gets a sensible default centred where you tapped.
     * Stamps keep their natural proportions inside whatever was drawn, because a number line
     * squashed to a square is not a number line.
     */
    private fun finishPlacement() {
        val item = armedPlacement
        val start = placeStart
        placeStart = null
        val box = placing
        placing = null
        if (item == null || start == null || box == null) { invalidate(); return }

        val dragged = box.width() > placeSlopPt && box.height() > placeSlopPt

        val built: List<Stroke> = when (item) {
            is Placement.StampItem -> {
                val aspect = Stamps.aspectFor(item.kind, item.options)
                val bounds = if (dragged) fitAspect(box, aspect, start[0], start[1]) else {
                    // untouched default: wide enough to be usable, centred on the tap
                    var w = pageWidthPt * 0.42f
                    var h = w / aspect
                    val maxH = pageHeightPt * 0.30f
                    if (h > maxH) { h = maxH; w = h * aspect }
                    RectF(start[0] - w / 2f, start[1] - h / 2f, start[0] + w / 2f, start[1] + h / 2f)
                }
                buildStamp(
                    item.kind, bounds, livePage, color, liveWidth, item.options
                ) { ids.next() }
            }
            is Placement.ImageItem -> {
                val bounds = if (dragged) fitAspect(box, item.aspect, start[0], start[1]) else {
                    // a tap gets something big enough to see, in the picture's own proportions
                    var w = pageWidthPt * 0.45f
                    var h = w / item.aspect
                    val maxH = pageHeightPt * 0.45f
                    if (h > maxH) { h = maxH; w = h * item.aspect }
                    RectF(start[0] - w / 2f, start[1] - h / 2f, start[0] + w / 2f, start[1] + h / 2f)
                }
                listOf(
                    Stroke(
                        id = ids.next(), kind = StrokeKind.IMAGE, color = Color.BLACK,
                        baseWidth = 1f,
                        points = listOf(
                            InkPoint(bounds.left, bounds.top, 1f),
                            InkPoint(bounds.right, bounds.bottom, 1f)
                        ),
                        imageId = item.imageId, pageIndex = livePage, opacity = 1f,
                        updatedUtc = now()
                    )
                )
            }
            is Placement.TextItem -> {
                // Height drives the type size, which is the intuitive mapping: drag a tall box and
                // you get a big symbol. A plain tap gets the current pen's size, scaled up enough
                // to read as a symbol rather than a stroke.
                val size = if (dragged) box.height().coerceIn(6f, 400f)
                else (liveWidth * 8f).coerceIn(14f, 72f)
                val x = if (dragged) box.left else start[0]
                val y = if (dragged) box.top else start[1] - size / 2f
                listOf(
                    Stroke(
                        id = ids.next(), kind = StrokeKind.TEXT, color = color, baseWidth = 1f,
                        points = listOf(InkPoint(x, y, 1f)),
                        text = item.text, textSize = size,
                        pageIndex = livePage, opacity = opacity, updatedUtc = now()
                    )
                )
            }
        }

        if (built.isEmpty()) { invalidate(); return }
        // A picture goes down once. Stamps and symbols stay armed, because a page of them is a
        // series of taps; there is only ever one of a given photograph.
        if (item is Placement.ImageItem) {
            armedPlacement = null
            onPlacementChanged?.invoke(null)
        }
        strokes.addAll(built)
        growCanvasForAll(built)
        pushOp(Op(built, emptyList()))
        selection.clear(); built.forEach { selection.add(it.id) }
        onSelectionChanged?.invoke(selection.size)
        changed()
    }

    /** Largest rectangle of the given aspect that fits inside [box], centred in it. */
    /**
     * Shrinks [box] to [aspect], keeping the corner the drag started from exactly where it was
     * put and moving only the opposite one.
     *
     * The anchor matters more than it looks. Fitting the aspect and then re-centring on the box
     * makes the first point you placed drift as you drag the second, because the correction is
     * shared between both corners - so the stamp never lands where you started it, and placement
     * feels wobbly. Pinning [anchorX], [anchorY] makes the first touch a commitment: it is the
     * corner, and the drag only ever decides the size.
     */
    private fun fitAspect(box: RectF, aspect: Float, anchorX: Float, anchorY: Float): RectF {
        if (aspect <= 0f) return RectF(box)
        var w = box.width()
        var h = w / aspect
        if (h > box.height()) { h = box.height(); w = h * aspect }
        // The box is the normalised span of the two points, so the anchor is whichever corner it
        // started from - left or right, top or bottom.
        val left = if (anchorX <= box.centerX()) anchorX else anchorX - w
        val top = if (anchorY <= box.centerY()) anchorY else anchorY - h
        return RectF(left, top, left + w, top + h)
    }

    private fun onUp(cancelled: Boolean, t: Tool) {
        if (cropTargetId != null) {
            // The crop is not committed here. It is committed from the toolbar, so the rectangle
            // can be adjusted over several drags before anything is decided.
            cropGrab = 0
            drawingPointerId = -1
            parent?.requestDisallowInterceptTouchEvent(false)
            invalidate()
            return
        }

        if (placeStart != null) {
            if (cancelled) { placeStart = null; placing = null; invalidate() }
            else finishPlacement()
            drawingPointerId = -1
            drawingIsStylus = false
            // Release the parent as every other exit from this method does. Leaving it held means
            // the next gesture that should have scrolled the surrounding UI does nothing instead.
            parent?.requestDisallowInterceptTouchEvent(false)
            invalidate()
            return
        }

        if (rulerGrab != 0) {
            rulerGrab = 0
            drawingPointerId = -1
            parent?.requestDisallowInterceptTouchEvent(false)
            return
        }
        if (t == Tool.PAN) {
            if (!cancelled) {
                val (vx, vy) = releaseVelocity()
                startFling(vx, vy)
            }
            drawingPointerId = -1
            drawingIsStylus = false
            parent?.requestDisallowInterceptTouchEvent(false)
            return
        }
        when (t) {
            Tool.ERASER -> {
                if (erasedThisGesture.isNotEmpty()) {
                    pushEraseOp(); changed()
                }
                eraserCursor = null
            }
            Tool.REGION -> {
                val pts = live
                live = null
                if (!cancelled && pts != null && pts.size >= 2) {
                    val r = RectF(
                        min(pts[0].x, pts[1].x), min(pts[0].y, pts[1].y),
                        max(pts[0].x, pts[1].x), max(pts[0].y, pts[1].y)
                    )
                    // ignore an accidental tap, which would capture a sliver of nothing
                    if (r.width() > 8f && r.height() > 8f) onRegionCaptured?.invoke(livePage, r)
                }
                invalidate()
            }
            Tool.SELECT -> endSelectGesture(cancelled)
            Tool.TEXT -> Unit
            else -> commitLive(cancelled)
        }
        drawingPointerId = -1
        drawingIsStylus = false
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }

    private fun kindFor(t: Tool) = when (t) {
        Tool.LINE -> StrokeKind.LINE
        Tool.ARROW -> StrokeKind.ARROW
        Tool.RECT -> StrokeKind.RECT
        Tool.ELLIPSE -> StrokeKind.ELLIPSE
        Tool.TABLE -> StrokeKind.TABLE
        else -> StrokeKind.FREEHAND
    }

    /**
     * Clear the eraser ring and any half-built stroke.
     *
     * Hover events stop arriving when the pen is lifted away, and a cancelled gesture never
     * reaches ACTION_UP, so without this the indicator can sit on screen indefinitely with
     * nothing near the glass.
     */
    /**
     * Last-resort sweep for indicators left behind.
     *
     * Hover-exit is not reliably delivered by every OEM pen implementation, and a gesture can end
     * in ways the touch stream does not describe. Rather than enumerate those cases, this simply
     * notices that nothing has been on the glass for a while and takes the indicator down.
     */
    private val indicatorWatchdog = Runnable {
        if (drawingPointerId == -1 && !gesturing) clearTransientIndicators()
    }

    private fun armIndicatorWatchdog() {
        removeCallbacks(indicatorWatchdog)
        if (eraserCursor != null || live != null) {
            postDelayed(indicatorWatchdog, INDICATOR_TIMEOUT_MS)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopFling()
        velocityTracker?.recycle()
        velocityTracker = null
        endRulerTwoFinger()
        removeCallbacks(detailRequest)
        clearDetail()
        removeCallbacks(indicatorWatchdog)
        eraserCursor = null
        live = null
    }

    private fun clearTransientIndicators() {
        if (eraserCursor != null || live != null) {
            eraserCursor = null
            live = null
            invalidate()
        }
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) clearTransientIndicators()
        armIndicatorWatchdog()
        return super.onHoverEvent(event)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) clearTransientIndicators()
    }

    /**
     * Abandon whatever the pointer was doing and clear every transient indicator.
     *
     * Called when a gesture is superseded (a second finger arrives) or cancelled, neither of
     * which reaches [onUp]. Any strokes already erased in this gesture are still committed to the
     * undo stack: they are gone from the page either way, and silently dropping the undo entry
     * would make them unrecoverable.
     */
    /**
     * Act on a held first touch, replaying everything that happened while it was held.
     *
     * Order matters: the down is delivered first so the stroke starts at the right point and on
     * the right page, then each buffered move in the order it arrived.
     */
    private fun commitPending() {
        val p = pending ?: return
        pending = null
        // This is the moment the touch is known to be a stroke and not the first finger of a
        // pinch, so it is the moment the profile may switch. Resolving the tool afterwards
        // matters: it has to come from the profile the stroke will actually be drawn with.
        if (autoSwitchInput && !p.isStylus) switchInputMode(InputMode.TOUCH)
        val t = if (p.forceErase) Tool.ERASER else tool
        onDown(p.down, p.pointerIndex, p.isStylus, t)
        for (m in p.moves) onMove(m, p.isStylus, t)
        p.recycle()
    }

    private fun switchInputMode(wanted: InputMode) {
        if (wanted == activeMode) return
        activeMode = wanted
        onInputModeChanged?.invoke(wanted)
    }

    /** Throw away a held touch. Nothing was drawn, so there is nothing to undo. */
    private fun discardPending() {
        pending?.recycle()
        pending = null
    }

    private fun abandonGesture() {
        // A held touch never happened, so a second finger arriving costs nothing at all: no mark
        // on the page, and no cleared selection to get back.
        discardPending()
        gestureConfig = null

        // A part-drawn placement box is discarded, but the item stays armed: a pinch in the
        // middle of positioning something is a change of view, not a change of mind.
        placeStart = null
        placing = null

        // An interrupted drag is a different matter. The strokes have already been moved, and
        // clearing the gesture without putting them back would leave the selection somewhere the
        // user did not choose, with no undo entry to recover from.
        if (grab != Grab.NONE && selectionAtGrab.isNotEmpty()) {
            selectionAtGrab.forEach { orig ->
                val i = strokes.indexOfFirst { it.id == orig.id }
                if (i >= 0) strokes[i] = orig
            }
            pageIndexDirty = true
        }

        if (erasedThisGesture.isNotEmpty()) {
            pushEraseOp()
            onContentChanged?.invoke()
        }
        live = null
        eraserCursor = null
        marquee = null
        grab = Grab.NONE
        selectionAtGrab = emptyList()
        velocityTracker?.recycle()
        velocityTracker = null
        drawingPointerId = -1
        drawingIsStylus = false
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }

    private fun hasStylusPointer(e: MotionEvent): Boolean {
        for (i in 0 until e.pointerCount) {
            val ty = e.getToolType(i)
            if (ty == MotionEvent.TOOL_TYPE_STYLUS || ty == MotionEvent.TOOL_TYPE_ERASER) return true
        }
        return false
    }

    // ---- freehand and shape building ----------------------------------------

    /**
     * Clear the input filters so a new stroke starts clean.
     *
     * Leaving these set carries the previous stroke's position and width into the next one. It is
     * most obvious when switching between pen and finger, whose widths differ a lot: the new
     * stroke opens at the old width and tapers down, producing a blob with a tail.
     */
    /**
     * Whether the stroke in progress is being ruled.
     *
     * Latched at the first sample rather than tested per point. Without the latch a stroke that
     * merely passes near the ruler snaps straight halfway along, which is not something a
     * physical straightedge can do to you.
     */
    private var rulerEngaged = false

    private fun resetStrokeFilters() {
        lastVelWidth = 0f
        smoothedX = Float.NaN
        smoothedY = Float.NaN
        lastWidth = Float.NaN
    }

    private fun appendLive(vx: Float, vy: Float, pressure: Float, isStylus: Boolean, t: Tool) {
        val l = live ?: return
        toPage(vx, vy)
        var px = tmpPts[0]; var py = tmpPts[1]

        if (t != Tool.DRAW) {
            // shapes and tables keep only origin plus current corner
            if (l.isEmpty()) { l.add(InkPoint(px, py, liveWidth)); return }
            if (snapShapes) {
                val snapped = Stroke.snapShape(liveKind, l[0].x, l[0].y, px, py)
                px = snapped.first; py = snapped.second
            }
            if (l.size == 1) l.add(InkPoint(px, py, liveWidth))
            else l[1] = InkPoint(px, py, liveWidth)
            return
        }

        // A ruler guides the pen the way a physical one does: near enough counts, and the
        // line comes out straight regardless of the wobble in the hand holding it. Only for a
        // stroke that started against the edge - see [rulerEngaged].
        if (rulerEngaged) {
            projectOntoRuler(px, py, unbounded = true)?.let { snapped ->
                px = snapped[0]; py = snapped[1]
            }
        }

        // ---- position filtering ----
        // Exponential filter over the incoming samples. Raw digitiser output is noisy enough to
        // look shaky; over-filtering makes ink lag the nib and rounds off corners, which is
        // precisely why the amount is a user setting rather than a constant.
        //
        // The response is a power curve rather than a straight line, so that the ends of the
        // slider are worth having. At 0 the ink is the digitiser's own samples, untouched; at 1
        // it trails the nib heavily and comes out glassy. The middle stays close to where it was,
        // because that is where the sensible settings live.
        val alpha = (1f - smoothing).coerceIn(0f, 1f).pow(0.7f).coerceAtLeast(0.02f)
        if (smoothedX.isNaN()) { smoothedX = px; smoothedY = py }
        smoothedX += (px - smoothedX) * alpha
        smoothedY += (py - smoothedY) * alpha
        px = smoothedX; py = smoothedY

        // ---- width ----
        // The brush's range, stretched by the user's expressiveness setting.
        val dyn = config.dynamics.coerceIn(0f, 3f)
        val brushMin = brush.minFactorAt(dyn)
        val brushMax = brush.maxFactorAt(dyn)
        val raw = when {
            !brush.isVariableWidth -> liveWidth
            isStylus && pressureEnabled && pressure > 0f -> {
                // The user's own curve replaces the brush's default response, but the brush still
                // decides how far the width can travel - a highlighter stays flat whatever the
                // curve says.
                val p = pressure.coerceIn(0f, 1f)
                val shaped = Math.pow(p.toDouble(), config.pressureGamma.toDouble()).toFloat()
                val lo = minOf(maxOf(brushMin, config.pressureMin), brushMax)
                val byPressure = liveWidth * (lo + (brushMax - lo) * shaped)
                // Speed thins the line on top of pressure, for the brushes that ask for it.
                // Pressure alone draws evenly and deliberately; handwriting is fast down the
                // long strokes and slow through the turns, and this is what puts that back.
                byPressure * speedFactor(px, py, l, brush.speedTaper)
            }
            else -> velocityWidth(liveWidth, px, py, l, brushMin, brushMax)
        }
        // Widths get their own gentler filter: pressure data is steppy on most digitisers, and
        // unfiltered it shows up as visible banding along the stroke.
        val w = if (lastWidth.isNaN()) raw else lastWidth + (raw - lastWidth) * 0.35f
        lastWidth = w

        // A canvas makes room while the stroke is still being drawn, so writing off the edge of
        // the page simply works rather than ending in a jump.
        //
        // Strictly bounded, because this is a feedback loop by nature: growing puts more page
        // under the pen, which is exactly where the pen already is. One stroke may add at most
        // [MAX_GROWTH_PER_GESTURE] steps; past that the room appears when the stroke is finished
        // and its real extent is known, which is bounded by how far the hand actually moved.
        // Without the cap, a single flick across a zoomed-out canvas made a page hundreds of feet
        // long in half a second.
        canvas?.let { c ->
            if (canvasGrowthThisGesture < MAX_GROWTH_PER_GESTURE &&
                (px < c.left + CANVAS_EDGE_PT || py < c.top + CANVAS_EDGE_PT ||
                    px > c.right - CANVAS_EDGE_PT || py > c.bottom - CANVAS_EDGE_PT)
            ) {
                val before = canvas
                growCanvasFor(RectF(px, py, px, py))
                if (canvas != before) canvasGrowthThisGesture++
            }
        }

        // Sample spacing scales with zoom, so detail survives when zoomed in and files stay
        // small when zoomed out.
        val minStep = (0.7f / currentScale().coerceAtLeast(0.05f)).coerceIn(0.12f, 2.5f)
        val last = l.lastOrNull()
        if (last != null && hypot(px - last.x, py - last.y) < minStep) return
        l.add(InkPoint(px, py, w))
    }

    /**
     * Fallback for finger and non-pressure styluses: fast movement thins the line, slow movement
     * thickens it. Damped hard, or handwriting looks like a seismograph.
     */
    private fun velocityWidth(
        base: Float, px: Float, py: Float, l: List<InkPoint>,
        brushMin: Float, brushMax: Float
    ): Float {
        val resp = brush.velocityResponse
        if (resp < 0.02f) return base
        val last = l.lastOrNull() ?: return base
        val speed = hypot(px - last.x, py - last.y) * currentScale()
        val fast = (speed / 26f).coerceIn(0f, 1f)
        val factor = brushMax - (brushMax - brushMin) * fast * resp
        return base * factor.coerceAtLeast(brushMin)
    }

    /**
     * Width multiplier from how fast the nib is moving, 0 to 1 of [amount].
     *
     * Damped hard and clamped: an undamped speed sensor turns handwriting into a seismograph,
     * which is the trap the velocity fallback already had to be pulled out of once.
     */
    private fun speedFactor(px: Float, py: Float, l: List<InkPoint>, amount: Float): Float {
        if (amount < 0.02f) return 1f
        val last = l.lastOrNull() ?: return 1f
        val speed = hypot(px - last.x, py - last.y) * currentScale()
        val fast = (speed / 30f).coerceIn(0f, 1f)
        return 1f - amount * 0.55f * fast
    }

    private fun commitLive(cancelled: Boolean) {
        val l = live
        live = null
        if (cancelled || l.isNullOrEmpty()) return
        if (liveKind != StrokeKind.FREEHAND && l.size < 2) return
        if (liveKind != StrokeKind.FREEHAND) {
            val r = RectF(min(l[0].x, l[1].x), min(l[0].y, l[1].y), max(l[0].x, l[1].x), max(l[0].y, l[1].y))
            if (r.width() < 2f && r.height() < 2f) return    // stray tap, not a shape
        }
        val isTable = liveKind == StrokeKind.TABLE
        val s = Stroke(
            id = ids.next(), kind = liveKind, color = color,
            baseWidth = if (isTable) max(0.8f, liveWidth * 0.6f) else liveWidth,
            pageIndex = livePage,
            points = l, brush = brush, dash = dash,
            fill = fillStyle, fillColor = fillColor, opacity = opacity,
            textSize = textSize,
            rows = if (isTable) newTableRows else 0,
            cols = if (isTable) newTableCols else 0,
            cells = if (isTable) List(newTableRows * newTableCols) { "" } else emptyList(),
            updatedUtc = now()
        )
        var finished = if (recogniseShapes) com.inkslate.core.ShapeRecogniser.recognise(s) ?: s else s

        // A highlighter dragged across a line becomes clean bars over the words it crossed.
        // Falls back to the freehand stroke when there is no text layer, which is what a scanned
        // page will be - snapping to nothing would silently erase the mark.
        if (snapHighlighterToText && finished.isHighlighter && finished.points.size > 2) {
            val bars = textSnapper?.invoke(
                livePage, finished.points.map { it.x to it.y }
            ).orEmpty()
            if (bars.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val replacements = bars.map { r ->
                    Stroke(
                        id = ids.next(), kind = StrokeKind.FREEHAND, color = finished.color,
                        baseWidth = r.height().coerceAtLeast(4f),
                        points = listOf(
                            InkPoint(r.left, r.centerY(), r.height()),
                            InkPoint(r.right, r.centerY(), r.height())
                        ),
                        brush = BrushType.HIGHLIGHTER, opacity = finished.opacity,
                        pageIndex = livePage, updatedUtc = now
                    )
                }
                strokes.addAll(replacements)
                pushOp(Op(replacements, emptyList()))
                changed()
                return
            }
        }
        strokes.add(finished); pushOp(Op(listOf(finished), emptyList()))
        growCanvasForAll(listOf(finished)); changed()
    }

    private fun eraseAt(vx: Float, vy: Float) {
        eraseScratch.clear()
        toPage(vx, vy)
        eraseScratch.add(tmpPts[0]); eraseScratch.add(tmpPts[1])
        eraseAlong(eraseScratch)
    }

    /**
     * Erase everything touched by a run of sample points, in one pass.
     *
     * A fast swipe covers a lot of ground between two touch events, so the gap is walked in small
     * steps to make sure nothing is skipped. Doing that as one full scan of the document *per
     * step* meant a quick flick across a busy page cost tens of passes over every stroke in the
     * document, most of them on other pages entirely. This looks only at the page being erased,
     * and looks at it once however many steps there are.
     *
     * [pagePoints] is a flat list of x, y pairs in page coordinates.
     */
    private fun eraseAlong(pagePoints: List<Float>) {
        if (pagePoints.size < 2) return
        val candidates = strokesOnPage(livePage)
        if (candidates.isEmpty()) return

        var hits: HashSet<String>? = null
        var replacements: ArrayList<Stroke>? = null
        val partial = config.eraserMode == EraserMode.PARTIAL

        // The area the eraser covered this frame. Testing a stroke's bounds against it first
        // turns "is this stroke anywhere near the eraser" from one test per interpolated step
        // into one test per stroke, which matters because a fast swipe interpolates hundreds of
        // steps and a marked-up page has hundreds of strokes.
        val swath = swathBounds(pagePoints, liveEraser)
        val swathPath = if (partial) compactPath(pagePoints) else null

        for (s in candidates) {
            if (!RectF.intersects(StrokeRasteriser.boundsOf(s), swath)) continue

            var touched = false
            var i = 0
            while (i + 1 < pagePoints.size) {
                if (s.hitTest(pagePoints[i], pagePoints[i + 1], liveEraser)) { touched = true; break }
                i += 2
            }
            if (!touched) continue

            val set = hits ?: HashSet<String>().also { hits = it }
            set.add(s.id)
            erasedThisGesture.add(s)

            // Only freehand ink can be rubbed out in part. Half a table or half a text box is not
            // a thing, so those come away whole however the eraser is set.
            if (partial && s.kind == StrokeKind.FREEHAND && s.points.size > 1) {
                val pieces = splitAroundEraser(s, swathPath!!, swath)
                if (pieces.isNotEmpty()) {
                    val into = replacements ?: ArrayList<Stroke>().also { replacements = it }
                    into.addAll(pieces)
                }
            }
        }

        val removeIds = hits ?: return
        strokes.removeAll { it.id in removeIds }
        replacements?.let { fresh ->
            strokes.addAll(fresh)
            // The fragments are new objects, so they are part of what this gesture did and have to
            // be undone with it - otherwise undo restores the original *and* leaves the pieces.
            addedThisGesture.addAll(fresh)
        }
        pageIndexDirty = true
        selection.clear()
        onContentChanged?.invoke()
    }

    /**
     * The parts of [s] the eraser did not touch, as separate strokes.
     *
     * ## Keeping this cheap
     *
     * The obvious version - every sample of the stroke against every interpolated step of the
     * eraser - is quadratic in exactly the situation that matters, because a fast swipe is
     * interpolated into hundreds of steps and the strokes it crosses have hundreds of samples.
     * Two things stop that. The eraser's path is reduced to a handful of *segments* first, which
     * is both faster and more accurate than testing against its samples (a segment has no gaps
     * between it and the next one). And a sample outside the swath's bounding box is rejected
     * with four comparisons, which is nearly all of them for a stroke the eraser merely clips.
     *
     * ## What survives
     *
     * Fragments below [MIN_FRAGMENT_POINTS] samples are dropped rather than kept: a two-sample
     * crumb is invisible, and letting a scrub produce hundreds of them is how this turns into a
     * performance problem instead of a feature. For the same reason a stroke that would shatter
     * into more than [MAX_FRAGMENTS] pieces is simply taken away whole - at that point the user
     * is scrubbing it out, not trimming it.
     */
    private fun splitAroundEraser(s: Stroke, path: FloatArray, swath: RectF): List<Stroke> {
        val reach = liveEraser + s.baseWidth / 2f
        val reach2 = reach * reach
        val pts = s.points

        val kept = ArrayList<MutableList<InkPoint>>(4)
        var run: MutableList<InkPoint>? = null

        for (p in pts) {
            var inside = false
            if (p.x >= swath.left && p.x <= swath.right &&
                p.y >= swath.top && p.y <= swath.bottom
            ) {
                var i = 0
                while (i + 3 < path.size) {
                    if (distanceSqToSegment(
                            p.x, p.y, path[i], path[i + 1], path[i + 2], path[i + 3]
                        ) <= reach2
                    ) { inside = true; break }
                    i += 2
                }
                // a single tap leaves one point and no segment at all
                if (!inside && path.size == 2) {
                    val dx = p.x - path[0]
                    val dy = p.y - path[1]
                    inside = dx * dx + dy * dy <= reach2
                }
            }
            if (inside) {
                run = null
            } else {
                val r = run ?: ArrayList<InkPoint>(16).also { kept.add(it); run = it }
                r.add(p)
            }
        }

        val survivors = kept.filter { it.size >= MIN_FRAGMENT_POINTS }
        if (survivors.isEmpty()) return emptyList()
        if (survivors.size > MAX_FRAGMENTS) return emptyList()
        // Nothing was actually removed - the eraser clipped the stroke's fat outline without
        // reaching a single sample. Rebuilding it identically would churn ids for no reason.
        if (survivors.size == 1 && survivors[0].size == pts.size) return listOf(s)

        val now = now()
        return survivors.map { run ->
            s.copy(id = ids.next(), points = run, updatedUtc = now)
        }
    }

    /** Everything the eraser could possibly have touched this frame, in page coordinates. */
    private fun swathBounds(pagePoints: List<Float>, radius: Float): RectF {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var i = 0
        while (i + 1 < pagePoints.size) {
            val x = pagePoints[i]
            val y = pagePoints[i + 1]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            i += 2
        }
        // widest any stroke's own half-width can push the reach out past the eraser itself
        val pad = radius + MAX_ERASE_REACH_PAD
        return RectF(minX - pad, minY - pad, maxX + pad, maxY + pad)
    }

    /**
     * The eraser's path, thinned to a few segments.
     *
     * The samples handed in are a straight run interpolated every few pixels, so most of them say
     * nothing a segment between their neighbours does not. Keeping a bounded number means the
     * per-point cost of a split does not grow with how fast the hand moved.
     */
    private fun compactPath(pagePoints: List<Float>): FloatArray {
        val count = pagePoints.size / 2
        if (count <= 2) return pagePoints.toFloatArray()
        val stride = maxOf(1, (count - 1) / MAX_ERASE_SEGMENTS)
        val out = ArrayList<Float>(MAX_ERASE_SEGMENTS * 2 + 2)
        var i = 0
        while (i < count) {
            out.add(pagePoints[i * 2]); out.add(pagePoints[i * 2 + 1])
            i += stride
        }
        // the end of the swipe is the one sample that must never be dropped
        val lastX = pagePoints[(count - 1) * 2]
        val lastY = pagePoints[(count - 1) * 2 + 1]
        if (out[out.size - 2] != lastX || out[out.size - 1] != lastY) {
            out.add(lastX); out.add(lastY)
        }
        return out.toFloatArray()
    }

    private fun distanceSqToSegment(
        px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float
    ): Float {
        val vx = bx - ax
        val vy = by - ay
        val len2 = vx * vx + vy * vy
        val t = if (len2 < 1e-6f) 0f else
            (((px - ax) * vx + (py - ay) * vy) / len2).coerceIn(0f, 1f)
        val dx = px - (ax + vx * t)
        val dy = py - (ay + vy * t)
        return dx * dx + dy * dy
    }

    /** The strokes on one page, via the index rather than by filtering the whole document. */
    private fun strokesOnPage(page: Int): List<Stroke> {
        rebuildPageIndex()
        return strokesByPage[page].orEmpty()
    }

    /** Reused between erase samples so a drag does not allocate a list per frame. */
    private val eraseScratch = ArrayList<Float>(64)

    private fun topmostAt(px: Float, py: Float): Stroke? =
        strokes.lastOrNull { it.pageIndex == livePage && it.hitTest(px, py, 4f) }

    // ---- selection gestures --------------------------------------------------

    private fun beginSelectGesture(e: MotionEvent, doubleTap: Boolean) {
        toPage(e.x, e.y)
        grabStartPage = floatArrayOf(tmpPts[0], tmpPts[1])
        selBoundsAtGrab = selectionBoundsLocal() ?: RectF()
        selectionAtGrab = selectedStrokes()
        rememberGrabIndices()

        // a handle grab wins over everything else
        val handle = handleAt(e.x, e.y)
        if (handle != Grab.NONE) { grab = handle; return }

        // Anywhere inside the selection frame moves the whole selection, not just the pixels of
        // a drawn object. Requiring a hit on ink itself makes moving sparse handwriting fiddly.
        if (selection.isNotEmpty() &&
            selBoundsAtGrab.contains(grabStartPage[0], grabStartPage[1])
        ) {
            grab = Grab.MOVE
            return
        }

        val hit = topmostAt(grabStartPage[0], grabStartPage[1])
        if (hit == null) {
            clearSelection()
            grab = Grab.MARQUEE
            marquee = RectF(grabStartPage[0], grabStartPage[1], grabStartPage[0], grabStartPage[1])
            return
        }

        if (doubleTap) {
            when (hit.kind) {
                StrokeKind.TEXT -> {
                    onTextRequested?.invoke(hit.points[0].x, hit.points[0].y, hit); grab = Grab.NONE; return
                }
                StrokeKind.TABLE -> {
                    val cell = cellAt(hit, grabStartPage[0], grabStartPage[1])
                    if (cell != null) {
                        onCellRequested?.invoke(hit, cell.first, cell.second); grab = Grab.NONE; return
                    }
                }
                else -> Unit
            }
        }

        if (hit.id !in selection) {
            selection.clear(); selection.add(hit.id)
            onSelectionChanged?.invoke(1)
            selectionAtGrab = selectedStrokes()
            selBoundsAtGrab = selectionBoundsLocal() ?: RectF()
            rememberGrabIndices()
        }
        grab = Grab.MOVE
    }

    /** Where each grabbed object sits in [strokes], so a drag does not have to go looking. */
    private fun rememberGrabIndices() {
        val where = HashMap<String, Int>(selectionAtGrab.size * 2)
        strokes.forEachIndexed { i, st -> if (st.id in selection) where[st.id] = i }
        grabIndices = selectionAtGrab.map { where[it.id] ?: -1 }
    }

    private fun updateSelectGesture(e: MotionEvent) {
        toPage(e.x, e.y)
        val px = tmpPts[0]; val py = tmpPts[1]
        when (grab) {
            Grab.MARQUEE -> marquee = RectF(
                min(grabStartPage[0], px), min(grabStartPage[1], py),
                max(grabStartPage[0], px), max(grabStartPage[1], py)
            )
            Grab.MOVE -> applyLiveTransform(
                Matrix().apply { setTranslate(px - grabStartPage[0], py - grabStartPage[1]) }
            )
            Grab.TL, Grab.TR, Grab.BL, Grab.BR -> {
                val b = selBoundsAtGrab
                if (b.width() < 0.5f || b.height() < 0.5f) return
                val anchorX = if (grab == Grab.TL || grab == Grab.BL) b.right else b.left
                val anchorY = if (grab == Grab.TL || grab == Grab.TR) b.bottom else b.top
                var sx = (px - anchorX) / (grabStartPage[0] - anchorX)
                var sy = (py - anchorY) / (grabStartPage[1] - anchorY)
                if (!sx.isFinite() || abs(sx) < 0.04f) sx = 0.04f
                if (!sy.isFinite() || abs(sy) < 0.04f) sy = 0.04f
                // uniform scale keeps handwriting from being squashed into unreadable shapes
                val u = (abs(sx) + abs(sy)) / 2f
                applyLiveTransform(Matrix().apply { setScale(u, u, anchorX, anchorY) })
            }
            Grab.ROTATE -> {
                val cx = selBoundsAtGrab.centerX(); val cy = selBoundsAtGrab.centerY()
                val a0 = atan2(grabStartPage[1] - cy, grabStartPage[0] - cx)
                val a1 = atan2(py - cy, px - cx)
                var deg = Math.toDegrees((a1 - a0).toDouble()).toFloat()
                if (snapShapes) deg = (Math.round(deg / 15f) * 15).toFloat()
                applyLiveTransform(Matrix().apply { setRotate(deg, cx, cy) }, deg)
            }
            Grab.NONE -> Unit
        }
    }

    private fun applyLiveTransform(m: Matrix, addRotation: Float = 0f) {
        if (selectionAtGrab.isEmpty()) return
        pageIndexDirty = true
        val at = grabIndices
        selectionAtGrab.forEachIndexed { n, orig ->
            // Positions are worked out once when the drag starts. Searching the whole document
            // for each selected object on every frame made dragging a large selection across a
            // marked-up page progressively slower the more was on it.
            val i = at.getOrElse(n) { -1 }
            if (i in strokes.indices && strokes[i].id == orig.id) {
                var t = orig.transformed(m).copy(updatedUtc = now())
                if (addRotation != 0f) t = t.copy(rotation = orig.rotation + addRotation)
                strokes[i] = t
            }
        }
    }

    private fun endSelectGesture(cancelled: Boolean) {
        when (grab) {
            Grab.MARQUEE -> {
                marquee?.let { r ->
                    selection.clear()
                    strokes.filter { it.pageIndex == livePage && it.insideRect(r) }
                        .forEach { selection.add(it.id) }
                    onSelectionChanged?.invoke(selection.size)
                }
                marquee = null
            }
            Grab.MOVE, Grab.TL, Grab.TR, Grab.BL, Grab.BR, Grab.ROTATE -> {
                if (!cancelled && selectionAtGrab.isNotEmpty()) {
                    val after = selectedStrokes()
                    val moved = after.zip(selectionAtGrab).any { (a, b) -> a != b }
                    if (moved) { pushOp(Op(after, selectionAtGrab)); onContentChanged?.invoke() }
                }
            }
            Grab.NONE -> Unit
        }
        grab = Grab.NONE
        selectionAtGrab = emptyList()
    }

    private fun handleAt(vx: Float, vy: Float): Grab {
        val b = selectionBounds() ?: return Grab.NONE
        val v = RectF(b); pageToView.mapRect(v)
        val r = handleRadiusPx * 1.5f
        fun near(hx: Float, hy: Float) = hypot(vx - hx, vy - hy) <= r
        if (near(v.centerX(), v.top - rotateOffsetPx)) return Grab.ROTATE
        if (near(v.left, v.top)) return Grab.TL
        if (near(v.right, v.top)) return Grab.TR
        if (near(v.left, v.bottom)) return Grab.BL
        if (near(v.right, v.bottom)) return Grab.BR
        return Grab.NONE
    }

    private fun cellAt(table: Stroke, px: Float, py: Float): Pair<Int, Int>? {
        val r = table.rectOf()
        if (!r.contains(px, py)) return null
        val col = (((px - r.left) / (r.width() / table.cols)).toInt()).coerceIn(0, table.cols - 1)
        val row = (((py - r.top) / (r.height() / table.rows)).toInt()).coerceIn(0, table.rows - 1)
        return row to col
    }

    // ---- pan and zoom --------------------------------------------------------

    private fun handleTransformGesture(e: MotionEvent) {
        // A pointer can lift in the middle of a pinch, and Android keeps delivering MOVE events
        // for the remaining finger. Reading index 1 then throws IllegalArgumentException, which
        // is a hard crash during ordinary two-finger zooming - so bail out instead.
        if (e.pointerCount < 2) {
            lastSpan = 0f
            return
        }
        val fx = (e.getX(0) + e.getX(1)) / 2f
        val fy = (e.getY(0) + e.getY(1)) / 2f
        val span = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))

        when (e.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_DOWN -> {
                gesturing = true; lastFocusX = fx; lastFocusY = fy; lastSpan = span
            }
            MotionEvent.ACTION_MOVE -> {
                if (!gesturing) {
                    gesturing = true; lastFocusX = fx; lastFocusY = fy; lastSpan = span; return
                }
                pageToView.postTranslate(fx - lastFocusX, fy - lastFocusY)
                if (lastSpan > 8f && span > 8f) {
                    val cur = currentScale()
                    val next = (cur * (span / lastSpan)).coerceIn(minScale, maxScale)
                    val factor = next / cur
                    pageToView.postScale(factor, factor, fx, fy)
                }
                lastFocusX = fx; lastFocusY = fy; lastSpan = span
                loosePanning = true
                scheduleDetail()
                clampTranslation(loose = true); syncInverse(); invalidate()
                onTransformChanged?.invoke(); reportVisiblePages()
        scheduleDetail()
            }
            MotionEvent.ACTION_POINTER_UP -> lastSpan = 0f
        }
    }

    /** Keep the page on screen so it can never be flung off into the void. */
    /**
     * Keep the viewport somewhere sensible.
     *
     * Two behaviours, because they are wanted in different situations:
     *
     * - **Loose** (two-finger pan and zoom): the page moves freely and may sit with space on any
     *   side. Working near a margin means putting the edge of the page where your hand is, and
     *   snapping it flush against a screen edge fights that.
     * - **Strict** (the Pan tool, page jumps, layout changes): the page is held against the
     *   viewport and centred when it is smaller, which is what you want when deliberately
     *   scrolling through a document.
     *
     * Both keep the document reachable; neither can leave it lost off screen.
     */
    private fun clampTranslation(loose: Boolean = false) {
        if (slots.isEmpty()) return
        val r = RectF(reachBounds())
        pageToView.mapRect(r)
        var dx = 0f
        var dy = 0f

        if (loose) {
            val keepX = min(r.width(), min(width, height) * KEEP_VISIBLE_FRACTION)
            val keepY = min(r.height(), min(width, height) * KEEP_VISIBLE_FRACTION)
            if (r.right < keepX) dx = keepX - r.right
            else if (r.left > width - keepX) dx = (width - keepX) - r.left
            if (r.bottom < keepY) dy = keepY - r.bottom
            else if (r.top > height - keepY) dy = (height - keepY) - r.top
        } else {
            if (r.width() <= width) dx = (width - r.width()) / 2f - r.left
            else {
                if (r.left > 0) dx = -r.left
                if (r.right < width) dx = width - r.right
            }
            if (r.height() <= height) dy = (height - r.height()) / 2f - r.top
            else {
                if (r.top > 0) dy = -r.top
                if (r.bottom < height) dy = height - r.bottom
            }
        }

        if (abs(dx) > 0.01f || abs(dy) > 0.01f) pageToView.postTranslate(dx, dy)
    }

    override fun computeScroll() {
        if (!scroller.computeScrollOffset()) return
        val dx = (scroller.currX - lastFlingX).toFloat()
        val dy = (scroller.currY - lastFlingY).toFloat()
        lastFlingX = scroller.currX
        lastFlingY = scroller.currY
        if (dx == 0f && dy == 0f) {
            postInvalidateOnAnimation()
            return
        }

        val before = FloatArray(9).also { pageToView.getValues(it) }
        pageToView.postTranslate(dx, dy)
        // a fling continues under the rules of the gesture that threw it
        clampTranslation(loose = loosePanning)
        val after = FloatArray(9).also { pageToView.getValues(it) }

        // Clamping brings the fling to a hard stop at the edge of the document. Without this the
        // scroller keeps ticking against a wall, burning frames and delaying the settle.
        if (after[Matrix.MTRANS_X] == before[Matrix.MTRANS_X] &&
            after[Matrix.MTRANS_Y] == before[Matrix.MTRANS_Y]
        ) {
            scroller.abortAnimation()
        }

        syncInverse()
        reportVisiblePages()
        onTransformChanged?.invoke()
        scheduleDetail()
        postInvalidateOnAnimation()
    }

    private fun stopFling() {
        if (!scroller.isFinished) scroller.abortAnimation()
    }

    /** Launch momentum from a release velocity, in pixels per second. */
    private fun startFling(vx: Float, vy: Float) {
        if (!flingEnabled) return
        val speed = hypot(vx, vy)
        if (speed < minFlingVelocity) return
        lastFlingX = 0
        lastFlingY = 0
        val span = 1_000_000
        scroller.fling(
            0, 0,
            (vx * flingScale).toInt().coerceIn(-maxFlingVelocity.toInt(), maxFlingVelocity.toInt()),
            (vy * flingScale).toInt().coerceIn(-maxFlingVelocity.toInt(), maxFlingVelocity.toInt()),
            -span, span, -span, span
        )
        postInvalidateOnAnimation()
    }

    private fun trackVelocity(e: MotionEvent) {
        val tracker = velocityTracker ?: android.view.VelocityTracker.obtain().also {
            velocityTracker = it
        }
        tracker.addMovement(e)
    }

    private fun releaseVelocity(): Pair<Float, Float> {
        val tracker = velocityTracker ?: return 0f to 0f
        tracker.computeCurrentVelocity(1000, maxFlingVelocity)
        val v = tracker.xVelocity to tracker.yVelocity
        tracker.recycle()
        velocityTracker = null
        return v
    }

    private fun syncInverse() = pageToView.invert(viewToPage)

    /** Document-space coordinates of a view point, left in [tmpPts]. */
    private fun toDoc(vx: Float, vy: Float) {
        tmpPts[0] = vx; tmpPts[1] = vy
        viewToPage.mapPoints(tmpPts)
    }

    /**
     * View point in the coordinates of [livePage]. Strokes are stored page-local, so every
     * interaction converts through the owning page's origin rather than working in document
     * space - that is what keeps saving and export identical however pages are arranged.
     */
    private fun toPage(vx: Float, vy: Float) {
        toDoc(vx, vy)
        val o = originOf(livePage)
        tmpPts[0] -= o[0]; tmpPts[1] -= o[1]
    }

    /**
     * Turn one eraser gesture into a single undo step.
     *
     * A drag can rub a stroke into pieces and then rub a piece away again, so the raw lists
     * overlap: a fragment can appear in both. Cancelling those out leaves the net effect of the
     * gesture, which is what undo has to reverse - replaying the intermediate states would put
     * back half-erased pieces the user never saw stay.
     */
    private fun pushEraseOp() {
        val addedIds = addedThisGesture.map { it.id }.toHashSet()
        val removedIds = erasedThisGesture.map { it.id }.toHashSet()
        val netAdded = addedThisGesture.filterNot { it.id in removedIds }
        val netRemoved = erasedThisGesture.filterNot { it.id in addedIds }
        erasedThisGesture.clear()
        addedThisGesture.clear()
        if (netAdded.isEmpty() && netRemoved.isEmpty()) return
        pushOp(Op(netAdded, netRemoved))
    }

    private fun pushOp(op: Op) {
        undoStack.add(op)
        if (undoStack.size > 200) undoStack.removeAt(0)
        redoStack.clear()
    }

    private fun changed() {
        pageIndexDirty = true
        invalidate()
        onContentChanged?.invoke()
    }

    private companion object {
        /** Placeholder id for the stroke being drawn right now; never persisted. */
        const val LIVE_ID = "live"

        /** How long finger drawing stays blocked after a multi-touch gesture. */
        const val MULTITOUCH_GUARD_MS = 220L

        /**
         * How long a first finger is held before it is acted on.
         *
         * Long enough to cover the gap between two fingers landing, short enough to be invisible
         * - and irrelevant to anyone actually drawing, since real movement commits immediately.
         */
        const val COMMIT_HOLD_MS = 60L

        /** How close ink must be to the ruler, in view pixels, to snap onto it. */
        /** Below this many samples a surviving fragment is a crumb, not ink. */
        const val MIN_FRAGMENT_POINTS = 3

        /** More pieces than this from one pass means the stroke is being scrubbed out, not trimmed. */
        const val MAX_FRAGMENTS = 12

        /** Segments the eraser's path is reduced to before splitting strokes against it. */
        const val MAX_ERASE_SEGMENTS = 12

        /**
         * Slack in the swath's bounding box for half of a thick stroke's own width.
         *
         * The box decides which strokes are worth looking at, so it has to be generous enough
         * that a fat highlighter clipped only at its edge is still considered.
         */
        const val MAX_ERASE_REACH_PAD = 40f

        /** How far past its own edge a canvas can be scrolled, as a fraction of the viewport. */
        const val CANVAS_REACH_FRACTION = 0.9f

        /** Ink this close to the edge of a canvas makes it grow, in page points. */
        const val CANVAS_EDGE_PT = 24f

        /** Clear space left beyond ink when the canvas grows. */
        const val CANVAS_MARGIN_PT = 96f

        /** Growth is quantised to this, so writing along an edge does not re-lay-out constantly. */
        const val CANVAS_CHUNK_PT = 256f

        /**
         * Steps a single stroke may add.
         *
         * Generous, because with the camera held still growth is bounded by how far the hand
         * actually moved, and a long deliberate stroke should get its room as it is drawn. This
         * is a backstop against a runaway, not a working limit - anything the cap holds back is
         * added anyway when the stroke is finished and its real extent is known.
         */
        const val MAX_GROWTH_PER_GESTURE = 24

        /**
         * How far outside the canvas a growth request may reach before it is refused.
         *
         * A pen does not travel this far between two samples. A coordinate that says it did is a
         * bad coordinate, and growing to meet it is how a page ends up the size of a building.
         */
        const val MAX_GROWTH_STEP_PT = 2000f

        /** The largest a canvas may get in either direction, in points - about 55 feet. */
        const val MAX_CANVAS_SPAN_PT = 48000f

        const val RULER_SNAP_PT = 46f

        /** Half the drawn thickness of the ruler bar, in view pixels. */
        const val RULER_HALF_THICKNESS_PX = 34f

        /** Slack added to the culling rectangle, in page points. */
        const val CULL_MARGIN_PT = 96f

        /**
         * Fraction of the smaller viewport dimension that must stay covered by the document
         * while panning loosely.
         * Low enough to allow generous margins on any side, high enough that the page cannot be
         * pushed almost entirely off screen.
         */
        const val KEEP_VISIBLE_FRACTION = 0.22f

        /** Wait after the view stops moving before rendering a sharp tile. */
        const val DETAIL_SETTLE_MS = 220L

        /** How far the base raster may be stretched before a sharp tile is worth rendering. */
        const val DETAIL_TRIGGER = 1.25f

        /** Upper bound on the sharp tile, across its longest side. */
        const val DETAIL_MAX_PX = 3000

        /** Interval between re-requests for a visible page that has no bitmap yet. */
        const val RENDER_RETRY_MS = 700L

        /** How long an indicator may persist with nothing touching the screen. */
        const val INDICATOR_TIMEOUT_MS = 900L
    }
}
