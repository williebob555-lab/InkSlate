package com.inkslate

import com.inkslate.ui.editor.QuickWheelAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickWheelActionTest {
    @Test fun `compact wheel defaults contain the requested seven actions`() {
        assertEquals(QuickWheelAction.MAX_ACTIONS, QuickWheelAction.DEFAULTS.size)
        assertTrue(QuickWheelAction.SELECT_ONCE in QuickWheelAction.DEFAULTS)
        assertTrue(QuickWheelAction.UNDO in QuickWheelAction.DEFAULTS)
        assertFalse(QuickWheelAction.REDO in QuickWheelAction.DEFAULTS)
    }

    @Test fun `every configurable action has a unique readable label`() {
        assertEquals(QuickWheelAction.entries.size, QuickWheelAction.entries.map { it.label }.toSet().size)
        assertTrue(QuickWheelAction.entries.all { it.label.isNotBlank() })
    }
}
