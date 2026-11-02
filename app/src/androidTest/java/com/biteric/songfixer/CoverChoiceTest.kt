package com.biteric.songfixer

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.biteric.songfixer.data.Candidate
import com.biteric.songfixer.data.MatchSource
import com.biteric.songfixer.data.Song
import com.biteric.songfixer.data.SongDatabase
import com.biteric.songfixer.data.SongStatus
import com.biteric.songfixer.files.NewNames
import com.biteric.songfixer.files.SongWriter
import com.biteric.songfixer.online.CoverImage
import com.biteric.songfixer.ui.embeddedCover
import kotlinx.coroutines.test.runTest
import org.jaudiotagger.audio.AudioFileIO
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Choosing between the cover in the file and the album's cover from the Cover Art Archive (a fake
 * one on this device): the real repository and tag writer on copies of test.mp3.
 */
@RunWith(AndroidJUnit4::class)
class CoverChoiceTest {
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: SongDatabase
    private val files = mutableListOf<File>()

    private val red = jpeg(Color.RED)
    private val blue = jpeg(Color.BLUE)

    /** The album "rg-1" has the blue cover; any other album has none. */
    private val coverArt = TestServer { path ->
        if (path == "/release-group/rg-1/front-500") Triple(200, "image/jpeg", blue) else Triple(404, "text/plain", ByteArray(0))
    }
    private lateinit var repo: SongRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(appContext, SongDatabase::class.java).build()
        repo = testRepository(appContext, db, coverArtUrl = coverArt.baseUrl)
    }

    @After
    fun tearDown() {
        db.close()
        coverArt.close()
        files.forEach { it.delete() }
    }

    private fun jpeg(color: Int): ByteArray {
        val bmp = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    /** A copy of test.mp3 with [cover] in it (none when null), saved as a checked song whose match is album "rg-1". */
    private suspend fun song(name: String, cover: ByteArray?): Pair<Song, File> {
        val f = File(appContext.cacheDir, "cc-$name.mp3")
        testContext.assets.open("test.mp3").use { input -> f.outputStream().use { input.copyTo(it) } }
        files += f
        if (cover != null) SongWriter(appContext).writeTags(Uri.fromFile(f), "mp3", NewNames("Hello", "Adele", "25", ""), cover = CoverImage(cover, "image/jpeg"))
        val uri = Uri.fromFile(f).toString()
        db.songs().insert(listOf(Song(
            uri = uri, fileName = f.name, folder = "", size = f.length(), modified = f.lastModified(),
            title = "Hello", artist = "Adele", album = "25", status = SongStatus.OK, hasCover = cover != null,
            candidates = listOf(Candidate("Hello", "Adele", album = "25", source = MatchSource.NAME, score = 1.0, releaseGroupId = "rg-1")),
        )))
        return db.songs().all().single { it.uri == uri } to f
    }

    private fun pictures(f: File) = AudioFileIO.read(f).tag.artworkList

    /** The sheet with "Add cover art" ticked over an old cover: the new one takes its place, and only one stays. */
    @Test
    fun tickedReplacesTheCoverInTheFile() = runTest {
        val (s, f) = song("ticked", red)
        val done = repo.apply(s, NewNames("", "", "", ""), writeTags = false, rename = false, addLyrics = false, addCover = true, releaseGroupId = "rg-1", replaceCover = true)
        assertEquals(SongRepository.LyricsOutcome.ADDED, done.cover)
        assertEquals(1, pictures(f).size)
        assertArrayEquals(blue, embeddedCover(appContext, Uri.fromFile(f).toString()))
        assertEquals(true, db.songs().get(s.id)!!.hasCover)
        assertEquals("Hello", AudioFileIO.read(f).tag.getFirst(org.jaudiotagger.tag.FieldKey.TITLE))
    }

    /** Without asking to replace (the bulk "Fix N sure ones"), an old cover stays and nothing is downloaded. */
    @Test
    fun notAskedToReplaceKeepsTheOldCover() = runTest {
        val (s, f) = song("bulk", red)
        val done = repo.apply(s, NewNames("", "", "", ""), writeTags = false, rename = false, addLyrics = false, addCover = true, releaseGroupId = "rg-1")
        assertEquals(SongRepository.LyricsOutcome.ALREADY_THERE, done.cover)
        assertArrayEquals(red, embeddedCover(appContext, Uri.fromFile(f).toString()))
        assertTrue("no download: ${coverArt.paths}", coverArt.paths.isEmpty())
    }

    /** "Replace cover only" uses the match's album. */
    @Test
    fun replaceCoverOnlyUsesTheMatchsAlbum() = runTest {
        val (s, f) = song("only", red)
        assertEquals(SongRepository.LyricsOutcome.ADDED, repo.addCover(s, replace = true).cover)
        assertArrayEquals(blue, embeddedCover(appContext, Uri.fromFile(f).toString()))
        assertEquals(listOf("/release-group/rg-1/front-500"), coverArt.paths)
    }

    /** No new cover online for the album: the old one stays. */
    @Test
    fun noNewCoverOnlineKeepsTheOldOne() = runTest {
        val (s, f) = song("missing", red)
        val done = repo.apply(s, NewNames("", "", "", ""), writeTags = false, rename = false, addLyrics = false, addCover = true, releaseGroupId = "rg-404", replaceCover = true)
        assertEquals(SongRepository.LyricsOutcome.NOT_ONLINE, done.cover)
        assertArrayEquals(red, embeddedCover(appContext, Uri.fromFile(f).toString()))
        assertEquals(true, db.songs().get(s.id)!!.hasCover)
    }

    /** A file without a cover gets one either way. */
    @Test
    fun aFileWithoutACoverGetsOne() = runTest {
        val (s, f) = song("none", null)
        assertNull(embeddedCover(appContext, Uri.fromFile(f).toString()))
        val done = repo.apply(s, NewNames("", "", "", ""), writeTags = false, rename = false, addLyrics = false, addCover = true, releaseGroupId = "rg-1")
        assertEquals(SongRepository.LyricsOutcome.ADDED, done.cover)
        assertArrayEquals(blue, embeddedCover(appContext, Uri.fromFile(f).toString()))
    }

    /** Unticked (the file's cover chosen): nothing changes in the file. */
    @Test
    fun untickedChangesNothing() = runTest {
        val (s, f) = song("unticked", red)
        val before = f.readBytes()
        val done = repo.apply(s, NewNames("", "", "", ""), writeTags = false, rename = false, addLyrics = false, addCover = false, releaseGroupId = "rg-1", replaceCover = false)
        assertEquals(SongRepository.LyricsOutcome.NOT_ASKED, done.cover)
        assertArrayEquals(before, f.readBytes())
        assertTrue(coverArt.paths.isEmpty())
    }
}
