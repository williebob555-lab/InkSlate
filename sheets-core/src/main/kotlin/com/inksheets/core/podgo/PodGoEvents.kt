package com.inksheets.core.podgo

import com.inksheets.core.ControlEvent

/**
 * What the POD Go tells a listening editor, as controller events - so a footswitch or the
 * expression pedal can be bound to any of the app's actions like a MIDI controller's.
 *
 * Worked out from a USB capture of POD Go Edit with the user pressing each control (2026-10-03).
 * Every notification is {105: event, 106: {82, 68, 121: routing, 106: what happened}}:
 *  - event 8, {107: setlist, 108: preset}: a footswitch chose a preset (preset mode) - as a MIDI
 *    program change, PROGRAM ch 1 <preset>; the unit sends the same over its USB MIDI port too.
 *  - event 49, {98: block, 59: on}: a block turned on or off (stomp mode, the pedal's toe switch) -
 *    CC ch 1 <block>, 127 on, 0 off.
 *  - event 30, {98: block, 28: setting, 119: 0-1}: the expression pedal moving a block's setting -
 *    CC ch 2 <block * 8 + setting>, 0-127 across the pedal's travel.
 *  - event 22, {118: setting, 119: value}: a unit-wide setting - the volume knob (159, 0-1) as
 *    CC ch 3 #7, and which pedal the toe switch has active (124: 1 or 2) as CC ch 3 #124, 0 or 127.
 *    The rest (tempo, preset number, after every preset change) are no player's act, and left out.
 * Everything else (a preset loaded, its pedal assignments, the block selected) follows from those
 * and is left out, so nothing fires twice.
 */
object PodGoEvents {
    const val DEVICE = "POD Go (USB)"

    /**
     * Where what the unit says, and how the link went, is written down - set by the app to a file in
     * the music library, which syncs to the other devices (for working out which message is which
     * footswitch). Called off the UI thread.
     */
    @Volatile var record: ((String) -> Unit)? = null

    fun toControl(m: PodGoLink.Message): ControlEvent? {
        val event = m.event ?: return null
        val what = ((m.args as? Map<*, *>)?.get(106L) as? Map<*, *>) ?: return null
        fun int(key: Long) = (what[key] as? Long)?.toInt()
        return when (event) {
            8 -> int(108)?.let { ControlEvent(DEVICE, ControlEvent.PROGRAM, 1, it and 0x7F, it and 0x7F) }
            49 -> int(98)?.let { block -> ControlEvent(DEVICE, ControlEvent.CC, 1, block and 0x7F, if (what[59L] == true) 127 else 0) }
            30 -> int(98)?.let { block -> ControlEvent(DEVICE, ControlEvent.CC, 2, (block * 8 + (int(28) ?: 0)) and 0x7F, value(what[119L])) }
            22 -> when (int(118)) {
                159 -> ControlEvent(DEVICE, ControlEvent.CC, 3, 7, value(what[119L]))
                124 -> ControlEvent(DEVICE, ControlEvent.CC, 3, 124, if (value(what[119L]) >= 2) 127 else 0)
                else -> null
            }
            else -> null
        }
    }

    /** A setting's value as 0-127: on/off as 127/0; 0-1 across the range; a whole number as itself, held to 0-127. */
    fun value(v: Any?): Int = when (v) {
        is Boolean -> if (v) 127 else 0
        is Double -> if (v in 0.0..1.0) Math.round(v * 127).toInt() else Math.round(v).toInt().coerceIn(0, 127)
        is Long -> v.toInt().coerceIn(0, 127)
        else -> 127
    }
}
