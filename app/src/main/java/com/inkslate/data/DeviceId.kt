package com.inkslate.data

import android.content.Context
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Stable per-installation identity.
 *
 * Every stroke id is prefixed with this. Two devices editing the same document while offline
 * (laptop on a plane, tablet at a desk) must never mint the same id, or a Syncthing merge will
 * quietly drop one device's work. A short random tag is enough: it is not a security boundary,
 * only a collision boundary.
 */
object DeviceId {

    private const val PREFS = "device"
    private const val KEY = "device_tag"

    @Volatile private var cached: String? = null

    fun get(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var tag = sp.getString(KEY, null)
            if (tag == null) {
                tag = UUID.randomUUID().toString().replace("-", "").take(8)
                sp.edit().putString(KEY, tag).apply()
            }
            cached = tag
            return tag
        }
    }

    /** Friendly name shown in the sync/conflict UI, editable by the user. */
    fun label(context: Context): String {
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getString("device_label", null) ?: android.os.Build.MODEL ?: "This device"
    }

    fun setLabel(context: Context, label: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("device_label", label).apply()
    }
}

/**
 * Mints stroke ids of the form `<deviceTag>-<counter>`.
 *
 * The counter is seeded above anything already in the document, so reopening a file and drawing
 * more never reuses an id that a synced-in edit is already using.
 */
class StrokeIdGen(private val deviceTag: String) {

    private val counter = AtomicLong(0)

    fun seedFrom(existingIds: Collection<String>) {
        var maxOwn = 0L
        val prefix = "$deviceTag-"
        for (id in existingIds) {
            if (id.startsWith(prefix)) {
                id.removePrefix(prefix).toLongOrNull()?.let { if (it > maxOwn) maxOwn = it }
            }
        }
        // never move backwards, even if a stale document is loaded after a fresh one
        while (true) {
            val cur = counter.get()
            if (cur >= maxOwn || counter.compareAndSet(cur, maxOwn)) break
        }
    }

    fun next(): String = "$deviceTag-${counter.incrementAndGet()}"

    companion object {
        fun deviceOf(strokeId: String): String = strokeId.substringBefore('-', "")
    }
}
