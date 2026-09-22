package com.inkslate.desktop

import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSStream
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Prints what an exported PDF actually holds, page by page, and renders each page to a PNG.
 * Does nothing unless asked:
 * `INKSLATE_INSPECT=C:/path/a.pdf;C:/path/b.pdf INKSLATE_INSPECT_OUT=dir gradlew :desktop:test --tests *InspectExportTest* --rerun`.
 */
class InspectExportTest {
    @Test
    fun inspect() {
        val paths = System.getenv("INKSLATE_INSPECT") ?: return
        val outDir = File(System.getenv("INKSLATE_INSPECT_OUT") ?: "inspect").apply { mkdirs() }
        val out = StringBuilder()
        for (path in paths.split(';')) {
            val file = File(path)
            out.appendLine("== ${file.name} (${file.length() / 1024}KB)")
            Loader.loadPDF(file).use { pdf ->
                val files = pdf.documentCatalog.names?.embeddedFiles?.names?.keys
                out.appendLine("attachments: $files")
                val renderer = PDFRenderer(pdf)
                for (i in 0 until pdf.numberOfPages) {
                    val page = pdf.getPage(i)
                    val content = page.contentStreams.asSequence().sumOf { s ->
                        s.createInputStream().use { it.readBytes().size }
                    }
                    out.appendLine(
                        "page $i rot=${page.rotation} media=${page.mediaBox} crop=${page.cropBox} content=${content}B"
                    )
                    val annots = page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray
                    for (k in 0 until (annots?.size() ?: 0)) {
                        val a = annots!!.getObject(k) as? COSDictionary ?: continue
                        val ap = (a.getDictionaryObject(COSName.AP) as? COSDictionary)
                            ?.getDictionaryObject(COSName.N) as? COSStream
                        val apLen = ap?.createInputStream()?.use { it.readBytes().size }
                        out.appendLine(
                            "  annot ${a.getNameAsString(COSName.SUBTYPE)} T=${a.getString(COSName.T)} " +
                                "rect=${a.getDictionaryObject(COSName.RECT)} F=${a.getInt(COSName.F)} " +
                                "AP=${apLen}B bbox=${ap?.getDictionaryObject(COSName.BBOX)} " +
                                "matrix=${ap?.getDictionaryObject(COSName.MATRIX)}"
                        )
                    }
                    val img = renderer.renderImage(i, 1f)
                    ImageIO.write(img, "png", File(outDir, "${file.nameWithoutExtension}-p$i.png"))
                }
            }
            val ink = runCatching { DesktopEmbedder.read(file) }.getOrNull()
            out.appendLine(
                "embedded ink: " + (ink?.let { d ->
                    d.pages.entries.joinToString { "p${it.key}=${it.value.size}" }
                } ?: "none")
            )
        }
        File(outDir, "report.txt").writeText(out.toString())
    }
}
