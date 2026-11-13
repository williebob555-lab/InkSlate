package com.inkslate.ui.editor

import com.inkslate.core.Stroke.Kind as StrokeKind
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowOutward
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PanoramaFishEye
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Interests
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import android.view.MotionEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkslate.ink.*
import com.inkslate.ink.BrushType
import com.inkslate.ink.DashStyle
import com.inkslate.ink.FillStyle
import com.inkslate.ink.InkPoint
import com.inkslate.ink.Stroke
import com.inkslate.ink.StrokeRasteriser
import com.inkslate.core.EraserMode
import com.inkslate.core.Tool
import kotlin.math.roundToInt
import com.inkslate.core.InputMode
import com.inkslate.core.StylusButtonAction
import com.inkslate.core.AnnotationLayers
import com.inkslate.data.LocalDesignConfig

/** Width budget for the three touch channels; kept pure so narrow layouts stay regression-tested. */
internal fun layerMixerWidthDp(screenWidthDp: Float): Float = if (screenWidthDp < 600f) {
    (screenWidthDp * .42f).coerceIn(168f, 210f)
} else {
    (screenWidthDp * .24f).coerceIn(220f, 280f)
}

/** Fixed controls yield their height before the fader does on a short landscape toolbar. */
internal data class LayerMixerHeights(val visibility: Float, val actions: Float, val fader: Float)

internal fun layerMixerHeights(
    availableHeightDp: Float,
    visibilityHeightDp: Float,
    actionHeightDp: Float,
    minimumFaderHeightDp: Float
): LayerMixerHeights {
    val available = availableHeightDp.coerceAtLeast(0f)
    val reservedFader = minimumFaderHeightDp.coerceAtMost(available * .5f)
    val desiredControls = visibilityHeightDp + actionHeightDp
    val scale = if (desiredControls <= 0f) 0f
        else ((available - reservedFader) / desiredControls).coerceIn(0f, 1f)
    val visibility = visibilityHeightDp * scale
    val actions = actionHeightDp * scale
    return LayerMixerHeights(visibility, actions, available - visibility - actions)
}
/** Everything the toolbar needs to drive, gathered so the signature stays readable. */
class ToolBarActions(
    val onChanged: () -> Unit,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val page: Int,
    val pageCount: Int,
    val pageBookmarked: Boolean,
    val onPreviousPage: () -> Unit,
    val onNextPage: () -> Unit,
    val onOpenPageNavigation: () -> Unit,
    val onTogglePageBookmark: () -> Unit,
    val onRestyleSelection: (Int?, Float?) -> Unit,
    val onDeleteSelection: () -> Unit,
    val onDuplicateSelection: () -> Unit,
    val onCopySelection: () -> Unit,
    val onCutSelection: () -> Unit,
    val onClearSelection: () -> Unit,
    val onPaste: () -> Unit,
    val canPaste: Boolean,
    val onInsertTable: (Int, Int) -> Unit,
    /**
     * Step the input switch on. The argument is which pen button was held as it was tapped -
     * 0 for none - which is how the hidden button profiles are reached.
     */
    val onCycleMode: (Int) -> Unit,
    val onSetButtonAction: (StylusButtonAction) -> Unit,
    val onEditPressureCurve: () -> Unit,
    /** Opens or folds the shapes tray, where the symbols also live. */
    val onToggleShapes: () -> Unit,
    /** Present only while the selection is one stamp, which can then be changed in place. */
    val onEditStamp: (() -> Unit)? = null,
    /** Set when the selection is one text object: opens it in the text editor. */
    val onEditText: (() -> Unit)? = null,
    val onInsertPicture: () -> Unit,
    val onTakePhoto: () -> Unit,
    val onSnapRuler: () -> Unit,
    val onRotateRuler: (Float) -> Unit,
    val onResetRuler: () -> Unit,
    /** Filters currently laid on this page, and the finger-only action that adds/removes one. */
    val layerFiltersMask: Int,
    val onToggleLayerFilter: (Int) -> Unit,
    val onPickCustomColour: () -> Unit,
    /** Shows a passing message on the editor's snackbar. */
    val onMessage: (String) -> Unit,
    val onBeginCrop: () -> Unit,
    val onApplyCrop: () -> Unit,
    val onResetCrop: () -> Unit,
    val onCancelCrop: () -> Unit,
    /**
     * Save the selected picture as a PNG of its own, wherever the person picks. A picture on the
     * page is never a file until someone asks for one.
     */
    val onSavePicture: (() -> Unit)? = null
)

/**
 * The editor's control surface.
 *
 * Two principles: the things touched constantly stay on one visible row and never hide behind a
 * disclosure, and every control is labelled, because an unlabelled glyph in a drawing app is a
 * guess. Undo and redo live here rather than only in the app bar so they survive fullscreen.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ToolBar(
    state: ToolState,
    selectionCount: Int,
    /** True when the selection is a single picture, which is the only thing worth cropping. */
    canCrop: Boolean,
    cropping: Boolean,
    /** The shapes tray is showing, which lights its button. */
    shapesOpen: Boolean = false,
    actions: ToolBarActions
) {
    // A snapshot keyed on the revision counter. Reading the counter as a bare statement was not
    // enough to register a Compose state read, which is why brush changes only appeared after
    // some other event forced a recomposition.
    val cfg = remember(state.revision, state.activeMode) { state.active.snapshot() }
    var expanded by remember { mutableStateOf(false) }

    fun change(block: () -> Unit) {
        block()
        actions.onChanged()
    }

    // Cap the toolbar at a share of the screen and let it scroll internally. In landscape on a
    // tablet the viewport is short, and an unbounded toolbar would push the page off-screen.
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val density = LocalDensity.current
    val windowWidthDp = with(density) { LocalWindowInfo.current.containerSize.width.toDp().value }
    // Height, not width, is what makes the three channels easy to operate with the other hand.
    // Keep enough room for a fingertip per channel without pushing colours into a horizontal
    // scroller on ordinary tablets.
    val mixerWidth = layerMixerWidthDp(windowWidthDp).dp
    val mixerConfig = LocalDesignConfig.current.mixer
    val mixerMinimum = (mixerConfig.visibilityHeightDp + mixerConfig.actionHeightDp +
        mixerConfig.minimumFaderHeightDp + 8f).dp.coerceAtMost(screenHeight * .55f)
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    // The mixer is a full-height sibling, not another row. Reserving its exact
                    // column here guarantees that neither side can overlap the other at any size.
                    .padding(end = mixerWidth + 8.dp)
                    .heightIn(min = mixerMinimum, max = screenHeight * 0.55f)
                    .verticalScroll(rememberScrollState())
            ) {

            // ---- cropping, which takes over the toolbar while it is on ----
            AnimatedVisibility(visible = cropping) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Cropping",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            "Drag the corners, or the box itself. The picture itself is kept, so " +
                                "you can widen this again later.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    TextButton(onClick = actions.onResetCrop) { Text("Whole picture") }
                    TextButton(onClick = actions.onCancelCrop) { Text("Cancel") }
                    TextButton(onClick = actions.onApplyCrop) { Text("Crop") }
                }
            }

            // ---- selection actions, shown only while something is selected ----
            AnimatedVisibility(visible = selectionCount > 0 && !cropping) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "$selectionCount selected",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { actions.onRestyleSelection(cfg.color, null) }) {
                        Icon(Icons.Default.BorderColor, "Apply current colour")
                    }
                    actions.onEditText?.let { edit ->
                        IconButton(onClick = edit) { Icon(Icons.Default.Edit, "Edit text") }
                    }
                    actions.onEditStamp?.let { edit ->
                        IconButton(onClick = edit) { Icon(Icons.Default.Tune, "Change this stamp") }
                    }
                    IconButton(onClick = actions.onCopySelection) {
                        Icon(Icons.Default.ContentCopy, "Copy")
                    }
                    IconButton(onClick = actions.onCutSelection) {
                        Icon(Icons.Default.ContentCut, "Cut")
                    }
                    if (canCrop) {
                        IconButton(onClick = actions.onBeginCrop) {
                            Icon(Icons.Default.Crop, "Crop this picture")
                        }
                        actions.onSavePicture?.let { save ->
                            IconButton(onClick = save) {
                                Icon(Icons.Default.SaveAlt, "Save this picture as a PNG")
                            }
                        }
                    }
                    IconButton(onClick = actions.onDuplicateSelection) {
                        Icon(Icons.Default.Layers, "Duplicate")
                    }
                    IconButton(onClick = actions.onDeleteSelection) {
                        Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                    // One tap out of a selection, rather than hunting for empty space to tap.
                    IconButton(onClick = actions.onClearSelection) {
                        Icon(Icons.Default.Close, "Done selecting")
                    }
                }
            }

            // ---- colour + size, always visible ----
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                InputModeToggle(state.activeMode) { held -> actions.onCycleMode(held) }
                Spacer(Modifier.width(6.dp))
                // Deduplicated. A colour picked from the palette is also remembered as a recent
                // one, so without this the same swatch appeared twice and both copies drew
                // themselves as selected - which is what "several colours are selected at once"
                // actually was.
                val swatches = remember(state.customColors) {
                    (state.customColors + ToolState.PALETTE).distinct()
                }
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .72f),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = .42f)
                            )
                        ) {
                            Row(
                                Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                state.presets.forEachIndexed { i, p ->
                                    PresetDot(
                                        color = Color(p.color),
                                        width = p.width,
                                        highlighter = p.brush.isHighlighter,
                                        selected = state.activePreset == i,
                                        onClick = { change { state.selectPreset(i) } },
                                        onUpdate = { change { state.savePresetFromCurrent(i) } },
                                        onDelete = if (state.presets.size > 1) {
                                            { change { state.removePreset(i) } }
                                        } else null
                                    )
                                }
                                IconButton(
                                    onClick = { change { state.addPreset() } },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        "Save this pen as a preset",
                                        Modifier.size(17.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }
                    item {
                        // Opens the full picker. The fixed palette covers the common cases; this
                        // exists because there is no good reason to be limited to them.
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                .clickable { actions.onPickCustomColour() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "+",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    items(swatches) { c ->
                        ColorDot(
                            color = Color(c),
                            selected = cfg.color == c,
                            deletable = c in state.customColors,
                            onDelete = { state.removeCustomColor(c) },
                            onClick = {
                                change {
                                    state.edit { it.color = c }
                                    state.rememberColor(c)
                                    state.activePreset = -1
                                }
                            }
                        )
                    }
                }
            }

            // ---- compact size + other-layer visibility, always visible ----
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isEraser = cfg.tool == Tool.ERASER
                // A ladder of fixed stops, not a continuous range. The slider addresses an index,
                // so every position is a width you can land on again, and the fine end goes far
                // enough down to draw a genuine hairline.
                val ladder = if (isEraser) ToolState.ERASER_SIZES else ToolState.WIDTHS
                val current = if (isEraser) cfg.eraserRadius else cfg.strokeWidth
                Text(
                    if (isEraser) "Eraser" else "Size",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(40.dp)
                )
                SizeLadderControl(
                    value = current,
                    ladder = ladder,
                    eraser = isEraser,
                    modifier = Modifier.weight(if (isEraser) 1f else .66f).height(32.dp),
                    onValue = { w ->
                        change {
                            state.edit { if (isEraser) it.eraserRadius = w else it.strokeWidth = w }
                            state.activePreset = -1
                        }
                    }
                )
                RelativeSizePreview(current, ladder, isEraser)
                if (!isEraser) {
                    Text(
                        "Ink",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 3.dp)
                    )
                    Slider(
                        value = cfg.opacity,
                        onValueChange = { opacity ->
                            change {
                                state.edit { it.opacity = opacity }
                                state.activePreset = -1
                            }
                        },
                        modifier = Modifier.weight(.34f).height(32.dp)
                    )
                }
                // Reads the slider as a thickness on the glass rather than on the page. It lives
                // here rather than in a settings screen because it changes what the control
                // beside it means, and that is not something to find out about later.
                IconButton(
                    onClick = {
                        change {
                            state.edit { it.dynamicWidth = !it.dynamicWidth }
                            // Said once, the first time it is switched on, and never again. The
                            // tinted icon is the reminder after that.
                            if (state.active.dynamicWidth && !state.dynamicWidthHintSeen) {
                                state.noteDynamicWidthHintSeen()
                                actions.onMessage(
                                    "Width follows the zoom: strokes come out the same thickness " +
                                        "on screen however far in you are."
                                )
                            }
                        }
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.ZoomIn,
                        if (cfg.dynamicWidth) "Width follows the zoom (on)"
                        else "Width follows the zoom (off)",
                        Modifier.size(17.dp),
                        tint =
                            if (cfg.dynamicWidth) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    )
                }
            }

            // The barrel profile deliberately shows nothing extra here. It is a third pen
            // alongside Pen and Finger, with the same controls above and no second, differently
            // shaped set of verbs of its own.

            // ---- eraser mode, only while the eraser is the tool in hand ----
            if (cfg.tool == Tool.ERASER) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Takes",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(46.dp)
                    )
                    EraserMode.entries.forEach { mode ->
                        Chip(mode.label, cfg.eraserMode == mode) {
                            change { state.edit { it.eraserMode = mode } }
                        }
                    }
                }
            }

            // ---- tools ----
            // One physical row. Secondary insert tools may scroll, but the tools used to recover
            // from an edit (Undo, Redo and More) are fixed at the end. A FlowRow made the toolbar
            // two rows tall whenever a narrow device could not fit every uncommon insert action,
            // wasting exactly the page space the compact toolbar is meant to preserve.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LazyRow(Modifier.weight(1f)) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ToolButton(Icons.Default.Gesture, "Draw", cfg.tool == Tool.DRAW && !cfg.brush.isHighlighter) {
                                change {
                                    state.edit {
                                        it.tool = Tool.DRAW
                                        if (it.brush.isHighlighter) it.toggleHighlighter()
                                    }
                                }
                            }
                            ToolButton(Icons.Default.BorderColor, "Marker", cfg.brush.isHighlighter && cfg.tool == Tool.DRAW) {
                                // toggles, so leaving the highlighter is one tap and restores your pen
                                change { state.edit { it.toggleHighlighter() } }
                            }
                            ToolButton(Icons.Default.Remove, "Erase", cfg.tool == Tool.ERASER) {
                                change { state.edit { it.tool = Tool.ERASER } }
                            }
                            ToolButton(Icons.Default.SelectAll, "Select", cfg.tool == Tool.SELECT) {
                                change { state.edit { it.tool = Tool.SELECT } }
                            }
                            ToolButton(Icons.Default.OpenWith, "Pan", cfg.tool == Tool.PAN) {
                                change { state.edit { it.tool = Tool.PAN } }
                            }
                            ToolDivider()
                            // Line, arrow, box and oval have moved into the stamp picker. They are shapes you
                            // drag out, like everything else in there, and having them here cost four
                            // permanent slots in front of the tools used constantly.
                            ToolButton(Icons.Default.TextFields, "Text", cfg.tool == Tool.TEXT) {
                                change { state.edit { it.tool = Tool.TEXT } }
                            }
                            // Tables, captures, rulers, stamps and pictures are for homework; music is
                            // marked in pen and text.
                            ToolButton(Icons.Default.Interests, "Shapes", shapesOpen) { actions.onToggleShapes() }
                            if (!com.inkslate.AppFlavor.musicView) {
                                ToolButton(Icons.Default.GridOn, "Table", false) {
                                    actions.onInsertTable(state.tableRows, state.tableCols)
                                }
                                ToolButton(Icons.Default.CropFree, "Capture", cfg.tool == Tool.REGION) {
                                    change { state.edit { it.tool = Tool.REGION } }
                                }
                                ToolButton(Icons.Default.Straighten, "Ruler", state.rulerVisible) {
                                    change { state.rulerVisible = !state.rulerVisible; state.persistNow() }
                                }
                                // Symbols are in the tray as well, so one button covers everything you put on
                                // the page rather than draw on it.
                                ToolButton(Icons.Default.Image, "Picture", false) { actions.onInsertPicture() }
                                ToolButton(Icons.Default.PhotoCamera, "Photo", false) { actions.onTakePhoto() }
                            }
                            if (actions.canPaste) {
                                ToolButton(Icons.Default.ContentPaste, "Paste", false) { actions.onPaste() }
                            }
                        }
                    }
                }
                ToolDivider()
                // These never disappear into the scroller.
                ToolButton(Icons.AutoMirrored.Filled.Undo, "Undo", false, enabled = actions.canUndo) {
                    actions.onUndo()
                }
                ToolButton(Icons.AutoMirrored.Filled.Redo, "Redo", false, enabled = actions.canRedo) {
                    actions.onRedo()
                }
                ToolButton(
                    if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                    if (expanded) "Less" else "More", false
                ) { expanded = !expanded }
            }

            // ---- everything else ----
            AnimatedVisibility(visible = expanded) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                Column(
                    Modifier.weight(1f)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerLow,
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {

                    Label("Brush")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(BrushType.entries) { b ->
                            BrushOption(
                                brush = b,
                                color = cfg.color,
                                selected = cfg.brush == b,
                                onClick = {
                                    change { state.edit { it.selectBrush(b) }; state.activePreset = -1 }
                                }
                            )
                        }
                    }

                    SliderRow(
                        label = "Smoothing",
                        value = cfg.smoothing,
                        range = 0f..1f,
                        display = {
                            when {
                                it < 0.05f -> "Off"
                                it < 0.35f -> "Light"
                                it < 0.7f -> "Medium"
                                else -> "Heavy"
                            }
                        }
                    ) { v -> change { state.edit { it.smoothing = v } } }

                    SliderRow(
                        label = "Opacity",
                        value = cfg.opacity,
                        range = 0.1f..1f,
                        display = { "${(it * 100).toInt()}%" }
                    ) { v -> change { state.edit { it.opacity = v } } }

                    if (cfg.tool == Tool.TEXT) {
                        SliderRow(
                            label = "Text size",
                            value = cfg.textSize,
                            range = 6f..64f,
                            display = { "%.0f".format(it) }
                        ) { v -> change { state.edit { it.textSize = v } } }
                    }

                    if (cfg.tool == Tool.SELECT) {
                        // The same drag, doing the same job, in whichever shape suits what is
                        // being picked up - so it belongs here rather than in a second tool.
                        Label("Select by")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            item {
                                Chip("Box", !state.lassoSelect) {
                                    change { state.lassoSelect = false; state.persistNow() }
                                }
                            }
                            item {
                                Chip("Lasso", state.lassoSelect) {
                                    change { state.lassoSelect = true; state.persistNow() }
                                }
                            }
                        }
                    }

                    if (cfg.tool.isShape) {
                        Label("Line style")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(DashStyle.entries) { d ->
                                Chip(d.label, cfg.dash == d) { change { state.edit { it.dash = d } } }
                            }
                        }
                    }

                    if (cfg.tool == Tool.RECT || cfg.tool == Tool.ELLIPSE) {
                        Label("Fill")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(FillStyle.entries) { f ->
                                Chip(f.label, cfg.fillStyle == f) {
                                    change { state.edit { it.fillStyle = f } }
                                }
                            }
                        }
                        if (cfg.fillStyle != FillStyle.NONE) {
                            Label("Fill colour")
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(state.customColors + ToolState.PALETTE) { c ->
                                    ColorDot(Color(c), cfg.fillColor == c) {
                                        change { state.edit { it.fillColor = c } }
                                    }
                                }
                            }
                        }
                    }

                }

                Column(
                    Modifier.weight(1f)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerLow,
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {

                    if (state.rulerVisible && !com.inkslate.AppFlavor.musicView) {
                        Label("Ruler")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Chip("Snap to 15\u00B0", false) { actions.onSnapRuler() }
                            Chip("Rotate 15\u00B0", false) { actions.onRotateRuler(15f) }
                            Chip("Rotate 90\u00B0", false) { actions.onRotateRuler(90f) }
                            Chip("Recentre", false) { actions.onResetRuler() }
                        }
                    }

                    Label("Input")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Chip(
                            if (state.autoSwitchInput) "Auto pen/finger: on" else "Auto pen/finger: off",
                            state.autoSwitchInput
                        ) {
                            change { state.autoSwitchInput = !state.autoSwitchInput; state.persistNow() }
                        }
                        Chip(
                            if (state.pressureEnabled) "Pressure: on" else "Pressure: off",
                            state.pressureEnabled
                        ) {
                            change { state.pressureEnabled = !state.pressureEnabled; state.persistNow() }
                        }
                        Chip("Pressure curve...", false) { actions.onEditPressureCurve() }
                        Chip(
                            if (state.snapHighlighterToText) "Snap to text: on"
                            else "Snap to text: off",
                            state.snapHighlighterToText
                        ) {
                            change {
                                state.snapHighlighterToText = !state.snapHighlighterToText
                                state.persistNow()
                            }
                        }
                        Chip(if (state.snapShapes) "Snap: on" else "Snap: off", state.snapShapes) {
                            change { state.snapShapes = !state.snapShapes; state.persistNow() }
                        }
                    }
                }
                }
            }
            }

            // A mixer works like a channel strip: it keeps the whole toolbar height instead of
            // being squeezed into whichever row happened to have room. The main column reserved
            // this width above, so every control remains independent and fingertip-sized.
            Box(Modifier.matchParentSize().padding(top = 4.dp, end = 6.dp, bottom = 4.dp)) {
                LayerMixerBlock(
                    state = state,
                    filterMask = actions.layerFiltersMask,
                    onVisibility = { layer -> change { state.toggleLayerVisibility(layer) } },
                    onRecord = { layer -> change { state.toggleLayerRecord(layer) } },
                    onOpacity = { layer, value ->
                        change { state.setLayerOpacity(layer, value, save = false) }
                    },
                    onOpacityFinished = state::persistNow,
                    onFilter = actions.onToggleLayerFilter,
                    modifier = Modifier.align(Alignment.CenterEnd).width(mixerWidth).fillMaxHeight()
                )
            }
        }
    }
}

/** A size is easier to judge as ink than as a decimal, especially while moving quickly. */
@Composable
private fun RelativeSizePreview(value: Float, ladder: FloatArray, eraser: Boolean) {
    val index = ToolState.stopIndex(ladder, value)
    val fraction = if (ladder.size <= 1) 0f else index / ladder.lastIndex.toFloat()
    Canvas(
        Modifier
            .size(38.dp, 28.dp)
            .semantics {
                contentDescription = (if (eraser) "Eraser size" else "Pen size") +
                    if (value < 1f) " %.2f".format(value) else " %.1f".format(value)
            }
    ) {
        drawCircle(
            color = Color.Black.copy(alpha = .14f),
            radius = size.minDimension * .42f,
            center = center
        )
        drawCircle(
            color = if (eraser) Color(0xFFEF4444) else Color(0xFF2563EB),
            radius = size.minDimension * (.08f + .32f * fraction),
            center = center
        )
    }
}

/** A finite size ladder whose marks themselves show the thickness available at each position. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun SizeLadderControl(
    value: Float,
    ladder: FloatArray,
    eraser: Boolean,
    modifier: Modifier = Modifier,
    onValue: (Float) -> Unit
) {
    var trackWidth by remember { mutableFloatStateOf(1f) }
    val selected = ToolState.stopIndex(ladder, value)
    val accent = if (eraser) Color(0xFFEF4444) else MaterialTheme.colorScheme.primary
    val trackColour = MaterialTheme.colorScheme.outlineVariant
    val markColour = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .48f)
    fun choose(x: Float) {
        if (ladder.isEmpty()) return
        val usable = (trackWidth - 16f).coerceAtLeast(1f)
        val fraction = ((x - 8f) / usable).coerceIn(0f, 1f)
        onValue(ladder[(fraction * ladder.lastIndex).roundToInt().coerceIn(0, ladder.lastIndex)])
    }
    Canvas(
        modifier
            .onSizeChanged { trackWidth = it.width.toFloat() }
            .pointerInteropFilter { event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                        choose(event.x); true
                    }
                    else -> false
                }
            }
            .semantics {
                contentDescription = (if (eraser) "Eraser" else "Pen") + " size ${"%.2f".format(value)}"
            }
    ) {
        val left = 8f
        val right = size.width - 8f
        val y = size.height / 2f
        drawLine(trackColour, Offset(left, y), Offset(right, y), 2f)
        ladder.forEachIndexed { index, _ ->
            val x = if (ladder.size <= 1) size.width / 2f
            else left + (right - left) * index / ladder.lastIndex
            // The slider chooses discrete stops by position, so its visual progression should be
            // equally legible by position too. Mapping the real widths here made almost the whole
            // first half look identical and then explode at the end.
            val relative = if (ladder.size <= 1) 0f else index / ladder.lastIndex.toFloat()
            val radius = 1.5f + relative * 4.5f
            drawCircle(
                color = if (index == selected) accent else markColour,
                radius = if (index == selected) radius + 1.8f else radius,
                center = Offset(x, y)
            )
            if (index == selected) {
                drawCircle(Color.White, radius = (radius - .8f).coerceAtLeast(1f), center = Offset(x, y))
                drawCircle(accent, radius = (radius - 2f).coerceAtLeast(1f), center = Offset(x, y))
            }
        }
    }
}

/** Compact page transport; it replaces the full-width navigation bar. */
@Composable
internal fun PageControlIsland(
    page: Int,
    pageCount: Int,
    pageBookmarked: Boolean,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onOpenPageNavigation: () -> Unit,
    onOpenPages: () -> Unit,
    onTogglePageBookmark: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 3.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .25f))
    ) {
        Row(
            Modifier.height(34.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onPreviousPage,
                enabled = page > 0,
                modifier = Modifier.size(32.dp)
            ) { Icon(Icons.Default.ChevronLeft, "Previous page", Modifier.size(18.dp)) }
            TextButton(
                onClick = onOpenPageNavigation,
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 5.dp)
            ) {
                Text("${page + 1}/$pageCount", style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = onOpenPages, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.GridOn, "Edit pages", Modifier.size(19.dp))
            }
            IconButton(
                onClick = onTogglePageBookmark,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    if (pageBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    if (pageBookmarked) "Remove bookmark" else "Bookmark page",
                    Modifier.size(17.dp),
                    tint = if (pageBookmarked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onNextPage,
                enabled = page < pageCount - 1,
                modifier = Modifier.size(32.dp)
            ) { Icon(Icons.Default.ChevronRight, "Next page", Modifier.size(18.dp)) }
        }
    }
}

/** The layer mixer is visually separate because these are channels, not extra colour swatches. */
@Composable
private fun LayerMixerBlock(
    state: ToolState,
    filterMask: Int,
    onVisibility: (Int) -> Unit,
    onRecord: (Int) -> Unit,
    onOpacity: (Int, Float) -> Unit,
    onOpacityFinished: () -> Unit,
    onFilter: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .62f),
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .38f))
    ) {
        BoxWithConstraints {
        val config = LocalDesignConfig.current.mixer
        val heights = layerMixerHeights(
            (maxHeight.value - 8f).coerceAtLeast(0f),
            config.visibilityHeightDp, config.actionHeightDp, config.minimumFaderHeightDp
        )
        Row(
            Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 5.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            listOf(AnnotationLayers.ONE, AnnotationLayers.TWO, AnnotationLayers.THREE)
                .forEachIndexed { index, layer ->
                    LayerChannel(
                        label = (index + 1).toString(),
                        layer = layer,
                        visible = (state.visibleLayerMask and layer) != 0,
                        armed = (state.recordLayerMask and layer) != 0,
                        opacity = state.layerOpacities[index],
                        filterShown = (filterMask and layer) != 0,
                        onVisibility = { onVisibility(layer) },
                        onRecord = { onRecord(layer) },
                        onOpacity = { onOpacity(layer, it) },
                        onOpacityFinished = onOpacityFinished,
                        onFilter = { onFilter(layer) },
                        modifier = Modifier.weight(1f),
                        heights = heights
                    )
                }
        }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LayerChannel(
    label: String,
    layer: Int,
    visible: Boolean,
    armed: Boolean,
    opacity: Float,
    filterShown: Boolean,
    onVisibility: () -> Unit,
    onRecord: () -> Unit,
    onOpacity: (Float) -> Unit,
    onOpacityFinished: () -> Unit,
    onFilter: () -> Unit,
    modifier: Modifier = Modifier,
    heights: LayerMixerHeights
) {
    val colour = Color(AnnotationLayers.colour(layer))
    Column(
        modifier = modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.fillMaxWidth().height(heights.visibility.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colour.copy(alpha = if (visible) .24f else .045f))
                .border(
                    if (visible) 2.dp else 1.dp,
                    colour.copy(alpha = if (visible) 1f else .38f),
                    RoundedCornerShape(11.dp)
                )
                .clickable(onClick = onVisibility)
                .semantics {
                    contentDescription = "Layer $label ${if (visible) "visible" else "muted"}. " +
                        "Tap to ${if (visible) "mute" else "show"}."
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                color = colour.copy(alpha = if (visible) 1f else .55f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }
        VerticalLayerFader(
            opacity,
            colour,
            onOpacity,
            onOpacityFinished,
            Modifier.weight(1f).fillMaxWidth()
        )
        // A single compact row leaves the channel height to the fader on portrait and landscape.
        // Stacking two 44 dp buttons previously consumed 88 dp before the slider was measured.
        Row(Modifier.fillMaxWidth().height(heights.actions.dp)) {
            MixerAction(
                onClick = onRecord,
                icon = Icons.Default.FiberManualRecord,
                label = "Write",
                description = "Layer $label ${if (armed) "writing on" else "writing off"}",
                tint = if (armed) Color(0xFFEF4444)
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f),
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            MixerAction(
                onClick = onFilter,
                icon = Icons.Default.FilterAlt,
                label = "Filter",
                description = "${if (filterShown) "Remove" else "Place"} layer $label filter",
                tint = if (filterShown) colour else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
    }
}

/** Independent action hit regions without Material's 48 dp layout minimum. */
@Composable
private fun MixerAction(
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    description: String,
    tint: Color,
    modifier: Modifier
) {
    Box(
        modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(13.dp), tint = tint)
            Text(label, fontSize = 7.sp, lineHeight = 8.sp, maxLines = 1)
        }
    }
}
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun VerticalLayerFader(
    value: Float,
    colour: Color,
    onValue: (Float) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    var heightPx by remember { mutableFloatStateOf(1f) }
    val density = LocalDensity.current
    val knobRadiusPx = with(density) { 10.dp.toPx() }
    val innerRadiusPx = with(density) { 6.5.dp.toPx() }
    val trackWidthPx = with(density) { 2.dp.toPx() }
    fun choose(y: Float) {
        val inset = knobRadiusPx.coerceAtMost(heightPx / 4f)
        val usable = (heightPx - inset * 2f).coerceAtLeast(1f)
        onValue((1f - (y - inset) / usable).coerceIn(0f, 1f))
    }
    val track = MaterialTheme.colorScheme.outline.copy(alpha = .42f)
    val surface = MaterialTheme.colorScheme.surface
    Canvas(
        modifier
            .onSizeChanged { heightPx = it.height.toFloat() }
            .pointerInteropFilter { event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        choose(event.y); true
                    }
                    MotionEvent.ACTION_UP -> {
                        choose(event.y); onFinished(); true
                    }
                    else -> false
                }
            }
            .semantics { contentDescription = "${(value * 100).roundToInt()} percent opacity" }
    ) {
        val x = size.width / 2f
        val top = knobRadiusPx.coerceAtMost(size.height / 4f)
        val bottom = size.height - top
        val knob = bottom - (bottom - top) * value.coerceIn(0f, 1f)
        drawLine(track, Offset(x, top), Offset(x, bottom), trackWidthPx, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(colour, Offset(x, knob), Offset(x, bottom), trackWidthPx * 1.45f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(surface, knobRadiusPx, Offset(x, knob))
        drawCircle(colour, innerRadiusPx, Offset(x, knob))
    }
}

// ---- pieces ------------------------------------------------------------------

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 3.dp)
    )
}

@Composable
private fun ToolDivider() {
    Box(
        Modifier
            .padding(horizontal = 5.dp)
            .width(1.dp)
            .height(30.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    )
}

/**
 * Which device the settings on screen belong to. Tapping it switches manually; drawing with the
 * other device switches it automatically.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun InputModeToggle(mode: InputMode, onCycle: (Int) -> Unit) {
    // Which pen button was down when this was touched. Read from the raw event, because the
    // whole point of the gesture is that it is a tap *plus* a button, and Compose's own click
    // handling has no notion of a stylus barrel.
    var heldButton by remember { mutableStateOf(0) }

    val icon = when (mode) {
        InputMode.PEN -> Icons.Default.Gesture
        InputMode.TOUCH -> Icons.Default.TouchApp
        else -> Icons.Default.Adjust
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .pointerInteropFilter { event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE,
                    MotionEvent.ACTION_BUTTON_PRESS -> {
                        val state = event.buttonState
                        val index = when {
                            (state and (MotionEvent.BUTTON_STYLUS_SECONDARY or
                                MotionEvent.BUTTON_TERTIARY)) != 0 -> 2
                            (state and (MotionEvent.BUTTON_STYLUS_PRIMARY or
                                MotionEvent.BUTTON_SECONDARY)) != 0 -> 1
                            else -> 0
                        }
                        if (index != 0) heldButton = index
                    }
                }
                // Never consumed: the ordinary click below still has to happen.
                false
            }
            .clickable {
                val held = heldButton
                heldButton = 0
                onCycle(held)
            }
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            icon, null, Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            mode.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun ToolButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .padding(horizontal = 1.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .width(46.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = fg, modifier = Modifier.size(20.dp))
        // Labels, because an unlabelled glyph in a drawing app is a guessing game.
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = fg,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PresetDot(
    color: Color,
    width: Float,
    highlighter: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onUpdate: () -> Unit,
    onDelete: (() -> Unit)?
) {
    var menuOpen by remember { mutableStateOf(false) }
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .border(if (selected) 2.5.dp else 1.dp, ring, CircleShape)
            .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true }),
        contentAlignment = Alignment.Center
    ) {
        // dot size hints at thickness so presets are distinguishable at a glance
        Box(
            Modifier
                .size((8f + width.coerceAtMost(14f)).dp)
                .clip(CircleShape)
                .background(color.copy(alpha = if (highlighter) 0.55f else 1f))
                .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Update from current pen") },
                onClick = { menuOpen = false; onUpdate() }
            )
            onDelete?.let { remove ->
                DropdownMenuItem(
                    text = { Text("Delete preset") },
                    onClick = { menuOpen = false; remove() },
                    leadingIcon = { Icon(Icons.Default.Delete, null) }
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ColorDot(
    color: Color,
    selected: Boolean,
    deletable: Boolean = false,
    onDelete: () -> Unit = {},
    onClick: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(if (selected) 30.dp else 26.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                if (selected) 3.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                CircleShape
            )
            .combinedClickable(onClick = onClick, onLongClick = { if (deletable) menuOpen = true })
    ) {
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Delete custom colour") },
                onClick = { menuOpen = false; onDelete() },
                leadingIcon = { Icon(Icons.Default.Delete, null) }
            )
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    onChange: (Float) -> Unit
) {
    Column(Modifier.padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(display(value), style = MaterialTheme.typography.labelSmall)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.height(28.dp)
        )
    }
}

@Composable
private fun Stepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = { onChange((value - 1).coerceAtLeast(1)) }) { Text("−") }
        Text("$value", style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = { onChange((value + 1).coerceAtMost(20)) }) { Text("+") }
    }
}

/**
 * A brush swatch that actually draws with that brush.
 *
 * Rendered through the same [StrokeRasteriser] the page uses, with a canned pressure curve, so
 * what the swatch shows is exactly what the brush will do. A row of identical-looking names told
 * you nothing about how they differ.
 */
@Composable
private fun BrushOption(
    brush: BrushType,
    color: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    val stroke = remember(brush, color) { sampleStroke(brush, color) }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .width(72.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(Modifier.fillMaxWidth().height(26.dp)) {
            drawIntoCanvas { c ->
                val sx = size.width / SAMPLE_W
                val sy = size.height / SAMPLE_H
                val native = c.nativeCanvas
                val save = native.save()
                native.scale(sx, sy)
                StrokeRasteriser.draw(native, stroke)
                native.restoreToCount(save)
            }
        }
        Text(
            brush.label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

private const val SAMPLE_W = 72f
private const val SAMPLE_H = 26f

/** An S-curve with a pressure ramp: light, heavy, light. */
private fun sampleStroke(brush: BrushType, color: Int): Stroke {
    val n = 26
    val pts = (0 until n).map { i ->
        val t = i / (n - 1f)
        val x = 6f + t * (SAMPLE_W - 12f)
        val y = SAMPLE_H / 2f + kotlin.math.sin(t * Math.PI * 1.6).toFloat() * 6f
        // ramp pressure up and back down so taper and chisel behaviour both show
        val pressure = kotlin.math.sin(t * Math.PI).toFloat().coerceIn(0.08f, 1f)
        InkPoint(x, y, brush.widthFor(brush.defaultWidth.coerceAtMost(7f), pressure))
    }
    return Stroke(
        id = "preview", kind = StrokeKind.FREEHAND, color = color,
        baseWidth = brush.defaultWidth.coerceAtMost(7f), points = pts, brush = brush
    )
}
