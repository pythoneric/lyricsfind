package com.biteric.songfixer

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks every song in the folder chosen in the app, against the real online services, without
 * the UI (same steps as CheckWorker), and logs the results. Opt-in: needs network, a chosen
 * folder and `-e live 1`.
 */
@RunWith(AndroidJUnit4::class)
class LiveCheckTest {
    @Test
    fun checkChosenFolder() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "1")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SongFixerApp
        val tree = app.settings.current.folderUri
        assumeTrue(tree != null)
        app.repository.sync(tree!!)
        app.repository.recheckAll()
        while (app.repository.checkNext()) Unit
        for (s in app.repository.songs.first()) {
            Log.i("LiveCheck", "${s.fileName} | now: ${s.title} / ${s.artist} | ${s.status} | lyrics=${s.lyricsFound} | ${s.note ?: ""}")
            s.candidates.take(3).forEach { c ->
                Log.i("LiveCheck", "    ${c.title} / ${c.artist} / ${c.album} / ${c.genre} / ${c.year} | ${c.source} ${"%.2f".format(c.score)} sure=${c.sure} len=${c.durationSec}")
            }
        }
    }

    /** Adds lyrics only to the songs whose file name contains `-e lyrics <text>`. */
    @Test
    fun addLyricsToChosenSong() = runBlocking {
        val match = InstrumentationRegistry.getArguments().getString("lyrics")
        assumeTrue(match != null)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SongFixerApp
        assumeTestFolder(app)
        val song = app.repository.songs.first().first { it.fileName.contains(match!!) }
        val applied = app.repository.addLyrics(song)
        Log.i("LiveCheck", "lyrics for ${song.fileName}: ${applied.lyrics} | status ${applied.song.status} | lyricsInFile=${applied.song.lyricsInFile}")
        org.junit.Assert.assertEquals(SongRepository.LyricsOutcome.ADDED, applied.lyrics)
    }

    /** Adds the album cover only to the songs whose file name contains `-e cover <text>`. */
    @Test
    fun addCoverToChosenSong() = runBlocking {
        val match = InstrumentationRegistry.getArguments().getString("cover")
        assumeTrue(match != null)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SongFixerApp
        assumeTestFolder(app)
        val song = app.repository.songs.first().first { it.fileName.contains(match!!) }
        val applied = app.repository.addCover(song)
        val r = android.media.MediaMetadataRetriever()
        r.setDataSource(app, android.net.Uri.parse(applied.song.uri))
        val picture = r.embeddedPicture
        r.release()
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        picture?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size, bounds) }
        Log.i("LiveCheck", "cover for ${song.fileName}: ${applied.cover} | picture ${picture?.size ?: 0} bytes, ${bounds.outWidth}x${bounds.outHeight} | hasCover=${applied.song.hasCover}")
        org.junit.Assert.assertEquals(SongRepository.LyricsOutcome.ADDED, applied.cover)
        org.junit.Assert.assertTrue(picture != null && bounds.outWidth > 0)
    }

    /** Applies the top suggestion to the songs whose file name contains `-e fix <text>`, then re-reads the folder. */
    @Test
    fun fixChosenSong() = runBlocking {
        val match = InstrumentationRegistry.getArguments().getString("fix")
        assumeTrue(match != null)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SongFixerApp
        assumeTestFolder(app)
        val song = app.repository.songs.first().first { it.fileName.contains(match!!) && it.best != null }
        val c = song.best!!
        val applied = app.repository.apply(
            song,
            com.biteric.songfixer.files.NewNames(c.title, c.artist, c.album.orEmpty(), c.genre.orEmpty(), c.year?.toString().orEmpty()),
            writeTags = true,
            rename = true,
            addLyrics = true,
        )
        val fixed = applied.song
        val tags = com.biteric.songfixer.files.MusicFolder(app).readTags(android.net.Uri.parse(fixed.uri))
        Log.i("LiveCheck", "fixed: ${song.fileName} -> ${fixed.fileName} | tags now: ${tags.title} / ${tags.artist} / ${tags.album} / ${tags.genre} | ${tags.durationMs} ms | lyrics: ${applied.lyrics}")
        org.junit.Assert.assertEquals(c.title, tags.title)
        org.junit.Assert.assertEquals(c.artist, tags.artist)
        app.repository.sync(app.settings.current.folderUri!!)
        val after = app.repository.songs.first().first { it.id == song.id }
        Log.i("LiveCheck", "after re-sync: ${after.fileName} ${after.status}")
        org.junit.Assert.assertEquals(com.biteric.songfixer.data.SongStatus.FIXED, after.status)
    }

    /**
     * Puts volume tags another program might have left into the songs whose file name contains
     * `-e oldtags <text>`: `-e style rg1` (ReplayGain 1.0, 89 dB, with album gain) or `r128`
     * (R128_TRACK_GAIN only). Other TXXX frames stay. For checking that the app replaces them.
     */
    @Test
    fun putOldVolumeTags() = runBlocking {
        val match = InstrumentationRegistry.getArguments().getString("oldtags")
        assumeTrue(match != null)
        val style = InstrumentationRegistry.getArguments().getString("style") ?: "rg1"
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SongFixerApp
        assumeTestFolder(app)
        val song = app.repository.songs.first().first { it.fileName.contains(match!!) && it.extension == "mp3" }
        val uri = android.net.Uri.parse(song.uri)
        val copy = java.io.File(app.cacheDir, "oldtags.mp3")
        app.contentResolver.openInputStream(uri)!!.use { input -> copy.outputStream().use { input.copyTo(it) } }
        val audio = org.jaudiotagger.audio.AudioFileIO.read(copy)
        val tag = audio.tagOrCreateAndSetDefault as org.jaudiotagger.tag.id3.AbstractID3v2Tag
        fun txxx(d: String, v: String) = tag.createFrame("TXXX").apply { body = org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX(0, d, v) }
        val volume = setOf("REPLAYGAIN_TRACK_GAIN", "REPLAYGAIN_TRACK_PEAK", "REPLAYGAIN_REFERENCE_LOUDNESS", "REPLAYGAIN_ALBUM_GAIN", "REPLAYGAIN_ALBUM_PEAK", "R128_TRACK_GAIN")
        val others = runCatching { tag.getFields("TXXX") }.getOrDefault(emptyList())
            .filterIsInstance<org.jaudiotagger.tag.id3.AbstractID3v2Frame>()
            .filter { (it.body as? org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX)?.description?.uppercase() !in volume }
        val old = when (style) {
            "r128" -> listOf(txxx("R128_TRACK_GAIN", "-1536"))
            else -> listOf(
                txxx("replaygain_track_gain", "-3.10 dB"),
                txxx("replaygain_track_peak", "0.950000"),
                txxx("REPLAYGAIN_REFERENCE_LOUDNESS", "89.0 dB"),
                txxx("REPLAYGAIN_ALBUM_GAIN", "-2.50 dB"),
                txxx("REPLAYGAIN_ALBUM_PEAK", "0.990000"),
            )
        }
        tag.setFrame("TXXX", others + old + txxx("SongFixer Test", "keep me"))
        audio.commit()
        app.contentResolver.openOutputStream(uri, "wt")!!.use { out -> copy.inputStream().use { it.copyTo(out) } }
        copy.delete()
        Log.i("LiveCheck", "old $style volume tags put in ${song.fileName}")
        Unit
    }

    /** Reads the folder and measures / reads volume tags like the check's loudness phase (`-e volume 1`), and logs verdicts. */
    @Test
    fun readVolumeTags() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("volume") == "1")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SongFixerApp
        assumeTestFolder(app)
        app.repository.sync(app.settings.current.folderUri!!)
        while (app.repository.measureNext()) Unit
        for (s in app.repository.songs.first()) {
            Log.i("LiveCheck", "${s.fileName} | ${s.loudnessLufs} LUFS gain ${s.trackGainDb} | tags ${s.existingTrackGain} / ${s.existingTrackPeak} / ${s.existingReference} | ${s.gainVerdict}")
        }
    }

    /**
     * Cases that change files run only when the app is pointed at a test folder (its name has
     * "SongFixerTest" in it), never at someone's real music.
     */
    private fun assumeTestFolder(app: SongFixerApp) =
        assumeTrue(app.settings.current.folderUri?.toString()?.contains("SongFixerTest") == true)
}
