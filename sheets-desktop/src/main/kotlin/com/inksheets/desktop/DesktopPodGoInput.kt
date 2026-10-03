package com.inksheets.desktop

import com.inkslate.desktop.EventLog
import com.inksheets.core.ControlEvent
import com.inksheets.core.ControllerInput
import com.inksheets.core.podgo.MsgPack
import com.inksheets.core.podgo.PodGoEvents
import com.inksheets.core.podgo.PodGoLink
import org.usb4java.DeviceHandle
import org.usb4java.DeviceList
import org.usb4java.LibUsb
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A POD Go plugged into the computer: its footswitches and pedal heard as its editor hears them (see
 * [PodGoLink]), through libusb. Looked for every few seconds. On Linux nothing else holds the unit's
 * editor link; on Windows Line 6's own driver does, and until the link is given the WinUSB driver
 * (Zadig) it cannot be opened here - said once in the log, and tried again only when plugged in anew.
 */
class DesktopPodGoInput : ControllerInput {
    @Volatile private var running = false
    private var watcher: Thread? = null
    @Volatile private var linked = false
    private var refusedFor: String? = null

    override fun start(onEvent: (ControlEvent) -> Unit, onDevices: (List<String>) -> Unit) {
        if (running) return
        if (runCatching { LibUsb.init(null) }.getOrElse { EventLog.info("sheets", "POD Go: no USB access here (${it.message})"); return } != LibUsb.SUCCESS) return
        running = true
        watcher = Thread({
            while (running) {
                runCatching { look(onEvent, onDevices) }.onFailure { EventLog.warn("sheets", "POD Go: ${it.message}") }
                try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
            }
        }, "pod-go").apply { isDaemon = true; start() }
    }

    override fun stop() {
        running = false
        watcher?.interrupt()
    }

    /** The unit, if plugged in and not linked yet: opened and listened to until it goes or the app stops. */
    private fun look(onEvent: (ControlEvent) -> Unit, onDevices: (List<String>) -> Unit) {
        if (linked) return
        val list = DeviceList()
        if (LibUsb.getDeviceList(null, list) < 0) return
        try {
            val device = list.firstOrNull { d ->
                val desc = org.usb4java.DeviceDescriptor()
                LibUsb.getDeviceDescriptor(d, desc) == LibUsb.SUCCESS &&
                    (desc.idVendor().toInt() and 0xFFFF) == PodGoLink.VENDOR && (desc.idProduct().toInt() and 0xFFFF) == PodGoLink.POD_GO
            }
            if (device == null) { refusedFor = null; return }
            val where = "${LibUsb.getBusNumber(device)}-${LibUsb.getDeviceAddress(device)}"
            if (refusedFor == where) return
            val handle = DeviceHandle()
            val opened = LibUsb.open(device, handle)
            if (opened != LibUsb.SUCCESS) { refuse(where, "could not be opened (${LibUsb.errorName(opened)})"); return }
            LibUsb.setAutoDetachKernelDriver(handle, true)
            val claimed = LibUsb.claimInterface(handle, 0)
            if (claimed != LibUsb.SUCCESS) { LibUsb.close(handle); refuse(where, "its editor link is held by another driver (${LibUsb.errorName(claimed)}) - on Windows it needs the WinUSB driver"); return }
            linked = true
            Thread({ listen(handle, onEvent, onDevices) }, "pod-go-link").apply { isDaemon = true; start() }
        } finally {
            LibUsb.freeDeviceList(list, true)
        }
    }

    private fun refuse(where: String, why: String) {
        refusedFor = where
        EventLog.info("sheets", "POD Go: $why"); PodGoEvents.record?.invoke("POD Go: $why")
    }

    private fun listen(handle: DeviceHandle, onEvent: (ControlEvent) -> Unit, onDevices: (List<String>) -> Unit) {
        val inBuf = ByteBuffer.allocateDirect(16384)
        val count = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer()
        val wire = object : PodGoLink.Wire {
            override fun send(bytes: ByteArray): Boolean {
                val out = ByteBuffer.allocateDirect(bytes.size).put(bytes).also { it.flip() }
                return LibUsb.bulkTransfer(handle, 0x01.toByte(), out, count, 1000L) == LibUsb.SUCCESS
            }
            override fun recv(timeoutMs: Int): ByteArray? {
                inBuf.clear()
                val r = LibUsb.bulkTransfer(handle, 0x81.toByte(), inBuf, count, timeoutMs.toLong())
                if (r == LibUsb.ERROR_NO_DEVICE) throw IllegalStateException("unplugged")
                if (r != LibUsb.SUCCESS) return null
                return ByteArray(count.get(0)).also { inBuf.get(it, 0, it.size) }
            }
        }
        val link = PodGoLink(wire) { EventLog.info("sheets", it); PodGoEvents.record?.invoke(it) }
        var logged = 0
        try {
            if (link.start()) {
                onDevices(listOf(PodGoEvents.DEVICE))
                while (running) link.pump(500) { msg ->
                    if (logged < 2000) { logged++; "POD Go says: channel 0x${msg.channel.toString(16)} service ${msg.service} ${MsgPack.show(msg.body).take(1000)}".let { EventLog.info("sheets", it.take(400)); PodGoEvents.record?.invoke(it) } }
                    PodGoEvents.toControl(msg)?.let(onEvent)
                }
            }
        } catch (t: Throwable) {
            EventLog.info("sheets", "POD Go: ${t.message}")
        } finally {
            runCatching { link.close() }
            runCatching { LibUsb.releaseInterface(handle, 0) }
            runCatching { LibUsb.close(handle) }
            linked = false
            onDevices(emptyList())
        }
    }
}
