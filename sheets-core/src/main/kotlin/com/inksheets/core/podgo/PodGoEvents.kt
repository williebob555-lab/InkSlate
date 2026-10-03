package com.inksheets.core.podgo

import com.inksheets.core.ControlEvent

/**
 * What the POD Go tells a listening editor, as controller events - so a footswitch or the
 * expression pedal can be bound to any of the app's actions like a MIDI controller's.
 *
 * Known from the HX family: a block's setting changed is event 39, {82: block, 68: setting,
 * 121: value}. Footswitches that turn blocks on and off, and the pedal moving its block's setting,
 * are expected to arrive this way - each block and setting its own control:
 * "POD Go (USB) · CC <setting> (ch <block + 1>)", the value 0-127 (on/off as 127/0, a 0-1 setting
 * across 0-127). Any other notification is a control of its own on channel 16 - numbered by its
 * event, its value 127 - so even one not worked out yet can be bound by pressing it.
 */
object PodGoEvents {
    const val DEVICE = "POD Go (USB)"

    fun toControl(m: PodGoLink.Message): ControlEvent? {
        val event = m.event ?: return null
        // (A deferred request's completion is about our own asking - never a front-panel act.)
        if (event == 1 || event == 20) return null
        val args = m.args as? Map<*, *>
        if (event == 39 && args != null) {
            val block = (args[82L] as? Long)?.toInt() ?: return null
            val setting = (args[68L] as? Long)?.toInt() ?: return null
            return ControlEvent(DEVICE, ControlEvent.CC, (block and 0x0F) + 1, setting and 0x7F, value(args[121L]))
        }
        return ControlEvent(DEVICE, ControlEvent.CC, 16, event and 0x7F, 127)
    }

    /** A setting's value as 0-127: on/off as 127/0; 0-1 across the range; a whole number as itself, held to 0-127. */
    fun value(v: Any?): Int = when (v) {
        is Boolean -> if (v) 127 else 0
        is Double -> if (v in 0.0..1.0) Math.round(v * 127).toInt() else Math.round(v).toInt().coerceIn(0, 127)
        is Long -> v.toInt().coerceIn(0, 127)
        else -> 127
    }
}
