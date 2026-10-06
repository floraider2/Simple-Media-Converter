package com.simpleconverter.app.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.simpleconverter.app.R
import com.simpleconverter.app.data.formatSize
import com.simpleconverter.app.model.MediaKind
import com.simpleconverter.app.model.RecentItem
import com.simpleconverter.app.model.kindOfMime
import com.simpleconverter.app.model.matchesHistory

/** Ganzer Verlauf mit Suche, Filter nach Dateityp und Menü je Eintrag. */
@Composable
fun HistoryScreen(items: List<RecentItem>, onRemove: (RecentItem) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var kindName by rememberSaveable { mutableStateOf<String?>(null) }
    val kind = kindName?.let(MediaKind::valueOf)
    val shown = remember(items, query, kind) {
        items.filter { matchesHistory(it.outputName, it.inputName, it.mimeType, query, kind) }
    }

    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.history_search)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.history_clear_search))
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val filters = listOf(
                    null to R.string.history_all,
                    MediaKind.VIDEO to R.string.settings_video,
                    MediaKind.AUDIO to R.string.settings_audio,
                    MediaKind.IMAGE to R.string.settings_image,
                )
                items(filters) { (k, label) ->
                    FilterChip(selected = k == kind, onClick = { kindName = k?.name }, label = { Text(stringResource(label)) })
                }
            }
        }
        if (shown.isEmpty()) {
            item {
                Text(
                    stringResource(if (items.isEmpty()) R.string.history_empty else R.string.history_no_match),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(shown, key = { it.timestamp }) { item ->
            HistoryRow(
                item = item,
                onOpen = { openOutput(context, item.outputUri, item.mimeType) },
                onShare = { shareOutputs(context, listOf(item.outputUri), item.mimeType) },
                onRemove = { onRemove(item) },
            )
        }
        item { Spacer(Modifier.size(16.dp)) }
    }
}

/** „Gerade eben“ für die letzte Minute, sonst „vor 5 Minuten“, „gestern“ … */
@Composable
private fun whenText(timestamp: Long): String =
    if (System.currentTimeMillis() - timestamp < DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.history_just_now)
    else DateUtils.getRelativeTimeSpanString(timestamp).toString()

@Composable
fun HistoryRow(item: RecentItem, onOpen: () -> Unit, onShare: (() -> Unit)? = null, onRemove: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(item.outputUri, kindOfMime(item.mimeType))
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.outputName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatSize(item.inputSize)} → ${formatSize(item.outputSize)} · " + whenText(item.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onShare != null || onRemove != null) {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.history_more, item.outputName))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (onShare != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, onClick = { menu = false; onShare() })
                    }
                    if (onRemove != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.history_remove)) }, onClick = { menu = false; onRemove() })
                    }
                }
            }
        }
    }
}
