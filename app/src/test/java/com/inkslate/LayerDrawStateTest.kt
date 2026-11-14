package com.inkslate

import android.content.Context
import com.inkslate.core.AnnotationLayers
import com.inkslate.core.BrushType
import com.inkslate.core.DesignConfig
import com.inkslate.core.Tool
import com.inkslate.ui.editor.QuickWheelAction
import com.inkslate.ui.editor.ToolState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class LayerDrawStateTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("tools", Context.MODE_PRIVATE)
    private lateinit var originalDesign: DesignConfig

    @Before fun prepare() {
        originalDesign = DesignConfig.current
        DesignConfig.install(DesignConfig())
        preferences.edit().clear().commit()
    }

    @After fun restore() {
        preferences.edit().clear().commit()
        DesignConfig.install(originalDesign)
    }

    @Test fun `layer pen replaces multi record while retaining other visibility and faders`() {
        preferences.edit()
            .putInt("record_annotation_layers", AnnotationLayers.ALL)
            .putInt("visible_annotation_layers", AnnotationLayers.ONE or AnnotationLayers.THREE)
            .putString("annotation_layer_opacities", "0.35,0.0,0.65")
            .commit()
        val state = ToolState(context)
        state.pen.brush = BrushType.GEL
        state.pen.color = 0xFF2764BE.toInt()
        state.pen.strokeWidth = 3.5f
        state.pen.tool = Tool.ERASER

        state.selectDrawLayer(AnnotationLayers.TWO)

        assertEquals(AnnotationLayers.TWO, state.recordLayerMask)
        assertEquals(AnnotationLayers.ALL, state.visibleLayerMask)
        assertEquals(listOf(.35f, 1f, .65f), state.layerOpacities)
        assertEquals(Tool.DRAW, state.pen.tool)
        assertEquals(BrushType.GEL, state.pen.brush)
        assertEquals(0xFF2764BE.toInt(), state.pen.color)
        assertEquals(3.5f, state.pen.strokeWidth, .001f)

        val reopened = ToolState(context)
        assertEquals(state.recordLayerMask, reopened.recordLayerMask)
        assertEquals(state.visibleLayerMask, reopened.visibleLayerMask)
        assertEquals(state.layerOpacities, reopened.layerOpacities)
        assertEquals(Tool.DRAW, reopened.pen.tool)
        assertEquals(BrushType.GEL, reopened.pen.brush)
        assertEquals(state.pen.color, reopened.pen.color)
        assertEquals(state.pen.strokeWidth, reopened.pen.strokeWidth, .001f)
    }

    @Test fun `layer pen leaves highlighter and restores the previous writing pen`() {
        val state = ToolState(context)
        state.pen.brush = BrushType.GEL
        state.pen.color = 0xFF2764BE.toInt()
        state.pen.strokeWidth = 3.5f
        state.pen.opacity = .7f
        state.pen.toggleHighlighter()
        state.pen.color = 0xFFF4CA16.toInt()
        state.pen.strokeWidth = 15f
        state.setLayerOpacity(AnnotationLayers.THREE, .4f)

        state.selectDrawLayer(AnnotationLayers.THREE)

        assertEquals(Tool.DRAW, state.pen.tool)
        assertEquals(BrushType.GEL, state.pen.brush)
        assertEquals(0xFF2764BE.toInt(), state.pen.color)
        assertEquals(3.5f, state.pen.strokeWidth, .001f)
        assertEquals(.7f, state.pen.opacity, .001f)
        assertEquals(.4f, state.layerOpacities[2], .001f)
        assertEquals(AnnotationLayers.THREE, state.recordLayerMask)
        assertEquals(AnnotationLayers.ONE or AnnotationLayers.THREE, state.visibleLayerMask)
        // The separate highlighter profile survives the switch back to writing.
        state.pen.toggleHighlighter()
        assertEquals(0xFFF4CA16.toInt(), state.pen.color)
        assertEquals(15f, state.pen.strokeWidth, .001f)
    }

    @Test fun `uncalibrated scribble speed follows design updates without saving a personal override`() {
        val state = ToolState(context)
        assertEquals(240f, state.scribbleMinimumSpeedDpPerSecond, .001f)
        state.persistNow()
        assertFalse(preferences.contains("scribble_minimum_speed_dp_per_second"))

        DesignConfig.install(DesignConfig(scribble = DesignConfig.Scribble(minimumSpeedDpPerSecond = 480f)))
        assertEquals(480f, state.scribbleMinimumSpeedDpPerSecond, .001f)
        assertEquals(480f, ToolState(context).scribbleMinimumSpeedDpPerSecond, .001f)
        state.selectDrawLayer(AnnotationLayers.TWO)
        assertFalse(preferences.contains("scribble_minimum_speed_dp_per_second"))
    }

    @Test fun `calibrated scribble speed survives reopening and later design updates`() {
        val state = ToolState(context)
        state.updateScribbleMinimumSpeed(420f)
        assertEquals(420f, preferences.getFloat("scribble_minimum_speed_dp_per_second", -1f), .001f)
        DesignConfig.install(DesignConfig(scribble = DesignConfig.Scribble(minimumSpeedDpPerSecond = 700f)))
        assertEquals(420f, state.scribbleMinimumSpeedDpPerSecond, .001f)
        assertEquals(420f, ToolState(context).scribbleMinimumSpeedDpPerSecond, .001f)
    }

    @Test fun `calibrated speed is bounded and nonfinite updates preserve previous calibration`() {
        val state = ToolState(context)
        state.updateScribbleMinimumSpeed(20f)
        assertEquals(80f, ToolState(context).scribbleMinimumSpeedDpPerSecond, .001f)
        state.updateScribbleMinimumSpeed(9000f)
        assertEquals(2000f, ToolState(context).scribbleMinimumSpeedDpPerSecond, .001f)
        state.updateScribbleMinimumSpeed(420f)
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            state.updateScribbleMinimumSpeed(invalid)
            assertEquals(420f, state.scribbleMinimumSpeedDpPerSecond, .001f)
            assertEquals(420f, ToolState(context).scribbleMinimumSpeedDpPerSecond, .001f)
        }
    }

    @Test fun `legacy wheel gains layer draw once and respects a later explicit disable`() {
        val legacy = listOf(QuickWheelAction.PEN, QuickWheelAction.COLOR, QuickWheelAction.UNDO)
        preferences.edit().putString("quick_action_wheel_actions", legacy.joinToString(",") { it.name }).commit()
        val migrated = ToolState(context)
        assertTrue(migrated.quickWheelActions.containsAll(legacy))
        assertEquals(1, migrated.quickWheelActions.count { it == QuickWheelAction.LAYER_DRAW })
        assertEquals(migrated.quickWheelActions, ToolState(context).quickWheelActions)

        migrated.setQuickWheelAction(QuickWheelAction.LAYER_DRAW, false)
        assertFalse(QuickWheelAction.LAYER_DRAW in ToolState(context).quickWheelActions)
    }
}
