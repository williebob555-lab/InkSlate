package com.inksheets.desktop

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.sound.midi.MidiMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.Receiver
import javax.sound.midi.ShortMessage

/**
 * Everything a MIDI device sends, for a while, each message with when it came: for finding out what
 * a controller (the POD Go) says over MIDI when each footswitch is pressed and each pedal moved.
 * -Dinksheets.podgo=<seconds> [-Dinksheets.podgo.out=file]
 */
class PodGoCapture {
    @Test
    fun `listen to the pod go`() {
        val seconds = System.getProperty("inksheets.podgo")?.toIntOrNull() ?: return assumeTrue(false)
        val out = File(System.getProperty("inksheets.podgo.out") ?: "build/podgo-midi.txt")
        out.writeText("")
        fun log(s: String) { out.appendText(s + "\n"); println(s) }
        val started = System.currentTimeMillis()
        val opened = ArrayList<javax.sound.midi.MidiDevice>()
        for (info in MidiSystem.getMidiDeviceInfo()) {
            val dev = runCatching { MidiSystem.getMidiDevice(info) }.getOrNull() ?: continue
            if (dev.maxTransmitters == 0) continue
            log("DEVICE ${info.name} | ${info.description} | ${info.vendor}")
            runCatching {
                dev.open()
                dev.transmitter.receiver = object : Receiver {
                    override fun send(msg: MidiMessage, ts: Long) {
                        val t = (System.currentTimeMillis() - started) / 1000.0
                        val bytes = msg.message.take(msg.length).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                        val say = (msg as? ShortMessage)?.let { m -> when (m.command) {
                            ShortMessage.CONTROL_CHANGE -> "CC ch${m.channel + 1} #${m.data1} = ${m.data2}"
                            ShortMessage.PROGRAM_CHANGE -> "PC ch${m.channel + 1} ${m.data1}"
                            ShortMessage.NOTE_ON -> "NOTE ON ch${m.channel + 1} ${m.data1} vel ${m.data2}"
                            ShortMessage.NOTE_OFF -> "NOTE OFF ch${m.channel + 1} ${m.data1}"
                            else -> "cmd ${m.command}" } } ?: "sysex/other"
                        log("MIDI %7.2fs %-12s %s  [%s]".format(t, info.name.take(12), say, bytes))
                    }
                    override fun close() {}
                }
                opened += dev
            }.onFailure { log("  (could not open: ${it.message})") }
        }
        log("LISTENING for $seconds s")
        Thread.sleep(seconds * 1000L)
        opened.forEach { runCatching { it.close() } }
        log("DONE")
    }
}
