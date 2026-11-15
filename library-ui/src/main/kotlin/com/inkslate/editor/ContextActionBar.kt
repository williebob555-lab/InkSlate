package com.inkslate.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.inkslate.core.AnnotationLayers
import com.inkslate.core.Palette
import com.inkslate.core.SelectionProperties
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** One visual and interaction contract for tablet, desktop and both app flavors. */
class ContextActions(
    val restyle: (Int?, Float?) -> Unit,
    val duplicate: (() -> Unit)? = null,
    val delete: (() -> Unit)? = null,
    val done: () -> Unit,
    val copy: (() -> Unit)? = null,
    val cut: (() -> Unit)? = null,
    val edit: (() -> Unit)? = null,
    val editLabel: String = "Edit settings",
    val crop: (() -> Unit)? = null,
    val savePicture: (() -> Unit)? = null,
    val transferLayer: ((Int, Boolean) -> Unit)? = null
)

@Composable
fun ContextActionBar(
    properties: SelectionProperties,
    actions: ContextActions,
    modifier: Modifier = Modifier,
    placing: Boolean = false,
    editing: Boolean = false
) {
    Surface(modifier.fillMaxWidth().testTag("context-bar"),
        color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 2.dp) {
        BoxWithConstraints {
            // Below this budget, properties get a separate lane; fixed actions never compete with them.
            val compact = maxWidth < 760.dp
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).height(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    Column(Modifier.padding(start = 10.dp, end = 8.dp).then(
                        if (compact) Modifier.weight(1f) else Modifier.widthIn(max = 160.dp))) {
                        Text(if (placing) "PLACE REPEATEDLY" else if (editing) "EDITING" else "SELECTED",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(properties.title, style = MaterialTheme.typography.titleSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!compact) {
                        PropertyLane(properties, actions, Modifier.weight(1f).padding(horizontal = 8.dp))
                        VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
                    }
                    actions.duplicate?.let { action ->
                        Action("Duplicate", Icons.Default.ContentCopy, action, compact = compact)
                    }
                    actions.delete?.let { action ->
                        Action("Delete", Icons.Default.DeleteOutline, action, compact = compact, destructive = true)
                    }
                    FilledTonalButton(onClick = actions.done, contentPadding = PaddingValues(horizontal = 12.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                        modifier = Modifier.heightIn(min = 44.dp).testTag("context-done")) {
                        Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp)); Text("Done", maxLines = 1)
                    }
                }
                if (compact) {
                    HorizontalDivider(Modifier.padding(top = 5.dp, bottom = 5.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
                    PropertyLane(properties, actions, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun PropertyLane(p: SelectionProperties, a: ContextActions, modifier: Modifier) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    Row(modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
    Row(Modifier.weight(1f).horizontalScroll(scroll).heightIn(min = 44.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        a.edit?.let { Action(a.editLabel, Icons.Default.Tune, it, filled = true) }
        a.crop?.let { Action("Crop", Icons.Default.Crop, it, filled = true) }
        if (p.canColor) ColorProperty(p.color) { a.restyle(it, null) }
        if (p.canWidth) WidthProperty(p.width) { a.restyle(null, it) }
        a.transferLayer?.let { LayerProperty(p.layerMask, it) }
        MoreActions(a)
        if (!p.canColor && !p.canWidth && a.crop == null && a.edit == null && a.transferLayer == null) {
            Text("Move or resize on the page", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        }
    }
    if (scroll.maxValue > 0) IconButton(onClick = {
        scope.launch { scroll.animateScrollTo(if (scroll.canScrollForward) (scroll.value + 220).coerceAtMost(scroll.maxValue) else 0) }
    }, modifier = Modifier.size(36.dp)) {
        Icon(if (scroll.canScrollForward) Icons.Default.ChevronRight else Icons.Default.ChevronLeft,
            if (scroll.canScrollForward) "More object properties" else "First object properties", Modifier.size(20.dp))
    }
    }
}

@Composable
private fun Action(label: String, icon: ImageVector, click: () -> Unit,
                   compact: Boolean = false, destructive: Boolean = false, filled: Boolean = false) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    if (compact) {
        // Short labels remain visible even at phone widths; icon-only controls were hard to learn.
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        Column(Modifier.width((if (label == "Duplicate") 64.dp else 52.dp) * fontScale).heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = click)
            .semantics { contentDescription = label }, horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(icon, null, Modifier.size(18.dp), tint = color)
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = color, maxLines = 1, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    } else {
        Surface(shape = RoundedCornerShape(10.dp), color = if (filled) MaterialTheme.colorScheme.surface
            else Color.Transparent) {
            TextButton(onClick = click, contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier.heightIn(min = 44.dp)) {
                Icon(icon, null, Modifier.size(18.dp), tint = color)
                Spacer(Modifier.width(6.dp)); Text(label, color = color, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ColorProperty(current: Int?, apply: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var hex by remember(current) { mutableStateOf(current?.let { "%06X".format(it and 0xFFFFFF) } ?: "") }
    Box {
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.heightIn(min = 44.dp).clickable { open = true }.padding(horizontal = 10.dp)
                .semantics { contentDescription = "Selection color" }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp).clip(CircleShape).background(current?.let { Color(it) } ?: MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                Spacer(Modifier.width(7.dp)); Text(if (current == null) "Color · Mixed" else "Color", style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Default.ExpandMore, null, Modifier.size(16.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text("Selection color", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(12.dp))
            Palette.COLORS.chunked(4).forEach { row ->
                Row(Modifier.padding(horizontal = 8.dp)) {
                    row.forEach { color ->
                        Box(Modifier.size(44.dp).padding(5.dp).clip(CircleShape).background(Color(color))
                            .border(if (current == color) 3.dp else 1.dp,
                                if (current == color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .clickable { apply(color); open = false }
                            .semantics { contentDescription = "Color #%06X".format(color and 0xFFFFFF) }, contentAlignment = Alignment.Center) {
                            if (current == color) Icon(Icons.Default.Check, null, Modifier.size(18.dp),
                                tint = if (color == -1 || color == 0xFFFDE047.toInt()) Color.Black else Color.White)
                        }
                    }
                }
            }
            Row(Modifier.width(216.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(hex, { hex = it.removePrefix("#").take(8) }, label = { Text("Hex color") },
                    singleLine = true, modifier = Modifier.weight(1f))
                val parsed = hex.takeIf { it.length == 6 || it.length == 8 }?.toLongOrNull(16)
                IconButton(onClick = {
                    parsed?.let { apply(if (hex.length == 6) (it or 0xFF000000L).toInt() else it.toInt()) }; open = false
                }, enabled = parsed != null) { Icon(Icons.Default.Check, "Apply hex color") }
            }
        }
    }
}

@Composable
private fun WidthProperty(current: Float?, apply: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val widths = Palette.WIDTHS
    var index by remember(current) { mutableStateOf(widths.indices.minByOrNull { abs(widths[it] - (current ?: 2f)) } ?: 0) }
    Box {
        Action(if (current == null) "Weight · Mixed" else "Weight · ${number(current)}", Icons.Default.LineWeight,
            { open = true }, filled = true)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.width(240.dp).padding(16.dp)) {
                Text("Line weight", style = MaterialTheme.typography.titleSmall)
                Text("${number(widths[index])} pt", color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
                Slider(value = index.toFloat(), onValueChange = { index = it.roundToInt() },
                    valueRange = 0f..widths.lastIndex.toFloat(), steps = widths.size - 2,
                    onValueChangeFinished = { apply(widths[index]) },
                    modifier = Modifier.semantics { contentDescription = "Selection line weight" })
                Text("Release to apply · one undo step", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LayerProperty(mask: Int?, transfer: (Int, Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = mask?.let { value -> (1..3).filter { value and (1 shl (it - 1)) != 0 }.joinToString("+") } ?: "Mixed"
    Box {
        Action("Layer · $name", Icons.Default.Layers, { open = true }, filled = true)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(false to "Move to layer", true to "Copy to layer").forEach { (copy, title) ->
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                (1..3).forEach { number ->
                    val bit = 1 shl (number - 1)
                    DropdownMenuItem(text = { Text("Layer $number") }, leadingIcon = {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(Color(AnnotationLayers.colour(bit))))
                    }, onClick = { transfer(bit, copy); open = false })
                }
            }
        }
    }
}

@Composable
private fun MoreActions(a: ContextActions) {
    if (a.copy == null && a.cut == null && a.savePicture == null) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreHoriz, "More selection actions") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(Triple("Copy", Icons.Default.ContentCopy, a.copy), Triple("Cut", Icons.Default.ContentCut, a.cut),
                Triple("Save as PNG", Icons.Default.SaveAlt, a.savePicture)).forEach { (name, icon, click) ->
                if (click != null) DropdownMenuItem(text = { Text(name) }, leadingIcon = { Icon(icon, null) },
                    onClick = { open = false; click() })
            }
        }
    }
}

private fun number(value: Float): String = "%.2f".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.')
