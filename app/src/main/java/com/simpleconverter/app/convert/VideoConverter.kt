package com.simpleconverter.app.convert

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.simpleconverter.app.convert.audio.GainAudioProcessor
import com.simpleconverter.app.convert.audio.Loudness
import com.simpleconverter.app.convert.wmv.AsfAssetLoader
import com.simpleconverter.app.convert.wmv.AsfDecoder
import com.simpleconverter.app.data.FileInspector
import com.simpleconverter.app.model.Bitrate
import com.simpleconverter.app.model.ConversionSettings
import com.simpleconverter.app.model.OutputFormat
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Video → MP4 (H.264 oder H.265 + AAC), Video → WebM (VP9 + Opus) und Video/Audio → M4A (AAC)
 * über Media3 Transformer. Nutzt die Hardware-Encoder des Geräts.
 *
 * HDR-Videos (z. B. von neueren Handys) werden immer nach SDR umgerechnet: Sonst wirken sie
 * auf vielen Geräten und in Messengern blass oder falsch.
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
object VideoConverter {

    suspend fun convert(
        context: Context,
        input: Uri,
        output: File,
        settings: ConversionSettings,
        durationMs: Long?,
        onProgress: (Int) -> Unit,
        gainDb: Double = 0.0,
    ) {
        if (settings.passthrough) {
            // „Original behalten“: kopieren. Geht das nicht (exotischer Codec o. Ä.), in bester Qualität umwandeln.
            try {
                return export(context, input, output, settings, durationMs, onProgress, 0.0)
            } catch (e: ExportException) {
                output.delete()
                return export(context, input, output, settings.copy(passthrough = false, videoBitrate = null), durationMs, onProgress, gainDb)
            }
        }
        export(context, input, output, settings, durationMs, onProgress, gainDb)
    }

    /**
     * Kann Media3 die Datei nicht lesen (z. B. WMV), noch einmal mit dem Leser und den Decodern von Android –
     * das klappt, wenn das Gerät passende Decoder hat (siehe [FrameworkAssetLoader]).
     */
    private suspend fun export(
        context: Context,
        input: Uri,
        output: File,
        settings: ConversionSettings,
        durationMs: Long?,
        onProgress: (Int) -> Unit,
        gainDb: Double,
    ) {
        try {
            exportOnce(context, input, output, settings, durationMs, onProgress, gainDb, frameworkReader = false)
        } catch (e: ExportException) {
            if (generateSequence<Throwable>(e) { it.cause }.none { it is UnrecognizedInputFormatException }) throw e
            output.delete()
            exportOnce(context, input, output, settings, durationMs, onProgress, gainDb, frameworkReader = true)
        }
    }

    private suspend fun exportOnce(
        context: Context,
        input: Uri,
        output: File,
        settings: ConversionSettings,
        durationMs: Long?,
        onProgress: (Int) -> Unit,
        gainDb: Double,
        frameworkReader: Boolean,
    ) {
        val copy = settings.passthrough
        val audioOnly = settings.format == OutputFormat.M4A
        val removeAudio = !audioOnly && settings.removeAudio

        // WMV/WMA liest FFmpeg (Android kann es meist nicht) – auch die Eckdaten kommen von dort.
        val asf = if (AsfDecoder.isAsf(context, input)) AsfDecoder.open(context, input).use { it.info } else null
        val probe = if (asf != null) Probe(asf.width, asf.height, null).takeIf { asf.hasVideo } else probe(context, input)
        val videoOnly = if (audioOnly || copy) Effects.EMPTY else videoEffects(probe, settings.videoShortSide)
        val effects = if (gainDb == 0.0 || removeAudio) videoOnly
        else Effects(listOf(GainAudioProcessor(Loudness.linear(gainDb))), videoOnly.videoEffects)
        val mediaItem = MediaItem.Builder()
            .setUri(input)
            .apply {
                if (settings.isTrimmed) {
                    setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(settings.trimStartMs ?: 0L)
                            .apply { settings.trimEndMs?.let { setEndPositionMs(it) } }
                            .build()
                    )
                }
            }
            .build()
        val edited = EditedMediaItem.Builder(mediaItem)
            .setRemoveVideo(audioOnly)
            .setRemoveAudio(removeAudio)
            .setEffects(effects)
            .build()
        // Welche Spuren die Ausgabe hat, muss Media3 vorab wissen.
        val tracks = if (asf != null) null else FileInspector.trackMimes(context, input)
        val trackTypes = buildSet {
            val hasVideo = asf?.hasVideo ?: (tracks == null || tracks.video != null)
            val hasAudio = asf?.hasAudio ?: (tracks == null || tracks.audio != null)
            if (!audioOnly && hasVideo) add(C.TRACK_TYPE_VIDEO)
            if (!removeAudio && hasAudio) add(C.TRACK_TYPE_AUDIO)
        }
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(trackTypes).addItem(edited).build())
            .apply {
                if (copy) {
                    // Spuren nur umpacken, nicht dekodieren/kodieren.
                    setTransmuxAudio(true)
                    setTransmuxVideo(!audioOnly)
                } else {
                    setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                }
            }
            .build()
        val webm = settings.format == OutputFormat.WEBM
        val videoMime = when {
            webm -> MimeTypes.VIDEO_VP9
            settings.hevc -> MimeTypes.VIDEO_H265
            else -> MimeTypes.VIDEO_H264
        }

        // Nie mehr Bitrate als das Original – sonst wird die Datei beim „Verkleinern“ größer.
        // Zielgröße bezieht sich auf die gekürzte Länge.
        val videoBitrate = (targetVideoBitrate(settings, settings.trimmedDurationMs(durationMs), removeAudio) ?: settings.videoBitrate)
            ?.let { Bitrate.capToSource(it, probe?.bitrate) }
        // Beim Kopieren keine Encoder-Wünsche angeben: Media3 (ab 1.6) kodiert sonst trotz Transmux neu.
        val encoderFactory = DefaultEncoderFactory.Builder(context.applicationContext)
            .setEnableFallback(true)
            .apply {
                if (!copy) {
                    setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(settings.audioBitrate).build())
                    if (videoBitrate != null) {
                        setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(videoBitrate).build())
                    }
                }
            }
            .build()

        // Transformer muss auf einem Thread mit Looper laufen.
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val handler = Handler(Looper.getMainLooper())
                val holder = ProgressHolder()
                lateinit var transformer: Transformer
                val poll = object : Runnable {
                    override fun run() {
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(holder.progress)
                        }
                        handler.postDelayed(this, 500)
                    }
                }
                transformer = Transformer.Builder(context.applicationContext)
                    .apply {
                        when {
                            asf != null -> setAssetLoaderFactory(AsfAssetLoader.Factory(context))
                            frameworkReader -> setAssetLoaderFactory(FrameworkAssetLoader.Factory(context))
                        }
                    }
                    .apply {
                        // Beim Kopieren die Formate der Quelle behalten.
                        if (!copy) {
                            setVideoMimeType(videoMime)
                            setAudioMimeType(if (webm) MimeTypes.AUDIO_OPUS else MimeTypes.AUDIO_AAC)
                        }
                    }
                    .setEncoderFactory(encoderFactory)
                    // Beim Kopieren mit Kürzen nur den Anfang bis zum nächsten Schlüsselbild neu kodieren.
                    .experimentalSetTrimOptimizationEnabled(copy && settings.isTrimmed)
                    .apply {
                        if (webm) {
                            setMuxerFactory(WebmMuxer.Factory())
                            // WebM kann keine Drehung speichern → Hochkant-Videos hochkant kodieren.
                            setPortraitEncodingEnabled(true)
                        } else if (audioOnly) {
                            // Ohne Platzhalter für „schnellen Start“ – bei reinem Ton wären das sonst ~1/3 der Datei.
                            setMuxerFactory(InAppMp4Muxer.Factory().setAttemptStreamableOutputEnabled(false))
                        }
                    }
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            handler.removeCallbacks(poll)
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            handler.removeCallbacks(poll)
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()

                transformer.start(composition, output.absolutePath)
                handler.post(poll)
                cont.invokeOnCancellation {
                    handler.post {
                        handler.removeCallbacks(poll)
                        transformer.cancel()
                    }
                }
            }
        }
    }

    /** Rechnet aus der Zielgröße die Video-Bitrate aus („kleiner als 16 MB“). */
    private fun targetVideoBitrate(settings: ConversionSettings, durationMs: Long?, removeAudio: Boolean): Int? {
        val target = settings.targetSizeBytes ?: return null
        if (durationMs == null || durationMs <= 0) return null
        return Bitrate.videoForTargetSize(target, durationMs, settings.audioBitrate, withAudio = !removeAudio)
    }

    private class Probe(val width: Int, val height: Int, val bitrate: Int?)

    private fun videoEffects(probe: Probe?, shortSide: Int?): Effects {
        if (shortSide == null || probe == null) return Effects.EMPTY
        val currentShort = min(probe.width, probe.height)
        if (currentShort <= shortSide) return Effects.EMPTY
        val scale = shortSide.toDouble() / currentShort
        val outWidth = even(probe.width * scale)
        val outHeight = even(probe.height * scale)
        return Effects(
            emptyList(),
            listOf(Presentation.createForWidthAndHeight(outWidth, outHeight, Presentation.LAYOUT_SCALE_TO_FIT)),
        )
    }

    /** Größe so, wie das Video angezeigt wird (Drehung berücksichtigt), und Gesamt-Bitrate. */
    private fun probe(context: Context, input: Uri): Probe? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, input)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
            when {
                w == null || h == null -> null
                rotation % 180 != 0 -> Probe(h, w, bitrate)
                else -> Probe(w, h, bitrate)
            }
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun even(value: Double): Int = (value / 2).roundToInt() * 2
}
