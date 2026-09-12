package com.inkslate.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.Toolkit

/**
 * Where the window is, across a rotation and across a restart.
 *
 * Two things a desktop program is expected to do that this did not. It opened at a fixed size in
 * the middle of the screen every time, however it was left; and when the machine was folded into a
 * tablet and the screen turned, the window kept the shape it had in landscape - which on a portrait
 * screen means most of it is off the edge, with the toolbar running out of sight.
 *
 * The screen is watched rather than listened to, because the toolkit has no event for a display
 * being reconfigured. Once a second is far below noticing and costs a field read.
 */
object WindowMemory {

    private const val K_WIDTH = "window/width"
    private const val K_HEIGHT = "window/height"
    private const val K_X = "window/x"
    private const val K_Y = "window/y"
    private const val K_MAXIMISED = "window/maximised"

    /** Opening smaller than this is not a window anyone meant to leave behind. */
    private val MINIMUM = DpSize(640.dp, 480.dp)

    fun restore(): WindowState {
        val screen = screenSize()
        val width = DesktopPrefs.get(K_WIDTH)?.toFloatOrNull()
        val height = DesktopPrefs.get(K_HEIGHT)?.toFloatOrNull()
        val x = DesktopPrefs.get(K_X)?.toFloatOrNull()
        val y = DesktopPrefs.get(K_Y)?.toFloatOrNull()
        val maximised = DesktopPrefs.get(K_MAXIMISED)?.toBoolean() ?: false

        val size = if (width != null && height != null) {
            fitted(DpSize(width.dp, height.dp), screen)
        } else {
            fitted(DpSize(1200.dp, 900.dp), screen)
        }

        // A remembered position on a screen that is no longer there - an unplugged monitor, or the
        // same screen turned on its side - puts the window somewhere it cannot be reached.
        val position = if (x != null && y != null && onScreen(x, y, size, screen)) {
            WindowPosition(x.dp, y.dp)
        } else {
            WindowPosition.PlatformDefault
        }

        return WindowState(
            size = size,
            position = position,
            placement = if (maximised) WindowPlacement.Maximized else WindowPlacement.Floating
        )
    }

    fun remember(state: WindowState) {
        DesktopPrefs.put(K_MAXIMISED, (state.placement == WindowPlacement.Maximized).toString())
        // Only a floating window's shape is worth keeping: a maximised one has the screen's.
        if (state.placement != WindowPlacement.Floating) return
        DesktopPrefs.put(K_WIDTH, state.size.width.value.toString())
        DesktopPrefs.put(K_HEIGHT, state.size.height.value.toString())
        val position = state.position
        if (position is WindowPosition.Absolute) {
            DesktopPrefs.put(K_X, position.x.value.toString())
            DesktopPrefs.put(K_Y, position.y.value.toString())
        }
    }

    /**
     * Bring the window back inside a screen that has changed shape under it.
     *
     * Only ever shrinks and nudges. A window that already fits is left exactly as it was, because
     * a rotation that moves a window nobody asked to move is its own annoyance.
     */
    fun refit(state: WindowState) {
        if (state.placement != WindowPlacement.Floating) return
        val screen = screenSize()
        val size = fitted(state.size, screen)
        if (size != state.size) state.size = size

        val position = state.position
        if (position is WindowPosition.Absolute) {
            val x = position.x.value.coerceIn(0f, (screen.width.value - size.width.value).coerceAtLeast(0f))
            val y = position.y.value.coerceIn(0f, (screen.height.value - size.height.value).coerceAtLeast(0f))
            if (x != position.x.value || y != position.y.value) {
                state.position = WindowPosition(x.dp, y.dp)
            }
        }
    }

    /** Whether this maximised the window itself, and so may put it back. */
    private var maximisedForTablet = false

    /**
     * Fill the screen when the machine is folded into a tablet, and give the window back when it
     * is opened again.
     *
     * A window that was already maximised is not touched, and neither is one the person maximised
     * themselves - undoing that would be this program deciding it knows better about a window it
     * did not arrange.
     */
    fun postureChanged(state: WindowState, tablet: Boolean) {
        if (tablet) {
            if (state.placement == WindowPlacement.Floating) {
                remember(state)
                state.placement = WindowPlacement.Maximized
                maximisedForTablet = true
            }
        } else if (maximisedForTablet) {
            maximisedForTablet = false
            state.placement = WindowPlacement.Floating
            refit(state)
        }
    }

    fun screenSize(): DpSize = runCatching {
        val screen = Toolkit.getDefaultToolkit().screenSize
        DpSize(screen.width.dp, screen.height.dp)
    }.getOrDefault(DpSize(1280.dp, 800.dp))

    private fun fitted(size: DpSize, screen: DpSize): DpSize = DpSize(
        size.width.value.coerceIn(MINIMUM.width.value, screen.width.value).dp,
        size.height.value.coerceIn(MINIMUM.height.value, screen.height.value).dp
    )

    private fun onScreen(x: Float, y: Float, size: DpSize, screen: DpSize): Boolean {
        // A strip of the title bar reachable is enough; the rest can hang off the edge the way any
        // window may, so long as it can be picked up again.
        val reachable = 80f
        return x + size.width.value > reachable &&
            y >= -1f &&
            x < screen.width.value - reachable &&
            y < screen.height.value - reachable
    }
}
