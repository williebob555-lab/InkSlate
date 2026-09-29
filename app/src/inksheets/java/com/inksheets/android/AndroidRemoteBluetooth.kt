package com.inksheets.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.inksheets.core.RemoteBluetooth
import com.inksheets.core.RemotePipe
import com.inkslate.data.EventLog
import java.io.InputStream
import java.io.OutputStream

/**
 * A remote over classic Bluetooth (RFCOMM), with devices paired in the system's settings: the way
 * round a Wi-Fi that lets a connection open and then carries nothing (eduroam).
 */
@SuppressLint("MissingPermission")
class AndroidRemoteBluetooth(private val context: () -> Context) : RemoteBluetooth {

    private val adapter: BluetoothAdapter?
        get() = (context().getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @Volatile private var servers: List<BluetoothServerSocket> = emptyList()

    override fun ready(): Boolean {
        val c = context()
        if (Build.VERSION.SDK_INT >= 31 && c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            (c as? Activity)?.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 4712)
            return false
        }
        val a = adapter ?: return false
        if (!a.isEnabled) {
            runCatching { (c as? Activity)?.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            return false
        }
        return true
    }

    private class Pipe(val socket: BluetoothSocket) : RemotePipe {
        override val input: InputStream = socket.inputStream
        override val output: OutputStream = socket.outputStream
        override val address: String = "bt:" + (runCatching { socket.remoteDevice.address }.getOrNull() ?: "?")
        override fun close() { runCatching { socket.close() } }
    }

    override fun listen(take: (RemotePipe) -> Unit): RemoteBluetooth.Listening? {
        stopListening()
        val a = adapter ?: return null
        // Both kinds: a paired remote connects to either; the insecure one spares a second prompt.
        val made = listOfNotNull(
            runCatching { a.listenUsingInsecureRfcommWithServiceRecord(RemoteBluetooth.NAME, RemoteBluetooth.UUID) }.getOrNull()
        )
        if (made.isEmpty()) return null
        servers = made
        made.forEach { server ->
            Thread({
                while (server in servers) {
                    val s = runCatching { server.accept() }.getOrNull() ?: break
                    runCatching { take(Pipe(s)) }.onFailure { runCatching { s.close() } }
                }
            }, "remote-bt-accept").apply { isDaemon = true; start() }
        }
        // Android keeps its own address to itself: remotes find this device in their paired list.
        return RemoteBluetooth.Listening(null, null)
    }

    override fun stopListening() {
        val old = servers
        servers = emptyList()
        old.forEach { runCatching { it.close() } }
    }

    override fun connect(address: String, channel: Int?): RemotePipe? {
        val a = adapter ?: return null
        val device = runCatching { a.getRemoteDevice(address) }.getOrNull() ?: return null
        runCatching { a.cancelDiscovery() }
        // By the service's id, both ways; then by channel, for a computer whose service is not
        // listed (Linux).
        val attempts = listOf<() -> BluetoothSocket>(
            { device.createInsecureRfcommSocketToServiceRecord(RemoteBluetooth.UUID) },
            { device.createRfcommSocketToServiceRecord(RemoteBluetooth.UUID) },
            { byChannel(device, channel ?: RemoteBluetooth.CHANNEL) }
        )
        for (make in attempts) {
            val s = runCatching { make() }.getOrNull() ?: continue
            if (runCatching { s.connect() }.onFailure { EventLog.info("sheets", "Remote: Bluetooth to $address: ${it.message}") }.isSuccess) return Pipe(s)
            runCatching { s.close() }
        }
        return null
    }

    private fun byChannel(device: android.bluetooth.BluetoothDevice, channel: Int): BluetoothSocket =
        device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType).invoke(device, channel) as BluetoothSocket

    override fun paired(): List<Pair<String, String>> =
        runCatching { adapter?.bondedDevices.orEmpty().map { (it.name ?: it.address) to it.address } }.getOrDefault(emptyList())
            .sortedBy { it.first.lowercase() }
}
