package com.biteric.songfixer.match

import com.biteric.songfixer.data.Candidate
import com.biteric.songfixer.data.MatchSource
import com.biteric.songfixer.online.AcoustIdApi
import com.biteric.songfixer.online.AcoustIdKeyException
import com.biteric.songfixer.online.AcoustIdReleaseGroup
import com.biteric.songfixer.online.AcoustIdResult
import com.biteric.songfixer.online.LrclibApi
import com.biteric.songfixer.online.MusicBrainzApi
import kotlinx.coroutines.CancellationException
import java.io.IOException
import kotlin.math.abs
import kotlin.math.min

/** What a song is known as now. */
data class SongInfo(
    val fileName: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val genre: String?,
    val durationSec: Int?,
)

data class Identification(
    /** Best first. */
    val candidates: List<Candidate>,
    /** LRCLIB has lyrics under the best candidate's name (or the current one); null = couldn't check. */
    val lyricsFound: Boolean?,
    /** Something went wrong along the way that the user may want to know (e.g. a bad AcoustID key). */
    val note: String? = null,
)

/**
 * Works out a song's right name: by its sound (AcoustID) when a fingerprint and key are given,
 * otherwise (or when the sound isn't known) by its tags and file name (MusicBrainz, LRCLIB).
 */
class Identifier(
    private val acoustId: AcoustIdApi,
    private val musicBrainz: MusicBrainzApi,
    private val lrclib: LrclibApi,
) {
    suspend fun identify(song: SongInfo, fingerprint: String?, acoustIdKey: String?, fingerprintDurationSec: Int?): Identification {
        val guesses = NameGuesses.from(song.fileName, song.title, song.artist)
        var note: String? = null
        var candidates = emptyList<Candidate>()

        if (fingerprint != null && !acoustIdKey.isNullOrBlank()) {
            try {
                val results = acoustId.lookup(acoustIdKey, fingerprint, fingerprintDurationSec ?: song.durationSec ?: 0)
                candidates = fromSound(results, song, guesses)
            } catch (e: AcoustIdKeyException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                note = "Sound lookup failed: ${e.message}"
            }
        }
        if (candidates.isEmpty()) candidates = fromNames(guesses, song.durationSec)

        // Fill in a missing genre from MusicBrainz.
        val best = candidates.firstOrNull()
        if (best != null && best.genre == null && song.genre.isNullOrBlank() && (best.recordingId != null || best.artistId != null)) {
            val genre = runCatching { musicBrainz.genre(best.recordingId, best.artistId) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            if (genre != null) candidates = listOf(best.copy(genre = genre)) + candidates.drop(1)
        }

        val lyricsTitle = candidates.firstOrNull()?.title ?: song.title?.takeIf(NameGuesses::usableTitle)
        val lyricsArtist = candidates.firstOrNull()?.artist ?: song.artist?.takeIf(NameGuesses::usableArtist)
        val lyrics = if (lyricsTitle != null && lyricsArtist != null) hasLyrics(lyricsTitle, lyricsArtist, song.durationSec) else null
        return Identification(candidates, lyrics, note)
    }

    /** Search by free text (the user typed it), for songs nothing was found for. */
    suspend fun search(query: String, durationSec: Int?): List<Candidate> {
        val parts = query.split(Regex("""\s+[-–—]\s+"""), limit = 2)
        val guesses = if (parts.size == 2) {
            listOf(NameGuess(parts[1].trim(), parts[0].trim()), NameGuess(parts[0].trim(), parts[1].trim()))
        } else {
            listOf(NameGuess(query.trim(), null))
        }
        return fromNames(guesses, durationSec, lrclibQuery = query)
    }

    private fun fromSound(results: List<AcoustIdResult>, song: SongInfo, guesses: List<NameGuess>): List<Candidate> {
        class Group(val title: String, val artist: String, var score: Double, val groups: MutableList<AcoustIdReleaseGroup>, var count: Int,
                    val durationSec: Int?, val recordingId: String, val artistId: String?)
        val byName = LinkedHashMap<String, Group>()
        for (r in results) {
            if (r.score < MIN_SOUND_SCORE) continue
            for (rec in r.recordings) {
                val title = rec.title?.takeIf { it.isNotBlank() } ?: continue
                if (rec.artists.isEmpty()) continue
                val artist = AcoustIdApi.credit(rec.artists)
                val key = Text.normalize(title) + "|" + Text.normalize(artist)
                val g = byName.getOrPut(key) {
                    Group(title, artist, r.score, mutableListOf(), 0, rec.duration?.toInt(), rec.id, rec.artists.first().id)
                }
                g.score = maxOf(g.score, r.score)
                g.groups += rec.releasegroups
                g.count++
            }
        }
        return byName.values
            .map { g ->
                val agree = guesses.maxOfOrNull { nameScore(g.title, g.artist, it) } ?: 0.0
                val rank = g.score + 0.04 * agree + 0.01 * min(g.count, 5)
                val durationOk = song.durationSec == null || g.durationSec == null || abs(song.durationSec - g.durationSec) <= 10
                rank to Candidate(
                    title = g.title,
                    artist = g.artist,
                    album = pickAlbum(g.groups, song.album),
                    durationSec = g.durationSec,
                    source = MatchSource.SOUND,
                    score = g.score,
                    sure = g.score >= SURE_SOUND_SCORE && durationOk,
                    recordingId = g.recordingId,
                    artistId = g.artistId,
                )
            }
            .sortedByDescending { it.first }
            .map { it.second }
            .take(MAX_CANDIDATES)
    }

    private suspend fun fromNames(guesses: List<NameGuess>, durationSec: Int?, lrclibQuery: String? = null): List<Candidate> {
        val found = mutableListOf<Candidate>()
        var asked = 0
        var failure: IOException? = null
        for (guess in guesses.take(4)) {
            asked++
            val recordings = try {
                musicBrainz.searchRecordings(guess.title, guess.artist)
            } catch (e: IOException) {
                failure = e
                emptyList()
            }
            for (rec in recordings) {
                if (rec.artistCredit.isEmpty()) continue
                val len = rec.length?.let { (it / 1000).toInt() }
                // Original albums over mixtapes, compilations and bootlegs.
                val onAlbum = rec.releases.any { it.status == "Official" && it.releaseGroup?.primaryType == "Album" && it.releaseGroup.secondaryTypes.isEmpty() }
                val score = (scoreByName(rec.title, rec.artist, guess, len, durationSec) + if (onAlbum) 0.03 else 0.0)
                    .coerceAtMost(1.0) * (0.7 + 0.3 * (rec.score ?: 100) / 100.0)
                found += Candidate(
                    title = rec.title,
                    artist = rec.artist,
                    album = pickMbAlbum(rec.releases),
                    year = rec.year,
                    durationSec = len,
                    source = MatchSource.NAME,
                    score = score,
                    recordingId = rec.id,
                    artistId = rec.artistCredit.first().artist?.id,
                )
            }
            if (found.any { it.score >= SURE_NAME_SCORE }) break
        }
        // LRCLIB knows the names lyrics are filed under, and many songs MusicBrainz lacks.
        val top = guesses.firstOrNull()
        val q = lrclibQuery ?: top?.let { listOfNotNull(it.artist, it.title).joinToString(" ") }
        var lrclibFailed = false
        if (q != null && top != null) {
            val tracks = try {
                lrclib.search(q)
            } catch (e: IOException) {
                lrclibFailed = true
                emptyList()
            } catch (e: kotlinx.serialization.SerializationException) {
                lrclibFailed = true
                emptyList()
            }
            for (t in tracks.take(10)) {
                val title = t.trackName ?: continue
                val artist = t.artistName ?: continue
                val len = t.duration?.toInt()
                val score = guesses.take(2).maxOf { scoreByName(title, artist, it, len, durationSec) }
                found += Candidate(title, artist, t.albumName?.takeIf { it.isNotBlank() }, durationSec = len, source = MatchSource.NAME, score = score)
            }
        }
        // Nothing answered (offline?): that's not "not found", so the song can be checked again later.
        if (found.isEmpty() && asked > 0 && failure != null && (lrclibFailed || q == null)) throw failure
        // One entry per name; keep the best-scored, but take album/ids from MusicBrainz when it has them.
        // Names entered carelessly ("juicy / notorious big", "artist_-_title") lose to proper ones,
        // and a name many entries agree on wins over a one-off.
        return found
            .map { it.copy(score = (it.score - sloppiness(it)).coerceAtLeast(0.0)) }
            .groupBy { Text.normalize(it.title) + "|" + Text.normalize(it.artist) }
            .values
            .map { same ->
                val best = same.maxBy { it.score }
                val mb = same.filter { it.recordingId != null }.maxByOrNull { it.score }
                val merged = if (mb != null && best.recordingId == null) best.copy(album = mb.album ?: best.album, year = mb.year, recordingId = mb.recordingId, artistId = mb.artistId) else best
                val agreed = (merged.score + 0.01 * (minOf(same.size, 5) - 1)).coerceAtMost(1.0)
                merged.copy(score = agreed, sure = agreed >= SURE_NAME_SCORE)
            }
            .filter { it.score >= MIN_NAME_SCORE }
            .sortedByDescending { it.score }
            .take(MAX_CANDIDATES)
    }

    private val lyricsFinder = LyricsFinder(lrclib)

    private suspend fun hasLyrics(title: String, artist: String, durationSec: Int?): Boolean? = try {
        lyricsFinder.find(title, artist, durationSec)?.let { LyricsFinder.text(it) != null } ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        const val MIN_SOUND_SCORE = 0.5
        const val SURE_SOUND_SCORE = 0.8
        const val MIN_NAME_SCORE = 0.45
        const val SURE_NAME_SCORE = 0.9
        const val MAX_CANDIDATES = 6

        /** 0..1: how well a found name fits a guess, before any length check. */
        fun nameScore(title: String, artist: String, guess: NameGuess): Double {
            val t = Text.similarity(title, guess.title)
            return if (guess.artist != null) 0.55 * t + 0.45 * Text.artistSimilarity(artist, guess.artist) else 0.75 * t
        }

        /**
         * [nameScore] adjusted by how close the lengths are: a different length is a different
         * recording (live, remix, radio edit). A perfect name alone stays below [SURE_NAME_SCORE];
         * only a matching length makes a name match sure.
         */
        fun scoreByName(title: String, artist: String, guess: NameGuess, foundSec: Int?, ourSec: Int?): Double {
            var s = 0.88 * nameScore(title, artist, guess)
            if (ourSec != null) {
                s += if (foundSec == null) {
                    -0.05
                } else {
                    val diff = abs(foundSec - ourSec)
                    // Seconds for short songs, a share of the length for long mixes.
                    when {
                        diff <= maxOf(3.0, ourSec * 0.01) -> 0.12
                        diff <= maxOf(8.0, ourSec * 0.03) -> 0.04
                        diff <= 20 -> -0.1
                        else -> -0.25
                    }
                }
            }
            return s.coerceIn(0.0, 1.0)
        }

        /**
         * How much a name looks typed carelessly: title and artist both lower case (one alone can be
         * real: "deadmau5", "bad guy"), underscores, or the artist inside the title.
         */
        fun sloppiness(c: Candidate): Double {
            fun lower(s: String) = s.any { it.isLetter() } && s.none { it.isUpperCase() }
            var p = 0.0
            if (lower(c.title) && lower(c.artist)) p += 0.08
            if ('_' in c.title || '_' in c.artist) p += 0.08
            if (Text.normalize(c.title).contains(Text.normalize(c.artist)) && Text.normalize(c.artist).length >= 3) p += 0.08
            return p
        }

        /** The original album over compilations and live releases; the user's album when it's one of them. */
        fun pickAlbum(groups: List<AcoustIdReleaseGroup>, current: String?): String? {
            val named = groups.filter { !it.title.isNullOrBlank() }
            if (!current.isNullOrBlank()) named.firstOrNull { Text.same(it.title, current) }?.let { return it.title }
            return (named.firstOrNull { it.type == "Album" && it.secondarytypes.isEmpty() }
                ?: named.firstOrNull { (it.type == "Single" || it.type == "EP") && it.secondarytypes.isEmpty() }
                ?: named.firstOrNull { "Compilation" !in it.secondarytypes }
                ?: named.firstOrNull())?.title
        }

        private fun pickMbAlbum(releases: List<com.biteric.songfixer.online.MbRelease>): String? {
            val named = releases.filter { !it.title.isNullOrBlank() }
            return (named.firstOrNull { it.releaseGroup?.primaryType == "Album" && it.releaseGroup.secondaryTypes.isEmpty() && it.status == "Official" }
                ?: named.firstOrNull { it.releaseGroup?.secondaryTypes?.isEmpty() == true }
                ?: named.firstOrNull())?.title
        }
    }
}
