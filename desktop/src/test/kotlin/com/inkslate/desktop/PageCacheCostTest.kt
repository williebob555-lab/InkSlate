package com.inkslate.desktop

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Drawing real scanned parts against reading them back: -Dinksheets.lib=... */
class PageCacheCostTest {
    @Test
    fun `drawn against kept`() {
        val lib = System.getProperty("inksheets.lib")?.let(::File)
        assumeTrue(lib != null && lib.isDirectory)
        val files = listOf(
            "MobileSheets/America Forever March.pdf", "MobileSheets/99 Red Balloons - Electric Bass.pdf",
            "Imported/PEP BAND/Music/24K Magic/24K Magic - Electric Bass.pdf", "Imported/PEP BAND/Music/Sweet Caroline/SweetC - Trombone 1.pdf"
        ).map { File(lib, it) }.filter { it.isFile }
        for (f in files) {
            PdfSource(f, detached = true).use { src ->
                val t0 = System.nanoTime(); src.render(0, 1700); val drawn = (System.nanoTime() - t0) / 1e6
                val t1 = System.nanoTime(); src.render(0, 1700); val mem = (System.nanoTime() - t1) / 1e6
                PageCache.forgetMemory()
                val t2 = System.nanoTime(); src.render(0, 1700); val disk = (System.nanoTime() - t2) / 1e6
                println("PAGE %-45s drawn %6.0f ms   from memory %5.1f ms   from disk %5.0f ms".format(f.name.take(45), drawn, mem, disk))
            }
        }
    }
}
