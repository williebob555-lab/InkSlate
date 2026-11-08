package com.inksheets.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.inkslate.core.Perform
import com.inkslate.core.PerformAction

/** Annotation controls live as a peer of Timing, Recording and Score, not as a permanent footer. */
@Composable
internal fun AnnotationCategory(state: SheetsState) {
    val toolsOut=Perform.isOn!=null && !Perform.on(PerformAction.FULLSCREEN)
    Column(Modifier.fillMaxSize().padding(horizontal=12.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Draw on the original PDF",style=MaterialTheme.typography.titleSmall,color=widgetColor(ShelfWidget.ANNOTATION))
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            DockAction(Icons.Default.Edit,"Pen","Pen",active=Perform.on(PerformAction.PEN)) { Perform.run(PerformAction.PEN) }
            DockAction(Icons.Default.CleaningServices,"Erase","Eraser",active=Perform.on(PerformAction.ERASER)) { Perform.run(PerformAction.ERASER) }
            DockAction(Icons.AutoMirrored.Filled.Undo,"Undo") { Perform.run(PerformAction.UNDO) }
            DockAction(Icons.AutoMirrored.Filled.Redo,"Redo") { Perform.run(PerformAction.REDO) }
            DockAction(if(toolsOut)Icons.Default.Close else Icons.Default.Construction,if(toolsOut)"Hide tools" else "All tools",
                if(toolsOut)"Hide the full annotation toolbar" else "Open the full annotation toolbar",active=toolsOut) { Perform.run(PerformAction.FULLSCREEN) }
            val marked=state.bookmarkHere()!=null
            DockAction(if(marked)Icons.Default.Bookmark else Icons.Default.BookmarkBorder,if(marked)"Remove mark" else "Bookmark",
                if(marked)"Remove bookmark from this page" else "Bookmark this page",active=marked) { state.toggleBookmark() }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("Page ${state.pageShown.first+1} of ${state.pageShown.second}",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={state.clearingMarks=true}) {Text("Clear markings",color=MaterialTheme.colorScheme.error)}
        }
    }
}
