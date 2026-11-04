package com.inksheets.desktop

import com.inksheets.core.ImportPlan
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.AudioOut
import com.inksheets.ui.Microphone
import com.inksheets.ui.SheetsPlatform
import com.inksheets.ui.SheetsState
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CombinedPdfPartsTest {
    @get:Rule val tmp=TemporaryFolder()

    private class Platform(override val startFolder:File,private val texts:List<String>):SheetsPlatform {
        private val prefs=mutableMapOf("sheets_library" to startFolder.absolutePath)
        override val deviceId="combined-pdf-test"
        override val deviceName="Combined PDF test"
        override val localFolder=File(startFolder,"local")
        override fun pref(key:String)=prefs[key]
        override fun setPref(key:String,value:String?) {if(value==null)prefs.remove(key)else prefs[key]=value}
        override fun openPart(song:Song,part:Part,file:File) {}
        override fun pageText(file:File,page:Int)=texts.getOrNull(page-1)
        override fun pageTexts(file:File,pageCount:Int)=texts.take(pageCount)
        override fun pageCount(file:File)=texts.size
        override val audioOut:AudioOut?=null
        override val microphone:Microphone?=null
        override fun onMain(block:()->Unit)=block()
    }

    @Test fun `unknown whole PDF is replaced by its multi page parts`() {
        val root=tmp.newFolder("library")
        val file=File(root,"Give Us This Day.pdf").apply {writeText("fixture")}
        val texts=listOf(
            "Give Us This Day\nPiccolo","continued","continued","continued",
            "Give Us This Day\nFlute 1","continued","continued","continued","continued",
            "Give Us This Day\nEuphonium","continued","continued"
        )
        val state=SheetsState(Platform(root,texts))
        lateinit var song:Song
        state.change {song=addSong("Give Us This Day",listOf(Part(id="whole",file=file.name)))}

        state.readUnknownParts()

        val parts=state.library!!.song(song.id)!!.parts
        assertEquals(listOf("piccolo","flute","euphonium"),parts.map {it.instrument})
        assertEquals(listOf(1 to 4,5 to 9,10 to 12),parts.map {it.firstPage!! to it.lastPage!!})
    }

    @Test fun `Give Us This Day becomes its real multi page parts`() {
        val file=File(System.getenv("USERPROFILE") ?: "","Music/Sheet Music/InkSheets/MobileSheets/Give Us This Day.pdf")
        assumeTrue(file.isFile)
        val texts=Loader.loadPDF(file).use {pdf ->
            val stripper=PDFTextStripper().apply {sortByPosition=true}
            (1..pdf.numberOfPages).map {page->
                stripper.startPage=page;stripper.endPage=page
                stripper.getText(pdf).lines().filter {it.isNotBlank()}.take(15).joinToString("\n")
            }
        }
        val parts=ImportPlan.splitCombinedParts(file.name,"Give Us This Day",texts.size,{texts[it-1]})
        assertEquals(34,parts.size)
        fun range(instrument:String,chair:Int?=null)=parts.single {it.instrument==instrument && it.chair==chair}
        assertEquals(1 to 4,range("piccolo").let {it.firstPage to it.lastPage})
        assertEquals(102 to 105,range("euphonium").let {it.firstPage to it.lastPage})
        assertEquals(116 to 124,range("piano").let {it.firstPage to it.lastPage})
        assertEquals(128 to 132,parts.first {it.firstPage==128}.let {it.firstPage to it.lastPage})
    }
}
