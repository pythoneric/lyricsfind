package com.biteric.songfixer

import com.biteric.songfixer.data.SettingsState
import com.biteric.songfixer.data.Song
import com.biteric.songfixer.data.SongStatus
import kotlinx.coroutines.CancellationException

/** The fixes done to many songs at once, from the list's buttons. */
enum class BatchKind(val doing: String) {
    SURE("Fixing sure matches"),
    YEARS("Correcting years"),
    NORMALIZE("Normalizing volume"),
    TRIM("Trimming silence"),
    LYRICS("Adding lyrics"),
}

/**
 * Runs a batch over the songs that need it right now, each read again just before it's changed
 * (the check may be running too). [progress] gets (done, total) after each song. Returns what to
 * tell the user. Stopping (cancellation) stops it between songs.
 */
class Batches(private val repo: SongRepository) {
    suspend fun run(kind: BatchKind, settings: SettingsState, progress: suspend (done: Int, total: Int) -> Unit): String {
        val songs = repo.allSongs()
        return when (kind) {
            BatchKind.SURE -> sure(songs, settings, progress)
            BatchKind.YEARS -> years(songs, settings, progress)
            BatchKind.NORMALIZE -> normalize(songs, progress)
            BatchKind.TRIM -> trim(songs, progress)
            BatchKind.LYRICS -> lyrics(songs, progress)
        }
    }

    /** Runs [each] on every item, counting failures; a stop request isn't counted as one, it ends the batch. */
    private suspend fun <T> forEach(items: List<T>, progress: suspend (Int, Int) -> Unit, each: suspend (T) -> Unit): Int {
        var failed = 0
        progress(0, items.size)
        items.forEachIndexed { i, item ->
            try {
                each(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
            }
            progress(i + 1, items.size)
        }
        return failed
    }

    private suspend fun sure(songs: List<Song>, s: SettingsState, progress: suspend (Int, Int) -> Unit): String {
        var fixed = 0
        var lyrics = 0
        val failed = forEach(repo.sureOnes(songs), progress) { (listed, c) ->
            val song = repo.get(listed.id)?.takeIf { it.status == SongStatus.SUGGESTED } ?: return@forEach
            // A song whose only problem is its year gets just the year (no rename, names as they are).
            val done = repo.apply(song, SongRepository.surePlan(song, c, s))
            if (done.lyrics == SongRepository.LyricsOutcome.ADDED) lyrics++
            fixed++
        }
        return "Fixed $fixed songs" + (if (s.addLyrics) ", lyrics added to $lyrics" else "") + failedNote(failed, "couldn't be changed")
    }

    private suspend fun years(songs: List<Song>, s: SettingsState, progress: suspend (Int, Int) -> Unit): String {
        var done = 0
        val failed = forEach(SongRepository.yearOnly(songs), progress) { listed ->
            val song = repo.get(listed.id)?.takeIf(SongRepository::isYearOnly) ?: return@forEach
            repo.apply(song, SongRepository.yearOnlyPlan(song, s, extras = false))
            done++
        }
        return "Year corrected in " + songsText(done) + failedNote(failed, "couldn't be changed")
    }

    private suspend fun normalize(songs: List<Song>, progress: suspend (Int, Int) -> Unit): String {
        var added = 0
        var kept = 0
        val failed = forEach(SongRepository.needNormalizing(songs), progress) { listed ->
            val song = repo.get(listed.id)?.takeIf(SongRepository::needsNormalizing) ?: return@forEach
            when (repo.normalize(song).volume) {
                SongRepository.LyricsOutcome.ADDED -> added++
                SongRepository.LyricsOutcome.ALREADY_THERE -> kept++
                else -> throw IllegalStateException("not tagged")
            }
        }
        return "Volume tagged in " + songsText(added) + (if (kept > 0) ", $kept already standard" else "") + failedNote(failed, "couldn't be changed")
    }

    private suspend fun trim(songs: List<Song>, progress: suspend (Int, Int) -> Unit): String {
        var trimmed = 0
        val failed = forEach(SongRepository.needTrimming(songs), progress) { listed ->
            val song = repo.get(listed.id)?.takeIf { it.hasSilenceToTrim } ?: return@forEach
            when (repo.trimSilence(song).trim) {
                SongRepository.TrimOutcome.TRIMMED -> trimmed++
                SongRepository.TrimOutcome.FAILED -> throw IllegalStateException("not trimmed")
                else -> {}
            }
        }
        return "Silence cut from " + songsText(trimmed) + failedNote(failed, "couldn't be trimmed")
    }

    private suspend fun lyrics(songs: List<Song>, progress: suspend (Int, Int) -> Unit): String {
        var added = 0
        val failed = forEach(repo.needLyrics(songs), progress) { listed ->
            val song = repo.get(listed.id)?.takeIf(SongRepository::needsLyrics) ?: return@forEach
            if (repo.addLyrics(song).lyrics == SongRepository.LyricsOutcome.ADDED) added++
        }
        return "Lyrics added to " + songsText(added) + failedNote(failed, "couldn't be changed")
    }

    companion object {
        fun songsText(n: Int) = if (n == 1) "1 song" else "$n songs"
        private fun failedNote(n: Int, what: String) = if (n > 0) ", $n $what" else ""
    }
}
