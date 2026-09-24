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
    var openFile: ((File) -> Unit)? = null
    val state by lazy { SheetsState(DesktopSheetsPlatform { f -> openFile?.invoke(f) }) }
    AppFlavor.home = { open, openSettings ->
        openFile = open
        SheetsHome(state, onOpenSettings = openSettings)
    }
    AppFlavor.paneOverlay = { ActionStrip(state) }
}
