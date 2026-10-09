package com.inksheets.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Controllers: anything with switches, pedals and faders that tells the app about them - a MIDI
 * foot controller, a guitar multi-effects unit over USB (a Line 6 POD Go), a keyboard. Whatever the
 * device, what it does comes here as a [ControlEvent], and a [ControlBinding] says what the app
 * does then: any of the remote's actions ([RemoteButton]), so every control the app has can be put
 * under a foot.
 */
data class ControlEvent(
    /** The device's name, as the system gives it ("POD Go"). */
    val device: String,
    /** [NOTE], [CC] (a switch's or a fader's control change), [PROGRAM] (a program change), [KEY]. */
    val kind: String,
    /** MIDI channel 1-16 (0 where there is none). */
    val channel: Int,
    /** The note, controller or program number, or the key's code. */
    val number: Int,
    /** 0-127: how hard, how far, on or off (a program change: its number again). */
    val value: Int
) {
    /** Which control it was, whatever it was set to: what a binding is to. */
    val control: ControlRef get() = ControlRef(device, kind, channel, number)

    /** Pressed, as a switch is: a note struck, a control at its upper half, a program chosen, a key down. */
    val pressed: Boolean get() = when (kind) {
        NOTE -> value > 0
        CC -> value >= 64
        else -> true
    }

    /** In a few words, as a player reads it: "POD Go · CC 71 = 127 (ch 1)". */
    fun describe(): String = "$device · " + control.short() + when (kind) {
        CC -> " = $value"
        NOTE -> if (value > 0) " on" else " off"
        else -> ""
    }

    companion object {
        const val NOTE = "note"
        const val CC = "cc"
        const val PROGRAM = "program"
        const val KEY = "key"

        /**
         * A MIDI message from [device] as an event: a note on or off, a control change, a program
         * change; null for anything else (clock, sysex, pitch bend).
         */
        fun fromMidi(device: String, status: Int, data1: Int, data2: Int): ControlEvent? {
            val type = status and 0xF0
            val channel = (status and 0x0F) + 1
            return when (type) {
                0x90 -> ControlEvent(device, NOTE, channel, data1 and 0x7F, data2 and 0x7F)
                0x80 -> ControlEvent(device, NOTE, channel, data1 and 0x7F, 0)
                0xB0 -> ControlEvent(device, CC, channel, data1 and 0x7F, data2 and 0x7F)
                0xC0 -> ControlEvent(device, PROGRAM, channel, data1 and 0x7F, data1 and 0x7F)
                else -> null
            }
        }

        /**
         * The MIDI messages in [bytes] (from [offset], [count] of them) as events - with running
         * status (a status byte left out repeats the last) - for systems that hand over raw bytes.
         */
        fun fromMidiBytes(device: String, bytes: ByteArray, offset: Int, count: Int, running: IntArray = IntArray(1)): List<ControlEvent> {
            val out = ArrayList<ControlEvent>()
            var i = offset
            val end = offset + count
            while (i < end) {
                var b = bytes[i].toInt() and 0xFF
                if (b >= 0xF8) { i++; continue }               // real-time: clock, start, stop
                if (b == 0xF0) { while (i < end && (bytes[i].toInt() and 0xFF) != 0xF7) i++; i++; continue }   // sysex
                val status: Int
                // (Only a channel message is repeated by running status; a system byte ends it - or a stray data byte after one never moved on.)
                if (b >= 0x80) { status = b; running[0] = if (b < 0xF0) b else 0; i++ } else status = running[0]
                if (status == 0) { i++; continue }
                val type = status and 0xF0
                val length = if (type == 0xC0 || type == 0xD0) 1 else if (status >= 0xF0) 0 else 2
                if (i + length > end) break
                val d1 = if (length >= 1) bytes[i].toInt() and 0x7F else 0
                val d2 = if (length >= 2) bytes[i + 1].toInt() and 0x7F else 0
                i += length
                fromMidi(device, status, d1, d2)?.let { out += it }
            }
            return out
        }
    }
}

/** A control: which device ("" for any), what kind, its channel and number. */
@Serializable
data class ControlRef(val device: String, val kind: String, val channel: Int, val number: Int) {
    fun matches(e: ControlEvent) = (device.isEmpty() || sameDevice(device, e.device)) && kind == e.kind && channel == e.channel && number == e.number

    fun short(): String = when (kind) {
        ControlEvent.NOTE -> "note $number"
        ControlEvent.CC -> "CC $number"
        ControlEvent.PROGRAM -> "program $number"
        else -> "key $number"
    } + if (channel > 0) " (ch $channel)" else ""
}

/**
 * Whether two device names are one device: Windows puts the USB port in front of the name
 * ("2- T4 Pedal") and Linux a hardware address after it, so the same pedal in another port, or
 * plugged in again, is still the pedal a control was given to.
 */
fun sameDevice(a: String, b: String): Boolean = a == b || deviceKey(a) == deviceKey(b)

private fun deviceKey(name: String): String =
    name.trim().replace(Regex("""^\d+\s*-\s*"""), "").replace(Regex("""\s*\[[^\]]*]$"""), "").lowercase()

/**
 * What a control does: [action], one of the remote's. [continuous]: a fader or pedal sweeping a
 * value - its 0-127 across the action's range ([Controllers.range]) - rather than a switch.
 * [everyMessage]: a switch that sends one message a press (a POD Go footswitch set to toggle sends
 * 127, then 0 the next press): each message is a press; otherwise a press is its upper half only -
 * or, [whenOff], its lower half only: a toggle (the POD Go's toe switch) doing it one way of two.
 */
@Serializable
data class ControlBinding(
    val control: ControlRef,
    val action: RemoteButton,
    val continuous: Boolean = false,
    val everyMessage: Boolean = false,
    val whenOff: Boolean = false
)

/**
 * The control the player put on a spot of a controller's picture (a POD Go's footswitch, its
 * pedal): found by pressing it, never guessed. [momentary]: it sent on and, let go, off moments
 * later - so a press is its upper half only; otherwise (a POD Go block switch sends on at one
 * press, off at the next) each message is a press.
 */
@Serializable
data class SpotControl(val control: ControlRef, val momentary: Boolean = false)

/**
 * Listening for the control to put on a spot: everything heard since, in the order it came - the
 * first is most likely what was pressed (a POD Go says more after: the preset loaded, its tempo).
 * [chatter]: controls already talking before the listening began, as a unit's own goings-on - put
 * last, whenever they came.
 */
class ControlListener(private val chatter: Set<ControlRef> = emptySet()) {
    private val heard = LinkedHashMap<ControlRef, MutableList<Pair<Long, Int>>>()

    fun hear(e: ControlEvent, at: Long) { heard.getOrPut(e.control) { ArrayList() } += at to e.value }

    /** What was heard, most likely first. */
    val candidates: List<ControlRef> get() = heard.keys.sortedBy { it in chatter }

    fun count(c: ControlRef): Int = heard[c]?.size ?: 0

    /** Sent on, then off within a second: a momentary switch, pressed and let go. */
    fun momentary(c: ControlRef): Boolean {
        val h = heard[c] ?: return false
        return (1 until h.size).any { i -> h[i - 1].second >= 64 && h[i].second < 64 && h[i].first - h[i - 1].first < 1000 }
    }
}

object Controllers {
    /** The actions a fader or pedal can sweep, and over what values. */
    fun range(kind: String): ClosedFloatingPointRange<Double>? = when (kind) {
        RemoteButton.TEMPO_SET -> 40.0..240.0
        RemoteButton.AUDIO_VOLUME_SET -> 0.0..100.0
        RemoteButton.AUDIO_SPEED_SET -> 50.0..125.0
        else -> null
    }

    /** A pedal's sweep: an action with a range, and no value of its own ("Set the tempo to 120" is a switch's). */
    fun isSweep(b: RemoteButton) = range(b.kind) != null && b.value == null

    /** The value [b]'s control at [value] (0-127) stands for: across its action's range, in whole steps. */
    fun valueOf(b: ControlBinding, value: Int): Double? {
        val r = range(b.action.kind) ?: return null
        return Math.round(r.start + (r.endInclusive - r.start) * value.coerceIn(0, 127) / 127.0).toDouble()
    }

    /**
     * What [e] does under [bindings]: each binding it is for, and whether it fires - a fader every
     * time it moves; a switch when pressed (or at every message, for one that sends one a press).
     */
    fun firing(bindings: List<ControlBinding>, e: ControlEvent, on: Boolean = e.pressed): List<ControlBinding> =
        bindings.filter { b -> b.control.matches(e) && (b.continuous || b.everyMessage || on != b.whenOff) }

    /**
     * Whether a switch is on after [e], [before] being how it was: as it says, where it says on
     * (127) or off (0) - a POD Go footswitch lit or dark, a momentary switch down or up, a note -
     * and otherwise turned over at each message (a POD Go's MODE saying 1 or 0, TAP its tempo, a
     * preset chosen): so any switch can do a thing one way of two, pressed twice, done once.
     */
    fun on(e: ControlEvent, before: Boolean?): Boolean = when {
        e.kind == ControlEvent.NOTE || e.kind == ControlEvent.KEY -> e.pressed
        e.kind == ControlEvent.CC && (e.value == 0 || e.value == 127) -> e.value == 127
        else -> before != true
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val list = ListSerializer(ControlBinding.serializer())

    fun encode(bindings: List<ControlBinding>): String = json.encodeToString(list, bindings)
    fun decode(text: String?): List<ControlBinding> = text?.let { runCatching { json.decodeFromString(list, it) }.getOrNull() } ?: emptyList()

    /**
     * [action] for the control on a spot: a pedal or fader sweeping it if it is a sweep; a switch
     * pressed otherwise - at every message unless it was seen to send on and off at a press.
     */
    fun bindingFor(spot: SpotControl, action: RemoteButton): ControlBinding {
        val sweep = isSweep(action) && spot.control.kind == ControlEvent.CC
        return ControlBinding(spot.control, action, continuous = sweep, everyMessage = !sweep && everyPress(spot.control.kind, spot.momentary))
    }

    /**
     * Each message a press, as a switch is first set: a control change but a momentary one's (down
     * and up are one press), a preset chosen; a note is pressed when struck.
     */
    fun everyPress(kind: String, momentary: Boolean) = (kind == ControlEvent.CC && !momentary) || kind == ControlEvent.PROGRAM

    private val spotMap = MapSerializer(String.serializer(), SpotControl.serializer())

    fun encodeSpots(spots: Map<String, SpotControl>): String = json.encodeToString(spotMap, spots)
    fun decodeSpots(text: String?): Map<String, SpotControl> = text?.let { runCatching { json.decodeFromString(spotMap, it) }.getOrNull() } ?: emptyMap()
}

/**
 * A system's controllers: started with what to call on each event, and on the devices there (a
 * device plugged in or taken out says so again). Each platform has its own - MIDI on the desktop
 * and on Android - and one that has none has no input.
 */
interface ControllerInput {
    fun start(onEvent: (ControlEvent) -> Unit, onDevices: (List<String>) -> Unit)
    fun stop()
}

/** Several systems' controllers as one: MIDI, and a POD Go over USB - each device list merged. */
class AllControllers(private val inputs: List<ControllerInput>) : ControllerInput {
    private val devices = HashMap<Int, List<String>>()
    override fun start(onEvent: (ControlEvent) -> Unit, onDevices: (List<String>) -> Unit) {
        for ((i, input) in inputs.withIndex()) input.start(onEvent) { list ->
            synchronized(devices) { devices[i] = list; onDevices(devices.toSortedMap().values.flatten()) }
        }
    }
    override fun stop() = inputs.forEach { it.stop() }
}
