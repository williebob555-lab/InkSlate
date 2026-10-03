package com.inksheets.android

import android.content.Context
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.inkslate.data.EventLog
import com.inksheets.ui.WatchLink

/**
 * A Wear OS watch paired with this phone, through the watch's own link (Google's data layer, the
 * one Samsung's Galaxy Wearable app keeps up). It joins this app to the InkSheets watch app only:
 * same app id, same signing key.
 */
class AndroidWatch(private val context: () -> Context) : WatchLink {
    private var listener: MessageClient.OnMessageReceivedListener? = null

    private fun app() = context().applicationContext

    override fun start(onMessage: (path: String, data: ByteArray) -> Unit) {
        if (listener != null) return
        val l = MessageClient.OnMessageReceivedListener { e -> onMessage(e.path, e.data) }
        listener = l
        Wearable.getMessageClient(app()).addListener(l)
            .addOnFailureListener { EventLog.warn("sheets", "Watch: could not listen - ${it.message}") }
    }

    override fun stop() {
        listener?.let { Wearable.getMessageClient(app()).removeListener(it) }
        listener = null
    }

    override fun send(path: String, data: ByteArray, done: (Boolean) -> Unit) {
        val app = app()
        Wearable.getNodeClient(app).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) { done(false); return@addOnSuccessListener }
                var left = nodes.size
                var any = false
                for (node in nodes) Wearable.getMessageClient(app).sendMessage(node.id, path, data)
                    .addOnCompleteListener { r -> if (r.isSuccessful) any = true; if (--left == 0) done(any) }
            }
            .addOnFailureListener { done(false) }
    }

    override fun watches(onResult: (List<String>) -> Unit) {
        Wearable.getNodeClient(app()).connectedNodes
            .addOnSuccessListener { nodes -> onResult(nodes.map { it.displayName }) }
            // No Play services, or no watch app at all on this phone: no watches.
            .addOnFailureListener { onResult(emptyList()) }
    }
}
