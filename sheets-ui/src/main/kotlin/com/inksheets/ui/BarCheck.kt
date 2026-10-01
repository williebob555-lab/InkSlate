package com.inksheets.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inksheets.core.omr.Engraver

/**
 * The bars in doubt, one at a time. At the top, the bar as it is printed (cut from the page), and
 * right under it three readings of it drawn the same way - black on white - to compare with it at
 * a glance and pick with a tap. "None of these" looks further; "Skip" leaves the bar as it is.
 * The panel sits in the half of the page away from the bar.
 */
@Composable
fun BoxScope.BarCheck(state: SheetsState) {
    if (!ScoreTools.checking) return
    val m = ScoreTools.barUp(state) ?: return
    val score = ScoreTools.scoreHere(state)
    // Where the bar is on its page: the panel goes to the other half.
    val pageHeight = score?.pageWidths?.getOrNull(m.page)?.let { it * 1.3f } ?: 1f
    val barLow = m.box.top > pageHeight * 0.5f
    val paper = Color.White
    val printInk = Color(0xFF111111)
    val total = ScoreTools.checkBars.size
    Surface(
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        modifier = Modifier.align(if (barLow) Alignment.TopCenter else Alignment.BottomCenter).padding(12.dp).widthIn(max = 760.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Which bar, and how far through.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Bar ${m.number}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${ScoreTools.checkAt + 1} of $total to check", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinearProgressIndicator(progress = { (ScoreTools.checkAt + 1f) / total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth().height(3.dp))
            // The bar as printed.
            Text("On the page", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(10.dp)).background(paper)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            ) {
                val pic = ScoreTools.barPicture
                if (pic != null) Image(pic, "Bar ${m.number} as printed", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(140.dp).padding(4.dp))
                else Text("Getting the bar from the page...", style = MaterialTheme.typography.bodySmall, color = Color(0xFF777777))
            }
            Text(when {
                    ScoreTools.looking && ScoreTools.askedAgain -> "Looking at it further..."
                    ScoreTools.askedAgain -> "Looked further: is it one of these?"
                    ScoreTools.looking -> "Which matches? Tap it. (Looking at it again more closely...)"
                    else -> "Which matches? Tap it."
                }, style = MaterialTheme.typography.bodyMedium)
            // The readings, drawn as the print is: black on white.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((i, c) in ScoreTools.offered.withIndex()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .clickable { ScoreTools.pick(state, c) }.padding(6.dp)
                    ) {
                        val drawing = remember(c) { Engraver.line(listOf(m.copy(events = c.events, showsClef = true, showsKey = true, showsTime = false))) }
                        Canvas(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(8.dp)).background(paper)) {
                            val space = minOf(size.height / 10f, size.width / (drawing.width + 1f))
                            drawMarks(drawing, space, Offset(space * 0.3f, (size.height - space * 4f) / 2f), printInk)
                        }
                        Text(
                            if (c.changes.isEmpty()) "As read" else c.changes.joinToString("; "),
                            style = MaterialTheme.typography.labelSmall, fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center, maxLines = 2,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Text("${i + 1}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { ScoreTools.noneOfThese(state) }, modifier = Modifier.weight(1f).height(48.dp)) { Text("None of these") }
                OutlinedButton(onClick = { ScoreTools.next(state) }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Skip") }
                Button(onClick = { ScoreTools.endCheck() }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Done") }
            }
        }
    }
}

@Composable
private fun <T> remember(key: Any?, calc: () -> T): T = androidx.compose.runtime.remember(key, calc)
