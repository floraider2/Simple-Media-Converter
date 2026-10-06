package com.simpleconverter.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simpleconverter.app.BuildConfig
import com.simpleconverter.app.R
import com.simpleconverter.app.data.ThemeMode
import com.simpleconverter.app.model.MediaKind
import com.simpleconverter.app.model.OutputFormat

private const val SOURCE_URL = "https://github.com/floraider2/Simple-Media-Converter"

@Composable
fun SettingsScreen(vm: ConverterViewModel, modifier: Modifier) {
    val settings by vm.appSettings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sdk = android.os.Build.VERSION.SDK_INT

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Heading(stringResource(R.string.settings_defaults))
        Hint(stringResource(R.string.settings_defaults_hint))
        FormatChoice(stringResource(R.string.settings_video), OutputFormat.targetsFor(MediaKind.VIDEO, sdkInt = sdk), settings.defaultVideo) { f ->
            vm.updateAppSettings { it.copy(defaultVideo = f) }
        }
        FormatChoice(stringResource(R.string.settings_audio), OutputFormat.targetsFor(MediaKind.AUDIO, sdkInt = sdk), settings.defaultAudio) { f ->
            vm.updateAppSettings { it.copy(defaultAudio = f) }
        }
        FormatChoice(stringResource(R.string.settings_image), OutputFormat.targetsFor(MediaKind.IMAGE, sdkInt = sdk), settings.defaultImage) { f ->
            vm.updateAppSettings { it.copy(defaultImage = f) }
        }

        HorizontalDivider()
        Heading(stringResource(R.string.folder_title))
        Hint(stringResource(R.string.folder_settings_hint))
        listOf(
            MediaKind.VIDEO to R.string.settings_video,
            MediaKind.AUDIO to R.string.settings_audio,
            MediaKind.IMAGE to R.string.settings_image,
        ).forEach { (kind, title) ->
            FolderRow(
                title = stringResource(title),
                folder = settings.folderFor(kind),
                kind = kind,
                onPick = { vm.chooseDefaultFolder(kind, it) },
            )
        }

        HorizontalDivider()
        Heading(stringResource(R.string.settings_privacy))
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = settings.keepMetadata, role = Role.Switch) { c -> vm.updateAppSettings { it.copy(keepMetadata = c) } }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_keep_metadata_default))
                Hint(stringResource(R.string.adv_keep_metadata_hint))
            }
            Switch(checked = settings.keepMetadata, onCheckedChange = null)
        }

        HorizontalDivider()
        Heading(stringResource(R.string.settings_appearance))
        Chips(
            listOf(
                ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
                ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
                ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
            ),
            settings.theme,
        ) { mode -> vm.updateAppSettings { it.copy(theme = mode) } }
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = settings.showThumbnails, role = Role.Switch) { c -> vm.updateAppSettings { it.copy(showThumbnails = c) } }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_thumbnails))
                Hint(stringResource(R.string.settings_thumbnails_hint))
            }
            Switch(checked = settings.showThumbnails, onCheckedChange = null)
        }

        HorizontalDivider()
        Heading(stringResource(R.string.settings_about))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
        Hint(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME))
        Text(stringResource(R.string.settings_offline))
        Text(stringResource(R.string.settings_license))
        Text(
            stringResource(R.string.settings_source),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, SOURCE_URL.toUri()))
                    } catch (e: ActivityNotFoundException) {
                        // Kein Browser installiert – nichts zu tun.
                    }
                }
                .padding(vertical = 4.dp),
        )
        Hint(stringResource(R.string.settings_third_party))
    }
}

@Composable
private fun FormatChoice(title: String, formats: List<OutputFormat>, selected: OutputFormat, onSelect: (OutputFormat) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Chips(formats.map { it to it.label }, selected, onSelect)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Chips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

@Composable
private fun Heading(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
