package com.inksheets.android

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import com.inkslate.data.EventLog
import com.inksheets.core.ControlEvent
import com.inksheets.core.ControllerInput

/**
 * MIDI controllers on Android: USB (a POD Go, a foot controller through an OTG cable) and
 * Bluetooth MIDI devices the system has connected - each opened as it appears, let go as it goes.
 */
class AndroidMidiInput(private val context: () -> Context) : ControllerInput {
    private val main = Handler(Looper.getMainLooper())
    private val open = HashMap<Int, Pair<MidiDevice, List<MidiOutputPort>>>()
    private val names = LinkedHashMap<Int, String>()
    private var manager: MidiManager? = null
    private var callback: MidiManager.DeviceCallback? = null
    private var onEvent: (ControlEvent) -> Unit = {}
    private var onDevices: (List<String>) -> Unit = {}

    override fun start(onEvent: (ControlEvent) -> Unit, onDevices: (List<String>) -> Unit) {
        if (manager != null) return
        val m = context().getSystemService(Context.MIDI_SERVICE) as? MidiManager ?: return
        manager = m
        this.onEvent = onEvent
        this.onDevices = onDevices
        val cb = object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(device: MidiDeviceInfo) = add(device)
            override fun onDeviceRemoved(device: MidiDeviceInfo) = remove(device.id)
        }
        callback = cb
        m.registerDeviceCallback(cb, main)
        @Suppress("DEPRECATION")
        m.devices.forEach(::add)
    }

    override fun stop() {
        callback?.let { cb -> manager?.unregisterDeviceCallback(cb) }
        callback = null
        open.keys.toList().forEach(::remove)
        manager = null
    }

    private fun add(info: MidiDeviceInfo) {
        // Something that sends to us: its output ports.
        if (info.outputPortCount == 0 || info.id in open) return
        val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "MIDI device ${info.id}"
        manager?.openDevice(info, { device ->
            if (device == null) { EventLog.warn("sheets", "Could not open MIDI device $name"); return@openDevice }
            val ports = (0 until info.outputPortCount).mapNotNull { i ->
                device.openOutputPort(i)?.also { port ->
                    val running = IntArray(1)
                    port.connect(object : MidiReceiver() {
                        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                            ControlEvent.fromMidiBytes(name, msg, offset, count, running).forEach(onEvent)
                        }
                    })
                }
            }
            open[info.id] = device to ports
            names[info.id] = name
            onDevices(names.values.toList())
        }, main)
    }

    private fun remove(id: Int) {
        open.remove(id)?.let { (device, ports) ->
            ports.forEach { runCatching { it.close() } }
            runCatching { device.close() }
        }
        if (names.remove(id) != null) onDevices(names.values.toList())
    }
}
