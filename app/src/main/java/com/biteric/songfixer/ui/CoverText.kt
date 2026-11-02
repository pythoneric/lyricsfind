package com.biteric.songfixer.ui

import com.biteric.songfixer.SongRepository.LyricsOutcome
import com.biteric.songfixer.data.Song
import com.biteric.songfixer.files.SongWriter

/** The line under "Add cover art": what ticking it does to this song's cover. */
fun coverDetail(song: Song, addCover: Boolean): String = when {
    song.extension !in SongWriter.TAGGABLE -> ".${song.extension} files can't hold a cover here"
    song.hasCover != true -> "The album's front cover, from the Cover Art Archive"
    addCover -> "The new cover replaces the one in the file"
    else -> "The file keeps its cover"
}

/** The sheet's cover-only button. */
fun coverOnlyLabel(song: Song): String = if (song.hasCover == true) "Replace cover only" else "Add cover only"

/** What a fix did to the cover, after the fix's message (" · cover replaced"); "" when nothing to say. [hadCover] = before the fix. */
fun coverNote(outcome: LyricsOutcome, hadCover: Boolean): String = when (outcome) {
    LyricsOutcome.ADDED -> if (hadCover) " · cover replaced" else " · cover added"
    LyricsOutcome.NOT_ONLINE -> if (hadCover) " · no new cover found, kept the old one" else " · no cover found"
    LyricsOutcome.OFFLINE -> " · cover site unreachable"
    else -> ""
}

/** The message after "Add cover only" / "Replace cover only". */
fun coverOnlyMessage(outcome: LyricsOutcome, hadCover: Boolean): String = when (outcome) {
    LyricsOutcome.ADDED -> if (hadCover) "Cover replaced" else "Cover added"
    LyricsOutcome.ALREADY_THERE -> "This song already has a cover"
    LyricsOutcome.NOT_ONLINE -> if (hadCover) "No new cover found; the old one stays" else "No cover found for its album"
    else -> "The cover site can't be reached, try again later"
}
