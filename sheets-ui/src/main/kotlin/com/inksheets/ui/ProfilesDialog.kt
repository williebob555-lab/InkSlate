package com.inksheets.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.inksheets.core.InstrumentProfile
import com.inksheets.core.Instruments

/** The instruments you play, and which parts each one shows. */
@Composable
internal fun ProfilesDialog(state: SheetsState, onClose: () -> Unit) {
    var editing by remember { mutableStateOf<InstrumentProfile?>(null) }
    val profiles = state.profiles

    var listing by remember { mutableStateOf(false) }
    editing?.let { p ->
        ProfileEditor(p, onSave = { state.change { saveProfile(it) }; editing = null }, onCancel = { editing = null }, onTeach = { listing = true })
        if (listing) InstrumentListDialog(state, onClose = { listing = false })
        return
    }
    if (listing) { InstrumentListDialog(state, onClose = { listing = false }); return }

    SheetDialog(
        title = "Instruments I play",
        onDismiss = onClose,
        buttons = {
            TextButton(onClick = { listing = true }) { Text("All instruments...") }
            TextButton(onClick = {
                editing = InstrumentProfile(id = "p-" + System.currentTimeMillis().toString(36), name = "", instruments = emptyList())
            }) { Text("Add one") }
            TextButton(onClick = onClose) { Text("Done") }
        }
    ) {
        var query by remember { mutableStateOf("") }
        Column {
        ListSearch(profiles.size, query, { query = it })
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(profiles.filter { matches(query, it.name) }, key = { it.id }) { p ->
                ListRow(
                    icon = {},
                    title = p.name,
                    detail = p.instruments.mapNotNull { com.inksheets.core.PartChoice.seatName(it) }.joinToString(", ").ifEmpty { "No parts chosen" },
                    onClick = { editing = p },
                    menu = listOf(
                        "Edit" to { editing = p },
                        "Remove" to {
                            if (state.profileId == p.id) state.chooseProfile(null)
                            state.change { deleteProfile(p.id) }
                        }
                    )
                )
            }
        }
        }
    }
}

/**
 * What one player reads: instruments, each any part or one chair ("Trumpet 2"), in the order they
 * are preferred. Named for what is ticked - the name is the instrument.
 */
@Composable
private fun ProfileEditor(profile: InstrumentProfile, onSave: (InstrumentProfile) -> Unit, onCancel: () -> Unit, onTeach: () -> Unit) {
    // Entries as "trumpet" (any part) or "trumpet:2" (the 2nd).
    val chosen = remember { mutableStateListOf(*profile.instruments.toTypedArray()) }
    val named = chosen.mapNotNull { com.inksheets.core.PartChoice.seatName(it) }.joinToString(" / ")
    SheetDialog(
        title = named.ifEmpty { "What do you play?" },
        onDismiss = onCancel,
        wide = true,
        buttons = {
            TextButton(onClick = onTeach) { Text("Missing one?") }
            TextButton(onClick = onCancel) { Text("Cancel") }
            TextButton(
                enabled = chosen.isNotEmpty(),
                onClick = { onSave(profile.copy(name = named, instruments = chosen.toList())) }
            ) { Text("Save") }
        }
    ) {
        Column {
            Text(
                "Tick what you play, and which part if there are several - Trumpet 2, say. The first ticked opens first; " +
                    "a song without your part opens one that reads the same (a Baritone B.C. part for a euphonium).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp)
            )
            var query by remember { mutableStateOf("") }
            ListSearch(Instruments.all.size, query, { query = it }, "Find an instrument")
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(Instruments.all.filter { matches(query, it) }, key = { it.id }) { inst ->
                    val at = chosen.indexOfFirst { com.inksheets.core.PartChoice.seat(it).first == inst.id }
                    Column {
                        Row(
                            Modifier.fillMaxWidth().clickable { if (at >= 0) chosen.removeAt(at) else chosen.add(inst.id) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = at >= 0, onCheckedChange = null)
                            Text(inst.name, modifier = Modifier.weight(1f).padding(start = 8.dp))
                            if (at >= 0) Text("${at + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 12.dp))
                        }
                        if (at >= 0) {
                            val chair = com.inksheets.core.PartChoice.seat(chosen[at]).second
                            Row(Modifier.padding(start = 40.dp, bottom = 4.dp).horizontalScroll(rememberScrollState())) {
                                FilterChip(selected = chair == null, onClick = { chosen[at] = inst.id }, label = { Text("Any part") }, modifier = Modifier.padding(end = 4.dp))
                                for (n in 1..4) {
                                    FilterChip(selected = chair == n, onClick = { chosen[at] = "${inst.id}:$n" }, label = { Text("$n") }, modifier = Modifier.padding(end = 4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
