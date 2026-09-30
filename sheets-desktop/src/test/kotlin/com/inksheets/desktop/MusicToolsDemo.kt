package com.inksheets.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inksheets.core.omr.Engraver
import com.inksheets.core.omr.Measure
import com.inksheets.core.omr.Recognizer
import com.inksheets.ui.drawMarks
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Design demos for the music tools: the same real page and its real reading, with each toolbar
 * idea and each look for the read music, at a phone's size and a laptop's. Pictures only:
 * -Dinksheets.omr=demo -Dinksheets.shots=...
 */
@OptIn(ExperimentalTestApi::class)
class MusicToolsDemo {
    private val part = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets/Imported/PEP BAND/Music/Sweet Caroline/SweetC - Trumpet 1.pdf")

    private class Page(val image: ImageBitmap, val measures: List<Measure>)

    private fun page(file: File = part): Page {
        Loader.loadPDF(file).use { doc ->
            // Drawn as the app draws a page to read it: about 18 pixels to a staff space.
            val probe = PDFRenderer(doc).renderImageWithDPI(0, 72f, ImageType.RGB)
            val pp = IntArray(probe.width * probe.height).also { probe.getRGB(0, 0, probe.width, probe.height, it, 0, probe.width) }
            val sp = Recognizer().metrics(com.inksheets.core.omr.Ink.fromArgb(probe.width, probe.height, pp))!!.second
            val img = PDFRenderer(doc).renderImageWithDPI(0, 72f * 18f / sp, ImageType.RGB)
            val px = IntArray(img.width * img.height)
            img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
            val reading = Recognizer().read(com.inksheets.core.omr.Ink.fromArgb(img.width, img.height, px))
            return Page(img.toComposeImageBitmap(), reading.measures)
        }
    }

    private val accent = Color(0xFF6750A4)
    private val doubt = Color(0xFFE65100)

    /** The page fitted to the width, from [fromY] of it: returns the scale used. */
    @Composable
    private fun BoxScope.Sheet(p: Page, fromY: Float, look: String, selected: IntRange? = null) {
        Canvas(Modifier.fillMaxSize().background(Color.White)) {
            val scale = size.width / p.image.width
            val top = (fromY * p.image.height).toInt()
            val h = (size.height / scale).toInt().coerceAtMost(p.image.height - top)
            drawImage(p.image, IntOffset(0, top), IntSize(p.image.width, h), dstSize = IntSize(size.width.toInt(), (h * scale).toInt()))
            for (m in p.measures) {
                val x = m.box.left * scale; val y = (m.box.top - top) * scale
                val w = m.box.width * scale; val hh = m.box.height * scale
                if (y + hh < 0 || y > size.height) continue
                if (selected != null && m.number in selected) {
                    drawRect(accent.copy(alpha = 0.16f), Offset(x, y - m.space * scale * 2), Size(w, hh + m.space * scale * 4))
                }
                when (look) {
                    "doubts" -> if (!m.sure) drawRect(doubt.copy(alpha = 0.22f), Offset(x, y - m.space * scale * 1.5f), Size(w, hh + m.space * scale * 3))
                    // The read music drawn where the printed notes are, in colour over them.
                    "ghost" -> drawMarks(Engraver.aligned(m), m.space * scale, Offset(x, y), (if (m.sure) Color(0xFF1565C0) else doubt).copy(alpha = 0.55f))
                    // What the clean-up pen leaves: the bars it went over redrawn in place of the print.
                    "all-cleaned" -> {
                        drawRect(Color.White, Offset(x, y - m.space * scale * 3.5f), Size(w, hh + m.space * scale * 6.5f))
                        drawMarks(Engraver.aligned(m), m.space * scale, Offset(x, y), if (m.sure) Color.Black else doubt)
                    }
                    "cleaned" -> if (m.number in 9..14) {
                        drawRect(Color.White, Offset(x, y - m.space * scale * 3.5f), Size(w, hh + m.space * scale * 6.5f))
                        drawMarks(Engraver.aligned(m), m.space * scale, Offset(x, y), Color.Black)
                        drawRect(Color(0xFF2E7D32).copy(alpha = 0.08f), Offset(x, y - m.space * scale * 3.5f), Size(w, hh + m.space * scale * 6.5f))
                    }
                }
            }
        }
    }

    @Composable
    private fun Tool(icon: ImageVector, label: String, lit: Boolean = false, compact: Boolean = false) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 3.dp, vertical = 3.dp)) {
            Box(Modifier.size(if (compact) 34.dp else 40.dp).background(if (lit) Color(0xFFE8DEF8) else Color.Transparent, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, label, tint = if (lit) accent else Color(0xFF1D1B20))
            }
            Text(label, fontSize = 9.sp, lineHeight = 10.sp, maxLines = 1)
        }
    }

    @Composable
    private fun MainStrip(modifier: Modifier, musicLit: Boolean) {
        Surface(shape = RoundedCornerShape(20.dp), color = Color.White.copy(alpha = 0.92f), shadowElevation = 2.dp, modifier = modifier) {
            Column(Modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Tool(Icons.Default.Edit, "Pen"); Tool(Icons.Default.Undo, "Undo"); Tool(Icons.Default.Timer, "Metronome")
                Tool(Icons.Default.MusicNote, "Music", lit = musicLit); Tool(Icons.Default.MoreVert, "More")
            }
        }
    }

    private val musicTools = listOf(
        Icons.Default.Layers to "Underlay", Icons.Default.AutoFixHigh to "Clean pen", Icons.Default.PlayArrow to "Play bars",
        Icons.Default.Loop to "Loop", Icons.Default.Groups to "Band", Icons.Default.FactCheck to "Check me",
        Icons.Default.SwapVert to "Transpose", Icons.Default.Search to "Go to bar"
    )

    /** A: a second strip, on the other side. */
    @Composable
    private fun OptionA(p: Page, phone: Boolean) = Box(Modifier.fillMaxSize()) {
        Sheet(p, 0.03f, look = "doubts", selected = 12..14)
        MainStrip(Modifier.align(Alignment.BottomEnd).padding(6.dp), musicLit = true)
        Surface(shape = RoundedCornerShape(20.dp), color = Color.White.copy(alpha = 0.92f), shadowElevation = 2.dp, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)) {
            Column(Modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("♩ 136", fontSize = 11.sp, color = accent, modifier = Modifier.padding(4.dp))
                musicTools.forEachIndexed { i, (icon, label) -> Tool(icon, label, lit = i == 2, compact = phone) }
            }
        }
        Chip("Bars 12-14 selected · ▶ at ♩136", Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
    }

    /** B: a bottom bar - play controls always there, the rest as chips. */
    @Composable
    private fun OptionB(p: Page, phone: Boolean) = Box(Modifier.fillMaxSize()) {
        Sheet(p, 0.03f, look = "cleaned", selected = 12..14)
        MainStrip(Modifier.align(Alignment.CenterEnd).padding(6.dp), musicLit = true)
        Surface(color = Color.White.copy(alpha = 0.96f), shadowElevation = 6.dp, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Bars 12-14", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Tool(Icons.Default.Loop, "Loop", lit = true, compact = true)
                    Box(Modifier.size(48.dp).background(accent, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.PlayArrow, "Play", tint = Color.White) }
                    Text("♩ 136", fontSize = 13.sp, color = accent)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    val chips = if (phone) musicTools.filter { it.second in setOf("Underlay", "Clean pen", "Band", "Check me") } + (Icons.Default.MoreVert to "More")
                    else musicTools.filter { it.second !in setOf("Play bars", "Loop") }
                    chips.forEach { (icon, label) -> Chip(label, Modifier, icon, lit = label == "Underlay") }
                }
            }
        }
    }

    /** C: one Music button; a small palette opens beside it. */
    @Composable
    private fun OptionC(p: Page, phone: Boolean) = Box(Modifier.fillMaxSize()) {
        Sheet(p, 0.03f, look = "ghost", selected = null)
        MainStrip(Modifier.align(Alignment.BottomEnd).padding(6.dp), musicLit = true)
        Surface(shape = RoundedCornerShape(20.dp), color = Color.White, shadowElevation = 8.dp, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 76.dp, bottom = 60.dp).width(if (phone) 250.dp else 300.dp)) {
            Column(Modifier.padding(10.dp)) {
                Text("Music · 51 of 54 bars read sure", fontSize = 12.sp, color = Color(0xFF49454F))
                musicTools.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        row.forEach { (icon, label) -> Tool(icon, label, lit = label == "Underlay") }
                    }
                }
            }
        }
    }

    @Composable
    private fun Chip(text: String, modifier: Modifier, icon: ImageVector? = null, lit: Boolean = false) {
        Surface(shape = RoundedCornerShape(16.dp), color = if (lit) Color(0xFFE8DEF8) else Color(0xFFF3EDF7), shadowElevation = 1.dp, modifier = modifier) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                icon?.let { Icon(it, null, Modifier.size(16.dp), tint = accent); Spacer(Modifier.width(4.dp)) }
                Text(text, fontSize = 12.sp)
            }
        }
    }

    private fun shoot(name: String, w: Int, h: Int, content: @Composable () -> Unit) {
        val dir = File(System.getProperty("inksheets.shots")!!, "demo").apply { mkdirs() }
        runDesktopComposeUiTest(width = w, height = h) {
            setContent { MaterialTheme { content() } }
            waitForIdle()
            ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
        }
    }

    @Test
    fun `the clean-up pen on a scanned part, before and after`() {
        val scan = File(part.parentFile.parentFile, "99 Red Balloons/99 Red Balloons - Trumpet 1.pdf")
        assumeTrue(System.getProperty("inksheets.omr") == "demo" && scan.isFile)
        val p = page(scan)
        shoot("D-scan-before-after", 1280, 900) {
            Column(Modifier.fillMaxSize().background(Color.White)) {
                Text("  The scan", fontSize = 14.sp, modifier = Modifier.padding(4.dp))
                Box(Modifier.fillMaxWidth().weight(1f)) { Sheet(p, 0.05f, look = "none") }
                Text("  Every bar redrawn as read (orange: read in doubt)", fontSize = 14.sp, modifier = Modifier.padding(4.dp))
                Box(Modifier.fillMaxWidth().weight(1f)) { Sheet(p, 0.05f, look = "all-cleaned") }
            }
        }
    }

    @Test
    fun `music tools, three ways, at phone and laptop sizes`() {
        assumeTrue(System.getProperty("inksheets.omr") == "demo" && part.isFile)
        val p = page()
        for ((size, w, h) in listOf(Triple("phone", 390, 844), Triple("laptop", 1280, 800))) {
            val phone = size == "phone"
            shoot("A-second-strip-$size", w, h) { OptionA(p, phone) }
            shoot("B-bottom-bar-$size", w, h) { OptionB(p, phone) }
            shoot("C-palette-$size", w, h) { OptionC(p, phone) }
        }
    }
}
