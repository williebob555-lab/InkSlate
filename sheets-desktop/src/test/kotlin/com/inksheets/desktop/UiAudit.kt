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

    private class Item(val name: String, val bounds: Rect, val full: Rect, val clickable: Boolean, val ancestors: Set<Int>, val id: Int,
                       /** Inside something that scrolls: what it clips is reached by scrolling, by design. */
                       val inScroll: Boolean = false)

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
              nested: Set<String> = emptySet(),
              /**
               * What the screen must show whole - its own controls, named by their words: one pushed out
               * of sight by a panel too short for it is not in what is looked over at all (Compose leaves
               * out what is clipped away), so it is asked for by name.
               */
              required: List<String> = emptyList()): List<String> {
        test.waitForIdle()
        val roots = test.onAllNodes(isRoot()).fetchSemanticsNodes()
        val problems = ArrayList<String>()
        for ((ri, root) in roots.withIndex()) {
            val items = ArrayList<Item>()
            val hidden = ArrayList<Item>()
            fun walk(n: SemanticsNode, ancestors: Set<Int>, inScroll: Boolean) {
                val name = nameOf(n)
                val clickable = n.config.getOrNull(SemanticsActions.OnClick) != null
                val scrolls = inScroll || n.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null ||
                    n.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null
                if (name != null || clickable) {
                    val b = n.boundsInRoot
                    val full = runCatching { val c = n.layoutInfo.coordinates; val p = c.localToRoot(androidx.compose.ui.geometry.Offset.Zero)
                        Rect(p.x, p.y, p.x + c.size.width, p.y + c.size.height) }.getOrDefault(b)
                    if (b.width >= 2 && b.height >= 2) items += Item(name ?: "(button)", b, full, clickable, ancestors, n.id, scrolls)
                    // Laid out, but none of it to be seen: clipped away by what holds it.
                    else if (full.width >= 8 && full.height >= 8) hidden += Item(name ?: "(button)", b, full, clickable, ancestors, n.id, scrolls)
                }
                val next = ancestors + n.id
                for (c in n.children) walk(c, next, scrolls)
            }
            walk(root, emptySet(), false)
            System.getProperty("inksheets.uiaudit.debug")?.let { want -> for (it in items + hidden) if (it.name.contains(want, true))
                println("DEBUG $size $scene '${it.name}' seen ${it.bounds} full ${it.full} scroll ${it.inScroll} hidden ${it in hidden}") }
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
            // Clipped by what holds it (a panel too short for its contents), not by the window's edge,
            // and not inside something that scrolls: a part of it, or the whole, never to be seen.
            for (it in items + hidden) {
                if (it.inScroll) continue
                val f = it.full
                val off = f.left < -1 || f.top < -1 || f.right > w + 1 || f.bottom > h + 1
                val visible = it.bounds.width.coerceAtLeast(0f) * it.bounds.height.coerceAtLeast(0f)
                if (!off && f.width * f.height > 0 && visible < f.width * f.height * 0.85f)
                    problems += "CLIPPED '${it.name}' by its container: ${f.left.toInt()},${f.top.toInt()}-${f.right.toInt()},${f.bottom.toInt()} (${(visible / (f.width * f.height) * 100).toInt()}% seen)"
            }
            // Off the screen altogether - a panel taller than the screen pushing its buttons past its
            // edge - and not inside something that scrolls (a list's rows past its end are by design).
            for (it in hidden) {
                if (it.inScroll || !it.clickable) continue
                val f = it.full
                val outside = f.top >= h - 1 || f.bottom <= 1 || f.left >= w - 1 || f.right <= 1
                if (outside) problems += "OFF-SCREEN '${it.name}': ${f.left.toInt()},${f.top.toInt()}-${f.right.toInt()},${f.bottom.toInt()} in ${w}x$h"
            }
            // Cut by the window's edge: a clickable thing drawn partly off the screen.
            for (it in items) {
                if (!it.clickable || it.inScroll) continue
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
        // Words cut short in what holds them (a button too narrow for its label): their text laid
        // out wider or taller than it is shown.
        for (node in test.onAllNodes(androidx.compose.ui.test.hasText("", substring = true), useUnmergedTree = true).fetchSemanticsNodes()) {
            val get = node.config.getOrNull(SemanticsActions.GetTextLayoutResult) ?: continue
            val results = ArrayList<androidx.compose.ui.text.TextLayoutResult>()
            if (get.action?.invoke(results) != true) continue
            val r = results.firstOrNull() ?: continue
            // Every character shown: the last line laid out ends where the text does (one cut short
            // by too few lines, or too narrow a box, does not), and it is not wider than its box.
            val text = r.layoutInput.text.text.trimEnd()
            val shownTo = if (r.lineCount == 0) 0 else r.getLineEnd(r.lineCount - 1, visibleEnd = true)
            val cut = shownTo < text.length || r.isLineEllipsized(maxOf(0, r.lineCount - 1))
            if (cut && text.isNotBlank())
                problems += "TRUNCATED '${r.layoutInput.text.text.lines().joinToString(" ").take(40)}' at ${node.boundsInRoot.left.toInt()},${node.boundsInRoot.top.toInt()}"
        }
        for (name in required) {
            val nodes = test.onAllNodes(androidx.compose.ui.test.hasText(name, substring = true) or androidx.compose.ui.test.hasContentDescription(name, substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes()
            val whole = nodes.any { n ->
                val b = n.boundsInRoot
                val full = runCatching { n.layoutInfo.coordinates.size }.getOrNull()
                b.width >= 2 && b.height >= 2 && b.top >= -1 && b.bottom <= h + 1 && b.left >= -1 && b.right <= w + 1 &&
                    (full == null || b.width * b.height >= full.width * full.height * 0.85f)
            }
            if (!whole) problems += "UNREACHABLE '$name': not shown whole (${nodes.size} found)"
        }
        val distinct = problems.distinct()
        write("== $size $scene: ${distinct.size} problem(s)")
        distinct.forEach { write("   $it") }
        return distinct
    }
}
