package com.biteric.songfixer

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.biteric.songfixer.data.Settings
import com.biteric.songfixer.data.Song
import com.biteric.songfixer.data.SongDatabase
import com.biteric.songfixer.files.MusicFolder
import com.biteric.songfixer.files.ReplayGainTags
import com.biteric.songfixer.files.SongWriter
import com.biteric.songfixer.fingerprint.AudioFingerprinter
import com.biteric.songfixer.loudness.GainVerdict
import com.biteric.songfixer.loudness.LoudnessAnalyzer
import com.biteric.songfixer.loudness.ReplayGain
import com.biteric.songfixer.match.CoverFinder
import com.biteric.songfixer.match.Identifier
import com.biteric.songfixer.match.LyricsFinder
import com.biteric.songfixer.online.AcoustIdApi
import com.biteric.songfixer.online.CoverArtApi
import com.biteric.songfixer.online.LrclibApi
import com.biteric.songfixer.online.MusicBrainzApi
import kotlinx.coroutines.test.runTest
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * The whole path for volume tags already in a file, with the real repository, tag writer and
 * loudness meter on copies of test.mp3 (in the app's cache; no folder, no network): the check's
 * loudness phase reads them and judges them, then "Normalize" replaces or keeps them.
 */
@RunWith(AndroidJUnit4::class)
class VolumeTagsRepositoryTest {
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: SongDatabase
    private lateinit var repo: SongRepository
    private val files = mutableListOf<File>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(appContext, SongDatabase::class.java).build()
        // Nothing here goes online; the services point nowhere so a slip would fail, not reach out.
        val nowhere = "http://127.0.0.1:9"
        val ua = "SongFixerTest"
        val lrclib = LrclibApi(nowhere, ua)
        val coverArt = CoverArtApi(nowhere, ua)
        val musicBrainz = MusicBrainzApi(nowhere, ua)
        repo = SongRepository(
            db.songs(), MusicFolder(appContext), AudioFingerprinter(appContext),
            Identifier(AcoustIdApi(nowhere, ua), musicBrainz, lrclib), SongWriter(appContext), Settings(appContext),
            LyricsFinder(lrclib), lrclib, CoverFinder(musicBrainz, coverArt), coverArt, LoudnessAnalyzer(appContext),
        )
    }

    @After
    fun tearDown() {
        db.close()
        files.forEach { it.delete() }
    }

    /** A copy of test.mp3 with these TXXX frames (and no others). */
    private fun songFile(name: String, vararg frames: Pair<String, String>): File {
        val f = File(appContext.cacheDir, "vt-$name.mp3")
        testContext.assets.open("test.mp3").use { input -> f.outputStream().use { input.copyTo(it) } }
        files += f
        val audio = AudioFileIO.read(f)
        val tag = audio.tagOrCreateAndSetDefault as AbstractID3v2Tag
        tag.setFrame("TXXX", frames.map { (d, v) -> tag.createFrame("TXXX").apply { body = FrameBodyTXXX(0, d, v) } })
        audio.commit()
        return f
    }

    private suspend fun insert(f: File, song: (Song) -> Song = { it }): Song {
        db.songs().insert(listOf(song(Song(uri = Uri.fromFile(f).toString(), fileName = f.name, folder = "", size = f.length(), modified = f.lastModified()))))
        return db.songs().all().single { it.uri == Uri.fromFile(f).toString() }
    }

    private suspend fun reload(song: Song) = db.songs().get(song.id)!!

    private fun txxx(f: File): Map<String, String> =
        (AudioFileIO.read(f).tag as AbstractID3v2Tag).getFields("TXXX")
            .map { (it as AbstractID3v2Frame).body as FrameBodyTXXX }
            .associate { it.description to it.textWithoutTrailingNulls }

    private suspend fun measureAll() {
        while (repo.measureNext()) Unit
    }

    @Test
    fun replayGain1TagsAreFlaggedThenReplaced() = runTest {
        val f = songFile(
            "rg1",
            "MusicBrainz Album Id" to "abc",
            "replaygain_track_gain" to "-3.10 dB",
            "replaygain_track_peak" to "0.950000",
            "REPLAYGAIN_REFERENCE_LOUDNESS" to "89.0 dB",
            "REPLAYGAIN_ALBUM_GAIN" to "-2.50 dB",
        )
        val song = insert(f)
        measureAll()
        val read = reload(song)
        assertTrue("measured", read.loudnessChecked && read.loudnessLufs != null)
        assertEquals("-3.10 dB", read.existingTrackGain)
        assertEquals("0.950000", read.existingTrackPeak)
        assertEquals("89.0 dB", read.existingReference)
        assertEquals(GainVerdict.RG1, read.gainVerdict)
        assertTrue(read.volumeTagsNotStandard)
        assertEquals(listOf(read.id), SongRepository.needNormalizing(db.songs().all()).map { it.id })

        assertEquals(SongRepository.LyricsOutcome.ADDED, repo.normalize(read).volume)
        val fixed = reload(song)
        assertEquals(GainVerdict.STANDARD, fixed.gainVerdict)
        assertEquals(ReplayGain.gainText(read.trackGainDb!!), fixed.existingTrackGain)
        assertEquals("-18.00 LUFS", fixed.existingReference)
        assertTrue(SongRepository.needNormalizing(db.songs().all()).isEmpty())
        val tags = txxx(f)
        assertEquals(ReplayGain.gainText(read.trackGainDb!!), tags["REPLAYGAIN_TRACK_GAIN"])
        assertEquals("-18.00 LUFS", tags["REPLAYGAIN_REFERENCE_LOUDNESS"])
        assertEquals("-2.50 dB", tags["REPLAYGAIN_ALBUM_GAIN"])
        assertEquals("abc", tags["MusicBrainz Album Id"])
        assertFalse("old lower-case frames gone", tags.keys.any { it == "replaygain_track_gain" })
    }

    @Test
    fun standardTagsAreLeftAloneByteForByte() = runTest {
        val loud = LoudnessAnalyzer(appContext).measure(Uri.fromFile(songFile("probe")))
        val f = songFile(
            "standard",
            "REPLAYGAIN_TRACK_GAIN" to ReplayGain.gainText(loud.trackGainDb + 0.5),
            "REPLAYGAIN_TRACK_PEAK" to ReplayGain.peakText(loud.truePeak),
            "REPLAYGAIN_REFERENCE_LOUDNESS" to "-18 LUFS",
        )
        val song = insert(f)
        measureAll()
        val read = reload(song)
        assertEquals(GainVerdict.STANDARD, read.gainVerdict)
        assertTrue(read.volumeTagsStandard)
        assertTrue(SongRepository.needNormalizing(db.songs().all()).none { it.id == read.id })

        val before = f.readBytes()
        assertEquals(SongRepository.LyricsOutcome.ALREADY_THERE, repo.normalize(read).volume)
        assertArrayEquals("file unchanged", before, f.readBytes())
        assertEquals(GainVerdict.STANDARD, reload(song).gainVerdict)
    }

    @Test
    fun reMeasureStillReplacesStandardTags() = runTest {
        val loud = LoudnessAnalyzer(appContext).measure(Uri.fromFile(songFile("probe")))
        val f = songFile(
            "remeasure",
            "REPLAYGAIN_TRACK_GAIN" to ReplayGain.gainText(loud.trackGainDb + 0.5),
            "REPLAYGAIN_TRACK_PEAK" to "0.500000",
            "REPLAYGAIN_REFERENCE_LOUDNESS" to "-18.00 LUFS",
        )
        val song = insert(f)
        measureAll()
        assertEquals(GainVerdict.STANDARD, reload(song).gainVerdict)
        assertEquals(SongRepository.LyricsOutcome.ADDED, repo.remeasure(reload(song)).volume)
        val tags = txxx(f)
        assertEquals(ReplayGain.gainText(loud.trackGainDb), tags["REPLAYGAIN_TRACK_GAIN"])
        assertEquals(ReplayGain.peakText(loud.truePeak), tags["REPLAYGAIN_TRACK_PEAK"])
    }

    @Test
    fun r128OnlyTagIsReplacedAndRemoved() = runTest {
        val f = songFile("r128", "MusicBrainz Album Id" to "abc", "R128_TRACK_GAIN" to "-1536")
        val song = insert(f)
        measureAll()
        val read = reload(song)
        assertEquals(GainVerdict.R128_ONLY, read.gainVerdict)
        assertNull(read.existingTrackGain)
        assertEquals(SongRepository.LyricsOutcome.ADDED, repo.normalize(read).volume)
        assertEquals(setOf("MusicBrainz Album Id", "REPLAYGAIN_TRACK_GAIN", "REPLAYGAIN_TRACK_PEAK", "REPLAYGAIN_REFERENCE_LOUDNESS"), txxx(f).keys)
        assertEquals(GainVerdict.STANDARD, reload(song).gainVerdict)
    }

    @Test
    fun otherReferenceAndUnreadableValuesAreReplaced() = runTest {
        val ref23 = songFile("ref23", "REPLAYGAIN_TRACK_GAIN" to "-13.00 dB", "REPLAYGAIN_TRACK_PEAK" to "0.9", "REPLAYGAIN_REFERENCE_LOUDNESS" to "-23 LUFS")
        val junk = songFile("junk", "REPLAYGAIN_TRACK_GAIN" to "loud", "REPLAYGAIN_TRACK_PEAK" to "0.9", "REPLAYGAIN_REFERENCE_LOUDNESS" to "-18 LUFS")
        val a = insert(ref23)
        val b = insert(junk)
        measureAll()
        assertEquals(GainVerdict.OTHER_REFERENCE, reload(a).gainVerdict)
        assertEquals(GainVerdict.UNREADABLE, reload(b).gainVerdict)
        for (s in listOf(a, b)) assertEquals(SongRepository.LyricsOutcome.ADDED, repo.normalize(reload(s)).volume)
        for (f in listOf(ref23, junk)) assertEquals("-18.00 LUFS", txxx(f)["REPLAYGAIN_REFERENCE_LOUDNESS"])
    }

    /** Songs measured before tags were read (older database, or a file changed since) only get their tags read. */
    @Test
    fun measuredSongOnlyGetsItsTagsRead() = runTest {
        val f = songFile("measured", "REPLAYGAIN_TRACK_GAIN" to "-8.42 dB", "REPLAYGAIN_TRACK_PEAK" to "0.9", "REPLAYGAIN_REFERENCE_LOUDNESS" to "-18.00 LUFS")
        // A stored measurement far from the real one shows whether the audio was measured again.
        val song = insert(f) { it.copy(loudnessLufs = -30.0, trackGainDb = 12.0, truePeak = 0.5, loudnessChecked = true, replayGainInFile = true) }
        assertEquals(1, repo.unmeasuredCount())
        measureAll()
        val read = reload(song)
        assertEquals(-30.0, read.loudnessLufs!!, 0.0)
        // −8.42 dB against the stored 12 dB: off by more than 1 dB.
        assertEquals(GainVerdict.GAIN_OFF, read.gainVerdict)
        assertEquals(0, repo.unmeasuredCount())
    }

    @Test
    fun noTagsMeansNoneAndNormalizeAddsThem() = runTest {
        val f = songFile("none")
        val song = insert(f)
        measureAll()
        val read = reload(song)
        assertEquals(GainVerdict.NONE, read.gainVerdict)
        assertFalse(read.volumeTagsStandard || read.volumeTagsNotStandard)
        assertEquals(false, read.replayGainInFile)
        assertEquals(SongRepository.LyricsOutcome.ADDED, repo.normalize(read).volume)
        val gain = ReplayGainTags.trackGain(AudioFileIO.read(f).tag)
        assertTrue("gain $gain", abs((gain!!.removeSuffix(" dB").toDouble()) - read.trackGainDb!!) < 0.01)
    }
}
