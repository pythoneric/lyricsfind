package com.biteric.songfixer.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.biteric.songfixer.CheckWorker
import com.biteric.songfixer.SongFixerApp
import com.biteric.songfixer.data.SettingsState
import com.biteric.songfixer.data.Song
import com.biteric.songfixer.data.SongStatus
import com.biteric.songfixer.SongRepository
import com.biteric.songfixer.files.NewNames
import com.biteric.songfixer.files.SongWriter
import com.biteric.songfixer.match.LyricsFinder
import com.biteric.songfixer.online.LrclibTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The background check as the screen shows it. */
data class CheckState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val error: String? = null,
    /** Measuring loudness (after the names). */
    val measuring: Boolean = false,
)

/** [searched] = a search finished (so an empty [results] means nothing was found). */
data class LyricsSearch(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<LrclibTrack> = emptyList(),
    val searched: Boolean = false,
    val error: String? = null,
)

enum class SongFilter(val label: String) { TO_FIX("To fix"), NOT_FOUND("Not found"), DONE("Done"), ALL("All") }

fun SongFilter.matches(s: Song) = when (this) {
    SongFilter.TO_FIX -> s.status == SongStatus.SUGGESTED
    SongFilter.NOT_FOUND -> s.status == SongStatus.NOT_FOUND || s.status == SongStatus.FAILED
    SongFilter.DONE -> s.status == SongStatus.OK || s.status == SongStatus.FIXED || s.status == SongStatus.KEPT
    SongFilter.ALL -> true
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val songFixer = app as SongFixerApp
    private val repo = songFixer.repository
    private val settingsStore = songFixer.settings
    private val workManager = WorkManager.getInstance(app)

    val settings: StateFlow<SettingsState> = settingsStore.state
    val songs: StateFlow<List<Song>> = repo.songs.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Songs named right whose lyrics can be added. */
    val lyricsToAdd: StateFlow<Int> = repo.songs.map { repo.needLyrics(it).size }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val check: StateFlow<CheckState> = workManager.getWorkInfosForUniqueWorkFlow(CheckWorker.WORK_NAME)
        .map { infos ->
            val info = infos.firstOrNull() ?: return@map CheckState()
            val running = info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED
            CheckState(
                running = running,
                done = info.progress.getInt(CheckWorker.KEY_DONE, 0),
                total = info.progress.getInt(CheckWorker.KEY_TOTAL, 0),
                error = if (info.state == WorkInfo.State.FAILED) info.outputData.getString(CheckWorker.KEY_ERROR) else null,
                measuring = info.progress.getString(CheckWorker.KEY_PHASE) == CheckWorker.PHASE_LOUDNESS,
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CheckState())

    private val _message = MutableStateFlow<String?>(null)
    /** A one-off note for a snackbar. */
    val message: StateFlow<String?> = _message.asStateFlow()
    /** The song the current message is about, when it can be played to check it (after a fix). */
    var messageSongId: Long? = null
        private set

    fun messageShown() {
        _message.value = null
        messageSongId = null
    }

    private val _busy = MutableStateFlow(false)
    /** Fixing or searching right now. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun folderPicked(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val old = settings.value.folderUri
        viewModelScope.launch {
            if (old != null && old != uri) {
                CheckWorker.stop(getApplication())
                repo.forgetFolder()
                runCatching { resolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            }
            settingsStore.update { it.copy(folderUri = uri) }
            CheckWorker.start(getApplication())
        }
    }

    fun startCheck() = CheckWorker.start(getApplication())

    fun stopCheck() = CheckWorker.stop(getApplication())

    fun updateSettings(change: (SettingsState) -> SettingsState) = settingsStore.update(change)

    fun recheckAll() = viewModelScope.launch {
        repo.recheckAll()
        CheckWorker.start(getApplication())
    }

    fun retryFailed() = viewModelScope.launch {
        repo.retryFailed()
        CheckWorker.start(getApplication())
    }

    fun recheck(song: Song) = viewModelScope.launch {
        repo.recheck(song)
        CheckWorker.start(getApplication())
    }

    fun keep(song: Song) = viewModelScope.launch { repo.keep(song) }

    fun search(song: Song, query: String) = work("Search failed") {
        val found = repo.search(song, query)
        if (found.candidates.isEmpty()) _message.value = "Nothing found for \"$query\""
    }

    fun apply(
        song: Song,
        names: NewNames,
        writeTags: Boolean,
        rename: Boolean,
        addLyrics: Boolean,
        addCover: Boolean = false,
        releaseGroupId: String? = null,
        normalize: Boolean = false,
    ) = work("Couldn't fix ${song.fileName}") {
        // From the sheet, where both covers are shown: a ticked box means the new one is wanted.
        val done = repo.apply(song, names, writeTags, rename, addLyrics, addCover, releaseGroupId, normalize, replaceCover = addCover)
        val what = if (writeTags || rename) "Fixed: ${names.artist} - ${names.title}" else song.fileName
        messageSongId = done.song.id
        _message.value = what + when (done.lyrics) {
            SongRepository.LyricsOutcome.ADDED -> " · lyrics added"
            SongRepository.LyricsOutcome.ALREADY_THERE -> " · it already had lyrics"
            SongRepository.LyricsOutcome.NOT_ONLINE -> " · no lyrics online for it"
            SongRepository.LyricsOutcome.OFFLINE -> " · lyrics site unreachable, try again later"
            SongRepository.LyricsOutcome.NOT_ASKED -> ""
        } + when (done.cover) {
            SongRepository.LyricsOutcome.ADDED -> if (song.hasCover == true) " · cover replaced" else " · cover added"
            SongRepository.LyricsOutcome.NOT_ONLINE -> if (song.hasCover == true) " · no new cover found, kept the old one" else " · no cover found"
            SongRepository.LyricsOutcome.OFFLINE -> " · cover site unreachable"
            else -> ""
        } + volumeNote(done.volume)
    }

    private fun volumeNote(v: SongRepository.LyricsOutcome) = when (v) {
        SongRepository.LyricsOutcome.ADDED -> " · volume tagged"
        SongRepository.LyricsOutcome.ALREADY_THERE -> " · its volume tags already follow the standard"
        SongRepository.LyricsOutcome.NOT_ONLINE -> " · volume couldn't be measured"
        else -> ""
    }

    /** Songs measured without standard ReplayGain tags (none, or ones that get replaced). */
    val toNormalize: StateFlow<Int> = repo.songs.map { SongRepository.needNormalizing(it).size }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Writes ReplayGain tags into every measured song without standard ones. */
    fun normalizeAll() = work("Normalizing stopped") {
        var added = 0
        var kept = 0
        var failed = 0
        for (song in SongRepository.needNormalizing(songs.value)) {
            try {
                when (repo.normalize(song).volume) {
                    SongRepository.LyricsOutcome.ADDED -> added++
                    SongRepository.LyricsOutcome.ALREADY_THERE -> kept++
                    else -> failed++
                }
            } catch (e: Exception) {
                failed++
            }
        }
        _message.value = "Volume tagged in $added songs" + (if (kept > 0) ", $kept already standard" else "") + if (failed > 0) ", $failed couldn't be changed" else ""
    }

    fun normalize(song: Song) = work("Couldn't tag the volume") {
        val done = repo.normalize(song)
        messageSongId = done.song.id
        _message.value = song.fileName + volumeNote(done.volume)
    }

    /** Measures again and replaces the song's ReplayGain tags. */
    fun remeasure(song: Song) = work("Couldn't measure it") {
        val done = repo.remeasure(song)
        messageSongId = done.song.id
        _message.value = done.song.loudnessLufs?.let { "Measured ${loudnessText(it)}" + volumeNote(done.volume) } ?: "The song couldn't be measured"
    }

    /** "Add cover only" / "Replace cover only" in the sheet (the old and new covers are on screen). */
    fun addCover(song: Song) = work("Couldn't add the cover") {
        val done = repo.addCover(song, replace = true)
        messageSongId = done.song.id
        _message.value = when (done.cover) {
            SongRepository.LyricsOutcome.ADDED -> if (song.hasCover == true) "Cover replaced" else "Cover added"
            SongRepository.LyricsOutcome.ALREADY_THERE -> "This song already has a cover"
            SongRepository.LyricsOutcome.NOT_ONLINE -> if (song.hasCover == true) "No new cover found; the old one stays" else "No cover found for its album"
            else -> "The cover site can't be reached, try again later"
        }
    }

    /** The URL of a small preview of the cover a fix would add; null when the album isn't known. */
    suspend fun coverPreview(releaseGroupId: String?, album: String?, artist: String?): String? =
        runCatching { repo.coverPreview(releaseGroupId, album, artist) }.getOrNull()

    /** Applies every sure suggestion with the default options. */
    fun applyAllSure() = work("Fixing stopped") {
        val s = settings.value
        var fixed = 0
        var failed = 0
        var lyrics = 0
        for ((song, c) in repo.sureOnes(songs.value)) {
            try {
                val names = NewNames(c.title, c.artist, c.album.orEmpty(), c.genre.orEmpty(), c.year?.toString().orEmpty())
                val done = repo.apply(song, names, s.writeTags && song.extension in SongWriter.TAGGABLE, s.renameFiles, s.addLyrics, s.addCover, c.releaseGroupId, s.normalize)
                if (done.lyrics == SongRepository.LyricsOutcome.ADDED) lyrics++
                fixed++
            } catch (e: Exception) {
                failed++
            }
        }
        _message.value = "Fixed $fixed songs" + (if (s.addLyrics) ", lyrics added to $lyrics" else "") + if (failed > 0) ", $failed couldn't be changed" else ""
    }

    /** Adds lyrics to the songs that are named right but have none in the file yet. */
    fun addLyricsToAll() = work("Adding lyrics stopped") {
        var added = 0
        var failed = 0
        for (song in repo.needLyrics(songs.value)) {
            try {
                if (repo.addLyrics(song).lyrics == SongRepository.LyricsOutcome.ADDED) added++
            } catch (e: Exception) {
                failed++
            }
        }
        _message.value = "Lyrics added to $added songs" + if (failed > 0) ", $failed couldn't be changed" else ""
    }

    private val _lyricsSearch = MutableStateFlow(LyricsSearch())
    /** The "Find lyrics" dialog's search. */
    val lyricsSearch: StateFlow<LyricsSearch> = _lyricsSearch.asStateFlow()
    private var searchJob: kotlinx.coroutines.Job? = null

    fun searchLyrics(query: String, durationSec: Int?) {
        if (query.isBlank()) return
        searchJob?.cancel()
        _lyricsSearch.value = LyricsSearch(query = query, loading = true)
        searchJob = viewModelScope.launch {
            _lyricsSearch.value = try {
                LyricsSearch(query = query, results = repo.searchLyrics(query, durationSec), searched = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                LyricsSearch(query = query, error = "LRCLIB can't be reached right now. Try again later, or paste the lyrics.")
            }
        }
    }

    fun clearLyricsSearch() {
        searchJob?.cancel()
        _lyricsSearch.value = LyricsSearch()
    }

    /** Saves the lyrics of a search result the user picked. */
    fun chooseLyrics(song: Song, track: LrclibTrack) {
        val text = LyricsFinder.text(track) ?: return
        saveLyrics(song, text)
    }

    fun pasteLyrics(song: Song, text: String) = saveLyrics(song, text.trim())

    private fun saveLyrics(song: Song, text: String) = work("Couldn't save the lyrics") {
        repo.saveLyrics(song, text)
        _message.value = "Lyrics saved"
    }

    fun addLyrics(song: Song) = work("Couldn't add lyrics") {
        _message.value = when (repo.addLyrics(song).lyrics) {
            SongRepository.LyricsOutcome.ADDED -> "Lyrics added"
            SongRepository.LyricsOutcome.ALREADY_THERE -> "This song already has lyrics"
            SongRepository.LyricsOutcome.NOT_ONLINE -> "No lyrics online for this song"
            else -> "The lyrics site can't be reached, try again later"
        }
    }

    private fun work(failure: String, block: suspend () -> Unit) = viewModelScope.launch {
        _busy.value = true
        try {
            block()
        } catch (e: Exception) {
            _message.value = "$failure: ${e.message}"
        } finally {
            _busy.value = false
        }
    }
}
