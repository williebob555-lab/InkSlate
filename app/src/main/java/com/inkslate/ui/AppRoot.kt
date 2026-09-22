package com.inkslate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
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

/** Where the "Home tab" currently is - Home, a folder, or Settings. */
private sealed interface Screen {
    data object Home : Screen
    data class Browser(val dir: String?) : Screen
    data object Settings : Screen
}

/** How many documents may be open as tabs at once before the oldest is closed to make room. */
private const val MAX_OPEN_TABS = 8

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

    // A screen wide enough to show a side panel is wide enough to show a second document -
    // the same 840dp the editor already uses to decide whether a panel can sit beside the page
    // instead of over it.
    val splitAvailable = LocalConfiguration.current.screenWidthDp >= 840

    val tabs = remember { mutableStateListOf<DocTab>() }
    var homeShown by remember { mutableStateOf(true) }
    var activeTabId by remember { mutableStateOf<String?>(null) }
    var splitTabId by remember { mutableStateOf<String?>(null) }
    var focusedPane by remember { mutableStateOf(Pane.PRIMARY) }
    var splitRatio by remember { mutableStateOf(0.5f) }
    // Every open tab's editor, built once and kept for as long as the tab is open - moving where
    // one is drawn (primary pane, split pane, or off-screen) does not restart it. See
    // androidx.compose.runtime.movableContentOf.
    val editors = remember { mutableStateMapOf<String, @Composable (Boolean) -> Unit>() }

    fun closeTab(id: String) {
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        tabs.removeAt(idx)
        editors.remove(id)
        if (activeTabId == id) {
            activeTabId = splitTabId ?: tabs.getOrNull(idx.coerceAtMost(tabs.lastIndex))?.id
            if (splitTabId == id) splitTabId = null
            focusedPane = Pane.PRIMARY
        } else if (splitTabId == id) {
            splitTabId = null
            if (focusedPane == Pane.SECONDARY) focusedPane = Pane.PRIMARY
        }
        if (tabs.isEmpty()) {
            activeTabId = null
            homeShown = true
        }
    }

    fun openFile(f: File) {
        val existing = tabs.firstOrNull { it.file.absolutePath == f.absolutePath }
        activeTabId = if (existing != null) {
            existing.id
        } else {
            // A homework annotator is not a browser: a handful of worksheets open at once is the
            // real case, and each one holds a page renderer and its own undo history. The oldest
            // background tab makes room rather than letting every tab ever opened pile up in
            // memory - it is closed the same safe way its own tab button would, so nothing is
            // lost, just no longer kept warm.
            if (tabs.size >= MAX_OPEN_TABS) {
                tabs.firstOrNull { it.id != activeTabId && it.id != splitTabId }
                    ?.closeRequested?.value = true
            }
            val tab = DocTab(id = "${f.absolutePath}#${System.nanoTime()}", file = f)
            tabs.add(tab)
            tab.id
        }
        if (splitTabId == activeTabId) splitTabId = null
        focusedPane = Pane.PRIMARY
        homeShown = false
    }

    fun selectTab(id: String) {
        if (id == splitTabId) {
            // The one already on the other side becomes primary; whatever was primary takes its
            // place, so a tap always means "put this one where I'm looking."
            val previousPrimary = activeTabId
            activeTabId = id
            splitTabId = previousPrimary
        } else {
            activeTabId = id
        }
        focusedPane = Pane.PRIMARY
        homeShown = false
    }

    fun openInSplit(id: String) {
        if (id == activeTabId) return
        if (activeTabId == null) {
            activeTabId = id
        } else {
            splitTabId = id
            focusedPane = Pane.SECONDARY
        }
        homeShown = false
    }

    fun closeSplit() {
        splitTabId = null
        focusedPane = Pane.PRIMARY
    }

    fun closeOthers(id: String) {
        tabs.filter { it.id != id }.forEach { it.closeRequested.value = true }
    }

    fun closeAll() {
        tabs.forEach { it.closeRequested.value = true }
    }

    // A device saying it has written something is worth a look: the file itself arrives by the
    // ordinary sync, but this is what makes the shelf show it now rather than on the next visit.
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.inkslate.data.AppPeers.onRemoteWrite { _, _ -> scope.launch { refreshKey++ } }
        onDispose { com.inkslate.data.AppPeers.onRemoteWrite(null) }
    }

    // an Open-with from another app jumps straight into the editor, as its own tab
    LaunchedEffect(openRequest) {
        openRequest?.let {
            openFile(it)
            onOpenHandled()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (!storageGranted) {
            StorageGate(onRequestStorage)
            return@Surface
        }

        // Back always walks toward Home rather than out of the app, which is what a landing
        // screen implies. A focused document handles its own back press so unsaved work can be
        // caught; from Home itself, back does whatever leaving the app normally does.
        BackHandler(enabled = homeShown && screen !is Screen.Home) {
            screen = Screen.Home
            refreshKey++
        }

        // Focus mode is "just the page and the tools" - in single-pane view the tab strip is
        // chrome too, and gets out of the way along with the document's own toolbar. Split view
        // is already a multitasking layout, so it keeps its tabs regardless.
        val activeImmersive = splitTabId == null &&
            tabs.firstOrNull { it.id == activeTabId }?.immersive?.value == true

        Column(Modifier.fillMaxSize()) {
            if (tabs.isNotEmpty() && !(!homeShown && activeImmersive)) {
                TabStrip(
                    tabs = tabs,
                    homeShown = homeShown,
                    activeTabId = activeTabId,
                    splitTabId = splitTabId,
                    splitAvailable = splitAvailable,
                    onHome = { homeShown = true },
                    onSelect = ::selectTab,
                    onOpenInSplit = ::openInSplit,
                    onCloseSplit = ::closeSplit,
                    onCloseTab = { id -> tabs.firstOrNull { it.id == id }?.closeRequested?.value = true },
                    onCloseOthers = ::closeOthers,
                    onCloseAll = ::closeAll,
                    onNewTab = { homeShown = true; screen = Screen.Home }
                )
            }

            Surface(Modifier.weight(1f).fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize()) {
                    if (homeShown) {
                        when (val s = screen) {
                            Screen.Home -> HomeScreen(
                                onOpenFile = ::openFile,
                                onOpenFolder = { screen = Screen.Browser(it.absolutePath) },
                                onBrowse = { screen = Screen.Browser(null) },
                                onOpenSettings = { screen = Screen.Settings },
                                onNewDocument = { newDocOpen = true },
                                refreshKey = refreshKey
                            )

                            is Screen.Browser -> BrowserScreen(
                                startDir = s.dir?.let(::File),
                                onOpenFile = ::openFile,
                                onOpenSettings = { screen = Screen.Settings },
                                onHome = { screen = Screen.Home; refreshKey++ },
                                onDirChanged = { screen = Screen.Browser(it.absolutePath) }
                            )

                            Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Home })
                        }
                    }

                    // Build each tab's editor once, the first time it is seen.
                    for (tab in tabs) {
                        if (tab.id in editors) continue
                        editors[tab.id] = movableContentOf { focused: Boolean ->
                            EditorScreen(
                                file = tab.file,
                                focused = focused,
                                closeRequested = tab.closeRequested,
                                immersiveState = tab.immersive,
                                onClose = { closeTab(tab.id) }
                            )
                        }
                    }

                    if (!homeShown && activeTabId != null) {
                        if (splitTabId != null) {
                            Row(Modifier.fillMaxSize()) {
                                Box(
                                    Modifier.weight(splitRatio.coerceIn(0.15f, 0.85f)).fillMaxHeight()
                                        .observeFocus { focusedPane = Pane.PRIMARY }
                                ) { editors[activeTabId]?.invoke(focusedPane == Pane.PRIMARY) }
                                SplitDivider(
                                    onDrag = { deltaPx ->
                                        splitRatio = (splitRatio + deltaPx / 1200f).coerceIn(0.15f, 0.85f)
                                    },
                                    onClose = ::closeSplit
                                )
                                Box(
                                    Modifier.weight(1f - splitRatio.coerceIn(0.15f, 0.85f)).fillMaxHeight()
                                        .observeFocus { focusedPane = Pane.SECONDARY }
                                ) { editors[splitTabId]?.invoke(focusedPane == Pane.SECONDARY) }
                            }
                        } else {
                            Box(Modifier.fillMaxSize()) { editors[activeTabId]?.invoke(true) }
                        }
                    }

                    // Every other open tab is kept alive off-screen, so coming back to it does
                    // not reopen the file or lose the undo history.
                    for (tab in tabs) {
                        val visible = !homeShown && (tab.id == activeTabId || tab.id == splitTabId)
                        if (visible) continue
                        key(tab.id) {
                            Box(Modifier.size(0.dp)) { editors[tab.id]?.invoke(false) }
                        }
                    }
                }
            }
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
                        openFile(f)
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
