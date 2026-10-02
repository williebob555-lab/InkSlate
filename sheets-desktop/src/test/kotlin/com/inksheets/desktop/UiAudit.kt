package com.inksheets.desktop

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min

/**
 * Looking over a screen for what a player would see wrong with it: two buttons or words lying over
 * each other (one hiding the other, or both unreadable), and a button cut off by the screen's edge.
 * Every screen looked at is photographed to build/uiaudit/<size>-<scene>.png, and what is found
 * written to build/uiaudit/report.txt.
 */
object UiAudit {
    /** Phone, 7" and 10-13" tablets each way up, and laptops: in dp, as the app lays itself out. */
    val SIZES = listOf(
        Triple("phone-small", 360, 640), Triple("phone-small-land", 640, 360),
        Triple("phone", 390, 844), Triple("phone-land", 844, 390),
        Triple("tablet7", 600, 960), Triple("tablet7-land", 960, 600),
        Triple("tablet10", 800, 1280), Triple("tablet10-land", 1280, 800),
        Triple("tablet13", 1024, 1366), Triple("tablet13-land", 1366, 1024),
        Triple("laptop-short", 1280, 720), Triple("laptop", 1600, 1000)
    )

    val dir = File("build/uiaudit").apply { mkdirs() }
    private val report = File(dir, "report.txt")
    @Synchronized fun reset() = report.writeText("")
    @Synchronized private fun write(line: String) { report.appendText(line + "\n"); println(line) }

    private class Item(val name: String, val bounds: Rect, val full: Rect, val clickable: Boolean, val ancestors: Set<Int>, val id: Int)

    private fun nameOf(n: SemanticsNode): String? {
        val c = n.config
        val text = c.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }?.takeIf { it.isNotBlank() }
        val desc = c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")?.takeIf { it.isNotBlank() }
        val edit = c.getOrNull(SemanticsProperties.EditableText)?.text?.takeIf { it.isNotBlank() }
        return (text ?: desc ?: edit)?.replace("\n", " ")?.take(40)
    }

    /**
     * What is wrong with the screen now: each overlap (two named things, neither inside the other,
     * sharing a fifth of the smaller's area or more) and each clickable thing cut by the window's edge.
     */
    @OptIn(ExperimentalTestApi::class)
    fun check(test: ComposeUiTest, size: String, scene: String, w: Int, h: Int, shoot: Boolean = true,
              /** Things meant to lie over the edge of what is under them, carved round them (the remote's page tabs): their overlaps are by design. */
              nested: Set<String> = emptySet()): List<String> {
        test.waitForIdle()
        val roots = test.onAllNodes(isRoot()).fetchSemanticsNodes()
        val problems = ArrayList<String>()
        for ((ri, root) in roots.withIndex()) {
            val items = ArrayList<Item>()
            fun walk(n: SemanticsNode, ancestors: Set<Int>) {
                val name = nameOf(n)
                val clickable = n.config.getOrNull(SemanticsActions.OnClick) != null
                if (name != null || clickable) {
                    val b = n.boundsInRoot
                    val full = runCatching { val c = n.layoutInfo.coordinates; val p = c.localToRoot(androidx.compose.ui.geometry.Offset.Zero)
                        Rect(p.x, p.y, p.x + c.size.width, p.y + c.size.height) }.getOrDefault(b)
                    if (b.width >= 2 && b.height >= 2) items += Item(name ?: "(button)", b, full, clickable, ancestors, n.id)
                }
                val next = ancestors + n.id
                for (c in n.children) walk(c, next)
            }
            walk(root, emptySet())
            for (i in items.indices) for (j in i + 1 until items.size) {
                val a = items[i]; val b = items[j]
                if (a.id in b.ancestors || b.id in a.ancestors) continue
                if (a.name in nested || b.name in nested) continue
                val ix = max(0f, min(a.bounds.right, b.bounds.right) - max(a.bounds.left, b.bounds.left))
                val iy = max(0f, min(a.bounds.bottom, b.bounds.bottom) - max(a.bounds.top, b.bounds.top))
                val inter = ix * iy
                val smaller = min(a.bounds.width * a.bounds.height, b.bounds.width * b.bounds.height)
                if (inter >= 40f && inter >= smaller * 0.2f) problems += "OVERLAP '${a.name}' x '${b.name}' at ${a.bounds.left.toInt()},${a.bounds.top.toInt()} (${(inter / smaller * 100).toInt()}% of the smaller)"
            }
            // Cut by the window's edge: a clickable thing drawn partly off the screen.
            for (it in items) {
                if (!it.clickable) continue
                val f = it.full
                val off = f.left < -1 || f.top < -1 || f.right > w + 1 || f.bottom > h + 1
                val visible = it.bounds.width * it.bounds.height
                if (off && f.width * f.height > 0 && visible < f.width * f.height * 0.85f && visible > 0)
                    problems += "CUT '${it.name}' by the edge: ${f.left.toInt()},${f.top.toInt()}-${f.right.toInt()},${f.bottom.toInt()} in ${w}x$h"
            }
            if (shoot) runCatching {
                ImageIO.write(test.onAllNodes(isRoot())[ri].captureToImage().toAwtImage(), "png", File(dir, "$size-$scene${if (ri > 0) "-$ri" else ""}.png"))
            }
        }
        val distinct = problems.distinct()
        write("== $size $scene: ${distinct.size} problem(s)")
        distinct.forEach { write("   $it") }
        return distinct
    }
}
