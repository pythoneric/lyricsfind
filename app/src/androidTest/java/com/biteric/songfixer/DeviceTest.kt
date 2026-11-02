package com.biteric.songfixer

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.biteric.songfixer.files.NewNames
import com.biteric.songfixer.files.SongWriter
import com.biteric.songfixer.fingerprint.AudioFingerprinter
import kotlinx.coroutines.test.runTest
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext

    private fun copyAsset(name: String): File {
        val f = File(appContext.cacheDir, "test-" + name)
        testContext.assets.open(name).use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    /**
     * Android's MP3 decoder isn't FFmpeg's, so the fingerprint can't be bit-identical to fpcalc's;
     * AcoustID matches fingerprints by bit errors, so almost all bits must agree.
     */
    @Test
    fun fingerprintMatchesFpcalc() = runTest {
        val expected = testContext.assets.open("test.mp3.fpcalc.out").bufferedReader().readLines()
            .first { it.startsWith("FINGERPRINT=") }.substringAfter('=').split(',').map { it.toLong().toInt() }
        val fp = AudioFingerprinter(appContext).fingerprint(Uri.fromFile(copyAsset("test.mp3")))
        assertEquals(10, fp.durationSec)
        // The best alignment (decoder delay can shift a frame) and its share of equal bits.
        val best = (-2..2).maxOf { shift ->
            var same = 0
            var total = 0
            for (i in expected.indices) {
                val j = i + shift
                if (j !in fp.raw.indices) continue
                same += 32 - Integer.bitCount(expected[i] xor fp.raw[j])
                total += 32
            }
            same.toDouble() / total
        }
        android.util.Log.i("DeviceTest", "fingerprint: ${fp.raw.size} items vs ${expected.size}, ${"%.1f".format(best * 100)}% bits equal")
        assertTrue("only ${best * 100}% of bits equal", best > 0.9)
        assertTrue(kotlin.math.abs(fp.raw.size - expected.size) <= 2)
    }

    @Test
    fun writesTagsIntoTheFile() = runTest {
        val f = copyAsset("test.mp3")
        SongWriter(appContext).writeTags(Uri.fromFile(f), "mp3", NewNames("Hello", "Adele", "25", "Pop", "2015"))
        val tag = AudioFileIO.read(f).tag
        assertEquals("Hello", tag.getFirst(FieldKey.TITLE))
        assertEquals("Adele", tag.getFirst(FieldKey.ARTIST))
        assertEquals("25", tag.getFirst(FieldKey.ALBUM))
        assertEquals("Pop", tag.getFirst(FieldKey.GENRE))
        // Still a playable song of the same length.
        assertEquals(10, AudioFileIO.read(f).audioHeader.trackLength)
        f.delete()
    }

    @Test
    fun addsLyricsButNeverReplacesThem() = runTest {
        val f = copyAsset("test.mp3")
        val writer = SongWriter(appContext)
        val lrc = "[00:01.00] First line\n[00:04.50] Second line"
        assertEquals(SongWriter.EmbedResult.ADDED, writer.writeTags(Uri.fromFile(f), "mp3", NewNames("Hello", "Adele", "", ""), lrc).lyrics)
        assertEquals(lrc, AudioFileIO.read(f).tag.getFirst(FieldKey.LYRICS))
        // Lyrics only, names untouched.
        assertEquals(SongWriter.EmbedResult.KEPT_EXISTING, writer.writeTags(Uri.fromFile(f), "mp3", NewNames("", "", "", ""), "other").lyrics)
        assertEquals(lrc, AudioFileIO.read(f).tag.getFirst(FieldKey.LYRICS))
        assertEquals("Hello", AudioFileIO.read(f).tag.getFirst(FieldKey.TITLE))
        // Lyrics the user picked or pasted do replace them.
        assertEquals(SongWriter.EmbedResult.ADDED, writer.writeTags(Uri.fromFile(f), "mp3", NewNames("", "", "", ""), "pasted", replaceLyrics = true).lyrics)
        assertEquals("pasted", AudioFileIO.read(f).tag.getFirst(FieldKey.LYRICS))
        f.delete()
    }

    /** A plain 300x300 JPEG, standing in for a downloaded cover. */
    private fun testCover(color: Int): com.biteric.songfixer.online.CoverImage {
        val bmp = android.graphics.Bitmap.createBitmap(300, 300, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        return com.biteric.songfixer.online.CoverImage(out.toByteArray(), "image/jpeg")
    }

    @Test
    fun addsACoverButNeverReplacesOne() = runTest {
        val f = copyAsset("test.mp3")
        val writer = SongWriter(appContext)
        val red = testCover(android.graphics.Color.RED)
        assertEquals(SongWriter.EmbedResult.ADDED, writer.writeTags(Uri.fromFile(f), "mp3", NewNames("Hello", "Adele", "", ""), cover = red).cover)
        // Android (and so BitEric and other players) sees it.
        val r = android.media.MediaMetadataRetriever()
        r.setDataSource(f.absolutePath)
        val picture = r.embeddedPicture
        r.release()
        assertTrue(picture != null && picture.contentEquals(red.bytes))
        // A second cover doesn't replace the first.
        assertEquals(SongWriter.EmbedResult.KEPT_EXISTING, writer.writeTags(Uri.fromFile(f), "mp3", NewNames("", "", "", ""), cover = testCover(android.graphics.Color.BLUE)).cover)
        assertTrue(AudioFileIO.read(f).tag.firstArtwork.binaryData.contentEquals(red.bytes))
        assertEquals("Hello", AudioFileIO.read(f).tag.getFirst(FieldKey.TITLE))
        assertEquals(10, AudioFileIO.read(f).audioHeader.trackLength)
        f.delete()
    }

    @Test
    fun writesAndReadsBackReplayGainTags() = runTest {
        val f = copyAsset("test.mp3")
        val writer = SongWriter(appContext)
        // Another program's TXXX frame must survive ours.
        AudioFileIO.read(f).let { audio ->
            val tag = audio.tagOrCreateAndSetDefault as org.jaudiotagger.tag.id3.AbstractID3v2Tag
            tag.setFrame("TXXX", listOf(tag.createFrame("TXXX").apply {
                body = org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX(0, "MusicBrainz Album Id", "abc")
            }))
            audio.commit()
        }
        val loud = com.biteric.songfixer.loudness.Loudness(lufs = -9.58, truePeak = 0.988)
        assertEquals(SongWriter.EmbedResult.ADDED, writer.writeTags(Uri.fromFile(f), "mp3", NewNames("", "", "", ""), replayGain = loud).replayGain)
        val tag = AudioFileIO.read(f).tag
        assertEquals("-8.42 dB", com.biteric.songfixer.files.ReplayGainTags.trackGain(tag))
        assertEquals("0.988000", com.biteric.songfixer.files.ReplayGainTags.trackPeak(tag))
        assertEquals("-18.00 LUFS", com.biteric.songfixer.files.ReplayGainTags.reference(tag))
        val descriptions = (tag as org.jaudiotagger.tag.id3.AbstractID3v2Tag).getFields("TXXX")
            .map { ((it as org.jaudiotagger.tag.id3.AbstractID3v2Frame).body as org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX).description }
        assertTrue("other TXXX kept: $descriptions", "MusicBrainz Album Id" in descriptions)
        assertEquals(4, descriptions.size)

        // Existing tags stay unless re-measured.
        val other = com.biteric.songfixer.loudness.Loudness(lufs = -20.0, truePeak = 0.5)
        assertEquals(SongWriter.EmbedResult.KEPT_EXISTING, writer.writeTags(Uri.fromFile(f), "mp3", NewNames("", "", "", ""), replayGain = other).replayGain)
        assertEquals("-8.42 dB", com.biteric.songfixer.files.ReplayGainTags.trackGain(AudioFileIO.read(f).tag))
        assertEquals(SongWriter.EmbedResult.ADDED, writer.writeTags(Uri.fromFile(f), "mp3", NewNames("", "", "", ""), replayGain = other, replaceReplayGain = true).replayGain)
        val replaced = AudioFileIO.read(f).tag
        assertEquals("2.00 dB", com.biteric.songfixer.files.ReplayGainTags.trackGain(replaced))
        assertEquals(4, (replaced as org.jaudiotagger.tag.id3.AbstractID3v2Tag).getFields("TXXX").size)
        assertEquals(10, AudioFileIO.read(f).audioHeader.trackLength)
        f.delete()
    }

    /** Decodes the whole of test.mp3 with Android's codecs and measures it. */
    @Test
    fun measuresLoudnessOfARealFile() = runTest {
        val f = copyAsset("test.mp3")
        val l = com.biteric.songfixer.loudness.LoudnessAnalyzer(appContext).measure(Uri.fromFile(f))
        android.util.Log.i("DeviceTest", "test.mp3: ${l.lufs} LUFS, true peak ${l.truePeak}, gain ${l.trackGainDb} dB")
        assertTrue("loudness ${l.lufs}", l.lufs in -40.0..0.0)
        assertTrue("peak ${l.truePeak}", l.truePeak in 0.01..2.0)
        f.delete()
    }

    @Test
    fun blankFieldsKeepWhatTheFileHas() = runTest {
        val f = copyAsset("test.mp3")
        val writer = SongWriter(appContext)
        writer.writeTags(Uri.fromFile(f), "mp3", NewNames("Hello", "Adele", "25", "Pop"))
        writer.writeTags(Uri.fromFile(f), "mp3", NewNames("Hello", "Adele", "", ""))
        assertEquals("25", AudioFileIO.read(f).tag.getFirst(FieldKey.ALBUM))
        f.delete()
    }
}
