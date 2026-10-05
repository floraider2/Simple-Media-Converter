package com.simpleconverter.app.convert

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/** Dekodiert die Tonspur einer Video- oder Audiodatei zu 16-Bit-PCM und schreibt eine WAV-Datei. */
object WavConverter {

    private const val HEADER_SIZE = 44
    private const val TIMEOUT_US = 10_000L

    suspend fun convert(context: Context, input: Uri, output: File, onProgress: (Int) -> Unit) =
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(context, input, null)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: throw ConversionException("Die Datei enthält keine Tonspur.")
                extractor.selectTrack(track)
                val inputFormat = extractor.getTrackFormat(track)
                val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                    inputFormat.getLong(MediaFormat.KEY_DURATION)
                } else {
                    0L
                }

                val decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
                codec = decoder
                decoder.configure(inputFormat, null, null, 0)
                decoder.start()

                var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var floatPcm = false

                RandomAccessFile(output, "rw").use { out ->
                    out.setLength(0)
                    out.write(ByteArray(HEADER_SIZE))
                    var dataBytes = 0L
                    val info = MediaCodec.BufferInfo()
                    var inputDone = false
                    var outputDone = false
                    var lastProgress = -1

                    while (!outputDone) {
                        coroutineContext.ensureActive()
                        if (!inputDone) {
                            val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                            if (inIndex >= 0) {
                                val buffer = decoder.getInputBuffer(inIndex)!!
                                val read = extractor.readSampleData(buffer, 0)
                                if (read < 0) {
                                    decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    decoder.queueInputBuffer(inIndex, 0, read, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }

                        val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                        when {
                            outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val f = decoder.outputFormat
                                sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                    f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                            }
                            outIndex >= 0 -> {
                                if (info.size > 0) {
                                    val buffer = decoder.getOutputBuffer(outIndex)!!
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)
                                    val bytes = if (floatPcm) floatTo16Bit(buffer) else ByteArray(info.size).also { buffer.get(it) }
                                    out.write(bytes)
                                    dataBytes += bytes.size
                                }
                                decoder.releaseOutputBuffer(outIndex, false)
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                                if (durationUs > 0) {
                                    val p = ((info.presentationTimeUs * 100) / durationUs).toInt().coerceIn(0, 99)
                                    if (p != lastProgress) {
                                        lastProgress = p
                                        onProgress(p)
                                    }
                                }
                            }
                        }
                    }

                    out.seek(0)
                    out.write(wavHeader(dataBytes, sampleRate, channels))
                }
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                extractor.release()
            }
        }

    private fun floatTo16Bit(buffer: ByteBuffer): ByteArray {
        val floats = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val out = ByteBuffer.allocate(floats.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN)
        while (floats.hasRemaining()) {
            val sample = (floats.get().coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            out.putShort(sample)
        }
        return out.array()
    }

    private fun wavHeader(dataBytes: Long, sampleRate: Int, channels: Int): ByteArray {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        return ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt((36 + dataBytes).toInt())
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1) // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * bitsPerSample / 8).toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray())
            putInt(dataBytes.toInt())
        }.array()
    }
}
