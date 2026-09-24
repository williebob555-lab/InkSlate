package com.inkslate

import android.app.Application
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import com.inksheets.android.AndroidSheetsPlatform
import com.inksheets.ui.ActionStrip
import com.inksheets.ui.SheetsHome
import com.inksheets.ui.SheetsState
import java.io.File

/**
 * InkSheets: the music library becomes the Home screen, and a strip of action buttons sits over
 * the song in front. Both are one [SheetsState], so a setlist started at Home is the one the
 * strip's "next song" steps through.
 */
object FlavorSetup {

    private var state: SheetsState? = null
    private var stateContext: Context? = null
    private var openFile: ((File) -> Unit)? = null

    private fun stateFor(context: Context): SheetsState {
        state?.takeIf { stateContext === context }?.let { return it }
        return SheetsState(AndroidSheetsPlatform(context) { f -> openFile?.invoke(f) }).also {
            state = it
            stateContext = context
        }
    }

    fun install(app: Application) {
        AppFlavor.fingerPans = true
        AppFlavor.musicView = true
        com.inkslate.ink.DrawingView.fitWholePage = true
        AppFlavor.home = { open, openSettings ->
            openFile = open
            SheetsHome(stateFor(LocalContext.current), onOpenSettings = openSettings)
        }
        AppFlavor.paneOverlay = {
            ActionStrip(stateFor(LocalContext.current))
        }
    }
}
