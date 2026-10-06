package com.simpleconverter.app.data

import android.content.Context
import com.simpleconverter.app.model.MediaKind
import com.simpleconverter.app.model.OutputFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Was die Nutzer unter „Einstellungen“ festlegen. */
data class AppSettings(
    val defaultVideo: OutputFormat = OutputFormat.MP4,
    val defaultAudio: OutputFormat = OutputFormat.MP3,
    val defaultImage: OutputFormat = OutputFormat.JPG,
    val keepMetadata: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Standard-Zielordner je Dateityp (Tree-URI); null = Filme/Musik/Bilder → SimpleConverter. */
    val folderVideo: String? = null,
    val folderAudio: String? = null,
    val folderImage: String? = null,
) {
    fun folderFor(kind: MediaKind) = when (kind) {
        MediaKind.VIDEO -> folderVideo
        MediaKind.AUDIO -> folderAudio
        MediaKind.IMAGE -> folderImage
    }

    fun withFolder(kind: MediaKind, folder: String?) = when (kind) {
        MediaKind.VIDEO -> copy(folderVideo = folder)
        MediaKind.AUDIO -> copy(folderAudio = folder)
        MediaKind.IMAGE -> copy(folderImage = folder)
    }

    fun defaultFor(kind: MediaKind) = when (kind) {
        MediaKind.VIDEO -> defaultVideo
        MediaKind.AUDIO -> defaultAudio
        MediaKind.IMAGE -> defaultImage
    }
}

/** Liest und speichert die Einstellungen (SharedPreferences) und meldet Änderungen als Flow. */
class SettingsStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putString(KEY_VIDEO, next.defaultVideo.name)
            .putString(KEY_AUDIO, next.defaultAudio.name)
            .putString(KEY_IMAGE, next.defaultImage.name)
            .putBoolean(KEY_KEEP_METADATA, next.keepMetadata)
            .putString(KEY_THEME, next.theme.name)
            .putString(KEY_FOLDER_VIDEO, next.folderVideo)
            .putString(KEY_FOLDER_AUDIO, next.folderAudio)
            .putString(KEY_FOLDER_IMAGE, next.folderImage)
            .apply()
        _settings.value = next
    }

    private fun load(): AppSettings {
        val defaults = AppSettings()
        fun format(key: String, fallback: OutputFormat, kind: MediaKind) =
            prefs.getString(key, null)
                ?.let { runCatching { OutputFormat.valueOf(it) }.getOrNull() }
                ?.takeIf { it.kind == kind || (kind == MediaKind.VIDEO && it.kind == MediaKind.AUDIO) }
                ?: fallback
        return AppSettings(
            defaultVideo = format(KEY_VIDEO, defaults.defaultVideo, MediaKind.VIDEO),
            defaultAudio = format(KEY_AUDIO, defaults.defaultAudio, MediaKind.AUDIO),
            defaultImage = format(KEY_IMAGE, defaults.defaultImage, MediaKind.IMAGE),
            keepMetadata = prefs.getBoolean(KEY_KEEP_METADATA, defaults.keepMetadata),
            theme = prefs.getString(KEY_THEME, null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: defaults.theme,
            folderVideo = prefs.getString(KEY_FOLDER_VIDEO, null),
            folderAudio = prefs.getString(KEY_FOLDER_AUDIO, null),
            folderImage = prefs.getString(KEY_FOLDER_IMAGE, null),
        )
    }

    companion object {
        private const val KEY_VIDEO = "defaultVideo"
        private const val KEY_AUDIO = "defaultAudio"
        private const val KEY_IMAGE = "defaultImage"
        private const val KEY_KEEP_METADATA = "keepMetadata"
        private const val KEY_THEME = "theme"
        private const val KEY_FOLDER_VIDEO = "folderVideo"
        private const val KEY_FOLDER_AUDIO = "folderAudio"
        private const val KEY_FOLDER_IMAGE = "folderImage"

        @Volatile private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) { instance ?: SettingsStore(context).also { instance = it } }
    }
}
