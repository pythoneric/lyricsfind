package com.biteric.songfixer.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsState(
    val folderUri: Uri? = null,
    val acoustIdKey: String = "",
    val writeTags: Boolean = true,
    val renameFiles: Boolean = true,
    /** Put the lyrics from LRCLIB into the file's lyrics tag when fixing. */
    val addLyrics: Boolean = true,
    /** Also save them as a .lrc file next to the song (always done for formats without a lyrics tag). */
    val lrcFiles: Boolean = false,
    /** Put the album cover (Cover Art Archive) into the file when it has none. */
    val addCover: Boolean = true,
    /** Measure each song's loudness and write ReplayGain tags when fixing (volume normalization). */
    val normalize: Boolean = true,
    /** Find the silence at each song's start and end, and cut it off when fixing (for gapless mixing). */
    val trimSilence: Boolean = true,
)

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsState> = _state.asStateFlow()
    val current: SettingsState get() = _state.value

    private fun read() = SettingsState(
        folderUri = prefs.getString("folder", null)?.let(Uri::parse),
        acoustIdKey = prefs.getString("acoustid_key", "").orEmpty(),
        writeTags = prefs.getBoolean("write_tags", true),
        renameFiles = prefs.getBoolean("rename_files", true),
        addLyrics = prefs.getBoolean("add_lyrics", true),
        lrcFiles = prefs.getBoolean("lrc_files", false),
        addCover = prefs.getBoolean("add_cover", true),
        normalize = prefs.getBoolean("normalize", true),
        trimSilence = prefs.getBoolean("trim_silence", true),
    )

    fun update(change: (SettingsState) -> SettingsState) {
        val s = change(_state.value)
        prefs.edit()
            .putString("folder", s.folderUri?.toString())
            .putString("acoustid_key", s.acoustIdKey.trim())
            .putBoolean("write_tags", s.writeTags)
            .putBoolean("rename_files", s.renameFiles)
            .putBoolean("add_lyrics", s.addLyrics)
            .putBoolean("lrc_files", s.lrcFiles)
            .putBoolean("add_cover", s.addCover)
            .putBoolean("normalize", s.normalize)
            .putBoolean("trim_silence", s.trimSilence)
            .apply()
        _state.value = s.copy(acoustIdKey = s.acoustIdKey.trim())
    }
}
