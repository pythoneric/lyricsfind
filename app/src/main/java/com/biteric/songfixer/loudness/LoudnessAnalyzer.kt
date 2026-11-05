package com.biteric.songfixer.loudness

import android.content.Context
import android.net.Uri
import com.biteric.songfixer.fingerprint.PcmDecoder
import com.biteric.songfixer.fingerprint.PcmSink
import java.io.IOException
import java.util.Locale

/** A song's measured loudness: integrated [lufs] and linear [truePeak]. */
data class Loudness(val lufs: Double, val truePeak: Double) {
    /** ReplayGain 2.0 track gain, dB: what brings the song to [ReplayGain.REFERENCE_LUFS]. */
    val trackGainDb: Double get() = ReplayGain.REFERENCE_LUFS - lufs
}

/** ReplayGain 2.0: loudness by BS.1770 / EBU R128, reference −18 LUFS. Tag values as the standard writes them. */
object ReplayGain {
    const val REFERENCE_LUFS = -18.0
    const val TRACK_GAIN = "REPLAYGAIN_TRACK_GAIN"
    const val TRACK_PEAK = "REPLAYGAIN_TRACK_PEAK"
    const val REFERENCE_LOUDNESS = "REPLAYGAIN_REFERENCE_LOUDNESS"
    /** Further than this from the reference, a song counts as too loud or too quiet. */
    const val FAR_DB = 3.0

    fun gainText(db: Double) = String.format(Locale.US, "%.2f dB", db)
    fun peakText(peak: Double) = String.format(Locale.US, "%.6f", peak)
    fun referenceText() = String.format(Locale.US, "%.2f LUFS", REFERENCE_LUFS)
}

/** One pass over a song: its loudness (null when silent) and the silence at its ends. */
data class Analysis(val loudness: Loudness?, val silence: Silence)

/** Decodes a whole song and measures it. */
class LoudnessAnalyzer(context: Context) {
    private val decoder = PcmDecoder(context)

    suspend fun measure(uri: Uri): Loudness = analyze(uri).loudness ?: throw IOException("The song is silent")

    suspend fun analyze(uri: Uri): Analysis {
        var meter: LoudnessMeter? = null
        var detector: SilenceDetector? = null
        var channels = 1
        // Up to 2 channels count; downmixed input keeps the frame layout.
        var buf = FloatArray(0)
        decoder.decode(uri, object : PcmSink {
            override fun start(sampleRate: Int, channelCount: Int) {
                channels = channelCount
                // A format change midway restarts the measurement (rare; keeps the result honest).
                meter = LoudnessMeter(sampleRate, channelCount.coerceAtMost(LoudnessMeter.MAX_CHANNELS))
                detector = SilenceDetector(sampleRate, channelCount)
            }

            override fun pcm(samples: FloatArray, frames: Int) {
                val m = meter ?: return
                detector?.feed(samples, frames)
                if (channels <= LoudnessMeter.MAX_CHANNELS) return m.feed(samples, frames)
                // More than two channels: the first two.
                if (buf.size < frames * 2) buf = FloatArray(frames * 2)
                for (f in 0 until frames) {
                    buf[2 * f] = samples[f * channels]
                    buf[2 * f + 1] = samples[f * channels + 1]
                }
                m.feed(buf, frames)
            }
        })
        val m = meter ?: throw IOException("The audio couldn't be decoded")
        val lufs = m.integratedLoudness()
        return Analysis(lufs?.let { Loudness(it, m.truePeak()) }, detector!!.result())
    }

    /** Only where the sound starts and ends. */
    suspend fun silence(uri: Uri): Silence {
        var detector: SilenceDetector? = null
        decoder.decode(uri, object : PcmSink {
            override fun start(sampleRate: Int, channels: Int) {
                detector = SilenceDetector(sampleRate, channels)
            }

            override fun pcm(samples: FloatArray, frames: Int) {
                detector?.feed(samples, frames)
            }
        })
        return detector?.result() ?: throw IOException("The audio couldn't be decoded")
    }
}
