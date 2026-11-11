package com.inkslate

import androidx.compose.ui.geometry.Offset
import com.inkslate.core.ToolConfig
import com.inkslate.ui.editor.QuickWheelAction
import com.inkslate.ui.editor.QuickWheelGesture
import com.inkslate.ui.editor.ToolState
import com.inkslate.ui.editor.advanceQuickWheelGesture
import com.inkslate.ui.editor.quickWheelMainAction
import com.inkslate.ui.editor.quickWheelPalette
import com.inkslate.ui.editor.quickWheelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class QuickWheelGestureTest {
    private val current = ToolConfig().snapshot()
    private val actions = QuickWheelAction.DEFAULTS
    private val anchor = Offset(300f, 300f)

    @Test fun `direction still selects after pen leaves the visible wheel`() {
        assertEquals(
            QuickWheelAction.PEN,
            quickWheelMainAction(actions, current, anchor, Offset(300f, -900f))
        )
    }

    @Test fun `colour opens only in its outer radial band and releases inward`() {
        val point = pointFor(QuickWheelAction.COLOR, 120f)
        val moved = advanceQuickWheelGesture(
            QuickWheelGesture(anchor), point, actions, current,
            activationRadiusPx = 44f,
            sizeTrackStartPx = 74f,
            sizeTrackLengthPx = 190f,
            sizeLadder = ToolState.WIDTHS
        )
        assertEquals(QuickWheelAction.COLOR, moved.submenu)
        val released = advanceQuickWheelGesture(
            moved, pointFor(QuickWheelAction.COLOR, 55f), actions, current,
            activationRadiusPx = 44f,
            sizeTrackStartPx = 74f,
            sizeTrackLengthPx = 190f,
            sizeLadder = ToolState.WIDTHS
        )
        assertEquals(null, released.submenu)
    }

    @Test fun `pulling size outward chooses a larger marked stop`() {
        val entered = advanceQuickWheelGesture(
            QuickWheelGesture(anchor), pointFor(QuickWheelAction.SIZE, 70f), actions, current,
            activationRadiusPx = 44f,
            sizeTrackStartPx = 74f,
            sizeTrackLengthPx = 190f,
            sizeLadder = ToolState.WIDTHS
        )
        val expanded = advanceQuickWheelGesture(
            entered, pointFor(QuickWheelAction.SIZE, 150f), actions, current,
            activationRadiusPx = 44f,
            sizeTrackStartPx = 74f,
            sizeTrackLengthPx = 190f,
            sizeLadder = ToolState.WIDTHS
        )
        assertEquals(QuickWheelAction.SIZE, expanded.submenu)
        assertTrue(expanded.previewSize!! > entered.previewSize!!)
    }

    @Test fun `circling the wheel never remains trapped in a submenu`() {
        var gesture = QuickWheelGesture(anchor)
        actions.forEach { action ->
            gesture = advanceQuickWheelGesture(
                gesture, pointFor(action, 58f), actions, current,
                activationRadiusPx = 44f,
                sizeTrackStartPx = 74f,
                sizeTrackLengthPx = 190f,
                sizeLadder = ToolState.WIDTHS
            )
            assertEquals(action, quickWheelMainAction(actions, current, anchor, gesture.pointer))
            if (action != QuickWheelAction.SIZE) assertEquals(null, gesture.submenu)
        }
    }

    @Test fun `size track reaches both real extremes and palette order is fixed`() {
        assertEquals(ToolState.WIDTHS.first(), quickWheelSize(ToolState.WIDTHS, 74f, 74f, 190f))
        assertEquals(ToolState.WIDTHS.last(), quickWheelSize(ToolState.WIDTHS, 264f, 74f, 190f))
        assertEquals(
            listOf(0xFF111827.toInt(), 0xFF2563EB.toInt(), 0xFF7C3AED.toInt()),
            quickWheelPalette().take(3)
        )
    }

    private fun pointFor(action: QuickWheelAction, radius: Float): Offset {
        val index = actions.indexOf(action)
        val angle = Math.toRadians((-90.0 + index * 360.0 / actions.size))
        return Offset(
            anchor.x + cos(angle).toFloat() * radius,
            anchor.y + sin(angle).toFloat() * radius
        )
    }
}
