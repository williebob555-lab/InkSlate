package com.inkslate.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.inkslate.core.ImportedPage

/** What a picked file turned out to be, once it had been looked at. */
data class ImportCandidate(
    val path: String,
    val displayName: String,
    val isImage: Boolean,
    val pageCount: Int,
    val width: Float,
    val height: Float,
    /** Displayed dimensions for each PDF page; PDFs may mix paper sizes and rotations. */
    val pageSizes: List<Pair<Float, Float>> = emptyList()
)

/**
 * Choose what to take from a file that is being brought in.
 *
 * A page range rather than "all of it": picking the answer sheet out of a fifty-page pack should
 * not mean deleting forty-nine pages afterwards. The same dialog as the tablet's, down to the
 * default of every page and the choice of whether a picture is fitted to the document's own page
 * size or brought in at its own.
 */
@Composable
fun ImportPagesDialog(
    candidates: List<ImportCandidate>,
    documentWidth: Float,
    documentHeight: Float,
    insertAfter: Int?,
    onDismiss: () -> Unit,
    onImport: (List<Triple<ImportCandidate, Int, Boolean>>) -> Unit
) {
    // One range for the lot: these are almost always one file, and a per-file range would be a
    // form to fill in rather than a choice to make.
    var from by remember { mutableStateOf("1") }
    var to by remember {
        mutableStateOf(candidates.maxOf { it.pageCount }.coerceAtLeast(1).toString())
    }
    var fitToPage by remember { mutableStateOf(true) }

    val anyImages = candidates.any { it.isImage }
    val anyPdfs = candidates.any { !it.isImage }

    fun picked(): List<Triple<ImportCandidate, Int, Boolean>> {
        val lo = (from.toIntOrNull() ?: 1).coerceAtLeast(1)
        val hi = (to.toIntOrNull() ?: Int.MAX_VALUE).coerceAtLeast(lo)
        return candidates.flatMap { c ->
            if (c.isImage) {
                listOf(Triple(c, 0, fitToPage))
            } else {
                (lo..minOf(hi, c.pageCount)).map { Triple(c, it - 1, fitToPage) }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add pages") },
        text = {
            Column {
                Text(
                    candidates.joinToString(", ") { it.displayName },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (insertAfter == null) "Added at the end."
                    else "Added after page ${insertAfter + 1}.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )

                if (anyPdfs) {
                    Text(
                        "Pages",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Row {
                        OutlinedTextField(
                            value = from,
                            onValueChange = { from = it.filter(Char::isDigit) },
                            label = { Text("From") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = to,
                            onValueChange = { to = it.filter(Char::isDigit) },
                            label = { Text("To") },
                            singleLine = true,
                            modifier = Modifier.weight(1f).padding(start = 8.dp)
                        )
                    }
                }

                if (anyImages) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Fit to this document's pages")
                            Text(
                                "%.0f x %.0f pt".format(documentWidth, documentHeight),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = fitToPage, onCheckedChange = { fitToPage = it })
                    }
                }

                Text(
                    "${picked().size} page${if (picked().size == 1) "" else "s"} will be added.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = picked().isNotEmpty(),
                onClick = { onImport(picked()) }
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Look at a picked file well enough to offer a sensible range. */
fun inspectForImport(file: java.io.File): ImportCandidate? = runCatching {
    when {
        DesktopSources.isPdf(file) -> org.apache.pdfbox.Loader.loadPDF(file).use { pdf ->
            val sizes = (0 until pdf.numberOfPages).map { index ->
                val page = pdf.getPage(index)
                val box = page.cropBox ?: page.mediaBox
                val turn = ((page.rotation % 360) + 360) % 360
                if (turn == 90 || turn == 270) box.height to box.width
                else box.width to box.height
            }
            val first = sizes.first()
            ImportCandidate(
                path = file.absolutePath,
                displayName = file.name,
                isImage = false,
                pageCount = pdf.numberOfPages,
                width = first.first,
                height = first.second,
                pageSizes = sizes
            )
        }

        DesktopSources.isImage(file) -> {
            val image = javax.imageio.ImageIO.read(file) ?: return null
            ImportCandidate(
                path = file.absolutePath,
                displayName = file.name,
                isImage = true,
                pageCount = 1,
                width = image.width.toFloat(),
                height = image.height.toFloat()
            )
        }

        else -> null
    }
}.getOrNull()

/** The planned pages a set of choices turns into. */
fun importedPages(
    picked: List<Triple<ImportCandidate, Int, Boolean>>,
    documentWidth: Float,
    documentHeight: Float
): List<ImportedPage> = picked.map { (candidate, pageIndex, fit) ->
    val own = candidate.pageSizes.getOrNull(pageIndex) ?: (candidate.width to candidate.height)
    val w = if (candidate.isImage && fit) documentWidth else own.first
    val h = if (candidate.isImage && fit) documentHeight else own.second
    ImportedPage(
        path = candidate.path,
        pageIndex = pageIndex,
        isImage = candidate.isImage,
        width = w,
        height = h,
        fitToPage = fit
    )
}
