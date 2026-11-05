package com.biteric.songfixer.ui

import com.biteric.songfixer.data.Candidate
import com.biteric.songfixer.data.Song

/** What the sheet's Year box starts with: the chosen match's year, else the file's; "" when neither knows. */
fun startYear(candidate: Candidate?, song: Song): String = (candidate?.year ?: song.year)?.toString().orEmpty()

/** Why the Year box can't be written as it is ("Four digits, like 2019"); null when it's fine (blank = leave the file's). */
fun yearProblem(text: String, thisYear: Int = java.time.Year.now().value): String? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val y = t.toIntOrNull()
    return when {
        y == null || t.length != 4 -> "Four digits, like 2019"
        y < 1000 || y > thisYear + 1 -> "Not a year a song could be from"
        else -> null
    }
}

/** The list chip for a song whose year gets corrected: "Year 2021 → 2017", or "No year → 2017". */
fun yearChip(song: Song): String? {
    if (song.status != com.biteric.songfixer.data.SongStatus.SUGGESTED) return null
    val right = com.biteric.songfixer.SongRepository.correctYear(song) ?: return null
    return (song.year?.let { "Year $it" } ?: "No year") + " → $right"
}

/** What the sheet's Fix section starts with. */
data class FixStart(
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val year: String,
    val rename: Boolean,
    /** "Add cover art" ticked (ticked over a cover in the file replaces it). */
    val addCover: Boolean,
)

/**
 * The chosen [candidate]'s names (the file's where it has none), renamed and with a cover as the
 * settings say; but a song whose only problem is the year, on its best match, starts from the file's
 * own names, no rename and its cover kept (a cover is only added where there's none), so Fix changes
 * only the year (plus what the settings add to every fix).
 */
fun fixStart(song: Song, candidate: Candidate?, renameSetting: Boolean, coverSetting: Boolean): FixStart =
    if (candidate != null && candidate == song.best && com.biteric.songfixer.SongRepository.isYearOnly(song)) {
        FixStart(
            song.title.orEmpty(), song.artist.orEmpty(), song.album.orEmpty(), song.genre.orEmpty(), startYear(candidate, song),
            rename = false, addCover = coverSetting && song.hasCover != true,
        )
    } else {
        FixStart(
            candidate?.title ?: song.title.orEmpty(),
            candidate?.artist ?: song.artist.orEmpty(),
            candidate?.album ?: song.album.orEmpty(),
            candidate?.genre ?: song.genre.orEmpty(),
            startYear(candidate, song),
            renameSetting,
            coverSetting,
        )
    }
