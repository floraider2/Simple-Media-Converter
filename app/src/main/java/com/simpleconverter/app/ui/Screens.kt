package com.simpleconverter.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simpleconverter.app.data.formatDuration
import com.simpleconverter.app.data.formatSize
import com.simpleconverter.app.convert.Encoders
import com.simpleconverter.app.model.ConversionSettings
import com.simpleconverter.app.model.FileResult
import com.simpleconverter.app.model.commonTargets
import com.simpleconverter.app.model.InputFile
import com.simpleconverter.app.model.MediaKind
import com.simpleconverter.app.model.OutputFormat
import com.simpleconverter.app.model.Presets
import com.simpleconverter.app.model.RecentItem
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConverterApp(vm: ConverterViewModel) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    BackHandler(enabled = screen !is Screen.Home) {
        when (screen) {
            is Screen.Working -> vm.cancelConversion()
            else -> vm.goHome()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (screen is Screen.Home) "Simple Converter" else titleFor(screen)) },
                navigationIcon = {
                    if (screen !is Screen.Home && screen !is Screen.Working) {
                        IconButton(onClick = vm::goHome) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(padding)
        when (val s = screen) {
            Screen.Home -> HomeScreen(recents, loading, vm::openFiles, vm::clearRecents, modifier)
            is Screen.Setup -> SetupScreen(s, vm, modifier)
            is Screen.Working -> WorkingScreen(s, vm::cancelConversion, modifier)
            is Screen.Done -> DoneScreen(s, vm, modifier)
            is Screen.Failed -> FailedScreen(s, vm::backToSetup, vm::goHome, modifier)
        }
    }
}

private fun titleFor(screen: Screen) = when (screen) {
    is Screen.Setup -> if (screen.files.size == 1) screen.files.first().name else "${screen.files.size} Dateien"
    is Screen.Working -> "Wird umgewandelt …"
    is Screen.Done -> "Fertig"
    is Screen.Failed -> "Fehler"
    Screen.Home -> ""
}

// ───────────────────────── Start ─────────────────────────

@Composable
private fun HomeScreen(
    recents: List<RecentItem>,
    loading: Boolean,
    onPick: (List<Uri>) -> Unit,
    onClearRecents: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_FILES)) { uris ->
        onPick(uris)
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        // Dauerhafte Leserechte: so kann die Umwandlung auch ohne offene App weiterlaufen.
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        onPick(uris)
    }

    LazyColumn(modifier = modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Was möchtest du umwandeln?", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Video, Audio oder Bild – alles bleibt auf deinem Gerät.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = {
                            mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("🖼️  Aus der Galerie wählen") }
                    FilledTonalButton(
                        onClick = { documentPicker.launch(arrayOf("video/*", "audio/*", "image/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Datei suchen (auch Musik)")
                    }
                    Text(
                        "Mehrere Dateien auf einmal? Einfach mehrere auswählen.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
        item {
            Text(
                "Tipp: Du kannst Dateien auch aus WhatsApp, der Galerie oder dem Dateimanager direkt an „Simple Converter“ teilen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (recents.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Zuletzt", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClearRecents) { Text("Leeren") }
                }
            }
            items(recents, key = { it.timestamp }) { item ->
                RecentRow(item) { openOutput(context, item.outputUri, item.mimeType) }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun RecentRow(item: RecentItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emojiForMime(item.mimeType), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.outputName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatSize(item.inputSize)} → ${formatSize(item.outputSize)} · " +
                    DateUtils.getRelativeTimeSpanString(item.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

// ───────────────────────── Einstellungen ─────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupScreen(s: Screen.Setup, vm: ConverterViewModel, modifier: Modifier) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    val presets = Presets.forFormat(s.format)

    // Der Knopf „Umwandeln“ bleibt unten fest stehen, nur die Einstellungen scrollen.
    Column(modifier) {
    Column(
        Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val first = s.files.first()
        if (s.files.size == 1) FileHeader(first) else BatchHeader(s.files, vm::removeFile)
        if (s.files.any { it.kind == MediaKind.VIDEO && !it.hasAudio }) {
            Text(
                if (s.files.size == 1) "Dieses Video hat keine Tonspur."
                else "Mindestens ein Video hat keine Tonspur, deshalb gibt es kein „Nur Ton“.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Zielformat") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                commonTargets(s.files).forEach { format ->
                    FilterChip(
                        selected = format == s.format,
                        onClick = { vm.selectFormat(format) },
                        label = { Text(if (first.kind == MediaKind.VIDEO && format.kind == MediaKind.AUDIO) "Nur Ton: ${format.label}" else format.label) },
                    )
                }
            }
        }

        Section("Vorgabe") {
            Column {
                presets.forEach { preset ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = preset.id == s.presetId,
                                onClick = { vm.selectPreset(preset.id) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = preset.id == s.presetId, onClick = null)
                        Spacer(Modifier.size(12.dp))
                        Column {
                            Text(preset.label)
                            Text(
                                preset.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (s.presetId == null) {
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = true, onClick = null)
                        Spacer(Modifier.size(12.dp))
                        Text("Eigene Einstellungen")
                    }
                }
            }
        }

        if (hasAdvanced(s.format)) {
            HorizontalDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { advanced = !advanced }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Erweitert", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(if (advanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
            }
            AnimatedVisibility(advanced) {
                AdvancedOptions(
                    format = s.format,
                    settings = s.settings,
                    allHaveDuration = s.files.all { it.durationMs != null },
                    allHaveAudio = s.files.all { it.hasAudio },
                    update = vm::updateSettings,
                )
            }
        }

        if (first.kind == MediaKind.IMAGE) {
            Text(
                if (s.settings.keepMetadata) "🔒 Der Standort wird entfernt, Kameradaten bleiben erhalten."
                else "🔒 Standort, Kameradaten und andere EXIF-Infos werden beim Umwandeln entfernt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

    }
    HorizontalDivider()
    Button(
        onClick = vm::startConversion,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .height(52.dp),
    ) {
        Text(if (s.files.size == 1) "Umwandeln" else "${s.files.size} Dateien umwandeln")
    }
    }
}

private fun hasAdvanced(format: OutputFormat) = format != OutputFormat.WAV && format != OutputFormat.FLAC

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdvancedOptions(
    format: OutputFormat,
    settings: ConversionSettings,
    allHaveDuration: Boolean,
    allHaveAudio: Boolean,
    update: ((ConversionSettings) -> ConversionSettings) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (format) {
            OutputFormat.MP4, OutputFormat.WEBM -> {
                if (format == OutputFormat.MP4 && Encoders.hevc) {
                    ChipGroup(
                        "Codec",
                        listOf(false to "H.264 (überall)", true to "H.265 (kleiner)"),
                        settings.hevc,
                    ) { v -> update { it.copy(hevc = v ?: false) } }
                }
                ChipGroup(
                    "Auflösung",
                    listOf(null to "Original", 1080 to "1080p", 720 to "720p", 480 to "480p"),
                    settings.videoShortSide,
                ) { v -> update { it.copy(videoShortSide = v) } }
                if (allHaveDuration) {
                    val mb = 1024L * 1024L
                    ChipGroup(
                        "Zielgröße",
                        listOf(null to "Aus", 8 * mb to "< 8 MB", 16 * mb to "< 16 MB", 24 * mb to "< 25 MB", 50 * mb to "< 50 MB"),
                        settings.targetSizeBytes,
                    ) { v -> update { it.copy(targetSizeBytes = v) } }
                }
                if (settings.targetSizeBytes == null) {
                    ChipGroup(
                        "Video-Bitrate",
                        listOf(700_000 to "0,7 Mbit/s", 2_000_000 to "2 Mbit/s", 5_000_000 to "5 Mbit/s", 12_000_000 to "12 Mbit/s"),
                        settings.videoBitrate,
                    ) { v -> update { it.copy(videoBitrate = v) } }
                }
                if (allHaveAudio) {
                    SwitchRow("Ton entfernen", null, settings.removeAudio) { c -> update { it.copy(removeAudio = c) } }
                    if (!settings.removeAudio) AudioBitrateChips(settings, update)
                }
            }
            OutputFormat.M4A, OutputFormat.MP3 -> AudioBitrateChips(settings, update)
            OutputFormat.OPUS -> ChipGroup(
                "Audio-Bitrate",
                listOf(32_000 to "32k", 64_000 to "64k", 96_000 to "96k", 128_000 to "128k", 192_000 to "192k"),
                settings.audioBitrate,
            ) { v -> if (v != null) update { it.copy(audioBitrate = v) } }
            OutputFormat.JPG, OutputFormat.WEBP, OutputFormat.PNG -> {
                ChipGroup(
                    "Maximale Größe (längste Seite)",
                    listOf(null to "Original", 3840 to "3840 px", 1920 to "1920 px", 1600 to "1600 px", 1280 to "1280 px"),
                    settings.imageMaxSide,
                ) { v -> update { it.copy(imageMaxSide = v) } }
                SwitchRow(
                    "Kameradaten behalten",
                    "Aufnahmezeit, Kamera, Belichtung – der Standort wird trotzdem entfernt",
                    settings.keepMetadata,
                ) { c -> update { it.copy(keepMetadata = c) } }
                if (format != OutputFormat.PNG) {
                    Text("Qualität: ${settings.imageQuality}", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = settings.imageQuality.toFloat(),
                        onValueChange = { v -> update { it.copy(imageQuality = v.roundToInt()) } },
                        valueRange = 30f..100f,
                    )
                }
            }
            OutputFormat.WAV, OutputFormat.FLAC -> Unit
        }
    }
}

/** Ganze Zeile antippbar, nicht nur der Schalter. */
@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun AudioBitrateChips(settings: ConversionSettings, update: ((ConversionSettings) -> ConversionSettings) -> Unit) {
    ChipGroup(
        "Audio-Bitrate",
        listOf(96_000 to "96k", 128_000 to "128k", 192_000 to "192k", 256_000 to "256k", 320_000 to "320k"),
        settings.audioBitrate,
    ) { v -> if (v != null) update { it.copy(audioBitrate = v) } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipGroup(title: String, options: List<Pair<T?, String>>, selected: T?, onSelect: (T?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
            }
        }
    }
}

@Composable
private fun FileHeader(file: InputFile) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emojiFor(file.kind), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.size(16.dp))
            Column {
                Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val details = listOfNotNull(
                    formatSize(file.size),
                    file.durationMs?.let(::formatDuration),
                    file.mimeType?.substringAfter('/')?.uppercase(),
                ).joinToString(" · ")
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BatchHeader(files: List<InputFile>, onRemove: (InputFile) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shown = if (expanded) files else files.take(3)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(emojiFor(files.first().kind), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.size(16.dp))
                Column {
                    Text("${files.size} Dateien", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "zusammen ${formatSize(files.sumOf { it.size })}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            shown.forEach { file ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${file.name} · ${formatSize(file.size)}",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onRemove(file) }) {
                        Icon(Icons.Default.Close, contentDescription = "${file.name} entfernen")
                    }
                }
            }
            if (files.size > 3) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Weniger anzeigen" else "Alle ${files.size} anzeigen")
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

// ───────────────────────── Fortschritt / Ergebnis ─────────────────────────

@Composable
private fun WorkingScreen(s: Screen.Working, onCancel: () -> Unit, modifier: Modifier) {
    Column(
        modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val current = s.files.getOrElse(s.index) { s.files.first() }
        Text(emojiFor(current.kind), style = MaterialTheme.typography.displayMedium)
        if (s.files.size > 1) {
            Text("Datei ${s.index + 1} von ${s.files.size}", style = MaterialTheme.typography.titleMedium)
        }
        Text("${current.name} → ${s.format.label}", textAlign = TextAlign.Center)
        if (s.preparing) {
            Text("Datei wird vorbereitet …", style = MaterialTheme.typography.bodyMedium)
        }
        if (s.overall > 0) {
            LinearProgressIndicator(progress = { s.overall / 100f }, modifier = Modifier.fillMaxWidth())
            Text("${s.overall} %", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            "Du kannst die App verlassen – der Fortschritt steht in der Benachrichtigung.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onCancel) { Text("Abbrechen") }
    }
}

@Composable
private fun DoneScreen(s: Screen.Done, vm: ConverterViewModel, modifier: Modifier) {
    val single = s.results.singleOrNull()
    when {
        single != null && single.ok -> SingleDone(single, vm::goHome, { vm.saveCopy(single, it) }, modifier)
        single != null -> FailedScreen(Screen.Failed(s.files, single.error ?: ""), vm::backToSetup, vm::goHome, modifier)
        else -> BatchDone(s.results, vm::goHome, { vm.saveAllTo(s.results, it) }, modifier)
    }
}

@Composable
private fun SingleDone(r: FileResult, onAnother: () -> Unit, onSaveAs: (Uri) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(r.mimeType)) { uri ->
        uri?.let(onSaveAs)
    }
    Column(
        modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
        Text(r.outputName ?: "", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text("${formatSize(r.inputSize)} → ${formatSize(r.outputSize)}", style = MaterialTheme.typography.titleMedium)
        if (r.outputSize > r.inputSize) {
            Text(
                "Die neue Datei ist größer als das Original – das Original war schon stark komprimiert. " +
                    "Für eine kleinere Datei probiere „Kleinste Datei“ oder ein anderes Format.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { shareOutputs(context, listOf(r.outputUri!!), r.mimeType) }) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Teilen")
            }
            FilledTonalButton(onClick = { openOutput(context, r.outputUri!!, r.mimeType) }) { Text("Öffnen") }
        }
        OutlinedButton(onClick = { saveAs.launch(r.outputName ?: "umgewandelt") }) { Text("Speichern unter …") }
        TextButton(onClick = onAnother) { Text("Noch eine Datei") }
    }
}

@Composable
private fun BatchDone(results: List<FileResult>, onAnother: () -> Unit, onSaveAll: (Uri) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(onSaveAll)
    }
    val ok = results.filter { it.ok }
    val allOk = ok.size == results.size
    LazyColumn(
        modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    if (allOk) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (allOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(56.dp),
                )
                Text(
                    if (allOk) "${results.size} Dateien fertig" else "${ok.size} von ${results.size} Dateien fertig",
                    style = MaterialTheme.typography.titleLarge,
                )
                if (ok.isNotEmpty()) {
                    Text(
                        "${formatSize(ok.sumOf { it.inputSize })} → ${formatSize(ok.sumOf { it.outputSize })}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Button(onClick = { shareOutputs(context, ok.map { it.outputUri!! }, ok.first().mimeType) }) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Alle teilen")
                    }
                    OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("Alle in Ordner speichern …") }
                }
                TextButton(onClick = onAnother) { Text("Noch eine Datei") }
            }
        }
        items(results) { r ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = r.ok) { openOutput(context, r.outputUri!!, r.mimeType) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(r.outputName ?: r.inputName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (r.ok) "${formatSize(r.inputSize)} → ${formatSize(r.outputSize)}" else r.error ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (r.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
                Icon(
                    if (r.ok) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = if (r.ok) "fertig" else "fehlgeschlagen",
                    tint = if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun FailedScreen(s: Screen.Failed, onRetry: () -> Unit, onHome: () -> Unit, modifier: Modifier) {
    Column(
        modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(72.dp))
        Text("Das hat nicht geklappt.", style = MaterialTheme.typography.titleLarge)
        Text(s.message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onRetry) { Text("Andere Einstellungen versuchen") }
        TextButton(onClick = onHome) { Text("Zum Start") }
    }
}

// ───────────────────────── Hilfen ─────────────────────────

private fun emojiFor(kind: MediaKind) = when (kind) {
    MediaKind.VIDEO -> "🎬"
    MediaKind.AUDIO -> "🎵"
    MediaKind.IMAGE -> "🖼️"
}

private fun emojiForMime(mime: String) = when {
    mime.startsWith("video/") -> "🎬"
    mime.startsWith("audio/") -> "🎵"
    else -> "🖼️"
}

private fun shareOutputs(context: Context, uris: List<Uri>, mime: String) {
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    send.setType(mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Teilen"))
}

/** Obergrenze für die Mehrfachauswahl im Photo Picker. */
private const val MAX_FILES = 100

private fun openOutput(context: Context, uri: Uri, mime: String) {
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(view)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Keine App zum Öffnen gefunden.", Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Toast.makeText(context, "Datei ist nicht mehr verfügbar.", Toast.LENGTH_SHORT).show()
    }
}
