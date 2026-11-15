package com.inkslate.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class DocumentMenuAction(val label: String, val icon: ImageVector, val run: () -> Unit,
                              val selected: Boolean = false)
data class DocumentMenuSection(val label: String, val icon: ImageVector, val actions: List<DocumentMenuAction>)

/** One bounded menu, with in-place category pages instead of a long flat list or nested popups. */
@Composable
fun DocumentActionsMenu(
    expanded: Boolean, onDismiss: () -> Unit, fileName: String, status: String,
    primary: List<DocumentMenuAction>, quick: List<DocumentMenuAction>,
    sections: List<DocumentMenuSection>, clear: DocumentMenuAction? = null
) {
    var page by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    LaunchedEffect(expanded) { if (!expanded) { page = null; scroll.scrollTo(0) } }
    LaunchedEffect(page) { scroll.scrollTo(0) }
    DropdownMenu(expanded, onDismissRequest = onDismiss,
        modifier = Modifier.width(320.dp).heightIn(max = 560.dp).testTag("document-actions-menu"),
        scrollState = scroll, containerColor = MaterialTheme.colorScheme.surface) {
        val section = sections.firstOrNull { it.label == page }
        if (section == null) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(fileName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                primary.forEach { action ->
                    FilledTonalButton(onClick = action.run, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
                        Icon(action.icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp))
                        Text(action.label, maxLines = 1)
                    }
                }
            }
            quick.forEach { MenuAction(it) }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            sections.forEach { category ->
                DropdownMenuItem(text = { Text(category.label) }, leadingIcon = { Icon(category.icon, null) },
                    trailingIcon = { Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp)) },
                    onClick = { page = category.label })
            }
            clear?.let { action ->
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                MenuAction(action, destructive = true)
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { page = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to document actions") }
                Text(section.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider(Modifier.padding(bottom = 6.dp))
            section.actions.forEach { MenuAction(it) }
        }
    }
}

@Composable
private fun MenuAction(action: DocumentMenuAction, destructive: Boolean = false) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(text = { Text(action.label, color = color) },
        leadingIcon = { Icon(action.icon, null, tint = color) },
        trailingIcon = if (action.selected) ({ Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.primary) }) else null,
        onClick = action.run)
}
