package com.biteric.songfixer

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.biteric.songfixer.data.Song
import com.biteric.songfixer.data.SongDatabase
import com.biteric.songfixer.data.SongStatus
import com.biteric.songfixer.files.SilenceTrimmer
import com.biteric.songfixer.files.SongWriter
import com.biteric.songfixer.loudness.LoudnessAnalyzer
import com.biteric.songfixer.loudness.Silence
import kotlinx.coroutines.test.runTest
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Trimming checked with Android's own decoders: silence is added to real and generated songs,
 * cut off by [SongWriter.trimSilence], and the result decoded again.
 */
@RunWith(AndroidJUnit4::class)
class TrimSilenceTest {
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val analyzer = LoudnessAnalyzer(appContext)
    private val writer = SongWriter(appContext)

    private fun file(name: String, bytes: ByteArray) = File(appContext.cacheDir, "trimtest-$name").apply { writeBytes(bytes) }

    private suspend fun trimAndCheck(f: File, ext: String, addedLeadMs: Long, addedTailMs: Long) {
        val before = analyzer.silence(Uri.fromFile(f))
        android.util.Log.i("TrimSilenceTest", "$ext before: lead ${before.leadMs} ms, tail ${before.tailMs} ms, ${before.totalFrames} frames")
        assertTrue("lead ${before.leadMs}", before.leadMs >= addedLeadMs)
        assertTrue("tail ${before.tailMs}", before.tailMs >= addedTailMs)
        val beforeLoud = analyzer.measure(Uri.fromFile(f))

        val t = writer.trimSilence(Uri.fromFile(f), ext) { analyzer.silence(it) }
        val after = analyzer.silence(Uri.fromFile(f))
        android.util.Log.i("TrimSilenceTest", "$ext cut ${t.startMs} + ${t.endMs} ms; after: lead ${after.leadMs} ms, tail ${after.tailMs} ms")

        // The silence is gone but for the margins kept.
        assertTrue("lead after ${after.leadMs}", after.leadMs in 0..SilenceTrimmer.KEEP_BEFORE_MS + 200)
        assertTrue("tail after ${after.tailMs}", after.tailMs in 0..SilenceTrimmer.KEEP_AFTER_MS + 200)
        assertTrue("cut ${t.startMs}", t.startMs >= before.leadMs - SilenceTrimmer.KEEP_BEFORE_MS - 200)
        assertTrue("cut ${t.endMs}", t.endMs >= before.tailMs - SilenceTrimmer.KEEP_AFTER_MS - 200)
        // The sound itself is all there: same length and same loudness.
        val soundBefore = (before.soundEnd - before.soundStart) * 1000 / before.sampleRate
        val soundAfter = (after.soundEnd - after.soundStart) * 1000 / after.sampleRate
        assertEquals(soundBefore.toDouble(), soundAfter.toDouble(), 30.0)
        val afterLoud = analyzer.measure(Uri.fromFile(f))
        // Loudness is measured in 400 ms blocks from the file's start: once the start moves they fall
        // a little differently over the fades (~0.1 LU on these test tones with long fades).
        assertEquals(beforeLoud.lufs, afterLoud.lufs, 0.25)
        assertEquals(beforeLoud.truePeak, afterLoud.truePeak, 0.01)
        // The header's length agrees with what decodes.
        val header = AudioFileIO.read(f).audioHeader.trackLength.toDouble()
        val decoded = after.totalFrames.toDouble() / after.sampleRate
        assertTrue("header ${header}s, decoded ${decoded}s", abs(header - decoded) < 1.5)
        // Nothing more to cut.
        assertEquals(0L, writer.trimSilence(Uri.fromFile(f), ext) { analyzer.silence(it) }.let { it.startMs + it.endMs })
        f.delete()
    }

    /** test.mp3 with ~2 s of silent frames after its Info frame and ~3 s before its end tags. */
    private fun paddedMp3(): ByteArray = ByteArrayOutputStream().also { writePaddedMp3(it) }.toByteArray()

    /**
     * Writes test.mp3 with ~2 s of silent frames after its Info frame and ~3 s before its end tags,
     * its audio [repeats] times over (a long mix, written as it goes); the Info frame counts it all.
     */
    /** Returns how many audio frames it wrote (the Info frame not counted). */
    private fun writePaddedMp3(out: java.io.OutputStream, repeats: Int = 1): Int {
        val b = testContext.assets.open("test.mp3").use { it.readBytes() }
        var p = 0
        if (String(b, 0, 3) == "ID3") p = 10 + ((b[6].toInt() and 0x7F) shl 21 or ((b[7].toInt() and 0x7F) shl 14) or ((b[8].toInt() and 0x7F) shl 7) or (b[9].toInt() and 0x7F))
        while (SilenceTrimmer.Mp3.header(b, p)?.let { SilenceTrimmer.Mp3.header(b, p + it.length) } == null) p++
        val first = SilenceTrimmer.Mp3.header(b, p)!!
        // Frames from here on; the end of the last one.
        var end = p
        var frames = 0
        while (true) {
            val h = SilenceTrimmer.Mp3.header(b, end) ?: break
            if (end + h.length > b.size) break
            end += h.length
            frames++
        }
        val hasInfo = listOf(p + 36, p + 21, p + 13).any { String(b, it, 4) == "Xing" || String(b, it, 4) == "Info" }
        val audioStart = if (hasInfo) p + first.length else p
        val audioFrames = if (hasInfo) frames - 1 else frames
        // A silent frame: the same stream, no CRC, no padding, all side info zero (no audio data).
        val silent = ByteArray(first.length).also {
            b.copyInto(it, 0, p, p + 4)
            it[1] = (it[1].toInt() or 1).toByte()
            it[2] = (it[2].toInt() and 0xFD).toByte()
        }
        val frameLen = SilenceTrimmer.Mp3.header(silent, 0)!!.length
        val one = silent.copyOf(frameLen)
        val perSecond = first.sampleRate / first.samples
        // The Info frame counts the added frames too, as an encoder would have.
        val head = b.copyOf(audioStart)
        val x = listOf(p + 36, p + 21, p + 13).firstOrNull { String(head, it, 4) == "Xing" || String(head, it, 4) == "Info" }
        if (x != null) {
            val flags = head[x + 7].toInt()
            fun add(at: Int, n: Int) {
                val v = ((head[at].toInt() and 0xFF) shl 24 or ((head[at + 1].toInt() and 0xFF) shl 16) or ((head[at + 2].toInt() and 0xFF) shl 8) or (head[at + 3].toInt() and 0xFF)) + n
                head[at] = (v shr 24).toByte(); head[at + 1] = (v shr 16).toByte(); head[at + 2] = (v shr 8).toByte(); head[at + 3] = v.toByte()
            }
            var q = x + 8
            if (flags and 1 != 0) { add(q, 5 * perSecond + (repeats - 1) * audioFrames); q += 4 }
            if (flags and 2 != 0) add(q, 5 * perSecond * frameLen + (repeats - 1) * (end - audioStart))
        }
        out.write(head)
        repeat(2 * perSecond) { out.write(one) }
        repeat(repeats) { out.write(b, audioStart, end - audioStart) }
        repeat(3 * perSecond) { out.write(one) }
        out.write(b, end, b.size - end)
        return 5 * perSecond + repeats * audioFrames
    }

    /**
     * A long mix (~130 MB, like the 127 MB "1 cena Boda 1.mp3" that closed the app): trimmed within
     * the app's memory. It ran out of memory when the whole song was read in and cut in memory.
     */
    @Test
    fun aLongMixIsTrimmedWithinTheAppsMemory() = runTest(timeout = kotlin.time.Duration.parse("5m")) {
        val f = File(appContext.cacheDir, "trimtest-long-mix.mp3")
        val frames = f.outputStream().buffered().use { writePaddedMp3(it, repeats = 1600) }.toLong()
        try {
            android.util.Log.i("TrimSilenceTest", "long mix: ${f.length()} bytes, heap limit ${Runtime.getRuntime().maxMemory()}")
            assertTrue("${f.length()} bytes", f.length() > 120_000_000)
            // Where its sound starts and ends is known (2 s in, 3 s before the end): decoding 2+ hours on
            // an emulator takes longer than the test may. The memory problem was in the cut, after it.
            val rate = 44_100
            val known = Silence(rate, frames * 1152, 2L * rate, frames * 1152 - 3L * rate)
            val t = writer.trimSilence(Uri.fromFile(f), "mp3") { known }
            android.util.Log.i("TrimSilenceTest", "long mix cut ${t.startMs} + ${t.endMs} ms, now ${f.length()} bytes")
            assertTrue("cut ${t.startMs}", t.startMs in 1700..2100)
            assertTrue("cut ${t.endMs}", t.endMs in 2600..3100)
            // Still a sound MP3: its Info frame counts what's in it.
            val header = AudioFileIO.read(f).audioHeader.trackLength
            assertTrue("length $header s", header > 7000)
        } finally {
            f.delete()
        }
    }

    @Test
    fun mp3() = runTest {
        trimAndCheck(file("song.mp3", paddedMp3()), "mp3", 1900, 2900)
    }

    /** ID3v2 tags at the start (with a cover) and ID3v1 at the end come through the cut. */
    @Test
    fun mp3KeepsItsTags() = runTest {
        val g = file("tagged.mp3", paddedMp3())
        AudioFileIO.read(g).let { audio ->
            audio.tagOrCreateAndSetDefault.setField(FieldKey.TITLE, "Trim Test Title")
            audio.tag.setField(FieldKey.ARTIST, "Trim Test Artist")
            audio.commit()
        }
        val t = writer.trimSilence(Uri.fromFile(g), "mp3") { analyzer.silence(it) }
        assertTrue(t.startMs > 1000 && t.endMs > 1000)
        val tag = AudioFileIO.read(g).tag
        assertEquals("Trim Test Title", tag.getFirst(FieldKey.TITLE))
        assertEquals("Trim Test Artist", tag.getFirst(FieldKey.ARTIST))
        g.delete()
    }

    /**
     * Fixing while the check runs: a measurement that finishes after the song was renamed doesn't
     * put the old file back in its row; the song is measured again under its new name.
     */
    @Test
    fun measurementDoesNotUndoAFixMadeMeanwhile() = runTest {
        val db = Room.inMemoryDatabaseBuilder(appContext, SongDatabase::class.java).build()
        val f = file("race.mp3", paddedMp3())
        val renamed = File(appContext.cacheDir, "trimtest-race-renamed.mp3")
        try {
            val repo = testRepository(appContext, db)
            db.songs().insert(listOf(Song(uri = Uri.fromFile(f).toString(), fileName = f.name, folder = "", size = f.length(), modified = f.lastModified(), status = SongStatus.OK)))
            val song = db.songs().all().single()
            // The fix lands (file renamed, row updated) while the measurement of the old row is still running.
            f.renameTo(renamed)
            db.songs().update(song.copy(uri = Uri.fromFile(renamed).toString(), fileName = renamed.name, status = SongStatus.FIXED))
            val saved = db.songs().saveMeasurement(
                song.id, song.uri, -9.0, -9.0, 0.9, true, 3000, 4000, true, null, null, null, "NONE", false,
            )
            assertEquals(0, saved)
            val row = db.songs().get(song.id)!!
            assertEquals(renamed.name, row.fileName)
            assertEquals(SongStatus.FIXED, row.status)
            assertEquals(false, row.silenceChecked)
            // Measured again, under its new name.
            repo.measure(song.id)
            val measured = db.songs().get(song.id)!!
            assertEquals(renamed.name, measured.fileName)
            assertEquals(SongStatus.FIXED, measured.status)
            assertTrue("lead ${measured.silenceStartMs}", measured.silenceStartMs!! >= 1900)
            assertTrue(measured.loudnessChecked && measured.silenceChecked && measured.gainVerdict != null)
        } finally {
            db.close()
            f.delete()
            renamed.delete()
        }
    }

    /** The app's path: the check measures it, suggests the trim, the trim is saved, nothing is left to suggest. */
    @Test
    fun repositoryMeasuresSuggestsAndTrims() = runTest {
        val db = Room.inMemoryDatabaseBuilder(appContext, SongDatabase::class.java).build()
        val f = file("repo.mp3", paddedMp3())
        try {
            val repo = testRepository(appContext, db)
            db.songs().insert(listOf(Song(
                uri = Uri.fromFile(f).toString(), fileName = f.name, folder = "", size = f.length(), modified = f.lastModified(),
                status = SongStatus.OK, durationMs = 20_000,
            )))
            while (repo.measureNext()) Unit
            val measured = db.songs().all().single()
            assertTrue("lead ${measured.silenceStartMs}", measured.silenceStartMs!! >= 1900)
            assertTrue("tail ${measured.silenceEndMs}", measured.silenceEndMs!! >= 2900)
            assertEquals(listOf(measured.id), SongRepository.needTrimming(db.songs().all()).map { it.id })

            val done = repo.trimSilence(measured)
            assertEquals(SongRepository.TrimOutcome.TRIMMED, done.trim)
            val after = db.songs().all().single()
            assertEquals(SongStatus.OK, after.status)
            assertEquals(measured.loudnessLufs, after.loudnessLufs)
            assertEquals(20_000 - done.trimmed!!.startMs - done.trimmed!!.endMs, after.durationMs)
            assertTrue(SongRepository.needTrimming(db.songs().all()).isEmpty())
            // What's saved agrees with the file.
            val real = analyzer.silence(Uri.fromFile(f))
            assertEquals(real.leadMs.toDouble(), after.silenceStartMs!!.toDouble(), 150.0)
            assertEquals(real.tailMs.toDouble(), after.silenceEndMs!!.toDouble(), 150.0)
        } finally {
            db.close()
            f.delete()
        }
    }

    private fun tone(leadS: Double, soundS: Double, tailS: Double, rate: Int = 44_100): ShortArray {
        val lead = (leadS * rate).toInt()
        val sound = (soundS * rate).toInt()
        return ShortArray(lead + sound + (tailS * rate).toInt()) { i ->
            if (i in lead until lead + sound) (0.3 * 32767 * sin(2 * PI * 440 * i / rate) * sin(PI * (i - lead) / sound)).toInt().toShort() else 0
        }
    }

    @Test
    fun wav() = runTest {
        val s = tone(2.5, 4.0, 3.0)
        val out = ByteArrayOutputStream()
        fun le32(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun le16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        out.write("RIFF".toByteArray()); le32(36 + s.size * 2); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1); le32(44_100); le32(88_200); le16(2); le16(16)
        out.write("data".toByteArray()); le32(s.size * 2)
        for (v in s) le16(v.toInt())
        trimAndCheck(file("song.wav", out.toByteArray()), "wav", 2000, 2500)
    }

    /** Verbatim FLAC frames (block size 4096, 44.1 kHz, mono, 16 bit). */
    @Test
    fun flac() = runTest {
        val s = tone(3.0, 4.0, 2.0)
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray())
        out.write(byteArrayOf(0x80.toByte(), 0, 0, 34))
        val si = ByteArray(34)
        si[0] = 0x10; si[2] = 0x10
        si[10] = 0x0A; si[11] = 0xC4.toByte(); si[12] = 0x40; si[13] = 0xF0.toByte()
        si[14] = (s.size shr 24).toByte(); si[15] = (s.size shr 16).toByte(); si[16] = (s.size shr 8).toByte(); si[17] = s.size.toByte()
        out.write(si)
        var n = 0
        var frame = 0
        while (n < s.size) {
            val bs = minOf(4096, s.size - n)
            val f = ByteArrayOutputStream()
            f.write(0xFF); f.write(0xF8); f.write(if (bs == 4096) 0xC9 else 0x79); f.write(0x08); f.write(frame)
            if (bs != 4096) { f.write((bs - 1) shr 8); f.write((bs - 1) and 0xFF) }
            val h = f.toByteArray()
            f.write(SilenceTrimmer.Flac.crc8(h, 0, h.size))
            f.write(0x02)
            for (k in 0 until bs) { val v = s[n + k].toInt(); f.write(v shr 8); f.write(v and 0xFF) }
            val body = f.toByteArray()
            val crc = SilenceTrimmer.Flac.crc16(body, 0, body.size)
            out.write(body); out.write(crc shr 8); out.write(crc and 0xFF)
            n += bs; frame++
        }
        trimAndCheck(file("song.flac", out.toByteArray()), "flac", 2500, 1500)
    }
}
