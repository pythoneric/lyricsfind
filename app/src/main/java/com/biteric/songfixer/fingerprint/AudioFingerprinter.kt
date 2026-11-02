package com.biteric.songfixer.fingerprint

import android.content.Context
import android.net.Uri
import java.io.IOException

/** [fingerprint] is AcoustID's compressed form; [durationSec] the whole file's length. */
class AudioFingerprint(val fingerprint: String, val raw: IntArray, val durationSec: Int)

/** Decodes the start of a song with Android's codecs and fingerprints it like fpcalc does. */
class AudioFingerprinter(context: Context) {
    private val decoder = PcmDecoder(context)

    suspend fun fingerprint(uri: Uri, maxSeconds: Int = 120): AudioFingerprint {
        val cp = Chromaprint()
        var channels = 1
        var shorts = ShortArray(0)
        val durationSec = decoder.decode(uri, object : PcmSink {
            override fun start(sampleRate: Int, channelCount: Int) {
                cp.start(sampleRate, channelCount)
                channels = channelCount
            }

            override fun pcm(samples: FloatArray, frames: Int) {
                val n = frames * channels
                if (shorts.size < n) shorts = ShortArray(n)
                // Back to 16 bits: exact for 16-bit decoders (the decoder divided by 32768).
                for (i in 0 until n) shorts[i] = (samples[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort()
                cp.feed(shorts, n)
            }
        }, maxSeconds)
        cp.finish()
        val raw = cp.rawFingerprint()
        if (raw.isEmpty()) throw IOException("The song is too short to recognize")
        return AudioFingerprint(Chromaprint.encode(raw, Chromaprint.ALGORITHM), raw, durationSec)
    }
}
