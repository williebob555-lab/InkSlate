package com.inksheets.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothReadinessPolicyTest {
    @Test fun `classic Bluetooth on modern Android needs connect but no WiFi permissions`() {
        assertEquals(listOf(BluetoothReadinessPolicy.CONNECT), BluetoothReadinessPolicy.required(36, mesh = false))
    }
    @Test fun `BLE includes connect because reading adapter enabled state needs it`() {
        assertEquals(setOf(BluetoothReadinessPolicy.CONNECT, BluetoothReadinessPolicy.SCAN, BluetoothReadinessPolicy.ADVERTISE),
            BluetoothReadinessPolicy.required(31, mesh = true).toSet())
    }
    @Test fun `denied scan permission remains a readiness failure rather than an empty discovery result`() {
        val missing = BluetoothReadinessPolicy.missing(36, mesh = true) { it != BluetoothReadinessPolicy.SCAN }
        assertEquals(listOf(BluetoothReadinessPolicy.SCAN), missing)
    }
    @Test fun `Android 26 classic needs no runtime location and BLE uses location only`() {
        assertTrue(BluetoothReadinessPolicy.required(26, mesh = false).isEmpty())
        assertEquals(listOf(BluetoothReadinessPolicy.LOCATION), BluetoothReadinessPolicy.required(26, mesh = true))
    }
}
