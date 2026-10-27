package com.inksheets.desktop

import androidx.compose.ui.test.*
import com.inkslate.desktop.*
import com.inkslate.core.Perform
import com.inkslate.core.PerformAction
import com.inksheets.ui.ScoreTools
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Shipping workspace and renderer, with a generated three-page PDF and isolated app data. */
@OptIn(ExperimentalTestApi::class)
class WorkstationAppAuditTest {
    @get:Rule val tmp = TemporaryFolder()
    @After fun reset() {
        ScoreTools.open = false
        AppFlavor.home = null; AppFlavor.paneOverlay = null; AppFlavor.settingsSection = null
        AppFlavor.onHomeShown = null; AppFlavor.onHome = null
        AppFlavor.musicView = false; AppFlavor.alwaysFullscreen = false; AppFlavor.fingerPans = false
        AppFlavor.musicShelfDp = 0f; AppFlavor.musicLaneDp = 0f; AppFlavor.studioRailDp = 0f
        com.inksheets.ui.Overlays.studioOut = androidx.compose.ui.unit.Dp(0f)
        Perform.isOn = null; Perform.workspace = null; Perform.document = null
    }
    private fun DesktopComposeUiTest.settle(frames: Int = 20) = repeat(frames) { mainClock.advanceTimeBy(16); Thread.sleep(4) }
    @Test fun `real PDF workspace menus and annotation access at every supported size`() {
        val only = System.getProperty("inksheets.workstation.sizes")?.split(',')?.toSet()
        for ((size, w, h) in UiAudit.SIZES) {
            if (only != null && size !in only) continue
            val root = tmp.newFolder(size)
            val pdf = File(root, "Nocturne - Euphonium.pdf")
            PDDocument().use { doc ->
                repeat(3) { index ->
                    val page = PDPage(); doc.addPage(page)
                    PDPageContentStream(doc, page).use { stream ->
                        stream.beginText(); stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 20f)
                        stream.newLineAtOffset(50f, 735f); stream.showText("Nocturne - Euphonium - page ${index + 1}"); stream.endText()
                        for (staff in 0..8) for (line in 0..4) { val y=670f-staff*65-line*6; stream.moveTo(50f,y);stream.lineTo(560f,y);stream.stroke() }
                    }
                }; doc.save(pdf)
            }
            val sheets = installInkSheets { p ->
                p.setPref("sheets_library", root.absolutePath); p.setPref("sheets_strip_collapsed", "false")
                p.setPref("sheets_read_music", "true"); p.setPref("sheets_shelf_hidden", null)
            }
            val home = AppFlavor.home!!
            var openFile: ((File) -> Unit)? = null
            var openSettings: (() -> Unit)? = null
            AppFlavor.home = { open, settings -> openFile = open; openSettings = settings; home(open,settings) }
            runDesktopComposeUiTest(width=w, height=h) {
                mainClock.autoAdvance=false
                setContent { InkSlateTheme { AppRoot(Shortcuts(), NavigationHooks()) } }
                settle(35)
                runOnIdle { openFile!!(pdf) }
                val until=System.currentTimeMillis()+20_000
                while (sheets().pageShown.second==0 && System.currentTimeMillis()<until) settle(5)
                settle(35)
                assertEquals("$size: PDF opened",3,sheets().pageShown.second)
                val s=sheets()
                assertTrue("$size: renderer reserves shelf", AppFlavor.musicShelfDp>0)
                assertTrue("$size: music area remains",AppFlavor.musicShelfDp<h*.42)
                val issues=UiAudit.check(this,size,"workstation-real-score",w,h,allowedTruncations=setOf(pdf.name))
                assertTrue("$size: $issues",issues.isEmpty())
                runOnIdle { Perform.run(PerformAction.FULLSCREEN) }
                settle(20)
                val annotationIssues = UiAudit.check(this,size,"workstation-annotation-tools",w,h,allowedTruncations=setOf(pdf.name))
                assertTrue("$size annotation tools: $annotationIssues",annotationIssues.isEmpty())
                if (h < 550) {
                    assertTrue("$size: compact rehearsal controls leave room for annotation", AppFlavor.musicShelfDp <= 120f)
                    onNodeWithContentDescription("Return to music workspace").performClick()
                    settle()
                    assertTrue("$size: one tap restores music workspace", Perform.on(PerformAction.FULLSCREEN))
                    assertTrue("$size: full shelf restored", AppFlavor.musicShelfDp > 40f)
                }
                runOnIdle { if (!Perform.on(PerformAction.FULLSCREEN)) Perform.run(PerformAction.FULLSCREEN) }
                settle()
                runOnIdle { s.companionOpen=true }
                settle()
                val connectionIssues=UiAudit.check(this,size,"workstation-connections",w,h,allowedTruncations=setOf(pdf.name))
                assertTrue("$size connections: $connectionIssues",connectionIssues.isEmpty())
                runOnIdle { s.companionOpen=false; s.stripCollapsed=true }
                settle()
                assertEquals("$size: folded shelf frees page space",0f,AppFlavor.musicShelfDp,.01f)
                onNodeWithText("Home").performClick()
                settle()
                runOnIdle { openSettings!!() }
                settle()
                onNodeWithText("Appearance").performClick()
                settle()
                val settingsIssues=UiAudit.check(this,size,"workstation-real-settings",w,h)
                assertTrue("$size settings: $settingsIssues",settingsIssues.isEmpty())
            }
        }
    }
}
