package com.inksheets.desktop

import org.junit.Assume.assumeTrue
import org.junit.Test

/** The desktop POD Go link's watcher, run for a while: libusb loads, the unit is found (or not), every message logged. -Dinksheets.podgo.usb=<seconds> */
class PodGoUsbCheck {
    @Test
    fun `look for the pod go over usb`() {
        val seconds = System.getProperty("inksheets.podgo.usb")?.toIntOrNull() ?: return assumeTrue(false)
        val input = DesktopPodGoInput()
        input.start({ println("EVENT ${it.describe()}") }) { println("DEVICES $it") }
        Thread.sleep(seconds * 1000L)
        input.stop()
        println("LOG\n" + com.inkslate.desktop.EventLog.dump())
    }
}
