package com.inkslate.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.inkslate.core.PagePlan
import com.inkslate.core.PaperSpec
import com.inkslate.core.PlannedPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Add, remove, duplicate, turn and reorder the pages of a document.
 *
 * Nothing happens to the file until Apply. Everything up to that point is a list of
 * [PlannedPage], so deleting thirty pages, changing your mind and closing the sheet costs
 * nothing - which is the only way anyone will trust a screen that can throw work away.
 *
 * Reordering is by arrows rather than by dragging: a drag on a grid needs a drop target and a
 * scroll-while-dragging, and a pair of arrows moves a page exactly one place with no ambiguity
 * about where it landed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagesSheet(
    source: DesktopSource,
    currentPage: Int,
    thumbnailFor: suspend (Int) -> ImageBitmap?,
    onApply: (List<PlannedPage>) -> Unit,
    onGoToPage: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pageSize = remember(source) { source.pageDim(0) }
    var plan by remember { mutableStateOf(PagePlan.identity(source.pageCount)) }
    var nextUid by remember { mutableStateOf(source.pageCount.toLong() + 1000L) }
    var selected by remember { mutableStateOf(currentPage) }

    val thumbs = remember { mutableStateMapOf<Int, ImageBitmap>() }
    LaunchedEffect(source) {
        for (i in 0 until source.pageCount) {
            withContext(Dispatchers.IO) { runCatching { thumbnailFor(i) }.getOrNull() }
                ?.let { thumbs[i] = it }
        }
    }

    var importing by remember { mutableStateOf<List<ImportCandidate>?>(null) }

    val changed = !PagePlan.isUnchanged(plan, source.pageCount)

    /**
     * Bring pages in from another file.
     *
     * Nothing is read from the picked file here beyond its size and page count: the pages are
     * carried in the plan by path and only pulled in when the rearrangement is applied, which is
     * what keeps picking twenty files from costing twenty document loads.
     */
    fun pickFilesToImport() {
        val dialog = java.awt.FileDialog(
            null as java.awt.Frame?, "Add pages from", java.awt.FileDialog.LOAD
        )
        dialog.isMultipleMode = true
        dialog.setFilenameFilter { _, name ->
            val f = java.io.File(name)
            DesktopSources.isPdf(f) || DesktopSources.isImage(f)
        }
        dialog.isVisible = true
        val staged = dialog.files.orEmpty().mapNotNull { inspectForImport(it) }
        if (staged.isNotEmpty()) importing = staged
    }

    importing?.let { candidates ->
        ImportPagesDialog(
            candidates = candidates,
            documentWidth = pageSize.width,
            documentHeight = pageSize.height,
            insertAfter = selected,
            onDismiss = { importing = null },
            onImport = { picked ->
                importing = null
                val fresh = importedPages(picked, pageSize.width, pageSize.height)
                    .map { PlannedPage(source = -1, uid = nextUid++, import = it) }
                plan = PagePlan.inserted(plan, selected + 1, fresh)
            }
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp)) {

            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Pages", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (changed) {
                            "${plan.size} page${if (plan.size == 1) "" else "s"} " +
                                "- nothing is written until you apply"
                        } else {
                            "${plan.size} page${if (plan.size == 1) "" else "s"}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (changed) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (changed) {
                    TextButton(onClick = { plan = PagePlan.identity(source.pageCount) }) {
                        Text("Undo all")
                    }
                }
                TextButton(enabled = changed, onClick = { onApply(plan) }) { Text("Apply") }
            }

            // ---- what can be done to the page in hand ----
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                IconButton(
                    onClick = { plan = PagePlan.moved(plan, selected, selected - 1); selected-- },
                    enabled = selected > 0
                ) { Icon(Icons.Default.ArrowBack, "Move earlier") }
                IconButton(
                    onClick = { plan = PagePlan.moved(plan, selected, selected + 1); selected++ },
                    enabled = selected < plan.lastIndex
                ) { Icon(Icons.Default.ArrowForward, "Move later") }
                IconButton(onClick = { plan = PagePlan.turned(plan, selected, -1) }) {
                    Icon(Icons.Default.RotateLeft, "Turn left")
                }
                IconButton(onClick = { plan = PagePlan.turned(plan, selected, 1) }) {
                    Icon(Icons.Default.RotateRight, "Turn right")
                }
                IconButton(onClick = { plan = PagePlan.duplicated(plan, selected, nextUid++) }) {
                    Icon(Icons.Default.Layers, "Duplicate")
                }
                IconButton(
                    onClick = {
                        val dim = source.pageDim(selected.coerceIn(0, source.pageCount - 1))
                        plan = PagePlan.inserted(
                            plan, selected + 1,
                            listOf(
                                PlannedPage(
                                    source = -1,
                                    uid = nextUid++,
                                    blankWidth = dim.width,
                                    blankHeight = dim.height,
                                    paper = PaperSpec()
                                )
                            )
                        )
                    }
                ) { Icon(Icons.Default.Add, "Add a blank page after this one") }
                IconButton(onClick = { pickFilesToImport() }) {
                    Icon(Icons.Default.LibraryAdd, "Add pages from another file")
                }
                Box(Modifier.weight(1f))
                IconButton(
                    onClick = {
                        plan = PagePlan.removed(plan, selected)
                        selected = selected.coerceAtMost(plan.lastIndex)
                    },
                    enabled = plan.size > 1
                ) {
                    Icon(Icons.Default.Delete, "Remove", tint = MaterialTheme.colorScheme.error)
                }
            }

            HorizontalDivider()

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 118.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(plan.size) { index ->
                    val p = plan[index]
                    PageTile(
                        planned = p,
                        position = index,
                        thumb = if (p.isNew) null else thumbs[p.source],
                        selected = index == selected,
                        onClick = { selected = index },
                        onDoubleClick = {
                            // Only meaningful for a page that is still where it was; a planned
                            // page has no place in the document to jump to yet.
                            if (!changed && !p.isNew) onGoToPage(index)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PageTile(
    planned: PlannedPage,
    position: Int,
    thumb: ImageBitmap?,
    selected: Boolean,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .secondaryClick(onDoubleClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.77f)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White)
                .border(
                    if (selected) 2.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    RoundedCornerShape(4.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            when {
                thumb != null -> Image(
                    bitmap = thumb,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .rotate(planned.quarterTurns * 90f),
                    contentScale = ContentScale.Fit
                )
                planned.isImported -> Text(
                    "Imported",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF666666)
                )
                else -> Text(
                    "Blank",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF666666)
                )
            }
        }
        Text(
            "${position + 1}" + when {
                planned.isNew -> "  ·  new"
                planned.quarterTurns != 0 -> "  ·  turned"
                else -> ""
            },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
