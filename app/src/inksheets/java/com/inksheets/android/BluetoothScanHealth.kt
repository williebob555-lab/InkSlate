package com.inksheets.android

/** Scanner failure belongs to one run; an old Android callback cannot poison a new run. */
internal class BluetoothScanHealth {
    private var generation = 0L
    @Volatile var healthy = true
        private set
    @Synchronized fun begin(): Long { generation++; healthy = true; return generation }
    @Synchronized fun failed(token: Long) { if (token == generation) healthy = false }
    @Synchronized fun stop() { generation++; healthy = true }
}
