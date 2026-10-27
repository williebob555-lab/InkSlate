package com.inksheets.android

import org.junit.Assert.*
import org.junit.Test

class BluetoothScanHealthTest {
    @Test fun failedRunCanBeStoppedAndRetriedWithoutRepeatedPermissionPrompts() {
        val health = BluetoothScanHealth()
        val first = health.begin()
        health.failed(first)
        assertFalse(health.healthy)
        health.stop()
        assertTrue(health.healthy)
        val second = health.begin()
        health.failed(first)
        assertTrue(health.healthy)
        health.failed(second)
        assertFalse(health.healthy)
    }
    @Test fun callbackAfterStopCannotBreakIdleOrNextRun() {
        val health = BluetoothScanHealth()
        val old = health.begin()
        health.stop()
        health.failed(old)
        assertTrue(health.healthy)
        health.begin()
        health.failed(old)
        assertTrue(health.healthy)
    }
}
