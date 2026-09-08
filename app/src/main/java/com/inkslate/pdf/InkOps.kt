package com.inkslate.pdf

import android.graphics.Color
import com.inkslate.core.Stroke.Kind as StrokeKind
import com.inkslate.core.StrokeOutline
import com.inkslate.ink.BrushType
import com.inkslate.ink.DashStyle
import com.inkslate.ink.FillStyle
import com.inkslate.ink.Stroke
import com.inkslate.ink.rawBounds
import com.inkslate.ink.rectOf
import com.inkslate.ink.rotationMatrix
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Writes PDF page operators directly, for the marks that make up nearly all of a document.
 *
 * `PDPageContentStream` is a general-purpose writer: every operand goes through formatting, a
 * resource dictionary and a deflating stream one small write at a time. That is the right shape
 * for a library and the wrong shape for this, where a single save emits about a dozen distinct
 * operators and something like a hundred thousand numbers - a marked-up page becomes thousands of
 * small filled polygons, because PDF has no variable-width stroke and tapered ink has to be a
 * shape either way.
 *
 * So the operators are built here into one buffer, with a number formatter that does the only
 * thing this needs - two decimal places, which is a five-hundredth of a millimetre - and the
 * whole buffer is handed over to be compressed once.
 *
 * Deliberately partial. Text needs fonts and pasted images need image XObjects, and reproducing
 * PdfBox's handling of those to save a few milliseconds on marks that are rare would be trading a
 * real risk for nothing. [canEmit] says what is covered, and a page holding anything else is
 * built the old way in full - never half here and half there, because the two would have to be
 * interleaved to keep what is drawn on top on top.
 */
internal object InkOps {

    /** Whether every mark on a page can be written here. */
    fun canEmitAll(strokes: List<Stroke>): Boolean = strokes.all { canEmit(it) }

    private fun canEmit(s: Stroke): Boolean = when (s.kind) {
        StrokeKind.FREEHAND, StrokeKind.LINE, StrokeKind.ARROW,
        StrokeKind.RECT, StrokeKind.ELLIPSE -> true
        StrokeKind.TEXT, StrokeKind.TABLE, StrokeKind.IMAGE -> false
    }

    /**
     * Build the content stream for one page's worth of marks.
     *
     * [resources] collects the transparency states the operators refer to by name; it belongs to
     * the appearance stream these bytes will become.
     */
    fun emitPage(
        strokes: List<Stroke>,
        toUser: Matrix,
        resources: PDResources
    ): ByteArray {
        val out = Ops()
        out.op("q")
        out.matrix(toUser)
        for (s in strokes.sortedBy { if (it.isHighlighter) 0 else 1 }) emit(out, s, resources)
        out.op("Q")
        return out.bytes()
    }

    private fun emit(out: Ops, s: Stroke, resources: PDResources) {
        out.op("q")

        // Transparency and blend mode, named out of the page's resource dictionary. Identical
        // states are shared rather than added again, which on a page of one colour is one entry
        // instead of several hundred.
        val alpha = s.effectiveAlpha.coerceIn(0f, 1f)
        out.name(stateFor(resources, alpha, s.usesMultiply)).op("gs")

        s.rotationMatrix()?.let {
            val c = s.rawBounds()
            val rad = Math.toRadians(s.rotation.toDouble())
            out.matrix(Matrix(1f, 0f, 0f, 1f, c.centerX(), c.centerY()))
            out.matrix(
                Matrix(
                    cos(rad).toFloat(), sin(rad).toFloat(),
                    (-sin(rad)).toFloat(), cos(rad).toFloat(), 0f, 0f
                )
            )
            out.matrix(Matrix(1f, 0f, 0f, 1f, -c.centerX(), -c.centerY()))
        }

        colour(out, s)
        out.int(if (s.brush == BrushType.MARKER || s.isHighlighter) 2 else 1).op("J")
        out.int(1).op("j")
        s.dash.pattern?.let { pattern ->
            out.raw('['.code.toByte())
            for (v in pattern) out.num(v)
            out.raw(']'.code.toByte()).raw(' '.code.toByte()).int(0).op("d")
        }

        when (s.kind) {
            StrokeKind.FREEHAND -> freehand(out, s)
            StrokeKind.LINE, StrokeKind.ARROW -> line(out, s)
            StrokeKind.RECT -> rect(out, s)
            StrokeKind.ELLIPSE -> ellipse(out, s)
            else -> Unit
        }

        out.op("Q")
    }

    private fun colour(out: Ops, s: Stroke) {
        out.num(Color.red(s.color) / 255f).num(Color.green(s.color) / 255f)
            .num(Color.blue(s.color) / 255f).op("RG")
        val fc = if (s.fill == FillStyle.NONE) s.color else s.fillColor
        out.num(Color.red(fc) / 255f).num(Color.green(fc) / 255f)
            .num(Color.blue(fc) / 255f).op("rg")
    }

    private fun freehand(out: Ops, s: Stroke) {
        if (s.points.isEmpty()) return
        if (s.points.size == 1) {
            val p = s.points[0]
            out.num(max(0.05f, p.width)).op("w")
            out.num(p.x).num(p.y).op("m").num(p.x + 0.01f).num(p.y).op("l").op("S")
            return
        }
        if (s.usesOutlineRender) {
            for (poly in StrokeOutline.contours(s)) {
                if (poly.size < 6) continue
                out.num(poly[0]).num(poly[1]).op("m")
                var i = 2
                while (i < poly.size) {
                    out.num(poly[i]).num(poly[i + 1]).op("l")
                    i += 2
                }
                out.op("h")
            }
            out.op("f")
            return
        }
        out.num(max(0.05f, s.baseWidth)).op("w")
        out.num(s.points[0].x).num(s.points[0].y).op("m")
        for (i in 1 until s.points.size) {
            val prev = s.points[i - 1]
            val cur = s.points[i]
            // curveTo1: the current point is the first control point, so only the second control
            // point and the endpoint are written. Matches what the renderer draws.
            out.num(prev.x).num(prev.y)
                .num((prev.x + cur.x) / 2f).num((prev.y + cur.y) / 2f).op("v")
        }
        out.num(s.points.last().x).num(s.points.last().y).op("l").op("S")
    }

    private fun line(out: Ops, s: Stroke) {
        val a = s.points.first()
        val b = s.points.last()
        out.num(max(0.05f, s.baseWidth)).op("w")
        out.num(a.x).num(a.y).op("m").num(b.x).num(b.y).op("l").op("S")
        if (s.kind != StrokeKind.ARROW) return
        val size = max(3f, s.baseWidth * 3.6f)
        val angle = Math.atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
        val spread = Math.toRadians(26.0)
        for (side in listOf(-spread, spread)) {
            val ang = angle + Math.PI + side
            out.num(b.x).num(b.y).op("m")
            out.num(b.x + (cos(ang) * size).toFloat())
                .num(b.y + (sin(ang) * size).toFloat()).op("l").op("S")
        }
    }

    private fun rect(out: Ops, s: Stroke) {
        val r = s.rectOf()
        out.num(max(0.05f, s.baseWidth)).op("w")
        out.num(r.left).num(r.top).num(r.width()).num(r.height()).op("re")
        finish(out, s)
    }

    private fun ellipse(out: Ops, s: Stroke) {
        val r = s.rectOf()
        val cx = r.centerX(); val cy = r.centerY()
        val rx = r.width() / 2f; val ry = r.height() / 2f
        val k = 0.5523f
        out.num(max(0.05f, s.baseWidth)).op("w")
        out.num(cx - rx).num(cy).op("m")
        out.num(cx - rx).num(cy - ry * k).num(cx - rx * k).num(cy - ry).num(cx).num(cy - ry).op("c")
        out.num(cx + rx * k).num(cy - ry).num(cx + rx).num(cy - ry * k).num(cx + rx).num(cy).op("c")
        out.num(cx + rx).num(cy + ry * k).num(cx + rx * k).num(cy + ry).num(cx).num(cy + ry).op("c")
        out.num(cx - rx * k).num(cy + ry).num(cx - rx).num(cy + ry * k).num(cx - rx).num(cy).op("c")
        out.op("h")
        finish(out, s)
    }

    private fun finish(out: Ops, s: Stroke) {
        out.op(if (s.fill == FillStyle.NONE) "S" else "B")
    }

    // ---- resources -----------------------------------------------------------

    private fun stateFor(resources: PDResources, alpha: Float, multiply: Boolean): String {
        val wanted = PDExtendedGraphicsState().apply {
            strokingAlphaConstant = alpha
            nonStrokingAlphaConstant = alpha
            if (multiply) blendMode = BlendMode.MULTIPLY
        }
        // Reuse an identical state rather than adding another. A page of one-colour handwriting
        // would otherwise carry one graphics state per stroke, all of them the same.
        for (name in resources.extGStateNames) {
            val existing = resources.getExtGState(name) ?: continue
            if (existing.nonStrokingAlphaConstant == alpha &&
                existing.strokingAlphaConstant == alpha &&
                (existing.blendMode == BlendMode.MULTIPLY) == multiply
            ) {
                return name.name
            }
        }
        return resources.add(wanted).name
    }

    // ---- the buffer ----------------------------------------------------------

    /**
     * Operators and operands, written straight out as bytes.
     *
     * The number formatter is the point of the whole class. Coordinates are rounded to a
     * hundredth of a point and written as an integer, a dot and at most two digits - no locales,
     * no pattern, no allocation. Everything here is on a path that runs a hundred thousand times
     * per save.
     */
    private class Ops(initial: Int = 1 shl 18) {
        private val out = ByteArrayOutputStream(initial)
        private val digits = ByteArray(20)

        fun bytes(): ByteArray = out.toByteArray()

        fun raw(b: Byte): Ops {
            out.write(b.toInt())
            return this
        }

        fun op(name: String): Ops {
            for (c in name) out.write(c.code)
            out.write('\n'.code)
            return this
        }

        fun name(n: String): Ops {
            out.write('/'.code)
            for (c in n) out.write(c.code)
            out.write(' '.code)
            return this
        }

        fun int(v: Int): Ops {
            whole(v.toLong())
            out.write(' '.code)
            return this
        }

        fun num(v: Float): Ops {
            var scaled = (v.toDouble() * 100.0).roundToLong()
            if (scaled < 0) { out.write('-'.code); scaled = -scaled }
            whole(scaled / 100)
            val frac = (scaled % 100).toInt()
            if (frac != 0) {
                out.write('.'.code)
                out.write('0'.code + frac / 10)
                if (frac % 10 != 0) out.write('0'.code + frac % 10)
            }
            out.write(' '.code)
            return this
        }

        fun matrix(m: Matrix): Ops {
            num(m.scaleX).num(m.shearY).num(m.shearX).num(m.scaleY)
                .num(m.translateX).num(m.translateY).op("cm")
            return this
        }

        private fun whole(value: Long) {
            if (value == 0L) { out.write('0'.code); return }
            var v = value
            var n = 0
            while (v > 0) { digits[n++] = ('0'.code + (v % 10).toInt()).toByte(); v /= 10 }
            while (n > 0) out.write(digits[--n].toInt())
        }
    }
}
