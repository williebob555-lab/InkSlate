package com.inkslate.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where the "Home tab" currently is - Home, a folder, or Settings. Plain state; the graph is small
 * enough not to need more.
 */
private sealed interface Screen {
    data object Home : Screen
    data class Browser(val dir: String?) : Screen
    data object Settings : Screen
}

/**
 * How many documents may be open as tabs at once before the oldest is closed to make room. A
 * desktop machine has more headroom than a tablet, but each tab still holds a page renderer and
 * its own undo history, and unbounded tabs is how a program ends up needing to be restarted.
 */
private const val MAX_OPEN_TABS = 16

/**
 * The application: a row of tabs across the top, each an open document, plus a pinned Home tab for
 * finding the next one. At most two documents are ever actually laid out at once - single, or side
 * by side in a split - but every open tab is kept mounted off-screen the rest of the time, which is
 * what makes switching back to one instant rather than a reload.
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

    // ---- the workspace: tabs, and what is on screen right now -------------------------

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
        repo.noteOpened(f)
        val existing = tabs.firstOrNull { it.file.absolutePath == f.absolutePath }
        activeTabId = if (existing != null) {
            existing.id
        } else {
            // Closed the same safe way its own tab button would, so nothing is lost - just no
            // longer kept warm in memory.
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
            // place, so a click always means "put this one where I'm looking."
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

    // Only the editor keeps these; everywhere else they would fire into nothing. Cleared here on
    // every composition so a screen that does not set them cannot inherit the last one's.
    shortcuts.clear()

    // A device saying it has written something is worth a look: the file itself arrives by the
    // ordinary sync, but this is what makes the shelf show it now rather than on the next visit.
    DisposableEffect(Unit) {
        DesktopPeers.start()
        DesktopPeers.onRemoteWrite { _, _ -> scope.launch { refreshKey++ } }
        onDispose { DesktopPeers.onRemoteWrite(null) }
    }

    // Focus mode is "just the page and the tools" - in single-pane view the tab strip is chrome
    // too, and gets out of the way along with the document's own toolbar. Split view is already a
    // multitasking layout, so it keeps its tabs regardless.
    val activeImmersive = splitTabId == null &&
        tabs.firstOrNull { it.id == activeTabId }?.immersive?.value == true

    Column(Modifier.fillMaxSize()) {
        if (tabs.isNotEmpty() && !(!homeShown && activeImmersive)) {
            TabStrip(
                tabs = tabs,
                homeShown = homeShown,
                activeTabId = activeTabId,
                splitTabId = splitTabId,
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

                        Screen.Settings -> SettingsScreen(
                            onBack = { screen = Screen.Home },
                            navigation = navigation
                        )
                    }
                }

                // Build each tab's editor once, the first time it is seen.
                for (tab in tabs) {
                    if (tab.id in editors) continue
                    editors[tab.id] = movableContentOf { focused: Boolean ->
                        EditorScreen(
                            file = tab.file,
                            shortcuts = shortcuts,
                            navigation = navigation,
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
                                    // A resolution-independent nudge is close enough here - this is
                                    // a hand on a mouse, not something that needs to track the
                                    // pointer pixel for pixel.
                                    splitRatio = (splitRatio + deltaPx / 1600f).coerceIn(0.15f, 0.85f)
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

                // Every other open tab is kept alive off-screen, so coming back to it does not
                // reopen the file or lose the undo history.
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
                                        spec.spacing,
                                        // This program ruled the page, so it can rule the rest of
                                        // the canvas to match and show no seam between them.
                                        ownPaper = true
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
                        openFile(f)
                    }
                }
            }
        )
    }
}
