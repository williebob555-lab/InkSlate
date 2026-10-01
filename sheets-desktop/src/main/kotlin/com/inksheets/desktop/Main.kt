package com.inksheets.desktop

import com.inkslate.desktop.AppFlavor
import com.inkslate.desktop.runAs
import com.inksheets.ui.ActionStrip
import com.inksheets.ui.SheetsHome
import com.inksheets.ui.SheetsState
import java.io.File

/**
 * InkSheets on Windows and Linux: InkSlate's desktop app, with the music library as its Home and
 * the action buttons over the song in front - one [SheetsState] behind both.
 */
fun main() = runAs("InkSheets") { installInkSheets() }

/**
 * InkSlate's desktop app made InkSheets: its flavour, Home, buttons and hooks, all on one
 * [SheetsState] - here rather than in [main] so a test runs exactly the app that ships.
 * [prepare] sees the platform before the state is made (a test points the library somewhere).
 */
fun installInkSheets(prepare: (DesktopSheetsPlatform) -> Unit = {}): () -> SheetsState {
    AppFlavor.fingerPans = true
    AppFlavor.musicView = true
    // A music stand, not a window among windows: the whole screen, Home too. Quit is in the menu.
    AppFlavor.alwaysFullscreen = true
    var openFile: ((File) -> Unit)? = null
    // The library read in off the UI thread: its log is megabytes, and the first frame waited on it.
    val state by lazy { SheetsState(DesktopSheetsPlatform { f -> openFile?.invoke(f) }.also(prepare), openLater = true) }
    AppFlavor.home = { open, openSettings ->
        openFile = open
        SheetsHome(state, onOpenSettings = openSettings)
    }
    AppFlavor.paneOverlay = { ActionStrip(state) }
    AppFlavor.onHome = { state.backToSetlist() }
    AppFlavor.settingsSection = { com.inksheets.ui.SheetsSettings(state) }
    AppFlavor.onTabsMoved = { state.tabsMoved(it) }
    AppFlavor.onOpenTabs = { state.openTabs(it) }
    AppFlavor.onHomeShown = { home -> state.homeInFront = home }
    AppFlavor.onSaveTabs = { files -> state.savingTabs = files }
    AppFlavor.onFilesDropped = { files -> state.offer(files) }
    // Windows' own pickers - search, filters, Quick access - rather than a list of files.
    if (com.inkslate.desktop.WindowsFileDialog.available) {
        com.inksheets.ui.NativePickers.file = { title, start, extensions ->
            val types = if (extensions.isEmpty()) listOf("All files" to "*.*")
            else listOf(extensions.joinToString(", ") { it.uppercase() } to extensions.joinToString(";") { "*.$it" }, "All files" to "*.*")
            com.inkslate.desktop.WindowsFileDialog.files(title, start, types).firstOrNull()
        }
        com.inksheets.ui.NativePickers.folder = { title, start -> com.inkslate.desktop.WindowsFileDialog.folder(title, start) }
    }
    AppFlavor.pagesActions = { path, pages, close -> com.inksheets.ui.MusicPageActions(state, path, pages, close) }
    return { state }
}
