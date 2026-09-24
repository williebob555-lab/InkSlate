package com.inkslate

import android.app.Application
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.inksheets.android.AndroidSheetsPlatform
import com.inksheets.ui.SheetsHome
import com.inksheets.ui.SheetsState

/** InkSheets: the music library becomes the Home screen. */
object FlavorSetup {
    fun install(app: Application) {
        AppFlavor.home = { openFile, openSettings ->
            val context = LocalContext.current
            val state = remember { SheetsState(AndroidSheetsPlatform(context, openFile)) }
            SheetsHome(state, onOpenSettings = openSettings)
        }
    }
}
