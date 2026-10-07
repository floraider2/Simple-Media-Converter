package com.simpleconverter.app.convert

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.MediaFormatUtil
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Ersatz-Leser für Dateien, die Media3 nicht öffnen kann (z. B. WMV/ASF): liest mit dem Leser von
 * Android und dekodiert mit den Decodern des Geräts. Skalieren, Kodieren und Speichern macht weiter
 * Media3. Ob es klappt, hängt vom Gerät ab – Samsung etwa bringt einen WMV-Decoder mit, reines Android nicht.
 */
@OptIn(UnstableApi::class)
internal class FrameworkAssetLoader private constructor(
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
        ): AssetLoader = FrameworkAssetLoader(context.applicationContext, editedMediaItem, listener)
    }

    /** Für diese Spur hat das Gerät keinen Decoder (oder er liefert ein unbrauchbares Format). */
    class NoDecoderException(what: String) : Exception("Kein passender Decoder: $what")

    @Volatile private var released = false
    @Volatile private var progress = 0
    private val decoderNames = ConcurrentHashMap<Int, String>()
    private var thread: Thread? = null

    override fun start() {
        thread = Thread({
            try {
                run()
            } catch (e: Exception) {
                if (!released) {
                    Log.w(TAG, "Lesen fehlgeschlagen", e)
                    val code = if (e is NoDecoderException) ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                    else ExportException.ERROR_CODE_DECODING_FAILED
                    listener.onError(ExportException.createForAssetLoader(e, code))
                }
            }
        }, "FrameworkAssetLoader").apply { start() }
    }

    override fun getProgress(progressHolder: ProgressHolder): Int {
        progressHolder.progress = progress
        return Transformer.PROGRESS_STATE_AVAILABLE
    }

    override fun getDecoderNames(): ImmutableMap<Int, String> = ImmutableMap.copyOf(decoderNames)

    override fun release() {
        released = true
        thread?.join(STOP_WAIT_MS)
    }

    // ───────────── Ablauf ─────────────

    private fun run() {
        val clipping = item.mediaItem.clippingConfiguration
        val startUs = clipping.startPositionUs
        val endUs = if (clipping.endPositionUs == C.TIME_END_OF_SOURCE) Long.MAX_VALUE else clipping.endPositionUs

        val extractor = MediaExtractor()
        val tracks = mutableListOf<Track>()
        try {
            extractor.setDataSource(context, item.mediaItem.localConfiguration!!.uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                val isVideo = mime.startsWith("video/") && !item.removeVideo && tracks.none { it is VideoTrack }
                val isAudio = mime.startsWith("audio/") && !item.removeAudio && tracks.none { it is AudioTrack }
                if (!isVideo && !isAudio) continue
                // Dekodieren kann nur, wofür das Gerät einen Decoder hat.
                MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) ?: throw NoDecoderException(mime)
                extractor.selectTrack(i)
                tracks += if (isVideo) VideoTrack(i, format) else AudioTrack(i, format)
            }
            if (tracks.isEmpty()) throw NoDecoderException("keine Spur")
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val fullUs = tracks.maxOf { if (it.format.containsKey(MediaFormat.KEY_DURATION)) it.format.getLong(MediaFormat.KEY_DURATION) else 0L }
            val durationUs = (minOf(fullUs, endUs) - startUs).coerceAtLeast(1)
            listener.onDurationUs(durationUs)
            listener.onTrackCount(tracks.size)
            for (track in tracks) {
                track.startUs = startUs
                track.endUs = endUs
                listener.onTrackAdded(MediaFormatUtil.createFormatFromMediaFormat(track.format), AssetLoader.SUPPORTED_OUTPUT_TYPE_DECODED)
            }

            var inputDone = false
            while (!released && tracks.any { !it.done }) {
                var busy = false
                // Media3 nimmt Ausgaben erst an, wenn alle Spuren angemeldet sind – deshalb reihum ohne Warten fragen.
                if (tracks.any { !it.ready }) {
                    tracks.forEach { if (!it.ready && it.tryInit()) busy = true }
                    if (!busy) Thread.sleep(5)
                    continue
                }
                if (!inputDone) {
                    val index = extractor.sampleTrackIndex
                    val time = extractor.sampleTime
                    if (index < 0 || time > endUs) {
                        tracks.forEach { it.queueEndOfInput() }
                        inputDone = tracks.all { it.endQueued }
                        busy = true
                    } else if (tracks.first { it.index == index }.queueInput(extractor)) {
                        extractor.advance()
                        busy = true
                        progress = (((time - startUs) * 100) / durationUs).toInt().coerceIn(0, 99)
                    }
                }
                for (track in tracks) if (track.drainOutput()) busy = true
                if (!busy) Thread.sleep(1)
            }
        } finally {
            tracks.forEach { it.release() }
            extractor.release()
        }
    }

    // ───────────── Spuren ─────────────

    private abstract inner class Track(val index: Int, val format: MediaFormat) {
        protected lateinit var codec: MediaCodec
        protected val info = MediaCodec.BufferInfo()
        var startUs = 0L
        var endUs = Long.MAX_VALUE
        var done = false
            protected set
        var endQueued = false
            private set
        val ready get() = ::codec.isInitialized

        /** Holt das Ziel bei Media3 und startet den Decoder; false, solange Media3 noch nicht bereit ist. */
        abstract fun tryInit(): Boolean

        /** Gibt fertige Ausgaben weiter; true, wenn sich etwas getan hat. */
        abstract fun drainOutput(): Boolean

        /** Einen Block an den Decoder; false, wenn gerade kein Eingangspuffer frei ist. */
        fun queueInput(extractor: MediaExtractor): Boolean {
            val i = codec.dequeueInputBuffer(0)
            if (i < 0) return false
            val size = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
            codec.queueInputBuffer(i, 0, size.coerceAtLeast(0), extractor.sampleTime, 0)
            return true
        }

        fun queueEndOfInput() {
            if (endQueued) return
            val i = codec.dequeueInputBuffer(0)
            if (i < 0) return // nächster Durchlauf versucht es erneut
            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            endQueued = true
        }

        fun release() {
            if (!ready) return
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }

        protected fun startCodec(surface: Surface?, trackType: Int) {
            val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            c.configure(format, surface, null, 0)
            c.start()
            decoderNames[trackType] = c.name
            codec = c
        }
    }

    /** Video: der Decoder zeichnet direkt in die Eingangsfläche von Media3. */
    private inner class VideoTrack(index: Int, format: MediaFormat) : Track(index, format) {
        private lateinit var consumer: SampleConsumer
        private var pendingIndex = -1
        private var pendingTimeUs = 0L
        private var eosSeen = false

        override fun tryInit(): Boolean {
            consumer = listener.onOutputFormat(MediaFormatUtil.createFormatFromMediaFormat(format)) ?: return false
            startCodec(consumer.inputSurface, C.TRACK_TYPE_VIDEO)
            return true
        }

        override fun drainOutput(): Boolean {
            if (done) return false
            if (pendingIndex >= 0) {
                if (!deliverPending()) return false
                if (eosSeen) finish()
                return true
            }
            if (eosSeen) {
                finish()
                return true
            }
            val i = codec.dequeueOutputBuffer(info, 0)
            if (i < 0) return false
            eosSeen = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            val pts = info.presentationTimeUs
            if (info.size > 0 && pts >= startUs && pts <= endUs) {
                pendingIndex = i
                pendingTimeUs = pts - startUs
                deliverPending()
            } else {
                codec.releaseOutputBuffer(i, false)
            }
            if (eosSeen && pendingIndex < 0) finish()
            return true
        }

        /** Bild bei Media3 anmelden und zeichnen – sobald dort Platz ist. */
        private fun deliverPending(): Boolean {
            if (consumer.pendingVideoFrameCount >= MAX_PENDING_FRAMES || !consumer.registerVideoFrame(pendingTimeUs)) return false
            codec.releaseOutputBuffer(pendingIndex, pendingTimeUs * 1000)
            pendingIndex = -1
            return true
        }

        private fun finish() {
            consumer.signalEndOfVideoInput()
            done = true
        }
    }

    /** Ton: der Decoder liefert PCM, das in die Puffer von Media3 kopiert wird. */
    private inner class AudioTrack(index: Int, format: MediaFormat) : Track(index, format) {
        private lateinit var consumer: SampleConsumer
        private val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        private val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        private var pending: ByteBuffer? = null
        private var pendingTimeUs = 0L
        private var pendingEnd = false

        /** Das PCM-Format steht vorab fest (Samplerate und Kanäle wie die Quelle), damit Media3 nicht warten muss. */
        override fun tryInit(): Boolean {
            consumer = listener.onOutputFormat(
                Format.Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_RAW)
                    .setSampleRate(sampleRate)
                    .setChannelCount(channels)
                    .setPcmEncoding(C.ENCODING_PCM_16BIT)
                    .build()
            ) ?: return false
            startCodec(null, C.TRACK_TYPE_AUDIO)
            return true
        }

        override fun drainOutput(): Boolean {
            if (done) return false
            if (pending != null || pendingEnd) return deliverPending()
            val i = codec.dequeueOutputBuffer(info, 0)
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                checkOutput(codec.outputFormat)
                return true
            }
            if (i < 0) return false
            if (info.size > 0) {
                val out = codec.getOutputBuffer(i)!!
                out.limit(info.offset + info.size).position(info.offset)
                val pcm = out.slice().order(ByteOrder.LITTLE_ENDIAN)
                PcmDecoder.clip(pcm, info.presentationTimeUs, startUs, endUs, sampleRate, channels)?.let { clipped ->
                    val copy = ByteBuffer.allocate(clipped.remaining()).order(ByteOrder.LITTLE_ENDIAN)
                    copy.put(clipped).flip()
                    pending = copy
                    pendingTimeUs = maxOf(info.presentationTimeUs, startUs) - startUs
                }
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) pendingEnd = true
            codec.releaseOutputBuffer(i, false)
            deliverPending()
            return true
        }

        /** Der Decoder muss liefern, was vorab angekündigt wurde. */
        private fun checkOutput(output: MediaFormat) {
            val encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) output.getInteger(MediaFormat.KEY_PCM_ENCODING) else C.ENCODING_PCM_16BIT
            if (encoding != C.ENCODING_PCM_16BIT ||
                output.getInteger(MediaFormat.KEY_SAMPLE_RATE) != sampleRate ||
                output.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != channels
            ) {
                throw NoDecoderException("abweichendes PCM-Format $output")
            }
        }

        private fun deliverPending(): Boolean {
            pending?.let { data ->
                val buffer = consumer.inputBuffer ?: return false
                buffer.ensureSpaceForWrite(data.remaining())
                buffer.data!!.put(data.duplicate())
                buffer.flip()
                buffer.timeUs = pendingTimeUs
                if (!consumer.queueInputBuffer()) return false
                pending = null
            }
            if (pendingEnd) {
                val buffer = consumer.inputBuffer ?: return false
                buffer.clear()
                buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
                if (!consumer.queueInputBuffer()) return false
                pendingEnd = false
                done = true
            }
            return true
        }
    }

    private companion object {
        const val TAG = "FrameworkAssetLoader"
        const val STOP_WAIT_MS = 5_000L

        /** So viele Bilder dürfen bei Media3 auf Verarbeitung warten. */
        const val MAX_PENDING_FRAMES = 5
    }
}
