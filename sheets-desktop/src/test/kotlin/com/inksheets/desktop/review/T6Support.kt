package com.inksheets.desktop.review

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.inkslate.desktop.AppRoot
import com.inkslate.desktop.InkSlateTheme
import com.inkslate.desktop.NavigationHooks
import com.inkslate.desktop.Shortcuts
import com.inksheets.desktop.installInkSheets

/**
 * T6: like [t2App] (the app as it ships, on copies of five real songs) but the folder's own scan is
 * waited for and the songs it made are used - [t2App] adds the same songs by hand while the first scan
 * is still running, which gives every song twice (a harness race, not what a player can do).
 * The setlist "T2 Gig" is made of the five songs in the T2 order; its id is [T2.setId].
 */
@OptIn(ExperimentalTestApi::class)
fun t6App(tag: String, w: Int = 1600, h: Int = 1000, profile: String? = "trombone", body: T2.() -> Unit) {
    val lib = T2Lib.build()
    val sheets = installInkSheets { it.setPref("sheets_library", lib.absolutePath) }
    try {
        runDesktopComposeUiTest(width = w, height = h) {
            mainClock.autoAdvance = false
            setContent { InkSlateTheme { AppRoot(Shortcuts(), NavigationHooks()) } }
            val x = T2(this, sheets, w.toFloat(), h.toFloat(), tag)
            x.settle(30)
            x.waitFor(60_000) { sheets().library?.songs?.size == T2Lib.songs.size }
            x.settle(40)
            val l = sheets().library!!
            val set = l.addSetlist("T2 Gig")
            for ((title, _) in T2Lib.songs) l.songs.firstOrNull { it.title.startsWith(title, true) }?.let { l.addToSetlist(set.id, it.id) }
            sheets().refresh()
            x.setId = set.id
            if (profile != null) sheets().chooseProfile(profile)
            x.settle(10)
            x.body()
        }
    } finally { resetFlavor() }
}
