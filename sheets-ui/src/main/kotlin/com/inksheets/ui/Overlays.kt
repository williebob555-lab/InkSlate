package com.inksheets.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The space over the music, shared fairly. The strips down the sides say how much of each side
 * they take ([left], [right]); a window over the music (a floating panel, Fix) says it is open
 * ([Opened]) and how wide it would like to be. Where the room between the strips is enough the
 * windows sit in it; where it is not (a phone, a small tablet stood up) the strips step aside -
 * each folds into a tab at its edge - while a window is open, and come back when it closes, or
 * at once when their tab is tapped.
 */
internal object Overlays {
    /** How wide each strip is when out (measured; 0 where it is not shown at all), and which side the action strip is on. */
    var actionOut by mutableStateOf(0.dp)
    var musicOut by mutableStateOf(0.dp)
    var actionOnLeft by mutableStateOf(false)

    /** The windows open over the music, by name, and the width each wants. */
    private val open = mutableStateMapOf<String, Dp>()

    /** The strips brought back by their tab, though a window is open: until the windows change. */
    var stripsBack by mutableStateOf(false)

    /** The screen's width, as the strips last saw it. */
    var screen by mutableStateOf(0.dp)

    /** The widest window open, or nothing. */
    val wanted: Dp? get() = open.values.maxOrNull()

    /**
     * Whether the strips step aside now: a window open that does not fit between them as they are
     * out - and they were not called back.
     */
    val stripsAside: Boolean get() {
        val w = wanted ?: return false
        if (stripsBack) return false
        return screen > 0.dp && screen - actionOut - musicOut - 16.dp < w
    }

    /** How much of each edge the strips take now: a tab's width when they have stepped aside. */
    private fun side(onLeft: Boolean): Dp {
        val action = if (actionOnLeft == onLeft) actionOut else 0.dp
        val music = if (actionOnLeft != onLeft) musicOut else 0.dp
        val out = maxOf(action, music)
        return if (out == 0.dp) 0.dp else if (stripsAside) TAB else out
    }
    val left: Dp get() = side(true)
    val right: Dp get() = side(false)

    val TAB = 22.dp

    fun opened(name: String, width: Dp) { open[name] = width; stripsBack = false }
    fun closed(name: String) { open.remove(name); stripsBack = false }
}

/** While shown: a window over the music of [width], named [name] (see [Overlays]). */
@Composable
internal fun Opened(name: String, width: Dp) {
    DisposableEffect(name, width) {
        Overlays.opened(name, width)
        onDispose { Overlays.closed(name) }
    }
}

/**
 * A strip stepped aside: a tab at its edge, its inner side rounded off, the way back to it -
 * tapped, the strips come out again over the window.
 */
@Composable
internal fun BoxScope.StripTab(onLeft: Boolean, what: String) {
    val shape = if (onLeft) RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp) else RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)
    Surface(
        shape = shape,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        modifier = Modifier.align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd).size(22.dp, 72.dp)
            .semantics { contentDescription = "Show $what" }
            .clickable { Overlays.stripsBack = true }
    ) {
        Box(contentAlignment = Alignment.Center) {
            // A grip and an arrow pointing the way the strip comes back in.
            Box(Modifier.size(3.dp, 22.dp).background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)).align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd))
            Icon(if (onLeft) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.AutoMirrored.Filled.KeyboardArrowLeft, null,
                Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** [onWidth] told this element's width in pixels whenever it is placed. */
internal fun Modifier.reportWidth(onWidth: (Int) -> Unit): Modifier =
    this.then(Modifier.onGloballyPositioned { onWidth(it.size.width) })
