package com.inksheets.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.inkslate.core.Perform
import com.inkslate.core.PerformAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One bounded workspace: the dock chooses the arrangement which leaves the fitted score larger. */
@Composable
internal fun BoxScope.MusicDock(state: SheetsState, footer: @Composable RowScope.() -> Unit) {
    val density=LocalDensity.current
    val panels=remember(state){ReaderPanels.forState(state)}
    LaunchedEffect(state.root,state.currentPath,state.version,state.readMusic,state.homeInFront,ScoreTools.checking) {
        while(!state.homeInFront && state.readMusic && !ScoreTools.checking) {
            if(state.platform.inForeground)Transcriber.background(state)
            delay(2000)
        }
        Transcriber.pauseBackground(state,true)
    }
    LaunchedEffect(state.metronomeOpen,state.tunerOpen,state.audioOpen,state.companionOpen,state.scorePanelOpen,state.readMusicOpen,state.groupMessageOpen) {
        if(!state.homeInFront && !ScoreTools.checking) when {
            state.metronomeOpen -> state.expandMusicCategory(ShelfWidget.TIMING)
            state.tunerOpen -> state.expandMusicCategory(ShelfWidget.TIMING,"tuner")
            state.audioOpen -> state.expandMusicCategory(ShelfWidget.RECORDING)
            state.companionOpen -> state.expandMusicCategory(ShelfWidget.DEVICES)
            state.scorePanelOpen -> state.expandMusicCategory(ShelfWidget.SCORE)
            state.readMusicOpen -> state.expandMusicCategory(ShelfWidget.SCORE,"reading")
            state.groupMessageOpen -> state.expandMusicCategory(ShelfWidget.SESSION,"message")
        }
    }
    var bottom by remember { mutableStateOf(0f) }
    val active=Recording.session || SelfRecorder.recording || SharedMetronome.running || Listener.active || ScoreTools.barNow()!=null || Click.counting>0 || Transcriber.busy!=null
    val issue=Recording.issue!=null && Recording.issue?.root==state.root?.absolutePath
    val expanded=!state.stripCollapsed && !state.homeInFront
    val visible=(expanded || active || issue || Recording.loading || panels.open || state.expandedMusicCategory!=null) && !state.homeInFront && !ScoreTools.checking
    var controllers by remember { mutableStateOf(false) }
    var goTo by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(0L) }
    LaunchedEffect(Recording.session) { while(Recording.session) { position=Recording.player?.positionMs ?:0;delay(100) } }
    DisposableEffect(state) { onDispose {
        Transcriber.stopBackground(state)
        panels.base=0f
        state.shelfHeight=0f;Overlays.bottomOut=0.dp;Overlays.studioOut=0.dp
        state.platform.setMusicShelfHeight(0f);state.platform.setStudioRailWidth(0f);state.platform.setStudioRailSide(false);Overlays.studioOnRight=false
    } }
    BoxWithConstraints(Modifier.matchParentSize()) {
        val viewportWidth=maxWidth
        val viewportHeight=maxHeight
        val annotation=Perform.isOn!=null && !Perform.on(PerformAction.FULLSCREEN)
        val essentials=annotation && maxHeight<340.dp
        // A wide floating editor gets the rail's space temporarily on a small display.
        // Keep page/transport controls below it and restore the chosen widgets on close.
        val panelNeedsSpace=Overlays.wanted?.let {it+252.dp>maxWidth}==true
        val widgets=if(expanded && state.expandedMusicCategory==null && !issue && !essentials && !panelNeedsSpace && !panels.open && !SelfRecorder.recording)state.shelf.order.filter { it !in state.shelf.hidden && (it!=ShelfWidget.SCORE ||state.readMusic) && (it!=ShelfWidget.SESSION || state.companion.leading || state.companion.following!=null) }else emptyList()
        val widgetHeight=if(maxHeight<440.dp || (active && maxHeight<700.dp))116.dp else 132.dp
        val layout=if(widgets.isEmpty())MusicDockView.SHELF else chooseMusicDock(state.shelf.view,maxWidth.value,maxHeight.value,state.pageAspect,widgetHeight.value)
        val rail=visible && layout==MusicDockView.STUDIO
        val railRight=state.shelf.railSide==StudioRailSide.RIGHT
        LaunchedEffect(visible,bottom,rail,railRight,panels.height) {
            panels.base=bottom
            val height=if(visible)bottom+panels.height else 0f
            state.shelfHeight=height;Overlays.bottomOut=height.dp
            state.platform.setMusicShelfHeight(height)
            Overlays.studioOut=if(rail)236.dp else 0.dp
            Overlays.studioOnRight=railRight
            state.platform.setStudioRailSide(railRight)
            state.platform.setStudioRailWidth(if(rail)236f else 0f)
        }
        if(rail) Surface(tonalElevation=2.dp,modifier=Modifier.align(if(railRight)Alignment.TopEnd else Alignment.TopStart).width(236.dp)
            .fillMaxHeight().padding(bottom=bottom.dp)) {
            Column {
                if(state.companion.leading || state.companion.following!=null) SessionQuickActions(state,quick=true)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                widgets.forEach { widget ->
                    Box(Modifier.fillMaxWidth().height(132.dp)) { MusicDockWidget(state,widget,position,{controllers=true},{goTo=true}) }
                    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.45f))
                }
                }
            }
        }
        if(visible) Surface(tonalElevation=3.dp,shadowElevation=2.dp,modifier=Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .onSizeChanged { bottom=with(density){it.height.toDp().value} }) {
            Column {
                PlaybackProblem(state)
                if(state.expandedMusicCategory!=null) {
                    val footerHeight=if(viewportWidth<600.dp)88.dp else 48.dp
                    val categoryHeight=minOf(420.dp,maxOf(216.dp,viewportHeight*.44f),(viewportHeight-footerHeight-128.dp-(if(active)40.dp else 0.dp)).coerceAtLeast(128.dp))
                    Box(Modifier.fillMaxWidth().height(categoryHeight)){MusicCategoryBar(state,position)}
                }
                if(Recording.loading) Row(Modifier.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(15.dp),strokeWidth=2.dp)
                    Text("Opening recording…",Modifier.weight(1f).padding(start=8.dp),style=MaterialTheme.typography.labelSmall)
                    TextButton(onClick={Recording.end(state)}) {Text("Cancel playback")}
                }
                if(essentials && expanded && state.expandedMusicCategory==null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically) {
                    DockAction(Icons.Default.MusicNote,"Music","Return to music workspace") {Perform.run(PerformAction.FULLSCREEN)}
                    DockAction(Icons.Default.Timer,"${SharedMetronome.bpm.toInt()}","Metronome settings") {state.metronomeOpen=true}
                    DockAction(Icons.Default.PlayArrow,"Click","Start or pause metronome",active=SharedMetronome.running) {Click.toggle(state)}
                    DockAction(Icons.Default.GraphicEq,"Tuner") {state.tunerOpen=!state.tunerOpen}
                    DockAction(Icons.Default.LibraryMusic,"Audio","Choose recording") {state.audioOpen=true}
                    DockAction(Icons.Default.Devices,"Devices") {state.companionOpen=true}
                }
                val shelfScroll=rememberScrollState()
                val scope=rememberCoroutineScope()
                val cellWidth=if(viewportWidth>=1000.dp)maxOf(236.dp,viewportWidth/widgets.size.coerceAtLeast(1))else 236.dp
                // On a phone the overflow must be discoverable without a swipe or a settings visit.
                if(!rail && widgets.size>1 && viewportWidth<cellWidth*widgets.size) Row(Modifier.fillMaxWidth().height(26.dp),verticalAlignment=Alignment.CenterVertically) {
                    val stride=with(density){(cellWidth+1.dp).toPx()}
                    val nearest=widgets.indices.minByOrNull { kotlin.math.abs(shelfScroll.value-(stride*it).coerceAtMost(shelfScroll.maxValue.toFloat())) }
                    widgets.forEachIndexed { index,widget ->
                        val offset=stride*index
                        val selected=index==nearest
                        Text(widget.label,Modifier.weight(1f).fillMaxHeight()
                            .background(if(selected)widgetColor(widget).copy(alpha=.13f)else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable {scope.launch{shelfScroll.animateScrollTo(offset.toInt())}}.padding(top=5.dp),
                            textAlign=androidx.compose.ui.text.style.TextAlign.Center,color=widgetColor(widget),style=MaterialTheme.typography.labelSmall)
                    }
                }
                if(!rail && widgets.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(shelfScroll)) {
                    widgets.forEach { widget ->
                        Box(Modifier.width(cellWidth).height(widgetHeight)) {
                            CompositionLocalProvider(LocalDockDense provides (widgetHeight<132.dp)) { MusicDockWidget(state,widget,position,{controllers=true},{goTo=true}) }
                        }
                        VerticalDivider(Modifier.height(widgetHeight).padding(vertical=8.dp),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.4f))
                    }
                }
                if(expanded && !rail && state.expandedMusicCategory==null && (state.companion.leading || state.companion.following!=null)) SessionQuickActions(state,quick=true)
                if(active) MusicLiveStatus(state,position)
                if(expanded) {
                    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,content=footer)
                } else Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={state.stripCollapsed=false}) {Icon(Icons.Default.UnfoldMore,"Show buttons")}
                    Text(state.edgeNotice ?: "Page ${state.pageShown.first+1} of ${state.pageShown.second}",Modifier.weight(1f),style=MaterialTheme.typography.labelSmall)
                }
            }
        }
        if(!visible && !ScoreTools.checking && !state.homeInFront) IconButton(onClick={state.stripCollapsed=false},modifier=Modifier.align(Alignment.BottomEnd)) {Icon(Icons.Default.UnfoldMore,"Show buttons")}
    }
    if(state.arrangingShelf) DockWorkspaceEditor(state) {state.arrangingShelf=false}
    if(state.scorePanelOpen && (state.homeInFront || ScoreTools.checking)) ScoreToolsPanel(state) {state.scorePanelOpen=false}
    if(controllers) SheetDialog("Pedals & watch",onDismiss={controllers=false},wide=true) {Column(Modifier.verticalScroll(rememberScrollState())) {ControllerSettings(state);WatchSettings(state)}}
    if(goTo) GoToBar(state) {goTo=false}
}

@Composable private fun MusicLiveStatus(state:SheetsState,position:Long) {
    var readingProgress by remember { mutableStateOf(0f) }
    LaunchedEffect(Transcriber.busy!=null) {
        while(Transcriber.busy!=null) {readingProgress=Transcriber.smoothProgress() ?: 0f;delay(150)}
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.primary.copy(alpha=.045f)),verticalAlignment=Alignment.CenterVertically) {
        Transcriber.busy?.let { reading ->
            Column(Modifier.width(190.dp).padding(horizontal=10.dp,vertical=6.dp)) {
                Text(reading,style=MaterialTheme.typography.labelSmall,maxLines=1)
                LinearProgressIndicator(progress={readingProgress},modifier=Modifier.fillMaxWidth().padding(top=3.dp).height(3.dp))
            }
            TextButton(onClick={Transcriber.stopAsked=true},enabled=!Transcriber.stopAsked) {Text(if(Transcriber.stopAsked)"Stopping…"else"Stop reading")}
        }
        if(Click.counting>0) {
            Text("Count in",Modifier.padding(horizontal=8.dp),style=MaterialTheme.typography.labelMedium)
            repeat((SharedMetronome.engine?.settings?.beatsPerBar ?:4).coerceIn(1,12)) {beat ->
                Surface(color=if(SharedMetronome.beat==beat)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    shape=MaterialTheme.shapes.small,modifier=Modifier.padding(2.dp).size(28.dp)) {
                    Box(contentAlignment=Alignment.Center) {Text("${beat+1}",style=MaterialTheme.typography.labelMedium)}
                }
            }
            IconButton(onClick={Recording.pause(state);Click.stop(state)}) {Icon(Icons.Default.Close,"Cancel count-in")}
        }
        if(Recording.session) {
            IconButton(onClick={Recording.replay(state)}) {Icon(Icons.Default.Replay,"From the start again",Modifier.size(18.dp))}
            IconButton(onClick={Recording.playPause(state)}) {Icon(if(Recording.playing)Icons.Default.Pause else Icons.Default.PlayArrow,"Pause or resume active recording",Modifier.size(20.dp))}
            Column(Modifier.widthIn(min=110.dp,max=210.dp).padding(vertical=4.dp)) {
                Text(Recording.interruption ?: if(Recording.playing)"Playing recording"else"Recording paused",style=MaterialTheme.typography.labelMedium)
                Text("${dockClock(position)} / ${dockClock(Recording.player?.durationMs ?:0)}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick={Recording.skip(-5.0)}) {Icon(Icons.Default.FastRewind,"Recording back five seconds",Modifier.size(18.dp))}
            IconButton(onClick={Recording.skip(5.0)}) {Icon(Icons.Default.FastForward,"Recording forward five seconds",Modifier.size(18.dp))}
            IconButton(onClick={Recording.end(state)}) {Icon(Icons.Default.Stop,"Stop recording playback",Modifier.size(18.dp))}
            PlayAlong.hint?.let {TextButton(onClick={PlayAlong.toggle(state)}) {Text(it,style=MaterialTheme.typography.labelSmall)}}
        }
        if(SharedMetronome.running && Click.counting==0) {
            IconButton(onClick={Click.stop(state)}) {Icon(Icons.Default.Stop,"Stop active metronome",Modifier.size(18.dp))}
            Text("${SharedMetronome.bpm.toInt()} BPM",Modifier.padding(end=8.dp),style=MaterialTheme.typography.labelSmall)
        }
        if(SelfRecorder.recording) TextButton(onClick={SelfRecorder.stop(state)}) {Text("Stop recording · ${SelfRecorder.seconds}s",color=MaterialTheme.colorScheme.error)}
        if(ScoreTools.barNow()!=null) {
            IconButton(onClick={if(ScoreTools.playing!=null)ScoreTools.pause(state)else ScoreTools.resume(state)}) {Icon(if(ScoreTools.playing!=null)Icons.Default.Pause else Icons.Default.PlayArrow,"Pause or resume notes",Modifier.size(18.dp))}
            Column(Modifier.width(110.dp).padding(vertical=4.dp)) {
                Text("${if(ScoreTools.playing!=null)"Playing"else"Paused"} · bar ${ScoreTools.barNow()}",style=MaterialTheme.typography.labelSmall)
                LinearProgressIndicator(progress={ScoreTools.playbackProgress},modifier=Modifier.fillMaxWidth().height(3.dp))
            }
            IconButton(onClick={ScoreTools.stepBars(state,-1)}) {Icon(Icons.Default.SkipPrevious,"Back a bar",Modifier.size(18.dp))}
            IconButton(onClick={ScoreTools.stepBars(state,1)}) {Icon(Icons.Default.SkipNext,"On a bar",Modifier.size(18.dp))}
            TextButton(onClick={ScoreTools.nudgeTempo(state,-4)}) {Text("− BPM",style=MaterialTheme.typography.labelSmall)}
            TextButton(onClick={ScoreTools.nudgeTempo(state,4)}) {Text("+ BPM",style=MaterialTheme.typography.labelSmall)}
            IconButton(onClick={ScoreTools.stop(state)}) {Icon(Icons.Default.Stop,"Stop notes",Modifier.size(18.dp))}
        }
        if(Listener.active) {
            ListenGauge(state)
            TextButton(onClick={Listener.stop(state)}) {Text("Stop following")}
        }
    }
}

@Composable private fun DockWorkspaceEditor(state:SheetsState,close:()->Unit) {
    SheetDialog("Arrange workspace",onDismiss=close,buttons={TextButton(onClick={state.shelf.reset();state.shelf.chooseView(MusicDockView.AUTO)}) {Text("Reset")};TextButton(onClick=close){Text("Done")}}) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("View",style=MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                MusicDockView.entries.forEach {view ->FilterChip(selected=state.shelf.view==view,onClick={state.shelf.chooseView(view)},label={Text(view.label)})}
            }
            Text("Auto chooses the layout that leaves the music larger.",style=MaterialTheme.typography.bodySmall)
            Text("Rail side",style=MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                StudioRailSide.entries.forEach {side->FilterChip(selected=state.shelf.railSide==side,onClick={state.shelf.chooseRailSide(side)},label={Text(side.label)})}
            }
            state.shelf.order.forEachIndexed {index,widget ->
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(widget !in state.shelf.hidden,{state.shelf.show(widget,it)})
                    Text(widget.label,Modifier.weight(1f),color=widgetColor(widget))
                    IconButton(onClick={state.shelf.move(widget,-1)},enabled=index>0){Icon(Icons.Default.ArrowUpward,"Move ${widget.label} earlier")}
                    IconButton(onClick={state.shelf.move(widget,1)},enabled=index<state.shelf.order.lastIndex){Icon(Icons.Default.ArrowDownward,"Move ${widget.label} later")}
                }
            }
            Text("Playback and page controls stay available independently of widget order.",style=MaterialTheme.typography.bodySmall)
        }
    }
}
