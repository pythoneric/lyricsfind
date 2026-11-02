package com.biteric.songfixer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.biteric.songfixer.data.Song
import com.biteric.songfixer.match.LyricsFinder
import com.biteric.songfixer.online.LrclibTrack
import kotlin.math.roundToInt

/** Search LRCLIB by free text and pick the lyrics by hand. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindLyricsDialog(
    song: Song,
    search: LyricsSearch,
    onSearch: (String) -> Unit,
    onPick: (LrclibTrack) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember(song.id) { mutableStateOf(lyricsQuery(song)) }
    // Search right away with what the song seems to be called.
    LaunchedEffect(song.id) { onSearch(query) }
    val ourSec = song.durationMs?.let { (it / 1000).toInt() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (song.lyricsInFile == true) "Replace lyrics" else "Find lyrics") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") } },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    singleLine = true,
                    label = { Text("Artist and title") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
                    trailingIcon = { IconButton(onClick = { onSearch(query) }, enabled = query.isNotBlank()) { Icon(Icons.Default.Search, "Search") } },
                )
                Text(
                    "Pick the version that matches your song: the length difference helps.",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    search.loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    search.error != null -> Centered(search.error, error = true)
                    search.searched && search.results.isEmpty() -> Centered("Nothing found. Try other words, or paste the lyrics.")
                    else -> LazyColumn {
                        items(search.results, key = { it.id }) { t ->
                            ResultRow(t, ourSec, onClick = { onPick(t) })
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(t: LrclibTrack, ourSec: Int?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${t.trackName.orEmpty()} · ${t.artistName.orEmpty()}",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Badge(if (t.syncedLyrics.isNullOrBlank()) "Plain" else "Synced")
        }
        val length = t.duration?.let { d ->
            val text = formatDuration((d * 1000).toLong())
            if (ourSec == null) text else {
                val diff = (d - ourSec).roundToInt()
                text + when {
                    diff == 0 -> " (same length)"
                    diff > 0 -> " (+$diff s)"
                    else -> " ($diff s)"
                }
            }
        }
        Text(
            listOfNotNull(t.albumName?.takeIf { it.isNotBlank() }, length).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            preview(t),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun Badge(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
        Text(
            text,
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun Centered(text: String, error: Boolean = false) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(32.dp),
        textAlign = TextAlign.Center,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Type or paste lyrics copied from anywhere (plain text or LRC); [initial] = text taken from a lyrics site, to check first. */
@Composable
fun PasteLyricsDialog(song: Song, onSave: (String) -> Unit, onDismiss: () -> Unit, initial: String = "") {
    var text by remember(song.id, initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (song.lyricsInFile == true) "Replace lyrics" else if (initial.isNotEmpty()) "Check the lyrics" else "Paste lyrics") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (initial.isNotEmpty()) {
                        "Remove anything that isn't lyrics (ads, credits), then save."
                    } else {
                        "Paste the lyrics copied from any site. Synced lyrics in LRC form ([01:23.45] line) also work."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp),
                    placeholder = { Text("Lyrics") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "Artist Title" from the suggestion, else the tags, else the file name. */
fun lyricsQuery(song: Song): String {
    val t = song.best?.title ?: song.title
    val a = song.best?.artist ?: song.artist
    return if (!t.isNullOrBlank() && !a.isNullOrBlank()) "$a $t" else song.fileName.substringBeforeLast('.').replace('_', ' ')
}

private val timeTags = Regex("""\[\d{1,2}:\d{2}(?:[.:]\d{1,3})?]""")

/** The first two lines, without LRC time tags. */
private fun preview(t: LrclibTrack): String =
    LyricsFinder.text(t).orEmpty().lines()
        .map { it.replace(timeTags, "").trim() }
        .filter { it.isNotEmpty() }
        .take(2)
        .joinToString(" / ")
