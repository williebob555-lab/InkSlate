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
 * Read from USB captures and the unit's own log (2026-10-03). Every notification is
 * {105: event, 106: {82, 68, 121: routing, 106: what happened}} - or, for a footswitch, {105: 41,
 * 106: {70: switch, 63: lit, 66: colour}}. A press says several things; only what tells the press
 * apart comes through, one control a press:
 *  - event 41, {70: footswitch, 63: lit}: a footswitch pressed (the toe switch too) - CC ch 5
 *    <footswitch>, 127 lit, 0 not. The unit sends it only for a switch given something to do on it.
 *  - event 8, {107: setlist, 108: preset}: a preset chosen - PROGRAM ch 1 <preset>.
 *  - event 30, {98: block, 28: setting, 119: value}: a block's setting moved (the pedal does this) -
 *    CC ch 2 <block * 8 + setting>; 0-1 across 0-127, a whole number as itself.
 *  - event 22, {118: setting, 119: value}: a unit-wide setting - 159 (the volume knob, 0-1) as
 *    CC ch 3 #7; any other as CC ch 4 <setting>.
 *  - any other event: CC ch 16 <event>, 127 - so one not worked out can still be put on a spot.
 * Left out, as what follows a press: a block turned on or off (49), the block selected (39), the
 * pedal switched over (40, and setting 124), the preset loaded (4), its pedal assignments (34).
 */
object PodGoEvents {
    const val DEVICE = "POD Go (USB)"

    /** What follows a press, said by the unit after what tells the press apart: left out. */
    private val FOLLOWERS = setOf(4, 34, 39, 40, 49)

    /**
     * A control as an earlier version read the unit, no longer sent: a block turned on or off (CC
     * ch 1), the pedal switched over (CC ch 3 #124), what follows a press (CC ch 16). A spot holding
     * one never lights again - it is to be learned anew.
     */
    fun retired(c: com.inksheets.core.ControlRef) = c.device == DEVICE && c.kind == ControlEvent.CC &&
        (c.channel == 1 || (c.channel == 3 && c.number == 124) || (c.channel == 16 && c.number in FOLLOWERS))

    /** A MIDI port of the same unit: what it says comes over USB already. */
    fun sameUnit(midiDevice: String) = midiDevice != DEVICE && midiDevice.contains("POD Go", ignoreCase = true)

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
        val args = m.args as? Map<*, *>
        val what = (if (event == 41) args else args?.get(106L) as? Map<*, *>).orEmpty()
        fun int(key: Long) = (what[key] as? Long)?.toInt()
        if (event in FOLLOWERS) return null
        val known = when (event) {
            41 -> int(70)?.let { fs -> ControlEvent(DEVICE, ControlEvent.CC, 5, fs and 0x7F, if (what[63L] == true) 127 else 0) }
            8 -> int(108)?.let { ControlEvent(DEVICE, ControlEvent.PROGRAM, 1, it and 0x7F, it and 0x7F) }
            30 -> int(98)?.let { block -> ControlEvent(DEVICE, ControlEvent.CC, 2, (block * 8 + (int(28) ?: 0)) and 0x7F, value(what[119L])) }
            22 -> when (val setting = int(118)) {
                null, 124 -> return null
                159 -> ControlEvent(DEVICE, ControlEvent.CC, 3, 7, value(what[119L]))
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
