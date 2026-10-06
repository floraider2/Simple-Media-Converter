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
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simpleconverter.app.R
import com.simpleconverter.app.convert.Encoders
import com.simpleconverter.app.data.formatDuration
import com.simpleconverter.app.data.formatSize
import com.simpleconverter.app.model.ConversionSettings
import com.simpleconverter.app.model.FileResult
import com.simpleconverter.app.model.InputFile
import com.simpleconverter.app.model.MediaKind
import com.simpleconverter.app.model.OutputFormat
import com.simpleconverter.app.model.Presets
import com.simpleconverter.app.model.RecentItem
import com.simpleconverter.app.model.commonTargets
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
                title = { Text(if (screen is Screen.Home) stringResource(R.string.app_name) else titleFor(screen)) },
                actions = {
                    if (screen is Screen.Home) {
                        IconButton(onClick = vm::openSettings) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
                        }
                    }
                },
                navigationIcon = {
                    if (screen !is Screen.Home && screen !is Screen.Working) {
                        IconButton(onClick = vm::goHome) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
            Screen.Settings -> SettingsScreen(vm, modifier)
            Screen.Home -> HomeScreen(recents, loading, vm::openFiles, vm::clearRecents, modifier)
            is Screen.Setup -> SetupScreen(s, vm, modifier)
            is Screen.Working -> WorkingScreen(s, vm::cancelConversion, modifier)
            is Screen.Done -> DoneScreen(s, vm, modifier)
            is Screen.Failed -> FailedScreen(s, vm::backToSetup, vm::goHome, modifier)
        }
    }
}

@Composable
private fun titleFor(screen: Screen) = when (screen) {
    is Screen.Setup -> if (screen.files.size == 1) screen.files.first().name
    else pluralStringResource(R.plurals.n_files, screen.files.size, screen.files.size)
    is Screen.Working -> stringResource(R.string.title_working)
    is Screen.Done -> stringResource(R.string.title_done)
    is Screen.Failed -> stringResource(R.string.title_error)
    Screen.Settings -> stringResource(R.string.settings_title)
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
                    Text(stringResource(R.string.home_question), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.home_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = {
                            mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.home_pick_gallery)) }
                    FilledTonalButton(
                        onClick = { documentPicker.launch(arrayOf("video/*", "audio/*", "image/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.home_pick_file))
                    }
                    Text(
                        stringResource(R.string.home_multiple_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
        item {
            Text(
                stringResource(R.string.home_share_tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (recents.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.home_recent), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClearRecents) { Text(stringResource(R.string.home_clear)) }
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
                stringResource(if (s.files.size == 1) R.string.setup_no_audio_single else R.string.setup_no_audio_batch),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section(stringResource(R.string.setup_target_format)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                commonTargets(s.files).forEach { format ->
                    FilterChip(
                        selected = format == s.format,
                        onClick = { vm.selectFormat(format) },
                        label = { Text(if (first.kind == MediaKind.VIDEO && format.kind == MediaKind.AUDIO) stringResource(R.string.setup_audio_only, format.label) else format.label) },
                    )
                }
            }
        }

        Section(stringResource(R.string.setup_preset)) {
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
                            Text(stringResource(preset.label))
                            Text(
                                preset.descriptionArg?.let { stringResource(preset.description, it) }
                                    ?: stringResource(preset.description),
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
                        Text(stringResource(R.string.setup_custom))
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
                Text(stringResource(R.string.setup_advanced), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
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
                stringResource(if (s.settings.keepMetadata) R.string.setup_privacy_keep else R.string.setup_privacy_strip),
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
        Text(
            if (s.files.size == 1) stringResource(R.string.setup_convert)
            else pluralStringResource(R.plurals.setup_convert_n, s.files.size, s.files.size),
        )
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
                        stringResource(R.string.adv_codec),
                        listOf(false to stringResource(R.string.adv_h264), true to stringResource(R.string.adv_h265)),
                        settings.hevc,
                    ) { v -> update { it.copy(hevc = v ?: false) } }
                }
                ChipGroup(
                    stringResource(R.string.adv_resolution),
                    listOf(null to stringResource(R.string.original), 1080 to "1080p", 720 to "720p", 480 to "480p"),
                    settings.videoShortSide,
                ) { v -> update { it.copy(videoShortSide = v) } }
                if (allHaveDuration) {
                    val mb = 1024L * 1024L
                    ChipGroup(
                        stringResource(R.string.adv_target_size),
                        listOf(null to stringResource(R.string.off), 8 * mb to "< 8 MB", 16 * mb to "< 16 MB", 24 * mb to "< 25 MB", 50 * mb to "< 50 MB"),
                        settings.targetSizeBytes,
                    ) { v -> update { it.copy(targetSizeBytes = v) } }
                }
                if (settings.targetSizeBytes == null) {
                    ChipGroup(
                        stringResource(R.string.adv_video_bitrate),
                        listOf(700_000 to stringResource(R.string.adv_mbit_0_7), 2_000_000 to "2 Mbit/s", 5_000_000 to "5 Mbit/s", 12_000_000 to "12 Mbit/s"),
                        settings.videoBitrate,
                    ) { v -> update { it.copy(videoBitrate = v) } }
                }
                if (allHaveAudio) {
                    SwitchRow(stringResource(R.string.adv_remove_audio), null, settings.removeAudio) { c -> update { it.copy(removeAudio = c) } }
                    if (!settings.removeAudio) AudioBitrateChips(settings, update)
                }
            }
            OutputFormat.M4A, OutputFormat.MP3 -> AudioBitrateChips(settings, update)
            OutputFormat.OPUS -> ChipGroup(
                stringResource(R.string.adv_audio_bitrate),
                listOf(32_000 to "32k", 64_000 to "64k", 96_000 to "96k", 128_000 to "128k", 192_000 to "192k"),
                settings.audioBitrate,
            ) { v -> if (v != null) update { it.copy(audioBitrate = v) } }
            OutputFormat.JPG, OutputFormat.WEBP, OutputFormat.PNG -> {
                ChipGroup(
                    stringResource(R.string.adv_max_size),
                    listOf(null to stringResource(R.string.original), 3840 to "3840 px", 1920 to "1920 px", 1600 to "1600 px", 1280 to "1280 px"),
                    settings.imageMaxSide,
                ) { v -> update { it.copy(imageMaxSide = v) } }
                SwitchRow(
                    stringResource(R.string.adv_keep_metadata),
                    stringResource(R.string.adv_keep_metadata_hint),
                    settings.keepMetadata,
                ) { c -> update { it.copy(keepMetadata = c) } }
                if (format != OutputFormat.PNG) {
                    Text(stringResource(R.string.adv_quality, settings.imageQuality), style = MaterialTheme.typography.labelLarge)
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
        stringResource(R.string.adv_audio_bitrate),
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
                    Text(pluralStringResource(R.plurals.n_files, files.size, files.size), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.batch_total, formatSize(files.sumOf { it.size })),
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
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.batch_remove, file.name))
                    }
                }
            }
            if (files.size > 3) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) stringResource(R.string.batch_show_less) else stringResource(R.string.batch_show_all, files.size))
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
            Text(stringResource(R.string.working_file_n, s.index + 1, s.files.size), style = MaterialTheme.typography.titleMedium)
        }
        Text("${current.name} → ${s.format.label}", textAlign = TextAlign.Center)
        if (s.preparing) {
            Text(stringResource(R.string.working_preparing), style = MaterialTheme.typography.bodyMedium)
        }
        if (s.overall > 0) {
            LinearProgressIndicator(progress = { s.overall / 100f }, modifier = Modifier.fillMaxWidth())
            Text("${s.overall} %", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            stringResource(R.string.working_leave_hint),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
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
    val defaultName = stringResource(R.string.default_file_name)
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
                stringResource(R.string.done_bigger_hint),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { shareOutputs(context, listOf(r.outputUri!!), r.mimeType) }) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.share))
            }
            FilledTonalButton(onClick = { openOutput(context, r.outputUri!!, r.mimeType) }) { Text(stringResource(R.string.open)) }
        }
        OutlinedButton(onClick = { saveAs.launch(r.outputName ?: defaultName) }) { Text(stringResource(R.string.save_as)) }
        TextButton(onClick = onAnother) { Text(stringResource(R.string.another_file)) }
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
                    if (allOk) pluralStringResource(R.plurals.done_all, results.size, results.size)
                    else stringResource(R.string.done_some, ok.size, results.size),
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
                        Text(stringResource(R.string.share_all))
                    }
                    OutlinedButton(onClick = { pickFolder.launch(null) }) { Text(stringResource(R.string.save_all)) }
                }
                TextButton(onClick = onAnother) { Text(stringResource(R.string.another_file)) }
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
                    contentDescription = stringResource(if (r.ok) R.string.cd_done else R.string.cd_failed),
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
        Text(stringResource(R.string.failed_title), style = MaterialTheme.typography.titleLarge)
        Text(s.message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onRetry) { Text(stringResource(R.string.failed_retry)) }
        TextButton(onClick = onHome) { Text(stringResource(R.string.to_start)) }
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
    context.startActivity(Intent.createChooser(send, context.getString(R.string.share)))
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
        Toast.makeText(context, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Toast.makeText(context, R.string.file_gone, Toast.LENGTH_SHORT).show()
    }
}
