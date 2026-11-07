package com.biteric.songfixer.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.biteric.songfixer.loudness.GainVerdict
import kotlinx.serialization.Serializable

enum class SongStatus {
    /** Not checked yet (new or changed file). */
    NEW,
    /** Names already right. */
    OK,
    /** A better name was found. */
    SUGGESTED,
    /** Nothing found online. */
    NOT_FOUND,
    /** The user applied a suggestion. */
    FIXED,
    /** The user chose to keep it as it is. */
    KEPT,
    /** The check itself failed (unreadable file, network). */
    FAILED,
}

enum class MatchSource { SOUND, NAME }

/** A possible right name for a song. [score] is 0..1; [sure] means it can be applied without a look. */
@Serializable
data class Candidate(
    val title: String,
    val artist: String,
    val album: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val durationSec: Int? = null,
    val source: MatchSource,
    val score: Double,
    val sure: Boolean = false,
    val recordingId: String? = null,
    val artistId: String? = null,
    /** MusicBrainz release group of [album]: where its cover art is found. */
    val releaseGroupId: String? = null,
)

/** A music file in the chosen folder, its tags as they are, and what the check found. */
@Entity(tableName = "songs", indices = [Index(value = ["uri"], unique = true)])
data class Song(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** SAF document URI (from the folder tree). */
    val uri: String,
    val fileName: String,
    /** Folder path inside the chosen folder, "" at the top. */
    val folder: String,
    val size: Long,
    val modified: Long,
    val durationMs: Long? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    /** The year in the file's tags; null = none (or not read yet, see [yearRead]). */
    val year: Int? = null,
    /** [year] was read from the file (songs checked before years were kept get it read once). */
    val yearRead: Boolean = false,
    val status: SongStatus = SongStatus.NEW,
    /** Best first. */
    val candidates: List<Candidate> = emptyList(),
    /** LRCLIB has lyrics for the suggested name (or the current one when it's right); null = unknown. */
    val lyricsFound: Boolean? = null,
    val note: String? = null,
    /** The file (or its .lrc) has lyrics: added by the app or already there. Null = not known. */
    val lyricsInFile: Boolean? = null,
    /** The file has a cover picture. Null = not known. */
    val hasCover: Boolean? = null,
    /** Integrated loudness (BS.1770 / EBU R128), LUFS; null = not measured or silent. */
    val loudnessLufs: Double? = null,
    /** ReplayGain 2.0 track gain, dB: −18 − [loudnessLufs]. */
    val trackGainDb: Double? = null,
    /** True peak, linear (1.0 = full scale). */
    val truePeak: Double? = null,
    /** The loudness measurement ran (also when it failed), so it isn't tried again by itself. */
    val loudnessChecked: Boolean = false,
    /** The file has ReplayGain tags: written by the app or already there. Null = not known. */
    val replayGainInFile: Boolean? = null,
    /** The volume tags found in the file when it was last read (as written; null = not there). */
    val existingTrackGain: String? = null,
    val existingTrackPeak: String? = null,
    val existingReference: String? = null,
    /** What those tags are worth; null = not read yet. */
    val gainVerdict: GainVerdict? = null,
    /** Silence before the first sound / after the last one, ms; null = not measured. */
    val silenceStartMs: Long? = null,
    val silenceEndMs: Long? = null,
    /** The silence was measured (also when it failed), so it isn't tried again by itself. */
    val silenceChecked: Boolean = false,
    /**
     * Why the last try to cut its silence failed; null = it didn't. Such a song stays out of the
     * batches (they'd fail on it again) until it's tried again from its sheet or the file changes.
     */
    val trimError: String? = null,
    /** Exactly where its sound starts and ends, and the file it was measured on ([com.biteric.songfixer.loudness.SilenceMark]). */
    val silenceMark: String? = null,
    /** The untrimmed file kept for "Undo trim" (in the app's own storage), and when it was kept. */
    val originalPath: String? = null,
    val originalSavedAt: Long? = null,
) {
    /** Enough silence at an end to be worth cutting, in a format that can be cut. */
    val silenceWorthTrimming: Boolean
        get() = extension in com.biteric.songfixer.files.SilenceTrimmer.FORMATS &&
            com.biteric.songfixer.files.SilenceTrimmer.worthIt(silenceStartMs, silenceEndMs)

    /** Silence to cut, and the last try didn't fail: for "To fix", the chips and the batches. */
    val hasSilenceToTrim: Boolean get() = silenceWorthTrimming && trimError == null

    /**
     * The file's volume tags follow ReplayGain 2.0, so they're left alone. Before tags were read
     * (older databases), tags the app wrote itself count.
     */
    val volumeTagsStandard: Boolean get() = gainVerdict == GainVerdict.STANDARD || (gainVerdict == null && replayGainInFile == true)

    /** The file has volume tags that get replaced with standard ones. */
    val volumeTagsNotStandard: Boolean get() = gainVerdict?.notStandard == true

    val best: Candidate? get() = candidates.firstOrNull()
    val extension: String get() = fileName.substringAfterLast('.', "").lowercase()
}
