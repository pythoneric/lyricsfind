package com.biteric.songfixer.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.biteric.songfixer.data.Song
import com.biteric.songfixer.data.SongStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainViewModel = viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val songs by vm.songs.collectAsStateWithLifecycle()
    val check by vm.check.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val lyricsToAdd by vm.lyricsToAdd.collectAsStateWithLifecycle()
    val toNormalize by vm.toNormalize.collectAsStateWithLifecycle()
    val toCorrectYear by vm.toCorrectYear.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var openSongId by rememberSaveable { mutableStateOf<Long?>(null) }
    var lyricsDialog by rememberSaveable { mutableStateOf<LyricsDialog?>(null) }
    var browseSite by rememberSaveable { mutableStateOf(LyricsSite.GENIUS) }
    var pasteInitial by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            vm.folderPicked(uri)
            if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(message) {
        message?.let {
            // After a fix: offer to play the song in BitEric to check it.
            val fixed = vm.messageSongId?.let { id -> songs.firstOrNull { s -> s.id == id } }
            val result = snackbar.showSnackbar(it, actionLabel = if (fixed != null) "Play" else null, duration = androidx.compose.material3.SnackbarDuration.Long)
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed && fixed != null) {
                // The row may have changed (renamed): play the current file.
                songs.firstOrNull { s -> s.id == fixed.id }?.let { s -> playInBitEric(context, s) }
            }
            vm.messageShown()
        }
    }

    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(
            settings = settings,
            onBack = { showSettings = false },
            onChange = vm::updateSettings,
            onPickFolder = { pickFolder.launch(null) },
            onRecheckAll = { vm.recheckAll(); showSettings = false },
            onRetryFailed = { vm.retryFailed(); showSettings = false },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Song Fixer") },
                actions = {
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (settings.folderUri == null) {
                Welcome(onPick = { pickFolder.launch(null) }, hasKey = settings.acoustIdKey.isNotBlank(), onSettings = { showSettings = true })
            } else {
                SongList(
                    songs = songs,
                    check = check,
                    busy = busy,
                    hasKey = settings.acoustIdKey.isNotBlank(),
                    folderName = settings.folderUri?.let(::folderLabel).orEmpty(),
                    onStart = vm::startCheck,
                    onStop = vm::stopCheck,
                    onFixAllSure = vm::applyAllSure,
                    lyricsToAdd = lyricsToAdd,
                    onAddAllLyrics = vm::addLyricsToAll,
                    toNormalize = if (settings.normalize) toNormalize else 0,
                    onNormalizeAll = vm::normalizeAll,
                    toCorrectYear = toCorrectYear,
                    onCorrectYears = vm::correctYears,
                    onPlay = { playInBitEric(context, it) },
                    onOpen = { openSongId = it.id },
                    onSettings = { showSettings = true },
                )
            }
        }
    }

    val open = openSongId?.let { id -> songs.firstOrNull { it.id == id } }
    if (open != null) {
        SongSheet(
            song = open,
            settings = settings,
            busy = busy,
            onDismiss = { openSongId = null; lyricsDialog = null },
            onApply = { names, tags, rename, lyrics, cover, rgId, normalize -> vm.apply(open, names, tags, rename, lyrics, cover, rgId, normalize); openSongId = null },
            onNormalize = { vm.normalize(open); openSongId = null },
            onRemeasure = { vm.remeasure(open); openSongId = null },
            onAddLyrics = { vm.addLyrics(open); openSongId = null },
            onAddCover = { vm.addCover(open); openSongId = null },
            coverPreview = vm::coverPreview,
            onKeep = { vm.keep(open); openSongId = null },
            onRecheck = { vm.recheck(open); openSongId = null },
            onPlay = { playInBitEric(context, open) },
            onSearch = { q -> vm.search(open, q) },
            onFindLyrics = { lyricsDialog = LyricsDialog.FIND },
            onPasteLyrics = { pasteInitial = ""; lyricsDialog = LyricsDialog.PASTE },
            onBrowseLyrics = { site -> browseSite = site; lyricsDialog = LyricsDialog.BROWSE },
        )
        // Over the sheet; the sheet stays open and shows "In the file" once saved.
        when (lyricsDialog) {
            LyricsDialog.FIND -> {
                val search by vm.lyricsSearch.collectAsStateWithLifecycle()
                val close = { lyricsDialog = null; vm.clearLyricsSearch() }
                FindLyricsDialog(
                    song = open,
                    search = search,
                    onSearch = { q -> vm.searchLyrics(q, open.durationMs?.let { (it / 1000).toInt() }) },
                    onPick = { t -> vm.chooseLyrics(open, t); close() },
                    onDismiss = close,
                )
            }
            LyricsDialog.PASTE -> PasteLyricsDialog(
                song = open,
                onSave = { text -> vm.pasteLyrics(open, text); lyricsDialog = null },
                onDismiss = { lyricsDialog = null },
                initial = pasteInitial,
            )
            // Text taken from the site goes through the paste dialog to be checked before saving.
            LyricsDialog.BROWSE -> LyricsBrowser(
                song = open,
                site = browseSite,
                onText = { text -> pasteInitial = text; lyricsDialog = LyricsDialog.PASTE },
                onDismiss = { lyricsDialog = null },
            )
            null -> {}
        }
    }
}

private enum class LyricsDialog { FIND, PASTE, BROWSE }

/** "primary:Music/Downloads" → "Music/Downloads". */
fun folderLabel(uri: Uri): String =
    runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri).substringAfter(':').ifEmpty { "Internal storage" } }.getOrDefault(uri.toString())

@Composable
private fun Welcome(onPick: () -> Unit, hasKey: Boolean, onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Lyrics, contentDescription = null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Find the right name for every song", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "Lyrics apps look songs up by title and artist. Song Fixer listens to each song in a folder, " +
                "finds what it really is, and suggests the right title, artist, album and genre, so the lyrics can be found.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPick) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Choose music folder")
        }
        if (!hasKey) {
            Spacer(Modifier.height(16.dp))
            Text(
                "To recognize songs by their sound (needed for files with no name), add a free AcoustID key in Settings.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onSettings, Modifier.padding(top = 8.dp)) { Text("Add AcoustID key") }
        }
    }
}

@Composable
private fun SongList(
    songs: List<Song>,
    check: CheckState,
    busy: Boolean,
    hasKey: Boolean,
    folderName: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onFixAllSure: () -> Unit,
    lyricsToAdd: Int,
    toNormalize: Int,
    onNormalizeAll: () -> Unit,
    toCorrectYear: Int,
    onCorrectYears: () -> Unit,
    onAddAllLyrics: () -> Unit,
    onPlay: (Song) -> Unit,
    onOpen: (Song) -> Unit,
    onSettings: () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(SongFilter.TO_FIX) }
    val shown = songs.filter(filter::matches)
    val sure = songs.count { it.status == SongStatus.SUGGESTED && it.best?.sure == true }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(folderName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val counts = songs.groupingBy { it.status }.eachCount()
                    Text(
                        "${songs.size} songs · ${counts[SongStatus.SUGGESTED] ?: 0} to fix · " +
                            "${(counts[SongStatus.OK] ?: 0) + (counts[SongStatus.FIXED] ?: 0)} right · " +
                            "${(counts[SongStatus.NOT_FOUND] ?: 0) + (counts[SongStatus.FAILED] ?: 0)} not found",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (check.running) {
                        Text(
                            when {
                                check.total == 0 -> "Reading the folder…"
                                check.measuring -> "Measuring loudness ${check.done} of ${check.total}…"
                                else -> "Checking ${check.done} of ${check.total}…"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (check.total > 0) {
                            LinearProgressIndicator(progress = { check.done.toFloat() / check.total }, Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onStop) { Text("Stop") }
                    } else {
                        check.error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onStart) { Text(if (songs.isEmpty()) "Check songs" else "Check new songs") }
                            if (sure > 0) OutlinedButton(onClick = onFixAllSure, enabled = !busy) { Text("Fix $sure sure ones") }
                        }
                        if (toCorrectYear > 0) {
                            OutlinedButton(onClick = onCorrectYears, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) {
                                Icon(Icons.Default.CalendarMonth, contentDescription = null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (toCorrectYear == 1) "Correct year in 1 song" else "Correct year in $toCorrectYear songs")
                            }
                        }
                        if (toNormalize > 0) {
                            OutlinedButton(onClick = onNormalizeAll, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) {
                                Icon(Icons.Default.GraphicEq, contentDescription = null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (toNormalize == 1) "Normalize 1 song" else "Normalize $toNormalize songs")
                            }
                        }
                        if (lyricsToAdd > 0) {
                            OutlinedButton(onClick = onAddAllLyrics, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) {
                                Icon(Icons.Default.Lyrics, contentDescription = null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (lyricsToAdd == 1) "Add lyrics to 1 song" else "Add lyrics to $lyricsToAdd songs")
                            }
                        }
                    }
                    if (!hasKey) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Matching by name only. Add an AcoustID key to recognize songs by sound.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.clickable(onClick = onSettings),
                        )
                    }
                }
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SongFilter.entries) { f ->
                    FilterChip(
                        selected = f == filter,
                        onClick = { filter = f },
                        label = { Text("${f.label} (${songs.count(f::matches)})") },
                    )
                }
            }
        }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) }
        if (shown.isEmpty()) {
            item {
                Text(
                    when {
                        songs.isEmpty() && check.running -> ""
                        songs.isEmpty() -> "No songs checked yet."
                        filter == SongFilter.TO_FIX -> "Nothing to fix here."
                        else -> "No songs here."
                    },
                    Modifier.fillMaxWidth().padding(32.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(shown, key = { it.id }) { song ->
            SongRow(song, onClick = { onOpen(song) }, onPlay = { onPlay(song) })
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        }
    }
}

@Composable
private fun SongRow(song: Song, onClick: () -> Unit, onPlay: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        SongRowText(song, Modifier.weight(1f))
        // Fixed: play it in BitEric to check the names and lyrics.
        if (song.status == SongStatus.FIXED || song.lyricsInFile == true) {
            IconButton(onClick = onPlay) {
                Icon(Icons.Default.PlayCircle, contentDescription = "Play in BitEric", Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SongRowText(song: Song, modifier: Modifier) {
    Column(modifier) {
        Text(
            if (song.folder.isEmpty()) song.fileName else "${song.folder}/${song.fileName}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            currentNames(song),
            style = MaterialTheme.typography.bodyMedium,
            color = if (song.title.isNullOrBlank() || song.artist.isNullOrBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val best = song.best
        if (song.status == SongStatus.SUGGESTED && best != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(
                    "${best.title} · ${best.artist}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Wraps: "Volume tags not standard" next to the others doesn't fit on one line.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            statusLabel(song)?.let { SmallChip(it) }
            loudnessChip(song)?.let { SmallChip(it) }
            yearChip(song)?.let { SmallChip(it) }
            if (song.status == SongStatus.SUGGESTED && best != null) SmallChip(if (best.sure) "Sure match" else "Check it")
            when {
                song.lyricsInFile == true -> SmallChip("Lyrics in file", icon = true)
                else -> when (song.lyricsFound) {
                    true -> SmallChip("Lyrics online", icon = true)
                    false -> SmallChip("No lyrics online")
                    null -> {}
                }
            }
        }
    }
}

fun currentNames(song: Song): String {
    val t = song.title?.takeIf { it.isNotBlank() } ?: "No title"
    val a = song.artist?.takeIf { it.isNotBlank() } ?: "no artist"
    return if (song.status == SongStatus.NEW) "Not checked yet" else "$t · $a"
}

private fun statusLabel(song: Song): String? = when (song.status) {
    SongStatus.OK -> "Already right"
    SongStatus.FIXED -> "Fixed"
    SongStatus.KEPT -> "Kept as is"
    SongStatus.NOT_FOUND -> "Not found"
    SongStatus.FAILED -> "Check failed"
    else -> null
}

@Composable
private fun SmallChip(text: String, icon: Boolean = false) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.padding(top = 4.dp)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon) {
                Icon(Icons.Default.Check, contentDescription = null, Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}
