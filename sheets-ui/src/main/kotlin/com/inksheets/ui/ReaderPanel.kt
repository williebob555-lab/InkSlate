package com.inksheets.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

internal val LocalReaderPanelState = staticCompositionLocalOf<SheetsState?> { null }
internal val LocalInsideReaderPanel = staticCompositionLocalOf { false }
internal object ReaderPanels {
    val heights = mutableStateMapOf<String,Float>()
    val order = mutableStateListOf<String>()
    var base by mutableStateOf(0f)
    val height get() = order.lastOrNull()?.let{heights[it]} ?: 0f
    val open get() = heights.isNotEmpty()
}

/** A bounded extension of the viewer's bar, reserving real space in the score viewport. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ReaderPanel(title:String, close:()->Unit, buttons:(@Composable ()->Unit)?, content:@Composable ()->Unit) {
    val density=LocalDensity.current
    DisposableEffect(title) {
        ReaderPanels.heights[title]=0f;ReaderPanels.order.add(title)
        onDispose {ReaderPanels.heights.remove(title);ReaderPanels.order.remove(title)}
    }
    if(ReaderPanels.order.lastOrNull()!=title)return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val limit=minOf(320.dp,maxHeight*.38f,(maxHeight-ReaderPanels.base.dp-120.dp).coerceAtLeast(72.dp))
        Surface(tonalElevation=5.dp,shadowElevation=3.dp,modifier=Modifier.align(Alignment.BottomStart)
            .fillMaxWidth().padding(bottom=ReaderPanels.base.dp).heightIn(max=limit)
            .onSizeChanged {ReaderPanels.heights[title]=with(density){it.height.toDp().value}}) {
            Column(Modifier.padding(horizontal=12.dp)) {
                Row(Modifier.fillMaxWidth().height(32.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.primary)
                    IconButton(onClick=close,modifier=Modifier.size(30.dp)){Icon(Icons.Default.Close,"Close",Modifier.size(18.dp))}
                }
                Box(Modifier.weight(1f,fill=false).fillMaxWidth()) {CompositionLocalProvider(LocalInsideReaderPanel provides true,content=content)}
                buttons?.let {FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){it()}}
            }
        }
    }
}
