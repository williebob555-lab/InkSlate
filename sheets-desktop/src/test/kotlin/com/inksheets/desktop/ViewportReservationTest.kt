package com.inksheets.desktop

import com.inkslate.core.Perform
import com.inkslate.desktop.AppFlavor
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportReservationTest {
    @Test fun `opening reader tool reservations does not fit the page`() {
        val oldFit=Perform.recentre
        val oldShelf=AppFlavor.musicShelfDp
        val oldRail=AppFlavor.studioRailDp
        val oldRight=AppFlavor.studioRailOnRight
        val oldStrip=AppFlavor.stripLaneDp
        var fits=0
        Perform.recentre={fits++}
        try {
            val platform=DesktopSheetsPlatform {}
            platform.setMusicShelfHeight(176f)
            platform.setStudioRailWidth(236f)
            platform.setStudioRailSide(true)
            platform.setStripLane(true)
            assertEquals("Making room for opening tools must preserve zoom and pan",0,fits)
        } finally {
            AppFlavor.musicShelfDp=oldShelf
            AppFlavor.studioRailDp=oldRail
            AppFlavor.studioRailOnRight=oldRight
            AppFlavor.stripLaneDp=oldStrip
            Perform.recentre=oldFit
        }
    }
}
