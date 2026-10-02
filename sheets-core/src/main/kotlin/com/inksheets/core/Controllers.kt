package com.inksheets.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
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
                if (b >= 0x80) { status = b; running[0] = b; i++ } else status = running[0]
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
    fun matches(e: ControlEvent) = (device.isEmpty() || device == e.device) && kind == e.kind && channel == e.channel && number == e.number

    fun short(): String = when (kind) {
        ControlEvent.NOTE -> "note $number"
        ControlEvent.CC -> "CC $number"
        ControlEvent.PROGRAM -> "program $number"
        else -> "key $number"
    } + if (channel > 0) " (ch $channel)" else ""
}

/**
 * What a control does: [action], one of the remote's. [continuous]: a fader or pedal sweeping a
 * value - its 0-127 across the action's range ([Controllers.range]) - rather than a switch.
 * [everyMessage]: a switch that sends one message a press (a POD Go footswitch set to toggle sends
 * 127, then 0 the next press): each message is a press; otherwise a press is its upper half only.
 */
@Serializable
data class ControlBinding(
    val control: ControlRef,
    val action: RemoteButton,
    val continuous: Boolean = false,
    val everyMessage: Boolean = false
)

object Controllers {
    /** The actions a fader or pedal can sweep, and over what values. */
    fun range(kind: String): ClosedFloatingPointRange<Double>? = when (kind) {
        RemoteButton.TEMPO_SET -> 40.0..240.0
        RemoteButton.AUDIO_VOLUME_SET -> 0.0..100.0
        RemoteButton.AUDIO_SPEED_SET -> 50.0..125.0
        else -> null
    }

    fun isSweep(b: RemoteButton) = range(b.kind) != null

    /** The value [b]'s control at [value] (0-127) stands for: across its action's range, in whole steps. */
    fun valueOf(b: ControlBinding, value: Int): Double? {
        val r = range(b.action.kind) ?: return null
        return Math.round(r.start + (r.endInclusive - r.start) * value.coerceIn(0, 127) / 127.0).toDouble()
    }

    /**
     * What [e] does under [bindings]: each binding it is for, and whether it fires - a fader every
     * time it moves; a switch when pressed (or at every message, for one that sends one a press).
     */
    fun firing(bindings: List<ControlBinding>, e: ControlEvent): List<ControlBinding> =
        bindings.filter { b -> b.control.matches(e) && (b.continuous || b.everyMessage || e.pressed) }

    private val json = Json { ignoreUnknownKeys = true }
    private val list = ListSerializer(ControlBinding.serializer())

    fun encode(bindings: List<ControlBinding>): String = json.encodeToString(list, bindings)
    fun decode(text: String?): List<ControlBinding> = text?.let { runCatching { json.decodeFromString(list, it) }.getOrNull() } ?: emptyList()
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
