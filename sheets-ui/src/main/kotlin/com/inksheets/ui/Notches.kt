package com.inksheets.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Tabs that sit over the edge of what is under them - the remote's page tabs over its buttons -
 * and the things under them carved round them: each a smooth notch the tab nests in, an even gap
 * all round, so the two read as made to fit rather than one lying on the other.
 */
internal object Notches {
    /** The tabs now, by name: where each is (root pixels) and how round its corners are (pixels). */
    val tabs = mutableStateMapOf<String, Pair<Rect, Float>>()
}

/** This element is a tab others are notched round, named [name], its corners [radius] round. */
@Composable
internal fun Modifier.notchTab(name: String, radius: Dp): Modifier {
    val r = with(LocalDensity.current) { radius.toPx() }
    DisposableEffect(name) { onDispose { Notches.tabs.remove(name) } }
    return this.onGloballyPositioned { Notches.tabs[name] = it.boundsInRoot() to r }
}

/**
 * A rounded rectangle of [corner] with a notch carved out for each of [notches] (in its own
 * pixels): the tab's own shape grown by [gap] all round, its corners as round again.
 */
internal class NotchedShape(private val corner: Dp, private val notches: List<Pair<Rect, Float>>, private val gap: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = with(density) { corner.toPx() }
        val g = with(density) { gap.toPx() }
        var path = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(c))) }
        for ((r, radius) in notches) {
            val hole = Path().apply { addRoundRect(RoundRect(r.left - g, r.top - g, r.right + g, r.bottom + g, CornerRadius(radius + g))) }
            path = Path.combine(PathOperation.Difference, path, hole)
        }
        return Outline.Generic(path)
    }

    override fun equals(other: Any?) = other is NotchedShape && other.corner == corner && other.notches == notches && other.gap == gap
    override fun hashCode() = notches.hashCode() * 31 + corner.hashCode()
}

/**
 * The shape for an element [corner] round that the tabs ([Notches]) may lie over - carved round
 * whichever do - and the modifier that tells it where it is. Use the modifier on the element
 * itself and the shape for its background and clip.
 */
@Composable
internal fun rememberNotched(corner: Dp, gap: Dp = 5.dp): Pair<Modifier, Shape> {
    var at by remember { mutableStateOf(Rect.Zero) }
    val g = with(LocalDensity.current) { gap.toPx() }
    val here = at
    val notches = if (here.width <= 0f) emptyList() else Notches.tabs.values.mapNotNull { (r, radius) ->
        val grown = Rect(r.left - g, r.top - g, r.right + g, r.bottom + g)
        if (!grown.overlaps(here)) null else Rect(r.left - here.left, r.top - here.top, r.right - here.left, r.bottom - here.top) to radius
    }
    val shape = remember(notches, corner, gap) { NotchedShape(corner, notches, gap) }
    return Modifier.onGloballyPositioned { at = it.boundsInRoot() } to shape
}
