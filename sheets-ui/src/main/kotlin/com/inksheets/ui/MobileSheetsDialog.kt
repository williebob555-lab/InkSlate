package com.inksheets.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.inksheets.core.MobileSheetsImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface MsStep {
    data object Explain : MsStep
    data object Choose : MsStep
    data object ChooseBackup : MsStep
    data class Unpacking(val backup: File, val note: String) : MsStep
    data class Working(val folder: File, val note: String) : MsStep
    data class Done(val result: MobileSheetsImport.Result, val copied: Int, val marks: Int = 0) : MsStep
    data class Failed(val message: String) : MsStep
}

/**
 * Bringing a MobileSheets library across: its songs with their details, the files, recordings,
 * bookmarks, and every setlist in its order.
 */
@Composable
internal fun MobileSheetsDialog(state: SheetsState, onClose: () -> Unit, backup: File? = null) {
    var step by remember { mutableStateOf<MsStep>(if (backup != null) MsStep.Unpacking(backup, "Opening the backup...") else MsStep.Explain) }

    when (val s = step) {
        MsStep.Explain -> SheetDialog(
            title = "Import from MobileSheets",
            onDismiss = onClose,
            buttons = {
                TextButton(onClick = onClose) { Text("Cancel") }
                TextButton(onClick = { step = MsStep.Choose }) { Text("Its folder") }
                TextButton(onClick = { step = MsStep.ChooseBackup }) { Text("A backup (.msb)") }
            }
        ) {
            Text(
                "Either of two things from MobileSheets works:\n\n" +
                    "A backup - MobileSheets' \"Backup library\" makes a .msb file. Put it anywhere " +
                    "you can reach and choose it here; the music inside is unpacked into your music folder.\n\n" +
                    "Its folder - with \"Expose Database File\" on in MobileSheets' settings, its " +
                    "storage folder holds mobilesheets.db beside the music.\n\n" +
                    "Songs that MobileSheets kept once per instrument (\"24K Magic - Trombone 1\", " +
                    "\"24K Magic - Electric Bass\") become one song with a part for each. Running this " +
                    "again later only adds what is new.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        MsStep.ChooseBackup -> FilePickerDialog(
            title = "Choose the MobileSheets backup",
            start = state.root ?: state.platform.startFolder,
            extensions = setOf("msb"),
            onChosen = { step = MsStep.Unpacking(it, "Opening the backup...") },
            onDismiss = onClose
        )

        is MsStep.Unpacking -> {
            LaunchedEffect(s.backup) {
                step = withContext(Dispatchers.IO) {
                    unpackBackup(state, s.backup) { note -> state.platform.onMain { step = MsStep.Unpacking(s.backup, note) } }
                }
            }
            SheetDialog(title = "Import from MobileSheets", onDismiss = {}, buttons = {}) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(s.note)
                }
            }
        }

        MsStep.Choose -> FolderPickerDialog(
            title = "The folder holding mobilesheets.db",
            start = state.root ?: state.platform.startFolder,
            confirmLabel = "Import from here",
            onChosen = { folder ->
                val db = listOf(File(folder, "mobilesheets.db"))
                    .plus(folder.listFiles { f -> f.isDirectory }.orEmpty().map { File(it, "mobilesheets.db") })
                    .firstOrNull { it.isFile }
                step = if (db == null) MsStep.Failed("There is no mobilesheets.db in that folder. Is \"Expose Database File\" on in MobileSheets?")
                else MsStep.Working(db.parentFile, "Reading the library...")
            },
            onDismiss = onClose
        )

        is MsStep.Working -> {
            LaunchedEffect(s.folder) {
                step = withContext(Dispatchers.IO) { runImport(state, s.folder, { note -> state.platform.onMain { step = MsStep.Working(s.folder, note) } }) }
            }
            SheetDialog(title = "Import from MobileSheets", onDismiss = {}, buttons = {}) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(s.note)
                }
            }
        }

        is MsStep.Done -> SheetDialog(title = "Imported", onDismiss = onClose) {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                val r = s.result
                Text(
                    "${r.songs} songs and ${r.setlists} setlists came across" +
                        (if (r.skipped > 0) "; ${r.skipped} were already here." else ".") +
                        (if (s.copied > 0) " ${s.copied} files were copied into your music folder." else "") +
                        " The setlists are in the folder \"${MobileSheetsImport.FOLDER_NAME}\"." +
                        (if (s.marks > 0) " ${s.marks} markings came too; each appears on its page when the music is opened." else ""),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (r.missing.isNotEmpty()) {
                    Spacer(Modifier.size(12.dp))
                    Text("These files could not be found:", style = MaterialTheme.typography.titleSmall)
                    r.missing.take(50).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (r.missing.size > 50) Text("and ${r.missing.size - 50} more", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        is MsStep.Failed -> SheetDialog(title = "Import from MobileSheets", onDismiss = onClose) {
            Text(s.message, color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * A backup: its music unpacked into the music folder's "MobileSheets" folder (which then syncs),
 * its database beside the library's own records, and then the same import as for a folder.
 */
private fun unpackBackup(state: SheetsState, backup: File, progress: (String) -> Unit): MsStep {
    val root = state.root ?: return MsStep.Failed("Choose your music folder first.")
    val total = backup.length().coerceAtLeast(1)
    val msFolder = File(root, "MobileSheets")
    val db = File(root, ".inksheets/mobilesheets-" + backup.nameWithoutExtension + ".db")
    val unpacked = runCatching {
        com.inksheets.core.MsbBackup.extract(backup, db, msFolder, state.platform::openMobileSheets) { read ->
            progress("Unpacking the music... ${read * 100 / total}%")
        }
    }.getOrElse { return MsStep.Failed("The backup could not be read: ${it.message}") }
    val step = runImport(state, msFolder, progress, database = unpacked.database)
    if (step is MsStep.Done) state.platform.setPref(backupDoneKey(backup), "true")
    return step
}

internal fun backupDoneKey(backup: File) = "msb_done_" + backup.name + "_" + backup.length()

private fun runImport(state: SheetsState, msFolder: File, progress: (String) -> Unit, database: File = File(msFolder, "mobilesheets.db")): MsStep {
    val root = state.root ?: return MsStep.Failed("Choose your music folder first.")
    val library = state.library ?: return MsStep.Failed("Choose your music folder first.")
    val tables = state.platform.openMobileSheets(database)
        ?: return MsStep.Failed("The MobileSheets library could not be opened.")

    progress("Finding the music...")
    val byName = msFolder.walkTopDown().onEnter { !it.name.startsWith(".") }
        .filter { it.isFile }.groupBy { it.name.lowercase() }
    val inside = msFolder.canonicalPath.startsWith(root.canonicalPath)
    val copyTo = File(root, "MobileSheets")
    var copied = 0

    val resolve = { path: String ->
        MobileSheetsImport.locate(path, msFolder, byName)?.let { found ->
            if (inside) {
                state.relative(found)
            } else {
                // Outside the synced folder: bring the file in, keeping its folders.
                val target = File(copyTo, found.relativeTo(msFolder).path)
                if (!target.isFile || target.length() != found.length()) {
                    target.parentFile?.mkdirs()
                    found.copyTo(target, overwrite = true)
                    copied++
                    if (copied % 20 == 0) progress("Copying the music... $copied files")
                }
                state.relative(target)
            }
        }
    }

    progress("Bringing the songs across...")
    val result = runCatching { MobileSheetsImport.run(tables, library, resolve) }
        .getOrElse { return MsStep.Failed("The import stopped: ${it.message}") }
    // MobileSheets keeps its markings in the database, not the PDFs. They are brought across too,
    // and even when every song was already here - an earlier import did not bring them.
    progress("Bringing the markings across...")
    val marks = runCatching { com.inksheets.core.MobileSheetsMarks.read(tables, resolve) }
        .onFailure { state.platform.log("MobileSheets markings could not be read: ${it.message}") }
        .getOrDefault(emptyMap())
    runCatching { com.inksheets.core.MobileSheetsMarks.save(root, marks) }
        .onFailure { state.platform.log("MobileSheets markings could not be kept: ${it.message}") }
    state.importedMarksChanged()
    state.change { }   // let the screens see what arrived
    return MsStep.Done(result, copied, marks.values.sumOf { it.size })
}
