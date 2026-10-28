package com.inksheets.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.inksheets.core.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/** A bounded streaming envelope: the display is sampled from the actual recording, never decorative data. */
internal class WaveformEnvelope {
    private val bins = arrayListOf<Float>()
    private var bucket = 256
    private var count = 0
    private var peak = 0f
    fun add(samples: FloatArray) {
        for (sample in samples) {
            peak = max(peak,abs(sample)); count++
            if (count >= bucket) {
                bins += peak; peak=0f; count=0
                if (bins.size >= 8192) {
                    val compact = bins.chunked(2).map { it.max() }
                    bins.clear();bins.addAll(compact);bucket *= 2
                }
            }
        }
    }
    fun finish(): FloatArray {
        val all = bins + if (count > 0) listOf(peak) else emptyList()
        if (all.isEmpty()) return floatArrayOf()
        val top = all.max().coerceAtLeast(.001f)
        return FloatArray(4096) { index ->
            val from = index * all.size / 4096
            val to = ((index+1)*all.size/4096).coerceAtLeast(from+1).coerceAtMost(all.size)
            (all.subList(from.coerceAtMost(all.lastIndex),to).max()/top).coerceIn(0f,1f)
        }
    }
}
private object WaveformCache {
    val data = object : LinkedHashMap<String,FloatArray>(8,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,FloatArray>?) = size > 12
    }
}

@Composable
internal fun RecordingWaveform(state: SheetsState, track: AudioTrack?, position: Long, duration: Long, loaded: Boolean,
                               modifier: Modifier = Modifier, expanded: Boolean = false,
                               onLoopChange: ((Long, Long) -> Unit)? = null,
                               onLoopMark: ((Long) -> Unit)? = null) {
    val file = track?.let { state.fileOf(it.file) }
    val key = file?.takeIf { it.isFile }?.let { "${it.absolutePath}:${it.length()}:${it.lastModified()}" }
    var peaks by remember(key) { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(key) {
        if (key == null || file == null) return@LaunchedEffect
        peaks = synchronized(WaveformCache.data) { WaveformCache.data[key] }
        if (peaks == null) withContext(Dispatchers.IO) {
            val envelope = WaveformEnvelope()
            val context = currentCoroutineContext()
            val ok = try {
                state.platform.decodeAudio(file) { samples, _ -> context.ensureActive(); envelope.add(samples) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                state.platform.log("Waveform unavailable for ${file.name}: ${failure.message}")
                false
            }
            if (ok) envelope.finish().takeIf { it.isNotEmpty() }?.let { result ->
                synchronized(WaveformCache.data) { WaveformCache.data[key] = result }; peaks = result
            }
        }
    }
    val accent = MaterialTheme.colorScheme.primary
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.28f)
    val loop = MaterialTheme.colorScheme.tertiary
    var draft by remember(track?.file,track?.loopStartMs,track?.loopEndMs) {
        mutableStateOf(track?.loopStartMs?.let { a -> track.loopEndMs?.let { b -> LoopRegion(a,b) } })
    }
    var zoom by remember(track?.file) { mutableStateOf(1f) }
    var heldWindow by remember(track?.file) { mutableStateOf<Pair<Long,Long>?>(null) }
    val window = if(expanded) heldWindow ?: waveformWindow(duration,position,draft,zoom) else 0L to duration.coerceAtLeast(1)
    val latestChange by rememberUpdatedState(onLoopChange)
    val latestWindow by rememberUpdatedState(window)
    val latestTrack by rememberUpdatedState(track)
    val latestMark by rememberUpdatedState(onLoopMark)
    if(expanded) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.TextButton(onClick={zoom=(zoom/2).coerceAtLeast(1f)}) { androidx.compose.material3.Text("− Zoom") }
        androidx.compose.material3.Text("${zoom.toInt()}×",modifier=Modifier.padding(top=12.dp))
        androidx.compose.material3.TextButton(onClick={zoom=(zoom*2).coerceAtMost(32f)}) { androidx.compose.material3.Text("+ Zoom") }
    }
    Canvas(modifier.fillMaxWidth().height(if(expanded) 144.dp else 24.dp).clipToBounds().semantics {
        contentDescription = if (loaded) "Recording waveform; tap to seek, hold to mark a practice loop" else if (peaks == null) "Recording waveform unavailable" else "Recording waveform"
    }.pointerInput(loaded,duration,track?.file,expanded,zoom) {
        if(expanded && loaded && duration>0) {
            var original: LoopRegion? = null
            var dragWindow = window
            var target = LoopDrag.BODY
            var delta=0f
            detectDragGestures(onDragStart={ at ->
                dragWindow=latestWindow
                heldWindow=dragWindow
                val region=draft ?: LoopRegion(dragWindow.first,dragWindow.second)
                original=region; delta=0f
                target=loopDragTarget(waveformTime(at.x,size.width.toFloat(),dragWindow),region,((dragWindow.second-dragWindow.first)*24f/size.width).toLong())
            },onDragCancel={
                draft=original
                heldWindow=null
                Recording.player?.setLoop(latestTrack?.loopStartMs,latestTrack?.loopEndMs)
            },onDragEnd={ draft?.let { latestChange?.invoke(it.start,it.end) };heldWindow=null }) { change,amount ->
                change.consume(); delta+=amount.x
                original?.let { initial ->
                    draft=moveLoop(initial,target,(delta/size.width*(dragWindow.second-dragWindow.first)).toLong(),duration)
                    draft?.let { Recording.player?.setLoop(it.start,it.end) }
                }
            }
        }
    }.pointerInput(loaded,duration,track?.file,window) {
        if (loaded && duration > 0) detectTapGestures(
            onTap = { Recording.player?.seek(waveformTime(it.x,size.width.toFloat(),window)) },
            onLongPress = {
                val at=waveformTime(it.x,size.width.toFloat(),window)
                latestMark?.invoke(at) ?: Recording.markLoop(state,at)
            })
    }) {
        fun xOf(time: Long) = (time-window.first).toFloat()/(window.second-window.first).coerceAtLeast(1)*size.width
        if(expanded) draft?.let { region ->
            val a=xOf(region.start);val b=xOf(region.end)
            drawRect(loop.copy(alpha=.15f),Offset(a,0f),androidx.compose.ui.geometry.Size((b-a).coerceAtLeast(0f),size.height))
            drawRect(loop.copy(alpha=.45f),Offset(a,0f),androidx.compose.ui.geometry.Size((b-a).coerceAtLeast(0f),12.dp.toPx()))
        }
        val values = peaks?.let { if(expanded) it else waveformPeaks(it,80) }
        if (values == null) drawLine(quiet,Offset(0f,size.height/2),Offset(size.width,size.height/2),2.dp.toPx())
        else values.forEachIndexed { index, value ->
            val time=((index+.5)/values.size*duration).toLong()
            if(expanded && time !in window.first..window.second) return@forEachIndexed
            val x=if(expanded) xOf(time) else (index+.5f)*size.width/values.size
            val height=(value*.9f+.06f)*size.height
            val played=loaded && duration>0 && index.toFloat()/values.size <=position.toFloat()/duration
            drawLine(if(played)accent else quiet,Offset(x,(size.height-height)/2),Offset(x,(size.height+height)/2),2.dp.toPx(),StrokeCap.Round)
        }
        if (loaded && duration>0) {
            val x=if(expanded) xOf(position) else (position.toFloat()/duration).coerceIn(0f,1f)*size.width
            drawLine(accent,Offset(x,0f),Offset(x,size.height),1.dp.toPx())
            listOfNotNull(if(expanded) draft?.start else track?.loopStartMs,if(expanded) draft?.end else track?.loopEndMs).forEach { mark ->
                val lx=if(expanded) xOf(mark) else (mark.toFloat()/duration).coerceIn(0f,1f)*size.width
                drawLine(loop,Offset(lx,0f),Offset(lx,size.height),2.dp.toPx())
                if(expanded) drawRect(loop,Offset(lx-5.dp.toPx(),0f),androidx.compose.ui.geometry.Size(10.dp.toPx(),28.dp.toPx()))
            }
        }
    }
}

internal data class LoopRegion(val start: Long,val end: Long)
internal fun waveformPeaks(values: FloatArray,count: Int): FloatArray = FloatArray(count) { index ->
    val from=index*values.size/count
    val to=((index+1)*values.size/count).coerceAtLeast(from+1).coerceAtMost(values.size)
    (from until to).maxOfOrNull { values[it] } ?: 0f
}
internal enum class LoopDrag { START,END,BODY }
internal fun loopDragTarget(time: Long,region: LoopRegion,tolerance: Long): LoopDrag = when {
    abs(time-region.start)<=tolerance && abs(time-region.start)<=abs(time-region.end) -> LoopDrag.START
    abs(time-region.end)<=tolerance -> LoopDrag.END
    else -> LoopDrag.BODY
}
internal fun moveLoop(region: LoopRegion,target: LoopDrag,delta: Long,duration: Long): LoopRegion {
    if(duration<=0) return LoopRegion(0,0)
    val gap=100L.coerceAtMost(duration)
    val start=region.start.coerceIn(0,duration-gap)
    val end=region.end.coerceIn(start+gap,duration)
    return when(target) {
        LoopDrag.START -> LoopRegion((start+delta).coerceIn(0,end-gap),end)
        LoopDrag.END -> LoopRegion(start,(end+delta).coerceIn(start+gap,duration))
        LoopDrag.BODY -> { val a=(start+delta).coerceIn(0,duration-(end-start));LoopRegion(a,a+end-start) }
    }
}
internal fun waveformTime(x: Float,width: Float,window: Pair<Long,Long>): Long =
    window.first+((x/width.coerceAtLeast(1f)).coerceIn(0f,1f)*(window.second-window.first)).toLong()
internal fun waveformWindow(duration: Long,position: Long,loop: LoopRegion?,zoom: Float): Pair<Long,Long> {
    val length=(duration/zoom.coerceAtLeast(1f)).toLong().coerceAtLeast(1)
    val center=loop?.let { (it.start+it.end)/2 } ?: position
    val start=(center-length/2).coerceIn(0,(duration-length).coerceAtLeast(0))
    return start to start+length
}
