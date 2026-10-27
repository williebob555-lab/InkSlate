package com.inksheets.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.inksheets.ui.FloatingPanel
import org.junit.Assert.*
import org.junit.Test

/** Exercise real pointer input: a second drag must start where the previous one ended. */
@OptIn(ExperimentalTestApi::class)
class FloatingPanelDragTest {
    @Test fun `repeated panel drags accumulate and reopen at their latest position`() {
        var showing by mutableStateOf(true)
        runDesktopComposeUiTest(width = 1000, height = 700) {
            setContent { MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    if (showing) FloatingPanel("Repeated drag regression", { showing = false }) { Text("Panel content") }
                }
            } }
            fun position() = onNodeWithText("Repeated drag regression").fetchSemanticsNode().boundsInRoot.topLeft
            fun drag(dx: Float, dy: Float) {
                onNodeWithText("Repeated drag regression").performTouchInput {
                    swipe(center, center + Offset(dx, dy), durationMillis = 600)
                }
                waitForIdle()
            }
            val start = position()
            drag(-120f, 30f)
            val first = position()
            assertTrue("First drag should move left", first.x < start.x - 70f)
            drag(-90f, 25f)
            val second = position()
            assertTrue("Second drag must continue from first, without snapping back", second.x < first.x - 50f)
            drag(65f, 25f)
            val third = position()
            assertTrue("Third drag moves right from the latest position", third.x > second.x + 30f)
            assertTrue("Downward drags accumulate too", third.y > start.y + 35f)
            runOnIdle { showing = false }
            runOnIdle { showing = true }
            assertEquals(third.x, position().x, 2f)
            assertEquals(third.y, position().y, 2f)
            onNodeWithContentDescription("Close").assertIsDisplayed()
        }
    }

    @Test fun `drag after viewport shrinks starts at the visible clamped position`() {
        var room by mutableStateOf(1000.dp)
        runDesktopComposeUiTest(width = 1000, height = 700) {
            setContent { MaterialTheme {
                Box(Modifier.width(room).fillMaxHeight()) {
                    FloatingPanel("Resize drag regression", {}) { Text("Panel content") }
                }
            } }
            runOnIdle { room = 560.dp }
            val before = onNodeWithText("Resize drag regression").fetchSemanticsNode().boundsInRoot.topLeft
            onNodeWithText("Resize drag regression").performTouchInput {
                swipe(center, center + Offset(-85f, 20f), durationMillis = 600)
            }
            val after = onNodeWithText("Resize drag regression").fetchSemanticsNode().boundsInRoot.topLeft
            assertTrue("Drag should move left from its visible position after resizing", after.x < before.x - 45f)
        }
    }
}
