package com.inkslate

import androidx.compose.ui.geometry.Offset
import com.inkslate.core.ToolConfig
import com.inkslate.core.AnnotationLayers
import com.inkslate.core.DesignConfig
import com.inkslate.ui.editor.QuickWheelAction
import com.inkslate.ui.editor.QuickWheelGesture
import com.inkslate.ui.editor.ToolState
import com.inkslate.ui.editor.advanceQuickWheelGesture
import com.inkslate.ui.editor.quickWheelMainAction
import com.inkslate.ui.editor.quickWheelPalette
import com.inkslate.ui.editor.quickWheelSize
import com.inkslate.ui.editor.quickWheelLayer
import com.inkslate.ui.editor.quickWheelActionAngle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import kotlin.math.cos
import kotlin.math.sin

class QuickWheelGestureTest {
    private val current = ToolConfig().snapshot()
    private val actions = QuickWheelAction.DEFAULTS
    private val anchor = Offset(300f, 300f)

    @Before fun defaultDesign() { DesignConfig.install(DesignConfig()) }

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
            if (action != QuickWheelAction.SIZE && action != QuickWheelAction.LAYER_DRAW)
                assertEquals(null, gesture.submenu)
        }
    }

    @Test fun `layer draw stays bottom right regardless of optional action count`() {
        for (configured in listOf(actions, listOf(QuickWheelAction.PEN, QuickWheelAction.LAYER_DRAW),
            listOf(QuickWheelAction.COLOR, QuickWheelAction.LAYER_DRAW, QuickWheelAction.SIZE))) {
            assertEquals(45f, quickWheelActionAngle(configured, QuickWheelAction.LAYER_DRAW), .001f)
            assertEquals(QuickWheelAction.LAYER_DRAW,
                quickWheelMainAction(configured, current, anchor, pointAt(45f, 54f)))
        }
    }

    @Test fun `layer draw begins at one and progresses to two then three outward`() {
        var gesture = QuickWheelGesture(anchor)
        for ((radius, layer) in listOf(54f to AnnotationLayers.ONE, 134f to AnnotationLayers.TWO,
            194f to AnnotationLayers.THREE, 400f to AnnotationLayers.THREE)) {
            gesture = advance(gesture, pointAt(45f, radius))
            assertEquals(QuickWheelAction.LAYER_DRAW, gesture.submenu)
            assertEquals(layer, gesture.previewLayer)
        }
        assertEquals(AnnotationLayers.ONE, quickWheelLayer(0f, 74f, 120f))
        assertEquals(AnnotationLayers.ONE, quickWheelLayer(74f, 74f, 120f))
        assertEquals(AnnotationLayers.TWO, quickWheelLayer(134f, 74f, 120f))
        assertEquals(AnnotationLayers.THREE, quickWheelLayer(194f, 74f, 120f))
    }

    @Test fun `returning inside activation boundary cancels the layer preview`() {
        val entered = advance(QuickWheelGesture(anchor), pointAt(45f, 194f))
        val cancelled = advance(entered, pointAt(45f, 43f))
        assertEquals(null, cancelled.submenu)
        assertEquals(null, cancelled.previewLayer)
        val centered = advance(entered, anchor)
        assertEquals(null, centered.submenu)
        assertEquals(null, centered.previewLayer)
        assertEquals(QuickWheelAction.PEN, quickWheelMainAction(actions, current, anchor, centered.pointer))
    }

    @Test fun `leaving the layer lane releases its preview and allows other actions`() {
        val entered = advance(QuickWheelGesture(anchor), pointAt(45f, 194f))
        val left = advance(entered, pointAt(90f, 134f))
        assertEquals(null, left.submenu)
        assertEquals(null, left.previewLayer)
        assertEquals(QuickWheelAction.SELECT_ONCE, quickWheelMainAction(actions, current, anchor, left.pointer))
    }

    @Test fun `layer radial selection scales with display density`() {
        val doubled = advanceQuickWheelGesture(QuickWheelGesture(anchor), pointAt(45f, 268f),
            actions, current, activationRadiusPx = 88f, sizeTrackStartPx = 148f,
            sizeTrackLengthPx = 380f, sizeLadder = ToolState.WIDTHS)
        assertEquals(AnnotationLayers.TWO, doubled.previewLayer)
    }

    private fun advance(gesture: QuickWheelGesture, point: Offset) = advanceQuickWheelGesture(
        gesture, point, actions, current, activationRadiusPx = 44f,
        sizeTrackStartPx = 74f, sizeTrackLengthPx = 190f, sizeLadder = ToolState.WIDTHS
    )

    private fun pointAt(degrees: Float, radius: Float): Offset {
        val angle = Math.toRadians(degrees.toDouble())
        return Offset(anchor.x + cos(angle).toFloat() * radius, anchor.y + sin(angle).toFloat() * radius)
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
        val angle = Math.toRadians(quickWheelActionAngle(actions, action).toDouble())
        return Offset(
            anchor.x + cos(angle).toFloat() * radius,
            anchor.y + sin(angle).toFloat() * radius
        )
    }
}
