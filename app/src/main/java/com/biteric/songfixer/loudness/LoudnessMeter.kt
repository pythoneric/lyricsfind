package com.biteric.songfixer.loudness

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * ITU-R BS.1770-4 loudness meter (the EBU R128 / ReplayGain 2.0 measurement):
 *
 *  - K-weighting: a high shelf (+4 dB above ~1.7 kHz) then a high-pass (~38 Hz), designed for
 *    any sample rate (the standard's 48 kHz coefficients, re-derived; same formulas as libebur128).
 *  - 400 ms blocks every 100 ms (75 % overlap); a block's loudness is
 *    −0.691 + 10·log10(Σ channel mean squares), channels weighted 1.0 (L, R, C).
 *  - Integrated loudness: blocks above −70 LUFS (absolute gate), then those above
 *    (their mean − 10 LU) (relative gate), averaged in energy.
 *  - True peak: 4× oversampled with a polyphase low-pass, as BS.1770-4 Annex 2 describes.
 *
 * Mono is one channel, as BS.1770 defines it (3 dB below the same signal on both channels).
 * Up to 2 channels count; for more, the first two (the rest are rare in music files).
 */
class LoudnessMeter(private val sampleRate: Int, channels: Int) {
    private val channels = channels.coerceIn(1, MAX_CHANNELS)
    private val inputChannels = channels

    // K-weighting: two biquads per channel (direct form I state).
    private val shelfB: DoubleArray
    private val shelfA: DoubleArray
    private val hpB = doubleArrayOf(1.0, -2.0, 1.0)
    private val hpA: DoubleArray
    private val x1 = DoubleArray(this.channels); private val x2 = DoubleArray(this.channels)
    private val y1 = DoubleArray(this.channels); private val y2 = DoubleArray(this.channels)
    private val z1 = DoubleArray(this.channels); private val z2 = DoubleArray(this.channels)

    // 100 ms sub-blocks; a 400 ms block is the last four.
    private val subBlockFrames = sampleRate / 10
    private var subFrames = 0
    private var subEnergy = 0.0
    private val lastSubs = DoubleArray(4)
    private var subCount = 0
    /** Mean square (channel-summed) of each 400 ms block. */
    private val blocks = ArrayList<Double>()

    // True peak.
    private val taps = PHASE_TAPS
    private val history = Array(this.channels) { DoubleArray(taps) }
    private var historyPos = 0
    private var truePeak = 0.0
    private var samplePeak = 0.0

    init {
        var f0 = 1681.974450955533
        val g = 3.999843853973347
        var q = 0.7071752369554196
        var k = tan(PI * f0 / sampleRate)
        val vh = 10.0.pow(g / 20.0)
        val vb = vh.pow(0.4996667741545416)
        var a0 = 1.0 + k / q + k * k
        shelfB = doubleArrayOf((vh + vb * k / q + k * k) / a0, 2.0 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0)
        shelfA = doubleArrayOf(1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0)
        f0 = 38.13547087602444
        q = 0.5003270373238773
        k = tan(PI * f0 / sampleRate)
        a0 = 1.0 + k / q + k * k
        hpA = doubleArrayOf(1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0)
    }

    /** Feeds [frames] frames of interleaved samples (−1..1; [inputChannels] per frame). */
    fun feed(samples: FloatArray, frames: Int) {
        var i = 0
        for (f in 0 until frames) {
            var energy = 0.0
            for (c in 0 until channels) {
                val x = samples[i + c].toDouble()
                // Shelf.
                val y = shelfB[0] * x + shelfB[1] * x1[c] + shelfB[2] * x2[c] - shelfA[1] * y1[c] - shelfA[2] * y2[c]
                x2[c] = x1[c]; x1[c] = x
                // High-pass.
                val z = hpB[0] * y + hpB[1] * y1[c] + hpB[2] * y2[c] - hpA[1] * z1[c] - hpA[2] * z2[c]
                y2[c] = y1[c]; y1[c] = y
                z2[c] = z1[c]; z1[c] = z
                energy += z * z
                peak(c, x)
            }
            historyPos = (historyPos + 1) % taps
            subEnergy += energy
            if (++subFrames == subBlockFrames) endSubBlock()
            i += inputChannels
        }
    }

    private fun endSubBlock() {
        lastSubs[subCount % 4] = subEnergy
        subCount++
        subEnergy = 0.0
        subFrames = 0
        if (subCount >= 4) blocks += lastSubs.sum() / (4.0 * subBlockFrames)
    }

    private fun peak(c: Int, x: Double) {
        val h = history[c]
        h[historyPos] = x
        val a = abs(x)
        if (a > samplePeak) samplePeak = a
        // Peaks between samples are at most a few dB above the samples around them: skip the
        // oversampling where nothing near could beat the peak so far.
        if (a * 2.0 < truePeak) return
        for (p in 0 until OVERSAMPLE) {
            val coef = PHASES[p]
            var s = 0.0
            var j = historyPos
            for (t in 0 until taps) {
                s += coef[t] * h[j]
                j = if (j == 0) taps - 1 else j - 1
            }
            val v = abs(s)
            if (v > truePeak) truePeak = v
        }
    }

    /** Integrated loudness in LUFS; null for silence (or under 400 ms of audio). */
    fun integratedLoudness(): Double? {
        val abs = blocks.filter { it > 0 && lufs(it) > ABSOLUTE_GATE }
        if (abs.isEmpty()) return null
        val relativeGate = lufs(abs.average()) - RELATIVE_GATE_LU
        val gated = abs.filter { lufs(it) > relativeGate }
        if (gated.isEmpty()) return null
        return lufs(gated.average())
    }

    /** True peak, linear (1.0 = full scale; can be above 1). */
    fun truePeak(): Double = max(truePeak, samplePeak)

    companion object {
        const val MAX_CHANNELS = 2
        const val ABSOLUTE_GATE = -70.0
        const val RELATIVE_GATE_LU = 10.0
        private const val OVERSAMPLE = 4
        private const val PHASE_TAPS = 12

        fun lufs(meanSquare: Double) = -0.691 + 10.0 * log10(meanSquare)

        /**
         * 4× interpolation filter, 48 taps split into 4 phases of 12: a windowed sinc with its
         * cut-off at the original Nyquist frequency (Hann window), each phase normalized to unity
         * gain so a constant signal reads its own level.
         */
        private val PHASES: Array<DoubleArray> = run {
            val n = OVERSAMPLE * PHASE_TAPS
            val center = (n - 1) / 2.0
            val h = DoubleArray(n) { i ->
                val t = (i - center) / OVERSAMPLE
                val sinc = if (t == 0.0) 1.0 else sin(PI * t) / (PI * t)
                val window = 0.5 - 0.5 * cos(2 * PI * (i + 0.5) / n)
                sinc * window
            }
            Array(OVERSAMPLE) { p ->
                val phase = DoubleArray(PHASE_TAPS) { k -> h[p + OVERSAMPLE * k] }
                val sum = phase.sum()
                for (k in phase.indices) phase[k] /= sum
                phase
            }
        }
    }
}
