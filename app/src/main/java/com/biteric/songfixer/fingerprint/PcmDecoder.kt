package com.biteric.songfixer.fingerprint

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/** Receives a song's audio as Android's codecs decode it. */
interface PcmSink {
    /** Before the first samples; called again if the decoder changes format midway. */
    fun start(sampleRate: Int, channels: Int)

    /** [frames] frames of interleaved samples in −1..1 (above 1 is possible from float decoders). */
    fun pcm(samples: FloatArray, frames: Int)
}

/** Decodes a song (or its first [maxSeconds]) with MediaExtractor + MediaCodec. */
class PcmDecoder(private val context: Context) {
    /** Returns the whole file's length in seconds (from its header; 0 when unknown). */
    suspend fun decode(uri: Uri, sink: PcmSink, maxSeconds: Int? = null): Int = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio in this file")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply {
                configure(format, null, null, 0)
                start()
            }

            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var float = false
            var started = false
            var framesFed = 0L
            var limit = Long.MAX_VALUE
            var inputDone = false
            var out = FloatArray(0)
            val info = MediaCodec.BufferInfo()

            while (true) {
                coroutineContext.ensureActive()
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
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    if (started) sink.start(rate, channels)
                } else if (o >= 0) {
                    if (!started) {
                        sink.start(rate, channels)
                        limit = maxSeconds?.let { it.toLong() * rate } ?: Long.MAX_VALUE
                        started = true
                    }
                    val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val count = if (float) info.size / 4 else info.size / 2
                    if (out.size < count) out = FloatArray(count)
                    if (float) {
                        buf.asFloatBuffer().get(out, 0, count)
                    } else {
                        val sb = buf.asShortBuffer()
                        for (k in 0 until count) out[k] = sb.get(k) / 32768f
                    }
                    val frames = minOf((count / channels).toLong(), limit - framesFed).toInt()
                    if (frames > 0) sink.pcm(out, frames)
                    framesFed += frames.coerceAtLeast(0)
                    codec.releaseOutputBuffer(o, false)
                    if (framesFed >= limit || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            if (!started) throw IOException("The audio couldn't be decoded")
            (durationUs / 1_000_000).toInt()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
