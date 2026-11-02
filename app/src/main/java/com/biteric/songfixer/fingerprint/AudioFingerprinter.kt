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

/** [fingerprint] is AcoustID's compressed form; [durationSec] the whole file's length. */
class AudioFingerprint(val fingerprint: String, val raw: IntArray, val durationSec: Int)

/** Decodes the start of a song with Android's codecs and fingerprints it like fpcalc does. */
class AudioFingerprinter(private val context: Context) {
    suspend fun fingerprint(uri: Uri, maxSeconds: Int = 120): AudioFingerprint = withContext(Dispatchers.Default) {
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
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            val cp = Chromaprint()
            var started = false
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var float = false
            var fed = 0L
            var limit = Long.MAX_VALUE
            var inputDone = false
            var shorts = ShortArray(0)
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
                } else if (o >= 0) {
                    if (!started) {
                        cp.start(rate, channels)
                        limit = maxSeconds.toLong() * rate * channels
                        started = true
                    }
                    val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val count = if (float) info.size / 4 else info.size / 2
                    if (shorts.size < count) shorts = ShortArray(count)
                    if (float) {
                        val fb = buf.asFloatBuffer()
                        for (k in 0 until count) shorts[k] = (fb.get(k).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    } else {
                        buf.asShortBuffer().get(shorts, 0, count)
                    }
                    // Whole frames only, up to the limit.
                    val take = minOf(count.toLong(), limit - fed).toInt().let { it - it % channels }
                    if (take > 0) cp.feed(shorts, take)
                    fed += take
                    codec.releaseOutputBuffer(o, false)
                    if (fed >= limit || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            if (!started) throw IOException("The audio couldn't be decoded")
            cp.finish()
            val raw = cp.rawFingerprint()
            if (raw.isEmpty()) throw IOException("The song is too short to recognize")
            AudioFingerprint(Chromaprint.encode(raw, Chromaprint.ALGORITHM), raw, (durationUs / 1_000_000).toInt())
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
