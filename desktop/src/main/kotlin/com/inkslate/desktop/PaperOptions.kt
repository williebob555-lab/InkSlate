package com.inkslate.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.inkslate.desktop.BlankDocumentFactory.Background

/**
 * Everything that describes a piece of blank paper.
 *
 * Deliberately not [BlankDocumentFactory.Spec]: that carries a filename and a page count, which a
 * page being inserted into an existing document has no use for. This is the part the two share -
 * the same split the Android build makes, so that inserting a page can reuse it here too when it
 * arrives.
 */
data class PaperStyle(
    val background: Background = Background.PLAIN,
    val paperColor: Int = BlankDocumentFactory.WHITE,
    val lineColor: Int = BlankDocumentFactory.DEFAULT_RULING,
    val spacing: Float = 24f
)

/**
 * The paper controls.
 *
 * The Android `BlankPaperOptions`, minus the swatch that opens a full colour picker: that picker
 * has not been brought across yet, and an option that opens nothing is worse than one that is not
 * offered. The eighteen paper tints and sixteen ruling colours below are the same lists the
 * tablet shows, so a document made on either machine can be made on the other.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BlankPaperOptions(style: PaperStyle, onChange: (PaperStyle) -> Unit) {
    OptionLabel("Background")
    OptionWrapRow {
        Background.entries.forEach { b ->
            OptionChip(b.label, style.background == b) { onChange(style.copy(background = b)) }
        }
    }

    OptionLabel("Paper")
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(BlankDocumentFactory.PAPER_COLORS) { c ->
            Swatch(Color(c), style.paperColor == c) { onChange(style.copy(paperColor = c)) }
        }
    }

    if (style.background != Background.PLAIN) {
        OptionLabel("Ruling colour")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(BlankDocumentFactory.LINE_COLORS) { c ->
                Swatch(Color(c), style.lineColor == c) { onChange(style.copy(lineColor = c)) }
            }
        }

        OptionLabel("Spacing: ${style.spacing.toInt()} pt")
        Slider(
            value = style.spacing,
            onValueChange = { onChange(style.copy(spacing = it)) },
            valueRange = 8f..60f
        )
    }
}

/**
 * A small preview of what the paper will look like.
 *
 * The chips name the pattern, which is enough on its own - but they cannot show a colour, and
 * colour is most of what is being chosen here. Approximate on purpose, and approximate the same
 * way the tablet's is: this draws the character of the ruling, not the ruling itself, so it does
 * not go through [com.inkslate.core.PaperPattern] and is not expected to match it line for line.
 */
@Composable
fun PaperPreview(style: PaperStyle, modifier: Modifier = Modifier) {
    val paper = Color(style.paperColor)
    val line = Color(style.lineColor)
    Canvas(modifier) {
        drawRect(paper)
        if (style.background == Background.PLAIN) return@Canvas

        // The preview is a few hundred points of page squeezed into a thumbnail, so the spacing
        // is scaled to the box rather than taken literally; at true scale a ruled page would be
        // a solid block of lines.
        val step = (size.height * (style.spacing / 300f)).coerceIn(4f, size.height / 3f)
        val thin = 1f

        fun h(y: Float, from: Float = 0f, to: Float = size.width, w: Float = thin) =
            drawLine(line, Offset(from, y), Offset(to, y), w)

        fun v(x: Float, from: Float = 0f, to: Float = size.height, w: Float = thin) =
            drawLine(line, Offset(x, from), Offset(x, to), w)

        when (style.background) {
            Background.RULED -> {
                var y = step
                while (y < size.height) { h(y); y += step }
            }
            Background.GRID, Background.GRAPH -> {
                val s = if (style.background == Background.GRAPH) step / 2f else step
                var y = s
                while (y < size.height) { h(y); y += s }
                var x = s
                while (x < size.width) { v(x); x += s }
            }
            Background.DOTS -> {
                var y = step
                while (y < size.height) {
                    var x = step
                    while (x < size.width) {
                        drawCircle(line, 0.9f, Offset(x, y))
                        x += step
                    }
                    y += step
                }
            }
            Background.CORNELL -> {
                var y = step
                while (y < size.height * 0.82f) { h(y); y += step }
                v(size.width * 0.28f, 0f, size.height * 0.82f, 1.6f)
                h(size.height * 0.82f, w = 1.6f)
            }
            Background.MUSIC -> {
                val gap = (step / 3f).coerceAtLeast(1.6f)
                var top = step
                while (top + gap * 4 < size.height) {
                    for (i in 0..4) h(top + gap * i, size.width * 0.08f, size.width * 0.92f)
                    top += step * 2.2f
                }
            }
            Background.ISOMETRIC -> {
                var x = -size.height
                while (x < size.width + size.height) {
                    drawLine(line, Offset(x, 0f), Offset(x + size.height * 0.58f, size.height), thin)
                    drawLine(line, Offset(x, size.height), Offset(x + size.height * 0.58f, 0f), thin)
                    x += step * 1.7f
                }
                var y = step
                while (y < size.height) { h(y); y += step }
            }
            Background.PLAIN -> Unit
        }
    }
}

// ---- the shared bits of chrome -----------------------------------------------

@Composable
fun OptionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

/** Simple flow layout so option chips wrap instead of scrolling out of reach. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OptionWrapRow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) { content() }
}

@Composable
fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                if (selected) 3.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                CircleShape
            )
            .clickable(onClick = onClick)
    )
}
