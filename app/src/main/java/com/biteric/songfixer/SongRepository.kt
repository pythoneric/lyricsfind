package com.biteric.songfixer

import android.net.Uri
import com.biteric.songfixer.data.Candidate
import com.biteric.songfixer.data.Settings
import com.biteric.songfixer.data.Song
import com.biteric.songfixer.data.SongDao
import com.biteric.songfixer.data.SongStatus
import com.biteric.songfixer.files.MusicFolder
import com.biteric.songfixer.files.NewNames
import com.biteric.songfixer.files.SongWriter
import com.biteric.songfixer.fingerprint.AudioFingerprinter
import com.biteric.songfixer.match.Identifier
import com.biteric.songfixer.match.LyricsFinder
import com.biteric.songfixer.match.SongInfo
import com.biteric.songfixer.match.Text
import com.biteric.songfixer.match.Verdict
import com.biteric.songfixer.online.AcoustIdKeyException
import com.biteric.songfixer.online.LrclibApi
import com.biteric.songfixer.online.LrclibTrack
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.io.IOException

class SongRepository(
    private val dao: SongDao,
    private val folder: MusicFolder,
    private val fingerprinter: AudioFingerprinter,
    private val identifier: Identifier,
    private val writer: SongWriter,
    private val settings: Settings,
    private val lyricsFinder: LyricsFinder,
    private val lrclib: LrclibApi,
) {
    val songs = dao.observeAll()
    val counts = dao.observeCounts()

    /** Brings the list in line with the folder: new and changed files get checked again. */
    suspend fun sync(tree: Uri) {
        val files = folder.list(tree)
        val known = dao.all().associateBy { it.uri }
        val found = files.associateBy { it.uri.toString() }
        dao.delete(known.values.filter { it.uri !in found }.map { it.id })
        val added = mutableListOf<Song>()
        for (f in files) {
            val old = known[f.uri.toString()]
            if (old == null) {
                added += Song(uri = f.uri.toString(), fileName = f.name, folder = f.folder, size = f.size, modified = f.modified)
            } else if (old.size != f.size || old.modified != f.modified) {
                // Changed outside the app (unless it's our own fix, which keeps FIXED).
                val status = if (old.status == SongStatus.FIXED || old.status == SongStatus.KEPT) old.status else SongStatus.NEW
                dao.update(old.copy(fileName = f.name, folder = f.folder, size = f.size, modified = f.modified, status = status))
            }
        }
        if (added.isNotEmpty()) dao.insert(added)
    }

    suspend fun uncheckedCount() = dao.uncheckedCount()

    /**
     * Checks the next unchecked song; false when there are none left.
     * Throws [AcoustIdKeyException] when the key is refused (the song stays unchecked).
     */
    suspend fun checkNext(): Boolean {
        val song = dao.nextUnchecked() ?: return false
        dao.update(check(song))
        return true
    }

    private suspend fun check(song: Song): Song {
        val uri = Uri.parse(song.uri)
        val tags = try {
            folder.readTags(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return song.copy(status = SongStatus.FAILED, note = "Can't read this file: ${e.message}")
        }
        val key = settings.current.acoustIdKey
        var note: String? = null
        val fp = if (key.isNotBlank()) {
            try {
                fingerprinter.fingerprint(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                note = "Couldn't listen to the song: ${e.message}"
                null
            }
        } else {
            null
        }
        val durationSec = tags.durationMs?.let { (it / 1000).toInt() } ?: fp?.durationSec
        val info = SongInfo(song.fileName, tags.title, tags.artist, tags.album, tags.genre, durationSec)
        val result = try {
            identifier.identify(info, fp?.fingerprint, key, fp?.durationSec?.takeIf { it > 0 } ?: durationSec)
        } catch (e: AcoustIdKeyException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return song.copy(title = tags.title, artist = tags.artist, album = tags.album, genre = tags.genre, durationMs = tags.durationMs,
                status = SongStatus.FAILED, note = "Check failed: ${e.message}")
        }
        return song.copy(
            title = tags.title,
            artist = tags.artist,
            album = tags.album,
            genre = tags.genre,
            durationMs = tags.durationMs,
            status = Verdict.status(tags.title, tags.artist, tags.album, tags.genre, result.candidates.firstOrNull()),
            candidates = result.candidates,
            lyricsFound = result.lyricsFound,
            note = result.note ?: note,
        )
    }

    /** Looks a song up by text the user typed. */
    suspend fun search(song: Song, query: String): Song {
        val found = identifier.search(query, song.durationMs?.let { (it / 1000).toInt() })
        val updated = song.copy(
            candidates = found,
            status = if (found.isEmpty()) SongStatus.NOT_FOUND else SongStatus.SUGGESTED,
            lyricsFound = null,
        )
        dao.update(updated)
        return updated
    }

    enum class LyricsOutcome { ADDED, ALREADY_THERE, NOT_ONLINE, OFFLINE, NOT_ASKED }

    class Applied(val song: Song, val lyrics: LyricsOutcome)

    /**
     * Writes [names] into the song's tags, renames it to "Artist - Title" and/or adds its lyrics
     * (looked up under the new names): into the lyrics tag, and as a .lrc file when the settings
     * ask for it or the format has no lyrics tag.
     */
    suspend fun apply(song: Song, names: NewNames, writeTags: Boolean, rename: Boolean, addLyrics: Boolean): Applied {
        var uri = Uri.parse(song.uri)
        var fileName = song.fileName
        val title = names.title.ifBlank { song.title.orEmpty() }
        val artist = names.artist.ifBlank { song.artist.orEmpty() }

        var outcome = LyricsOutcome.NOT_ASKED
        var lyrics: String? = null
        if (addLyrics && title.isNotBlank() && artist.isNotBlank()) {
            outcome = try {
                lyrics = lyricsFinder.find(title, artist, song.durationMs?.let { (it / 1000).toInt() })?.let(LyricsFinder::text)
                if (lyrics == null) LyricsOutcome.NOT_ONLINE else LyricsOutcome.ADDED
            } catch (e: IOException) {
                LyricsOutcome.OFFLINE
            } catch (e: SerializationException) {
                LyricsOutcome.OFFLINE
            }
        }
        val canEmbed = song.extension in SongWriter.TAGGABLE
        var embedded: SongWriter.EmbedResult? = null
        if (writeTags || (lyrics != null && canEmbed)) {
            embedded = writer.writeTags(uri, song.extension, if (writeTags) names else NewNames("", "", "", ""), lyrics.takeIf { canEmbed })
        }
        if (embedded == SongWriter.EmbedResult.HAD_LYRICS) outcome = LyricsOutcome.ALREADY_THERE
        if (rename && names.artist.isNotBlank() && names.title.isNotBlank()) {
            val newName = SongWriter.fileName(names.artist, names.title, song.extension)
            uri = writer.rename(uri, fileName, newName)
            fileName = newName
        }
        if (lyrics != null && wantsLrcFile(embedded)) writer.writeLrc(uri, fileName, lyrics)
        runCatching { writer.notifyMediaStore(uri) }
        val best = song.best
        val sameAsBest = best != null && Text.same(best.title, names.title) && Text.same(best.artist, names.artist)
        val fixed = song.copy(
            uri = uri.toString(),
            fileName = fileName,
            title = if (writeTags) names.title.ifBlank { song.title } else song.title,
            artist = if (writeTags) names.artist.ifBlank { song.artist } else song.artist,
            album = if (writeTags) names.album.ifBlank { song.album } else song.album,
            genre = if (writeTags) names.genre.ifBlank { song.genre } else song.genre,
            // Lyrics-only on a song that was already right: it stays "already right".
            status = if (!writeTags && !rename && song.status == SongStatus.OK) SongStatus.OK else SongStatus.FIXED,
            lyricsFound = when (outcome) {
                LyricsOutcome.ADDED, LyricsOutcome.ALREADY_THERE -> true
                LyricsOutcome.NOT_ONLINE -> false
                else -> if (sameAsBest) song.lyricsFound else null
            },
            lyricsInFile = if (outcome == LyricsOutcome.ADDED || outcome == LyricsOutcome.ALREADY_THERE) true else song.lyricsInFile,
            note = null,
        )
        dao.update(fixed)
        return Applied(fixed, outcome)
    }

    /** Songs whose names are right and whose lyrics are online but not in the file yet. */
    fun needLyrics(songs: List<Song>): List<Song> =
        songs.filter { (it.status == SongStatus.OK || it.status == SongStatus.FIXED) && it.lyricsFound == true && it.lyricsInFile != true }

    /** Adds lyrics only, names untouched. */
    suspend fun addLyrics(song: Song): Applied = apply(song, NewNames("", "", "", ""), writeTags = false, rename = false, addLyrics = true)

    /**
     * A .lrc file too: when the settings ask for one, or the lyrics couldn't go into the file's
     * tag (no tag written, or a format without a lyrics tag). Not when the file kept its own lyrics.
     */
    private fun wantsLrcFile(embedded: SongWriter.EmbedResult?) =
        embedded != SongWriter.EmbedResult.HAD_LYRICS &&
            (settings.current.lrcFiles || embedded == null || embedded == SongWriter.EmbedResult.UNSUPPORTED)

    /**
     * LRCLIB entries with lyrics for free text the user typed, for picking by hand when there's no
     * exact match: synced first, then the closest length to the song's.
     */
    suspend fun searchLyrics(query: String, durationSec: Int?): List<LrclibTrack> =
        rankLyrics(lrclib.search(query), durationSec)

    /**
     * Saves lyrics the user picked or pasted, replacing any the file has (they chose them). Into
     * the lyrics tag, plus a .lrc file as [apply] does. Names and status stay as they are.
     */
    suspend fun saveLyrics(song: Song, text: String): Song {
        val uri = Uri.parse(song.uri)
        val embedded = if (song.extension in SongWriter.TAGGABLE) {
            writer.writeTags(uri, song.extension, NewNames("", "", "", ""), text, replaceLyrics = true)
        } else {
            null
        }
        if (wantsLrcFile(embedded)) writer.writeLrc(uri, song.fileName, text)
        runCatching { writer.notifyMediaStore(uri) }
        val saved = song.copy(lyricsInFile = true, lyricsFound = true)
        dao.update(saved)
        return saved
    }

    companion object {
        fun rankLyrics(results: List<LrclibTrack>, durationSec: Int?): List<LrclibTrack> = results
            .filter { LyricsFinder.text(it) != null }
            .sortedWith(
                compareBy<LrclibTrack> { it.syncedLyrics.isNullOrBlank() }
                    .thenBy { if (durationSec == null || it.duration == null) Double.MAX_VALUE else kotlin.math.abs(it.duration - durationSec) },
            )
    }

    suspend fun keep(song: Song) = dao.update(song.copy(status = SongStatus.KEPT))

    suspend fun recheck(song: Song) = dao.update(song.copy(status = SongStatus.NEW))

    suspend fun recheckAll() = dao.markAllUnchecked()

    suspend fun retryFailed() = dao.markFailedUnchecked()

    suspend fun forgetFolder() = dao.clear()

    /** Songs with a sure suggestion, for "Fix all sure ones". */
    fun sureOnes(songs: List<Song>): List<Pair<Song, Candidate>> =
        songs.filter { it.status == SongStatus.SUGGESTED }.mapNotNull { s -> s.best?.takeIf { it.sure }?.let { s to it } }

    suspend fun get(id: Long) = dao.get(id)
}
