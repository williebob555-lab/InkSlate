package com.inksheets.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.inkslate.data.EventLog
import com.inksheets.core.ControlEvent
import com.inksheets.core.ControllerInput
import com.inksheets.core.podgo.MsgPack
import com.inksheets.core.podgo.PodGoEvents
import com.inksheets.core.podgo.PodGoLink

/**
 * A POD Go plugged into the tablet's USB: its footswitches and expression pedal, which it does not
 * send as MIDI, heard the way its editor hears them (see [PodGoLink]) and handed on as controller
 * events. Android asks once whether the app may use the unit; nothing in the unit is changed.
 * Every message it sends is written to the app's log (the first few hundred a session), for working
 * out which is which.
 */
class AndroidPodGoInput(private val context: () -> Context) : ControllerInput {
    private val main = Handler(Looper.getMainLooper())
    private var onEvent: (ControlEvent) -> Unit = {}
    private var onDevices: (List<String>) -> Unit = {}
    private var receiver: BroadcastReceiver? = null
    @Volatile private var running: Thread? = null
    @Volatile private var stopping = false
    private val usb get() = context().getSystemService(Context.USB_SERVICE) as? UsbManager

    override fun start(onEvent: (ControlEvent) -> Unit, onDevices: (List<String>) -> Unit) {
        if (receiver != null) return
        this.onEvent = onEvent
        this.onDevices = onDevices
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val device = deviceOf(intent) ?: return
                if (!isPodGo(device)) return
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> ask(device)
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> stopLink()
                    PERMISSION -> if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) startLink(device)
                        else EventLog.warn("sheets", "POD Go: the app was not let use it")
                }
            }
        }
        receiver = r
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED); addAction(PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= 33) context().registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") context().registerReceiver(r, filter)
        // (Already plugged in.)
        usb?.deviceList?.values?.firstOrNull(::isPodGo)?.let(::ask)
    }

    override fun stop() {
        receiver?.let { runCatching { context().unregisterReceiver(it) } }
        receiver = null
        stopLink()
    }

    private fun deviceOf(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private fun isPodGo(d: UsbDevice) = d.vendorId == PodGoLink.VENDOR && d.productId == PodGoLink.POD_GO

    private fun ask(device: UsbDevice) {
        val m = usb ?: return
        if (m.hasPermission(device)) { startLink(device); return }
        val intent = Intent(PERMISSION).setPackage(context().packageName)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        m.requestPermission(device, PendingIntent.getBroadcast(context(), 0, intent, flags))
    }

    private fun startLink(device: UsbDevice) {
        if (running != null) return
        val m = usb ?: return
        val iface = (0 until device.interfaceCount).map(device::getInterface)
            .firstOrNull { it.id == 0 && it.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC } ?: run { EventLog.warn("sheets", "POD Go: no editor interface"); return }
        val out = endpoint(iface, UsbConstants.USB_DIR_OUT) ?: return
        val inp = endpoint(iface, UsbConstants.USB_DIR_IN) ?: return
        val conn = m.openDevice(device) ?: run { EventLog.warn("sheets", "POD Go: could not be opened"); return }
        if (!conn.claimInterface(iface, true)) { EventLog.warn("sheets", "POD Go: its editor interface is in use"); conn.close(); return }
        stopping = false
        running = Thread({ run(conn, iface, out, inp) }, "pod-go").apply { isDaemon = true; start() }
    }

    private fun endpoint(iface: UsbInterface, dir: Int): UsbEndpoint? =
        (0 until iface.endpointCount).map(iface::getEndpoint).firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == dir }

    private fun run(conn: UsbDeviceConnection, iface: UsbInterface, out: UsbEndpoint, inp: UsbEndpoint) {
        val buf = ByteArray(16384)
        val wire = object : PodGoLink.Wire {
            override fun send(bytes: ByteArray) = conn.bulkTransfer(out, bytes, bytes.size, 1000) >= 0
            override fun recv(timeoutMs: Int): ByteArray? {
                val n = conn.bulkTransfer(inp, buf, buf.size, timeoutMs)
                return if (n < 0) null else buf.copyOf(n)
            }
        }
        val link = PodGoLink(wire) { EventLog.info("sheets", it) }
        var logged = 0
        try {
            if (link.start()) {
                main.post { onDevices(listOf(PodGoEvents.DEVICE)) }
                while (!stopping) link.pump(500) { msg ->
                    if (logged < 300) { logged++; EventLog.info("sheets", "POD Go says: channel 0x${msg.channel.toString(16)} service ${msg.service} ${MsgPack.show(msg.body).take(400)}") }
                    PodGoEvents.toControl(msg)?.let { e -> main.post { onEvent(e) } }
                }
            }
        } catch (t: Throwable) {
            EventLog.warn("sheets", "POD Go: ${t.message}")
        } finally {
            runCatching { link.close() }
            runCatching { conn.releaseInterface(iface) }
            runCatching { conn.close() }
            running = null
            main.post { onDevices(emptyList()) }
        }
    }

    private fun stopLink() {
        stopping = true
        running?.join(1500)
    }

    private companion object {
        const val PERMISSION = "com.inksheets.android.POD_GO_PERMISSION"
    }
}

/** Several systems' controllers as one: MIDI, and a POD Go over USB. */
class AllControllers(private val inputs: List<ControllerInput>) : ControllerInput {
    private val devices = HashMap<Int, List<String>>()
    override fun start(onEvent: (ControlEvent) -> Unit, onDevices: (List<String>) -> Unit) {
        for ((i, input) in inputs.withIndex()) input.start(onEvent) { list ->
            devices[i] = list
            onDevices(devices.toSortedMap().values.flatten())
        }
    }
    override fun stop() = inputs.forEach { it.stop() }
}
