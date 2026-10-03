package com.inksheets.core.podgo

import com.inksheets.core.ControlEvent

/**
 * What the POD Go tells a listening editor, as controller events - so a footswitch or the
 * expression pedal can be bound to any of the app's actions like a MIDI controller's.
 *
 * Which footswitch or pedal each one is, the player says on the POD Go's picture (Settings ->
 * Controllers): pressing it there puts what came on its spot. Here the unit's messages are only
 * told apart, each a control of its own - none is taken for a given switch.
 *
 * Read from a USB capture of POD Go Edit (2026-10-03). Every notification is
 * {105: event, 106: {82, 68, 121: routing, 106: what happened}}:
 *  - event 8, {107: setlist, 108: preset}: a preset chosen - PROGRAM ch 1 <preset>, as the unit
 *    sends over its USB MIDI port too.
 *  - event 49, {98: block, 59: on}: a block turned on or off - CC ch 1 <block>, 127 on, 0 off.
 *  - event 30, {98: block, 28: setting, 119: 0-1}: a block's setting moved (the pedal does this) -
 *    CC ch 2 <block * 8 + setting>, 0-127 across.
 *  - event 22, {118: setting, 119: value}: a unit-wide setting - 159 (0-1) as CC ch 3 #7, 124 (1 or
 *    2) as CC ch 3 #124, 0 or 127; any other as CC ch 4 <setting>.
 *  - any other event: CC ch 16 <event>, 127 - so one not worked out can still be put on a spot.
 * What follows a press (a preset loaded, its tempo) comes through as well: what fires is only what
 * the player put on a spot and gave an action, so it fires once.
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
        // (A deferred request's completion is about our own asking - never a front-panel act.)
        if (event == 1 || event == 20) return null
        val what = ((m.args as? Map<*, *>)?.get(106L) as? Map<*, *>).orEmpty()
        fun int(key: Long) = (what[key] as? Long)?.toInt()
        val known = when (event) {
            8 -> int(108)?.let { ControlEvent(DEVICE, ControlEvent.PROGRAM, 1, it and 0x7F, it and 0x7F) }
            49 -> int(98)?.let { block -> ControlEvent(DEVICE, ControlEvent.CC, 1, block and 0x7F, if (what[59L] == true) 127 else 0) }
            30 -> int(98)?.let { block -> ControlEvent(DEVICE, ControlEvent.CC, 2, (block * 8 + (int(28) ?: 0)) and 0x7F, value(what[119L])) }
            22 -> when (val setting = int(118)) {
                null -> null
                159 -> ControlEvent(DEVICE, ControlEvent.CC, 3, 7, value(what[119L]))
                124 -> ControlEvent(DEVICE, ControlEvent.CC, 3, 124, if (value(what[119L]) >= 2) 127 else 0)
                else -> ControlEvent(DEVICE, ControlEvent.CC, 4, setting and 0x7F, value(what[119L]))
            }
            else -> null
        }
        return known ?: ControlEvent(DEVICE, ControlEvent.CC, 16, event and 0x7F, 127)
    }

    /** A setting's value as 0-127: on/off as 127/0; 0-1 across the range; a whole number as itself, held to 0-127. */
    fun value(v: Any?): Int = when (v) {
        is Boolean -> if (v) 127 else 0
        is Double -> if (v in 0.0..1.0) Math.round(v * 127).toInt() else Math.round(v).toInt().coerceIn(0, 127)
        is Long -> v.toInt().coerceIn(0, 127)
        else -> 127
    }
}
