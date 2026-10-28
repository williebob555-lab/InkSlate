package com.inksheets.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalTestApi::class)
class ReaderPanelLifecycleTest {
    @get:Rule val temp=TemporaryFolder()
    private class Platform(override val startFolder:File):SheetsPlatform {
        var back:(()->Unit)?=null
        @Composable override fun readerPanelBackHandler(enabled:Boolean,onBack:()->Unit) {
            DisposableEffect(enabled,onBack) {
                if(enabled)back=onBack
                onDispose {if(back===onBack)back=null}
            }
        }
        override val localFolder=File(startFolder,".local")
        override val deviceId="panel-lifecycle"
        override val deviceName="Panel test"
        override val audioOut:AudioOut?=null
        override val microphone:Microphone?=null
        override fun pref(key:String)=if(key=="sheets_library")startFolder.absolutePath else null
        override fun setPref(key:String,value:String?) {}
        override fun onMain(block:()->Unit)=block()
        override fun pageText(file:File,page:Int):String?=null
        override fun openPart(song:Song,part:Part,file:File) {}
    }
    private fun state()=SheetsState(Platform(temp.newFolder())).apply {homeInFront=false}

    @Test fun `same titled panels close independently and escape returns to the previous panel`() {
        val owner=state();var first by mutableStateOf(true);var second by mutableStateOf(true)
        runDesktopComposeUiTest(width=390,height=844) {
            setContent {MaterialTheme {CompositionLocalProvider(LocalReaderPanelState provides owner) {
                Box(Modifier.fillMaxSize()) {
                    if(first)ReaderPanel("Options",{first=false},null){Text("First panel")}
                    if(second)ReaderPanel("Options",{second=false},null){Text("Second panel")}
                }
            }}}
            waitForIdle()
            onNodeWithText("Second panel").assertIsDisplayed()
            onNodeWithText("First panel").assertDoesNotExist()
            runOnIdle {assertEquals(2,ReaderPanels.forState(owner).order.size)}
            onNodeWithContentDescription("Close").performClick()
            onNodeWithText("First panel").assertIsDisplayed()
            runOnIdle {assertEquals(1,ReaderPanels.forState(owner).order.size)}
            val previous=ReaderPanels.forState(owner).order.single()
            runOnIdle {owner.currentPath="another-score.pdf"}
            waitForIdle()
            runOnIdle {
                assertEquals("Switching scores cannot retain the old panel entry",1,ReaderPanels.forState(owner).order.size)
                assertNotEquals(previous,ReaderPanels.forState(owner).order.single())
            }
            onRoot().performKeyInput {keyDown(Key.Escape);keyUp(Key.Escape)}
            waitForIdle()
            runOnIdle {assertFalse(first);assertFalse(ReaderPanels.forState(owner).open);assertEquals(0f,ReaderPanels.forState(owner).height,0f)}
        }
    }

    @Test fun `two viewer owners reserve their own space and disposal cannot close the other viewer`() {
        val a=state();val b=state();var showA by mutableStateOf(true);var showB by mutableStateOf(true)
        runDesktopComposeUiTest(width=1000,height=700) {
            setContent {MaterialTheme {Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {CompositionLocalProvider(LocalReaderPanelState provides a) {
                    if(showA)ReaderPanel("Options",{showA=false},null){Text("Viewer A")}
                }}
                Box(Modifier.weight(1f).fillMaxHeight()) {CompositionLocalProvider(LocalReaderPanelState provides b) {
                    if(showB)ReaderPanel("Options",{showB=false},null){Text("Viewer B")}
                }}
            }}}
            waitForIdle()
            onNodeWithText("Viewer A").assertIsDisplayed();onNodeWithText("Viewer B").assertIsDisplayed()
            runOnIdle {
                assertTrue(ReaderPanels.forState(a).height>0);assertTrue(ReaderPanels.forState(b).height>0)
                assertNotNull((a.platform as Platform).back);assertNotNull((b.platform as Platform).back)
                (a.platform as Platform).back!!.invoke()
            }
            waitForIdle()
            onNodeWithText("Viewer B").assertIsDisplayed()
            runOnIdle {
                assertFalse(ReaderPanels.forState(a).open);assertEquals(0f,ReaderPanels.forState(a).height,0f)
                assertNull((a.platform as Platform).back);assertTrue(ReaderPanels.forState(b).open)
                (b.platform as Platform).back!!.invoke()
            }
            waitForIdle()
            runOnIdle {assertFalse(ReaderPanels.forState(b).open);assertEquals(0f,ReaderPanels.forState(b).height,0f);assertNull((b.platform as Platform).back)}
        }
    }

    @Test fun `long forms scroll to final options and retain action buttons on phone orientations`() {
        val longTitle="Excerpt notes and reminders for the extremely long title of this multi-song collection"
        for((width,height)in listOf(390 to 844,844 to 390,320 to 568,568 to 320,360 to 640,640 to 360)) {
            val owner=state();var text by mutableStateOf("");var saved=false
            runDesktopComposeUiTest(width=width,height=height) {
                setContent {MaterialTheme {CompositionLocalProvider(LocalReaderPanelState provides owner) {
                    ReaderPanel(longTitle,{}, {TextButton(onClick={saved=true}){Text("Save excerpt")}}) {
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                            OutlinedTextField(value=text,onValueChange={text=it},label={Text("Reminder")})
                            repeat(25){Text("Excerpt option $it")}
                            TextButton(onClick={text="Last option selected"}){Text("Final option")}
                        }
                    }
                }}}
                onNodeWithText(longTitle).assertIsDisplayed()
                onNodeWithContentDescription("Close").assertIsDisplayed()
                onNodeWithText("Reminder").performTextInput("Remember the breath before the reprise")
                onNodeWithText("Final option").performScrollTo().performClick()
                onNodeWithText("Save excerpt").assertIsDisplayed().performClick()
                val issues=UiAudit.check(this,"${width}x$height","reader-long-form",width,height,
                    required=listOf("Close","Save excerpt"),allowedTruncations=setOf(longTitle))
                assertTrue("$width x $height: $issues",issues.isEmpty())
                runOnIdle {assertEquals("Last option selected",text);assertTrue(saved)}
            }
        }
    }

    @Test fun `short viewport leaves one complete text field visible above sticky save and footer`() {
        val owner=state();var reminder by mutableStateOf("")
        ReaderPanels.forState(owner).base=76f
        runDesktopComposeUiTest(width=360,height=320) {
            setContent {MaterialTheme {CompositionLocalProvider(LocalReaderPanelState provides owner) {
                ReaderPanel("Excerpt reminder",{}, {TextButton(onClick={}){Text("Save excerpt")}}) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        OutlinedTextField(value=reminder,onValueChange={reminder=it},label={Text("Reminder")},singleLine=true,
                            modifier=Modifier.fillMaxWidth())
                    }
                }
            }}}
            waitForIdle()
            val field=onNode(hasSetTextAction()).fetchSemanticsNode()
            val full=field.layoutInfo.coordinates.size.height
            println("SHORT FORM visible=${field.boundsInRoot.height}px full=${full}px panel=${ReaderPanels.forState(owner).height}dp")
            UiAudit.check(this,"360x320","reader-short-form",360,320,required=listOf("Close","Save excerpt"))
            assertTrue("A whole field must remain visible, rather than a clipped editable sliver",field.boundsInRoot.height>=full*.95f)
            onNodeWithText("Save excerpt").assertIsDisplayed()
            runOnIdle {assertTrue("The score keeps at least80dp",ReaderPanels.forState(owner).height+76f<=240f)}
        }
    }
}
