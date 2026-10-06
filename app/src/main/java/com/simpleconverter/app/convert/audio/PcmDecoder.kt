package com.simpleconverter.app.convert.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.simpleconverter.app.R
import com.simpleconverter.app.convert.ConversionException
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

/** Liest die erste Tonspur einer Video- oder Audiodatei und dekodiert sie mit MediaCodec. */
object PcmDecoder {

    private const val TIMEOUT_US = 10_000L

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
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, input, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw ConversionException(R.string.err_no_audio_track)
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val fullUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) inputFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            val stopUs = endUs ?: Long.MAX_VALUE
            val durationUs = (if (endUs != null) endUs else fullUs) - startUs
            // Zum Sync-Punkt davor springen; was vor startUs liegt, wird unten weggeschnitten.
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            var started = false
            var floatPcm = false
            var sampleRate = 0
            var channels = 0
            fun startSink(format: MediaFormat) {
                if (started) return
                floatPcm = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                sink.start(sampleRate, channels)
                started = true
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var lastProgress = -1
            while (true) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val read = extractor.readSampleData(decoder.getInputBuffer(inIndex)!!, 0)
                        if (read < 0 || extractor.sampleTime > stopUs) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    startSink(decoder.outputFormat)
                } else if (outIndex >= 0) {
                    if (info.size > 0) {
                        startSink(decoder.outputFormat)
                        val buffer = decoder.getOutputBuffer(outIndex)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val pcm = if (floatPcm) floatTo16Bit(buffer) else buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
                        clip(pcm, info.presentationTimeUs, startUs, stopUs, sampleRate, channels)?.let(sink::write)
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if (durationUs > 0) {
                        val p = (((info.presentationTimeUs - startUs) * 100) / durationUs).toInt().coerceIn(0, 99)
                        if (p != lastProgress) {
                            lastProgress = p
                            onProgress(p)
                        }
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            if (!started) throw ConversionException(R.string.err_empty_audio_track)
            sink.finish()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
            sink.release()
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

    private fun floatTo16Bit(buffer: ByteBuffer): ByteBuffer {
        val floats = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val out = ByteBuffer.allocate(floats.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN)
        while (floats.hasRemaining()) {
            out.putShort((floats.get().coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
        }
        out.flip()
        return out
    }
}
