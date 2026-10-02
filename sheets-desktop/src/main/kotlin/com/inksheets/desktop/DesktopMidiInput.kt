package com.inksheets.desktop

import com.inksheets.core.ControlEvent
import com.inksheets.core.ControllerInput
import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.Receiver
import javax.sound.midi.ShortMessage

/**
 * MIDI controllers on the desktop, through Java Sound: every input device there (a USB foot
 * controller, a POD Go), opened as it is plugged in - looked for again every few seconds - and
 * let go when it is taken out.
 */
class DesktopMidiInput : ControllerInput {
    private val open = LinkedHashMap<String, MidiDevice>()
    @Volatile private var running = false
    private var watcher: Thread? = null

    override fun start(onEvent: (ControlEvent) -> Unit, onDevices: (List<String>) -> Unit) {
        if (running) return
        running = true
        watcher = Thread({
            var last: List<String>? = null
            while (running) {
                runCatching { look(onEvent) }
                val names = synchronized(open) { open.keys.toList() }
                if (names != last) { last = names; onDevices(names) }
                try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
            }
        }, "midi-devices").apply { isDaemon = true; start() }
    }

    override fun stop() {
        running = false
        watcher?.interrupt()
        synchronized(open) { open.values.forEach { runCatching { it.close() } }; open.clear() }
    }

    private fun look(onEvent: (ControlEvent) -> Unit) {
        val there = HashMap<String, MidiDevice.Info>()
        for (info in MidiSystem.getMidiDeviceInfo()) {
            val device = runCatching { MidiSystem.getMidiDevice(info) }.getOrNull() ?: continue
            // Inputs only: something that sends (it has transmitters), not a synthesizer or sequencer.
            if (device is javax.sound.midi.Sequencer || device is javax.sound.midi.Synthesizer) continue
            if (device.maxTransmitters == 0) continue
            there[name(info)] = info
        }
        synchronized(open) {
            // Taken out: let go.
            for (gone in open.keys.filter { it !in there }) runCatching { open.remove(gone)?.close() }
        }
        for ((name, info) in there) {
            if (synchronized(open) { name in open }) continue
            val device = runCatching { MidiSystem.getMidiDevice(info) }.getOrNull() ?: continue
            // Another program can hold a device to itself on Windows: then it is tried again later.
            val ok = runCatching {
                device.open()
                device.transmitter.receiver = object : Receiver {
                    override fun send(message: MidiMessage, timeStamp: Long) {
                        if (message is ShortMessage) ControlEvent.fromMidi(name, message.status, message.data1, message.data2)?.let(onEvent)
                    }
                    override fun close() {}
                }
            }.isSuccess
            if (ok) synchronized(open) { open[name] = device } else runCatching { device.close() }
        }
    }

    private fun name(info: MidiDevice.Info): String = info.name.trim().ifEmpty { info.description.trim() }
}
