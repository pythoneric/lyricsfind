package com.biteric.songfixer.files

import com.biteric.songfixer.loudness.Silence
import com.biteric.songfixer.loudness.SilenceDetector
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.sin

/** Synthetic songs: [leadS] seconds of silence, [soundS] of a tone, [tailS] of silence (mono, 16 bit). */
class SilenceTrimmerTest {
    private val rate = 44_100

    private fun pcm(leadS: Double, soundS: Double, tailS: Double): ShortArray {
        val lead = (leadS * rate).toInt()
        val sound = (soundS * rate).toInt()
        val tail = (tailS * rate).toInt()
        return ShortArray(lead + sound + tail) { i ->
            if (i in lead until lead + sound) (0.5 * 32767 * sin(2 * PI * 440 * i / rate)).toInt().toShort() else 0
        }
    }

    private fun detect(samples: ShortArray): Silence {
        val d = SilenceDetector(rate, 1)
        d.feed(FloatArray(samples.size) { samples[it] / 32768f }, samples.size)
        return d.result()
    }

    @Test
    fun detectorFindsBothEnds() {
        val s = detect(pcm(2.0, 3.0, 4.0))
        assertEquals(2000.0, s.leadMs.toDouble(), 10.0)
        assertEquals(4000.0, s.tailMs.toDouble(), 10.0)
        assertTrue(!s.silent)
    }

    @Test
    fun hissBelowTheThresholdCountsAsSilence() {
        val samples = pcm(1.0, 1.0, 1.0)
        for (i in 0 until rate) samples[i] = (if (i % 2 == 0) 40 else -40).toShort() // about −58 dBFS
        assertEquals(1000.0, detect(samples).leadMs.toDouble(), 10.0)
    }

    @Test
    fun allSilentIsSilent() = assertTrue(detect(ShortArray(rate)).silent)

    @Test
    fun shortSilenceIsLeftAlone() {
        val s = detect(pcm(0.2, 2.0, 0.3))
        assertNull(SilenceTrimmer.plan(s))
    }

    // ------------------------------------------------------------ WAV

    private fun wav(samples: ShortArray, extra: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArrayOutputStream()
        fun le32(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun le16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        out.write("RIFF".toByteArray()); le32(36 + samples.size * 2 + extra.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1); le32(rate); le32(rate * 2); le16(2); le16(16)
        out.write("data".toByteArray()); le32(samples.size * 2)
        for (s in samples) le16(s.toInt())
        out.write(extra)
        return out.toByteArray()
    }

    private fun wavSamples(b: ByteArray): Int {
        var p = 12
        while (true) {
            val len = (b[p + 4].toInt() and 0xFF) or ((b[p + 5].toInt() and 0xFF) shl 8) or ((b[p + 6].toInt() and 0xFF) shl 16) or ((b[p + 7].toInt() and 0xFF) shl 24)
            if (String(b, p, 4) == "data") return len / 2
            p += 8 + len
        }
    }

    @Test
    fun wavLosesItsSilenceKeepingTheMargins() {
        val samples = pcm(2.0, 3.0, 4.0)
        val list = "LIST".toByteArray() + byteArrayOf(4, 0, 0, 0) + "INFO".toByteArray()
        val cut = SilenceTrimmer.cut(wav(samples, list), "wav", detect(samples))!!
        assertEquals(1950.0, cut.startMs.toDouble(), 15.0)
        assertEquals(3850.0, cut.endMs.toDouble(), 15.0)
        val left = wavSamples(cut.bytes)
        assertEquals(3.2 * rate, left.toDouble(), 0.03 * rate)
        // The chunk after the audio stays, and the RIFF size is right.
        assertArrayEquals(list, cut.bytes.copyOfRange(cut.bytes.size - list.size, cut.bytes.size))
        val riff = (cut.bytes[4].toInt() and 0xFF) or ((cut.bytes[5].toInt() and 0xFF) shl 8) or ((cut.bytes[6].toInt() and 0xFF) shl 16)
        assertEquals(cut.bytes.size - 8, riff)
        // Nothing left to cut after.
        val after = ShortArray(left) { samples[(cut.startMs * rate / 1000).toInt() + it] }
        assertNull(SilenceTrimmer.cut(cut.bytes, "wav", detect(after)))
    }

    // ------------------------------------------------------------ FLAC

    /** A FLAC file of verbatim frames (fixed block size 4096). */
    private fun flac(samples: ShortArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray())
        // STREAMINFO (last block).
        out.write(byteArrayOf(0x80.toByte(), 0, 0, 34))
        val si = ByteArray(34)
        si[0] = 0x10; si[1] = 0; si[2] = 0x10; si[3] = 0
        si[10] = (rate shr 12).toByte(); si[11] = (rate shr 4).toByte()
        si[12] = (((rate and 0xF) shl 4) or (0 shl 1) or (15 shr 4)).toByte() // mono, 16 bit
        si[13] = (((15 and 0xF) shl 4) or 0).toByte()
        val total = samples.size
        si[14] = (total shr 24).toByte(); si[15] = (total shr 16).toByte(); si[16] = (total shr 8).toByte(); si[17] = total.toByte()
        out.write(si)
        var n = 0
        var frame = 0
        while (n < samples.size) {
            val bs = minOf(4096, samples.size - n)
            val f = ByteArrayOutputStream()
            f.write(0xFF); f.write(0xF8)
            f.write(if (bs == 4096) 0xC9 else 0x79) // 4096 or 16-bit explicit size; 44.1 kHz
            f.write(0x08) // mono, 16 bit
            f.write(frame) // frame number (< 128 here)
            if (bs != 4096) { f.write((bs - 1) shr 8); f.write((bs - 1) and 0xFF) }
            val h = f.toByteArray()
            f.write(SilenceTrimmer.Flac.crc8(h, 0, h.size))
            f.write(0x02) // verbatim subframe
            for (k in 0 until bs) { val v = samples[n + k].toInt(); f.write(v shr 8); f.write(v and 0xFF) }
            val body = f.toByteArray()
            val crc = SilenceTrimmer.Flac.crc16(body, 0, body.size)
            out.write(body); out.write(crc shr 8); out.write(crc and 0xFF)
            n += bs
            frame++
        }
        return out.toByteArray()
    }

    @Test
    fun flacFramesAreCutRenumberedAndStillCheckOut() {
        val samples = pcm(2.0, 3.0, 2.0)
        val cut = SilenceTrimmer.cut(flac(samples), "flac", detect(samples))!!
        assertTrue("start ${cut.startMs}", cut.startMs in 1800..1950)
        assertTrue("end ${cut.endMs}", cut.endMs in 1700..1850)
        val b = cut.bytes
        // Walk the frames: numbers from 0, every CRC good, total samples as the stream info says.
        var p = 4 + 4 + 34
        var expected = 0L
        var total = 0L
        while (p < b.size) {
            val h = SilenceTrimmer.Flac.header(b, p)!!
            assertEquals(expected++, h.number)
            val len = h.length + 1 + h.blockSize * 2 + 2
            val crc = SilenceTrimmer.Flac.crc16(b, p, p + len - 2)
            assertEquals(crc, ((b[p + len - 2].toInt() and 0xFF) shl 8) or (b[p + len - 1].toInt() and 0xFF))
            total += h.blockSize
            p += len
        }
        val info = 8 + 13
        val stated = ((b[info].toLong() and 0x0F) shl 32) or ((b[info + 1].toLong() and 0xFF) shl 24) or
            ((b[info + 2].toLong() and 0xFF) shl 16) or ((b[info + 3].toLong() and 0xFF) shl 8) or (b[info + 4].toLong() and 0xFF)
        assertEquals(total, stated)
        assertEquals((samples.size - total) * 1000.0 / rate, (cut.startMs + cut.endMs).toDouble(), 2.0)
    }

    // ------------------------------------------------------------ MP3

    /** 128 kbps MPEG-1 Layer III frames of [frames] count, empty, after an ID3v2 tag and a LAME Info frame. */
    private fun mp3(frames: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray() + byteArrayOf(3, 0, 0, 0, 0, 0, 10) + ByteArray(10))
        val info = ByteArray(417)
        info[0] = 0xFF.toByte(); info[1] = 0xFB.toByte(); info[2] = 0x90.toByte(); info[3] = 0x44
        val x = 4 + 32
        "Info".toByteArray().copyInto(info, x)
        info[x + 7] = 0x0F
        fun be32(at: Int, v: Int) { info[at] = (v shr 24).toByte(); info[at + 1] = (v shr 16).toByte(); info[at + 2] = (v shr 8).toByte(); info[at + 3] = v.toByte() }
        be32(x + 8, frames)
        be32(x + 12, 417 * (frames + 1))
        "LAME3.100".toByteArray().copyInto(info, x + 120)
        be32(x + 120 + 28, 417 * (frames + 1))
        out.write(info)
        for (i in 0 until frames) {
            val f = ByteArray(417)
            f[0] = 0xFF.toByte(); f[1] = 0xFB.toByte(); f[2] = 0x90.toByte(); f[3] = 0x44
            f[100] = i.toByte() // tells frames apart
            out.write(f)
        }
        out.write("TAG".toByteArray() + ByteArray(125))
        return out.toByteArray()
    }

    @Test
    fun mp3KeepsTagsAndCorrectsTheInfoFrame() {
        val frames = 400 // ~10.4 s
        val spf = 1152L
        // Sound from frame 100 to frame 250.
        val silence = Silence(rate, frames * spf, 100 * spf, 250 * spf)
        val b = mp3(frames)
        val cut = SilenceTrimmer.cut(b, "mp3", silence)!!
        val o = cut.bytes
        // ID3v2 first, ID3v1 last, untouched.
        assertArrayEquals(b.copyOfRange(0, 20), o.copyOfRange(0, 20))
        assertArrayEquals(b.copyOfRange(b.size - 128, b.size), o.copyOfRange(o.size - 128, o.size))
        val kept = (o.size - 20 - 417 - 128) / 417
        val x = 20 + 36
        fun be32(at: Int) = ((o[at].toInt() and 0xFF) shl 24) or ((o[at + 1].toInt() and 0xFF) shl 16) or ((o[at + 2].toInt() and 0xFF) shl 8) or (o[at + 3].toInt() and 0xFF)
        assertEquals(kept, be32(x + 8))
        assertEquals(417 * (kept + 1), be32(x + 12))
        assertEquals(417 * (kept + 1), be32(x + 120 + 28))
        // The LAME tag's CRC matches again.
        val crc = SilenceTrimmer.Mp3.crc16Arc(o, 20, 20 + x - 20 + 120 + 34)
        assertEquals(crc, ((o[x + 120 + 34].toInt() and 0xFF) shl 8) or (o[x + 120 + 35].toInt() and 0xFF))
        // The first kept frame is a little before the sound (never after it), the last a little after.
        val firstKept = o[20 + 417 + 100].toInt() and 0xFF
        assertTrue("first kept $firstKept", firstKept in 90..99)
        val lastKept = firstKept + kept - 1
        assertTrue("last kept $lastKept", lastKept in 255..262)
        assertEquals(firstKept * spf * 1000 / rate, cut.startMs)
    }
}
