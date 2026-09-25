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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inksheets.core.BulkImport
import com.inksheets.core.ImportPlan
import com.inksheets.core.InstrumentReader
import com.inksheets.core.Instruments
import com.inksheets.core.Library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The ways music comes in: a download, what is already in the music folder, or the camera. */
@Composable
internal fun AddMusicDialog(state: SheetsState, onClose: () -> Unit) {
    var way by remember { mutableStateOf<String?>(null) }
    when (way) {
        "download" -> return BulkImportDialog(state, onClose)
        "folder" -> return FolderCheckDialog(state, onClose)
        "scan" -> return ScanDialog(state, onClose)
    }
    SheetDialog(title = "Add music", onDismiss = onClose) {
        Column {
            AddWay(Icons.Default.FolderOpen, "Files from anywhere",
                "Pick PDFs, pictures or recordings from another app, Google Drive or Downloads. They go into your music folder's Inbox and are filed for you."
            ) {
                state.platform.pickFiles { files ->
                    if (files.isNotEmpty()) Thread({ state.takeIn(files) }, "take-in").apply { isDaemon = true; start() }
                }
                onClose()
            }
            AddWay(Icons.Default.Download, "A download",
                "A zip or a folder of parts, from your band's website or anywhere. Songs, parts and setlists are sorted for you."
            ) { way = "download" }
            if (state.platform.canScanPages) {
                AddWay(Icons.Default.CameraAlt, "Scan paper music",
                    "Photograph the pages; each one's edges are found and it is straightened."
                ) { way = "scan" }
            }
            AddWay(Icons.Default.FolderOpen, "Already in my music folder",
                "Anything put in the music folder - by you, the file manager or another device - is added by itself within seconds. Check now."
            ) { way = "folder" }
        }
    }
}

@Composable
private fun AddWay(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A whole download at once: choose the zip (or the folder it unpacked to), see what it will make -
 * songs with their parts, and a setlist for each concert folder - and bring it in.
 */
@Composable
internal fun BulkImportDialog(state: SheetsState, onClose: () -> Unit, start: File? = null) {
    val root = state.root ?: return
    var chosen by remember { mutableStateOf(start) }
    var plan by remember { mutableStateOf<BulkImport.Plan?>(null) }
    var source by remember { mutableStateOf<BulkImport.Source?>(null) }
    var inPlace by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(0 to 0) }
    var failed by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<BulkImport.Result?>(null) }
    var working by remember { mutableStateOf(false) }
    var makeSetlists by remember { mutableStateOf(true) }
    // The songs as the person wants them, and as they were worked out, for "Automatic".
    val groups = remember { mutableStateListOf<ReviewGroup>() }
    var automatic by remember { mutableStateOf<List<ReviewGroup>>(emptyList()) }

    val file = chosen
    if (file == null) {
        FilePickerDialog(
            title = "Choose the download",
            start = state.platform.downloadsFolder,
            extensions = setOf("zip"),
            onChosen = { chosen = it },
            onDismiss = onClose,
            note = "Tap a zip, or open the folder a download unpacked to and choose it.",
            folderLabel = "Import this folder",
            onFolder = { chosen = it }
        )
        return
    }

    LaunchedEffect(file) {
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                // A zip is unpacked somewhere private first, so its parts can be read like any file.
                val src: BulkImport.Source = if (file.isDirectory) {
                    BulkImport.FolderSource(file)
                } else {
                    val staging = File(state.platform.cacheFolder, "inksheets-import/" + file.nameWithoutExtension)
                    staging.deleteRecursively()
                    val zip = BulkImport.ZipSource(file)
                    zip.list().forEach { zip.copy(it, File(staging, it)) }
                    object : BulkImport.Source by BulkImport.FolderSource(staging) {
                        override val name: String = zip.name
                    }
                }
                val here = if (file.isDirectory) state.relative(file) else null
                val files = src.list()
                val planned = BulkImport.plan(
                    src.name, files,
                    textOf = { rel ->
                        if (rel.endsWith(".pdf", ignoreCase = true)) src.fileOf(rel)?.let { runCatching { state.platform.pageText(it) }.getOrNull() } else null
                    },
                    recognise = { rel -> src.fileOf(rel)?.let { runCatching { state.platform.recognise(it) }.getOrNull() } },
                    onProgress = { done, of -> reading = done to of }
                )
                Triple(src, here, planned)
            }
        }
        outcome.fold(
            onSuccess = { (src, here, planned) ->
                source = src; inPlace = here; plan = planned
                automatic = planned.songs.map { song ->
                    ReviewGroup(song.title, song.parts.map { part -> reviewItem(part) }, into = existingFor(state, song.title))
                }
                groups.clear(); groups += automatic
            },
            onFailure = { failed = it.message ?: "It could not be read." }
        )
    }

    val p = plan
    val done = result
    SheetDialog(
        title = if (done != null) "Added" else "Import ${p?.name ?: file.nameWithoutExtension}",
        onDismiss = onClose,
        wide = true,
        buttons = {
            if (done != null || failed != null) {
                TextButton(onClick = onClose) { Text("Done") }
            } else {
                TextButton(onClick = onClose) { Text("Cancel") }
                val count = groups.count { !it.skip }
                TextButton(
                    enabled = count > 0 && !working,
                    onClick = {
                        val plan0 = p?.let { reviewed(it, groups) } ?: return@TextButton
                        val src = source ?: return@TextButton
                        working = true
                        Thread({
                            val r = runCatching {
                                state.importing++
                                try { BulkImport.apply(plan0, src, state.library!!, root, makeSetlists, emptySet(), inPlace) }
                                finally { state.importing-- }
                            }
                            // The unpacked copy of a zip has done its job.
                            if (!file.isDirectory) File(state.platform.cacheFolder, "inksheets-import").deleteRecursively()
                            state.platform.onMain {
                                state.change { }
                                working = false
                                r.fold(onSuccess = { result = it }, onFailure = { failed = it.message ?: "Something went wrong." })
                            }
                        }, "bulk-import").apply { isDaemon = true; start() }
                    }
                ) { Text(if (working) "Adding..." else if (count == 1) "Add 1 song" else "Add $count songs") }
            }
        }
    ) {
        when {
            failed != null -> Text("That could not be imported: $failed", color = MaterialTheme.colorScheme.error)
            done != null -> Text(
                listOfNotNull(
                    "${done.songsAdded} new songs.",
                    done.songsMatched.takeIf { it > 0 }?.let { "$it you already had gained any new parts." },
                    done.setlistsMade.takeIf { it > 0 }?.let { "$it setlists made - look in Setlists." }
                ).joinToString(" ")
            )
            p == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(if (reading.second == 0) "Opening it..." else "Reading the parts... ${reading.first} of ${reading.second}")
            }
            p.songs.isEmpty() -> Text("There is no music in it - only PDFs and pictures are brought in.")
            else -> Column {
                Row(
                    Modifier.fillMaxWidth().clickable { makeSetlists = !makeSetlists },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Make a setlist from each folder")
                        Text(
                            p.setlists.joinToString(", ") { "${it.name} (${it.songTitles.size})" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Switch(checked = makeSetlists, onCheckedChange = { makeSetlists = it })
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                ImportReview(state, groups, automatic)
                if (p.songs.any { s -> s.parts.any { it.instrument == null } }) {
                    Text(
                        "Parts not yet named are read from the page in the background once they are in.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

/**
 * Paper music in with the camera: the pages photographed and straightened by the system's
 * scanner, then named - the title guessed from the top of the first page - and filed as a song,
 * or as another part of a song already here.
 */
@Composable
internal fun ScanDialog(state: SheetsState, onClose: () -> Unit) {
    val root = state.root ?: return
    var scanned by remember { mutableStateOf<File?>(null) }
    var asked by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var guess by remember { mutableStateOf<ImportPlan.PlannedPart?>(null) }
    var reading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (asked) return@LaunchedEffect
        asked = true
        state.platform.scanPages { pdf -> if (pdf == null) onClose() else scanned = pdf }
    }
    val pdf = scanned ?: return

    LaunchedEffect(pdf) {
        reading = true
        val (part, words) = withContext(Dispatchers.IO) {
            val text = runCatching { state.platform.recognise(pdf) }.getOrNull()
            ImportPlan.readPart(pdf.name, recognise = { text }) to text
        }
        guess = part
        // The title is usually the biggest words at the top: the first line that is not the part's name.
        if (title.isEmpty()) {
            title = words?.lines()?.map { it.trim() }?.firstOrNull { line ->
                line.count { it.isLetter() } >= 3 && InstrumentReader.read(line) == null
            }.orEmpty()
        }
        reading = false
    }

    SheetDialog(
        title = "Scanned music",
        onDismiss = onClose,
        buttons = {
            TextButton(onClick = onClose) { Text("Cancel") }
            TextButton(enabled = title.isNotBlank(), onClick = {
                val name = title.trim().map { if (it in "\\/:*?\"<>|") ' ' else it }.joinToString("").trim()
                var target = File(root, "Scans/$name.pdf")
                var n = 2
                while (target.exists()) target = File(root, "Scans/$name ($n).pdf").also { n++ }
                target.parentFile?.mkdirs()
                pdf.copyTo(target)
                val rel = state.relative(target) ?: return@TextButton
                val part = (guess ?: ImportPlan.PlannedPart(rel, null, com.inksheets.core.InstrumentSource.UNKNOWN, null)).copy(file = rel).toPart()
                    .copy(id = Library.partIdFor(rel))
                state.change {
                    val existing = songs.firstOrNull { Library.matchKey(it.title) == Library.matchKey(title) }
                    if (existing != null) editSong(existing.id) { parts = existing.parts + part }
                    else addSong(title.trim(), listOf(part))
                }
                onClose()
            }) { Text("Add") }
        }
    ) {
        Column {
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text("Song title") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.size(8.dp))
            Text(
                when {
                    reading -> "Reading the page..."
                    guess?.instrument != null -> "Part: ${Instruments.byId[guess?.instrument]?.name}" + (guess?.label?.let { " (read \"$it\")" } ?: "")
                    else -> "The instrument could not be read; set it in the song's details."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.library?.songs?.any { Library.matchKey(it.title) == Library.matchKey(title) } == true) {
                Text("Added as another part of the song you already have.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}


/** A part of a download as the review shows it: its file, and what it was read as. */
private fun reviewItem(part: ImportPlan.PlannedPart): ReviewItem {
    val name = part.file.substringAfterLast('/')
    val instrument = part.instrument?.let { Instruments.byId[it]?.name }?.let { n ->
        n + (com.inksheets.core.CompanionLink.partNumber(part.label)?.let { " $it" } ?: "")
    } ?: "instrument to be read"
    return ReviewItem(part.file, "$name - $instrument", ImportPlan.readableName(name))
}

/**
 * The plan as the person left it in the review: its songs regrouped, retitled, left out or sent
 * to songs already here - and its setlists pointing at the songs their files ended up in.
 */
private fun reviewed(plan: BulkImport.Plan, groups: List<ReviewGroup>): BulkImport.Plan {
    val partByFile = plan.songs.flatMap { it.parts }.associateBy { it.file }
    val songOfFile = plan.songs.flatMap { s -> s.parts.map { it.file to s } }.toMap()
    val keyOfFile = HashMap<String, String>()
    val songs = groups.mapIndexedNotNull { i, g ->
        if (g.skip) return@mapIndexedNotNull null
        val key = "g$i"
        g.items.forEach { keyOfFile[it.key] = key }
        val audio = g.items.mapNotNull { songOfFile[it.key] }.distinct().flatMap { it.audio }.distinct()
        BulkImport.Song(g.title, g.items.mapNotNull { partByFile[it.key] }, audio, into = g.into, apart = g.apart, key = key)
    }
    val setlists = plan.setlists.map { list ->
        val keys = list.songTitles.mapNotNull { title ->
            plan.songs.firstOrNull { it.title == title }?.parts?.firstNotNullOfOrNull { keyOfFile[it.file] }
        }.distinct()
        BulkImport.SetlistPlan(list.name, keys)
    }
    return BulkImport.Plan(plan.name, songs, setlists)
}


/** The music folder looked at now, rather than in a few seconds. */
@Composable
private fun FolderCheckDialog(state: SheetsState, onClose: () -> Unit) {
    var report by remember { mutableStateOf<com.inksheets.core.LibraryScan.Report?>(null) }
    LaunchedEffect(Unit) { report = withContext(Dispatchers.IO) { state.scanFolder() } ?: com.inksheets.core.LibraryScan.Report() }
    SheetDialog(title = "Your music folder", onDismiss = onClose) {
        val r = report
        if (r == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text("Looking...")
            }
        } else {
            Text(
                if (!r.changed) "Everything in it is already in the library."
                else listOfNotNull(
                    r.added.size.takeIf { it > 0 }?.let { "$it files added" },
                    r.moved.size.takeIf { it > 0 }?.let { "$it moved files followed" },
                    r.removed.size.takeIf { it > 0 }?.let { "$it removed" },
                    r.merged.size.takeIf { it > 0 }?.let { "$it put together" }
                ).joinToString(", ") + ". Details are in Settings, under Library health."
            )
        }
    }
}
