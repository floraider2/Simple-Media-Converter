package com.simpleconverter.app.convert

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
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
@OptIn(UnstableApi::class)
object VideoConverter {

    suspend fun convert(
        context: Context,
        input: Uri,
        output: File,
        settings: ConversionSettings,
        durationMs: Long?,
        onProgress: (Int) -> Unit,
    ) {
        val audioOnly = settings.format == OutputFormat.M4A
        val removeAudio = !audioOnly && settings.removeAudio

        val probe = probe(context, input)
        val effects = if (audioOnly) Effects.EMPTY else videoEffects(probe, settings.videoShortSide)
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(input))
            .setRemoveVideo(audioOnly)
            .setRemoveAudio(removeAudio)
            .setEffects(effects)
            .build()
        val composition = Composition.Builder(EditedMediaItemSequence(edited))
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
        val webm = settings.format == OutputFormat.WEBM
        val videoMime = when {
            webm -> MimeTypes.VIDEO_VP9
            settings.hevc -> MimeTypes.VIDEO_H265
            else -> MimeTypes.VIDEO_H264
        }

        // Nie mehr Bitrate als das Original – sonst wird die Datei beim „Verkleinern“ größer.
        val videoBitrate = (targetVideoBitrate(settings, durationMs, removeAudio) ?: settings.videoBitrate)
            ?.let { Bitrate.capToSource(it, probe?.bitrate) }
        val encoderFactory = DefaultEncoderFactory.Builder(context.applicationContext)
            .setEnableFallback(true)
            .setRequestedAudioEncoderSettings(
                AudioEncoderSettings.Builder().setBitrate(settings.audioBitrate).build()
            )
            .apply {
                if (videoBitrate != null) {
                    setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(videoBitrate).build())
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
                    .setVideoMimeType(videoMime)
                    .setAudioMimeType(if (webm) MimeTypes.AUDIO_OPUS else MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoderFactory)
                    .apply {
                        if (webm) {
                            setMuxerFactory(WebmMuxer.Factory())
                            // WebM kann keine Drehung speichern → Hochkant-Videos hochkant kodieren.
                            setPortraitEncodingEnabled(true)
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
