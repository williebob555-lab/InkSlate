package com.inkslate.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
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
 * desktop machine has more headroom than a tablet, but each tab still holds a document's pages and
 * its own undo history, and unbounded tabs is how a program ends up needing to be restarted.
 */
private const val MAX_OPEN_TABS = 16

/**
 * The application: a row of tabs across the top, each an open document, plus a pinned Home tab for
 * finding the next one.
 *
 * Below the tabs is one workspace, not one window per document. There is a single set of bars -
 * the document's app bar above, the page bar and the tools below - and they belong to whichever
 * document was last touched. Between them sit one or two panes, and each shows a view of a
 * document: two different documents side by side, or the same one twice, each half with its own
 * place and zoom over the one set of marks.
 *
 * Every open document stays open behind its tab, so coming back to one does not read it from disk
 * again or lose its undo history.
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

    // ---- the workspace -------------------------------------------------------------

    // One set of tools for everything open, handed down to every document and to Settings.
    val tools = remember { ToolState() }
    val immersiveState = remember { mutableStateOf(false) }
    var immersive by immersiveState

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

    /** Put [id] in front of you: focus it where it already is, or show it in the focused pane. */
    fun selectTab(id: String) {
        homeShown = false
        tabOf(id)?.loaded = true
        // A setlist's next and previous songs are read now, while this one is played, so turning
        // to them shows them at once rather than after a moment of loading.
        if (AppFlavor.musicView) {
            val at = tabs.indexOfFirst { it.id == id }
            listOf(at + 1, at - 1).mapNotNull { tabs.getOrNull(it) }.filter { it.title != null && !it.closeRequested.value }
                .forEach { it.loaded = true }
        }
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
        repo.noteOpened(f)
        val existing = tabs.firstOrNull { it.file.absolutePath == f.absolutePath }
        val id = if (existing != null) {
            existing.id
        } else {
            // Closed the same safe way its own tab button would, so nothing is lost - just no
            // longer kept open.
            if (tabs.count { it.loaded } >= MAX_OPEN_TABS) {
                tabs.firstOrNull { it.loaded && it.title == null && it.id != primary?.tabId && it.id != secondary?.tabId }
                    ?.closeRequested?.value = true
            }
            val tab = DocTab(
                id = "${f.absolutePath}#${System.nanoTime()}",
                file = f,
                host = DocumentHost(immersiveState),
                // A document opened while in focus mode joins it.
                fullscreen = immersive || AppFlavor.musicView
            )
            tabs.add(tab)
            tab.id
        }
        selectTab(id)
    }

    /**
     * A setlist as tabs: one per song in set order, named by song, read only when first shown.
     * Tabs from anything else are put away (saved and closed), so the row is the set.
     */
    fun openSet(parts: List<Pair<File, String>>, focus: Int) {
        val wanted = parts.map { it.first.absolutePath }.toSet()
        tabs.filter { it.file.absolutePath !in wanted }.forEach { it.closeRequested.value = true }
        val ordered = ArrayList<DocTab>()
        var front: String? = null
        parts.forEachIndexed { i, (f, title) ->
            val tab = ordered.firstOrNull { it.file.absolutePath == f.absolutePath }
                ?: tabs.firstOrNull { it.file.absolutePath == f.absolutePath && !it.closeRequested.value }
                ?: DocTab(
                    id = "${f.absolutePath}#${System.nanoTime()}",
                    file = f,
                    host = DocumentHost(immersiveState),
                    fullscreen = immersive || AppFlavor.musicView,
                    title = title,
                    loaded = false
                ).also { tabs.add(it) }
            tab.title = title
            if (tab !in ordered) ordered += tab
            if (i == focus) front = tab.id
        }
        // In set order, ahead of anything still on its way out.
        tabs.removeAll(ordered)
        tabs.addAll(0, ordered)
        front?.let(::selectTab)
    }

    /** [old]'s tab gives way to [new], in its place in the row - another part of the same song. */
    fun swapTab(old: File, new: File) {
        val was = tabs.indexOfFirst { it.file.absolutePath == old.absolutePath && !it.closeRequested.value }
        openFile(new)
        val fresh = tabs.indexOfFirst { it.file.absolutePath == new.absolutePath && !it.closeRequested.value }
        if (was >= 0 && fresh > was) tabs.add(was, tabs.removeAt(fresh))
        tabs.filter { it.file.absolutePath == old.absolutePath }.forEach { it.closeRequested.value = true }
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

    val primaryTab = if (homeShown) null else tabOf(primary?.tabId)
    val secondaryTab = if (homeShown || primaryTab == null) null else tabOf(secondary?.tabId)
    val focusedRef = when {
        homeShown -> null
        focusedPane == Pane.SECONDARY && secondaryTab != null -> secondary
        else -> primary
    }
    val focusedTab = tabOf(focusedRef?.tabId)
    AppFlavor.openSet = ::openSet
    AppFlavor.swapTab = ::swapTab
    AppFlavor.closeSet = { closeAll() }

    // With no document in front, a pedal has no page to turn; the focused editor sets it again.
    if (focusedTab == null) com.inkslate.core.Perform.document = null
    // Pen tools and fullscreen from the strip over the page or a pedal. The pen's own profile is
    // changed (and the mouse's, for a laptop without one), so the next mark is the new tool
    // whatever touched the button - there is no mode to leave to annotate.
    com.inkslate.core.Perform.workspace = { action ->
        when (action) {
            com.inkslate.core.PerformAction.PEN, com.inkslate.core.PerformAction.HIGHLIGHTER,
            com.inkslate.core.PerformAction.ERASER -> {
                listOf(com.inkslate.core.InputMode.PEN, com.inkslate.core.InputMode.MOUSE).forEach {
                    com.inkslate.core.QuickTools.apply(tools.configFor(it), action)
                }
                tools.edit {}
                true
            }
            com.inkslate.core.PerformAction.FULLSCREEN -> { immersive = !immersive; true }
            else -> false
        }
    }
    // An app where the pen annotates and the finger turns pages (InkSheets) starts the finger
    // on moving the page instead of drawing - once, so a change made in Settings stays made.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (AppFlavor.fingerPans && DesktopPrefs.get("finger_pans_applied") == null) {
            tools.configFor(com.inkslate.core.InputMode.TOUCH).tool = com.inkslate.core.Tool.PAN
            tools.edit {}
            DesktopPrefs.put("finger_pans_applied", "true")
        }
    }
    com.inkslate.core.Perform.isOn = { action ->
        tools.revision
        when (action) {
            com.inkslate.core.PerformAction.FULLSCREEN -> immersive
            else -> com.inkslate.core.QuickTools.current(tools.configFor(com.inkslate.core.InputMode.PEN)) == action
        }
    }

    // Focus mode belongs to what is in front. Each document comes back the way it was left; the
    // two halves of a split share one, so moving between them never changes it. Home has no page
    // to focus on, so it always ends it. Opening or closing one half of a split leaves it as it is,
    // since the document still on screen is where you were.
    val lastFront = remember { arrayOf(emptyList<String>()) }
    val frontKey = if (homeShown) "" else listOfNotNull(primaryTab?.id, secondaryTab?.id).joinToString("|")
    androidx.compose.runtime.LaunchedEffect(frontKey) {
        val front = listOfNotNull(primaryTab, secondaryTab)
        val stayed = front.any { it.id in lastFront[0] }
        lastFront[0] = front.map { it.id }
        immersive = when {
            homeShown -> false
            stayed -> immersive
            else -> focusedTab?.fullscreen ?: false
        }
        snapshotFlow { immersiveState.value }.collect { on -> front.forEach { it.fullscreen = on } }
    }
    val wholeScreen = AppFlavor.alwaysFullscreen || (!homeShown && focusedTab != null && (AppFlavor.musicView || immersive))
    androidx.compose.runtime.SideEffect { AppFlavor.windowFullscreen = wholeScreen }
    androidx.compose.runtime.LaunchedEffect(homeShown || focusedTab == null) { AppFlavor.onHomeShown?.invoke(homeShown || focusedTab == null) }

    // Which of each document's views its bars follow: the one on screen, and for the document
    // the keyboard is in, the one last touched.
    SideEffect {
        val p = primary
        val s = secondary
        if (secondaryTab != null && s != null) secondaryTab.host.activeViewIndex = s.view
        if (primaryTab != null && p != null) primaryTab.host.activeViewIndex = p.view
        if (focusedTab != null && focusedRef != null) focusedTab.host.activeViewIndex = focusedRef.view
    }

    // The editor sets these while it has the keyboard, and Home and its neighbours set what they
    // use. With no document in front there is nothing to Save or Undo, so nothing is left armed.
    // Only then: clearing on every pass would leave the keys dead whenever this recomposed and the
    // focused document did not - which is most of the time, dragging the divider for one.
    if (focusedTab == null) shortcuts.clear()

    // A device saying it has written something is worth a look: the file itself arrives by the
    // ordinary sync, but this is what makes the shelf show it now rather than on the next visit.
    DisposableEffect(Unit) {
        DesktopPeers.start()
        DesktopPeers.onRemoteWrite { _, _ -> scope.launch { refreshKey++ } }
        onDispose { DesktopPeers.onRemoteWrite(null) }
    }

    CompositionLocalProvider(LocalToolState provides tools) {
        Column(Modifier.fillMaxSize()) {
            // Focus mode takes away the app bar, but the tabs stay so other documents are still a
            // click away; the way back out sits at the end of them.
            if (tabs.isNotEmpty()) {
                TabStrip(
                    tabs = tabs,
                    homeShown = homeShown,
                    shownIds = setOfNotNull(primaryTab?.id, secondaryTab?.id),
                    focusedId = focusedTab?.id,
                    primaryId = primaryTab?.id,
                    secondaryId = secondaryTab?.id,
                    onHome = { AppFlavor.onHome?.invoke(); homeShown = true },
                    onSelect = ::selectTab,
                    onOpenInSplit = ::openInSplit,
                    onCloseSplit = ::closeSplit,
                    onCloseTab = { id -> tabOf(id)?.closeRequested?.value = true },
                    onCloseOthers = ::closeOthers,
                    onCloseAll = ::closeAll,
                    onMoveTab = { from, to ->
                        tabs.add(to, tabs.removeAt(from))
                        AppFlavor.onTabsMoved?.invoke(tabs.filter { !it.closeRequested.value }.map { it.file })
                    },
                    onNewTab = { homeShown = true; screen = Screen.Home },
                    onLeaveFullscreen = if (immersive && !homeShown) {
                        { immersive = false }
                    } else null
                )
            }

            if (focusedTab != null) HostBar(focusedTab.host) { it.topBar }

            Surface(Modifier.weight(1f).fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize()) {
                    if (homeShown) {
                        when (val s = screen) {
                            Screen.Home -> {
                                val flavorHome = AppFlavor.home
                                if (flavorHome != null) {
                                    flavorHome(::openFile) { screen = Screen.Settings }
                                } else {
                                    HomeScreen(
                                        onOpenFile = ::openFile,
                                        onBrowse = { screen = Screen.Browser(null) },
                                        onOpenSettings = { screen = Screen.Settings },
                                        onNewDocument = { newDocOpen = true },
                                        refreshKey = refreshKey,
                                        navigation = navigation
                                    )
                                }
                            }

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
                    } else if (primaryTab != null) {
                        val p = primary!!
                        val s = secondary
                        if (secondaryTab != null && s != null) {
                            val ratio = splitRatio.coerceIn(0.15f, 0.85f)
                            // The bar follows the pointer: a drag is a share of the room the two
                            // halves have between them, not of some fixed guess at it.
                            val dividerPx = with(androidx.compose.ui.platform.LocalDensity.current) { SPLIT_DIVIDER.toPx() }
                            var splitWidth by remember { mutableStateOf(0) }
                            Row(Modifier.fillMaxSize().onSizeChanged { splitWidth = it.width }) {
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
                                        val room = (splitWidth - dividerPx).coerceAtLeast(1f)
                                        splitRatio = (splitRatio + deltaPx / room).coerceIn(0.15f, 0.85f)
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
                }
            }

            // In the music view, hidden tools means all of them - the page bar and the paint tray
            // too - so the page has the whole window; the Tools button brings them all back.
            if (focusedTab != null && !(AppFlavor.musicView && immersive)) HostBar(focusedTab.host) { it.bottomBar }
        }

        // Every open document, whether or not any of it is on screen. Each keeps its marks, its
        // undo history and its link to your other devices here for as long as its tab is open;
        // what it shows is drawn above, through its host.
        Box(Modifier.size(0.dp)) {
            for (tab in tabs) {
                key(tab.id) {
                    if (tab.loaded) {
                        EditorScreen(
                            file = tab.file,
                            shortcuts = shortcuts,
                            navigation = navigation,
                            onClose = { closeTab(tab.id) },
                            host = tab.host,
                            focused = tab === focusedTab,
                            closeRequested = tab.closeRequested
                        )
                    } else if (tab.closeRequested.value) {
                        // Never read, so nothing to save: it just goes.
                        androidx.compose.runtime.LaunchedEffect(Unit) { closeTab(tab.id) }
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

/**
 * One half of a split. Marked when it is the half the bars and the keyboard belong to, and moves
 * them here when touched - without taking the touch from whatever is underneath.
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
