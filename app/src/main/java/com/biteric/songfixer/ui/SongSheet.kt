package com.biteric.songfixer.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.then
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldLabelPosition
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.biteric.songfixer.data.Candidate
import com.biteric.songfixer.data.MatchSource
import com.biteric.songfixer.data.SettingsState
import com.biteric.songfixer.data.Song
import com.biteric.songfixer.data.SongStatus
import com.biteric.songfixer.files.NewNames
import com.biteric.songfixer.files.SongWriter
import com.biteric.songfixer.files.SilenceTrimmer
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongSheet(
    song: Song,
    settings: SettingsState,
    busy: Boolean,
    onDismiss: () -> Unit,
    onApply: (NewNames, writeTags: Boolean, rename: Boolean, addLyrics: Boolean, addCover: Boolean, releaseGroupId: String?, normalize: Boolean, trimSilence: Boolean) -> Unit,
    onNormalize: () -> Unit,
    onTrimSilence: () -> Unit,
    onRemeasure: () -> Unit,
    onAddLyrics: () -> Unit,
    onAddCover: () -> Unit,
    /** The preview URL of the cover a fix would add (release group, album, artist). */
    coverPreview: suspend (String?, String, String) -> String?,
    onFindLyrics: () -> Unit,
    onPasteLyrics: () -> Unit,
    onBrowseLyrics: (LyricsSite) -> Unit,
    onPlay: () -> Unit,
    onKeep: () -> Unit,
    onRecheck: () -> Unit,
    onSearch: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(song.fileName, style = MaterialTheme.typography.titleMedium)
            // Fixed: listen to it in BitEric and see the names and lyrics there.
            if (song.status == SongStatus.FIXED || song.lyricsInFile == true) {
                Button(onClick = onPlay, modifier = Modifier.padding(vertical = 6.dp)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Play in BitEric")
                }
            }
            Text(
                listOfNotNull(song.folder.ifEmpty { null }, song.durationMs?.let(::formatDuration)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            Section("Now")
            Field("Title", song.title)
            Field("Artist", song.artist)
            Field("Album", song.album)
            Field("Genre", song.genre)
            Field("Year", song.year?.toString() ?: if (!song.yearRead && song.status != SongStatus.NEW) "not read yet" else null)
            Field(
                "Volume",
                loudnessSummary(song)
                    ?: if (song.loudnessChecked) "couldn't be measured" else "not measured yet",
            )
            Field(
                "Silence",
                silenceSummary(song)
                    ?: if (song.silenceChecked) "couldn't be measured" else "not measured yet",
            )
            song.note?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            // Which suggestion the editable fields start from; reset when the list changes (a new search).
            var chosen by remember(song.candidates) { mutableIntStateOf(0) }
            val candidate = song.candidates.getOrNull(chosen)
            if (song.candidates.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Section(if (song.status == SongStatus.NOT_FOUND) "Possible matches (weak)" else "Suggestions")
                song.candidates.forEachIndexed { i, c ->
                    CandidateRow(c, selected = i == chosen, onClick = { chosen = i })
                }
            }

            Spacer(Modifier.height(16.dp))
            Section("Lyrics")
            Text(
                when {
                    song.lyricsInFile == true -> "In the file."
                    song.lyricsFound == true -> "LRCLIB has them under this name; they're added when you fix the song."
                    song.lyricsFound == false -> "LRCLIB has nothing under this exact name. Search it by other words, or paste them."
                    else -> "Not looked up yet (or LRCLIB couldn't be reached)."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val replace = song.lyricsInFile == true
                TextButton(onClick = onFindLyrics, enabled = !busy) { Text(if (replace) "Replace from search…" else "Find lyrics…") }
                TextButton(onClick = onPasteLyrics, enabled = !busy) { Text(if (replace) "Replace by pasting" else "Paste lyrics") }
            }
            // Not found automatically: look the song up on the big lyrics sites, by hand.
            if (song.lyricsInFile != true && song.lyricsFound != true) {
                Text("Look it up on", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LyricsSite.entries.forEach { site ->
                        OutlinedButton(onClick = { onBrowseLyrics(site) }, enabled = !busy) { Text(site.label) }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Section("Search by name")
            var query by remember(song.id) { mutableStateOf(searchSeed(song)) }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Artist - Title") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onSearch(query) }),
                trailingIcon = {
                    IconButton(onClick = { onSearch(query) }, enabled = query.isNotBlank() && !busy) { Icon(Icons.Default.Search, "Search") }
                },
            )

            Spacer(Modifier.height(16.dp))
            Section("Fix")
            // Start from the chosen suggestion (keeping what the file has where it has nothing), or, when only
            // the year is wrong, from the file's own names: then Fix changes the year alone.
            val start = remember(song.id, candidate) { fixStart(song, candidate, settings.renameFiles, settings.addCover) }
            val titleField = remember(start) { TextFieldState(start.title) }
            val artistField = remember(start) { TextFieldState(start.artist) }
            val albumField = remember(start) { TextFieldState(start.album) }
            val genreField = remember(start) { TextFieldState(start.genre) }
            val yearField = remember(start) { TextFieldState(start.year) }
            val title = titleField.text.toString()
            val artist = artistField.text.toString()
            val album = albumField.text.toString()
            val genre = genreField.text.toString()
            val year = yearField.text.toString()
            val yearProblem = yearProblem(year)
            var writeTags by remember(song.id) { mutableStateOf(settings.writeTags && song.extension in SongWriter.TAGGABLE) }
            var rename by remember(start) { mutableStateOf(start.rename) }
            Edit("Title", titleField)
            Edit("Artist", artistField)
            Edit("Album", albumField)
            Edit("Genre", genreField)
            Edit(
                "Year", yearField,
                problem = yearProblem,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                // Digits only, at most four.
                inputTransformation = InputTransformation.maxLength(4).then {
                    if (!asCharSequence().all(Char::isDigit)) revertAllChanges()
                },
            )
            CheckRow(
                "Write tags into the file",
                if (song.extension in SongWriter.TAGGABLE) null else ".${song.extension} files can't be tagged here",
                writeTags,
                enabled = song.extension in SongWriter.TAGGABLE,
            ) { writeTags = it }
            val newName = SongWriter.fileName(artist, title, song.extension)
            CheckRow("Rename file", newName.takeIf { artist.isNotBlank() && title.isNotBlank() }, rename) { rename = it }
            var addLyrics by remember(song.id) { mutableStateOf(settings.addLyrics && song.lyricsInFile != true) }
            CheckRow(
                "Add lyrics",
                when {
                    song.lyricsInFile == true -> "This song has lyrics already"
                    song.extension !in SongWriter.TAGGABLE || settings.lrcFiles -> "From LRCLIB, as ${SongWriter.lrcName(if (rename) newName else song.fileName)}"
                    else -> "From LRCLIB, into the file (synced when available)"
                },
                addLyrics,
            ) { addLyrics = it }
            // Cover: the one in the file now next to the album's front cover (from the chosen match's
            // album, or a search by album name). Ticked = the new one goes in, also over an old one.
            val canCover = song.extension in SongWriter.TAGGABLE
            var addCover by remember(start) { mutableStateOf(start.addCover && canCover) }
            CheckRow(
                "Add cover art",
                coverDetail(song, addCover),
                addCover,
                enabled = canCover,
            ) { addCover = it }
            if (canCover) {
                val rgId = candidate?.releaseGroupId?.takeIf { candidate.album == album }
                Row(
                    Modifier.padding(start = 48.dp, top = 4.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ChoosableCover("Now", selected = !addCover, onClick = { addCover = false }) {
                        FileCover(song.uri, key = song.hasCover)
                    }
                    ChoosableCover("New", selected = addCover, onClick = { addCover = true }) {
                        CoverPreview(key = listOf(rgId, album, artist), findUrl = {
                            // Typing in the album box: wait for a pause before searching.
                            if (rgId == null) kotlinx.coroutines.delay(700)
                            coverPreview(rgId, album, artist)
                        })
                    }
                }
            }

            // Volume: ReplayGain tags; players that read them (BitEric does) level the song. The audio isn't changed.
            val canNormalize = song.extension in SongWriter.VOLUME_TAGGABLE
            var normalize by remember(song.id) { mutableStateOf(normalizeByDefault(song, settings.normalize)) }
            CheckRow(
                "Normalize volume",
                when {
                    !canNormalize -> ".${song.extension} files can't hold volume tags here"
                    song.volumeTagsStandard -> "Volume tags already follow the standard"
                    song.volumeTagsNotStandard -> volumeTagsNote(song)!!
                    song.trackGainDb != null -> "ReplayGain tags: ${String.format(java.util.Locale.US, "%+.1f dB", song.trackGainDb)} to reach −18 LUFS; the audio isn't changed"
                    else -> "Measures it, then adds ReplayGain tags; the audio isn't changed"
                },
                normalize,
                enabled = canNormalize && !song.volumeTagsStandard,
            ) { normalize = it }

            // Silence at the ends: cut off (the audio in between isn't re-encoded), so the song mixes without a gap.
            val canTrim = song.extension in SilenceTrimmer.FORMATS
            var trim by remember(song.id) { mutableStateOf(settings.trimSilence && song.hasSilenceToTrim) }
            CheckRow(
                "Trim silence",
                when {
                    !canTrim -> ".${song.extension} files can't be trimmed here (MP3, FLAC and WAV can)"
                    song.hasSilenceToTrim ->
                        "Cuts its ${silenceAtEnds(song.silenceStartMs ?: 0, song.silenceEndMs ?: 0)}, so it mixes without a gap; nothing is re-encoded"
                    song.silenceChecked -> "No silence worth cutting at either end"
                    else -> "Finds the silence at the start and end and cuts it; nothing is re-encoded"
                },
                trim,
                enabled = canTrim && (song.hasSilenceToTrim || !song.silenceChecked),
            ) { trim = it }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        val rgId = candidate?.releaseGroupId?.takeIf { candidate.album == album }
                        onApply(NewNames(title, artist, album, genre, year.trim()), writeTags, rename, addLyrics, addCover, rgId, normalize, trim)
                    },
                    enabled = !busy && title.isNotBlank() && artist.isNotBlank() && yearProblem == null && (writeTags || rename || addLyrics || addCover || normalize || trim),
                ) { Text("Fix") }
                if (song.status != SongStatus.KEPT && song.status != SongStatus.FIXED) TextButton(onClick = onKeep) { Text("Keep as is") }
                TextButton(onClick = onRecheck) { Text("Check again") }
            }
            // Names already right: just the lyrics, nothing else touched.
            if ((song.status == SongStatus.OK || song.status == SongStatus.FIXED || song.status == SongStatus.KEPT) && song.lyricsInFile != true) {
                TextButton(onClick = onAddLyrics, enabled = !busy) { Text("Add lyrics only") }
            }
            if ((song.status == SongStatus.OK || song.status == SongStatus.FIXED || song.status == SongStatus.KEPT) &&
                song.extension in SongWriter.TAGGABLE
            ) {
                TextButton(onClick = onAddCover, enabled = !busy) { Text(coverOnlyLabel(song)) }
            }
            if (song.extension in SongWriter.VOLUME_TAGGABLE) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!song.volumeTagsStandard && song.status != SongStatus.NEW) {
                        TextButton(onClick = onNormalize, enabled = !busy) { Text("Normalize volume only") }
                    }
                    // Measures again and replaces volume tags, the app's or another program's.
                    TextButton(onClick = onRemeasure, enabled = !busy) { Text("Re-measure") }
                }
            }
            if (song.hasSilenceToTrim && song.status != SongStatus.NEW) {
                TextButton(onClick = onTrimSilence, enabled = !busy) { Text("Trim silence only") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** What the search box starts with: what the song seems to be called. */
private fun searchSeed(song: Song): String {
    val t = song.best?.title ?: song.title
    val a = song.best?.artist ?: song.artist
    return if (!t.isNullOrBlank() && !a.isNullOrBlank()) "$a - $t" else song.fileName.substringBeforeLast('.').replace('_', ' ')
}

@Composable
private fun CandidateRow(c: Candidate, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text("${c.title} · ${c.artist}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            val how = if (c.source == MatchSource.SOUND) "Sound match" else "Name match"
            Text(
                listOfNotNull(c.album, c.year?.toString(), c.genre, c.durationSec?.let { formatDuration(it * 1000L) }, "$how ${(c.score * 100).roundToInt()}%")
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
}

@Composable
private fun Field(label: String, value: String?) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (value.isNullOrBlank()) {
            Text("empty", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        } else {
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}


@Composable
private fun Edit(
    label: String,
    state: TextFieldState,
    problem: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    inputTransformation: InputTransformation? = null,
) {
    OutlinedTextField(
        state,
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        labelPosition = FIELD_LABEL,
        label = { Text(label) },
        isError = problem != null,
        supportingText = problem?.let { { Text(it) } },
        keyboardOptions = keyboardOptions,
        inputTransformation = inputTransformation,
        lineLimits = TextFieldLineLimits.SingleLine,
    )
}

/** Field names stay on the border, also over an empty field (like a missing year), so every field reads alike. */
private val FIELD_LABEL = TextFieldLabelPosition.Attached(alwaysMinimize = true)

/** A cover to pick, with its label under it; the picked one is outlined. */
@Composable
private fun ChoosableCover(label: String, selected: Boolean, onClick: () -> Unit, cover: @Composable () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier
                .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, shape)
                .padding(4.dp)
                .clickable(onClick = onClick),
        ) { cover() }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun CheckRow(text: String, detail: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Column {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
