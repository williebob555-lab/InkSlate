package com.inkslate.desktop

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Taking this app's handwriting out of named PDFs, leaving the pages as they are - for files that
 * arrived carrying marks nobody wants:
 *
 *     ./gradlew :desktop:test --tests '*StripInkTool*' -Dinkslate.strip="a.pdf|b.pdf"
 *
 * Skipped unless files are named. Each is checked afterwards: no handwriting left to read.
 */
class StripInkTool {

    @Test
    fun `strip handwriting from the named files`() {
        val files = System.getProperty("inkslate.strip")?.split('|')?.map(::File)?.filter { it.isFile }.orEmpty()
        assumeTrue(files.isNotEmpty())
        for (f in files) {
            val before = DesktopEmbedder.read(f)
            println("${f.name}: ${before?.pages?.values?.sumOf { it.size } ?: 0} marks before")
            DesktopEmbedder.strip(f).getOrThrow()
            val after = DesktopEmbedder.read(f)
            println("${f.name}: ${after?.pages?.values?.sumOf { it.size } ?: 0} marks after, ${f.length()} bytes")
            assertEquals(null, after)
        }
    }
}
