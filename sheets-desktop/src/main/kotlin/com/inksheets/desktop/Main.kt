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
fun main() = runAs("InkSheets") {
    AppFlavor.fingerPans = true
    AppFlavor.musicView = true
    // A music stand, not a window among windows: the whole screen, Home too. Quit is in the menu.
    AppFlavor.alwaysFullscreen = true
    var openFile: ((File) -> Unit)? = null
    val state by lazy { SheetsState(DesktopSheetsPlatform { f -> openFile?.invoke(f) }) }
    AppFlavor.home = { open, openSettings ->
        openFile = open
        SheetsHome(state, onOpenSettings = openSettings)
    }
    AppFlavor.paneOverlay = { ActionStrip(state) }
    AppFlavor.onHome = { state.backToSetlist() }
    AppFlavor.settingsSection = { com.inksheets.ui.SheetsSettings(state) }
    AppFlavor.onTabsMoved = { state.tabsMoved(it) }
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
}
