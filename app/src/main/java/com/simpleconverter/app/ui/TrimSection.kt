package com.simpleconverter.app.ui

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.simpleconverter.app.R
import com.simpleconverter.app.data.formatDuration
import com.simpleconverter.app.model.ConversionSettings
import com.simpleconverter.app.model.InputFile
import com.simpleconverter.app.model.MediaKind

/**
 * „Kürzen“: Vorschau (ExoPlayer), Bereichs-Schieberegler und Knöpfe, die die aktuelle
 * Abspielstelle als Anfang oder Ende übernehmen. Der Player läuft nur, solange der
 * Bereich aufgeklappt ist.
 */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrimSection(file: InputFile, settings: ConversionSettings, onTrim: (Long?, Long?) -> Unit) {
    val full = file.durationMs ?: return
    var expanded by rememberSaveable { mutableStateOf(settings.isTrimmed) }
    val start = settings.trimStartMs ?: 0L
    val end = settings.trimEndMs ?: full

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.trim_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (settings.isTrimmed) {
                        stringResource(R.string.trim_range, formatDuration(start), formatDuration(end), formatDuration(end - start))
                    } else {
                        stringResource(R.string.trim_full)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
        }

        AnimatedVisibility(expanded) {
            val context = LocalContext.current
            val player = remember(file.uri) {
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(file.uri))
                    prepare()
                    seekTo(start)
                }
            }
            DisposableEffect(player) { onDispose { player.release() } }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            if (file.kind == MediaKind.AUDIO) {
                                // Bei Audio gibt es kein Bild: Bedienleiste immer zeigen.
                                controllerShowTimeoutMs = 0
                                controllerHideOnTouch = false
                                showController()
                            }
                        }
                    },
                    onRelease = { it.player = null },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (file.kind == MediaKind.AUDIO) 120.dp else 240.dp),
                )
                RangeSlider(
                    value = start.toFloat()..end.toFloat(),
                    onValueChange = { range ->
                        val newStart = range.start.toLong()
                        val newEnd = range.endInclusive.toLong()
                        // Vorschau dorthin springen, wo gerade gezogen wird.
                        player.seekTo(if (newStart != start) newStart else newEnd)
                        onTrim(newStart, newEnd)
                    },
                    valueRange = 0f..full.toFloat(),
                    // Die Griffe liegen nah am Rand: ohne das deutet Android das Ziehen als Zurück-Geste.
                    modifier = Modifier.systemGestureExclusion(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onTrim(player.currentPosition.coerceAtMost(end - 1), settings.trimEndMs) }) {
                        Text(stringResource(R.string.trim_set_start))
                    }
                    OutlinedButton(onClick = { onTrim(settings.trimStartMs, player.currentPosition.coerceAtLeast(start + 1)) }) {
                        Text(stringResource(R.string.trim_set_end))
                    }
                    if (settings.isTrimmed) {
                        TextButton(onClick = { onTrim(null, null) }) { Text(stringResource(R.string.trim_reset)) }
                    }
                }
            }
        }
    }
}
