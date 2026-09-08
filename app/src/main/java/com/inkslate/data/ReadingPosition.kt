package com.inkslate.data

import android.content.Context
import com.inkslate.ink.*
import com.inkslate.ink.PageLayout

/**
 * Where you were last time, per document.
 *
 * Deliberately local rather than in the synced sidecar. Scroll position changes constantly, and
 * writing it to a file Syncthing watches would produce a stream of updates and conflicts for
 * something that is not really shared data - two devices are usually being read at two different
 * places on purpose.
 */
class ReadingPosition(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("reading_position", Context.MODE_PRIVATE)

    /**
     * Where the document was left.
     *
     * [camera] is scale plus the document point under the top-left of the viewport - the three
     * numbers [com.inkslate.ink.DrawingView.cameraState] hands back. Null when the document was
     * saved by a version that only recorded the page, which is why [page] is still kept
     * separately rather than derived: it is the fallback, and it is what the page indicator
     * needs anyway.
     */
    data class Position(
        val page: Int,
        val layout: PageLayout?,
        val camera: FloatArray?
    ) {
        // FloatArray in a data class means equals() compares references; nothing here relies on
        // comparing positions, and generating the correct version would be noise.
        override fun equals(other: Any?) = this === other
        override fun hashCode() = page
    }

    fun save(path: String, page: Int, layout: PageLayout, camera: FloatArray?) {
        val e = sp.edit()
            .putInt(key(path, "page"), page)
            .putString(key(path, "layout"), layout.name)
            .putLong(key(path, "at"), System.currentTimeMillis())
        if (camera != null && camera.size >= 3 && camera.all { it.isFinite() }) {
            e.putFloat(key(path, "scale"), camera[0])
                .putFloat(key(path, "x"), camera[1])
                .putFloat(key(path, "y"), camera[2])
                .putBoolean(key(path, "hasCamera"), true)
        } else {
            e.putBoolean(key(path, "hasCamera"), false)
        }
        e.apply()
    }

    fun load(path: String): Position? {
        val page = sp.getInt(key(path, "page"), -1)
        if (page < 0) return null
        val layout = sp.getString(key(path, "layout"), null)
            ?.let { runCatching { PageLayout.valueOf(it) }.getOrNull() }
        val camera = if (sp.getBoolean(key(path, "hasCamera"), false)) {
            floatArrayOf(
                sp.getFloat(key(path, "scale"), 0f),
                sp.getFloat(key(path, "x"), 0f),
                sp.getFloat(key(path, "y"), 0f)
            ).takeIf { it[0] > 0f }
        } else null
        return Position(page, layout, camera)
    }

    fun clear(path: String) {
        sp.edit()
            .remove(key(path, "page"))
            .remove(key(path, "layout"))
            .remove(key(path, "scale"))
            .remove(key(path, "x"))
            .remove(key(path, "y"))
            .remove(key(path, "hasCamera"))
            .remove(key(path, "at"))
            .apply()
    }

    /** Keyed by path; renaming a file simply loses its position, which is harmless. */
    private fun key(path: String, field: String) = "$path|$field"
}
