package com.biteric.songfixer.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
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
) {
    val best: Candidate? get() = candidates.firstOrNull()
    val extension: String get() = fileName.substringAfterLast('.', "").lowercase()
}
