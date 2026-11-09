package com.inksheets.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkslate.core.Perform
import com.inkslate.core.PerformAction

@Composable
internal fun PagesCategory(state:SheetsState) {
    val (page,count)=state.pageShown
    Column(Modifier.fillMaxSize().padding(horizontal=12.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(state.current?.title ?: "Pages",style=MaterialTheme.typography.titleSmall,color=widgetColor(ShelfWidget.PAGES),
            maxLines=1,overflow=TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(onClick={Perform.run(PerformAction.PREVIOUS_PAGE)}) {
                Icon(Icons.AutoMirrored.Filled.NavigateBefore,null);Text("Previous")
            }
            Column(horizontalAlignment=Alignment.CenterHorizontally) {
                Text(state.edgeNotice ?: "Page ${page+1} of $count",style=MaterialTheme.typography.titleMedium)
                if(state.readMusic) {
                    val read=ScoreTools.scoreHere(state)?.hasRead(page)==true
                    Text(if(read)"Music read"else"Not read",style=MaterialTheme.typography.labelSmall,
                        color=if(read)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
                }
            }
            TextButton(onClick={Perform.run(PerformAction.NEXT_PAGE)}) {
                Text("Next");Icon(Icons.AutoMirrored.Filled.NavigateNext,null)
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
            TextButton(onClick={Perform.run(PerformAction.FIRST_PAGE)}) {Icon(Icons.Default.FirstPage,null);Text("First")}
            FilledTonalButton(onClick={Perform.openPages?.invoke()}) {Icon(Icons.Default.GridView,null);Text("Page list")}
            TextButton(onClick={Perform.run(PerformAction.LAST_PAGE)}) {Text("Last");Icon(Icons.Default.LastPage,null)}
        }
    }
}

@Composable
internal fun PartsCategory(state:SheetsState) {
    Column(Modifier.fillMaxSize().padding(horizontal=12.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Instrument and part",style=MaterialTheme.typography.titleSmall,color=widgetColor(ShelfWidget.PARTS))
        Text("Choose the PDF part shown for this song, or change your instrument across the library.",
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color=widgetColor(ShelfWidget.PARTS).copy(alpha=.08f),shape=MaterialTheme.shapes.medium) {
            PartSwitcher(state,Modifier.fillMaxWidth().padding(4.dp))
        }
    }
}
