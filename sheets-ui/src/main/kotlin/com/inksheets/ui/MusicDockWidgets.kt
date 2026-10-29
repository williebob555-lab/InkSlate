package com.inksheets.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkslate.core.Perform
import com.inksheets.core.ConnectionStatus
import com.inksheets.core.Metronome
import java.io.File

internal val LocalDockDense = compositionLocalOf { false }

internal fun widgetColor(widget: ShelfWidget) = when(widget) {
    ShelfWidget.TIMING -> Color(0xFFB46D26)
    ShelfWidget.RECORDING -> Color(0xFF337EC9)
    ShelfWidget.SCORE -> Color(0xFF8B62BA)
    ShelfWidget.DEVICES -> Color(0xFF2C977D)
    ShelfWidget.SESSION -> Color(0xFFC06B46)
}

@Composable
internal fun DockAction(icon: ImageVector, label: String, description: String = label,
                        enabled: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    val color = if(active)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(Modifier.widthIn(min=68.dp).height(if(LocalDockDense.current)28.dp else 32.dp).background(if(active)MaterialTheme.colorScheme.primaryContainer else Color.Transparent,RoundedCornerShape(7.dp))
        .semantics { selected = active }.clickable(enabled=enabled,onClick=onClick).padding(horizontal=3.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
        Icon(icon,description,Modifier.size(16.dp),tint=color.copy(alpha=if(enabled)1f else .28f))
        Text(label,Modifier.padding(start=3.dp),fontSize=10.sp,maxLines=1,color=color.copy(alpha=if(enabled)1f else .28f))
    }
}

@Composable
private fun WidgetHeading(widget: ShelfWidget, title: String, actionDescription: String, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(if(LocalDockDense.current)20.dp else 22.dp).semantics{contentDescription=actionDescription}.clickable(onClick=action),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(12.dp).background(widgetColor(widget),CircleShape))
        Text(title,Modifier.weight(1f).padding(start=6.dp),style=MaterialTheme.typography.labelMedium,
            fontWeight=FontWeight.SemiBold,color=widgetColor(widget),maxLines=1)
        Icon(Icons.Default.ChevronRight,null,Modifier.size(17.dp))
    }
}

@Composable
internal fun MusicDockWidget(state: SheetsState, widget: ShelfWidget, position: Long, controllers: () -> Unit, goToBar: () -> Unit) {
    Column(Modifier.fillMaxSize().background(widgetColor(widget).copy(alpha=.065f)).padding(horizontal=10.dp,vertical=if(LocalDockDense.current)4.dp else 5.dp)) {
        when(widget) {
            ShelfWidget.SESSION -> SessionWidget(state)
            ShelfWidget.TIMING -> {
                val taps=remember { mutableStateListOf<Long>() }
                WidgetHeading(widget,"Timing","Timing settings") { state.metronomeOpen=true }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("${SharedMetronome.bpm.toInt()}",Modifier.semantics { contentDescription="Metronome settings" }.clickable { state.metronomeOpen=true },fontSize=29.sp,fontWeight=FontWeight.Light)
                    Column(Modifier.weight(1f).padding(start=5.dp)) {
                        Text("BPM",fontSize=9.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${SharedMetronome.engine?.settings?.beatsPerBar ?: 4} / 4",fontSize=10.sp)
                    }
                    FilledTonalIconButton(onClick={ Click.toggle(state) },modifier=Modifier.size(34.dp)) {
                        Icon(if(SharedMetronome.running)Icons.Default.Pause else Icons.Default.PlayArrow,"Start or pause metronome",Modifier.size(20.dp))
                    }
                    IconButton(onClick={state.tunerOpen=!state.tunerOpen},modifier=Modifier.size(30.dp)) { Icon(Icons.Default.GraphicEq,"Tuner",Modifier.size(18.dp)) }
                }
                Row(Modifier.fillMaxWidth().height(13.dp),horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=Alignment.CenterVertically) {
                    repeat((SharedMetronome.engine?.settings?.beatsPerBar ?: 4).coerceIn(1,12)) { beat ->
                        val lit=SharedMetronome.running && SharedMetronome.beat==beat
                        Box(Modifier.size(if(lit)8.dp else 6.dp).background(if(lit)widgetColor(widget)else MaterialTheme.colorScheme.outlineVariant,CircleShape)
                            .semantics { contentDescription="Beat ${beat+1}${if(lit) " active" else ""}" })
                    }
                }
                Row {
                    DockAction(Icons.Default.TouchApp,"Tap","Tap tempo") {
                        taps+=System.currentTimeMillis();while(taps.size>9)taps.removeAt(0)
                        Metronome.tapTempo(taps)?.let { bpm ->
                            SharedMetronome.bpm=bpm;Click.engine(state)?.let { it.settings=it.settings.copy(bpm=bpm) }
                        }
                    }
                    DockAction(Icons.Default.Timer,"Count","Meter / count-in") { state.metronomeOpen=true }
                    DockAction(Icons.Default.Hearing,"Follow","Listen and turn the pages",enabled=state.listenTurns,active=Listener.active) { Listener.toggle(state) }
                }
            }
            ShelfWidget.RECORDING -> {
                val track=if(Recording.session)Recording.track else state.current?.audio?.firstOrNull()
                val loaded=track!=null && Recording.loadedFile==track.file
                val duration=if(loaded)Recording.player?.durationMs ?:0L else 0L
                WidgetHeading(widget,if(SelfRecorder.recording)"Recording · ${SelfRecorder.seconds}s" else "Recording","Choose recording") { state.audioOpen=true }
                Text(track?.label ?: if(track!=null)track.file.substringAfterLast('/')else "No recording paired",style=MaterialTheme.typography.labelSmall,
                    modifier=Modifier.fillMaxWidth().clickable { state.audioOpen=true },maxLines=1,overflow=TextOverflow.Ellipsis)
                RecordingWaveform(state,track,position,duration,loaded)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(dockClock(if(loaded)position else 0),fontSize=9.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if(duration>0)dockClock(duration) else "Pair or record",fontSize=9.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                    IconButton(onClick={if(Recording.session)Recording.skip(-5.0)else state.audioOpen=true},modifier=Modifier.size(30.dp)) { Icon(Icons.Default.Replay,"Recording back five seconds",Modifier.size(18.dp)) }
                    FilledTonalIconButton(onClick={if(Recording.session)Recording.playPause(state)else Recording.toggle(state)},modifier=Modifier.size(34.dp)) { Icon(if(Recording.playing)Icons.Default.Pause else Icons.Default.PlayArrow,"Play or pause recording",Modifier.size(20.dp)) }
                    IconButton(onClick={if(track?.loopStartMs!=null)Recording.editTrack(state,track){it.copy(loopStartMs=null,loopEndMs=null)}else if(loaded)Recording.markLoop(state,position)else state.audioOpen=true},modifier=Modifier.size(30.dp)) { Icon(Icons.Default.Repeat,"Recording loop and speed",Modifier.size(18.dp),tint=if(track?.loopStartMs!=null)widgetColor(widget)else MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton(onClick={if(track!=null)Recording.editTrack(state,track){it.copy(speed=when { it.speed<.7 -> .75;it.speed<.9 ->1.0;it.speed<1.1 ->1.25;else ->.5 })}else state.audioOpen=true},contentPadding=PaddingValues(0.dp),modifier=Modifier.width(40.dp).height(32.dp)) { Text("${track?.speed ?:1.0}×",fontSize=10.sp) }
                    IconButton(onClick={ if(SelfRecorder.recording)SelfRecorder.stop(state)else state.current?.let { if(!SelfRecorder.start(state,it))state.audioOpen=true } },modifier=Modifier.size(30.dp)) { Icon(if(SelfRecorder.recording)Icons.Default.Stop else Icons.Default.Mic,"Record myself",Modifier.size(18.dp),tint=if(SelfRecorder.recording)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            ShelfWidget.SCORE -> {
                val page=state.pageShown.first
                val read=ScoreTools.scoreHere(state)?.hasRead(page)==true
                WidgetHeading(widget,"Page ${page+1} · ${if(read) "READ" else "NOT READ"}","All score tools") { ScoreTools.open=true;state.scorePanelOpen=true }
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(maxItemsInEachRow=3) {
                    DockAction(Icons.Default.MusicNote,"Read","Read whole piece",enabled=Transcriber.busy==null &&state.currentPath!=null) { state.currentPath?.let { Transcriber.read(state,File(it)){Perform.marksChanged()} } }
                    DockAction(Icons.Default.Spellcheck,"Review","Review recognized notes",enabled=read,active=ScoreTools.colours) { ScoreTools.open=true;ScoreTools.toggleCheck(state) }
                    DockAction(Icons.Default.AutoFixHigh,"Clean","Clean view",enabled=read,active=ScoreTools.cleanEnabled(state)) { ScoreTools.toggleClean(state) }
                    DockAction(Icons.Default.PlayArrow,"Play","Play notes",enabled=read,active=ScoreTools.playing!=null) { if(ScoreTools.playing!=null)ScoreTools.stop(state)else ScoreTools.playSelected(state) }
                    DockAction(Icons.Default.Groups,"Band","Play other parts",enabled=read,active=ScoreTools.band) { ScoreTools.toggleBand(state) }
                    DockAction(Icons.Default.Tag,"Bar","Go to bar",enabled=read,onClick=goToBar)
                    DockAction(Icons.Default.Brush,"Mask","Clean mask brush",enabled=read,active=ScoreTools.maskBrushEnabled) { ScoreTools.toggleMaskBrush(state) }
                    DockAction(Icons.Default.Repeat,"Loop","Loop selected bars",enabled=read,active=ScoreTools.loop) { ScoreTools.loop=!ScoreTools.loop }
                }
            }
            ShelfWidget.DEVICES -> {
                WidgetHeading(widget,"Connections","Connect device") {state.companionOpen=true}
                val peers=state.companion.connectedDevices+state.remote.connectedDevices
                val rows=peers.distinctBy { it.id }.map { Triple(it.name,it.transport+" · "+when(it.status){ConnectionStatus.CONNECTED->"Connected";ConnectionStatus.RECONNECTING->"Reconnecting";else->"Disconnected"},it.status==ConnectionStatus.CONNECTED) }
                val peripherals=state.controllers.devices.map {Triple(it,"Page controller",true)}+state.watch.watches.map {Triple(it,"Watch",true)}
                val devices=(rows+peripherals)
                if(devices.isEmpty()) DeviceRow("No devices connected","Connect a stand, remote or pedal",false) {state.companionOpen=true}
                else devices.take(2).forEach { DeviceRow(it.first,it.second,it.third) {state.companionOpen=true} }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    DockAction(Icons.Default.SettingsRemote,"Remote","Remote") {state.remote.remoteOpen=true}
                    DockAction(Icons.Default.SettingsInputComponent,"Pedals","Pedals and watch",onClick=controllers)
                    DockAction(Icons.Default.Devices,if(devices.size>2)"+${devices.size-2}" else "Group","Play together") {state.companionOpen=true}
                }
            }
        }
    }
}

@Composable private fun DeviceRow(name:String,detail:String,connected:Boolean,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().height(29.dp).clickable(onClick=onClick),verticalAlignment=Alignment.CenterVertically) {
        Icon(if(detail.contains("Watch"))Icons.Default.Watch else if(detail.contains("controller"))Icons.Default.SettingsInputComponent else Icons.Default.TabletAndroid,null,
            Modifier.size(17.dp),tint=if(connected)widgetColor(ShelfWidget.DEVICES)else MaterialTheme.colorScheme.outline)
        Column(Modifier.weight(1f).padding(start=7.dp)) {
            Text(name,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(detail,fontSize=9.sp,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.size(5.dp).background(if(connected)widgetColor(ShelfWidget.DEVICES)else MaterialTheme.colorScheme.tertiary,CircleShape))
    }
}
internal fun dockClock(ms:Long)="%d:%02d".format(ms.coerceAtLeast(0)/60000,ms.coerceAtLeast(0)/1000%60)
