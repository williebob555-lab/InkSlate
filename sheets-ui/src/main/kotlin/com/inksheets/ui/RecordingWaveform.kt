package com.inksheets.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
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
    val key = remember(file?.absolutePath,state.version,loaded) {
        file?.takeIf { it.isFile }?.let { "${it.absolutePath}:${it.length()}:${it.lastModified()}" }
    }
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
    val draftState = remember(track?.file,track?.loopStartMs,track?.loopEndMs) {
        mutableStateOf(track?.loopStartMs?.let { a -> track.loopEndMs?.let { b -> LoopRegion(a,b) } })
    }
    var draft by draftState
    var zoom by remember(track?.file) { mutableStateOf(1f) }
    var heldWindow by remember(track?.file) { mutableStateOf<Pair<Long,Long>?>(null) }
    val window = if(expanded) heldWindow ?: waveformWindow(duration,position,draft,zoom) else 0L to duration.coerceAtLeast(1)
    val latestChange by rememberUpdatedState(onLoopChange)
    val latestWindow by rememberUpdatedState(window)
    val latestTrack by rememberUpdatedState(track)
    val latestMark by rememberUpdatedState(onLoopMark)
    val livePosition=rememberUpdatedState(position)
    val liveLoaded=rememberUpdatedState(loaded)
    // The costly waveform geometry changes only with its viewport, data or size. Handle and
    // playback-position updates invalidate drawing, without rebuilding thousands of segments.
    val waveformDrawing=remember(peaks,window,duration,expanded,accent,quiet,loop,draftState) {
        Modifier.drawWithCache {
            val values=peaks?.let { waveformVisiblePeaks(it,duration,window,(size.width/2).toInt().coerceIn(1,2048)) }
            val path=Path()
            values?.forEachIndexed { index,value ->
                val x=(index+.5f)*size.width/values.size
                val height=(value*.9f+.06f)*size.height
                path.moveTo(x,(size.height-height)/2);path.lineTo(x,(size.height+height)/2)
            }
            onDrawWithContent {
                fun xOf(time:Long)=(time-window.first).toFloat()/(window.second-window.first).coerceAtLeast(1)*size.width
                if(expanded)draftState.value?.let { region ->
                    val a=xOf(region.start);val b=xOf(region.end)
                    drawRect(loop.copy(alpha=.15f),Offset(a,0f),androidx.compose.ui.geometry.Size((b-a).coerceAtLeast(0f),size.height))
                    drawRect(loop.copy(alpha=.45f),Offset(a,0f),androidx.compose.ui.geometry.Size((b-a).coerceAtLeast(0f),12.dp.toPx()))
                }
                if(values==null)drawLine(quiet,Offset(0f,size.height/2),Offset(size.width,size.height/2),2.dp.toPx())
                else {
                    val stroke=Stroke(2.dp.toPx(),cap=StrokeCap.Round)
                    drawPath(path,quiet,style=stroke)
                    if(liveLoaded.value)clipRect(right=xOf(livePosition.value).coerceIn(0f,size.width)) {drawPath(path,accent,style=stroke)}
                }
                drawContent()
            }
        }
    }
    if(expanded) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.TextButton(onClick={heldWindow=null;zoom=(zoom/2).coerceAtLeast(1f)}) { androidx.compose.material3.Text("− Zoom") }
        androidx.compose.material3.Text("${zoom.toInt()}×",modifier=Modifier.padding(top=12.dp))
        androidx.compose.material3.TextButton(onClick={heldWindow=null;zoom=(zoom*2).coerceAtMost(32f)}) { androidx.compose.material3.Text("+ Zoom") }
    }
    Canvas(modifier.fillMaxWidth().height(if(expanded) 144.dp else 24.dp).clipToBounds().semantics {
        contentDescription = if (loaded) "Recording waveform; tap to seek, hold to mark a practice loop" else if (peaks == null) "Recording waveform unavailable" else "Recording waveform"
    }.then(waveformDrawing).pointerInput(loaded,duration,track?.file,expanded,zoom) {
        if(expanded && loaded && duration>0) {
            awaitEachGesture {
                val down=awaitFirstDown()
                val original=draft
                val dragWindow=latestWindow
                val time=waveformTime(down.position.x,size.width.toFloat(),dragWindow)
                val tolerance=((dragWindow.second-dragWindow.first)*24.dp.toPx()/size.width).toLong()
                val target=original?.let {loopDragTarget(time,it,tolerance)} ?: LoopDrag.BODY
                // Grabbing a handle acts at once. Body dragging waits only for horizontal
                // slop so scrolling the surrounding recording controls still works normally.
                val first=if(original!=null && target!=LoopDrag.BODY) down.also {it.consume()}
                    else {
                        val canDrag=original!=null && time in original.start..original.end
                        val change=withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            if(canDrag)awaitHorizontalTouchSlopOrCancellation(down.id) {moved,_->moved.consume()}
                            else { waitForUpOrCancellation();null }
                        }
                        if(change==null) {
                            val pointer=currentEvent.changes.firstOrNull {it.id==down.id}
                            if(pointer!=null && !pointer.isConsumed) {
                                if(pointer.pressed) {
                                    latestMark?.invoke(time) ?: Recording.markLoop(state,time)
                                    waitForUpOrCancellation()?.consume()
                                } else {
                                    pointer.consume()
                                    Recording.player?.seek(time)
                                }
                            }
                            return@awaitEachGesture
                        }
                        change
                    }
                if(original==null)return@awaitEachGesture
                heldWindow=dragWindow
                val preview=LoopPreviewGate { region -> Recording.player?.setLoop(region.start,region.end) }
                fun move(x:Float) {
                    val region=moveLoop(original,target,((x-down.position.x)/size.width*(dragWindow.second-dragWindow.first)).toLong(),duration)
                    draft=region;preview.offer(region,System.nanoTime()/1_000_000)
                }
                move(first.position.x)
                val ended=if(target!=LoopDrag.BODY)drag(first.id) {change->change.consume();move(change.position.x)}
                    else horizontalDrag(first.id) {change->change.consume();move(change.position.x)}
                if(ended)draft?.takeIf {it!=original}?.let { region ->
                    preview.finish(region)
                    latestChange?.invoke(region.start,region.end)
                } else {
                    draft=original
                    Recording.player?.setLoop(latestTrack?.loopStartMs,latestTrack?.loopEndMs)
                }
                // Retain this viewport after release. Recentering on the newly trimmed loop
                // made every small adjustment jump under the finger; Zoom explicitly recenters.
            }
        }
    }.pointerInput(loaded,duration,track?.file) {
        if (!expanded && loaded && duration > 0) detectTapGestures(
            onTap = { Recording.player?.seek(waveformTime(it.x,size.width.toFloat(),latestWindow)) },
            onLongPress = {
                val at=waveformTime(it.x,size.width.toFloat(),latestWindow)
                latestMark?.invoke(at) ?: Recording.markLoop(state,at)
            })
    }) {
        fun xOf(time: Long) = (time-window.first).toFloat()/(window.second-window.first).coerceAtLeast(1)*size.width
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

/** Bound Android handler messages during a drag; every release still applies its exact endpoint. */
internal class LoopPreviewGate(private val apply:(LoopRegion)->Unit) {
    private var sent:LoopRegion?=null
    private var sentAt:Long?=null
    fun offer(region:LoopRegion,nowMs:Long) {
        if(region==sent || sentAt?.let {nowMs-it<50}==true)return
        sent=region;sentAt=nowMs;apply(region)
    }
    fun finish(region:LoopRegion) {
        if(region!=sent) {sent=region;apply(region)}
    }
}

/** One peak per visible screen column, preserving transients without drawing offscreen samples. */
internal fun waveformVisiblePeaks(values:FloatArray,duration:Long,window:Pair<Long,Long>,count:Int):FloatArray {
    if(values.isEmpty() || duration<=0)return floatArrayOf()
    val from=(window.first.toDouble()/duration*values.size).toInt().coerceIn(0,values.lastIndex)
    val end=kotlin.math.ceil(window.second.toDouble()/duration*values.size).toInt().coerceIn(from+1,values.size)
    val bins=count.coerceAtLeast(1).coerceAtMost(end-from)
    return FloatArray(bins) { index ->
        val a=from+index*(end-from)/bins
        val b=(from+(index+1)*(end-from)/bins).coerceAtLeast(a+1)
        (a until b).maxOf {values[it]}
    }
}
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
