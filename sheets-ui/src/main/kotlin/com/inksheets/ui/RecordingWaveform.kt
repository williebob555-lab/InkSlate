package com.inksheets.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    private var bucket = 1024
    private var count = 0
    private var peak = 0f
    fun add(samples: FloatArray) {
        for (sample in samples) {
            peak = max(peak,abs(sample)); count++
            if (count >= bucket) {
                bins += peak; peak=0f; count=0
                if (bins.size >= 192) {
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
        return FloatArray(80) { index ->
            val from = index * all.size / 80
            val to = ((index+1)*all.size/80).coerceAtLeast(from+1).coerceAtMost(all.size)
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
                               modifier: Modifier = Modifier) {
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
    Canvas(modifier.fillMaxWidth().height(24.dp).semantics {
        contentDescription = if (loaded) "Recording waveform; tap to seek, hold to mark a practice loop" else if (peaks == null) "Recording waveform unavailable" else "Recording waveform"
    }.pointerInput(loaded,duration,track?.file) {
        if (loaded && duration > 0) detectTapGestures(
            onTap = { Recording.player?.seek((it.x/size.width*duration).toLong().coerceIn(0,duration)) },
            onLongPress = { Recording.markLoop(state,(it.x/size.width*duration).toLong().coerceIn(0,duration)) })
    }) {
        val values = peaks
        if (values == null) drawLine(quiet,Offset(0f,size.height/2),Offset(size.width,size.height/2),2.dp.toPx())
        else values.forEachIndexed { index, value ->
            val x=(index+.5f)*size.width/values.size
            val height=(value*.9f+.06f)*size.height
            val played=loaded && duration>0 && index.toFloat()/values.size <=position.toFloat()/duration
            drawLine(if(played)accent else quiet,Offset(x,(size.height-height)/2),Offset(x,(size.height+height)/2),2.dp.toPx(),StrokeCap.Round)
        }
        if (loaded && duration>0) {
            val x=(position.toFloat()/duration).coerceIn(0f,1f)*size.width
            drawLine(accent,Offset(x,0f),Offset(x,size.height),1.dp.toPx())
            listOfNotNull(track?.loopStartMs,track?.loopEndMs).forEach { mark ->
                val lx=(mark.toFloat()/duration).coerceIn(0f,1f)*size.width
                drawLine(loop,Offset(lx,0f),Offset(lx,size.height),2.dp.toPx())
            }
        }
    }
}
