package com.biteric.songfixer

import android.net.Uri
import com.biteric.songfixer.data.Candidate
import com.biteric.songfixer.data.Settings
import com.biteric.songfixer.data.SettingsState
import com.biteric.songfixer.data.Song
import com.biteric.songfixer.data.SongDao
import com.biteric.songfixer.data.SongStatus
import com.biteric.songfixer.files.MusicFolder
import com.biteric.songfixer.files.NewNames
import com.biteric.songfixer.files.SongWriter
import com.biteric.songfixer.files.SilenceTrimmer
import com.biteric.songfixer.loudness.SilenceMark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.biteric.songfixer.fingerprint.AudioFingerprinter
import com.biteric.songfixer.match.Identifier
import com.biteric.songfixer.match.LyricsFinder
import com.biteric.songfixer.match.SongInfo
import com.biteric.songfixer.match.Text
import com.biteric.songfixer.match.Verdict
import com.biteric.songfixer.online.AcoustIdKeyException
import com.biteric.songfixer.loudness.Loudness
import com.biteric.songfixer.loudness.LoudnessAnalyzer
import com.biteric.songfixer.loudness.GainVerdict
import com.biteric.songfixer.loudness.ReplayGain
import com.biteric.songfixer.loudness.ReplayGainCheck
import com.biteric.songfixer.loudness.VolumeTags
import com.biteric.songfixer.match.CoverFinder
import com.biteric.songfixer.online.CoverArtApi
import com.biteric.songfixer.online.CoverImage
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
    private val coverFinder: CoverFinder,
    private val coverArt: CoverArtApi,
    private val loudness: LoudnessAnalyzer,
    /** Where untrimmed originals are kept for "Undo trim"; null = never kept. */
    private val originalsDir: File? = null,
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
                // Its volume tags are read again (the audio isn't measured again).
                // A trim that failed on the old file is worth trying on the new one.
                dao.update(old.copy(fileName = f.name, folder = f.folder, size = f.size, modified = f.modified, status = status, gainVerdict = null, trimError = null))
            }
        }
        if (added.isNotEmpty()) dao.insert(added)
    }

    suspend fun uncheckedCount() = dao.uncheckedCount()

    /**
     * Reads the year from the files of songs checked before years were kept (no lookups). A song
     * named right whose file has no year, while its match knows one, becomes something to fix;
     * other statuses (fixed, kept, suggested…) stay as they are.
     */
    suspend fun readYears() {
        for (song in dao.yearNotRead()) {
            val year = try {
                folder.readTags(Uri.parse(song.uri)).year
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                song.year
            }
            val status = if (song.status == SongStatus.OK) {
                Verdict.status(song.title, song.artist, song.album, song.genre, song.candidates, year = year)
                    .takeIf { it == SongStatus.SUGGESTED } ?: SongStatus.OK
            } else {
                song.status
            }
            dao.update(song.copy(year = year, yearRead = true, status = status))
        }
    }

    /**
     * Checks the next unchecked song; false when there are none left.
     * Throws [AcoustIdKeyException] when the key is refused (the song stays unchecked).
     */
    suspend fun checkNext(): Boolean = checkNextSong() != null

    /** Checks the next unchecked song and returns it as saved; null when there are none left. */
    suspend fun checkNextSong(): Song? {
        val song = dao.nextUnchecked() ?: return null
        val checked = check(song)
        dao.update(checked)
        return checked
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
            return song.copy(title = tags.title, artist = tags.artist, album = tags.album, genre = tags.genre, year = tags.year, yearRead = true, durationMs = tags.durationMs, hasCover = tags.hasCover,
                status = SongStatus.FAILED, note = "Check failed: ${e.message}")
        }
        return song.copy(
            title = tags.title,
            artist = tags.artist,
            album = tags.album,
            genre = tags.genre,
            year = tags.year,
            yearRead = true,
            durationMs = tags.durationMs, hasCover = tags.hasCover,
            status = Verdict.status(tags.title, tags.artist, tags.album, tags.genre, result.candidates, year = tags.year),
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

    /** Same outcomes for the cover: ADDED, ALREADY_THERE, NOT_ONLINE (no album or no cover), OFFLINE, NOT_ASKED. */
    class Applied(
        val song: Song,
        val lyrics: LyricsOutcome,
        val cover: LyricsOutcome = LyricsOutcome.NOT_ASKED,
        /** ADDED (ReplayGain tags written), ALREADY_THERE (the file had some), NOT_ONLINE (couldn't measure / format can't hold them). */
        val volume: LyricsOutcome = LyricsOutcome.NOT_ASKED,
        val trim: TrimOutcome = TrimOutcome.NOT_ASKED,
        /** What was cut off, when [trim] is TRIMMED. */
        val trimmed: SongWriter.Trimmed? = null,
        /** Why it couldn't be trimmed, when [trim] is FAILED. */
        val trimError: String? = null,
    )

    enum class TrimOutcome { TRIMMED, NOTHING_TO_CUT, FAILED, NOT_ASKED }

    /**
     * Writes [names] into the song's tags, renames it to "Artist - Title" and/or adds its lyrics
     * (looked up under the new names): into the lyrics tag, and as a .lrc file when the settings
     * ask for it or the format has no lyrics tag. With [addCover], the album's front cover from
     * the Cover Art Archive goes in too ([releaseGroupId] = the chosen match's album, if known);
     * a cover already in the file stays unless [replaceCover] (the user saw both and kept the new one).
     */
    suspend fun apply(
        song: Song,
        names: NewNames,
        writeTags: Boolean,
        rename: Boolean,
        addLyrics: Boolean,
        addCover: Boolean = false,
        releaseGroupId: String? = null,
        normalize: Boolean = false,
        replaceReplayGain: Boolean = false,
        replaceCover: Boolean = false,
        trimSilence: Boolean = false,
    ): Applied {
        var uri = Uri.parse(song.uri)
        var fileName = song.fileName
        // The silence measured on exactly this file (same size and time), if it was: a trim uses it
        // instead of listening to the whole song again.
        val mark = SilenceMark.decode(song.silenceMark)?.takeIf { it.matches(writer.stamp(uri)) }

        // Silence first: it rewrites the audio, the tags go into the trimmed file after.
        var trimOutcome = TrimOutcome.NOT_ASKED
        var trimmed: SongWriter.Trimmed? = null
        var trimError: String? = null
        var keptOriginal: File? = null
        if (trimSilence) {
            trimOutcome = try {
                // The untrimmed file is kept for "Undo trim" (the first one only, when trimmed twice).
                val keepAs = originalsDir?.takeIf { settings.current.keepOriginals && song.originalPath == null }
                    ?.let { File(it, "${song.id}-${System.currentTimeMillis()}.${song.extension}") }
                val t = writer.trimSilence(uri, song.extension, keepAs) { copy -> mark?.silence ?: loudness.silence(copy) }
                if (t.originalKept) keptOriginal = keepAs
                trimmed = t
                when {
                    t.startMs + t.endMs > 0 -> TrimOutcome.TRIMMED
                    // Silence worth cutting, but none of it could be (it'd be tried in every batch again).
                    SilenceTrimmer.worthIt(t.leadLeftMs, t.tailLeftMs) -> {
                        trimError = "its silence couldn't be cut"
                        TrimOutcome.FAILED
                    }
                    else -> TrimOutcome.NOTHING_TO_CUT
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                trimError = e.message ?: e.javaClass.simpleName
                TrimOutcome.FAILED
            } catch (e: OutOfMemoryError) {
                // Never again expected (the trim streams), but one song must not close the app mid-batch.
                trimError = "the song is too big to trim"
                TrimOutcome.FAILED
            }
        }
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
        // A cover only goes into the file's tags (there's no sidecar for it here).
        var coverOutcome = LyricsOutcome.NOT_ASKED
        var cover: CoverImage? = null
        if (addCover && canEmbed && (song.hasCover != true || replaceCover)) {
            val album = names.album.ifBlank { song.album.orEmpty() }
            coverOutcome = try {
                cover = coverFinder.find(releaseGroupId, album, artist)
                if (cover == null) LyricsOutcome.NOT_ONLINE else LyricsOutcome.ADDED
            } catch (e: IOException) {
                LyricsOutcome.OFFLINE
            } catch (e: SerializationException) {
                LyricsOutcome.OFFLINE
            }
        } else if (addCover && song.hasCover == true) {
            coverOutcome = LyricsOutcome.ALREADY_THERE
        }
        // Volume: ReplayGain tags from the song's measured loudness (measured now if it wasn't).
        var volumeOutcome = LyricsOutcome.NOT_ASKED
        var measured: Song = song
        var gain: Loudness? = null
        if (normalize && canEmbed) {
            measured = if (song.loudnessLufs == null || replaceReplayGain) measure(song) else song
            gain = measured.loudnessLufs?.let { Loudness(it, measured.truePeak ?: 1.0) }
            if (gain == null) volumeOutcome = LyricsOutcome.NOT_ONLINE
        } else if (normalize) {
            volumeOutcome = LyricsOutcome.NOT_ONLINE
        }
        var embedded: SongWriter.EmbedResult? = null
        // The file now, before its tags are written: a silence mark valid for it stays valid after
        // (tags don't change the audio) and moves to the written file's stamp.
        val stampBeforeTags = writer.stamp(uri)
        if (writeTags || ((lyrics != null || cover != null || gain != null) && canEmbed)) {
            val written = writer.writeTags(
                uri, song.extension, if (writeTags) names else NewNames("", "", "", ""), lyrics.takeIf { canEmbed }, cover = cover,
                replaceCover = replaceCover, replayGain = gain, replaceReplayGain = replaceReplayGain,
            )
            embedded = written.lyrics
            volumeOutcome = when (written.replayGain) {
                SongWriter.EmbedResult.ADDED -> LyricsOutcome.ADDED
                SongWriter.EmbedResult.KEPT_EXISTING -> LyricsOutcome.ALREADY_THERE
                SongWriter.EmbedResult.UNSUPPORTED -> LyricsOutcome.NOT_ONLINE
                null -> volumeOutcome
            }
            when (written.cover) {
                SongWriter.EmbedResult.KEPT_EXISTING -> coverOutcome = LyricsOutcome.ALREADY_THERE
                SongWriter.EmbedResult.UNSUPPORTED -> coverOutcome = LyricsOutcome.NOT_ONLINE
                else -> {}
            }
        }
        if (embedded == SongWriter.EmbedResult.KEPT_EXISTING) outcome = LyricsOutcome.ALREADY_THERE
        if (rename && names.artist.isNotBlank() && names.title.isNotBlank()) {
            val newName = SongWriter.fileName(names.artist, names.title, song.extension)
            uri = writer.rename(uri, fileName, newName)
            fileName = newName
        }
        if (lyrics != null && wantsLrcFile(embedded)) writer.writeLrc(uri, fileName, lyrics)
        runCatching { writer.notifyMediaStore(uri) }
        val best = song.best
        val sameAsBest = best != null && Text.same(best.title, names.title) && Text.same(best.artist, names.artist)
        val fixed = measured.copy(
            uri = uri.toString(),
            fileName = fileName,
            title = if (writeTags) names.title.ifBlank { song.title } else song.title,
            artist = if (writeTags) names.artist.ifBlank { song.artist } else song.artist,
            album = if (writeTags) names.album.ifBlank { song.album } else song.album,
            genre = if (writeTags) names.genre.ifBlank { song.genre } else song.genre,
            year = if (writeTags) names.year.trim().toIntOrNull() ?: song.year else song.year,
            yearRead = writeTags || song.yearRead,
            // Only lyrics or a cover added: the names weren't touched, so the status stays (an
            // unchecked song stays unchecked, one that was already right stays right).
            status = if (!writeTags && !rename) song.status else SongStatus.FIXED,
            lyricsFound = when (outcome) {
                LyricsOutcome.ADDED, LyricsOutcome.ALREADY_THERE -> true
                LyricsOutcome.NOT_ONLINE -> false
                else -> if (sameAsBest) song.lyricsFound else null
            },
            lyricsInFile = if (outcome == LyricsOutcome.ADDED || outcome == LyricsOutcome.ALREADY_THERE) true else song.lyricsInFile,
            hasCover = if (coverOutcome == LyricsOutcome.ADDED || coverOutcome == LyricsOutcome.ALREADY_THERE) true else song.hasCover,
            replayGainInFile = if (volumeOutcome == LyricsOutcome.ADDED || volumeOutcome == LyricsOutcome.ALREADY_THERE) true else measured.replayGainInFile,
            note = null,
        ).let { s ->
            // A failed trim is remembered with its reason: out of the batches (they'd fail on it again),
            // its silence still shown as measured, tried again from its sheet. Any other outcome clears it.
            if (trimOutcome == TrimOutcome.FAILED) return@let s.copy(trimError = trimError ?: "unknown error")
            val t = trimmed ?: return@let s
            s.copy(
                trimError = null,
                silenceStartMs = t.leadLeftMs,
                silenceEndMs = t.tailLeftMs,
                silenceChecked = true,
                durationMs = s.durationMs?.let { (it - t.startMs - t.endMs).coerceAtLeast(0) },
            )
        }.let { s ->
            when {
                volumeOutcome == LyricsOutcome.ADDED && gain != null -> s.copy(
                    existingTrackGain = ReplayGain.gainText(gain.trackGainDb),
                    existingTrackPeak = ReplayGain.peakText(gain.truePeak),
                    existingReference = ReplayGain.referenceText(),
                    gainVerdict = GainVerdict.STANDARD,
                )
                // Kept only because they follow the standard.
                volumeOutcome == LyricsOutcome.ALREADY_THERE -> s.copy(gainVerdict = GainVerdict.STANDARD)
                else -> s
            }
        }
        val stampAfter = writer.stamp(uri)
        val saved = fixed.copy(
            silenceMark = when {
                // The audio changed: where its sound is now is measured again when it's needed.
                trimOutcome == TrimOutcome.TRIMMED -> null
                else -> SilenceMark.decode(fixed.silenceMark)?.takeIf { it.matches(stampBeforeTags) && stampAfter != null }
                    ?.copy(stamp = stampAfter!!)?.encode() ?: fixed.silenceMark
            },
            originalPath = keptOriginal?.absolutePath ?: fixed.originalPath,
            originalSavedAt = if (keptOriginal != null) System.currentTimeMillis() else fixed.originalSavedAt,
        )
        dao.update(saved)
        return Applied(saved, outcome, coverOutcome, volumeOutcome, trimOutcome, trimmed, trimError)
    }

    /**
     * "Undo trim": puts the kept original back over the song (the file as it was before the trim,
     * its tags then included), and measures its silence again. Returns the song as saved.
     */
    suspend fun undoTrim(song: Song): Song {
        val original = File(song.originalPath ?: throw SongWriter.Failed("No original is kept for this song"))
        val uri = Uri.parse(song.uri)
        writer.restore(uri, original)
        original.delete()
        runCatching { writer.notifyMediaStore(uri) }
        val tags = runCatching { folder.readTags(uri) }.getOrNull()
        val restored = measureSilence(
            song.copy(
                originalPath = null, originalSavedAt = null, trimError = null,
                // Its tags are read again (they're the original's now).
                gainVerdict = null, durationMs = tags?.durationMs ?: song.durationMs,
            ),
        )
        dao.update(restored)
        return restored
    }

    /** How many originals are kept, and their size in bytes. */
    suspend fun originalsKept(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val kept = dao.withOriginals().mapNotNull { s -> s.originalPath?.let(::File)?.takeIf { it.exists() } }
        kept.size to kept.sumOf { it.length() }
    }

    /**
     * Drops kept originals older than [KEEP_ORIGINALS_MS], then the oldest past [MAX_ORIGINALS_BYTES]
     * in all, and files no song points to; with [all], every one.
     */
    suspend fun pruneOriginals(all: Boolean = false, now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val dir = originalsDir ?: return@withContext
        val kept = dao.withOriginals()
        val survivors = mutableSetOf<String>()
        var total = 0L
        for (s in kept.sortedByDescending { it.originalSavedAt ?: 0 }) {
            val f = File(s.originalPath!!)
            val fresh = now - (s.originalSavedAt ?: 0) < KEEP_ORIGINALS_MS
            if (!all && fresh && f.exists() && total + f.length() <= MAX_ORIGINALS_BYTES) {
                total += f.length()
                survivors += f.absolutePath
            } else {
                f.delete()
                dao.clearOriginal(s.id)
            }
        }
        dir.listFiles()?.filter { it.absolutePath !in survivors }?.forEach { it.delete() }
    }

    /**
     * Measures the next song not measured yet (in the background check), then reads the volume
     * tags it already has; false when none are left. Songs measured before only get their tags read
     * (and their silence measured, when that's new to them: their loudness stays as measured).
     * A failure is recorded too, so the song isn't tried again by itself.
     */
    suspend fun measureNext(): Boolean {
        val song = dao.nextUnmeasured() ?: return false
        measureSong(song)
        return true
    }

    /**
     * Measures [id] now (right after its names were checked, so it's complete in "To fix" while
     * the check goes on); nothing when it's measured already.
     */
    suspend fun measure(id: Long) {
        val song = dao.get(id) ?: return
        if (!song.loudnessChecked || !song.silenceChecked || song.gainVerdict == null || needsMark(song)) measureSong(song)
    }

    /** Measures what the song is missing and reads its volume tags; saves only those columns. */
    private suspend fun measureSong(song: Song) {
        val measured = withVolumeTags(
            when {
                !song.loudnessChecked -> measure(song)
                !song.silenceChecked || needsMark(song) -> measureSilence(song)
                else -> song
            },
        )
        with(measured) {
            dao.saveMeasurement(
                id, uri, loudnessLufs, trackGainDb, truePeak, loudnessChecked, silenceStartMs, silenceEndMs, silenceChecked,
                existingTrackGain, existingTrackPeak, existingReference, gainVerdict?.name, replayGainInFile, silenceMark,
            )
        }
    }

    /** The song with the silence at its ends filled in (not saved). */
    private suspend fun measureSilence(song: Song): Song = try {
        val stamp = writer.stamp(Uri.parse(song.uri))
        val s = loudness.silence(Uri.parse(song.uri))
        song.copy(
            silenceStartMs = if (s.silent) null else s.leadMs,
            silenceEndMs = if (s.silent) null else s.tailMs,
            silenceChecked = true,
            silenceMark = if (s.silent) null else stamp?.let { SilenceMark(s, it).encode() } ?: "",
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        song.copy(silenceStartMs = null, silenceEndMs = null, silenceChecked = true, silenceMark = null)
    }

    /** The song with the volume tags in its file and their verdict against its measurement (not saved). */
    private suspend fun withVolumeTags(song: Song): Song {
        val tags = try {
            writer.readVolumeTags(Uri.parse(song.uri), song.extension)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            VolumeTags()
        }
        return song.copy(
            existingTrackGain = tags.trackGain,
            existingTrackPeak = tags.trackPeak,
            existingReference = tags.reference,
            gainVerdict = ReplayGainCheck.verdict(tags, song.loudnessLufs),
            replayGainInFile = tags.trackGain != null,
        )
    }

    suspend fun unmeasuredCount() = dao.unmeasuredCount()

    /** The song with its loudness, true peak, track gain and silence at the ends filled in (not saved). One decode for all. */
    private suspend fun measure(song: Song): Song = try {
        val stamp = writer.stamp(Uri.parse(song.uri))
        val a = loudness.analyze(Uri.parse(song.uri))
        val l = a.loudness
        val quiet = a.silence.silent
        song.copy(
            loudnessLufs = l?.lufs, trackGainDb = l?.trackGainDb, truePeak = l?.truePeak, loudnessChecked = true,
            // A song with no sound at all: nothing to trim (it would cut the whole song).
            silenceStartMs = if (quiet) null else a.silence.leadMs,
            silenceEndMs = if (quiet) null else a.silence.tailMs,
            silenceChecked = true,
            silenceMark = if (quiet) null else stamp?.let { SilenceMark(a.silence, it).encode() } ?: "",
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        song.copy(
            loudnessLufs = null, trackGainDb = null, truePeak = null, loudnessChecked = true,
            silenceStartMs = null, silenceEndMs = null, silenceChecked = true,
        )
    }

    /** Cuts the silence off the song's ends only, names untouched. */
    suspend fun trimSilence(song: Song): Applied =
        apply(song, NewNames("", "", "", ""), writeTags = false, rename = false, addLyrics = false, trimSilence = true)

    /** Writes ReplayGain tags only, names untouched; existing tags stay if they follow the standard. */
    suspend fun normalize(song: Song): Applied =
        apply(song, NewNames("", "", "", ""), writeTags = false, rename = false, addLyrics = false, normalize = true)

    /** Measures the song again and replaces its ReplayGain tags ("Re-measure"). */
    suspend fun remeasure(song: Song): Applied =
        apply(song, NewNames("", "", "", ""), writeTags = false, rename = false, addLyrics = false, normalize = true, replaceReplayGain = true)

    /** Adds the album cover only, names untouched; with [replace], in place of the one in the file. */
    suspend fun addCover(song: Song, replace: Boolean = false): Applied =
        apply(
            song, NewNames("", "", "", ""), writeTags = false, rename = false, addLyrics = false, addCover = true,
            releaseGroupId = song.best?.releaseGroupId, replaceCover = replace,
        )

    /** The preview URL of the cover a match would add, for the song sheet (null when its album isn't known). */
    suspend fun coverPreview(releaseGroupId: String?, album: String?, artist: String?): String? =
        coverFinder.releaseGroup(releaseGroupId, album, artist)?.let(coverArt::previewUrl)

    /** Songs whose names are right and whose lyrics are online but not in the file yet. */
    fun needLyrics(songs: List<Song>): List<Song> = songs.filter(::needsLyrics)

    /** Adds lyrics only, names untouched. */
    suspend fun addLyrics(song: Song): Applied = apply(song, NewNames("", "", "", ""), writeTags = false, rename = false, addLyrics = true)

    /**
     * A .lrc file too: when the settings ask for one, or the lyrics couldn't go into the file's
     * tag (no tag written, or a format without a lyrics tag). Not when the file kept its own lyrics.
     */
    private fun wantsLrcFile(embedded: SongWriter.EmbedResult?) =
        embedded != SongWriter.EmbedResult.KEPT_EXISTING &&
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
            writer.writeTags(uri, song.extension, NewNames("", "", "", ""), text, replaceLyrics = true).lyrics
        } else {
            null
        }
        if (wantsLrcFile(embedded)) writer.writeLrc(uri, song.fileName, text)
        runCatching { writer.notifyMediaStore(uri) }
        val saved = song.copy(lyricsInFile = true, lyricsFound = true)
        dao.update(saved)
        return saved
    }

    /** Runs a [FixPlan] on the song. */
    suspend fun apply(song: Song, plan: FixPlan): Applied =
        apply(song, plan.names, plan.writeTags, plan.rename, plan.addLyrics, plan.addCover, plan.releaseGroupId, plan.normalize, trimSilence = plan.trimSilence)

    /** What a fix does: the names to write (blank = leave as is) and what else it changes. */
    data class FixPlan(
        val names: NewNames,
        val writeTags: Boolean,
        val rename: Boolean,
        val addLyrics: Boolean,
        val addCover: Boolean,
        val releaseGroupId: String?,
        val normalize: Boolean,
        val trimSilence: Boolean = false,
    )

    companion object {
        /** The song's names are right and only its year is wrong (missing or not the match's). */
        fun isYearOnly(song: Song): Boolean =
            song.status == SongStatus.SUGGESTED && song.extension in SongWriter.TAGGABLE &&
                Verdict.onlyYearWrong(song.title, song.artist, song.album, song.genre, song.year, song.candidates)

        /** The year [song] should have, when its names are right by a good match ([Verdict.correctYear]). */
        fun correctYear(song: Song): Int? = Verdict.correctYear(song.title, song.artist, song.year, song.candidates)

        /** Songs whose only problem is the year, for "Correct year in N songs". */
        fun yearOnly(songs: List<Song>): List<Song> = songs.filter(::isYearOnly)

        /**
         * A year-only fix: the year, and nothing else of the names, no rename. With [extras], the
         * lyrics, cover and volume tags the settings add on every fix come along (as in "Fix N sure ones").
         */
        fun yearOnlyPlan(song: Song, settings: SettingsState, extras: Boolean, writeTags: Boolean = true): FixPlan = FixPlan(
            names = NewNames("", "", "", "", correctYear(song)?.toString().orEmpty()),
            writeTags = writeTags && song.extension in SongWriter.TAGGABLE,
            rename = false,
            addLyrics = extras && settings.addLyrics,
            addCover = extras && settings.addCover,
            releaseGroupId = song.best?.releaseGroupId,
            normalize = extras && settings.normalize,
            trimSilence = extras && settings.trimSilence && song.hasSilenceToTrim,
        )

        /** How "Fix N sure ones" fixes [song] with its sure match [c]: the whole match, or only the year when that's all that's wrong. */
        fun surePlan(song: Song, c: Candidate, settings: SettingsState): FixPlan =
            if (isYearOnly(song)) {
                yearOnlyPlan(song, settings, extras = true, writeTags = settings.writeTags)
            } else {
                FixPlan(
                    NewNames(c.title, c.artist, c.album.orEmpty(), c.genre.orEmpty(), c.year?.toString().orEmpty()),
                    settings.writeTags && song.extension in SongWriter.TAGGABLE, settings.renameFiles, settings.addLyrics, settings.addCover,
                    c.releaseGroupId, settings.normalize, settings.trimSilence && song.hasSilenceToTrim,
                )
            }

        /** Songs measured, whose files can take ReplayGain tags and don't have standard ones yet. */
        fun needNormalizing(songs: List<Song>): List<Song> =
            songs.filter(::needsNormalizing)

        /** Named right, with lyrics online that aren't in the file yet. */
        fun needsLyrics(song: Song): Boolean =
            (song.status == SongStatus.OK || song.status == SongStatus.FIXED) && song.lyricsFound == true && song.lyricsInFile != true

        /** Measured, in a format that takes ReplayGain tags, without standard ones yet. */
        fun needsNormalizing(song: Song): Boolean =
            song.loudnessLufs != null && !song.volumeTagsStandard && song.extension in SongWriter.VOLUME_TAGGABLE

        /**
         * Something to do for this song, so it's in "To fix": a better name or year, or (as the
         * settings ask) volume tags, a silence trim or lyrics. Songs not checked yet aren't.
         */
        fun toFix(song: Song, settings: SettingsState): Boolean = when {
            song.status == SongStatus.NEW -> false
            song.status == SongStatus.SUGGESTED -> true
            settings.normalize && needsNormalizing(song) -> true
            settings.trimSilence && song.hasSilenceToTrim -> true
            else -> settings.addLyrics && needsLyrics(song)
        }

        /** Same as the database's NEEDS_MARK: silence to trim, no exact mark yet. */
        fun needsMark(song: Song): Boolean = song.silenceMark == null && song.trimError == null && song.silenceWorthTrimming

        /** Kept originals go after a week, and the oldest go past 1 GB in all. */
        const val KEEP_ORIGINALS_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_ORIGINALS_BYTES = 1L shl 30

        /** Songs measured with enough silence at an end to cut, in a format that can be cut. */
        fun needTrimming(songs: List<Song>): List<Song> = songs.filter { it.hasSilenceToTrim }

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

    /** Every song as saved now. */
    suspend fun allSongs() = dao.all()
}
