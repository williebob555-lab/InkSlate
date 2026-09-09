package com.inkslate.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import com.inkslate.core.Stroke
import com.inkslate.core.TextFont
import kotlin.math.max

/** The font a [TextFont] names, as this platform provides it. */
fun TextFont.toFontFamily(): FontFamily = when (this) {
    TextFont.SANS -> FontFamily.SansSerif
    TextFont.SERIF -> FontFamily.Serif
    TextFont.MONO -> FontFamily.Monospace
    TextFont.CASUAL -> FontFamily.Cursive
}

/** The style a text stroke is drawn in, in page points. */
fun Stroke.textStyle(): TextStyle = TextStyle(
    color = color.toComposeColor(),
    // Page points, not scaled pixels: the canvas is already scaled into page space, so asking
    // for sp here would apply the system's font scale on top of the document's own geometry and
    // the same document would lay out differently on two machines.
    fontSize = TextUnit(textSize, TextUnitType.Sp),
    fontFamily = font.toFontFamily(),
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal
)

/**
 * Draw a text box: its background, its border, then its lines.
 *
 * Line breaking comes from [Stroke.wrapLines], the model's own, measured with the font actually
 * drawing. Letting Compose wrap instead would be less code and would put the screen out of step
 * with the PDF exporter, which has no Compose to ask - and a paragraph that breaks in different
 * places on screen and on the page is the sort of thing nobody notices until it is printed.
 */
fun DrawScope.drawTextStroke(s: Stroke, measurer: TextMeasurer) {
    val p = s.points.firstOrNull() ?: return
    val style = s.textStyle()
    val measure: (String) -> Float = { line ->
        if (line.isEmpty()) 0f
        else measurer.measure(line, style).size.width.toFloat()
    }

    val lines = s.wrapLines(measure)
    val contentWidth =
        if (s.boxWidth > 0f) s.boxWidth - s.padding * 2f
        else max(8f, lines.maxOfOrNull(measure) ?: 0f)
    val boxW = if (s.boxWidth > 0f) s.boxWidth else contentWidth + s.padding * 2f
    val boxH =
        if (s.boxHeight > 0f) s.boxHeight
        else max(1, lines.size) * s.textSize * s.lineSpacing + s.padding * 2f

    if (s.boxFillColor != 0) {
        drawRect(
            s.boxFillColor.toComposeColor(),
            topLeft = Offset(p.x, p.y),
            size = Size(boxW, boxH)
        )
    }
    if (s.boxBorder) {
        drawRect(
            s.color.toComposeColor(),
            topLeft = Offset(p.x, p.y),
            size = Size(boxW, boxH),
            style = DrawStroke(width = max(0.4f, s.baseWidth))
        )
    }

    var y = p.y + s.padding
    for (line in lines) {
        if (line.isNotEmpty()) {
            val w = measure(line)
            drawText(
                textMeasurer = measurer,
                text = line,
                topLeft = Offset(p.x + s.padding + s.lineOffsetX(w, contentWidth), y),
                style = style
            )
        }
        y += s.textSize * s.lineSpacing
    }
}

/**
 * The lines a text stroke breaks into, and how wide its content area is.
 *
 * Pulled out so the exporter can lay a text box out exactly as the screen did without a
 * [DrawScope] to draw into.
 */
fun Stroke.layoutText(measurer: TextMeasurer): Pair<List<String>, Float> {
    val style = textStyle()
    val measure: (String) -> Float = { line ->
        if (line.isEmpty()) 0f else measurer.measure(line, style).size.width.toFloat()
    }
    val lines = wrapLines(measure)
    val contentWidth =
        if (boxWidth > 0f) boxWidth - padding * 2f
        else max(8f, lines.maxOfOrNull(measure) ?: 0f)
    return lines to contentWidth
}
