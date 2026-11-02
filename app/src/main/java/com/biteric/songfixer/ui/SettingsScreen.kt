package com.biteric.songfixer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.biteric.songfixer.BuildConfig
import com.biteric.songfixer.data.SettingsState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsState,
    onBack: () -> Unit,
    onChange: ((SettingsState) -> SettingsState) -> Unit,
    onPickFolder: () -> Unit,
    onRecheckAll: () -> Unit,
    onRetryFailed: () -> Unit,
) {
    val uri = LocalUriHandler.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Heading("Music folder")
            Text(settings.folderUri?.let(::folderLabel) ?: "None chosen", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onPickFolder, Modifier.padding(top = 8.dp)) { Text(if (settings.folderUri == null) "Choose folder" else "Change folder") }

            Heading("Recognize by sound")
            Text(
                "Song Fixer recognizes songs by their sound with AcoustID, which needs a free key: sign in at acoustid.org, " +
                    "register an application (any name, e.g. \"My Song Fixer\") and paste its API key here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var key by remember { mutableStateOf(settings.acoustIdKey) }
            OutlinedTextField(
                value = key,
                onValueChange = {
                    key = it
                    onChange { s -> s.copy(acoustIdKey = it) }
                },
                label = { Text("AcoustID API key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            TextButton(onClick = { uri.openUri("https://acoustid.org/new-application") }) { Text("Get a free key at acoustid.org") }

            Heading("When fixing")
            SwitchRow("Write title, artist, album and genre into the file", settings.writeTags) { v -> onChange { it.copy(writeTags = v) } }
            SwitchRow("Rename the file to \"Artist - Title\"", settings.renameFiles) { v -> onChange { it.copy(renameFiles = v) } }
            SwitchRow("Add the lyrics (from LRCLIB, synced when available) into the file", settings.addLyrics) { v -> onChange { it.copy(addLyrics = v) } }
            SwitchRow("Also save lyrics as a .lrc file next to the song", settings.lrcFiles) { v -> onChange { it.copy(lrcFiles = v) } }
            Text(
                "Lyrics already in a file are never replaced. Formats without a lyrics tag (e.g. .opus) always get a .lrc file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Heading("Checking")
            OutlinedButton(onClick = onRetryFailed) { Text("Retry songs whose check failed") }
            OutlinedButton(onClick = onRecheckAll, Modifier.padding(top = 8.dp)) { Text("Check all songs again") }
            Text(
                "Songs you fixed or kept as they are aren't checked again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Heading("About")
            Text(
                "Song Fixer ${BuildConfig.VERSION_NAME}\n\n" +
                    "Song recognition: AcoustID (acoustid.org) and its Chromaprint fingerprint (MIT; resampler from FFmpeg, LGPL 2.1). " +
                    "Song data: MusicBrainz (musicbrainz.org, CC0). Lyrics check: LRCLIB (lrclib.net). " +
                    "Tag writing: jaudiotagger (LGPL 2.1).\n\n" +
                    "Song Fixer sends each song's fingerprint and length to AcoustID, and song names to MusicBrainz and LRCLIB. " +
                    "It never uploads your music files.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Heading(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
