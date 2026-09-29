package com.inksheets.desktop

import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Remotes over Bluetooth on this computer's own radio: the socket opens, the service is listed, and
 * the address for the pairing code is read. Run by hand (-Dinksheets.bluetooth=true) - it needs a
 * Bluetooth radio switched on, which the build machine does not have.
 */
class BluetoothListenTest {
    @Test
    fun `this computer takes remotes over Bluetooth`() {
        assumeTrue(System.getProperty("inksheets.bluetooth") == "true")
        val bt = DesktopRemoteBluetooth.forThisSystem()
        println("Bluetooth: $bt")
        val listening = bt?.listen { pipe -> println("connection from ${pipe.address}"); pipe.close() }
        println("listening: $listening")
        Thread.sleep(1_000)
        bt?.stopListening()
        println("stopped")
        assert(listening != null) { "could not listen" }
    }
}
