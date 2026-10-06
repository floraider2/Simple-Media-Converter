package com.simpleconverter.app.model

import android.net.Uri
import androidx.annotation.StringRes
import androidx.work.Data
import androidx.work.workDataOf
import com.simpleconverter.app.R

enum class MediaKind { VIDEO, AUDIO, IMAGE }

enum class OutputFormat(
    val label: String,
    val extension: String,
    val mimeType: String,
    val kind: MediaKind,
    /** Ab welcher Android-Version das Format erzeugt werden kann. */
    val minSdk: Int = 26,
) {
    MP4("MP4", "mp4", "video/mp4", MediaKind.VIDEO),
    WEBM("WebM", "webm", "video/webm", MediaKind.VIDEO, minSdk = 29),
    MP3("MP3", "mp3", "audio/mpeg", MediaKind.AUDIO),
    M4A("M4A (AAC)", "m4a", "audio/mp4", MediaKind.AUDIO),
    OPUS("Opus", "ogg", "audio/ogg", MediaKind.AUDIO, minSdk = 29),
    FLAC("FLAC", "flac", "audio/flac", MediaKind.AUDIO),
    WAV("WAV", "wav", "audio/wav", MediaKind.AUDIO),
    JPG("JPG", "jpg", "image/jpeg", MediaKind.IMAGE),
    PNG("PNG", "png", "image/png", MediaKind.IMAGE),
    WEBP("WebP", "webp", "image/webp", MediaKind.IMAGE);

    companion object {
        /** Zielformate für einen Medientyp; ohne Tonspur gibt es keine Audio-Ziele. */
        fun targetsFor(kind: MediaKind, hasAudio: Boolean = true, sdkInt: Int = Int.MAX_VALUE): List<OutputFormat> = when (kind) {
            MediaKind.VIDEO -> listOf(MP4, WEBM, MP3, M4A, OPUS, FLAC, WAV)
            MediaKind.AUDIO -> listOf(MP3, M4A, OPUS, FLAC, WAV)
            MediaKind.IMAGE -> listOf(JPG, PNG, WEBP)
        }.filter { (hasAudio || it.kind != MediaKind.AUDIO) && sdkInt >= it.minSdk }

        /** Formate, die in allen Listen vorkommen (Reihenfolge der ersten Liste). */
        fun intersect(lists: List<List<OutputFormat>>): List<OutputFormat> =
            lists.reduceOrNull { acc, t -> acc.filter { it in t } } ?: emptyList()
    }
}

data class InputFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String?,
    val kind: MediaKind,
    val durationMs: Long?,
    /** false bei Videos ohne Tonspur (z. B. Bildschirmaufnahmen). */
    val hasAudio: Boolean = true,
) {
    /** Zielformate, die für diese Datei Sinn ergeben. */
    fun targets(): List<OutputFormat> = OutputFormat.targetsFor(kind, hasAudio, android.os.Build.VERSION.SDK_INT)
}

/** Alle Stellschrauben einer Umwandlung. Die Vorgaben ([Preset]) befüllen sie nur. */
data class ConversionSettings(
    val format: OutputFormat,
    /** Video: kürzere Seite in Pixeln (720 = „720p“), null = Original. */
    val videoShortSide: Int? = null,
    /** Video: Bitrate in bit/s, null = Encoder entscheidet. */
    val videoBitrate: Int? = null,
    /** Video: Zielgröße der ganzen Datei, überschreibt [videoBitrate]. */
    val targetSizeBytes: Long? = null,
    val removeAudio: Boolean = false,
    val audioBitrate: Int = 192_000,
    /** Bild: längere Seite in Pixeln, null = Original. */
    val imageMaxSide: Int? = null,
    /** Bild: Qualität 1–100 (JPG/WebP). */
    val imageQuality: Int = 90,
    /** Video (MP4): H.265 statt H.264 – kleiner, aber nicht überall abspielbar. */
    val hevc: Boolean = false,
    /** Bild: Kameradaten und Aufnahmezeit behalten (Standort wird immer entfernt). */
    val keepMetadata: Boolean = false,
    /** Zielordner (Tree-URI aus der Ordnerauswahl); null = Standardordner (Filme/Musik/Bilder). */
    val outputFolder: String? = null,
    /** Kürzen (Video/Audio): Anfang und Ende in ms; null = vom Anfang bzw. bis zum Ende. */
    val trimStartMs: Long? = null,
    val trimEndMs: Long? = null,
    /** Lautstärke auf −14 LUFS angleichen (zwei Durchgänge: messen, dann umwandeln). */
    val normalizeLoudness: Boolean = false,
) {
    val isTrimmed get() = trimStartMs != null || trimEndMs != null

    /** Länge nach dem Kürzen; null, wenn die volle Länge unbekannt ist. */
    fun trimmedDurationMs(fullMs: Long?): Long? {
        if (fullMs == null) return null
        val end = (trimEndMs ?: fullMs).coerceAtMost(fullMs)
        val start = (trimStartMs ?: 0L).coerceIn(0L, end)
        return end - start
    }

    /** Was der Nutzer unabhängig von der Vorgabe gewählt hat, auf andere Einstellungen übertragen. */
    fun withUserChoicesFrom(other: ConversionSettings) =
        copy(
            keepMetadata = other.keepMetadata,
            trimStartMs = other.trimStartMs,
            trimEndMs = other.trimEndMs,
            normalizeLoudness = other.normalizeLoudness,
        )

    fun toData(): Data = workDataOf(
        "format" to format.name,
        "videoShortSide" to (videoShortSide ?: 0),
        "videoBitrate" to (videoBitrate ?: 0),
        "targetSizeBytes" to (targetSizeBytes ?: 0L),
        "removeAudio" to removeAudio,
        "audioBitrate" to audioBitrate,
        "imageMaxSide" to (imageMaxSide ?: 0),
        "imageQuality" to imageQuality,
        "hevc" to hevc,
        "keepMetadata" to keepMetadata,
        "outputFolder" to outputFolder,
        "trimStartMs" to (trimStartMs ?: -1L),
        "trimEndMs" to (trimEndMs ?: -1L),
        "normalizeLoudness" to normalizeLoudness,
    )

    companion object {
        fun fromData(d: Data) = ConversionSettings(
            format = OutputFormat.valueOf(d.getString("format")!!),
            videoShortSide = d.getInt("videoShortSide", 0).takeIf { it > 0 },
            videoBitrate = d.getInt("videoBitrate", 0).takeIf { it > 0 },
            targetSizeBytes = d.getLong("targetSizeBytes", 0L).takeIf { it > 0 },
            removeAudio = d.getBoolean("removeAudio", false),
            audioBitrate = d.getInt("audioBitrate", 192_000),
            imageMaxSide = d.getInt("imageMaxSide", 0).takeIf { it > 0 },
            imageQuality = d.getInt("imageQuality", 90),
            hevc = d.getBoolean("hevc", false),
            keepMetadata = d.getBoolean("keepMetadata", false),
            outputFolder = d.getString("outputFolder"),
            trimStartMs = d.getLong("trimStartMs", -1L).takeIf { it >= 0 },
            trimEndMs = d.getLong("trimEndMs", -1L).takeIf { it >= 0 },
            normalizeLoudness = d.getBoolean("normalizeLoudness", false),
        )
    }
}

/**
 * Eine Vorgabe in Alltagssprache statt Fachbegriffen.
 * Texte kommen aus den Ressourcen; [descriptionArg] füllt z. B. „%d kbit/s“.
 */
data class Preset(
    val id: String,
    @StringRes val label: Int,
    @StringRes val description: Int,
    val settings: ConversionSettings,
    val descriptionArg: Int? = null,
)

object Presets {
    private const val MB = 1024L * 1024L

    private fun kbits(id: String, @StringRes label: Int, format: OutputFormat, kbit: Int) =
        Preset(id, label, R.string.kbits, ConversionSettings(format, audioBitrate = kbit * 1000), descriptionArg = kbit)

    fun forFormat(format: OutputFormat): List<Preset> = when (format) {
        OutputFormat.MP4 -> listOf(
            Preset("whatsapp", R.string.preset_whatsapp, R.string.preset_whatsapp_desc,
                ConversionSettings(format, videoShortSide = 720, videoBitrate = 2_000_000, audioBitrate = 128_000)),
            Preset("email", R.string.preset_email, R.string.preset_email_desc,
                ConversionSettings(format, videoShortSide = 720, targetSizeBytes = 24 * MB, audioBitrate = 96_000)),
            Preset("smallest", R.string.preset_smallest, R.string.preset_480p_desc,
                ConversionSettings(format, videoShortSide = 480, videoBitrate = 700_000, audioBitrate = 96_000)),
            Preset("max", R.string.preset_max, R.string.preset_original_resolution,
                ConversionSettings(format, videoBitrate = 12_000_000, audioBitrate = 192_000)),
        )
        OutputFormat.WEBM -> listOf(
            Preset("standard", R.string.preset_standard, R.string.preset_webm_desc,
                ConversionSettings(format, videoShortSide = 720, videoBitrate = 1_500_000, audioBitrate = 96_000)),
            Preset("smallest", R.string.preset_smallest, R.string.preset_480p_desc,
                ConversionSettings(format, videoShortSide = 480, videoBitrate = 500_000, audioBitrate = 64_000)),
            Preset("max", R.string.preset_max, R.string.preset_original_resolution,
                ConversionSettings(format, videoBitrate = 8_000_000, audioBitrate = 128_000)),
        )
        OutputFormat.MP3 -> listOf(
            Preset("standard", R.string.preset_standard, R.string.preset_mp3_standard_desc,
                ConversionSettings(format, audioBitrate = 192_000)),
            kbits("high", R.string.preset_high, format, 320),
            kbits("small", R.string.preset_small, format, 128),
        )
        OutputFormat.OPUS -> listOf(
            Preset("standard", R.string.preset_standard, R.string.preset_opus_standard_desc,
                ConversionSettings(format, audioBitrate = 128_000)),
            kbits("high", R.string.preset_high, format, 192),
            Preset("speech", R.string.preset_speech, R.string.preset_speech_desc,
                ConversionSettings(format, audioBitrate = 32_000)),
        )
        OutputFormat.FLAC -> listOf(
            Preset("lossless", R.string.preset_lossless, R.string.preset_flac_desc, ConversionSettings(format)),
        )
        OutputFormat.M4A -> listOf(
            kbits("standard", R.string.preset_standard, format, 192),
            kbits("high", R.string.preset_high, format, 256),
            Preset("small", R.string.preset_small, R.string.preset_m4a_small_desc,
                ConversionSettings(format, audioBitrate = 96_000)),
        )
        OutputFormat.WAV -> listOf(
            Preset("lossless", R.string.preset_lossless, R.string.preset_wav_desc, ConversionSettings(format)),
        )
        OutputFormat.JPG, OutputFormat.WEBP -> listOf(
            Preset("standard", R.string.preset_standard, R.string.preset_image_standard_desc,
                ConversionSettings(format, imageQuality = 90)),
            Preset("messenger", R.string.preset_messenger, R.string.preset_image_messenger_desc,
                ConversionSettings(format, imageMaxSide = 1600, imageQuality = 80)),
            Preset("smallest", R.string.preset_smallest, R.string.preset_image_smallest_desc,
                ConversionSettings(format, imageMaxSide = 1280, imageQuality = 65)),
            Preset("max", R.string.preset_max, R.string.preset_image_max_desc,
                ConversionSettings(format, imageQuality = 100)),
        )
        OutputFormat.PNG -> listOf(
            Preset("standard", R.string.preset_original_size, R.string.preset_lossless,
                ConversionSettings(format)),
            Preset("messenger", R.string.preset_downscaled, R.string.preset_png_downscaled_desc,
                ConversionSettings(format, imageMaxSide = 1600)),
        )
    }
}

data class RecentItem(
    val inputName: String,
    val outputName: String,
    val outputUri: Uri,
    val mimeType: String,
    val inputSize: Long,
    val outputSize: Long,
    val timestamp: Long,
)

/** Dateiname des Ergebnisses: gleicher Name, neue Endung. */
fun outputFileName(inputName: String, format: OutputFormat): String {
    val base = inputName.substringBeforeLast('.', inputName).trim().trimStart('.').ifBlank { "converted" }
    return "$base.${format.extension}"
}

object Bitrate {
    const val MIN_VIDEO = 150_000
    const val MAX_VIDEO = 50_000_000

    /**
     * Video-Bitrate, damit die ganze Datei unter [targetBytes] bleibt.
     * 5 % Puffer für den Container, die Tonspur wird abgezogen.
     */
    fun videoForTargetSize(targetBytes: Long, durationMs: Long, audioBitrate: Int, withAudio: Boolean): Int {
        require(durationMs > 0) { "Dauer muss größer als 0 sein" }
        val seconds = durationMs / 1000.0
        val totalBits = targetBytes * 8 * 0.95
        val audioBits = if (withAudio) audioBitrate * seconds else 0.0
        val videoBitrate = ((totalBits - audioBits) / seconds).toLong()
        return videoBitrate.coerceIn(MIN_VIDEO.toLong(), MAX_VIDEO.toLong()).toInt()
    }

    /** Begrenzt die gewünschte Bitrate auf die des Originals (falls bekannt). */
    fun capToSource(requested: Int, sourceBitrate: Int?): Int {
        if (sourceBitrate == null || sourceBitrate <= 0) return requested
        return minOf(requested, maxOf(sourceBitrate, MIN_VIDEO))
    }
}

/** Zielformate, die für alle Dateien eines Stapels funktionieren (leer = Mischung passt nicht). */
fun commonTargets(files: List<InputFile>): List<OutputFormat> = OutputFormat.intersect(files.map { it.targets() })

/** Ergebnis einer Datei aus einem Stapel. Entweder [outputUri] oder [error] ist gesetzt. */
data class FileResult(
    val inputName: String,
    val inputSize: Long,
    val outputUri: Uri?,
    val outputName: String?,
    val outputSize: Long,
    val mimeType: String,
    val error: String?,
) {
    val ok get() = outputUri != null
}
