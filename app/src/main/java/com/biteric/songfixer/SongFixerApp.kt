package com.biteric.songfixer

import android.app.Application
import com.biteric.songfixer.data.Settings
import com.biteric.songfixer.data.SongDatabase
import com.biteric.songfixer.files.MusicFolder
import com.biteric.songfixer.files.SongWriter
import com.biteric.songfixer.fingerprint.AudioFingerprinter
import com.biteric.songfixer.match.CoverFinder
import com.biteric.songfixer.match.Identifier
import com.biteric.songfixer.online.CoverArtApi
import com.biteric.songfixer.match.LyricsFinder
import com.biteric.songfixer.online.AcoustIdApi
import com.biteric.songfixer.online.Http
import com.biteric.songfixer.online.LrclibApi
import com.biteric.songfixer.online.MusicBrainzApi

class SongFixerApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var repository: SongRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        // MusicBrainz asks every app to name itself with a contact.
        val userAgent = "SongFixer/${BuildConfig.VERSION_NAME} ( ${Http.USER_AGENT_CONTACT} )"
        val lrclib = LrclibApi(userAgent = userAgent)
        // One MusicBrainz client, so its one-request-a-second limit holds across lookups.
        val musicBrainz = MusicBrainzApi(userAgent = userAgent)
        val coverArt = CoverArtApi(userAgent = userAgent)
        val identifier = Identifier(
            AcoustIdApi(userAgent = userAgent),
            musicBrainz,
            lrclib,
        )
        repository = SongRepository(
            SongDatabase.create(this).songs(),
            MusicFolder(this),
            AudioFingerprinter(this),
            identifier,
            SongWriter(this),
            settings,
            LyricsFinder(lrclib),
            lrclib,
            CoverFinder(musicBrainz, coverArt),
            coverArt,
        )
    }
}
