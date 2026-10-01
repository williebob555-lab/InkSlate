package com.inkslate.desktop

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** What a document carries inside it, read only: -Dinkslate.inkdump=<file> */
class InkDump {
    @Test
    fun `what the file carries`() {
        val path = System.getProperty("inkslate.inkdump")
        assumeTrue(path != null)
        val f = File(path!!)
        val doc = DesktopEmbedder.read(f)
        println("INK embedded: ${doc != null}; sidecar: ${DocumentIO.sidecarFor(f).isFile}")
        if (doc == null) return
        println("INK pageSizes ${doc.pageSizes.size}: ${doc.pageSizes.take(4)}")
        println("INK pages with marks: ${doc.pages.filterValues { it.isNotEmpty() }.keys}")
        println("INK source: ${doc.source}")
        println("INK layout '${doc.layout}', structure changes ${doc.structureHistory.size}: ${doc.structureHistory.take(5)}")
        println("INK canvas ${doc.canvas}, modified ${doc.modifiedUtc} by ${doc.modifiedBy}, app ${doc.app}")
    }
}
