package com.simpleconverter.app.convert.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.RequiresApi
import com.simpleconverter.app.R
import com.simpleconverter.app.convert.ConversionException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Ziel für dekodiertes Audio: 16-Bit-PCM, verschachtelt (L R L R …), Little Endian.
 */
interface PcmSink {
    fun start(sampleRate: Int, channels: Int)

    /** Verbraucht alle verbleibenden Bytes von [pcm]. */
    fun write(pcm: ByteBuffer)

    fun finish()

    /** Gibt Ressourcen frei – wird immer aufgerufen, auch nach Fehlern. */
    fun release()
}

/**
 * Liest die erste Tonspur einer Video- oder Audiodatei und liefert 16-Bit-PCM.
 *
 * Drei Wege, vom schnellsten zum langsamsten:
 * 1. **WAV (16-Bit-PCM)** wird ohne Dekoder direkt durchgereicht.
 * 2. **Gebündelt (Android 15+):** Viele komprimierte Blöcke auf einmal an einen Dekoder, der das
 *    kann („multiple-frames“). Software-Dekoder laufen in einem eigenen Systemprozess; jeder einzelne
 *    Block kostet dort einen Hin-und-Rückweg – gebündelt sind es zehntausende weniger.
 * 3. **Ein Block pro Aufruf** wie bisher (ältere Geräte, Dekoder ohne Bündelung).
 */
object PcmDecoder {

    private const val TAG = "PcmDecoder"
    private const val BATCH_INPUT_BYTES = 256 * 1024
    private const val BATCH_MAX_FRAMES = 64

    /** Kommt so lange nichts vom Dekoder, gilt er als hängend. */
    private const val STALL_MS = 15_000L

    /**
     * Bündeln nur für erprobte Formate. Der FLAC-Dekoder von Android meldet zwar „multiple-frames“,
     * liefert gebündelt auf dem Galaxy S24 Ultra (Android 16) aber nie ein Ergebnis.
     */
    private val BATCH_DECODE_OK = setOf("audio/mp4a-latm", "audio/mpeg", "audio/opus", "audio/vorbis")

    /**
     * @param startUs Anfang des Ausschnitts (0 = vom Anfang)
     * @param endUs Ende des Ausschnitts (null = bis zum Ende)
     */
    suspend fun decode(
        context: Context,
        input: Uri,
        sink: PcmSink,
        onProgress: (Int) -> Unit,
        startUs: Long = 0L,
        endUs: Long? = null,
    ) {
        val extractor = try {
            AudioSource.open(context, input)
        } catch (e: Exception) {
            sink.release()
            throw e
        }
        try {
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw ConversionException(R.string.err_no_audio_track)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val fullUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val range = Range(startUs, endUs ?: Long.MAX_VALUE, (endUs ?: fullUs) - startUs, onProgress)
            // Zum Sync-Punkt davor springen; was vor startUs liegt, wird beim Zuschneiden entfernt.
            if (startUs > 0) extractor.seekTo(startUs)

            if (isPcm16(format)) {
                passThrough(extractor, format, sink, range)
            } else {
                withCodec(extractor, format, sink, range)
            }
        } finally {
            extractor.release()
            sink.release()
        }
    }

    /** Ausschnitt + Fortschritt, gemeinsam für alle Wege. */
    private class Range(val startUs: Long, val stopUs: Long, val durationUs: Long, val onProgress: (Int) -> Unit) {
        private var last = -1
        fun progress(ptsUs: Long) {
            if (durationUs <= 0) return
            val p = (((ptsUs - startUs) * 100) / durationUs).toInt().coerceIn(0, 99)
            if (p != last) {
                last = p
                onProgress(p)
            }
        }
    }

    private fun isPcm16(format: MediaFormat): Boolean =
        format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_RAW &&
            (!format.containsKey(MediaFormat.KEY_PCM_ENCODING) || format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT)

    // ───────────── 1. WAV direkt ─────────────

    private suspend fun passThrough(extractor: AudioSource, format: MediaFormat, sink: PcmSink, range: Range) {
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        sink.start(rate, channels)
        val buffer = ByteBuffer.allocateDirect(maxOf(format.maxInputSize(), 64 * 1024)).order(ByteOrder.LITTLE_ENDIAN)
        while (true) {
            coroutineContext.ensureActive()
            buffer.clear()
            val read = extractor.readSampleData(buffer, 0)
            val pts = extractor.sampleTime
            if (read < 0 || pts > range.stopUs) break
            buffer.position(0).limit(read)
            clip(buffer, pts, range.startUs, range.stopUs, rate, channels)?.let(sink::write)
            range.progress(pts)
            extractor.advance()
        }
        sink.finish()
    }

    private fun MediaFormat.maxInputSize() = if (containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0

    // ───────────── 2./3. Mit Dekoder (asynchron) ─────────────

    /**
     * Der Dekoder meldet sich über Rückrufe, sobald ein Eingangspuffer frei oder ein Ergebnis fertig ist.
     * Nur in diesem Modus erlaubt Android das Bündeln vieler Blöcke („Large Frame audio“, Android 15+).
     */
    private suspend fun withCodec(extractor: AudioSource, format: MediaFormat, sink: PcmSink, range: Range) {
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val batchCodec = if (Build.VERSION.SDK_INT >= 35 && extractor.canBatch && mime in BATCH_DECODE_OK) batchingDecoder(mime) else null
        if (batchCodec != null) {
            val ok = runCatching { decodeWith(MediaCodec.createByCodecName(batchCodec), format, batching = true, extractor, sink, range) }
            if (ok.isSuccess) return
            val error = ok.exceptionOrNull()
            // Abfangen nur, solange noch nichts beim Ziel angekommen ist – sonst wäre die Ausgabe doppelt.
            val retry = error is SetupException || (error is StallException && !error.anyOutput)
            if (!retry) throw error!!
            Log.w(TAG, "Bündeln mit $batchCodec nicht möglich, normaler Weg", error)
            extractor.seekTo(maxOf(range.startUs, 0L))
        }
        decodeWith(MediaCodec.createDecoderByType(mime), format, batching = false, extractor, sink, range)
    }

    private class SetupException(cause: Throwable) : Exception(cause)

    /** Der Dekoder hat [STALL_MS] lang nichts gemeldet. */
    private class StallException(val anyOutput: Boolean) : Exception("Dekoder antwortet nicht")

    private sealed interface Event {
        class Input(val index: Int) : Event
        class Output(val index: Int, val frames: List<MediaCodec.BufferInfo>) : Event
        class Format(val format: MediaFormat) : Event
        class Failure(val error: Exception) : Event
    }

    private suspend fun decodeWith(
        codec: MediaCodec,
        format: MediaFormat,
        batching: Boolean,
        extractor: AudioSource,
        sink: PcmSink,
        range: Range,
    ) {
        val thread = HandlerThread("PcmDecoder").apply { start() }
        val events = LinkedBlockingQueue<Event>()
        try {
            try {
                codec.setCallback(object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(c: MediaCodec, index: Int) = events.put(Event.Input(index))
                    override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) =
                        events.put(Event.Output(index, listOf(copy(info))))
                    override fun onOutputBuffersAvailable(c: MediaCodec, index: Int, infos: ArrayDeque<MediaCodec.BufferInfo>) =
                        events.put(Event.Output(index, infos.map(::copy)))
                    override fun onOutputFormatChanged(c: MediaCodec, f: MediaFormat) = events.put(Event.Format(f))
                    override fun onError(c: MediaCodec, e: MediaCodec.CodecException) = events.put(Event.Failure(e))
                }, Handler(thread.looper))
                // Kopie nur im Bündel-Zweig (Android 15+); der Kopier-Konstruktor gibt es erst ab Android 10.
                val configured = if (batching && Build.VERSION.SDK_INT >= 35) {
                    MediaFormat(format).apply {
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BATCH_INPUT_BYTES)
                        setBatchOutput()
                    }
                } else {
                    format
                }
                codec.configure(configured, null, null, 0)
                codec.start()
            } catch (e: Exception) {
                throw SetupException(e)
            }
            Log.d(TAG, "Dekoder ${codec.name} für ${format.getString(MediaFormat.KEY_MIME)}, gebündelt=$batching")
            Session(extractor, codec, sink, range, batching && Build.VERSION.SDK_INT >= 35).run(events)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            thread.quitSafely()
        }
    }

    private fun copy(info: MediaCodec.BufferInfo) =
        MediaCodec.BufferInfo().apply { set(info.offset, info.size, info.presentationTimeUs, info.flags) }

    @RequiresApi(35)
    private fun MediaFormat.setBatchOutput() {
        setInteger(MediaFormat.KEY_BUFFER_BATCH_MAX_OUTPUT_SIZE, 1024 * 1024)
        setInteger(MediaFormat.KEY_BUFFER_BATCH_THRESHOLD_OUTPUT_SIZE, 512 * 1024)
    }

    /** Ein Dekoder (bevorzugt der von Android selbst), der mehrere Blöcke auf einmal annimmt. */
    @RequiresApi(35)
    private fun batchingDecoder(mime: String): String? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && mime in it.supportedTypes.map(String::lowercase) }
            .filter { runCatching { it.getCapabilitiesForType(mime).isFeatureSupported("multiple-frames") }.getOrDefault(false) }
            .sortedByDescending { it.name.startsWith("c2.android.") }
            .firstOrNull()?.name

    /** Verarbeitet die Rückrufe des Dekoders der Reihe nach in der aufrufenden Coroutine. */
    private class Session(
        val extractor: AudioSource,
        val decoder: MediaCodec,
        val sink: PcmSink,
        val range: Range,
        val batching: Boolean,
    ) {
        private var inputDone = false
        private var started = false
        private var floatPcm = false
        private var sampleRate = 0
        private var channels = 0
        private var floatScratch: ByteBuffer? = null

        suspend fun run(events: LinkedBlockingQueue<Event>) {
            var outputDone = false
            var lastEvent = System.currentTimeMillis()
            while (!outputDone) {
                coroutineContext.ensureActive()
                val event = events.poll(100, TimeUnit.MILLISECONDS)
                if (event == null) {
                    // Wächter: lieber mit Fehler abbrechen als endlos warten.
                    if (System.currentTimeMillis() - lastEvent > STALL_MS) throw StallException(anyOutput = started)
                    continue
                }
                lastEvent = System.currentTimeMillis()
                when (event) {
                    is Event.Input -> if (!inputDone) {
                        val buffer = decoder.getInputBuffer(event.index)!!
                        if (batching && Build.VERSION.SDK_INT >= 35) feedBatch(event.index, buffer) else feedOne(event.index, buffer)
                    }
                    is Event.Format -> startSink(event.format)
                    is Event.Failure -> throw event.error
                    is Event.Output -> {
                        val buffer = decoder.getOutputBuffer(event.index)
                        for (frame in event.frames) {
                            if (frame.size > 0 && buffer != null) {
                                startSink(decoder.outputFormat)
                                emit(buffer, frame.offset, frame.size, frame.presentationTimeUs)
                            }
                            if (frame.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                        decoder.releaseOutputBuffer(event.index, false)
                    }
                }
            }
            if (!started) throw ConversionException(R.string.err_empty_audio_track)
            sink.finish()
        }

        private fun feedOne(index: Int, buffer: ByteBuffer) {
            val read = extractor.readSampleData(buffer, 0)
            if (read < 0 || extractor.sampleTime > range.stopUs) {
                decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputDone = true
            } else {
                decoder.queueInputBuffer(index, 0, read, extractor.sampleTime, 0)
                extractor.advance()
            }
        }

        /** Viele Blöcke hintereinander in einen Puffer, mit Angabe, wo jeder beginnt. */
        @RequiresApi(35)
        private fun feedBatch(index: Int, buffer: ByteBuffer) {
            val frames = ArrayDeque<MediaCodec.BufferInfo>()
            var offset = 0
            while (frames.size < BATCH_MAX_FRAMES) {
                val size = extractor.sampleSize
                val time = extractor.sampleTime
                if (size < 0 || time < 0 || time > range.stopUs) {
                    inputDone = true
                    break
                }
                if (offset + size > buffer.capacity()) break
                buffer.limit(buffer.capacity()).position(offset)
                val read = extractor.readSampleData(buffer, offset)
                if (read < 0) {
                    inputDone = true
                    break
                }
                frames.add(MediaCodec.BufferInfo().apply { set(offset, read, time, 0) })
                offset += read
                extractor.advance()
            }
            if (frames.isEmpty()) {
                decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputDone = true
                return
            }
            if (inputDone) frames.last.flags = MediaCodec.BUFFER_FLAG_END_OF_STREAM
            decoder.queueInputBuffers(index, frames)
        }

        private fun startSink(format: MediaFormat) {
            if (started) return
            floatPcm = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            sink.start(sampleRate, channels)
            started = true
        }

        private fun emit(buffer: ByteBuffer, offset: Int, size: Int, ptsUs: Long) {
            buffer.limit(offset + size).position(offset)
            val pcm = if (floatPcm) floatTo16Bit(buffer) else buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
            clip(pcm, ptsUs, range.startUs, range.stopUs, sampleRate, channels)?.let(sink::write)
            range.progress(ptsUs)
        }

        /** Float-PCM → 16 Bit, in einem wiederverwendeten Puffer. */
        private fun floatTo16Bit(buffer: ByteBuffer): ByteBuffer {
            val floats = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
            val needed = floats.remaining() * 2
            val out = floatScratch?.takeIf { it.capacity() >= needed }
                ?: ByteBuffer.allocate(needed).order(ByteOrder.LITTLE_ENDIAN).also { floatScratch = it }
            out.clear()
            while (floats.hasRemaining()) {
                out.putShort((floats.get().coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
            }
            out.flip()
            return out
        }
    }

    /**
     * Schneidet einen PCM-Block auf [startUs, stopUs) zu. Ein Block beginnt bei [ptsUs];
     * gibt null zurück, wenn nichts davon im Ausschnitt liegt.
     */
    internal fun clip(pcm: ByteBuffer, ptsUs: Long, startUs: Long, stopUs: Long, sampleRate: Int, channels: Int): ByteBuffer? {
        if (startUs <= 0 && stopUs == Long.MAX_VALUE) return pcm
        val frameBytes = channels * 2
        val frames = pcm.remaining() / frameBytes
        fun frameAt(us: Long) = (((us - ptsUs) * sampleRate) / 1_000_000L).coerceIn(0L, frames.toLong()).toInt()
        val from = frameAt(startUs)
        val to = if (stopUs == Long.MAX_VALUE) frames else frameAt(stopUs)
        if (to <= from) return null
        val out = pcm.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        out.position(pcm.position() + from * frameBytes)
        out.limit(pcm.position() + to * frameBytes)
        return out.slice().order(ByteOrder.LITTLE_ENDIAN)
    }
}
