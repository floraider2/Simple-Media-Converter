package com.simpleconverter.app.convert.wmv

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.media.ImageWriter
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.AssetLoader
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.SampleConsumer
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableMap
import com.simpleconverter.app.convert.audio.PcmDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Liefert WMV/WMA-Dateien an Media3: FFmpeg dekodiert in Software, die Bilder gehen über einen
 * [ImageWriter] mit Zeitstempel in die Eingangsfläche von Media3, der Ton als PCM in dessen Puffer.
 * Skalieren, Kodieren (Hardware-Encoder des Geräts) und Speichern macht Media3 wie bei jedem Video.
 */
@OptIn(UnstableApi::class)
internal class AsfAssetLoader private constructor(
    private val context: Context,
    private val item: EditedMediaItem,
    private val listener: AssetLoader.Listener,
) : AssetLoader {

    class Factory(private val context: Context) : AssetLoader.Factory {
        override fun createAssetLoader(
            editedMediaItem: EditedMediaItem,
            looper: Looper,
            listener: AssetLoader.Listener,
            compositionSettings: AssetLoader.CompositionSettings,
        ): AssetLoader = AsfAssetLoader(context.applicationContext, editedMediaItem, listener)
    }

    @Volatile private var released = false
    @Volatile private var progress = 0
    private var decoderNames: ImmutableMap<Int, String> = ImmutableMap.of()
    private var thread: Thread? = null

    override fun start() {
        thread = Thread({
            try {
                run()
            } catch (e: Exception) {
                if (!released) {
                    Log.w(TAG, "WMV/WMA fehlgeschlagen", e)
                    listener.onError(ExportException.createForAssetLoader(e, ExportException.ERROR_CODE_DECODING_FAILED))
                }
            }
        }, "AsfAssetLoader").apply { start() }
    }

    override fun getProgress(progressHolder: ProgressHolder): Int {
        progressHolder.progress = progress
        return Transformer.PROGRESS_STATE_AVAILABLE
    }

    override fun getDecoderNames(): ImmutableMap<Int, String> = decoderNames

    override fun release() {
        released = true
        thread?.join(STOP_WAIT_MS)
    }

    // ───────────── Ablauf ─────────────

    private fun run() {
        val clipping = item.mediaItem.clippingConfiguration
        val startUs = clipping.startPositionUs
        val endUs = if (clipping.endPositionUs == C.TIME_END_OF_SOURCE) Long.MAX_VALUE else clipping.endPositionUs

        AsfDecoder.open(context, item.mediaItem.localConfiguration!!.uri).use { decoder ->
            val info = decoder.info
            val wantVideo = info.hasVideo && !item.removeVideo
            val wantAudio = info.hasAudio && !item.removeAudio
            if (!wantVideo && !wantAudio) throw IllegalStateException("Keine Spur zum Umwandeln")
            decoder.start(wantVideo, wantAudio)
            if (startUs > 0) decoder.seekTo(startUs)
            decoderNames = ImmutableMap.copyOf(
                buildMap {
                    if (wantVideo) put(C.TRACK_TYPE_VIDEO, "FFmpeg ${info.videoCodec}")
                    if (wantAudio) put(C.TRACK_TYPE_AUDIO, "FFmpeg ${info.audioCodec}")
                }
            )

            val durationUs = (minOf(info.durationUs, endUs) - startUs).coerceAtLeast(1)
            listener.onDurationUs(durationUs)
            listener.onTrackCount((if (wantVideo) 1 else 0) + (if (wantAudio) 1 else 0))
            val video = if (wantVideo) VideoOut(info) else null
            val audio = if (wantAudio) AudioOut(info) else null
            video?.let { listener.onTrackAdded(it.format, AssetLoader.SUPPORTED_OUTPUT_TYPE_DECODED) }
            audio?.let { listener.onTrackAdded(it.format, AssetLoader.SUPPORTED_OUTPUT_TYPE_DECODED) }

            try {
                // Media3 nimmt Ausgaben erst an, wenn alle Spuren angemeldet sind – reihum ohne Warten fragen.
                while (!released && (video?.ready == false || audio?.ready == false)) {
                    val any = (video?.tryInit() ?: false) or (audio?.tryInit() ?: false)
                    if (!any) Thread.sleep(5)
                }

                var ended = false
                while (!released && !ended) {
                    // Erst Wartendes loswerden, sonst würde das nächste Bild das aktuelle überschreiben.
                    if (video?.hasPending == true) {
                        if (!video.deliver(decoder)) Thread.sleep(1)
                        continue
                    }
                    if (audio?.hasPending == true) {
                        if (!audio.deliver()) Thread.sleep(1)
                        continue
                    }
                    when (decoder.read()) {
                        AsfDecoder.Result.VIDEO -> {
                            val pts = decoder.ptsUs
                            if (video == null || pts < startUs || pts > endUs) decoder.dropFrame() else video.hold(pts - startUs)
                            if (pts > endUs) video?.markEnded()
                            progress = (((pts - startUs) * 100) / durationUs).toInt().coerceIn(0, 99)
                        }
                        AsfDecoder.Result.AUDIO -> {
                            val pts = decoder.ptsUs
                            audio?.hold(decoder.audio, pts, startUs, endUs)
                            if (pts > endUs) audio?.markEnded()
                        }
                        AsfDecoder.Result.END -> ended = true
                    }
                    if ((video == null || video.ended) && (audio == null || audio.ended)) ended = true
                }
                // Restliches abliefern und das Ende melden.
                while (!released && !((video?.finish() ?: true) and (audio?.finish(released) ?: true))) Thread.sleep(1)
            } finally {
                video?.release()
            }
        }
    }

    // ───────────── Video ─────────────

    private inner class VideoOut(info: AsfDecoder.Info) {
        // Ein Video-Typ (nicht video/raw): nur dann bekommt der Lader von Media3 eine Eingangsfläche statt Texturen.
        val format: Format = Format.Builder()
            .setSampleMimeType(WMV_MIME)
            .setWidth(info.width)
            .setHeight(info.height)
            .apply { if (info.frameRate > 0) setFrameRate(info.frameRate) }
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build()
        private lateinit var consumer: SampleConsumer
        private var writer: ImageWriter? = null
        private var yuv = Build.VERSION.SDK_INT >= 29
        private var pendingUs = -1L
        private var endSignalled = false
        var ended = false
            private set
        var ready = false
            private set
        val hasPending get() = pendingUs >= 0

        fun tryInit(): Boolean {
            if (ready) return false
            consumer = listener.onOutputFormat(format) ?: return false
            ready = true
            return true
        }

        fun hold(ptsUs: Long) {
            pendingUs = ptsUs
        }

        fun markEnded() {
            ended = true
        }

        /** Bild bei Media3 anmelden und mit Zeitstempel in dessen Fläche schreiben – sobald dort Platz ist. */
        fun deliver(decoder: AsfDecoder): Boolean {
            if (consumer.pendingVideoFrameCount >= MAX_PENDING_FRAMES || !consumer.registerVideoFrame(pendingUs)) return false
            val w = writer ?: openWriter()
            val image = w.dequeueInputImage()
            try {
                decoder.copyTo(image)
                image.timestamp = pendingUs * 1000
                w.queueInputImage(image)
            } catch (e: Exception) {
                image.close()
                throw e
            }
            pendingUs = -1
            return true
        }

        /**
         * Android 10+: YUV direkt (die Grafikeinheit rechnet um). Ältere Geräte oder wenn die Fläche
         * kein YUV annimmt: RGBA, umgerechnet in C.
         */
        private fun openWriter(): ImageWriter {
            val surface = consumer.inputSurface
            if (yuv && Build.VERSION.SDK_INT >= 29) {
                // Manche Geräte erlauben YUV-Puffer, aber keinen Schreibzugriff darauf – vorab ausprobieren.
                runCatching {
                    val w = ImageWriter.newInstance(surface, MAX_IMAGES, ImageFormat.YUV_420_888)
                    try {
                        w.dequeueInputImage().use { it.planes }
                    } catch (e: Exception) {
                        w.close()
                        throw e
                    }
                    w
                }
                    .onSuccess { writer = it; return it }
                    .onFailure { Log.i(TAG, "YUV nicht beschreibbar, nutze RGBA", it) }
                yuv = false
            }
            val w = if (Build.VERSION.SDK_INT >= 29) ImageWriter.newInstance(surface, MAX_IMAGES, PixelFormat.RGBA_8888)
            else ImageWriter.newInstance(surface, MAX_IMAGES)
            writer = w
            return w
        }

        /** true, wenn alles abgeliefert und das Ende gemeldet ist. */
        fun finish(): Boolean {
            if (hasPending) return false
            if (!endSignalled) consumer.signalEndOfVideoInput()
            endSignalled = true
            return true
        }

        fun release() {
            writer?.close()
        }
    }

    // ───────────── Ton ─────────────

    private inner class AudioOut(private val info: AsfDecoder.Info) {
        val format: Format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setSampleRate(info.sampleRate)
            .setChannelCount(info.channels)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .build()
        private lateinit var consumer: SampleConsumer
        private var pending: ByteBuffer? = null
        private var pendingUs = 0L
        private var endSignalled = false
        var ended = false
            private set
        var ready = false
            private set
        val hasPending get() = pending != null

        fun tryInit(): Boolean {
            if (ready) return false
            consumer = listener.onOutputFormat(format) ?: return false
            ready = true
            return true
        }

        /** Ton auf den Ausschnitt zuschneiden und kopieren (der Puffer des Decoders wird gleich wieder benutzt). */
        fun hold(pcm: ByteBuffer, ptsUs: Long, startUs: Long, endUs: Long) {
            val clipped = PcmDecoder.clip(pcm.order(ByteOrder.LITTLE_ENDIAN), ptsUs, startUs, endUs, info.sampleRate, info.channels) ?: return
            val copy = ByteBuffer.allocate(clipped.remaining()).order(ByteOrder.LITTLE_ENDIAN)
            copy.put(clipped).flip()
            pending = copy
            pendingUs = maxOf(ptsUs, startUs) - startUs
        }

        fun markEnded() {
            ended = true
        }

        fun deliver(): Boolean {
            val data = pending ?: return true
            val buffer = consumer.inputBuffer ?: return false
            buffer.ensureSpaceForWrite(data.remaining())
            buffer.data!!.put(data.duplicate())
            buffer.flip()
            buffer.timeUs = pendingUs
            if (!consumer.queueInputBuffer()) return false
            pending = null
            return true
        }

        /** true, wenn alles abgeliefert und das Ende gemeldet ist. */
        fun finish(cancelled: Boolean): Boolean {
            if (cancelled) return true
            if (!deliver()) return false
            if (endSignalled) return true
            val buffer = consumer.inputBuffer ?: return false
            buffer.clear()
            buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
            if (!consumer.queueInputBuffer()) return false
            endSignalled = true
            return true
        }
    }

    private companion object {
        const val TAG = "AsfAssetLoader"
        const val STOP_WAIT_MS = 5_000L

        /** So viele Bilder dürfen bei Media3 auf Verarbeitung warten. */
        const val MAX_PENDING_FRAMES = 8
        const val MAX_IMAGES = 8
        const val WMV_MIME = "video/x-ms-wmv"
    }
}
