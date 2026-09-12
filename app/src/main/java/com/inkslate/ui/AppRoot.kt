package com.inkslate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.inkslate.data.FileRepo
import com.inkslate.pdf.BlankDocumentFactory
import com.inkslate.ui.browser.BrowserScreen
import com.inkslate.data.DocumentRepo
import com.inkslate.ui.browser.NewDocumentDialog
import com.inkslate.ui.editor.EditorScreen
import com.inkslate.ui.home.HomeScreen
import com.inkslate.ui.settings.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Where the app currently is. Plain state; the graph is small enough not to need more. */
private sealed interface Screen {
    data object Home : Screen
    data class Browser(val dir: String?) : Screen
    data class Editor(val path: String) : Screen
    data object Settings : Screen
}

@Composable
fun AppRoot(
    storageGranted: Boolean,
    onRequestStorage: () -> Unit,
    openRequest: File?,
    onOpenHandled: () -> Unit
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var refreshKey by remember { mutableStateOf(0) }
    var newDocOpen by remember { mutableStateOf(false) }

    // A device saying it has written something is worth a look: the file itself arrives by the
    // ordinary sync, but this is what makes the shelf show it now rather than on the next visit.
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.inkslate.data.AppPeers.onRemoteWrite { _, _ -> scope.launch { refreshKey++ } }
        onDispose { com.inkslate.data.AppPeers.onRemoteWrite(null) }
    }

    // an Open-with from another app jumps straight into the editor
    LaunchedEffect(openRequest) {
        openRequest?.let {
            screen = Screen.Editor(it.absolutePath)
            onOpenHandled()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (!storageGranted) {
            StorageGate(onRequestStorage)
            return@Surface
        }

        // Back always walks toward Home rather than out of the app, which is what a landing
        // screen implies. The editor handles its own back press so unsaved work can be caught.
        BackHandler(enabled = screen !is Screen.Home && screen !is Screen.Editor) {
            screen = Screen.Home
            refreshKey++
        }

        when (val s = screen) {
            Screen.Home -> HomeScreen(
                onOpenFile = { screen = Screen.Editor(it.absolutePath) },
                onOpenFolder = { screen = Screen.Browser(it.absolutePath) },
                onBrowse = { screen = Screen.Browser(null) },
                onOpenSettings = { screen = Screen.Settings },
                onNewDocument = { newDocOpen = true },
                refreshKey = refreshKey
            )

            is Screen.Browser -> BrowserScreen(
                startDir = s.dir?.let(::File),
                onOpenFile = { screen = Screen.Editor(it.absolutePath) },
                onOpenSettings = { screen = Screen.Settings },
                onHome = { screen = Screen.Home; refreshKey++ },
                onDirChanged = { screen = Screen.Browser(it.absolutePath) }
            )

            is Screen.Editor -> EditorScreen(
                file = File(s.path),
                onClose = {
                    refreshKey++
                    // back to wherever makes sense: the containing folder if it is one we were
                    // browsing, otherwise Home
                    screen = Screen.Home
                }
            )

            Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Home })
        }
    }

    if (newDocOpen) {
        val repo = remember { FileRepo(context) }
        NewDocumentDialog(
            onDismiss = { newDocOpen = false },
            onCreate = { spec ->
                newDocOpen = false
                scope.launch {
                    // Wherever the user nominated by long-pressing a folder, falling back to
                    // the first added folder so "New" never has to ask.
                    val target = repo.defaultNewFolder
                        ?: File(
                            android.os.Environment.getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_DOCUMENTS
                            ),
                            "InkSlate"
                        )
                    val created = withContext(Dispatchers.IO) {
                        BlankDocumentFactory.create(target, spec).onSuccess { f ->
                            if (spec.autoGrow) {
                                DocumentRepo(context).markAsCanvas(
                                    file = f,
                                    width = spec.pageSize.width,
                                    height = spec.pageSize.height,
                                    background = spec.background.name,
                                    paperColor = spec.paperColor,
                                    lineColor = spec.lineColor,
                                    spacing = spec.spacing
                                )
                            }
                        }
                    }
                    created.getOrNull()?.let { f ->
                        repo.noteOpened(f)
                        refreshKey++
                        screen = Screen.Editor(f.absolutePath)
                    }
                }
            }
        )
    }
}

/**
 * All-files access has to be granted from a Settings screen, so this explains why before sending
 * the user there. A permission prompt with no explanation gets denied.
 */
@Composable
private fun StorageGate(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text("File access needed", style = MaterialTheme.typography.headlineSmall)
        Text(
            "InkSlate browses your storage directly so it can open assignments from any folder " +
                "and save them straight back, with no copying in and out.\n\n" +
                "Android asks for this on its own Settings screen. Turn on \"Allow access to " +
                "manage all files\", then come back.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onRequest) { Text("Open Settings") }
    }
}
