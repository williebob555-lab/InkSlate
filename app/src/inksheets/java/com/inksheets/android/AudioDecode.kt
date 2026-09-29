package com.inksheets.android

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.inkslate.data.EventLog
import java.io.File
import java.nio.ByteOrder

/**
 * A recording's sound as mono floats, a block at a time, through Android's own decoders - every
 * format the tablet plays. For following a recording by ear; nothing is played.
 */
object AudioDecode {
    fun decode(file: File, onChunk: (FloatArray, Int) -> Unit): Boolean = runCatching {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.path)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return false
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var float = false
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    o >= 0 -> {
                        val out = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                        out.position(info.offset); out.limit(info.offset + info.size)
                        val ch = channels.coerceAtLeast(1)
                        val mono = if (float) {
                            val fb = out.asFloatBuffer()
                            FloatArray(fb.remaining() / ch) { k -> var s = 0f; for (c in 0 until ch) s += fb.get(k * ch + c); s / ch }
                        } else {
                            val sb = out.asShortBuffer()
                            FloatArray(sb.remaining() / ch) { k -> var s = 0f; for (c in 0 until ch) s += sb.get(k * ch + c) / 32768f; s / ch }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (mono.isNotEmpty()) onChunk(mono, rate)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
        true
    }.onFailure { EventLog.warn("sheets", "Could not read ${file.name} to follow it: ${it.message}") }.getOrDefault(false)
}
