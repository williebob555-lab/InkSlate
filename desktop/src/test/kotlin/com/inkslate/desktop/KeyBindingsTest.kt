package com.inkslate.desktop

import androidx.compose.ui.input.key.Key
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * What each key does, on the same terms as what each button does.
 *
 * The property that matters is the one the pointer table has: what it does out of the box is
 * exactly what the chain of conditions in the window's key handler did, because anything else is a
 * change nobody asked for.
 */
class KeyBindingsTest {

    @Before
    fun clean() = KeyBindingStore.reset()

    @After
    fun tidy() = KeyBindingStore.reset()

    private fun ctrl(key: Key) = KeyStroke(key.keyCode, ctrl = true)

    @Test
    fun `the shortcuts that were written into the handler still work`() {
        assertEquals(KeyAction.SAVE, KeyBindingStore.actionFor(ctrl(Key.S)))
        assertEquals(KeyAction.UNDO, KeyBindingStore.actionFor(ctrl(Key.Z)))
        assertEquals(KeyAction.REDO, KeyBindingStore.actionFor(ctrl(Key.Y)))
        assertEquals(
            KeyAction.REDO,
            KeyBindingStore.actionFor(KeyStroke(Key.Z.keyCode, ctrl = true, shift = true))
        )
        assertEquals(KeyAction.SELECT_ALL, KeyBindingStore.actionFor(ctrl(Key.A)))
        assertEquals(KeyAction.BACK, KeyBindingStore.actionFor(KeyStroke(Key.Escape.keyCode)))
        assertEquals(KeyAction.DELETE, KeyBindingStore.actionFor(KeyStroke(Key.Delete.keyCode)))
    }

    @Test
    fun `a key that means nothing is left alone`() {
        // Typing into a text field must not be eaten by a shortcut that does not exist.
        assertNull(KeyBindingStore.actionFor(KeyStroke(Key.Q.keyCode)))
        assertNull(KeyBindingStore.actionFor(KeyStroke(Key.S.keyCode)))
    }

    @Test
    fun `a key can be moved, and the old one stops doing it`() {
        KeyBindingStore.bind(KeyAction.SAVE, ctrl(Key.E))

        assertEquals(KeyAction.SAVE, KeyBindingStore.actionFor(ctrl(Key.E)))
        assertNull("the old key should have stopped saving", KeyBindingStore.actionFor(ctrl(Key.S)))
    }

    /** One key does one thing, or there is no way to tell what pressing it will do. */
    @Test
    fun `taking a key from another action gives it up`() {
        KeyBindingStore.bind(KeyAction.ZOOM_IN, ctrl(Key.S))

        assertEquals(KeyAction.ZOOM_IN, KeyBindingStore.actionFor(ctrl(Key.S)))
        assertNotEquals(ctrl(Key.S), KeyBindingStore.strokeFor(KeyAction.SAVE))
    }

    @Test
    fun `what was chosen comes back`() {
        KeyBindingStore.bind(KeyAction.SAVE, KeyStroke(Key.E.keyCode, ctrl = true, shift = true))

        val written = KeyBindingStore.strokeFor(KeyAction.SAVE)!!
        val read = KeyStroke.fromText(written.toText())

        assertEquals(written, read)
    }

    @Test
    fun `putting them back restores what came with the program`() {
        KeyBindingStore.bind(KeyAction.SAVE, ctrl(Key.E))
        KeyBindingStore.reset()

        assertEquals(KeyAction.SAVE, KeyBindingStore.actionFor(ctrl(Key.S)))
    }

    @Test
    fun `a shortcut is written down in the words the keyboard uses`() {
        assertEquals("Ctrl+S", ctrl(Key.S).label())
        assertEquals("Ctrl+Shift+Z", KeyStroke(Key.Z.keyCode, ctrl = true, shift = true).label())
    }
}
