package com.inkslate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
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
import com.inkslate.ui.editor.LocalToolState
import com.inkslate.ui.editor.ToolState
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

/**
 * The application: a row of tabs across the top, each an open document, plus a pinned Home tab for
 * finding the next one.
 *
 * Below the tabs is one workspace, not one screen per document. There is a single set of bars -
 * the document's app bar above, the page bar and the tools below - and they belong to whichever
 * document was last touched. Between them sit one pane, or two on a screen wide enough, and each
 * shows a view of a document: two different documents side by side, or the same one twice, each
 * half with its own place and zoom over the one set of marks.
 *
 * Every open document stays open behind its tab - its marks, its undo history and its link to your
 * other devices - so coming back to one does not read it from storage again. Only the drawing
 * surfaces of documents off screen are let go, and they pick up where they were looking.
 */
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

    // ---- the workspace -------------------------------------------------------------

    // One set of tools for everything open, handed down to every document and to Settings.
    val tools = remember { ToolState(context) }
    // Focus mode hides the system bars, which are the whole screen's, so there is one of it.
    val immersive = rememberImmersive()

    val tabs = remember { mutableStateListOf<DocTab>() }
    var homeShown by remember { mutableStateOf(true) }
    var primary by remember { mutableStateOf<PaneRef?>(null) }
    var secondary by remember { mutableStateOf<PaneRef?>(null) }
    var focusedPane by remember { mutableStateOf(Pane.PRIMARY) }
    var splitRatio by remember { mutableStateOf(0.5f) }

    fun tabOf(id: String?): DocTab? = tabs.firstOrNull { it.id == id }

    fun closeTab(id: String) {
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        val closing = tabs.removeAt(idx)
        if (tools.rulerOwner === closing.host) {
            tools.rulerVisible = false
            tools.rulerOwner = null
        }
        val wasPrimary = primary?.tabId == id
        val wasSecondary = secondary?.tabId == id
        if (wasSecondary) secondary = null
        if (wasPrimary) {
            primary = secondary ?: tabs.getOrNull(idx.coerceAtMost(tabs.lastIndex))?.let { PaneRef(it.id, 0) }
            secondary = null
        }
        if (wasPrimary || wasSecondary) focusedPane = Pane.PRIMARY
        if (tabs.isEmpty()) {
            primary = null
            secondary = null
            homeShown = true
        }
    }

    /** Put [id] in front of you: move to it where it already is, or show it in the pane in use. */
    fun selectTab(id: String) {
        homeShown = false
        when {
            primary?.tabId == id -> focusedPane = Pane.PRIMARY
            secondary?.tabId == id -> focusedPane = Pane.SECONDARY
            secondary != null && focusedPane == Pane.SECONDARY -> secondary = PaneRef(id, 0)
            else -> {
                primary = PaneRef(id, 0)
                focusedPane = Pane.PRIMARY
            }
        }
    }

    fun openFile(f: File) {
        val existing = tabs.firstOrNull { it.file.absolutePath == f.absolutePath }
        val id = if (existing != null) {
            existing.id
        } else {
            // A homework annotator is not a browser: a handful of worksheets open at once is the
            // real case, and each one holds its pages and its own undo history. The oldest
            // background tab makes room rather than letting every tab ever opened pile up in
            // memory - it is closed the same safe way its own tab button would, so nothing is
            // lost, just no longer kept open.
            if (tabs.size >= MAX_OPEN_TABS) {
                tabs.firstOrNull { it.id != primary?.tabId && it.id != secondary?.tabId }
                    ?.closeRequested?.value = true
            }
            val tab = DocTab(
                id = "${f.absolutePath}#${System.nanoTime()}",
                file = f,
                host = DocumentHost(immersive)
            )
            tabs.add(tab)
            tab.id
        }
        selectTab(id)
    }

    /**
     * Show [id] in the other half. The document already in front gets a second view of itself -
     * two places in one document, both written into the same marks.
     */
    fun openInSplit(id: String) {
        homeShown = false
        val p = primary
        if (p == null) {
            primary = PaneRef(id, 0)
            focusedPane = Pane.PRIMARY
            return
        }
        if (secondary?.tabId == id) {
            focusedPane = Pane.SECONDARY
            return
        }
        val view = if (p.tabId == id) (if (p.view == 0) 1 else 0) else 0
        tabOf(id)?.host?.ensureView(view)
        secondary = PaneRef(id, view)
        focusedPane = Pane.SECONDARY
    }

    fun closeSplit() {
        secondary = null
        focusedPane = Pane.PRIMARY
    }

    fun closeOthers(id: String) {
        tabs.filter { it.id != id }.forEach { it.closeRequested.value = true }
    }

    fun closeAll() {
        tabs.forEach { it.closeRequested.value = true }
    }

    // Focus mode is for the page. Home comes back with the system bars, as leaving the editor
    // always brought them back.
    LaunchedEffect(homeShown) {
        if (homeShown && immersive.isFullscreen) immersive.set(false)
    }

    // A narrow screen - the tablet turned to portrait, the app in a split-screen window - has no
    // room for two panes. The document being worked in keeps the screen.
    LaunchedEffect(splitAvailable) {
        if (!splitAvailable && secondary != null) {
            if (focusedPane == Pane.SECONDARY) primary = secondary
            secondary = null
            focusedPane = Pane.PRIMARY
        }
    }

    val primaryTab = if (homeShown) null else tabOf(primary?.tabId)
    val secondaryTab = if (homeShown || primaryTab == null || !splitAvailable) null else tabOf(secondary?.tabId)
    val focusedRef = when {
        homeShown -> null
        focusedPane == Pane.SECONDARY && secondaryTab != null -> secondary
        else -> primary
    }
    val focusedTab = tabOf(focusedRef?.tabId)

    // Which of each document's views its bars follow: the one on screen, and for the document
    // being worked in, the one last touched.
    SideEffect {
        val p = primary
        val s = secondary
        if (secondaryTab != null && s != null) secondaryTab.host.activeViewIndex = s.view
        if (primaryTab != null && p != null) primaryTab.host.activeViewIndex = p.view
        if (focusedTab != null && focusedRef != null) focusedTab.host.activeViewIndex = focusedRef.view
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
        // screen implies. The document being worked in handles its own back press so unsaved work
        // can be caught; from Home itself, back does whatever leaving the app normally does.
        BackHandler(enabled = homeShown && screen !is Screen.Home) {
            screen = Screen.Home
            refreshKey++
        }

        CompositionLocalProvider(LocalToolState provides tools) {
            Column(Modifier.fillMaxSize()) {
                // Focus mode is "just the page and the tools": the tabs go along with the app bar.
                if (tabs.isNotEmpty() && !(immersive.isFullscreen && !homeShown)) {
                    TabStrip(
                        tabs = tabs,
                        homeShown = homeShown,
                        shownIds = setOfNotNull(primaryTab?.id, secondaryTab?.id),
                        focusedId = focusedTab?.id,
                        primaryId = primaryTab?.id,
                        secondaryId = secondaryTab?.id,
                        splitAvailable = splitAvailable,
                        onHome = { homeShown = true },
                        onSelect = ::selectTab,
                        onOpenInSplit = ::openInSplit,
                        onCloseSplit = ::closeSplit,
                        onCloseTab = { id -> tabOf(id)?.closeRequested?.value = true },
                        onCloseOthers = ::closeOthers,
                        onCloseAll = ::closeAll,
                        onNewTab = { homeShown = true; screen = Screen.Home }
                    )
                }

                if (focusedTab != null) HostBar(focusedTab.host) { it.topBar }

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
                        } else if (primaryTab != null) {
                            val p = primary!!
                            val s = secondary
                            if (secondaryTab != null && s != null) {
                                val ratio = splitRatio.coerceIn(0.15f, 0.85f)
                                Row(Modifier.fillMaxSize()) {
                                    PaneFrame(
                                        focused = focusedPane == Pane.PRIMARY,
                                        onFocus = { focusedPane = Pane.PRIMARY },
                                        modifier = Modifier.weight(ratio).fillMaxHeight()
                                    ) {
                                        HostPane(
                                            primaryTab.host, primaryTab.host.viewOrFirst(p.view),
                                            focusedPane == Pane.PRIMARY
                                        )
                                    }
                                    SplitDivider(
                                        onDrag = { deltaPx ->
                                            splitRatio = (splitRatio + deltaPx / 1200f).coerceIn(0.15f, 0.85f)
                                        },
                                        onClose = ::closeSplit
                                    )
                                    PaneFrame(
                                        focused = focusedPane == Pane.SECONDARY,
                                        onFocus = { focusedPane = Pane.SECONDARY },
                                        modifier = Modifier.weight(1f - ratio).fillMaxHeight()
                                    ) {
                                        HostPane(
                                            secondaryTab.host, secondaryTab.host.viewOrFirst(s.view),
                                            focusedPane == Pane.SECONDARY
                                        )
                                    }
                                }
                            } else {
                                Box(Modifier.fillMaxSize().clipToBounds()) {
                                    HostPane(primaryTab.host, primaryTab.host.viewOrFirst(p.view), true)
                                }
                            }
                        }

                        // The bars are gone in focus mode, so this is the only way back out.
                        if (immersive.isFullscreen && !homeShown) {
                            FilledTonalIconButton(
                                onClick = { immersive.set(false) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .statusBarsPadding()
                                    .padding(10.dp)
                            ) { Icon(Icons.Default.FullscreenExit, "Leave fullscreen") }
                        }
                    }
                }

                if (focusedTab != null) HostBar(focusedTab.host) { it.bottomBar }
            }

            // Every open document, whether or not any of it is on screen. Each keeps its marks,
            // its undo history and its link to your other devices here for as long as its tab is
            // open; what it shows is drawn above, through its host.
            Box(Modifier.size(0.dp)) {
                for (tab in tabs) {
                    key(tab.id) {
                        EditorScreen(
                            file = tab.file,
                            onClose = { closeTab(tab.id) },
                            host = tab.host,
                            focused = tab === focusedTab,
                            closeRequested = tab.closeRequested
                        )
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
 * One half of a split. Marked when it is the half the bars belong to, and moves them here when
 * touched - without taking the touch from whatever is underneath.
 */
@Composable
private fun PaneFrame(
    focused: Boolean,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier
            .observeFocus(onFocus)
            .border(2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent)
            .padding(2.dp)
            // Each half draws inside itself and nowhere else - a page pushed off one edge of its
            // half goes out of sight, not across the divider onto the other document.
            .clipToBounds()
    ) { content() }
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
