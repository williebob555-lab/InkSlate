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

    editing?.let { p ->
        ProfileEditor(p, onSave = { state.change { saveProfile(it) }; editing = null }, onCancel = { editing = null })
        return
    }

    SheetDialog(
        title = "Instruments I play",
        onDismiss = onClose,
        buttons = {
            TextButton(onClick = {
                editing = InstrumentProfile(id = "p-" + System.currentTimeMillis().toString(36), name = "", instruments = emptyList())
            }) { Text("Add one") }
            TextButton(onClick = onClose) { Text("Done") }
        }
    ) {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(profiles, key = { it.id }) { p ->
                ListRow(
                    icon = {},
                    title = p.name,
                    detail = p.instruments.mapNotNull { Instruments.byId[it]?.name }.joinToString(", ").ifEmpty { "No parts chosen" },
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

/** A profile's name and its instruments, in the order they are preferred. */
@Composable
private fun ProfileEditor(profile: InstrumentProfile, onSave: (InstrumentProfile) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(profile.name) }
    val chosen = remember { mutableStateListOf(*profile.instruments.toTypedArray()) }
    SheetDialog(
        title = if (profile.name.isEmpty()) "New instrument" else profile.name,
        onDismiss = onCancel,
        wide = true,
        buttons = {
            TextButton(onClick = onCancel) { Text("Cancel") }
            TextButton(
                enabled = name.isNotBlank() && chosen.isNotEmpty(),
                onClick = { onSave(profile.copy(name = name.trim(), instruments = chosen.toList())) }
            ) { Text("Save") }
        }
    ) {
        Column {
            OutlinedTextField(name, { name = it }, label = { Text("Name, e.g. Tuba") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(
                "Tick the parts this shows. The first ticked is opened first when a song has several.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp)
            )
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(Instruments.all, key = { it.id }) { inst ->
                    val at = chosen.indexOf(inst.id)
                    Row(
                        Modifier.fillMaxWidth().clickable { if (at >= 0) chosen.removeAt(at) else chosen.add(inst.id) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = at >= 0, onCheckedChange = null)
                        Text(inst.name, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        if (at >= 0) Text("${at + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 12.dp))
                    }
                }
            }
        }
    }
}
