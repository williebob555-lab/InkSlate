package com.inkslate.desktop

import androidx.compose.ui.graphics.Path
import com.inkslate.core.Box
import com.inkslate.core.Stroke

/**
 * The shape of a mark, worked out once.
 *
 * Every frame used to rebuild the geometry of every mark on the page from its points, and a frame
 * happens for each point of the mark being drawn - so writing a line across a page carrying five
 * hundred marks rebuilt five hundred shapes per sample, several thousand times a second. That is
 * felt as the pen lagging behind the hand, and it gets worse the more is on the page, which is the
 * wrong way round for a program whose whole purpose is accumulating annotations.
 *
 * A mark cannot change without being restamped, so its shape can be kept until it is. The shapes
 * are in page coordinates, which the viewport never touches - panning and zooming are a transform
 * applied over the top - so they survive every movement of the camera as well.
 *
 * Kept here rather than in the drawing code because the bounds are wanted for a second purpose:
 * skipping marks that are off screen entirely, which cannot be done cheaply without them.
 */
object InkGeometry {

    /**
     * Shapes held before the least recently used are dropped.
     *
     * Each entry holds native memory for its path, so this is a real ceiling rather than a
     * formality. A document past this size is drawing a fraction of them at any moment anyway.
     */
    private const val MAX_ENTRIES = 4_000

    private class Entry(val stamp: Long, val points: Int) {
        var path: Path? = null
        var outline: Path? = null
        var bounds: Box? = null
    }

    private val cache = object : LinkedHashMap<String, Entry>(1_024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) =
            size > MAX_ENTRIES
    }

    /**
     * The entry for a mark as it is now.
     *
     * Identity is the mark's id together with the stamp it was last edited at. A mark that has
     * been changed has a new stamp and so gets a new entry; the point count is checked as well,
     * because two edits within the same millisecond would otherwise share a stamp and one of them
     * would be drawn with the other's shape.
     */
    @Synchronized
    private fun entryFor(s: Stroke): Entry {
        val existing = cache[s.id]
        if (existing != null && existing.stamp == s.updatedUtc && existing.points == s.points.size) {
            RenderStats.geometryHits++
            return existing
        }
        RenderStats.geometryMisses++
        return Entry(s.updatedUtc, s.points.size).also { cache[s.id] = it }
    }

    @Synchronized
    fun path(s: Stroke): Path {
        val e = entryFor(s)
        return e.path ?: s.toComposePath().also { e.path = it }
    }

    @Synchronized
    fun outline(s: Stroke): Path {
        val e = entryFor(s)
        return e.outline ?: s.toComposeOutline().also { e.outline = it }
    }

    /** The mark's extent in page coordinates, for deciding whether to draw it at all. */
    @Synchronized
    fun bounds(s: Stroke): Box {
        val e = entryFor(s)
        return e.bounds ?: s.boundsBox().also { e.bounds = it }
    }

    /** Dropped when a document closes, because none of it is about the next one. */
    @Synchronized
    fun clear() {
        cache.clear()
        RenderStats.geometryHits = 0L
        RenderStats.geometryMisses = 0L
    }

    @Synchronized
    fun held(): Int = cache.size
}
