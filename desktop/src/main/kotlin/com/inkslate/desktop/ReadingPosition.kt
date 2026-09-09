package com.inkslate.desktop

import com.inkslate.core.PageLayout

/**
 * Where you were last time, per document.
 *
 * Deliberately local rather than inside the document. A scroll position changes constantly, and
 * writing it into the file the tablet syncs would produce a stream of updates and conflicts for
 * something that is not really shared: two devices are usually being read at two different places
 * on purpose.
 */
object ReadingPosition {

    /**
     * Where a document was left: the page, how its pages were arranged, and the camera.
     *
     * [scale] with [x] and [y] is the zoom plus the document point under the top-left of the
     * window. The page is kept separately rather than derived from the camera because it is the
     * fallback when a document has no camera recorded, and it is what the page indicator wants
     * anyway.
     */
    data class Position(
        val page: Int,
        val layout: PageLayout,
        val scale: Float?,
        val x: Float?,
        val y: Float?
    ) {
        val hasCamera: Boolean get() = scale != null && x != null && y != null
    }

    fun save(path: String, page: Int, layout: PageLayout, scale: Float, x: Float, y: Float) {
        if (!scale.isFinite() || !x.isFinite() || !y.isFinite()) return
        DesktopPrefs.put(
            key(path),
            listOf(page, layout.name, scale, x, y).joinToString("|")
        )
    }

    fun load(path: String): Position? {
        val parts = DesktopPrefs.get(key(path))?.split("|") ?: return null
        return runCatching {
            Position(
                page = parts[0].toInt(),
                layout = PageLayout.valueOf(parts[1]),
                scale = parts.getOrNull(2)?.toFloatOrNull(),
                x = parts.getOrNull(3)?.toFloatOrNull(),
                y = parts.getOrNull(4)?.toFloatOrNull()
            )
        }.getOrNull()
    }

    fun forget(path: String) = DesktopPrefs.put(key(path), null)

    /**
     * Keyed by a hash of the path rather than by the path itself.
     *
     * Paths contain the separators this store uses, and a preference key holding a Windows path
     * is a key nothing else can parse back out.
     */
    private fun key(path: String): String {
        val hash = java.security.MessageDigest.getInstance("SHA-1")
            .digest(path.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(20)
        return "read_at_$hash"
    }
}
