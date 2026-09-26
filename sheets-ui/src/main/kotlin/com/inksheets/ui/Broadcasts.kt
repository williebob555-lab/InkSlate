package com.inksheets.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.inksheets.core.Instruments
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.luminance

private const val K_URGENT_COLOUR = "sheets_urgent_colour"

/**
 * What a leader writes to the band: a line of text, with a quick "Next up" and "Look up", to
 * everyone or only some instruments. Everyone by default.
 */
@Composable
internal fun SendNote(state: SheetsState, onSent: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val only = remember { mutableStateListOf<String>() }
    var urgent by remember { mutableStateOf(false) }
    // Remembered, so a band learns what a colour means.
    var colour by remember { mutableStateOf(state.platform.pref(K_URGENT_COLOUR)?.toIntOrNull()) }
    // The instruments with parts in the library, to pick from - not every instrument there is.
    val present = remember(state.version) {
        state.library?.songs.orEmpty().flatMap { s -> s.parts.mapNotNull { it.instrument } }.distinct()
            .let { have -> Instruments.all.filter { it.id in have } }
    }
    fun send(what: String, cover: Boolean = urgent) {
        if (what.isBlank()) return
        state.companion.sendNote(what, only.toList(), cover, colour)
        onSent(if (only.isEmpty()) "Sent to everyone." else "Sent to " + only.mapNotNull { Instruments.byId[it]?.name }.joinToString(", ") + ".")
        text = ""
    }
    Column {
        Text("Message the band", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.playing?.let { (id, index) ->
                val next = state.library?.setlist(id)?.entries?.getOrNull(index + 1)?.let { e -> state.library?.song(e.songId)?.title }
                if (next != null) AssistChip(onClick = { send("Next up: $next") }, label = { Text("Next up: $next") })
            }
            // Right now, over everything: the band stops.
            AssistChip(
                onClick = { send("STOP", cover = true) },
                label = { Text("Stop", color = MaterialTheme.colorScheme.error) }
            )
            AssistChip(onClick = { send("Look up") }, label = { Text("Look up") })
            AssistChip(onClick = { send("From the top") }, label = { Text("From the top") })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                text, { text = it },
                placeholder = { Text("A message for the band") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { send(text) }, enabled = text.isNotBlank()) { Text("Send") }
        }
        Row(
            Modifier.fillMaxWidth().clickable { urgent = !urgent },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Cover the music")
                Text(
                    "Big, over the page, until each player taps it away - for between songs, or stopping now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.material3.Switch(checked = urgent, onCheckedChange = { urgent = it })
        }
        // The colour it covers the music in - also what Stop uses.
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Colour", style = MaterialTheme.typography.labelLarge)
            FilterChip(selected = colour == null, onClick = {
                colour = null; state.platform.setPref(K_URGENT_COLOUR, null)
            }, label = { Text("Warning") })
            for (c in MARK_COLOURS) {
                androidx.compose.foundation.layout.Box(
                    Modifier.size(28.dp).background(androidx.compose.ui.graphics.Color(c), androidx.compose.foundation.shape.CircleShape)
                        .then(if (c == colour) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, androidx.compose.foundation.shape.CircleShape) else Modifier)
                        .clickable { colour = c; state.platform.setPref(K_URGENT_COLOUR, c.toString()) }
                )
            }
        }
        Text("To", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
        var query by remember { mutableStateOf("") }
        ListSearch(present.size, query, { query = it }, "Find an instrument")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = only.isEmpty(), onClick = { only.clear() }, label = { Text("Everyone") })
            // Chosen ones stay in sight whatever is searched for.
            for (inst in present.filter { it.id in only || matches(query, it) }) {
                FilterChip(
                    selected = inst.id in only,
                    onClick = { if (inst.id in only) only.remove(inst.id) else only.add(inst.id) },
                    label = { Text(inst.name) }
                )
            }
        }
    }
}

/** From the strip while leading: just the sending, and done. */
@Composable
internal fun SendNoteDialog(state: SheetsState, onClose: () -> Unit) {
    var said by remember { mutableStateOf<String?>(null) }
    SheetDialog(title = "Message the band", onDismiss = onClose, buttons = { TextButton(onClick = onClose) { Text("Done") } }) {
        Column {
            SendNote(state) { said = it }
            said?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}

/**
 * The leader's message, over the song where the music is not: beside a landscape page, opposite
 * the strip; under a portrait one. Gone after a few seconds, or at a tap.
 */
@Composable
internal fun BoxWithConstraintsScope.NotePopup(state: SheetsState) {
    val companion = state.companion
    val note = companion.notice
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(companion.noticeCount) {
        if (companion.notice == null) return@LaunchedEffect
        shown = true
        delay(if (companion.notice?.urgent == true) 15_000 else 6_000)
        shown = false
    }
    if (note?.urgent == true) {
        UrgentNote(note, shown) { shown = false }
        return
    }
    val landscape = maxWidth > maxHeight
    val where = when {
        !landscape -> Alignment.BottomCenter
        state.stripOnLeft -> Alignment.TopEnd
        else -> Alignment.TopStart
    }
    AnimatedVisibility(
        visible = shown && note != null,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 1.15f),
        modifier = Modifier.align(where).padding(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = 6.dp,
            modifier = Modifier
                .widthIn(max = if (landscape) (maxWidth * 0.2f).coerceIn(160.dp, 280.dp) else 420.dp)
                .clickable { shown = false }
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                note?.let { n ->
                    Text(n.from.ifBlank { "Leader" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(n.text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}

/** A message that covers the music on purpose: the band stops, or reads this before the next song. */
@Composable
private fun BoxWithConstraintsScope.UrgentNote(note: com.inksheets.core.CompanionLink.Note, shown: Boolean, onGone: () -> Unit) {
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + scaleOut(targetScale = 1.1f),
        modifier = Modifier.matchParentSize()
    ) {
        // Everything under it waits: a tap anywhere is taken here, not by the page.
        val fill = note.color?.let { androidx.compose.ui.graphics.Color(it) } ?: MaterialTheme.colorScheme.errorContainer
        // Words dark on a light colour and light on a dark one.
        val ink = if (note.color == null) MaterialTheme.colorScheme.onErrorContainer
            else if (fill.luminance() > 0.45f) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White
        Surface(
            color = fill.copy(alpha = 0.96f),
            modifier = Modifier.matchParentSize().clickable(onClick = onGone)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(32.dp)
            ) {
                Text(note.from.ifBlank { "Leader" }, style = MaterialTheme.typography.titleMedium, color = ink)
                Text(
                    note.text,
                    style = MaterialTheme.typography.displayMedium,
                    color = ink,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
                TextButton(onClick = onGone) { Text("Got it", style = MaterialTheme.typography.titleMedium, color = ink) }
            }
        }
    }
}
