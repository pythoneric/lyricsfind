package com.biteric.songfixer.fingerprint

import java.util.Base64
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * A Kotlin port of Chromaprint 1.5.1's default algorithm (TEST2), the audio fingerprint AcoustID
 * looks songs up by. It follows the C++ pipeline stage by stage so the fingerprints match the
 * official library's (checked against Chromaprint's own test data in ChromaprintTest):
 *
 *   samples → mono → resample to 11025 Hz → 4096-sample frames every 1365 samples → Hamming
 *   window → FFT power → 12 chroma bands (28–3520 Hz) → 5-tap smoothing → normalize →
 *   16 classifiers over a rolling integral image → one 32-bit number per frame.
 *
 * Chromaprint: Copyright (C) 2010-2016 Lukas Lalinsky, MIT license. The resampler is a port of
 * FFmpeg's resample2.c as bundled with Chromaprint (LGPL 2.1).
 */
class Chromaprint {
    private val calculator = FingerprintCalculator(CLASSIFIERS)
    private val normalizer: (DoubleArray) -> Unit = { normalize(it); calculator.consume(it) }
    private val filter = ChromaFilter(FILTER_COEFFICIENTS, normalizer)
    private val chroma = Chroma(MIN_FREQ, MAX_FREQ, FRAME_SIZE, SAMPLE_RATE) { filter.consume(it) }
    private val fft = FrameFft(FRAME_SIZE, FRAME_SIZE - FRAME_OVERLAP) { chroma.consume(it) }
    private val processor = AudioProcessor(SAMPLE_RATE) { buf, len -> fft.consume(buf, len) }

    /** Starts a new fingerprint for interleaved 16-bit audio. */
    fun start(sampleRate: Int, channels: Int) {
        require(channels > 0 && sampleRate > 1000) { "Unsupported audio: $sampleRate Hz, $channels channels" }
        processor.reset(sampleRate, channels)
        fft.reset()
        filter.reset()
        calculator.reset()
    }

    /** Feeds [length] interleaved samples (all channels counted). */
    fun feed(samples: ShortArray, length: Int = samples.size) = processor.consume(samples, length)

    fun finish() = processor.flush()

    /** The raw fingerprint: one number per ~0.124 s of audio. */
    fun rawFingerprint(): IntArray = calculator.fingerprint.toIntArray()

    /** The compressed, base64 fingerprint AcoustID expects. */
    fun fingerprint(): String = encode(rawFingerprint(), ALGORITHM)

    companion object {
        const val SAMPLE_RATE = 11025
        const val FRAME_SIZE = 4096
        const val FRAME_OVERLAP = FRAME_SIZE - FRAME_SIZE / 3
        private const val MIN_FREQ = 28
        private const val MAX_FREQ = 3520
        /** CHROMAPRINT_ALGORITHM_TEST2, the default. */
        const val ALGORITHM = 1

        private val FILTER_COEFFICIENTS = doubleArrayOf(0.25, 0.75, 1.0, 0.75, 0.25)

        private val CLASSIFIERS = arrayOf(
            Classifier(Filter(0, 4, 3, 15), Quantizer(1.98215, 2.35817, 2.63523)),
            Classifier(Filter(4, 4, 6, 15), Quantizer(-1.03809, -0.651211, -0.282167)),
            Classifier(Filter(1, 0, 4, 16), Quantizer(-0.298702, 0.119262, 0.558497)),
            Classifier(Filter(3, 8, 2, 12), Quantizer(-0.105439, 0.0153946, 0.135898)),
            Classifier(Filter(3, 4, 4, 8), Quantizer(-0.142891, 0.0258736, 0.200632)),
            Classifier(Filter(4, 0, 3, 5), Quantizer(-0.826319, -0.590612, -0.368214)),
            Classifier(Filter(1, 2, 2, 9), Quantizer(-0.557409, -0.233035, 0.0534525)),
            Classifier(Filter(2, 7, 3, 4), Quantizer(-0.0646826, 0.00620476, 0.0784847)),
            Classifier(Filter(2, 6, 2, 16), Quantizer(-0.192387, -0.029699, 0.215855)),
            Classifier(Filter(2, 1, 3, 2), Quantizer(-0.0397818, -0.00568076, 0.0292026)),
            Classifier(Filter(5, 10, 1, 15), Quantizer(-0.53823, -0.369934, -0.190235)),
            Classifier(Filter(3, 6, 2, 10), Quantizer(-0.124877, 0.0296483, 0.139239)),
            Classifier(Filter(2, 1, 1, 14), Quantizer(-0.101475, 0.0225617, 0.231971)),
            Classifier(Filter(3, 5, 6, 4), Quantizer(-0.0799915, -0.00729616, 0.063262)),
            Classifier(Filter(1, 9, 2, 12), Quantizer(-0.272556, 0.019424, 0.302559)),
            Classifier(Filter(3, 4, 2, 14), Quantizer(-0.164292, -0.0321188, 0.0846339)),
        )

        /** Chromaprint's compressed format: header, 3-bit "normal" deltas, 5-bit "exceptional" ones. */
        fun encode(fp: IntArray, algorithm: Int): String {
            val normal = ArrayList<Int>(fp.size * 4)
            val exceptional = ArrayList<Int>()
            fun process(x0: Int) {
                var x = x0
                var bit = 1
                var lastBit = 0
                while (x != 0) {
                    if (x and 1 != 0) {
                        val value = bit - lastBit
                        if (value >= 7) {
                            normal += 7
                            exceptional += value - 7
                        } else {
                            normal += value
                        }
                        lastBit = bit
                    }
                    x = x ushr 1
                    bit++
                }
                normal += 0
            }
            if (fp.isNotEmpty()) {
                process(fp[0])
                for (i in 1 until fp.size) process(fp[i] xor fp[i - 1])
            }
            val out = ByteArray(4 + (normal.size * 3 + 7) / 8 + (exceptional.size * 5 + 7) / 8)
            out[0] = algorithm.toByte()
            out[1] = (fp.size shr 16).toByte()
            out[2] = (fp.size shr 8).toByte()
            out[3] = fp.size.toByte()
            var pos = 4
            pos = pack(normal, 3, out, pos)
            pack(exceptional, 5, out, pos)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out)
        }

        /** Packs [bits]-bit values little-endian into bytes (same layout as PackInt3/5Array). */
        private fun pack(values: List<Int>, bits: Int, out: ByteArray, start: Int): Int {
            var acc = 0L
            var accBits = 0
            var pos = start
            val mask = (1 shl bits) - 1
            for (v in values) {
                acc = acc or ((v and mask).toLong() shl accBits)
                accBits += bits
                while (accBits >= 8) {
                    out[pos++] = acc.toByte()
                    acc = acc ushr 8
                    accBits -= 8
                }
            }
            if (accBits > 0) out[pos++] = acc.toByte()
            return pos
        }

        private fun normalize(v: DoubleArray) {
            var squares = 0.0
            for (x in v) squares += x * x
            val norm = if (squares > 0) sqrt(squares) else 0.0
            if (norm < 0.01) v.fill(0.0) else for (i in v.indices) v[i] /= norm
        }
    }
}

/** Mixes to mono and resamples to the fingerprint's rate, in blocks like Chromaprint's AudioProcessor. */
private class AudioProcessor(private val targetRate: Int, private val consumer: (ShortArray, Int) -> Unit) {
    private val buffer = ShortArray(MAX_BUFFER)
    private var bufferOffset = 0
    private val resampled = ShortArray(MAX_BUFFER)
    private var resampler: Resampler? = null
    private var channels = 1

    fun reset(sampleRate: Int, channels: Int) {
        bufferOffset = 0
        this.channels = channels
        resampler = if (sampleRate != targetRate) Resampler(targetRate, sampleRate, 16, 8, 0.8) else null
    }

    fun consume(input: ShortArray, length: Int) {
        var frames = length / channels
        var inPos = 0
        while (frames > 0) {
            val n = min(frames, MAX_BUFFER - bufferOffset)
            for (i in 0 until n) {
                buffer[bufferOffset + i] = when (channels) {
                    1 -> input[inPos]
                    2 -> ((input[inPos] + input[inPos + 1]) / 2).toShort()
                    else -> {
                        var sum = 0
                        for (c in 0 until channels) sum += input[inPos + c]
                        (sum / channels).toShort()
                    }
                }
                inPos += channels
            }
            bufferOffset += n
            frames -= n
            if (bufferOffset == MAX_BUFFER) {
                resample()
                if (bufferOffset == MAX_BUFFER) return
            }
        }
    }

    fun flush() {
        if (bufferOffset > 0) resample()
    }

    private fun resample() {
        val r = resampler
        if (r == null) {
            consumer(buffer, bufferOffset)
            bufferOffset = 0
            return
        }
        val (length, consumed) = r.resample(resampled, buffer, bufferOffset, MAX_BUFFER)
        consumer(resampled, min(length, MAX_BUFFER))
        val remaining = bufferOffset - consumed
        if (remaining > 0) System.arraycopy(buffer, consumed, buffer, 0, remaining)
        bufferOffset = max(remaining, 0)
    }

    companion object {
        const val MAX_BUFFER = 1024 * 32
    }
}

/** FFmpeg's av_resample (16-bit filter bank, Kaiser window), as Chromaprint bundles it. */
private class Resampler(outRate: Int, inRate: Int, filterSize: Int, private val phaseShift: Int, cutoff: Double) {
    private val phaseMask = (1 shl phaseShift) - 1
    private val filterLength: Int
    private val filterBank: ShortArray
    private val srcIncr = outRate
    private var dstIncr: Int
    private var index: Int
    private var frac = 0

    init {
        val factor = min(outRate * cutoff / inRate, 1.0)
        val phaseCount = 1 shl phaseShift
        filterLength = max(ceil(filterSize / factor).toInt(), 1)
        filterBank = ShortArray(filterLength * (phaseCount + 1))
        buildFilter(factor, phaseCount)
        System.arraycopy(filterBank, 0, filterBank, filterLength * phaseCount + 1, filterLength - 1)
        filterBank[filterLength * phaseCount] = filterBank[filterLength - 1]
        dstIncr = inRate * phaseCount
        index = -phaseCount * ((filterLength - 1) / 2)
    }

    private fun buildFilter(factor0: Double, phaseCount: Int) {
        val factor = min(factor0, 1.0)
        val tab = DoubleArray(filterLength)
        val center = (filterLength - 1) / 2
        val scale = 1 shl FILTER_SHIFT
        for (ph in 0 until phaseCount) {
            var norm = 0.0
            for (i in 0 until filterLength) {
                var x = PI * ((i - center).toDouble() - ph.toDouble() / phaseCount) * factor
                var y = if (x == 0.0) 1.0 else sin(x) / x
                val w = 2.0 * x / (factor * filterLength * PI)
                y *= bessel(WINDOW_TYPE * sqrt(max(1 - w * w, 0.0)))
                tab[i] = y
                norm += y
            }
            for (i in 0 until filterLength) {
                // lrintf: to float, then round half to even.
                val v = round((tab[i] * scale / norm).toFloat().toDouble()).toLong()
                filterBank[ph * filterLength + i] = v.coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toInt().toShort()
            }
        }
    }

    private fun bessel(x0: Double): Double {
        var v = 1.0
        var lastV = 0.0
        var t = 1.0
        val x = x0 * x0 / 4
        var i = 1
        while (v != lastV) {
            lastV = v
            t *= x / (i * i)
            v += t
            i++
        }
        return v
    }

    /** Returns (samples written, input samples consumed). */
    fun resample(dst: ShortArray, src: ShortArray, srcSize: Int, dstSize: Int): Pair<Int, Int> {
        var index = this.index
        var frac = this.frac
        val dstIncrFrac = dstIncr % srcIncr
        val dstIncrInt = dstIncr / srcIncr
        var dstIndex = 0
        while (dstIndex < dstSize) {
            val filterOff = filterLength * (index and phaseMask)
            val sampleIndex = index shr phaseShift
            var v = 0L
            if (sampleIndex < 0) {
                for (i in 0 until filterLength) v += src[abs(sampleIndex + i) % srcSize] * filterBank[filterOff + i].toLong()
            } else if (sampleIndex + filterLength > srcSize) {
                break
            } else {
                for (i in 0 until filterLength) v += src[sampleIndex + i] * filterBank[filterOff + i].toLong()
            }
            // The C code accumulates in int32; keep its wraparound.
            var acc = v.toInt()
            acc = (acc + (1 shl (FILTER_SHIFT - 1))) shr FILTER_SHIFT
            dst[dstIndex] = if ((acc + 32768).toUInt() > 65535u) ((acc shr 31) xor 32767).toShort() else acc.toShort()
            frac += dstIncrFrac
            index += dstIncrInt
            if (frac >= srcIncr) {
                frac -= srcIncr
                index++
            }
            dstIndex++
        }
        val consumed = max(index, 0) shr phaseShift
        if (index >= 0) index = index and phaseMask
        this.frac = frac
        this.index = index
        return dstIndex to consumed
    }

    companion object {
        const val FILTER_SHIFT = 15
        const val WINDOW_TYPE = 9
    }
}

/** Cuts the audio into overlapping frames and hands on each frame's power spectrum. */
private class FrameFft(private val frameSize: Int, private val increment: Int, private val consumer: (DoubleArray) -> Unit) {
    private var buffer = ShortArray(frameSize * 2)
    private var bufferLen = 0
    private val window = DoubleArray(frameSize) { i -> (0.54 - 0.46 * cos(i * 2.0 * PI / (frameSize - 1))) / Short.MAX_VALUE }
    private val re = DoubleArray(frameSize)
    private val im = DoubleArray(frameSize)
    private val power = DoubleArray(frameSize / 2 + 1)
    private val bits = Integer.numberOfTrailingZeros(frameSize)
    private val cosTable = DoubleArray(frameSize / 2) { cos(2 * PI * it / frameSize) }
    private val sinTable = DoubleArray(frameSize / 2) { -sin(2 * PI * it / frameSize) }

    fun reset() {
        bufferLen = 0
    }

    fun consume(input: ShortArray, length: Int) {
        if (bufferLen + length > buffer.size) buffer = buffer.copyOf(max(buffer.size * 2, bufferLen + length))
        System.arraycopy(input, 0, buffer, bufferLen, length)
        bufferLen += length
        var start = 0
        while (bufferLen - start >= frameSize) {
            compute(start)
            start += increment
        }
        System.arraycopy(buffer, start, buffer, 0, bufferLen - start)
        bufferLen -= start
    }

    private fun compute(start: Int) {
        for (i in 0 until frameSize) {
            val j = Integer.reverse(i) ushr (32 - bits)
            re[j] = buffer[start + i] * window[i]
            im[j] = 0.0
        }
        var size = 2
        while (size <= frameSize) {
            val half = size / 2
            val step = frameSize / size
            var i = 0
            while (i < frameSize) {
                for (k in 0 until half) {
                    val c = cosTable[k * step]
                    val s = sinTable[k * step]
                    val a = i + k
                    val b = a + half
                    val tr = re[b] * c - im[b] * s
                    val ti = re[b] * s + im[b] * c
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                i += size
            }
            size *= 2
        }
        for (i in power.indices) power[i] = re[i] * re[i] + im[i] * im[i]
        consumer(power)
    }
}

/** Sums the spectrum's energy into the 12 notes of the octave. */
private class Chroma(minFreq: Int, maxFreq: Int, frameSize: Int, sampleRate: Int, private val consumer: (DoubleArray) -> Unit) {
    private val minIndex = max(1, freqToIndex(minFreq.toDouble(), frameSize, sampleRate))
    private val maxIndex = min(frameSize / 2, freqToIndex(maxFreq.toDouble(), frameSize, sampleRate))
    private val notes = IntArray(frameSize)
    private val features = DoubleArray(12)

    init {
        for (i in minIndex until maxIndex) {
            val freq = i.toDouble() * sampleRate / frameSize
            val octave = ln(freq / (440.0 / 16.0)) / ln(2.0)
            notes[i] = (12 * (octave - floor(octave))).toInt()
        }
    }

    fun consume(frame: DoubleArray) {
        features.fill(0.0)
        for (i in minIndex until maxIndex) features[notes[i]] += frame[i]
        consumer(features)
    }

    private fun freqToIndex(freq: Double, frameSize: Int, sampleRate: Int): Int {
        val x = frameSize * freq / sampleRate
        return (if (x >= 0) floor(x + 0.5) else ceil(x - 0.5)).toInt()
    }
}

/** Smooths each chroma band over 5 frames. */
private class ChromaFilter(private val coefficients: DoubleArray, private val consumer: (DoubleArray) -> Unit) {
    private val buffer = Array(8) { DoubleArray(12) }
    private val result = DoubleArray(12)
    private var offset = 0
    private var size = 1

    fun reset() {
        size = 1
        offset = 0
    }

    fun consume(features: DoubleArray) {
        features.copyInto(buffer[offset])
        offset = (offset + 1) % 8
        if (size >= coefficients.size) {
            val start = (offset + 8 - coefficients.size) % 8
            result.fill(0.0)
            for (i in 0 until 12) {
                for (j in coefficients.indices) result[i] += buffer[(start + j) % 8][i] * coefficients[j]
            }
            consumer(result)
        } else {
            size++
        }
    }
}

private class Quantizer(val t0: Double, val t1: Double, val t2: Double) {
    fun quantize(v: Double) = if (v < t1) (if (v < t0) 0 else 1) else (if (v < t2) 2 else 3)
}

/** Haar-like filter over a time × chroma area: [y]/[height] in bands, [width] in frames. */
private class Filter(val type: Int, val y: Int, val height: Int, val width: Int) {
    fun apply(img: RollingIntegralImage, x: Int): Double {
        val w = width
        val h = height
        val y = y
        fun cmp(a: Double, b: Double) = ln((1.0 + a) / (1.0 + b))
        return when (type) {
            0 -> cmp(img.area(x, y, x + w, y + h), 0.0)
            1 -> {
                val h2 = h / 2
                cmp(img.area(x, y + h2, x + w, y + h), img.area(x, y, x + w, y + h2))
            }
            2 -> {
                val w2 = w / 2
                cmp(img.area(x + w2, y, x + w, y + h), img.area(x, y, x + w2, y + h))
            }
            3 -> {
                val w2 = w / 2
                val h2 = h / 2
                cmp(
                    img.area(x, y + h2, x + w2, y + h) + img.area(x + w2, y, x + w, y + h2),
                    img.area(x, y, x + w2, y + h2) + img.area(x + w2, y + h2, x + w, y + h),
                )
            }
            4 -> {
                val h3 = h / 3
                cmp(img.area(x, y + h3, x + w, y + 2 * h3), img.area(x, y, x + w, y + h3) + img.area(x, y + 2 * h3, x + w, y + h))
            }
            5 -> {
                val w3 = w / 3
                cmp(img.area(x + w3, y, x + 2 * w3, y + h), img.area(x, y, x + w3, y + h) + img.area(x + 2 * w3, y, x + w, y + h))
            }
            else -> 0.0
        }
    }
}

private class Classifier(val filter: Filter, val quantizer: Quantizer)

/** Integral image over the last 256 chroma rows (rows = frames, columns = bands). */
private class RollingIntegralImage(maxRows: Int) {
    private val maxRows = maxRows + 1
    private val columns = 12
    private val data = DoubleArray(this.maxRows * columns)
    var numRows = 0
        private set

    fun reset() {
        numRows = 0
    }

    fun addRow(row: DoubleArray) {
        val cur = (numRows % maxRows) * columns
        var sum = 0.0
        for (c in 0 until columns) {
            sum += row[c]
            data[cur + c] = sum
        }
        if (numRows > 0) {
            val last = ((numRows - 1) % maxRows) * columns
            for (c in 0 until columns) data[cur + c] = data[last + c] + data[cur + c]
        }
        numRows++
    }

    fun area(r1: Int, c1: Int, r2: Int, c2: Int): Double {
        if (r1 == r2 || c1 == c2) return 0.0
        val row2 = ((r2 - 1) % maxRows) * columns
        if (r1 == 0) {
            return if (c1 == 0) data[row2 + c2 - 1] else data[row2 + c2 - 1] - data[row2 + c1 - 1]
        }
        val row1 = ((r1 - 1) % maxRows) * columns
        return if (c1 == 0) {
            data[row2 + c2 - 1] - data[row1 + c2 - 1]
        } else {
            data[row2 + c2 - 1] - data[row1 + c2 - 1] - data[row2 + c1 - 1] + data[row1 + c1 - 1]
        }
    }
}

private class FingerprintCalculator(private val classifiers: Array<Classifier>) {
    private val image = RollingIntegralImage(255)
    private val maxFilterWidth = classifiers.maxOf { it.filter.width }
    val fingerprint = ArrayList<Int>()

    fun reset() {
        image.reset()
        fingerprint.clear()
    }

    fun consume(features: DoubleArray) {
        image.addRow(features)
        if (image.numRows >= maxFilterWidth) {
            val offset = image.numRows - maxFilterWidth
            var bits = 0
            for (c in classifiers) bits = (bits shl 2) or GRAY[c.quantizer.quantize(c.filter.apply(image, offset))]
            fingerprint += bits
        }
    }

    companion object {
        private val GRAY = intArrayOf(0, 1, 3, 2)
    }
}
