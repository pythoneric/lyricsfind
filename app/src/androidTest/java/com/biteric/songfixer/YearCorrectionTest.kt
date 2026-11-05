package com.biteric.songfixer

import android.content.ContentValues
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.biteric.songfixer.data.Candidate
import com.biteric.songfixer.data.MatchSource
import com.biteric.songfixer.data.SettingsState
import com.biteric.songfixer.data.Song
import com.biteric.songfixer.data.SongDatabase
import com.biteric.songfixer.data.SongStatus
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.ID3v23Tag
import org.jaudiotagger.tag.id3.ID3v24Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.jaudiotagger.tag.images.AndroidArtwork
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.coroutines.resume

/**
 * A wrong year corrected on a song in shared storage, as Android's music library (what players
 * read) sees it: year 2021 in the file, 2017 by its match; only the year changes.
 */
@RunWith(AndroidJUnit4::class)
class YearCorrectionTest {
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = appContext.contentResolver
    private lateinit var db: SongDatabase
    private lateinit var repo: SongRepository
    private var media: Uri? = null
    private val displayName = "SongFixer year test ${System.currentTimeMillis()}.mp3"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(appContext, SongDatabase::class.java).build()
        repo = testRepository(appContext, db)
    }

    @After
    fun tearDown() {
        db.close()
        media?.let { resolver.delete(it, null, null) }
    }

    private fun mediaColumn(uri: Uri, column: String): String? =
        resolver.query(uri, arrayOf(column), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }

    /** Has Android's media scanner read the file again (as the app's notifyMediaStore does for folder songs). */
    private suspend fun rescan(uri: Uri) {
        val path = mediaColumn(uri, MediaStore.Audio.Media.DATA)!!
        suspendCancellableCoroutine { cont -> MediaScannerConnection.scanFile(appContext, arrayOf(path), null) { _, _ -> cont.resume(Unit) } }
    }

    private fun txxx(tag: AbstractID3v2Tag): Map<String, String> =
        tag.getFields("TXXX").map { (it as AbstractID3v2Frame).body as FrameBodyTXXX }.associate { it.description to it.textWithoutTrailingNulls }

    @Test
    fun aWrongYearIsCorrectedAndNothingElseChanges() = runTest {
        // test.mp3 with a full v2.4 tag, as another tagger leaves it: year 2021.
        val f = File(appContext.cacheDir, "yc.mp3")
        testContext.assets.open("test.mp3").use { input -> f.outputStream().use { input.copyTo(it) } }
        val cover = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888).let { bmp ->
            java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        }
        AudioFileIO.read(f).let { a ->
            val tag = ID3v24Tag()
            tag.setField(FieldKey.TITLE, "Stop It")
            tag.setField(FieldKey.ARTIST, "Fisher")
            tag.setField(FieldKey.ALBUM, "Oi Oi")
            tag.setField(FieldKey.GENRE, "Techno")
            tag.setField(FieldKey.YEAR, "2021")
            tag.setField(FieldKey.LYRICS, "[00:01.00]stop it")
            tag.setField(AndroidArtwork().apply { binaryData = cover; mimeType = "image/jpeg"; pictureType = 3 })
            tag.setFrame("TXXX", listOf(
                tag.createFrame("TXXX").apply { body = FrameBodyTXXX(0, "REPLAYGAIN_ALBUM_GAIN", "-2.50 dB") },
                tag.createFrame("TXXX").apply { body = FrameBodyTXXX(0, "SongFixer Test", "keep me") },
            ))
            a.setTag(tag)
            a.commit()
        }
        // Into shared storage through Android's music library (the app owns it, so it may write it).
        val uri = resolver.insert(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/SongFixerTest/")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            },
        )!!
        media = uri
        resolver.openOutputStream(uri, "wt")!!.use { out -> f.inputStream().use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        f.delete()
        rescan(uri)
        // Android's library can't see a v2.4 year: what players show today.
        android.util.Log.i("YearCorrection", "library year before: ${mediaColumn(uri, MediaStore.Audio.Media.YEAR)}")

        // Checked before years were kept: named right, its match (other case: FISHER) saying 2017.
        db.songs().insert(listOf(Song(
            uri = uri.toString(), fileName = displayName, folder = "", size = 1, modified = 1,
            title = "Stop It", artist = "Fisher", album = "Oi Oi", genre = "Techno", status = SongStatus.OK,
            candidates = listOf(Candidate("Stop It", "FISHER", "Oi Oi", "Techno", year = 2017, source = MatchSource.NAME, score = 1.0, sure = true)),
        )))
        repo.readYears()
        val flagged = db.songs().all().single()
        assertEquals(2021, flagged.year)
        assertEquals(SongStatus.SUGGESTED, flagged.status)
        assertTrue(SongRepository.isYearOnly(flagged))

        // "Correct year in N songs". (Later than the file was put there: the scanner skips files whose
        // modified time, in whole seconds, hasn't changed, and a fix comes long after in real use.)
        Thread.sleep(1_100)
        repo.apply(flagged, SongRepository.yearOnlyPlan(flagged, SettingsState(), extras = false))
        val fixed = db.songs().all().single()
        assertEquals(SongStatus.FIXED, fixed.status)
        assertEquals(2017, fixed.year)
        assertEquals(displayName, fixed.fileName)

        // The file: an ID3v2.3 tag with 2017, everything else as it was.
        val copy = File(appContext.cacheDir, "yc-after.mp3")
        resolver.openInputStream(uri)!!.use { input -> copy.outputStream().use { input.copyTo(it) } }
        val tag = AudioFileIO.read(copy).tag
        assertTrue("tag is ${tag.javaClass.simpleName}", tag is ID3v23Tag)
        assertEquals("2017", tag.getFirst(FieldKey.YEAR))
        assertEquals("Stop It", tag.getFirst(FieldKey.TITLE))
        assertEquals("Fisher", tag.getFirst(FieldKey.ARTIST))
        assertEquals("Oi Oi", tag.getFirst(FieldKey.ALBUM))
        assertEquals("Techno", tag.getFirst(FieldKey.GENRE))
        assertEquals("[00:01.00]stop it", tag.getFirst(FieldKey.LYRICS))
        assertEquals(1, tag.artworkList.size)
        assertArrayEquals(cover, tag.firstArtwork.binaryData)
        assertEquals(mapOf("REPLAYGAIN_ALBUM_GAIN" to "-2.50 dB", "SongFixer Test" to "keep me"), txxx(tag as AbstractID3v2Tag))
        assertNull("no volume tags added", com.biteric.songfixer.files.ReplayGainTags.trackGain(tag))
        assertEquals(10, AudioFileIO.read(copy).audioHeader.trackLength)
        copy.delete()

        // Not renamed, and Android's music library now shows 2017.
        rescan(uri)
        assertEquals(displayName, mediaColumn(uri, MediaStore.Audio.Media.DISPLAY_NAME))
        assertEquals("2017", mediaColumn(uri, MediaStore.Audio.Media.YEAR))
    }
}
