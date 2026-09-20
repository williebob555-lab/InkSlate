package com.inkslate.desktop

import androidx.compose.foundation.Image
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import com.inkslate.core.PageLayout
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
import androidx.compose.material3.AlertDialog
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
 *
 * ## Several pages at once
 *
 * A click picks one page. Ctrl-click adds or removes one, Shift-click takes the run from the last
 * page clicked, and the tick in each page's corner does what Ctrl-click does for a pen or a finger,
 * which has no Ctrl key. There is one row of actions, not a second set for a selection: every
 * button in it acts on whatever is picked, one page or twenty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagesSheet(
    source: DesktopSource,
    currentPage: Int,
    layout: PageLayout,
    onLayoutChange: (PageLayout) -> Unit,
    thumbnailFor: suspend (Int) -> ImageBitmap?,
    onApply: (List<PlannedPage>) -> Unit,
    onGoToPage: (Int) -> Unit,
    /** Export these pages of the document as it stands on disk. */
    onExport: (pages: List<Int>) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pageSize = remember(source) { source.pageDim(0) }
    var plan by remember { mutableStateOf(PagePlan.identity(source.pageCount)) }
    var nextUid by remember { mutableStateOf(source.pageCount.toLong() + 1000L) }
    // Picked pages by uid, not position: copying or moving them changes every position, and the
    // selection has to stay on the pages that were picked rather than the places they were.
    val firstPick = currentPage.coerceIn(0, (source.pageCount - 1).coerceAtLeast(0)).toLong()
    var picked by remember { mutableStateOf(setOf(firstPick)) }
    // Where a Shift-click runs from.
    var anchor by remember { mutableStateOf(firstPick) }

    val thumbs = remember { mutableStateMapOf<Int, ImageBitmap>() }
    LaunchedEffect(source) {
        for (i in 0 until source.pageCount) {
            withContext(Dispatchers.IO) { runCatching { thumbnailFor(i) }.getOrNull() }
                ?.let { thumbs[i] = it }
        }
    }

    var importing by remember { mutableStateOf<List<ImportCandidate>?>(null) }
    var confirmApply by remember { mutableStateOf(false) }

    val changed = !PagePlan.isUnchanged(plan, source.pageCount)
    val pickedAt = PagePlan.indicesOf(plan, picked)
    // New pages go after the last picked one, or at the end when nothing is picked.
    val insertAfter = pickedAt.lastOrNull() ?: plan.lastIndex

    fun pickOnly(uids: Set<Long>) {
        picked = uids
        uids.firstOrNull()?.let { anchor = it }
    }

    fun toggle(uid: Long) {
        picked = if (uid in picked) picked - uid else picked + uid
        anchor = uid
    }

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

    // Applying rewrites the document itself, which is not something to do on one click of a
    // button sitting next to "Undo all". The summary is the point: it says what is about to
    // happen to the file in the terms someone is thinking in - pages, turns, and where the
    // handwriting goes.
    if (confirmApply) {
        AlertDialog(
            onDismissRequest = { confirmApply = false },
            title = { Text("Rearrange the document?") },
            text = {
                val turned = plan.count { it.quarterTurns != 0 }
                val brought = plan.count { it.isImported }
                Text(
                    buildString {
                        append("This rewrites the file itself: ${plan.size} page")
                        append(if (plan.size == 1) "" else "s")
                        append(", from ${source.pageCount}.")
                        if (turned > 0) append(" $turned turned.")
                        if (brought > 0) append(" $brought brought in from other files.")
                        append(
                            "\n\nYour handwriting moves and turns with its pages, and marks on " +
                                "removed pages go with them.\n\n" +
                                "The previous version is kept in version history. Your other " +
                                "devices will pick up the new order when they next sync."
                        )
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmApply = false; onApply(plan) }) { Text("Rearrange") }
            },
            dismissButton = {
                TextButton(onClick = { confirmApply = false }) { Text("Cancel") }
            }
        )
    }

    importing?.let { candidates ->
        ImportPagesDialog(
            candidates = candidates,
            documentWidth = pageSize.width,
            documentHeight = pageSize.height,
            insertAfter = insertAfter,
            onDismiss = { importing = null },
            onImport = { chosen ->
                importing = null
                val fresh = importedPages(chosen, pageSize.width, pageSize.height)
                    .map { PlannedPage(source = -1, uid = nextUid++, import = it) }
                plan = PagePlan.inserted(plan, insertAfter + 1, fresh)
                pickOnly(fresh.map { it.uid }.toSet())
            }
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp)) {

            val count = if (pickedAt.size > 1) {
                "${pickedAt.size} of ${plan.size} pages picked"
            } else {
                "${plan.size} page${if (plan.size == 1) "" else "s"}"
            }
            PanelTop(
                "Pages",
                onDismiss,
                if (changed) "$count - nothing is written until you apply" else count
            ) {
                if (changed) {
                    TextButton(onClick = { plan = PagePlan.identity(source.pageCount) }) {
                        Text("Undo all")
                    }
                }
                TextButton(enabled = changed, onClick = { confirmApply = true }) { Text("Apply") }
            }

            // ---- how the pages sit on screen ----
            // Here rather than in the view menu, as on the tablet: the arrangement and the pages
            // answer the same question, which is what the document looks like.
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PageLayout.entries.forEach { option ->
                    FilterChip(
                        selected = layout == option,
                        onClick = { onLayoutChange(option) },
                        label = { Text(option.label) }
                    )
                }
            }

            // ---- what can be done to the picked pages ----
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val earlier = PagePlan.movedAll(plan, picked, -1)
                val later = PagePlan.movedAll(plan, picked, 1)
                IconButton(onClick = { plan = earlier }, enabled = earlier != plan) {
                    Icon(Icons.Default.ArrowBack, "Move earlier")
                }
                IconButton(onClick = { plan = later }, enabled = later != plan) {
                    Icon(Icons.Default.ArrowForward, "Move later")
                }
                IconButton(
                    onClick = { plan = PagePlan.turnedAll(plan, picked, -1) },
                    enabled = pickedAt.isNotEmpty()
                ) { Icon(Icons.Default.RotateLeft, "Turn left") }
                IconButton(
                    onClick = { plan = PagePlan.turnedAll(plan, picked, 1) },
                    enabled = pickedAt.isNotEmpty()
                ) { Icon(Icons.Default.RotateRight, "Turn right") }
                IconButton(
                    onClick = {
                        val (copied, copies) = PagePlan.duplicatedAll(plan, picked) { nextUid++ }
                        plan = copied
                        // Onto the copies, so a second press copies the copies rather than
                        // stacking another set of the originals in between.
                        pickOnly(copies)
                    },
                    enabled = pickedAt.isNotEmpty()
                ) { Icon(Icons.Default.Layers, "Copy") }
                IconButton(
                    onClick = {
                        val near = plan.getOrNull(insertAfter)
                        val dim = if (near != null && near.isNew) {
                            PageDim(near.blankWidth, near.blankHeight)
                        } else {
                            source.pageDim((near?.source ?: 0).coerceIn(0, source.pageCount - 1))
                        }
                        val blank = PlannedPage(
                            source = -1,
                            uid = nextUid++,
                            blankWidth = dim.width,
                            blankHeight = dim.height,
                            paper = PaperSpec()
                        )
                        plan = PagePlan.inserted(plan, insertAfter + 1, listOf(blank))
                        pickOnly(setOf(blank.uid))
                    }
                ) { Icon(Icons.Default.Add, "Add a blank page after this one") }
                IconButton(onClick = { pickFilesToImport() }) {
                    Icon(Icons.Default.LibraryAdd, "Add pages from another file")
                }
                IconButton(
                    // Exports the document as it is on disk, so an unapplied plan would export
                    // something other than what is on screen. Better impossible than explained.
                    enabled = !changed && pickedAt.isNotEmpty() && pickedAt.none { plan[it].isNew },
                    onClick = { onExport(pickedAt.map { plan[it].source }.sorted()) }
                ) { Icon(Icons.Default.Share, "Export") }
                IconButton(
                    onClick = {
                        if (pickedAt.size == plan.size) {
                            pickOnly(setOf(plan[pickedAt.firstOrNull() ?: 0].uid))
                        } else {
                            picked = plan.map { it.uid }.toSet()
                        }
                    }
                ) {
                    Icon(
                        Icons.Default.SelectAll,
                        if (pickedAt.size == plan.size) "Pick one page" else "Pick every page",
                        tint = if (pickedAt.size == plan.size && plan.size > 1) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Box(Modifier.weight(1f))
                val canRemove = pickedAt.isNotEmpty() && pickedAt.size < plan.size
                IconButton(
                    onClick = {
                        val at = pickedAt.first()
                        plan = PagePlan.removedAll(plan, picked)
                        pickOnly(setOf(plan[at.coerceAtMost(plan.lastIndex)].uid))
                    },
                    enabled = canRemove
                ) {
                    Icon(
                        Icons.Default.Delete,
                        if (pickedAt.size > 1) "Remove ${pickedAt.size} pages" else "Remove",
                        tint = if (canRemove) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }

            HorizontalDivider()

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 118.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(plan, key = { _, p -> p.uid }) { index, p ->
                    PageTile(
                        planned = p,
                        position = index,
                        thumb = if (p.isNew) null else thumbs[p.source],
                        selected = p.uid in picked,
                        onClick = { ctrl, shift ->
                            when {
                                shift -> picked = PagePlan.rangeBetween(plan, anchor, p.uid)
                                ctrl -> toggle(p.uid)
                                else -> pickOnly(setOf(p.uid))
                            }
                        },
                        onToggle = { toggle(p.uid) },
                        onDoubleClick = {
                            // Only meaningful for a page that is still where it was; a planned
                            // page has no place in the document to jump to yet.
                            if (!changed && !p.isNew) onGoToPage(p.source)
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
    onClick: (ctrl: Boolean, shift: Boolean) -> Unit,
    onToggle: () -> Unit,
    onDoubleClick: () -> Unit
) {
    val open by rememberUpdatedState(onDoubleClick)
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .selectClick(onClick)
            .secondaryClick { open() }
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
            PickTick(selected, onToggle, Modifier.align(Alignment.TopStart))
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

/**
 * The tick in a page's corner: adds it to the pages picked, or takes it out.
 *
 * What Ctrl-click does, for a pen or a finger. Drawn as an empty ring until it is ticked, so the
 * grid says at a glance that pages can be picked together without a sentence saying so.
 */
@Composable
internal fun PickTick(ticked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(4.dp)
            .size(22.dp)
            .clip(CircleShape)
            .background(
                if (ticked) MaterialTheme.colorScheme.primary
                else Color.White.copy(alpha = 0.85f)
            )
            .border(
                1.5.dp,
                if (ticked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                CircleShape
            )
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
        if (ticked) {
            Icon(
                Icons.Default.Check, "Picked",
                Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}
