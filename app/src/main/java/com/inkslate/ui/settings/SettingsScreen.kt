package com.inkslate.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkslate.core.StylusButtonAction
import com.inkslate.data.CopyLocation
import com.inkslate.data.CopyNaming
import com.inkslate.data.CrashLog
import com.inkslate.data.EventLog
import com.inkslate.data.FileRepo
import com.inkslate.ink.*
import com.inkslate.ink.RenderStats
import com.inkslate.data.DeviceId
import com.inkslate.data.InkFormat
import com.inkslate.data.SaveMode
import com.inkslate.data.SavePrefs
import java.io.File
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import com.inkslate.data.DocumentRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { SavePrefs(context) }
    // The same store the editor's toolbar edits, so the stylus setting is one value everywhere.
    val tools = com.inkslate.ui.editor.rememberToolState(context)
    var settings by remember { mutableStateOf(prefs.global()) }
    var overrides by remember { mutableStateOf(prefs.overriddenPaths()) }
    var deviceLabel by remember { mutableStateOf(DeviceId.label(context)) }
    var crashes by remember { mutableStateOf(CrashLog.recent(context)) }
    var viewingCrash by remember { mutableStateOf<String?>(null) }
    var statsTick by remember { mutableStateOf(0) }
    var showAllEvents by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Old-style companion files still sitting beside documents, and the tidy-up of them.
    var legacy by remember { mutableStateOf<List<java.io.File>?>(null) }
    var absorbing by remember { mutableStateOf(false) }
    var absorbed by remember { mutableStateOf(0) }
    var absorbFailed by remember { mutableStateOf(0) }
    var confirmAbsorb by remember { mutableStateOf(false) }
    val storageLines = remember(statsTick) {
        val ext = android.os.Environment.getExternalStorageDirectory()
        val freeGb = runCatching { ext.freeSpace / 1_073_741_824.0 }.getOrDefault(0.0)
        val totalGb = runCatching { ext.totalSpace / 1_073_741_824.0 }.getOrDefault(0.0)
        val libs = FileRepo(context).libraryFolders()
        listOf(
            "%.1f GB free of %.1f GB".format(freeGb, totalGb),
            libs.size.toString() + " folder(s) on Home"
        )
    }

    fun update(block: (com.inkslate.data.SaveSettings) -> com.inkslate.data.SaveSettings) {
        settings = block(settings).also { prefs.setGlobal(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            SectionHeader("Saving")
            Text(
                "These are the defaults. Any file can override them from the editor, and its own " +
                    "rules always win.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            ChoiceRow(
                title = "When I save",
                current = settings.mode.label,
                options = SaveMode.entries.map { it.label to it }
            ) { update { s -> s.copy(mode = it) } }

            ChoiceRow(
                title = "Ink in exported PDFs",
                subtitle = settings.inkFormat.detail,
                current = settings.inkFormat.label,
                options = InkFormat.entries.map { it.label to it }
            ) { update { s -> s.copy(inkFormat = it) } }

            if (settings.mode != SaveMode.OVERWRITE) {
                ChoiceRow(
                    title = "Name copies by",
                    current = settings.copyNaming.label,
                    options = CopyNaming.entries.map { it.label to it }
                ) { update { s -> s.copy(copyNaming = it) } }

                if (settings.copyNaming == CopyNaming.SUFFIX) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        OutlinedTextField(
                            value = settings.copySuffix,
                            onValueChange = { update { s -> s.copy(copySuffix = it) } },
                            label = { Text("Suffix") },
                            singleLine = true,
                            supportingText = {
                                Text("homework.pdf becomes homework${settings.copySuffix}.pdf")
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                ChoiceRow(
                    title = "Put copies",
                    current = settings.copyLocation.label,
                    options = CopyLocation.entries.map { it.label to it }
                ) { update { s -> s.copy(copyLocation = it) } }
            }

            if (settings.mode != SaveMode.COPY) {
                SwitchRow(
                    "Confirm before overwriting",
                    "Ask once before writing over the file you opened.",
                    settings.confirmOverwrite
                ) { update { s -> s.copy(confirmOverwrite = it) } }

                SwitchRow(
                    "Keep backups when overwriting",
                    "Snapshots the original into a hidden folder first. Strongly recommended: " +
                        "it is the only way back if you overwrite a blank worksheet.",
                    settings.backupOnOverwrite
                ) { update { s -> s.copy(backupOnOverwrite = it) } }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader("Autosave")

            SwitchRow(
                "Autosave annotations",
                "Keeps a working copy as you draw, in the app's own storage. Your document is " +
                    "only written when you save or leave it.",
                settings.autosave
            ) { update { s -> s.copy(autosave = it) } }

            if (settings.autosave) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        "Every ${settings.autosaveSeconds} seconds",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = settings.autosaveSeconds.toFloat(),
                        onValueChange = { update { s -> s.copy(autosaveSeconds = it.toInt()) } },
                        valueRange = 5f..120f,
                        steps = 22
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader("While a document is open")

            SwitchRow(
                title = "Pick up where I left off",
                subtitle = "Reopen each document at the same place and zoom. Off, and every " +
                    "document opens at the top.",
                checked = tools.rememberView
            ) { tools.rememberView = it; tools.persistNow() }

            SwitchRow(
                title = "Keep the screen on",
                subtitle = "The screen will not time out while you have a document open. It " +
                    "still turns off everywhere else.",
                checked = tools.keepScreenOn
            ) { tools.keepScreenOn = it; tools.persistNow() }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader("Stylus")

            // The button is configured where it is used rather than here. A setting three
            // screens away from the pen it belongs to is a setting nobody finds, and a button is
            // now a whole pen profile - colour, brush and width included - which is the toolbar's
            // job to edit, not a dropdown's.
            Text(
                if (tools.button1Seen) {
                    "Your pen's button" + (if (tools.button2Seen) "s have" else " has") +
                        " a profile of its own in the toolbar - a second pen, with its own " +
                        "tool, colour and width. Reach it by tapping the Pen/Finger switch " +
                        "while holding the button.\n\nRight now, held, it " +
                        tools.stylusButton.detail.replaceFirstChar { it.lowercase() } + "."
                } else {
                    "If your stylus has a barrel button, tap the Pen/Finger switch in the " +
                        "toolbar while holding it. That reveals a profile for the button - a " +
                        "second pen with its own tool, colour and width - and it stays in the " +
                        "switch afterwards. Nothing appears here until a button reports itself, " +
                        "so a stylus without one never shows a control it cannot use."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            // There is deliberately no switch here to reveal the profile. Tapping the Pen/Finger
            // control with the barrel held is the only way in, so there is one gesture to learn
            // rather than a gesture and a settings toggle that do the same thing.

            Text(
                "The eraser end of the pen always erases.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader("This device")

            Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                OutlinedTextField(
                    value = deviceLabel,
                    onValueChange = { deviceLabel = it; DeviceId.setLabel(context, it) },
                    label = { Text("Device name") },
                    singleLine = true,
                    supportingText = {
                        Text("Shown when a file was edited elsewhere and needs merging.")
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(
                "Sync tag: ${DeviceId.get(context)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )

            if (overrides.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionHeader("Files with their own rules")
                Text(
                    "${overrides.size} file${if (overrides.size == 1) "" else "s"} ignore the " +
                        "defaults above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )

                overrides.forEach { path ->
                    val o = prefs.overrideFor(path)
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    File(path).name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    o?.describe(settings)?.joinToString(" · ")
                                        ?.ifEmpty { "Same as defaults" } ?: "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = {
                                prefs.clearOverride(path)
                                overrides = prefs.overriddenPaths()
                            }) {
                                Icon(Icons.Default.Close, "Reset to defaults")
                            }
                        }
                    }
                }

                TextButton(
                    onClick = { prefs.clearAllOverrides(); overrides = prefs.overriddenPaths() },
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) { Text("Reset all to defaults") }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader("Diagnostics")

            // ---- live rendering stats ----
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Rendering", style = MaterialTheme.typography.labelLarge)
                    Text(
                        RenderStats.summary(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        "From the last document you had open. Anything consistently over 16ms " +
                            "is a dropped frame.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    TextButton(onClick = { RenderStats.reset(); statsTick++ }) {
                        Text("Reset counters")
                    }
                }
            }

            // ---- storage ----
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Storage", style = MaterialTheme.typography.labelLarge)
                    storageLines.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ---- activity log ----
            val counts = remember(statsTick) { EventLog.counts() }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Activity log  (" + counts.third + " errors, " + counts.second +
                        " warnings, " + counts.first + " info)",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showAllEvents = !showAllEvents }) {
                    Text(if (showAllEvents) "Problems only" else "Show all")
                }
            }

            val events = remember(statsTick, showAllEvents) {
                if (showAllEvents) EventLog.recent(80)
                else EventLog.recentOfAtLeast(EventLog.Level.WARN, 80)
            }
            if (events.isEmpty()) {
                Text(
                    if (showAllEvents) "Nothing recorded yet."
                    else "No warnings or errors recorded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            } else {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        events.forEach { e ->
                            Text(
                                e.format(),
                                style = MaterialTheme.typography.labelSmall,
                                color = when (e.level) {
                                    EventLog.Level.ERROR -> MaterialTheme.colorScheme.error
                                    EventLog.Level.WARN -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
            }
            Row(Modifier.padding(horizontal = 12.dp)) {
                TextButton(onClick = { statsTick++ }) { Text("Refresh") }
                TextButton(onClick = { EventLog.clear(); statsTick++ }) { Text("Clear log") }
            }

            // ---- crash reports ----
            Text(
                "Crash reports",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp)
            )
            if (crashes.isEmpty()) {
                Text(
                    "No crashes recorded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            } else {
                crashes.forEach { f ->
                    Card(
                        onClick = { viewingCrash = runCatching { f.readText() }.getOrNull() },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(f.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                runCatching {
                                    f.readText().lineSequence().firstOrNull {
                                        it.contains("Exception") || it.contains("Error")
                                    }?.trim().orEmpty()
                                }.getOrDefault(""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                TextButton(
                    onClick = { CrashLog.clear(context); crashes = CrashLog.recent(context) },
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) { Text("Clear crash reports") }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader("Companion files")

            Text(
                "Handwriting is stored inside your documents now, so there is nothing to keep " +
                    "beside them. Any .inkdoc files left over from an earlier version can be " +
                    "folded into their documents and removed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            val found = legacy
            when {
                absorbing -> Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        "  Folding in $absorbed of ${found?.size ?: 0}...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                found == null -> TextButton(
                    onClick = {
                        scope.launch {
                            legacy = withContext(Dispatchers.IO) {
                                DocumentRepo(context)
                                    .legacySidecars(FileRepo(context).libraryFolders())
                            }
                        }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) { Text("Check for leftover companion files") }

                found.isEmpty() -> Text(
                    if (absorbed > 0)
                        "Done. $absorbed document(s) now carry their own handwriting" +
                            (if (absorbFailed > 0) ", $absorbFailed could not be changed." else ".")
                    else "None found. Nothing to tidy up.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )

                else -> Column {
                    Text(
                        "${found.size} document(s) still have one.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 10.dp)
                    )
                    TextButton(
                        onClick = { confirmAbsorb = true },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) { Text("Fold them into the documents") }
                }
            }

            Text(
                "Logs are also written to Android/data/com.inkslate/files/crash-logs.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            UpdateSection()

            Box(Modifier.padding(24.dp))
        }
    }

    if (confirmAbsorb) {
        val count = legacy?.size ?: 0
        AlertDialog(
            onDismissRequest = { confirmAbsorb = false },
            title = { Text("Fold in $count companion file(s)?") },
            text = {
                Text(
                    "Each document will be rewritten to carry its own handwriting, and its " +
                        "companion file removed.\n\n" +
                        "The handwriting is copied into the document " +
                        "and read back before anything is deleted, and a copy is kept in the " +
                        "app's history first. Any document that cannot be rewritten is left " +
                        "exactly as it is."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmAbsorb = false
                    val targets = legacy.orEmpty()
                    scope.launch {
                        absorbing = true
                        absorbed = 0
                        absorbFailed = 0
                        val repo = DocumentRepo(context)
                        for (f in targets) {
                            val ok = withContext(Dispatchers.IO) { repo.absorbSidecar(f) }
                            if (ok.isSuccess) absorbed++ else absorbFailed++
                        }
                        legacy = withContext(Dispatchers.IO) {
                            repo.legacySidecars(FileRepo(context).libraryFolders())
                        }
                        absorbing = false
                    }
                }) { Text("Fold them in") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAbsorb = false }) { Text("Cancel") }
            }
        )
    }

    viewingCrash?.let { text ->
        AlertDialog(
            onDismissRequest = { viewingCrash = null },
            title = { Text("Crash report") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(text, style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = { TextButton(onClick = { viewingCrash = null }) { Text("Close") } }
        )
    }
}

// ---- pieces ------------------------------------------------------------------

// Shared with UpdateSection.kt so every heading on this screen is styled once.
@Composable
internal fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp)
    )
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    current: String,
    options: List<Pair<String, T>>,
    subtitle: String? = null,
    onPick: (T) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            TextButton(onClick = { open = true }) { Text(current) }
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { (label, value) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onPick(value); open = false }
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
