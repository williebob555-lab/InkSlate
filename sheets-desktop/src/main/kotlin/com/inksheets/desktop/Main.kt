package com.inksheets.desktop

import androidx.compose.runtime.remember
import com.inkslate.desktop.AppFlavor
import com.inkslate.desktop.runAs
import com.inksheets.ui.SheetsHome
import com.inksheets.ui.SheetsState

/**
 * InkSheets on Windows and Linux: InkSlate's desktop app, with the music library as its Home.
 */
fun main() = runAs("InkSheets") {
    AppFlavor.home = { openFile, openSettings ->
        val state = remember { SheetsState(DesktopSheetsPlatform(openFile)) }
        SheetsHome(state, onOpenSettings = openSettings)
    }
}
