package com.inksheets.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.inksheets.ui.MeshRadio
import com.inkslate.data.EventLog

/**
 * Play together over Bluetooth Low Energy: this device's frames advertised one after another, and
 * everyone else's heard by a scan that never stops. No pairing, no connections - any number of
 * devices can listen to any number of others.
 */
@SuppressLint("MissingPermission")
class BleMeshRadio(private val context: () -> Context) : MeshRadio {

    private val main = Handler(Looper.getMainLooper())
    private val scanHealth = BluetoothScanHealth()
    private var scanning: ScanCallback? = null
    private var set: AdvertisingSet? = null
    private var setCallback: AdvertisingSetCallback? = null
    @Volatile private var frames: List<ByteArray> = emptyList()
    private var turn = 0
    private val rotate = object : Runnable {
        override fun run() {
            val all = frames
            val s = set
            if (s != null && all.isNotEmpty()) {
                turn = (turn + 1) % all.size
                runCatching { s.setAdvertisingData(data(all[turn])) }
            }
            main.postDelayed(this, ROTATE_MS)
        }
    }

    private val adapter get() = (context().getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    override fun ready(): Boolean = ready(ask = true)

    override fun ready(ask: Boolean): Boolean {
        val c = context()
        val missing = BluetoothReadinessPolicy.missing(Build.VERSION.SDK_INT, mesh = true) {
            c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            val activity = c as? Activity
            if (ask) activity?.runOnUiThread { activity.requestPermissions(missing.toTypedArray(), 4711) }
            return false
        }
        val a = adapter ?: return false
        if (!runCatching { a.isEnabled }.getOrDefault(false)) {
            if (ask) (c as? Activity)?.runOnUiThread { runCatching { c.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } }
            return false
        }
        return runCatching { scanHealth.healthy && a.bluetoothLeScanner != null && a.bluetoothLeAdvertiser != null }.getOrDefault(false)
    }

    override fun start(onFrame: (ByteArray) -> Unit): Boolean = runCatching {
        stop()
        if (!ready(ask = false)) return false
        val a = adapter ?: return false
        val token = scanHealth.begin()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (scanning === this) result.scanRecord?.getServiceData(SERVICE)?.let(onFrame)
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                if (scanning !== this) return
                results.forEach { r -> r.scanRecord?.getServiceData(SERVICE)?.let(onFrame) }
            }
            override fun onScanFailed(errorCode: Int) {
                scanHealth.failed(token)
                EventLog.warn("sheets", "Bluetooth: listening failed ($errorCode)")
            }
        }
        // Filtered to this app's broadcasts, which also keeps listening going with the screen off.
        val filter = ScanFilter.Builder().setServiceData(SERVICE, ByteArray(0), ByteArray(0)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        val scanner = a.bluetoothLeScanner ?: return false
        scanner.startScan(listOf(filter), settings, callback)
        scanning = callback
        true
    }.getOrElse { e ->
        EventLog.warn("sheets", "Bluetooth: could not start (${e.message})")
        false
    }

    override fun broadcast(frames: List<ByteArray>) {
        this.frames = frames
        main.post {
            val a = adapter ?: return@post
            if (frames.isEmpty()) { stopAdvertising(); return@post }
            if (set != null) {
                turn %= frames.size
                runCatching { set?.setAdvertisingData(data(frames[turn])) }
                return@post
            }
            if (setCallback != null) return@post
            val params = AdvertisingSetParameters.Builder()
                .setLegacyMode(true)
                .setConnectable(false)
                .setScannable(false)
                .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                .build()
            val cb = object : AdvertisingSetCallback() {
                override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
                    if (setCallback !== this) {
                        runCatching { a.bluetoothLeAdvertiser?.stopAdvertisingSet(this) }
                        return
                    }
                    if (status != ADVERTISE_SUCCESS || advertisingSet == null) {
                        EventLog.warn("sheets", "Bluetooth: sending failed ($status)")
                        setCallback = null
                        return
                    }
                    set = advertisingSet
                    main.removeCallbacks(rotate)
                    main.postDelayed(rotate, ROTATE_MS)
                }
                override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
                    if (setCallback === this) { set = null; setCallback = null }
                }
            }
            setCallback = cb
            runCatching { a.bluetoothLeAdvertiser!!.startAdvertisingSet(params, data(frames.first()), null, null, null, cb) }
                .onFailure { EventLog.warn("sheets", "Bluetooth: could not send (${it.message})"); setCallback = null }
        }
    }

    private fun stopAdvertising() {
        main.removeCallbacks(rotate)
        setCallback?.let { cb -> runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(cb) } }
        setCallback = null
        set = null
    }

    override fun stop() {
        scanHealth.stop()
        main.post { stopAdvertising() }
        scanning?.let { cb -> runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) } }
        scanning = null
        frames = emptyList()
    }

    private fun data(frame: ByteArray): AdvertiseData = AdvertiseData.Builder()
        .setIncludeDeviceName(false)
        .setIncludeTxPowerLevel(false)
        .addServiceData(SERVICE, frame)
        .build()

    private companion object {
        /** InkSheets' broadcasts, told apart from everything else in the air by this. */
        val SERVICE: ParcelUuid = ParcelUuid.fromString("0000FE5A-0000-1000-8000-00805F9B34FB")
        /** How long each frame is on the air before the next: every few frames a second. */
        const val ROTATE_MS = 250L
    }
}
