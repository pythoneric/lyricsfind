package com.biteric.songfixer.files

import com.biteric.songfixer.loudness.Silence
import com.biteric.songfixer.loudness.SilenceDetector
import java.io.ByteArrayOutputStream

/**
 * Cuts the silence off a song's start and end without re-encoding it: whole MP3 frames
 * (~26 ms each), whole FLAC frames, or single WAV samples. Tags at the start and end of the
 * file are kept as they are; the headers that give the song's length are corrected.
 */
object SilenceTrimmer {
    val FORMATS = setOf("mp3", "flac", "wav")

    /** Left before the first sound, so its attack isn't clipped. */
    const val KEEP_BEFORE_MS = 50L
    /** Left after the last sound, so a fade-out ends on its own. */
    const val KEEP_AFTER_MS = 150L

    class Unsupported(message: String) : Exception(message)

    /** The trimmed file, and how much was cut at each end. */
    class Cut(val bytes: ByteArray, val startMs: Long, val endMs: Long)

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

    /** The trimmed file; null when there's nothing worth cutting. */
    fun cut(bytes: ByteArray, extension: String, silence: Silence): Cut? {
        val (start, end) = plan(silence) ?: return null
        return when (extension.lowercase()) {
            "mp3" -> Mp3.cut(bytes, start, end, silence)
            "flac" -> Flac.cut(bytes, start, end, silence)
            "wav" -> Wav.cut(bytes, start, end, silence)
            else -> throw Unsupported(".$extension files can't be trimmed here")
        }
    }

    private fun scale(frames: Long, from: Int, to: Int): Long = if (from == to) frames else frames * to / from

    private fun ms(samples: Long, rate: Int): Long = samples * 1000 / rate

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun be32(b: ByteArray, i: Int) = (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)
    private fun putBe32(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 24).toByte(); b[i + 1] = (v ushr 16).toByte(); b[i + 2] = (v ushr 8).toByte(); b[i + 3] = v.toByte()
    }
    private fun le32(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16) or (u8(b, i + 3) shl 24)
    private fun putLe32(b: ByteArray, i: Int, v: Int) {
        b[i] = v.toByte(); b[i + 1] = (v ushr 8).toByte(); b[i + 2] = (v ushr 16).toByte(); b[i + 3] = (v ushr 24).toByte()
    }
    private fun ascii(b: ByteArray, i: Int, s: String) = i >= 0 && i + s.length <= b.size && s.indices.all { b[i + it] == s[it].code.toByte() }

    /** Bytes of the ID3v2 tags at the start of [b] (0 when none). */
    private fun id3v2Length(b: ByteArray): Int {
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

        fun header(b: ByteArray, p: Int): Header? {
            if (p + 4 > b.size || u8(b, p) != 0xFF || u8(b, p + 1) and 0xE0 != 0xE0) return null
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

        fun cut(b: ByteArray, startWanted: Long, endWanted: Long, silence: Silence): Cut? {
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
            val offsets = ArrayList<Int>()
            while (true) {
                val h = header(b, p) ?: break
                if (!sameStream(h, first) || p + h.length > b.size) break
                offsets += p
                p += h.length
            }
            val audioEnd = p
            // What follows the frames must be tags (ID3v1, APE, Lyrics3), not more audio we lost track of.
            val rest = b.size - audioEnd
            if (rest > 0 && !ascii(b, audioEnd, "TAG") && !ascii(b, audioEnd, "APETAGEX") && !ascii(b, audioEnd, "LYRICS") && rest > 4096) {
                throw Unsupported("The MP3's frames couldn't be followed to its end")
            }

            // A Xing/Info frame (LAME) or VBRI frame first: it holds the length, it's not audio.
            val side = if (first.version == 3) (if (first.mono) 17 else 32) else (if (first.mono) 9 else 17)
            var xing = -1
            for (o in intArrayOf(4 + side, 4 + side + 2)) {
                if (ascii(b, head + o, "Xing") || ascii(b, head + o, "Info")) { xing = head + o; break }
            }
            if (ascii(b, head + 36, "VBRI")) throw Unsupported("MP3s with a VBRI header can't be trimmed here")
            val audioFirst = if (xing >= 0) 1 else 0
            val frames = offsets.size - audioFirst
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

            fun offsetOf(i: Int) = if (audioFirst + i < offsets.size) offsets[audioFirst + i] else audioEnd
            val keptStart = offsetOf(keepFrom)
            val keptEnd = offsetOf(keepTo)
            val out = ByteArrayOutputStream(b.size)
            out.write(b, 0, head)
            val infoFrame = if (xing >= 0) b.copyOfRange(head, offsets[1]) else null
            if (infoFrame != null) {
                val removedBytes = (keptStart - offsets[1]) + (audioEnd - keptEnd)
                fixInfo(infoFrame, xing - head, frames - (keepTo - keepFrom), removedBytes, b, keptStart, keptEnd, keepFrom, keepTo, ::offsetOf)
                out.write(infoFrame)
            }
            out.write(b, keptStart, keptEnd - keptStart)
            out.write(b, audioEnd, b.size - audioEnd)
            return Cut(out.toByteArray(), ms(keepFrom * spf, first.sampleRate), ms((frames - keepTo) * spf, first.sampleRate))
        }

        /** Corrects the Xing/Info frame's frame and byte counts, seek table and LAME tag for the cut. */
        private fun fixInfo(
            f: ByteArray, x: Int, removedFrames: Int, removedBytes: Int,
            b: ByteArray, keptStart: Int, keptEnd: Int, keepFrom: Int, keepTo: Int, offsetOf: (Int) -> Int,
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
                    val frame = keepFrom + (i * n / 100)
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

        /** CRC-16/ARC (poly 0x8005 reflected), as LAME's tag uses. */
        fun crc16Arc(b: ByteArray, from: Int, to: Int): Int {
            var crc = 0
            for (i in from until to) {
                crc = crc xor u8(b, i)
                repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
            }
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

        fun crc8(b: ByteArray, from: Int, to: Int): Int {
            var c = 0
            for (i in from until to) c = CRC8[c xor u8(b, i)]
            return c
        }

        fun crc16(b: ByteArray, from: Int, to: Int): Int {
            var c = 0
            for (i in from until to) c = ((c shl 8) xor CRC16[(c ushr 8) xor u8(b, i)]) and 0xFFFF
            return c
        }

        /** A frame header: its [number] (frame or sample number), block size and length in bytes; null when not valid. */
        class Header(val number: Long, val blockSize: Int, val length: Int, val variable: Boolean, val numberAt: Int, val numberLength: Int)

        fun header(b: ByteArray, p: Int): Header? {
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

        fun cut(b: ByteArray, startWanted: Long, endWanted: Long, silence: Silence): Cut? {
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
            val starts = ArrayList<Int>()
            val heads = ArrayList<Header>()
            var h = header(b, p) ?: throw Unsupported("The FLAC frames couldn't be read")
            var end = b.size
            if (end - 128 > p && ascii(b, end - 128, "TAG")) end -= 128
            while (true) {
                starts += p
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
                    starts += end
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

            val out = ByteArrayOutputStream(b.size)
            out.write(b, 0, starts[0])
            val meta = out.toByteArray()
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
            val frames = ByteArrayOutputStream(b.size)
            frames.write(meta)
            for (i in keepFrom until keepTo) {
                val hd = heads[i]
                val fp = starts[i]
                val number = if (hd.variable) firstSample[i] - firstSample[keepFrom] else (i - keepFrom).toLong()
                val f = ByteArrayOutputStream(starts[i + 1] - fp + 8)
                f.write(b, fp, hd.numberAt - fp)
                f.write(utf8(number))
                val afterNumber = hd.numberAt + hd.numberLength
                f.write(b, afterNumber, fp + hd.length - 1 - afterNumber)
                val headBytes = f.toByteArray()
                f.write(crc8(headBytes, 0, headBytes.size))
                f.write(b, fp + hd.length, starts[i + 1] - 2 - (fp + hd.length))
                val frame = f.toByteArray()
                val crc = crc16(frame, 0, frame.size)
                frames.write(frame)
                frames.write(crc shr 8)
                frames.write(crc and 0xFF)
            }
            frames.write(b, end, b.size - end)
            return Cut(frames.toByteArray(), ms(firstSample[keepFrom], rate), ms(firstSample[n] - firstSample[keepTo], rate))
        }
    }

    // ---------------------------------------------------------------- WAV

    internal object Wav {
        fun cut(b: ByteArray, startWanted: Long, endWanted: Long, silence: Silence): Cut? {
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
            val out = ByteArrayOutputStream(b.size)
            out.write(b, 0, payload)
            out.write(b, payload + (start * align).toInt(), newLen)
            if (newLen and 1 == 1) out.write(0)
            if (oldChunkEnd < b.size) out.write(b, oldChunkEnd, b.size - oldChunkEnd)
            val o = out.toByteArray()
            putLe32(o, data + 4, newLen)
            putLe32(o, 4, o.size - 8)
            return Cut(o, ms(start, rate), ms(total - end, rate))
        }
    }
}
