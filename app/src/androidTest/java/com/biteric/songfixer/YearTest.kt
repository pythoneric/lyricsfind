package com.biteric.songfixer

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.biteric.songfixer.data.Candidate
import com.biteric.songfixer.data.MatchSource
import com.biteric.songfixer.data.Song
import com.biteric.songfixer.data.SongDatabase
import com.biteric.songfixer.data.SongStatus
import com.biteric.songfixer.files.MusicFolder
import com.biteric.songfixer.files.NewNames
import com.biteric.songfixer.files.SongWriter
import kotlinx.coroutines.test.runTest
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The year: written into real files, read back as Android (and so players) see it, and kept in the list. */
@RunWith(AndroidJUnit4::class)
class YearTest {
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: SongDatabase
    private lateinit var repo: SongRepository
    private val files = mutableListOf<File>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(appContext, SongDatabase::class.java).build()
        repo = testRepository(appContext, db)
    }

    @After
    fun tearDown() {
        db.close()
        files.forEach { it.delete() }
    }

    /** A copy of test.mp3 titled Hello / Adele / 25 / Pop, with [year] (none when null). */
    private suspend fun file(name: String, year: Int?): File {
        val f = File(appContext.cacheDir, "yr-$name.mp3")
        testContext.assets.open("test.mp3").use { input -> f.outputStream().use { input.copyTo(it) } }
        files += f
        SongWriter(appContext).writeTags(Uri.fromFile(f), "mp3", NewNames("Hello", "Adele", "25", "Pop", year?.toString().orEmpty()))
        // test.mp3 may carry a year of its own; start from exactly what was asked.
        if (year == null) AudioFileIO.read(f).let { a -> a.tag.deleteField(FieldKey.YEAR); a.commit() }
        return f
    }

    /** A song checked before years were kept: named right, its match knowing [matchYear]. */
    private suspend fun oldSong(f: File, status: SongStatus, matchYear: Int?): Song {
        val uri = Uri.fromFile(f).toString()
        db.songs().insert(listOf(Song(
            uri = uri, fileName = f.name, folder = "", size = f.length(), modified = f.lastModified(),
            title = "Hello", artist = "Adele", album = "25", genre = "Pop", status = status,
            candidates = listOf(Candidate("Hello", "Adele", "25", "Pop", year = matchYear, source = MatchSource.NAME, score = 1.0, sure = true)),
        )))
        return db.songs().all().single { it.uri == uri }
    }

    private suspend fun readYear(f: File) = MusicFolder(appContext).readTags(Uri.fromFile(f)).year

    @Test
    fun aWrittenYearIsWhatAndroidReads() = runTest {
        val f = file("written", null)
        assertNull(readYear(f))
        SongWriter(appContext).writeTags(Uri.fromFile(f), "mp3", NewNames("", "", "", "", "2015"))
        assertEquals(2015, readYear(f))
        // Blank leaves the file's year alone.
        SongWriter(appContext).writeTags(Uri.fromFile(f), "mp3", NewNames("", "", "", "", ""))
        assertEquals(2015, readYear(f))
        assertEquals("Hello", AudioFileIO.read(f).tag.getFirst(FieldKey.TITLE))
    }

    /** A v2.4 year (TDRC) as other taggers write it: read from the tag, whatever Android's reader makes of it. */
    @Test
    fun readsTheId3v24Year() = runTest {
        val f = file("v24", null)
        AudioFileIO.read(f).let { a ->
            a.setTag(org.jaudiotagger.tag.id3.ID3v24Tag().apply { setField(FieldKey.TITLE, "Stop It"); setField(FieldKey.YEAR, "2021") })
            a.commit()
        }
        assertTrue(AudioFileIO.read(f).tag is org.jaudiotagger.tag.id3.ID3v24Tag)
        val r = android.media.MediaMetadataRetriever()
        r.setDataSource(f.absolutePath)
        android.util.Log.i("YearTest", "Android's reader on a v2.4 year: YEAR=${r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_YEAR)}")
        r.release()
        assertEquals(2021, readYear(f))
    }

    /** Written by the app, an MP3's tag is ID3v2.3, and Android's own reader (players) sees the year; nothing else is lost. */
    @Test
    fun writtenMp3sAreId3v23AndAndroidSeesTheYear() = runTest {
        val f = file("v23", null)
        // Start from a v2.4 tag holding lyrics, a cover and volume tags.
        AudioFileIO.read(f).let { a ->
            a.setTag(org.jaudiotagger.tag.id3.ID3v24Tag().apply {
                setField(FieldKey.TITLE, "Dārin")
                setField(FieldKey.LYRICS, "[00:01.00]line one")
            })
            a.commit()
        }
        val cover = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888).let { bmp ->
            java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        }
        SongWriter(appContext).writeTags(
            Uri.fromFile(f), "mp3", NewNames("", "Phil Berg", "Dārin", "Techno", "2023"),
            cover = com.biteric.songfixer.online.CoverImage(cover, "image/jpeg"),
            replayGain = com.biteric.songfixer.loudness.Loudness(lufs = -9.58, truePeak = 0.988),
        )
        val tag = AudioFileIO.read(f).tag
        assertTrue("tag is ${tag.javaClass.simpleName}", tag is org.jaudiotagger.tag.id3.ID3v23Tag)
        assertEquals("Dārin", tag.getFirst(FieldKey.TITLE))
        assertEquals("[00:01.00]line one", tag.getFirst(FieldKey.LYRICS))
        assertEquals("-8.42 dB", com.biteric.songfixer.files.ReplayGainTags.trackGain(tag))
        assertTrue(tag.firstArtwork.binaryData.contentEquals(cover))
        val r = android.media.MediaMetadataRetriever()
        r.setDataSource(f.absolutePath)
        assertEquals("2023", r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_YEAR))
        assertEquals("Dārin", r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE))
        r.release()
        assertEquals(2023, readYear(f))
        assertEquals(10, AudioFileIO.read(f).audioHeader.trackLength)
    }

    /** Writing the names a file already has changes nothing: no rewrite, its v2.4 tag stays v2.4. */
    @Test
    fun nothingToChangeLeavesTheFileAlone() = runTest {
        val f = file("same", null)
        AudioFileIO.read(f).let { a ->
            a.setTag(org.jaudiotagger.tag.id3.ID3v24Tag().apply { setField(FieldKey.TITLE, "Hello"); setField(FieldKey.YEAR, "2015") })
            a.commit()
        }
        val before = f.readBytes()
        SongWriter(appContext).writeTags(Uri.fromFile(f), "mp3", NewNames("Hello", "", "", "", "2015"))
        org.junit.Assert.assertArrayEquals(before, f.readBytes())
    }

    @Test
    fun fixingWritesTheYearAndKeepsItInTheList() = runTest {
        val f = file("fix", 2021)
        val s = oldSong(f, SongStatus.SUGGESTED, 2015)
        repo.apply(s, NewNames("Hello", "Adele", "25", "Pop", "2015"), writeTags = true, rename = false, addLyrics = false)
        assertEquals(2015, readYear(f))
        val saved = db.songs().get(s.id)!!
        assertEquals(2015, saved.year)
        assertTrue(saved.yearRead)
    }

    @Test
    fun rightSongWithoutAYearBecomesSomethingToFix() = runTest {
        val noYear = oldSong(file("noyear", null), SongStatus.OK, 2015)
        val hasYear = oldSong(file("hasyear", 2011), SongStatus.OK, 2015)
        val neitherKnows = oldSong(file("neither", null), SongStatus.OK, null)
        repo.readYears()
        db.songs().get(noYear.id)!!.let {
            assertEquals(SongStatus.SUGGESTED, it.status)
            assertNull(it.year)
            assertTrue(it.yearRead)
        }
        // A year of its own (another release) is right as it is.
        db.songs().get(hasYear.id)!!.let {
            assertEquals(SongStatus.OK, it.status)
            assertEquals(2011, it.year)
        }
        assertEquals(SongStatus.OK, db.songs().get(neitherKnows.id)!!.status)
        // Read once.
        assertTrue(db.songs().yearNotRead().isEmpty())
    }

    @Test
    fun readingYearsNeverChangesFixedOrKeptSongs() = runTest {
        val fixed = oldSong(file("fixed", null), SongStatus.FIXED, 2015)
        val kept = oldSong(file("kept", null), SongStatus.KEPT, 2015)
        val unchecked = oldSong(file("new", null), SongStatus.NEW, 2015)
        repo.readYears()
        assertEquals(SongStatus.FIXED, db.songs().get(fixed.id)!!.status)
        assertEquals(SongStatus.KEPT, db.songs().get(kept.id)!!.status)
        // Not checked yet: its year comes with the check.
        assertEquals(false, db.songs().get(unchecked.id)!!.yearRead)
    }
}
