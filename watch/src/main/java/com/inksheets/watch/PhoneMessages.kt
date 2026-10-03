package com.inksheets.watch

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.inksheets.core.watch.WatchWire

/** What the phone says to the watch - delivered even when the watch's screen is off. */
class PhoneMessages : WearableListenerService() {
    override fun onMessageReceived(e: MessageEvent) {
        val text = String(e.data)
        // Any word from the phone: it is there and in use, so listening carries on.
        WatchState.phoneAt = System.currentTimeMillis()
        when (e.path) {
            WatchWire.PING -> FlickService.sayHello(this)
            WatchWire.MODEL -> {
                FlickService.saveModel(this, text.ifBlank { null })
                FlickService.buzz(this, longArrayOf(0, 60, 60, 60))
                FlickService.sayHello(this)
            }
            WatchWire.LISTEN -> {
                if (text.startsWith("on")) {
                    val session = text.substringAfter(':', "")
                    WatchState.session = session
                    // Stopped by hand on the watch for this session: left stopped till the next.
                    if (FlickService.running == null && session != FlickService.stoppedSession(this)) FlickService.startFromPhone(this)
                } else FlickService.stop(this)
                FlickService.sayHello(this)
            }
            WatchWire.CALIBRATE -> {
                val on = text == "start"
                if (on && FlickService.running == null) FlickService.start(this)
                // Started just now, it takes a moment to be there.
                fun go(tries: Int) {
                    val s = FlickService.running
                    if (s != null) s.calibrate(on)
                    else if (tries > 0) android.os.Handler(mainLooper).postDelayed({ go(tries - 1) }, 200)
                    else FlickService.sayHello(this)
                }
                go(10)
            }
            WatchWire.CUE -> FlickService.running?.cue(text)
            WatchWire.TURNED -> {
                val n = text.substringBefore(':').toIntOrNull() ?: return
                val rest = text.substringAfter(':', "")
                FlickService.running?.turned(n, rest == "ok", rest.takeIf { it != "ok" && it.isNotBlank() })
            }
        }
    }
}
