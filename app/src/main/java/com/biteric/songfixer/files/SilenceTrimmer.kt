package com.biteric.songfixer.files

import com.biteric.songfixer.loudness.Silence
import com.biteric.songfixer.loudness.SilenceDetector
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Cuts the silence off a song's start and end without re-encoding it: whole MP3 frames
 * (~26 ms each), whole FLAC frames, or single WAV samples. Tags at the start and end of the
 * file are kept as they are; the headers that give the song's length are corrected.
 *
 * The song is read from its file a window at a time and the cut written out as it goes, so a
 * two-hour mix takes no more memory than a short song.
 */
object SilenceTrimmer {
    val FORMATS = setOf("mp3", "flac", "wav")

    /** Left before the first sound, so its attack isn't clipped. */
    const val KEEP_BEFORE_MS = 50L
    /** Left after the last sound, so a fade-out ends on its own. */
    const val KEEP_AFTER_MS = 150L

    class Unsupported(message: String) : Exception(message)

    /** How much was cut at each end; [bytes] is the trimmed file when it was cut in memory. */
    class Cut(val startMs: Long, val endMs: Long, private val data: ByteArray? = null) {
        val bytes: ByteArray get() = data ?: error("The cut was written to a stream")
    }

    /** The song's bytes, by position. */
    internal abstract class Src : Closeable {
        abstract val size: Int
        abstract operator fun get(i: Int): Byte
        /** Writes bytes [from] until [from] + [length] to [out]. */
        abstract fun writeTo(out: OutputStream, from: Int, length: Int)
        fun copyOfRange(from: Int, to: Int): ByteArray = ByteArrayOutputStream(to - from).also { writeTo(it, from, to - from) }.toByteArray()
        override fun close() {}
    }

    internal class ArraySrc(private val b: ByteArray) : Src() {
        override val size get() = b.size
        override fun get(i: Int) = b[i]
        override fun writeTo(out: OutputStream, from: Int, length: Int) = out.write(b, from, length)
    }

    /** A file read through a window of [WINDOW] bytes that follows the reads (forwards, and a little back). */
    internal class FileSrc(file: File) : Src() {
        private val raf = RandomAccessFile(file, "r")
        override val size: Int = raf.length().also { if (it > Int.MAX_VALUE) throw Unsupported("The file is too big to trim") }.toInt()
        private val window = ByteArray(WINDOW)
        private var windowStart = 0
        private var windowLength = 0

        override fun get(i: Int): Byte {
            if (i < windowStart || i >= windowStart + windowLength) {
                // Some room behind: FLAC frames are checked from their start once the next one is found.
                windowStart = (i - BACK).coerceAtLeast(0)
                raf.seek(windowStart.toLong())
                windowLength = raf.read(window, 0, minOf(WINDOW, size - windowStart)).coerceAtLeast(0)
                if (i >= windowStart + windowLength) throw IndexOutOfBoundsException("$i of $size")
            }
            return window[i - windowStart]
        }

        override fun writeTo(out: OutputStream, from: Int, length: Int) {
            val buf = ByteArray(COPY)
            raf.seek(from.toLong())
            var left = length
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(COPY, left))
                if (n <= 0) throw java.io.EOFException("The song's file ended early")
                out.write(buf, 0, n)
                left -= n
            }
        }

        override fun close() = raf.close()

        private companion object {
            const val WINDOW = 1 shl 20
            const val BACK = 256 * 1024
            const val COPY = 256 * 1024
        }
    }

    /** True when either end has enough silence to be worth cutting. */
    fun worthIt(leadMs: Long?, tailMs: Long?): Boolean =
        (leadMs ?: 0) >= SilenceDetector.MIN_TRIM_MS || (tailMs ?: 0) >= SilenceDetector.MIN_TRIM_MS

    /**
     * Where to cut, in [silence]'s sample frames: from the start (0 = nothing cut there) to the
     * end (totalFrames = nothing cut there); null when there's nothing worth cutting.
     */
    internal fun plan(silence: Silence): LongArray? {
        if (silence.silent || !worthIt(silence.leadMs, silence.tailMs)) return null
        val rate = silence.sampleRate
        val start = if (silence.leadMs >= SilenceDetector.MIN_TRIM_MS) (silence.soundStart - KEEP_BEFORE_MS * rate / 1000).coerceAtLeast(0) else 0
        val end = if (silence.tailMs >= SilenceDetector.MIN_TRIM_MS) (silence.soundEnd + KEEP_AFTER_MS * rate / 1000).coerceAtMost(silence.totalFrames) else silence.totalFrames
        return longArrayOf(start, end)
    }

    /** The trimmed file, in memory; null when there's nothing worth cutting. */
    fun cut(bytes: ByteArray, extension: String, silence: Silence): Cut? {
        val out = ByteArrayOutputStream(bytes.size)
        val cut = cut(ArraySrc(bytes), extension, silence, out) ?: return null
        return Cut(cut.startMs, cut.endMs, out.toByteArray())
    }

    /** Trims [file] into [out] (nothing is written when it returns null: nothing worth cutting). */
    fun cut(file: File, extension: String, silence: Silence, out: OutputStream): Cut? =
        FileSrc(file).use { cut(it, extension, silence, out) }

    private fun cut(b: Src, extension: String, silence: Silence, out: OutputStream): Cut? {
        val (start, end) = plan(silence) ?: return null
        return when (extension.lowercase()) {
            "mp3" -> Mp3.cut(b, start, end, silence, out)
            "flac" -> Flac.cut(b, start, end, silence, out)
            "wav" -> Wav.cut(b, start, end, silence, out)
            else -> throw Unsupported(".$extension files can't be trimmed here")
        }
    }

    private fun scale(frames: Long, from: Int, to: Int): Long = if (from == to) frames else frames * to / from

    private fun ms(samples: Long, rate: Int): Long = samples * 1000 / rate

    private fun u8(b: Src, i: Int) = b[i].toInt() and 0xFF
    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun be32(b: ByteArray, i: Int) = (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)
    private fun putBe32(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 24).toByte(); b[i + 1] = (v ushr 16).toByte(); b[i + 2] = (v ushr 8).toByte(); b[i + 3] = v.toByte()
    }
    private fun le32(b: Src, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16) or (u8(b, i + 3) shl 24)
    private fun putLe32(b: ByteArray, i: Int, v: Int) {
        b[i] = v.toByte(); b[i + 1] = (v ushr 8).toByte(); b[i + 2] = (v ushr 16).toByte(); b[i + 3] = (v ushr 24).toByte()
    }
    private fun ascii(b: Src, i: Int, s: String) = i >= 0 && i + s.length <= b.size && s.indices.all { b[i + it] == s[it].code.toByte() }
    private fun ascii(b: ByteArray, i: Int, s: String) = i >= 0 && i + s.length <= b.size && s.indices.all { b[i + it] == s[it].code.toByte() }

    /** Bytes of the ID3v2 tags at the start of [b] (0 when none). */
    private fun id3v2Length(b: Src): Int {
        var p = 0
        while (ascii(b, p, "ID3") && p + 10 <= b.size) {
            val size = (u8(b, p + 6) shl 21) or (u8(b, p + 7) shl 14) or (u8(b, p + 8) shl 7) or u8(b, p + 9)
            val footer = if (u8(b, p + 5) and 0x10 != 0) 10 else 0
            p += 10 + size + footer
        }
        return p.coerceAtMost(b.size)
    }

    // ---------------------------------------------------------------- MP3

    internal object Mp3 {
        private val BITRATES_V1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        private val BITRATES_V2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
        private val RATES_V1 = intArrayOf(44100, 48000, 32000)

        /** An MPEG audio Layer III frame header. */
        class Header(val version: Int, val sampleRate: Int, val length: Int, val samples: Int, val mono: Boolean, val crc: Boolean)

        fun header(b: ByteArray, p: Int): Header? = header(ArraySrc(b), p)

        internal fun header(b: Src, p: Int): Header? {
            if (p < 0 || p + 4 > b.size || u8(b, p) != 0xFF || u8(b, p + 1) and 0xE0 != 0xE0) return null
            val version = (u8(b, p + 1) shr 3) and 3 // 0 = 2.5, 2 = 2, 3 = 1
            val layer = (u8(b, p + 1) shr 1) and 3
            val bitrateIndex = u8(b, p + 2) shr 4
            val rateIndex = (u8(b, p + 2) shr 2) and 3
            if (version == 1 || layer != 1 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return null
            val v1 = version == 3
            val rate = RATES_V1[rateIndex] / (if (v1) 1 else if (version == 2) 2 else 4)
            val bitrate = (if (v1) BITRATES_V1 else BITRATES_V2)[bitrateIndex] * 1000
            val padding = (u8(b, p + 2) shr 1) and 1
            val length = (if (v1) 144 else 72) * bitrate / rate + padding
            return Header(version, rate, length, if (v1) 1152 else 576, (u8(b, p + 3) shr 6) == 3, u8(b, p + 1) and 1 == 0)
        }

        private fun sameStream(a: Header, b: Header) = a.version == b.version && a.sampleRate == b.sampleRate

        /**
         * The next frame of [first]'s stream from [from] on: one whose next frame follows right after
         * it (or that ends the file); -1 when there are no more frames.
         */
        private fun resync(b: Src, from: Int, first: Header): Int {
            var q = from
            while (q + 4 <= b.size) {
                if (u8(b, q) == 0xFF) {
                    val h = header(b, q)
                    if (h != null && sameStream(h, first) && q + h.length <= b.size) {
                        val after = q + h.length
                        val next = header(b, after)
                        if (after == b.size || (next != null && sameStream(next, first))) return q
                    }
                }
                q++
            }
            return -1
        }

        fun cut(b: Src, startWanted: Long, endWanted: Long, silence: Silence, out: OutputStream): Cut? {
            var p = id3v2Length(b)
            // The first frame: one whose next frame follows right after it.
            var found: Header? = null
            while (p + 4 <= b.size) {
                val h = header(b, p)
                if (h != null) {
                    val next = header(b, p + h.length)
                    if (next != null && sameStream(h, next)) { found = h; break }
                }
                p++
                if (p > 1 shl 20) break
            }
            val first = found ?: throw Unsupported("No MP3 frames found")
            val head = p
            var offsets = IntArray(1 shl 14)
            var count = 0
            var audioEnd = p
            while (true) {
                val h = header(b, p)
                if (h != null && sameStream(h, first) && p + h.length <= b.size) {
                    if (count == offsets.size) offsets = offsets.copyOf(count * 2)
                    offsets[count++] = p
                    p += h.length
                    audioEnd = p
                    continue
                }
                // Stray bytes between frames (real files have them; players skip them): the frames go on
                // where a frame starts that the next one follows. They stay in the file as they are.
                p = resync(b, p + 1, first)
                if (p < 0) break
            }
            // Past the last frame there's no more audio, only what ends the file (ID3v1, APE, Lyrics3
            // tags, padding): kept as it is.

            // A Xing/Info frame (LAME) or VBRI frame first: it holds the length, it's not audio.
            val side = if (first.version == 3) (if (first.mono) 17 else 32) else (if (first.mono) 9 else 17)
            var xing = -1
            for (o in intArrayOf(4 + side, 4 + side + 2)) {
                if (ascii(b, head + o, "Xing") || ascii(b, head + o, "Info")) { xing = head + o; break }
            }
            if (ascii(b, head + 36, "VBRI")) throw Unsupported("MP3s with a VBRI header can't be trimmed here")
            val audioFirst = if (xing >= 0) 1 else 0
            val frames = count - audioFirst
            if (frames <= 0) throw Unsupported("No MP3 frames found")

            // Sample positions → frames. The decoder's own delay (~1 frame) puts sound slightly later
            // in the file than in the decoded audio: cut a little early at the start and late at the end.
            val spf = first.samples.toLong()
            val start = scale(startWanted, silence.sampleRate, first.sampleRate)
            val end = scale(endWanted, silence.sampleRate, first.sampleRate)
            val slack = (2304 / spf).toInt()
            val cutStart = if (start > 0) ((start / spf).toInt() - slack).coerceAtLeast(0) else 0
            val cutEnd = if (end < scale(silence.totalFrames, silence.sampleRate, first.sampleRate)) ((end + spf - 1) / spf).toInt() + slack else frames
            val keepFrom = cutStart.coerceAtMost(frames)
            val keepTo = cutEnd.coerceIn(keepFrom, frames)
            if (keepFrom == 0 && keepTo == frames) return null
            if (keepTo - keepFrom < 2) throw Unsupported("Nothing would be left of the song")

            val offsetsSoFar = offsets
            fun offsetOf(i: Int) = if (audioFirst + i < count) offsetsSoFar[audioFirst + i] else audioEnd
            val keptStart = offsetOf(keepFrom)
            val keptEnd = offsetOf(keepTo)
            b.writeTo(out, 0, head)
            if (xing >= 0) {
                val infoFrame = b.copyOfRange(head, offsets[1])
                val removedBytes = (keptStart - offsets[1]) + (audioEnd - keptEnd)
                fixInfo(infoFrame, xing - head, frames - (keepTo - keepFrom), removedBytes, b, keptStart, keptEnd, keepFrom, keepTo, ::offsetOf)
                out.write(infoFrame)
            }
            b.writeTo(out, keptStart, keptEnd - keptStart)
            b.writeTo(out, audioEnd, b.size - audioEnd)
            return Cut(ms(keepFrom * spf, first.sampleRate), ms((frames - keepTo) * spf, first.sampleRate))
        }

        /** Corrects the Xing/Info frame's frame and byte counts, seek table and LAME tag for the cut. */
        private fun fixInfo(
            f: ByteArray, x: Int, removedFrames: Int, removedBytes: Int,
            b: Src, keptStart: Int, keptEnd: Int, keepFrom: Int, keepTo: Int, offsetOf: (Int) -> Int,
        ) {
            val flags = be32(f, x + 4)
            var p = x + 8
            var totalBytes = -1
            if (flags and 1 != 0) { putBe32(f, p, (be32(f, p) - removedFrames).coerceAtLeast(0)); p += 4 }
            if (flags and 2 != 0) { totalBytes = (be32(f, p) - removedBytes).coerceAtLeast(1); putBe32(f, p, totalBytes); p += 4 }
            if (flags and 4 != 0) {
                // Seek table: where in the stream (1/256ths of its bytes) each percent of the song starts.
                val n = keepTo - keepFrom
                val total = (totalBytes.takeIf { it > 0 } ?: (f.size + keptEnd - keptStart)).toDouble()
                for (i in 0 until 100) {
                    val frame = keepFrom + (i.toLong() * n / 100).toInt()
                    val pos = f.size + (offsetOf(frame) - keptStart)
                    f[p + i] = (pos / total * 256).toInt().coerceIn(0, 255).toByte()
                }
                p += 100
            }
            if (flags and 8 != 0) p += 4
            // LAME tag: music length, music CRC and the tag's own CRC.
            val lame = p
            if (lame + 36 <= f.size && (ascii(f, lame, "LAME") || ascii(f, lame, "Lavc") || ascii(f, lame, "Lavf") || ascii(f, lame, "GOGO"))) {
                putBe32(f, lame + 28, (be32(f, lame + 28) - removedBytes).coerceAtLeast(0))
                val music = crc16Arc(b, keptStart, keptEnd)
                f[lame + 32] = (music shr 8).toByte(); f[lame + 33] = music.toByte()
                val tag = crc16Arc(f, 0, lame + 34)
                f[lame + 34] = (tag shr 8).toByte(); f[lame + 35] = tag.toByte()
            }
        }

        private val ARC = IntArray(256) { i ->
            var c = i
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xA001 else c ushr 1 }
            c
        }

        /** CRC-16/ARC (poly 0x8005 reflected), as LAME's tag uses. */
        fun crc16Arc(b: ByteArray, from: Int, to: Int): Int = crc16Arc(ArraySrc(b), from, to)

        internal fun crc16Arc(b: Src, from: Int, to: Int): Int {
            var crc = 0
            for (i in from until to) crc = (crc ushr 8) xor ARC[(crc xor u8(b, i)) and 0xFF]
            return crc and 0xFFFF
        }
    }

    // ---------------------------------------------------------------- FLAC

    internal object Flac {
        private val CRC8 = IntArray(256) { i ->
            var c = i
            repeat(8) { c = if (c and 0x80 != 0) (c shl 1) xor 0x07 else c shl 1 }
            c and 0xFF
        }
        private val CRC16 = IntArray(256) { i ->
            var c = i shl 8
            repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x8005 else c shl 1 }
            c and 0xFFFF
        }

        fun crc8(b: ByteArray, from: Int, to: Int): Int = crc8(ArraySrc(b), from, to)

        internal fun crc8(b: Src, from: Int, to: Int): Int {
            var c = 0
            for (i in from until to) c = CRC8[c xor u8(b, i)]
            return c
        }

        fun crc16(b: ByteArray, from: Int, to: Int): Int = crc16(ArraySrc(b), from, to)

        internal fun crc16(b: Src, from: Int, to: Int): Int {
            var c = 0
            for (i in from until to) c = ((c shl 8) xor CRC16[(c ushr 8) xor u8(b, i)]) and 0xFFFF
            return c
        }

        /** A frame header: its [number] (frame or sample number), block size and length in bytes; null when not valid. */
        class Header(val number: Long, val blockSize: Int, val length: Int, val variable: Boolean, val numberAt: Int, val numberLength: Int)

        fun header(b: ByteArray, p: Int): Header? = header(ArraySrc(b), p)

        internal fun header(b: Src, p: Int): Header? {
            if (p + 6 > b.size || u8(b, p) != 0xFF || u8(b, p + 1) and 0xFE != 0xF8) return null
            val bsCode = u8(b, p + 2) shr 4
            val srCode = u8(b, p + 2) and 0xF
            val chan = u8(b, p + 3) shr 4
            if (bsCode == 0 || srCode == 15 || chan > 10 || u8(b, p + 3) and 1 != 0) return null
            var q = p + 4
            val lead = u8(b, q)
            val extra = when {
                lead and 0x80 == 0 -> 0
                lead and 0xE0 == 0xC0 -> 1
                lead and 0xF0 == 0xE0 -> 2
                lead and 0xF8 == 0xF0 -> 3
                lead and 0xFC == 0xF8 -> 4
                lead and 0xFE == 0xFC -> 5
                lead == 0xFE -> 6
                else -> return null
            }
            if (q + 1 + extra > b.size) return null
            var number = (if (extra == 0) lead else lead and (0x3F shr extra)).toLong()
            for (k in 1..extra) {
                val c = u8(b, q + k)
                if (c and 0xC0 != 0x80) return null
                number = (number shl 6) or (c and 0x3F).toLong()
            }
            val numberAt = q
            q += 1 + extra
            val blockSize = when (bsCode) {
                1 -> 192
                in 2..5 -> 576 shl (bsCode - 2)
                6 -> { if (q + 1 > b.size) return null; u8(b, q++) + 1 }
                7 -> { if (q + 2 > b.size) return null; ((u8(b, q) shl 8) or u8(b, q + 1)).also { q += 2 } + 1 }
                else -> 256 shl (bsCode - 8)
            }
            q += when (srCode) { 12 -> 1; 13, 14 -> 2; else -> 0 }
            if (q + 1 > b.size || crc8(b, p, q) != u8(b, q)) return null
            return Header(number, blockSize, q + 1 - p, u8(b, p + 1) and 1 == 1, numberAt, 1 + extra)
        }

        private fun utf8(v: Long): ByteArray = when {
            v < 0x80 -> byteArrayOf(v.toByte())
            else -> {
                var n = 1
                while (v >= (1L shl (5 * n + 6))) n++ // n continuation bytes
                val out = ByteArray(n + 1)
                var x = v
                for (k in n downTo 1) { out[k] = (0x80 or (x and 0x3F).toInt()).toByte(); x = x shr 6 }
                out[0] = (((0xFF00 shr (n + 1)) and 0xFF) or x.toInt()).toByte()
                out
            }
        }

        fun cut(b: Src, startWanted: Long, endWanted: Long, silence: Silence, out: OutputStream): Cut? {
            val tagged = id3v2Length(b)
            if (!ascii(b, tagged, "fLaC")) throw Unsupported("Not a FLAC file")
            // Metadata blocks.
            var p = tagged + 4
            var streamInfo = -1
            val blocks = ArrayList<Int>()
            while (true) {
                if (p + 4 > b.size) throw Unsupported("The FLAC file is cut short")
                val type = u8(b, p) and 0x7F
                val len = (u8(b, p + 1) shl 16) or (u8(b, p + 2) shl 8) or u8(b, p + 3)
                if (type == 0) streamInfo = p + 4
                blocks += p
                val last = u8(b, p) and 0x80 != 0
                p += 4 + len
                if (last) break
            }
            if (streamInfo < 0) throw Unsupported("The FLAC file has no stream info")
            val rate = (u8(b, streamInfo + 10) shl 12) or (u8(b, streamInfo + 11) shl 4) or (u8(b, streamInfo + 12) shr 4)
            if (rate <= 0) throw Unsupported("The FLAC file has no sample rate")

            // Frames: each next one is found by its header (CRC-8 and number) and checked by the CRC-16 ending the one before.
            var starts = IntArray(1 shl 12)
            val heads = ArrayList<Header>()
            var h = header(b, p) ?: throw Unsupported("The FLAC frames couldn't be read")
            var end = b.size
            if (end - 128 > p && ascii(b, end - 128, "TAG")) end -= 128
            while (true) {
                if (heads.size + 1 >= starts.size) starts = starts.copyOf(starts.size * 2)
                starts[heads.size] = p
                heads += h
                val expected = if (h.variable) h.number + h.blockSize else h.number + 1
                var q = p + h.length + 2
                var next: Header? = null
                while (q + 6 <= end) {
                    if (u8(b, q) == 0xFF && u8(b, q + 1) and 0xFE == 0xF8) {
                        val c = header(b, q)
                        if (c != null && c.number == expected && crc16(b, p, q - 2) == ((u8(b, q - 2) shl 8) or u8(b, q - 1))) { next = c; break }
                    }
                    q++
                }
                if (next == null) {
                    if (crc16(b, p, end - 2) != ((u8(b, end - 2) shl 8) or u8(b, end - 1))) throw Unsupported("The FLAC frames couldn't be followed to the end")
                    starts[heads.size] = end
                    break
                }
                p = q
                h = next
            }
            val n = heads.size
            val firstSample = LongArray(n + 1)
            for (i in 0 until n) firstSample[i + 1] = firstSample[i] + heads[i].blockSize

            val start = scale(startWanted, silence.sampleRate, rate)
            val endS = scale(endWanted, silence.sampleRate, rate)
            var keepFrom = 0
            while (keepFrom < n - 1 && firstSample[keepFrom + 1] <= start) keepFrom++
            var keepTo = n
            if (endWanted < silence.totalFrames) {
                keepTo = keepFrom + 1
                while (keepTo < n && firstSample[keepTo] < endS) keepTo++
            }
            if (keepFrom == 0 && keepTo == n) return null

            val meta = b.copyOfRange(0, starts[0])
            // Total samples (36 bits), MD5 and frame sizes unknown now; seek tables become padding.
            val kept = firstSample[keepTo] - firstSample[keepFrom]
            val si = streamInfo
            meta[si + 4] = 0; meta[si + 5] = 0; meta[si + 6] = 0; meta[si + 7] = 0; meta[si + 8] = 0; meta[si + 9] = 0
            meta[si + 13] = ((u8(meta, si + 13) and 0xF0) or ((kept shr 32).toInt() and 0x0F)).toByte()
            putBe32(meta, si + 14, kept.toInt())
            for (k in 0 until 16) meta[si + 18 + k] = 0
            for (bp in blocks) {
                if (u8(meta, bp) and 0x7F == 3) {
                    meta[bp] = ((u8(meta, bp) and 0x80) or 1).toByte()
                    val len = (u8(meta, bp + 1) shl 16) or (u8(meta, bp + 2) shl 8) or u8(meta, bp + 3)
                    for (k in 0 until len) meta[bp + 4 + k] = 0
                }
            }
            out.write(meta)
            for (i in keepFrom until keepTo) {
                val hd = heads[i]
                val fp = starts[i]
                val number = if (hd.variable) firstSample[i] - firstSample[keepFrom] else (i - keepFrom).toLong()
                val f = ByteArrayOutputStream(starts[i + 1] - fp + 8)
                b.writeTo(f, fp, hd.numberAt - fp)
                f.write(utf8(number))
                val afterNumber = hd.numberAt + hd.numberLength
                b.writeTo(f, afterNumber, fp + hd.length - 1 - afterNumber)
                val headBytes = f.toByteArray()
                f.write(crc8(headBytes, 0, headBytes.size))
                b.writeTo(f, fp + hd.length, starts[i + 1] - 2 - (fp + hd.length))
                val frame = f.toByteArray()
                val crc = crc16(frame, 0, frame.size)
                out.write(frame)
                out.write(crc shr 8)
                out.write(crc and 0xFF)
            }
            b.writeTo(out, end, b.size - end)
            return Cut(ms(firstSample[keepFrom], rate), ms(firstSample[n] - firstSample[keepTo], rate))
        }
    }

    // ---------------------------------------------------------------- WAV

    internal object Wav {
        fun cut(b: Src, startWanted: Long, endWanted: Long, silence: Silence, out: OutputStream): Cut? {
            if (!ascii(b, 0, "RIFF") || !ascii(b, 8, "WAVE")) throw Unsupported("Only plain RIFF WAV files can be trimmed")
            var p = 12
            var align = 0
            var rate = 0
            var data = -1
            var dataLen = 0
            while (p + 8 <= b.size) {
                val len = le32(b, p + 4)
                if (len < 0) throw Unsupported("The WAV file is too big")
                if (ascii(b, p, "fmt ")) {
                    rate = le32(b, p + 12)
                    align = u8(b, p + 20) or (u8(b, p + 21) shl 8)
                } else if (ascii(b, p, "data")) {
                    data = p
                    dataLen = len.coerceAtMost(b.size - p - 8)
                    break
                }
                p += 8 + len + (len and 1)
            }
            if (data < 0 || align <= 0 || rate <= 0) throw Unsupported("The WAV file has no audio")
            val total = (dataLen / align).toLong()
            val start = scale(startWanted, silence.sampleRate, rate).coerceIn(0, total)
            val end = if (endWanted < silence.totalFrames) scale(endWanted, silence.sampleRate, rate).coerceIn(start, total) else total
            if (start == 0L && end == total) return null
            val payload = data + 8
            val oldChunkEnd = payload + dataLen + (dataLen and 1)
            val newLen = ((end - start) * align).toInt()
            val pad = newLen and 1
            val after = (b.size - oldChunkEnd).coerceAtLeast(0)
            // Everything up to the audio, with the new sizes.
            val headBytes = b.copyOfRange(0, payload)
            putLe32(headBytes, data + 4, newLen)
            putLe32(headBytes, 4, payload + newLen + pad + after - 8)
            out.write(headBytes)
            b.writeTo(out, payload + (start * align).toInt(), newLen)
            if (pad == 1) out.write(0)
            if (after > 0) b.writeTo(out, oldChunkEnd, after)
            return Cut(ms(start, rate), ms(total - end, rate))
        }
    }
}
