package com.inksheets.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.inksheets.core.Instrument
import com.inksheets.core.Instruments

/**
 * Every instrument parts are read as. One missing - a mellophone - is added here, with the ways it
 * is printed; a built-in one can be given more names. Both go in the library, so every device
 * reads parts the same way. "Redo automatic assignment" then reads every part again with them.
 */
@Composable
internal fun InstrumentListDialog(state: SheetsState, onClose: () -> Unit) {
    var editing by remember { mutableStateOf<Instrument?>(null) }
    val taught = remember(state.version) { state.library?.instruments().orEmpty().associateBy { it.id } }
    val all = remember(state.version) { Instruments.all.sortedBy { it.name.lowercase() } }

    editing?.let { inst ->
        InstrumentEditor(
            state, inst, taught[inst.id],
            onDone = { editing = null }
        )
        return
    }

    SheetDialog(
        title = "Instruments",
        onDismiss = onClose,
        wide = true,
        buttons = {
            TextButton(onClick = {
                editing = Instrument(id = "i-" + System.currentTimeMillis().toString(36), name = "", names = emptyList(), clef = "treble")
            }) { Text("Add an instrument") }
            TextButton(onClick = onClose) { Text("Done") }
        }
    ) {
        Column {
            ReassignRow(state)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(all, key = { it.id }) { inst ->
                    val own = inst.id !in Instruments.builtIn.map { it.id }
                    ListRow(
                        icon = {},
                        title = inst.name + if (own) "  (added)" else "",
                        detail = "Read from: " + inst.names.joinToString(", ") +
                            Instruments.sisters(inst.id).mapNotNull { Instruments.byId[it]?.name }.takeIf { it.isNotEmpty() }
                                ?.let { "  ·  shares parts with " + it.joinToString(", ") }.orEmpty(),
                        onClick = { editing = inst },
                        menu = listOfNotNull(
                            (if (own) "Edit" else "Add names it is printed as") to { editing = inst },
                            if (own) "Remove" to { state.change { deleteInstrument(inst.id) } } else null
                        )
                    )
                }
            }
        }
    }
}

/** "Redo automatic assignment", with its progress and what it did. */
@Composable
internal fun ReassignRow(state: SheetsState) {
    Column {
        val running = state.reassigning
        if (running == null) {
            TextButton(onClick = {
                Thread({ state.reassignInstruments() }, "reassign").apply { isDaemon = true; start() }
            }) { Text("Redo automatic assignment") }
            Text(
                state.reassigned ?: "Reads every part's instrument and every song's tempo again - from names, first pages and scans - with the instruments below. Anything you set yourself stays as it is.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text("Reading parts... ${running.first} of ${running.second}", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(
                progress = { if (running.second == 0) 0f else running.first.toFloat() / running.second },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            )
        }
    }
}

/**
 * An instrument added here - its name, how it is printed, its clef and key - or a built-in one
 * given more names ([taught] holds those added so far).
 */
@Composable
private fun InstrumentEditor(state: SheetsState, instrument: Instrument, taught: Instrument?, onDone: () -> Unit) {
    val builtIn = Instruments.builtIn.firstOrNull { it.id == instrument.id }
    var name by remember { mutableStateOf(instrument.name) }
    // For a built-in one only the names added; its own are fixed.
    var names by remember { mutableStateOf((if (builtIn != null) taught?.names.orEmpty() else instrument.names).joinToString(", ")) }
    var clef by remember { mutableStateOf(instrument.clef) }
    var transpose by remember { mutableStateOf(instrument.transpose) }
    // Built in, only the links added here can change.
    var sameAs by remember { mutableStateOf((if (builtIn != null) taught?.sameAs.orEmpty() else instrument.sameAs).toSet()) }
    var linking by remember { mutableStateOf(false) }
    SheetDialog(
        title = when {
            builtIn != null -> builtIn.name
            instrument.name.isEmpty() -> "Add an instrument"
            else -> instrument.name
        },
        onDismiss = onDone,
        wide = true,
        buttons = {
            TextButton(onClick = onDone) { Text("Cancel") }
            TextButton(enabled = builtIn != null || name.isNotBlank(), onClick = {
                val list = names.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (builtIn != null) {
                    state.change {
                        if (list.isEmpty() && sameAs.isEmpty()) deleteInstrument(builtIn.id)
                        else saveInstrument(builtIn.copy(names = list, sameAs = sameAs.toList()))
                    }
                } else {
                    // Its own name is always one it is read as.
                    val all = (listOf(name.trim()) + list).distinctBy { it.lowercase() }
                    state.change { saveInstrument(Instrument(instrument.id, name.trim(), all, transpose, clef, sameAs.toList())) }
                }
                onDone()
            }) { Text("Save") }
        }
    ) {
        Column {
            if (builtIn == null) {
                OutlinedTextField(name, { name = it }, label = { Text("Name, e.g. Mellophone") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            } else {
                Text("Read from: " + builtIn.names.joinToString(", "), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                names, { names = it },
                label = { Text(if (builtIn != null) "Also printed as" else "Also printed as (optional)") },
                placeholder = { Text("e.g. Mello, Mellophone in F") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Text(
                "Separate names with commas. Short forms and full stops are fine - \"Mello.\" is read like \"Mello\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            // Instruments whose parts this one can read, kept separate for clarity.
            val fixed = builtIn?.sameAs.orEmpty() + Instruments.all.filter { o -> instrument.id in o.sameAs && o.id !in sameAs }.map { it.id }
            val linked = (fixed + sameAs).distinct().mapNotNull { Instruments.byId[it]?.name }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    "Shares parts with: " + linked.joinToString(", ").ifEmpty { "none" },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { linking = true }) { Text("Change") }
            }
            if (linking) {
                SheetDialog(title = "Reads the same parts as", onDismiss = { linking = false }, buttons = {
                    TextButton(onClick = { linking = false }) { Text("Done") }
                }) {
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(Instruments.all.filter { it.id != instrument.id }, key = { it.id }) { other ->
                            val locked = other.id in fixed
                            val on = locked || other.id in sameAs
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = !locked) { sameAs = if (other.id in sameAs) sameAs - other.id else sameAs + other.id },
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                androidx.compose.material3.Checkbox(checked = on, onCheckedChange = null, enabled = !locked)
                                Text(other.name, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
            if (builtIn == null) {
                Text("Clef", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Row {
                    for ((c, label) in listOf("treble" to "Treble", "bass" to "Bass", "alto" to "Alto")) {
                        FilterChip(selected = clef == c, onClick = { clef = c }, label = { Text(label) }, modifier = Modifier.padding(end = 6.dp))
                    }
                }
                Text("Key, for the tuner", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Row {
                    for ((t, label) in listOf(0 to "C", 2 to "B♭", 7 to "F", 9 to "E♭", 14 to "B♭ (octave)")) {
                        FilterChip(selected = transpose == t, onClick = { transpose = t }, label = { Text(label) }, modifier = Modifier.padding(end = 6.dp))
                    }
                }
            }
        }
    }
}
