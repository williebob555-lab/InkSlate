package com.inksheets.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

internal val LocalReaderPanelState = staticCompositionLocalOf<SheetsState?> { null }
internal val LocalInsideReaderPanel = staticCompositionLocalOf { false }
internal class ReaderPanelStack {
    val heights = mutableStateMapOf<String,Float>()
    val order = mutableStateListOf<String>()
    var base by mutableStateOf(0f)
    val height get() = order.lastOrNull()?.let { heights[it] } ?: 0f
    val open get() = order.isNotEmpty()
}
internal object ReaderPanels {
    private val owners = WeakHashMap<SheetsState,ReaderPanelStack>()
    private val standalone = ReaderPanelStack()
    private val ids = AtomicLong()
    private val registered = mutableMapOf<String,ReaderPanelStack>()
    fun forState(state:SheetsState?):ReaderPanelStack = if(state==null)standalone else owners.getOrPut(state){ReaderPanelStack()}
    fun nextId() = "reader-panel-${ids.incrementAndGet()}"
    // Aggregate diagnostics retained for audits. Layout always uses the owner's stack.
    val heights = mutableStateMapOf<String,Float>()
    val order = mutableStateListOf<String>()
    var base:Float
        get() = order.lastOrNull()?.let { registered[it]?.base } ?: 0f
        set(value) { (owners.values+standalone).forEach { it.base=value } }
    val height get() = order.lastOrNull()?.let{heights[it]} ?: 0f
    val open get() = heights.isNotEmpty()
    fun add(stack:ReaderPanelStack,id:String) {
        stack.heights[id]=0f;stack.order.add(id)
        registered[id]=stack;heights[id]=0f;order.add(id)
    }
    fun resize(stack:ReaderPanelStack,id:String,height:Float) {stack.heights[id]=height;heights[id]=height}
    fun remove(stack:ReaderPanelStack,id:String) {
        stack.heights.remove(id);stack.order.remove(id)
        registered.remove(id);heights.remove(id);order.remove(id)
    }
}

/** A bounded extension of the viewer's bar, reserving real space in the score viewport. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ReaderPanel(title:String, close:()->Unit, buttons:(@Composable ()->Unit)?, content:@Composable ()->Unit) {
    val density=LocalDensity.current
    val owner=LocalReaderPanelState.current
    val stack=remember(owner){ReaderPanels.forState(owner)}
    val id=remember(owner,title,owner?.current?.id,owner?.currentPath){ReaderPanels.nextId()}
    DisposableEffect(stack,id) {
        ReaderPanels.add(stack,id)
        onDispose {ReaderPanels.remove(stack,id)}
    }
    if(stack.order.lastOrNull()!=id)return
    owner?.platform?.readerPanelBackHandler(enabled=true,onBack=close)
    val focus=remember(id){FocusRequester()}
    LaunchedEffect(id) {focus.requestFocus()}
    BoxWithConstraints(Modifier.fillMaxSize().onKeyEvent {
        if(it.type==KeyEventType.KeyDown && it.key==Key.Escape){close();true}else false
    }.focusRequester(focus).focusable()) {
        // Forms need room for one complete field between the header and sticky
        // actions, even when a phone is short or resized by its keyboard.
        val minimum=if(buttons!=null)144.dp else 72.dp
        val scoreClearance=if(buttons!=null)80.dp else 120.dp
        val limit=minOf(320.dp,(maxHeight*.38f).coerceAtLeast(minimum),
            (maxHeight-stack.base.dp-scoreClearance).coerceAtLeast(72.dp))
        Surface(tonalElevation=5.dp,shadowElevation=3.dp,modifier=Modifier.align(Alignment.BottomStart)
            .fillMaxWidth().padding(bottom=stack.base.dp).heightIn(max=limit)
            .onSizeChanged {ReaderPanels.resize(stack,id,with(density){it.height.toDp().value})}) {
            Column(Modifier.padding(horizontal=12.dp)) {
                Row(Modifier.fillMaxWidth().height(32.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.primary,maxLines=1,overflow=TextOverflow.Ellipsis)
                    IconButton(onClick=close,modifier=Modifier.size(30.dp)){Icon(Icons.Default.Close,"Close",Modifier.size(18.dp))}
                }
                Box(Modifier.weight(1f,fill=false).fillMaxWidth()) {CompositionLocalProvider(LocalInsideReaderPanel provides true,content=content)}
                buttons?.let {FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){it()}}
            }
        }
    }
}
