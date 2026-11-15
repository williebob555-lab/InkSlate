package com.inkslate.core

/** Object properties, independent of the pen which will draw the next stroke. */
data class SelectionProperties(
    val title: String,
    val objectCount: Int,
    val color: Int?,
    val width: Float?,
    val canColor: Boolean,
    val canWidth: Boolean,
    val layerMask: Int?
) {
    companion object {
        fun of(strokes: List<Stroke>): SelectionProperties {
            val objects = strokes.groupBy { s -> s.stamp?.let { "${s.pageIndex}:${it.group}" } ?: "id:${s.id}" }.values
            val stamp = Stamps.stampOf(strokes)
            val count = objects.size
            val title = when {
                count == 0 -> "Selection"
                count > 1 -> "$count objects"
                stamp != null -> Stamps.kindOf(stamp)?.label ?: "Stamp"
                else -> when (strokes.first().kind) {
                    Stroke.Kind.FREEHAND -> "Handwriting"
                    Stroke.Kind.TEXT -> "Text"
                    Stroke.Kind.IMAGE -> "Picture"
                    Stroke.Kind.TABLE -> "Table"
                    else -> "Shape"
                }
            }
            val colored = strokes.filter { it.kind != Stroke.Kind.IMAGE }
            val weighted = colored.filter { it.kind != Stroke.Kind.TEXT }
            return SelectionProperties(title, count,
                colored.map { it.stamp?.options?.color ?: it.color }.distinct().singleOrNull(),
                weighted.map { it.stamp?.options?.weight ?: it.baseWidth }.distinct().singleOrNull(),
                colored.isNotEmpty(), weighted.isNotEmpty(),
                strokes.map { AnnotationLayers.normalise(it.layerMask) }.distinct().singleOrNull())
        }
    }
}

object SelectionAppearance {
    /** Rebuild whole stamps so their settings and differentiated line weights remain coherent. */
    fun restyle(strokes: List<Stroke>, color: Int?, width: Float?, now: Long, nextId: () -> String): List<Stroke> {
        require(width == null || (width.isFinite() && width > 0f))
        val groups = strokes.filter { it.stamp != null }.groupBy { it.pageIndex to it.stamp!!.group }
        val rebuilt = HashMap<Pair<Int, String>, List<Stroke>>()
        for ((key, members) in groups) {
            val tag = Stamps.stampOf(members) ?: continue
            if (Stamps.kindOf(tag) == null) continue
            val options = tag.options.copy(color = color ?: tag.options.color, weight = width ?: tag.options.weight)
            val layer = members.first().layerMask
            val clock = maxOf(now, members.maxOf { it.updatedUtc } + 1)
            rebuilt[key] = Stamps.rebuild(members, options, nextId).map {
                it.copy(layerMask = layer, updatedUtc = clock)
            }
        }
        val emitted = HashSet<Pair<Int, String>>()
        return buildList {
            for (stroke in strokes) {
                val key = stroke.stamp?.let { stroke.pageIndex to it.group }
                val replacement = rebuilt[key]
                if (key != null && replacement != null) {
                    if (emitted.add(key)) addAll(replacement)
                } else if (stroke.kind == Stroke.Kind.IMAGE) add(stroke)
                else {
                    val w = if (stroke.kind == Stroke.Kind.TEXT) null else width
                    val ratio = if (w != null && stroke.baseWidth > 0f) w / stroke.baseWidth else 1f
                    add(stroke.copy(color = color ?: stroke.color, baseWidth = w ?: stroke.baseWidth,
                        points = if (w == null) stroke.points else stroke.points.map { it.copy(width = it.width * ratio) },
                        updatedUtc = maxOf(now, stroke.updatedUtc + 1)))
                }
            }
        }
    }
}

data class SelectionLayerEdit(val added: List<Stroke>, val removed: List<Stroke>, val selected: List<Stroke>)

object SelectionLayers {
    /** Transfer the selected channel only; legacy shared ink survives on every untouched channel. */
    fun transfer(strokes: List<Stroke>, source: Int, target: Int, copy: Boolean,
                 now: Long, nextId: () -> String): SelectionLayerEdit {
        val from = AnnotationLayers.recordingLayer(source)
        val to = AnnotationLayers.recordingLayer(target)
        if (!copy && from == to) return SelectionLayerEdit(emptyList(), emptyList(), strokes)
        val retained = ArrayList<Stroke>()
        val transferred = ArrayList<Stroke>()
        val alreadyAtTarget = ArrayList<Stroke>()
        val removed = ArrayList<Stroke>()
        for (s in strokes) {
            if (!copy && s.layerMask and from == 0) continue
            val clock = maxOf(now, s.updatedUtc + 1)
            val remaining = AnnotationLayers.withoutLayer(s.layerMask, from)
            if (!copy) {
                removed += s
                if (remaining != 0) {
                    if (remaining == to) {
                        // Moving a membership to a channel which already owns this ink must
                        // not stack a second identical object on top of the surviving one.
                        val kept = s.copy(layerMask = to, updatedUtc = clock)
                        retained += kept
                        alreadyAtTarget += kept
                        continue
                    }
                    // If the destination and a third channel shared this object, separate the
                    // selected destination from that untouched channel without doubling its ink.
                    val untouched = remaining and to.inv()
                    if (untouched != 0) retained += s.copy(layerMask = untouched, updatedUtc = clock)
                }
            }
            transferred += s.copy(id = if (copy || remaining != 0) nextId() else s.id,
                layerMask = to, updatedUtc = clock)
        }
        val result = Stamps.regroup(transferred, nextId)
        return SelectionLayerEdit(retained + result, removed, alreadyAtTarget + result)
    }
}
