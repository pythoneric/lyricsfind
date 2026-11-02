package com.biteric.songfixer.loudness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * Against EBU Tech 3341 ("Loudness Metering: 'EBU Mode' metering to supplement EBU R 128
 * loudness normalization"), test cases 1–5, which are sine tones that can be generated here;
 * the standard allows ±0.1 LU.
 */
class LoudnessMeterTest {
    /** Stereo (or mono) 1 kHz sine segments: (dBFS, seconds). */
    private fun measure(vararg segments: Pair<Double, Double>, rate: Int = 48_000, channels: Int = 2, freq: Double = 1000.0): LoudnessMeter {
        val meter = LoudnessMeter(rate, channels)
        var n = 0L
        for ((db, seconds) in segments) {
            val amp = 10.0.pow(db / 20.0)
            val frames = (seconds * rate).toInt()
            val buf = FloatArray(4096 * channels)
            var done = 0
            while (done < frames) {
                val count = minOf(4096, frames - done)
                for (i in 0 until count) {
                    val v = (amp * sin(2 * PI * freq * n++ / rate)).toFloat()
                    for (c in 0 until channels) buf[i * channels + c] = v
                }
                meter.feed(buf, count)
                done += count
            }
        }
        return meter
    }

    private fun assertLufs(expected: Double, meter: LoudnessMeter) =
        assertEquals(expected, meter.integratedLoudness()!!, 0.1)

    @Test
    fun tech3341Case1() = assertLufs(-23.0, measure(-23.0 to 20.0))

    @Test
    fun tech3341Case2() = assertLufs(-33.0, measure(-33.0 to 20.0))

    @Test
    fun tech3341Case3RelativeGate() = assertLufs(-23.0, measure(-36.0 to 10.0, -23.0 to 60.0, -36.0 to 10.0))

    @Test
    fun tech3341Case4BothGates() = assertLufs(-23.0, measure(-72.0 to 10.0, -36.0 to 10.0, -23.0 to 60.0, -36.0 to 10.0, -72.0 to 10.0))

    @Test
    fun tech3341Case5() = assertLufs(-23.0, measure(-26.0 to 20.0, -20.0 to 20.1, -26.0 to 20.0))

    /** −20 dBFS on both channels is −20 LUFS; on one channel (mono) it's 3 dB less, −23 LUFS. */
    @Test
    fun sineAtMinus20() {
        assertLufs(-20.0, measure(-20.0 to 10.0, channels = 2))
        assertLufs(-23.0, measure(-20.0 to 10.0, channels = 1))
    }

    @Test
    fun otherSampleRates() {
        assertLufs(-23.0, measure(-23.0 to 10.0, rate = 44_100))
        assertLufs(-23.0, measure(-23.0 to 10.0, rate = 96_000))
    }

    @Test
    fun silenceHasNoLoudness() {
        assertNull(measure(-200.0 to 5.0).integratedLoudness())
        assertNull(LoudnessMeter(48_000, 2).integratedLoudness())
    }

    /**
     * A sine at a quarter of the sample rate, 45° out of phase with the samples: every sample is
     * at 0.707 of the wave's peak, which only oversampling sees (EBU Tech 3341 allows
     * +0.2 / −0.4 dB on true peak).
     */
    @Test
    fun truePeakBetweenSamples() {
        val rate = 48_000
        val meter = LoudnessMeter(rate, 2)
        val buf = FloatArray(2 * rate)
        for (i in 0 until rate) {
            val v = (0.5 * sin(2 * PI * (rate / 4.0) * i / rate + PI / 4)).toFloat()
            buf[2 * i] = v; buf[2 * i + 1] = v
        }
        meter.feed(buf, rate)
        val db = 20 * log10(meter.truePeak() / 0.5)
        assertTrue("true peak off by $db dB", db > -0.4 && db < 0.2)
    }

    @Test
    fun truePeakOfAPlainSine() {
        val db = 20 * log10(measure(-6.0 to 2.0).truePeak()) + 6.0
        assertTrue("off by $db dB", db > -0.4 && db < 0.2)
    }
}
