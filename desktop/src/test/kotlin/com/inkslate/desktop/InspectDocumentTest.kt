package com.inkslate.desktop

import org.junit.Test
import java.io.File

/**
 * Prints what kinds of marks a real document holds. Does nothing unless asked:
 * `INKSLATE_INSPECT=C:/path/doc.pdf gradlew :desktop:test --tests *InspectDocumentTest* --rerun`.
 */
class InspectDocumentTest {
    @Test
    fun inspect() {
        val paths = System.getenv("INKSLATE_INSPECT") ?: return
        val out = StringBuilder()
        for (path in paths.split(';')) {
            val ink = DesktopEmbedder.read(File(path))
            out.appendLine("== $path")
            if (ink == null) { out.appendLine("no handwriting"); continue }
            val strokes = ink.pages.values.flatten()
            out.appendLine("${strokes.size} marks, ${ink.deleted.size} tombstones")
            strokes.groupBy { it.kind }.forEach { (kind, list) ->
                out.appendLine("  $kind: ${list.size}  (by device: ${list.groupBy { it.id.substringBefore('-') }.mapValues { it.value.size }})")
            }
            strokes.filter { it.kind.name != "FREEHAND" }.take(12).forEach {
                out.appendLine("    ${it.id} ${it.kind} points=${it.points.size} brush=${it.brush}")
            }
            strokes.filter { it.kind.name == "FREEHAND" }.take(6).forEach {
                out.appendLine("    ${it.id} FREEHAND points=${it.points.size}")
            }
        }
        File(System.getenv("INKSLATE_INSPECT_OUT") ?: "inspect.txt").writeText(out.toString())
    }
}
