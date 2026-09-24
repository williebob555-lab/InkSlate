package com.inksheets.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inksheets.core.ImportPlan
import com.inksheets.core.InstrumentSource
import com.inksheets.core.Part
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val MUSIC_EXTENSIONS = setOf("pdf", "png", "jpg", "jpeg", "webp")

/**
 * Adding music: every file in the library folder (or one part of it) that the library does not
 * have yet is gathered into songs, its instrument read, and shown for a look before it goes in.
 */
@Composable
internal fun ImportDialog(state: SheetsState, onClose: () -> Unit) {
    val root = state.root ?: return
    var folder by remember { mutableStateOf<File?>(null) }
    var plan by remember { mutableStateOf<List<ImportPlan.PlannedSong>?>(null) }
    var reading by remember { mutableStateOf(0 to 0) }

    if (folder == null) {
        FolderPickerDialog(
            title = "Add the music in which folder?",
            start = root,
            within = root,
            confirmLabel = "Look in this folder",
            onChosen = { folder = it },
            onDismiss = onClose
        )
        return
    }

    LaunchedEffect(folder) {
        plan = withContext(Dispatchers.IO) {
            val found = folder!!.walkTopDown()
                .onEnter { !it.name.startsWith(".") }
                .filter { it.isFile && it.extension.lowercase() in MUSIC_EXTENSIONS }
                .mapNotNull { state.relative(it) }
                .sorted()
                .toList()
            val existing = state.library?.songs.orEmpty()
            var done = 0
            ImportPlan.plan(found, textOf = { rel ->
                reading = ++done to found.size
                if (rel.endsWith(".pdf", ignoreCase = true)) {
                    state.fileOf(rel)?.let { runCatching { state.platform.pageText(it) }.getOrNull() }
                } else null
            }, existing = existing, recognise = { rel ->
                state.fileOf(rel)?.let { runCatching { state.platform.recognise(it) }.getOrNull() }
            })
        }
    }

    val songs = plan
    val skipped = remember { mutableStateListOf<String>() }
    SheetDialog(
        title = "Add music",
        onDismiss = onClose,
        wide = true,
        buttons = {
            TextButton(onClick = onClose) { Text("Cancel") }
            val count = songs?.count { it.title !in skipped } ?: 0
            TextButton(
                enabled = count > 0,
                onClick = {
                    state.change {
                        songs.orEmpty().filter { it.title !in skipped }.forEach { s ->
                            addSong(s.title, s.parts.map { p -> Part(file = p.file, instrument = p.instrument, source = p.source, label = p.label) })
                        }
                    }
                    onClose()
                }
            ) { Text(if (count == 1) "Add 1 song" else "Add $count songs") }
        }
    ) {
        if (songs == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text("Reading the parts... ${reading.first} of ${reading.second}")
            }
        } else if (songs.isEmpty()) {
            Text("Everything in that folder is already in the library.")
        } else {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(songs, key = { it.title }) { s ->
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 4.dp)) {
                        Checkbox(
                            checked = s.title !in skipped,
                            onCheckedChange = { if (it) skipped.remove(s.title) else skipped.add(s.title) }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(s.title, style = MaterialTheme.typography.titleSmall)
                            s.parts.forEach { p ->
                                val instrument = p.instrument?.let { com.inksheets.core.Instruments.byId[it]?.name }
                                Text(
                                    "${p.file.substringAfterLast('/')} — " + when {
                                        instrument == null -> "instrument not found; set it later in the song's details"
                                        p.source == InstrumentSource.TEXT -> "$instrument (read from the page)"
                                        p.source == InstrumentSource.OCR -> "$instrument (read from the scan)"
                                        else -> "$instrument (from the file name)"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (instrument == null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Choosing a folder by walking the file system. Both builds can read the whole of shared
 * storage, so one browser serves the tablet and the laptop alike. [within] keeps it inside one
 * folder (the library), for choosing where in it to look.
 */
@Composable
internal fun FolderPickerDialog(
    title: String,
    start: File,
    onChosen: (File) -> Unit,
    onDismiss: () -> Unit,
    within: File? = null,
    confirmLabel: String = "Use this folder"
) {
    var at by remember { mutableStateOf(start.takeIf { it.isDirectory } ?: File(System.getProperty("user.home"))) }
    val children = remember(at) {
        at.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().sortedBy { it.name.lowercase() }
    }
    val canGoUp = at.parentFile != null && (within == null || at.canonicalPath != within.canonicalPath)
    SheetDialog(
        title = title,
        onDismiss = onDismiss,
        wide = true,
        buttons = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            TextButton(onClick = { onChosen(at) }) { Text(confirmLabel) }
        }
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { at.parentFile?.let { at = it } }, enabled = canGoUp) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up")
                }
                Text(at.absolutePath, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(children, key = { it.absolutePath }) { dir ->
                    Row(
                        Modifier.fillMaxWidth().clickable { at = dir }.padding(vertical = 8.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(dir.name)
                    }
                }
            }
        }
    }
}
