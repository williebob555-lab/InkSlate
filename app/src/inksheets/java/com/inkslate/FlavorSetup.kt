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
    private var platform: AndroidSheetsPlatform? = null
    private var openFile: ((File) -> Unit)? = null

    /**
     * The one [SheetsState] for the whole app. There used to be one per context, and a dialog or
     * the settings screen has a context of its own - so a second state was made, whose companion
     * picked up following the same leader again: two connections from one tablet, fighting.
     */
    private fun stateFor(context: Context): SheetsState {
        val activity = context.activity() ?: context
        state?.let { existing ->
            platform?.let { if (it.context !== activity && activity is android.app.Activity) it.context = activity }
            return existing
        }
        val p = AndroidSheetsPlatform(activity) { f -> openFile?.invoke(f) }
        return SheetsState(p).also {
            state = it
            platform = p
            pendingLink?.let { link -> pendingLink = null; follow(it, link) }
        }
    }

    private tailrec fun Context.activity(): android.app.Activity? = when (this) {
        is android.app.Activity -> this
        is android.content.ContextWrapper -> baseContext.activity()
        else -> null
    }

    /** A join code scanned with the camera app before the screens were up, kept until they are. */
    private var pendingLink: String? = null

    private fun follow(state: SheetsState, link: String) {
        com.inksheets.core.CompanionLink.parseJoin(link)?.let { leader -> state.companion.followLeader(leader) { } }
    }

    fun install(app: Application) {
        AppFlavor.fingerPans = true
        AppFlavor.musicView = true
        AppFlavor.alwaysFullscreen = true
        com.inkslate.ink.DrawingView.fitWholePage = true
        com.inkslate.ink.DrawingView.stripLaneDp = 64f
        AppFlavor.home = { open, openSettings ->
            openFile = open
            SheetsHome(stateFor(LocalContext.current), onOpenSettings = openSettings)
        }
        AppFlavor.onHome = { state?.backToSetlist() }
        AppFlavor.settingsSection = { com.inksheets.ui.SheetsSettings(stateFor(LocalContext.current)) }
        AppFlavor.onTabsMoved = { state?.tabsMoved(it) }
        AppFlavor.onHomeShown = { home -> state?.homeInFront = home }
        AppFlavor.onLink = { link -> state?.let { follow(it, link) } ?: run { pendingLink = link } }
        AppFlavor.paneOverlay = {
            ActionStrip(stateFor(LocalContext.current))
        }
    }
}
