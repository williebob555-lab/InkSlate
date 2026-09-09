package com.inkslate.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where the app currently is. Plain state; the graph is small enough not to need more - and it is
 * the same four screens the Android build has, deliberately.
 */
private sealed interface Screen {
    data object Home : Screen
    data class Browser(val dir: String?) : Screen
    data class Editor(val path: String) : Screen
    data object Settings : Screen
}

/**
 * The application, one screen at a time.
 *
 * The counterpart of Android's `AppRoot`, minus the permission gate: Windows lets an application
 * read the user's own documents without being asked, so there is no "All files access" screen to
 * stand in front of Home.
 */
@Composable
fun AppRoot(shortcuts: Shortcuts, navigation: NavigationHooks) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var refreshKey by remember { mutableStateOf(0) }
    var newDocOpen by remember { mutableStateOf(false) }
    val repo = remember { FileRepo() }
    val scope = rememberCoroutineScope()

    fun openFile(f: File) {
        repo.noteOpened(f)
        screen = Screen.Editor(f.absolutePath)
    }

    // Only the editor keeps these; everywhere else they would fire into nothing. Cleared here on
    // every composition so a screen that does not set them cannot inherit the last one's.
    shortcuts.clear()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val s = screen) {
            Screen.Home -> HomeScreen(
                onOpenFile = ::openFile,
                onBrowse = { screen = Screen.Browser(null) },
                onOpenSettings = { screen = Screen.Settings },
                onNewDocument = { newDocOpen = true },
                refreshKey = refreshKey,
                navigation = navigation
            )

            is Screen.Browser -> BrowserScreen(
                startDir = s.dir?.let(::File),
                onOpenFile = ::openFile,
                onOpenSettings = { screen = Screen.Settings },
                onHome = { screen = Screen.Home; refreshKey++ },
                onDirChanged = { screen = Screen.Browser(it.absolutePath) },
                navigation = navigation
            )

            is Screen.Editor -> EditorScreen(
                file = File(s.path),
                shortcuts = shortcuts,
                navigation = navigation,
                onClose = {
                    refreshKey++
                    screen = Screen.Home
                }
            )

            Screen.Settings -> SettingsScreen(
                onBack = { screen = Screen.Home },
                navigation = navigation
            )
        }
    }

    if (newDocOpen) {
        NewDocumentDialog(
            onDismiss = { newDocOpen = false },
            onCreate = { spec ->
                newDocOpen = false
                scope.launch {
                    // Wherever the user nominated by right-clicking a folder, falling back to the
                    // first folder on Home so "New" never has to ask. With nothing on Home at all
                    // there is nowhere it could mean, so it goes to Documents\InkSlate - the
                    // Android build's fallback, one shell folder along.
                    val target = repo.defaultNewFolder ?: File(
                        File(System.getProperty("user.home"), "Documents"), "InkSlate"
                    )
                    val created = withContext(Dispatchers.IO) {
                        BlankDocumentFactory.create(target, spec).onSuccess { f ->
                            // Written into the document rather than kept in a local setting: it
                            // has to travel with the file, or the same whiteboard would be a
                            // fixed page on the tablet.
                            if (spec.autoGrow) {
                                val ink = com.inkslate.core.InkDocument.create(
                                    sourceName = f.name,
                                    kind = "pdf",
                                    pageCount = 1,
                                    sizeBytes = f.length(),
                                    fingerprint = DesktopSources.fingerprint(f)
                                ).copy(
                                    canvas = com.inkslate.core.InkCanvas.startingAt(
                                        spec.pageSize.width,
                                        spec.pageSize.height,
                                        spec.background.name,
                                        spec.paperColor,
                                        spec.lineColor,
                                        spec.spacing
                                    )
                                )
                                DocumentIO.saveWorking(f, ink)
                                DesktopEmbedder.write(f, ink)
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
