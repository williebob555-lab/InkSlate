package com.inksheets.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inksheets.core.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

/** Performance controls first; library and detailed settings replace this same dock page. */
@Composable internal fun RecordingCategory(state:SheetsState,close:()->Unit) {
    val song=state.current ?: return
    state.version
    val tracks=state.library?.song(song.id)?.audio ?: song.audio
    val track=tracks.firstOrNull {it.file==Recording.loadedFile} ?: tracks.firstOrNull()
    var details by remember(song.id) {mutableStateOf(false)}
    var recordFailure by remember(song.id) {mutableStateOf(false)}
    val player=remember(state){Recording.playerFor(state)}
    LaunchedEffect(track?.file) {
        track?.let {withContext(Dispatchers.IO){Recording.peek(state,it,song.id)}}
    }
    val loaded=track!=null && Recording.loadedFile==track.file
    var playhead by remember(track?.file) {mutableStateOf(0L)}
    var duration by remember(track?.file) {mutableStateOf(0L)}
    LaunchedEffect(loaded,track?.file) {
        while(loaded) {
            playhead=player?.positionMs ?: 0L
            duration=player?.durationMs ?: 0L
            delay(100)
        }
    }
    fun update(change:(AudioTrack)->AudioTrack) {
        val old=track ?: return
        val current=state.library?.song(song.id) ?: song
        val newest=current.audio.firstOrNull {it.file==old.file} ?: return
        val changed=change(newest)
        state.change {editSong(song.id){audio=current.audio.map {if(it.file==old.file)changed else it}}}
        Recording.apply(changed)
    }
    SheetDialog("Recording",onDismiss=close) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(44.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={
                    if(loaded && Recording.session)Recording.playPause(state)
                    else track?.let {Recording.requestPlay(state,it,song)}
                },enabled=track!=null && !Recording.loading,modifier=Modifier.size(40.dp)) {
                    Icon(if(loaded && Recording.playing)Icons.Default.Pause else Icons.Default.PlayArrow,"Play or pause recording")
                }
                TextButton(onClick={details=true},modifier=Modifier.weight(1f),contentPadding=PaddingValues(0.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(track?.label ?: track?.file?.substringAfterLast('/') ?: "Recordings",maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelMedium)
                        if(track!=null) Text("${dockClock(if(loaded)playhead else 0)} / ${dockClock(duration)}",style=MaterialTheme.typography.labelSmall)
                    }
                }
                IconButton(onClick={
                    if(SelfRecorder.recording)SelfRecorder.stop(state)
                    else {recordFailure=!SelfRecorder.start(state,song);if(!recordFailure)close()}
                },enabled=state.platform.microphone!=null,modifier=Modifier.size(40.dp)) {
                    Icon(if(SelfRecorder.recording)Icons.Default.Stop else Icons.Default.Mic,"Record yourself",tint=MaterialTheme.colorScheme.error)
                }
                IconButton(onClick={details=true},modifier=Modifier.size(40.dp)) {
                    Icon(Icons.Default.Tune,"Recording settings and library")
                }
            }
            if(track!=null) RecordingWaveform(state,track,if(loaded)playhead else 0,duration,loaded,
                modifier=Modifier.weight(1f),expanded=true,
                onLoopChange={a,b->update {it.copy(loopStartMs=a,loopEndMs=b)}},
                onLoopMark={at->update {old->
                    val start=old.loopStartMs
                    if(start==null || old.loopEndMs!=null || at<=start)old.copy(loopStartMs=at,loopEndMs=null)
                    else old.copy(loopEndMs=at)
                }})
            else TextButton(onClick={details=true},modifier=Modifier.fillMaxWidth()) {
                Icon(Icons.Default.AudioFile,null);Spacer(Modifier.width(6.dp));Text("Pair a recording")
            }
            if(recordFailure) Text("Could not open microphone",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)
        }
    }
    if(details) AudioDialog(state,song,movable=true){details=false}
}
