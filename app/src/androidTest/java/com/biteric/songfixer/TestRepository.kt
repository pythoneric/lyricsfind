package com.biteric.songfixer

import android.content.Context
import com.biteric.songfixer.data.Settings
import com.biteric.songfixer.data.SongDatabase
import com.biteric.songfixer.files.MusicFolder
import com.biteric.songfixer.files.SongWriter
import com.biteric.songfixer.fingerprint.AudioFingerprinter
import com.biteric.songfixer.loudness.LoudnessAnalyzer
import com.biteric.songfixer.match.CoverFinder
import com.biteric.songfixer.match.Identifier
import com.biteric.songfixer.match.LyricsFinder
import com.biteric.songfixer.online.AcoustIdApi
import com.biteric.songfixer.online.CoverArtApi
import com.biteric.songfixer.online.LrclibApi
import com.biteric.songfixer.online.MusicBrainzApi

/** Nothing listens here: a service a test doesn't serve fails instead of reaching out. */
const val NOWHERE = "http://127.0.0.1:9"

/** The real repository on [db], with its online services at the given addresses (none by default). */
fun testRepository(context: Context, db: SongDatabase, coverArtUrl: String = NOWHERE, musicBrainzUrl: String = NOWHERE): SongRepository {
    val ua = "SongFixerTest"
    val lrclib = LrclibApi(NOWHERE, ua)
    val coverArt = CoverArtApi(coverArtUrl, ua)
    val musicBrainz = MusicBrainzApi(musicBrainzUrl, ua)
    return SongRepository(
        db.songs(), MusicFolder(context), AudioFingerprinter(context),
        Identifier(AcoustIdApi(NOWHERE, ua), musicBrainz, lrclib), SongWriter(context), Settings(context),
        LyricsFinder(lrclib), lrclib, CoverFinder(musicBrainz, coverArt), coverArt, LoudnessAnalyzer(context),
        java.io.File(context.filesDir, "originals-test"),
    )
}
