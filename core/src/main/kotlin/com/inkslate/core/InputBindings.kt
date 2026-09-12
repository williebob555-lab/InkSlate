package com.inkslate.core

/**
 * What each thing that can touch the screen does.
 *
 * This replaces a chain of special cases. Every device that turned up - a pen, its barrel button,
 * its second button, a finger, the left mouse button, the right one, the middle one, a thumb
 * button, a trackpad - was another branch written against the one before it, and each new branch
 * broke a neighbour: allowing the right button to draw quietly enrolled the thumb buttons, and
 * teaching the pen to pick its own profile threw away the first stroke after picking it up.
 *
 * There is no version of that chain that is finished, because the combinations do not stop. So
 * what is written down instead is a table: what pressed it, which button, and what that does. An
 * arrangement nobody anticipated is a row in the table rather than a branch in the code, and the
 * person holding the pen can write the row.
 *
 * Lives in core so that both builds read the same table from the same document, and a pen button
 * set up on one behaves the same on the other.
 */
enum class InputDevice(val label: String) {
    PEN("Pen"),
    FINGER("Finger"),
    MOUSE("Mouse"),
    TRACKPAD("Trackpad"),

    /** Something the platform would not name. Treated as a mouse, which is what it usually is. */
    UNKNOWN("Other");

    companion object {
        fun named(name: String?): InputDevice =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: UNKNOWN
    }
}

enum class InputButton(val label: String) {
    /** No button at all: a pen tip on the glass, or a finger. */
    NONE("Tip"),
    PRIMARY("Left"),
    SECONDARY("Right"),
    MIDDLE("Middle"),
    BACK("Back"),
    FORWARD("Forward"),
    PEN_ONE("Barrel"),
    PEN_TWO("Second button");

    companion object {
        fun named(name: String?): InputButton =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NONE
    }
}

/**
 * What an input does.
 *
 * The drawing entries name a pen - a saved colour, width and tool - rather than a tool, so that
 * "the right mouse button draws with the highlighter" is a thing somebody sets up once by editing
 * that pen, exactly as they would for the stylus.
 */
enum class InputAction(val label: String, val mode: InputMode? = null) {
    DRAW_PEN("Draw with the pen", InputMode.PEN),
    DRAW_PEN_ONE("Draw with the barrel pen", InputMode.BUTTON_1),
    DRAW_PEN_TWO("Draw with the second pen", InputMode.BUTTON_2),
    DRAW_FINGER("Draw with the finger pen", InputMode.TOUCH),
    DRAW_MOUSE("Draw with the left-mouse pen", InputMode.MOUSE),
    DRAW_MOUSE_RIGHT("Draw with the right-mouse pen", InputMode.MOUSE_RIGHT),
    PAN("Move the page"),
    ERASE("Erase"),
    UNDO("Undo"),
    REDO("Redo"),
    NOTHING("Nothing");

    val draws: Boolean get() = mode != null

    companion object {
        fun named(name: String?): InputAction? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/**
 * The table itself.
 *
 * Only what has been changed is stored. Anything absent falls to [DEFAULTS], so a table written by
 * an older build gains new devices as they are understood rather than leaving them dead, and a row
 * somebody set up survives everything else moving around it.
 */
data class InputBindings(private val changed: Map<String, InputAction> = emptyMap()) {

    fun actionFor(device: InputDevice, button: InputButton): InputAction =
        changed[keyOf(device, button)]
            ?: DEFAULTS[keyOf(device, button)]
            ?: fallbackFor(device, button)

    fun with(device: InputDevice, button: InputButton, action: InputAction): InputBindings =
        copy(changed = changed + (keyOf(device, button) to action))

    /** Put one row back to what it was born as. */
    fun without(device: InputDevice, button: InputButton): InputBindings =
        copy(changed = changed - keyOf(device, button))

    fun isChanged(device: InputDevice, button: InputButton): Boolean =
        keyOf(device, button) in changed

    fun toText(): String = changed.entries
        .sortedBy { it.key }
        .joinToString(";") { "${it.key}=${it.value.name}" }

    companion object {
        private fun keyOf(device: InputDevice, button: InputButton) = "${device.name}:${button.name}"

        /**
         * What every combination does before anybody changes anything.
         *
         * Exactly what the build did when these were branches rather than rows, so turning this
         * on changes nothing until somebody asks it to.
         */
        val DEFAULTS: Map<String, InputAction> = mapOf(
            keyOf(InputDevice.PEN, InputButton.NONE) to InputAction.DRAW_PEN,
            keyOf(InputDevice.PEN, InputButton.PEN_ONE) to InputAction.DRAW_PEN_ONE,
            keyOf(InputDevice.PEN, InputButton.PEN_TWO) to InputAction.DRAW_PEN_TWO,
            keyOf(InputDevice.PEN, InputButton.SECONDARY) to InputAction.DRAW_PEN_ONE,

            keyOf(InputDevice.FINGER, InputButton.NONE) to InputAction.DRAW_FINGER,

            keyOf(InputDevice.MOUSE, InputButton.PRIMARY) to InputAction.DRAW_MOUSE,
            keyOf(InputDevice.MOUSE, InputButton.SECONDARY) to InputAction.DRAW_MOUSE_RIGHT,
            keyOf(InputDevice.MOUSE, InputButton.MIDDLE) to InputAction.PAN,
            // Where a browser puts them, which is where a hand expects them.
            keyOf(InputDevice.MOUSE, InputButton.BACK) to InputAction.UNDO,
            keyOf(InputDevice.MOUSE, InputButton.FORWARD) to InputAction.REDO,

            keyOf(InputDevice.TRACKPAD, InputButton.PRIMARY) to InputAction.DRAW_MOUSE,
            keyOf(InputDevice.TRACKPAD, InputButton.SECONDARY) to InputAction.DRAW_MOUSE_RIGHT,
            keyOf(InputDevice.TRACKPAD, InputButton.MIDDLE) to InputAction.PAN,
            keyOf(InputDevice.TRACKPAD, InputButton.BACK) to InputAction.UNDO,
            keyOf(InputDevice.TRACKPAD, InputButton.FORWARD) to InputAction.REDO,

            keyOf(InputDevice.UNKNOWN, InputButton.NONE) to InputAction.DRAW_MOUSE,
            keyOf(InputDevice.UNKNOWN, InputButton.PRIMARY) to InputAction.DRAW_MOUSE,
            keyOf(InputDevice.UNKNOWN, InputButton.SECONDARY) to InputAction.DRAW_MOUSE_RIGHT
        )

        /**
         * What to do with a combination nobody has ever described.
         *
         * The point of the whole arrangement: something turns up that was not anticipated - a pen
         * with a third button, a tablet whose barrel reports as a thumb button - and it draws with
         * the pen belonging to whatever is holding it rather than doing nothing, or worse, doing
         * something surprising. It can then be bound to anything.
         */
        private fun fallbackFor(device: InputDevice, button: InputButton): InputAction =
            when (device) {
                InputDevice.PEN -> if (button == InputButton.NONE) {
                    InputAction.DRAW_PEN
                } else {
                    InputAction.DRAW_PEN_ONE
                }

                InputDevice.FINGER -> InputAction.DRAW_FINGER
                else -> if (button == InputButton.SECONDARY) {
                    InputAction.DRAW_MOUSE_RIGHT
                } else {
                    InputAction.DRAW_MOUSE
                }
            }

        fun fromText(text: String?): InputBindings {
            if (text.isNullOrBlank()) return InputBindings()
            val rows = text.split(';').mapNotNull { row ->
                val at = row.indexOf('=')
                if (at <= 0) return@mapNotNull null
                val key = row.substring(0, at)
                val action = InputAction.named(row.substring(at + 1)) ?: return@mapNotNull null
                // A key from a later build naming a device this one has never heard of is dropped
                // rather than kept: it cannot be shown, and a row nobody can see or edit is worse
                // than one that was never there.
                val parts = key.split(':')
                if (parts.size != 2) return@mapNotNull null
                if (InputDevice.entries.none { it.name == parts[0] }) return@mapNotNull null
                if (InputButton.entries.none { it.name == parts[1] }) return@mapNotNull null
                key to action
            }.toMap()
            return InputBindings(rows)
        }

        /** Every row worth showing, in the order it should be shown. */
        fun rows(): List<Pair<InputDevice, InputButton>> = listOf(
            InputDevice.PEN to InputButton.NONE,
            InputDevice.PEN to InputButton.PEN_ONE,
            InputDevice.PEN to InputButton.PEN_TWO,
            InputDevice.FINGER to InputButton.NONE,
            InputDevice.MOUSE to InputButton.PRIMARY,
            InputDevice.MOUSE to InputButton.SECONDARY,
            InputDevice.MOUSE to InputButton.MIDDLE,
            InputDevice.MOUSE to InputButton.BACK,
            InputDevice.MOUSE to InputButton.FORWARD
        )
    }
}
