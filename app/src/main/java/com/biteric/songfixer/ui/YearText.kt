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
