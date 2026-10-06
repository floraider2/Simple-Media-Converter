package com.simpleconverter.app.convert.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.simpleconverter.app.R
import com.simpleconverter.app.convert.ConversionException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

// ───────────────────────── WAV ─────────────────────────

/** Unkomprimiertes 16-Bit-PCM mit WAV-Kopf. */
class WavSink(private val output: File) : PcmSink {
    private lateinit var out: RandomAccessFile
    private var sampleRate = 0
    private var channels = 0
    private var dataBytes = 0L

    override fun start(sampleRate: Int, channels: Int) {
        this.sampleRate = sampleRate
        this.channels = channels
        out = RandomAccessFile(output, "rw").apply {
            setLength(0)
            write(ByteArray(HEADER_SIZE))
        }
    }

    private var bytes = ByteArray(0)

    override fun write(pcm: ByteBuffer) {
        val n = pcm.remaining()
        if (bytes.size < n) bytes = ByteArray(n)
        pcm.get(bytes, 0, n)
        out.write(bytes, 0, n)
        dataBytes += n
    }

    override fun finish() {
        out.seek(0)
        out.write(header(dataBytes, sampleRate, channels))
    }

    override fun release() {
        if (::out.isInitialized) runCatching { out.close() }
    }

    companion object {
        private const val HEADER_SIZE = 44

        fun header(dataBytes: Long, sampleRate: Int, channels: Int): ByteArray {
            val bitsPerSample = 16
            return ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray())
                putInt((36 + dataBytes).toInt())
                put("WAVE".toByteArray())
                put("fmt ".toByteArray())
                putInt(16)
                putShort(1) // PCM
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(sampleRate * channels * bitsPerSample / 8)
                putShort((channels * bitsPerSample / 8).toShort())
                putShort(bitsPerSample.toShort())
                put("data".toByteArray())
                putInt(dataBytes.toInt())
            }.array()
        }
    }
}

// ───────────────────────── MediaCodec-Encoder ─────────────────────────

/**
 * Gemeinsame Basis für Encoder des Systems: füttert PCM in MediaCodec und reicht
 * die kodierten Pakete an [onEncoded] weiter.
 *
 * Asynchron (Rückrufe) und – wo möglich, ab Android 15 – gebündelt: Software-Encoder laufen in
 * einem eigenen Systemprozess, jedes einzelne Paket (bei Opus alle 20 ms) kostet sonst einen
 * Hin-und-Rückweg. [onEncoded] und [onOutputFormat] laufen auf dem Rückruf-Thread, [onFinished]
 * danach auf dem aufrufenden Thread.
 */
abstract class EncoderSink(private val mime: String) : PcmSink {
    private var codec: MediaCodec? = null
    private var thread: HandlerThread? = null
    private val freeInputs = LinkedBlockingQueue<Int>()
    private val done = CountDownLatch(1)
    @Volatile private var failure: Exception? = null
    private var sampleRate = 0
    private var channels = 0
    private var framesQueued = 0L

    protected abstract fun configure(format: MediaFormat)
    protected abstract fun onOutputFormat(format: MediaFormat)
    protected abstract fun onEncoded(data: ByteBuffer, info: MediaCodec.BufferInfo)
    protected open fun onFinished(totalFrames: Long) {}

    override fun start(sampleRate: Int, channels: Int) {
        this.sampleRate = sampleRate
        this.channels = channels
        val format = MediaFormat.createAudioFormat(mime, sampleRate, channels)
        configure(format)
        // FLAC ist ungebündelt nicht langsamer, gebündelt aber weniger erprobt.
        val batchName = if (Build.VERSION.SDK_INT >= 35 && mime != MediaFormat.MIMETYPE_AUDIO_FLAC) batchingEncoder(mime) else null
        codec = batchName?.let { name ->
            runCatching { open(name, format, batching = true) }
                .onFailure { if (it is ConversionException) throw it }
                .getOrNull()
        } ?: open(null, format, batching = false)
    }

    private fun open(name: String?, base: MediaFormat, batching: Boolean): MediaCodec {
        val c = try {
            if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createEncoderByType(mime)
        } catch (e: Exception) {
            throw ConversionException(R.string.err_audio_encoder_missing)
        }
        val t = HandlerThread("EncoderSink").apply { start() }
        try {
            c.setCallback(callback, Handler(t.looper))
            // Kopie nur im Bündel-Zweig (Android 15+); der Kopier-Konstruktor gibt es erst ab Android 10.
            val f = if (batching && Build.VERSION.SDK_INT >= 35) {
                MediaFormat(base).apply {
                    setInteger(MediaFormat.KEY_BUFFER_BATCH_MAX_OUTPUT_SIZE, 256 * 1024)
                    setInteger(MediaFormat.KEY_BUFFER_BATCH_THRESHOLD_OUTPUT_SIZE, 128 * 1024)
                }
            } else {
                base
            }
            // Große Eingangspuffer: weniger Hin-und-Rückwege beim Einspeisen.
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_BYTES)
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
        } catch (e: Exception) {
            runCatching { c.release() }
            t.quitSafely()
            throw e
        }
        thread = t
        return c
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) = freeInputs.put(index)
        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) = output(c, index, listOf(info))
        override fun onOutputBuffersAvailable(c: MediaCodec, index: Int, infos: java.util.ArrayDeque<MediaCodec.BufferInfo>) =
            output(c, index, infos.toList())
        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) = guarded { onOutputFormat(format) }
        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) = fail(e)
    }

    private fun output(c: MediaCodec, index: Int, frames: List<MediaCodec.BufferInfo>) = guarded {
        val buffer = c.getOutputBuffer(index)!!
        var eos = false
        for (info in frames) {
            if (info.size > 0) {
                buffer.limit(info.offset + info.size).position(info.offset)
                onEncoded(buffer, info)
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
        }
        c.releaseOutputBuffer(index, false)
        if (eos) done.countDown()
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            fail(e)
        }
    }

    private fun fail(e: Exception) {
        failure = e
        freeInputs.put(-1) // weckt einen wartenden write()/finish()
        done.countDown()
    }

    private fun nextInput(): Int {
        val since = System.currentTimeMillis()
        while (true) {
            failure?.let { throw it }
            val index = freeInputs.poll(100, TimeUnit.MILLISECONDS)
            if (index == null) {
                if (System.currentTimeMillis() - since > STALL_MS) throw ConversionException(R.string.err_cannot_encode)
                continue
            }
            if (index < 0) throw failure ?: IllegalStateException("Encoder gestoppt")
            return index
        }
    }

    override fun write(pcm: ByteBuffer) {
        val c = codec!!
        val frameBytes = channels * 2
        while (pcm.hasRemaining()) {
            val index = nextInput()
            val buffer = c.getInputBuffer(index)!!
            buffer.clear()
            // Nur ganze Frames einreihen, damit die Zeitstempel stimmen.
            val bytes = minOf(buffer.remaining(), pcm.remaining()) / frameBytes * frameBytes
            val chunk = pcm.duplicate().apply { limit(position() + bytes) }
            buffer.put(chunk)
            pcm.position(pcm.position() + bytes)
            c.queueInputBuffer(index, 0, bytes, framesQueued * 1_000_000 / sampleRate, 0)
            framesQueued += bytes / frameBytes
        }
    }

    override fun finish() {
        val c = codec!!
        c.queueInputBuffer(nextInput(), 0, 0, framesQueued * 1_000_000 / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        val since = System.currentTimeMillis()
        while (!done.await(100, TimeUnit.MILLISECONDS)) {
            failure?.let { throw it }
            // Wächter: lieber mit Fehler abbrechen als endlos warten.
            if (System.currentTimeMillis() - since > STALL_MS) throw ConversionException(R.string.err_cannot_encode)
        }
        failure?.let { throw it }
        onFinished(framesQueued)
    }

    override fun release() {
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        thread?.quitSafely()
    }

    private companion object {
        const val INPUT_BYTES = 256 * 1024
        const val STALL_MS = 15_000L

        /** Ein Encoder (bevorzugt der von Android selbst), der mehrere Pakete auf einmal liefert. */
        @RequiresApi(35)
        fun batchingEncoder(mime: String): String? =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && mime in it.supportedTypes.map(String::lowercase) }
                .filter { runCatching { it.getCapabilitiesForType(mime).isFeatureSupported("multiple-frames") }.getOrDefault(false) }
                .sortedByDescending { it.name.startsWith("c2.android.") }
                .firstOrNull()?.name
    }
}

/**
 * FLAC über den System-Encoder. MediaMuxer kann kein FLAC schreiben, also bauen wir die Datei
 * selbst: Codec-Konfiguration („fLaC“ + STREAMINFO) und danach die rohen Frames.
 */
class FlacSink(private val output: File, private val compressionLevel: Int = 5) : EncoderSink(MediaFormat.MIMETYPE_AUDIO_FLAC) {
    private val out = RandomAccessFile(output, "rw").apply { setLength(0) }
    private var headerWritten = false
    private var minFrameBytes = Int.MAX_VALUE
    private var maxFrameBytes = 0
    private val md5 = MessageDigest.getInstance("MD5")

    override fun write(pcm: ByteBuffer) {
        // Prüfsumme über das unkomprimierte Audio (16 Bit, Little Endian, verschachtelt) – wie im FLAC-Standard.
        md5.update(pcm.duplicate())
        super.write(pcm)
    }

    override fun configure(format: MediaFormat) {
        format.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, compressionLevel)
    }

    override fun onOutputFormat(format: MediaFormat) = Unit

    override fun onEncoded(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        val bytes = ByteArray(data.remaining()).also { data.get(it) }
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            if (headerWritten) return
            headerWritten = true
            // Manche Encoder liefern nur STREAMINFO ohne die Kennung „fLaC“.
            if (!bytes.startsWith(FLAC_MAGIC)) out.write(FLAC_MAGIC)
        } else if (!headerWritten) {
            throw ConversionException(R.string.err_flac_header)
        } else {
            minFrameBytes = minOf(minFrameBytes, bytes.size)
            maxFrameBytes = maxOf(maxFrameBytes, bytes.size)
        }
        out.write(bytes)
    }

    /**
     * Ergänzt STREAMINFO um Frame-Größen, Gesamtzahl der Samples und MD5 – der Encoder lässt sie leer.
     * Ohne Frame-Größen zerlegen manche Leser (Android, Media3) die Datei falsch in Blöcke.
     */
    override fun onFinished(totalFrames: Long) {
        // „fLaC“(4) + Blockkopf(4): STREAMINFO beginnt bei Byte 8 (Typ 0 im ersten Blockkopf).
        if (out.length() < 42) return
        out.seek(4)
        if (out.readUnsignedByte() and 0x7F != 0) return
        // Byte 12: min/max Framegröße, je 24 Bit.
        if (maxFrameBytes > 0) {
            out.seek(12)
            out.write(int24(minFrameBytes) + int24(maxFrameBytes))
        }
        // Byte 18: 20 Bit Samplerate, 3 Bit Kanäle, 5 Bit Bittiefe, 36 Bit Samples.
        out.seek(18)
        val packed = ByteArray(8).also { out.readFully(it) }
        var bits = 0L
        for (b in packed) bits = (bits shl 8) or (b.toLong() and 0xFF)
        bits = (bits and 0x0FFFFFFFFFL.inv()) or (totalFrames and 0x0FFFFFFFFFL)
        for (i in 7 downTo 0) {
            packed[i] = (bits and 0xFF).toByte()
            bits = bits shr 8
        }
        out.seek(18)
        out.write(packed)
        // Byte 26: MD5 des unkomprimierten Audios.
        out.write(md5.digest())
    }

    private fun int24(v: Int) = byteArrayOf((v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    override fun release() {
        super.release()
        runCatching { out.close() }
    }

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private companion object {
        val FLAC_MAGIC = "fLaC".toByteArray()
    }
}

/** Opus in einem OGG-Container (Android 10+). */
@RequiresApi(Build.VERSION_CODES.Q)
class OpusSink(output: File, private val bitrate: Int) : EncoderSink(MediaFormat.MIMETYPE_AUDIO_OPUS) {
    private val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
    private var track = -1

    override fun configure(format: MediaFormat) {
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate.coerceIn(6_000, 256_000))
    }

    override fun onOutputFormat(format: MediaFormat) {
        track = muxer.addTrack(format)
        muxer.start()
    }

    override fun onEncoded(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 || track < 0) return
        muxer.writeSampleData(track, data, info)
    }

    override fun finish() {
        super.finish()
        if (track >= 0) muxer.stop()
    }

    override fun release() {
        super.release()
        runCatching { muxer.release() }
    }

    companion object {
        /** Samplerates, die der Opus-Encoder annimmt. */
        val SAMPLE_RATES = setOf(8_000, 12_000, 16_000, 24_000, 48_000)
    }
}

/**
 * AAC in einem MP4-Container (.m4a) über den Encoder des Systems.
 * Schneller als der Weg über Media3 Transformer: Dekodieren und Kodieren laufen parallel (siehe [PipelineSink]).
 */
class AacSink(output: File, private val bitrate: Int) : EncoderSink(MediaFormat.MIMETYPE_AUDIO_AAC) {
    private val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1

    override fun configure(format: MediaFormat) {
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate.coerceIn(32_000, 320_000))
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
    }

    override fun onOutputFormat(format: MediaFormat) {
        track = muxer.addTrack(format)
        muxer.start()
    }

    override fun onEncoded(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 || track < 0) return
        muxer.writeSampleData(track, data, info)
    }

    override fun finish() {
        super.finish()
        if (track >= 0) muxer.stop()
    }

    override fun release() {
        super.release()
        runCatching { muxer.release() }
    }

    companion object {
        /** Samplerates, die jeder AAC-Encoder annimmt. */
        val SAMPLE_RATES = setOf(8_000, 11_025, 12_000, 16_000, 22_050, 24_000, 32_000, 44_100, 48_000)
    }
}

// ───────────────────────── Vorverarbeitung ─────────────────────────

/** Mischt Mehrkanal-Ton (z. B. 5.1) auf Stereo herunter. */
class StereoDownmix(private val next: PcmSink) : PcmSink {
    private var channels = 0

    override fun start(sampleRate: Int, channels: Int) {
        this.channels = channels
        next.start(sampleRate, minOf(channels, 2))
    }

    private var out = ByteBuffer.allocate(0)

    override fun write(pcm: ByteBuffer) {
        if (channels <= 2) return next.write(pcm)
        val input = pcm.order(ByteOrder.LITTLE_ENDIAN)
        val frames = input.remaining() / (channels * 2)
        if (out.capacity() < frames * 4) out = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        out.clear()
        val base = input.position()
        repeat(frames) { f ->
            // Reihenfolge laut Android: FL, FR, FC, LFE, BL, BR … (direkt gelesen, ohne Hilfs-Array pro Sample)
            val p = base + f * channels * 2
            val center = input.getShort(p + 4) * 0.707f
            val backL = if (channels > 5) input.getShort(p + 8) * 0.707f else 0f
            val backR = if (channels > 5) input.getShort(p + 10) * 0.707f else 0f
            val l = (input.getShort(p) + center + backL) / 1.707f
            val r = (input.getShort(p + 2) + center + backR) / 1.707f
            out.putShort(l.toInt().coerceIn(-32768, 32767).toShort())
            out.putShort(r.toInt().coerceIn(-32768, 32767).toShort())
        }
        input.position(input.limit())
        out.flip()
        next.write(out)
    }

    override fun finish() = next.finish()
    override fun release() = next.release()
}

/** Rechnet auf eine andere Samplerate um (Media3 Sonic). */
@OptIn(UnstableApi::class)
class Resampler(private val targetRate: (Int) -> Int, private val next: PcmSink) : PcmSink {
    private var sonic: SonicAudioProcessor? = null

    override fun start(sampleRate: Int, channels: Int) {
        val target = targetRate(sampleRate)
        if (target != sampleRate) {
            sonic = SonicAudioProcessor().apply {
                setOutputSampleRateHz(target)
                configure(AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT))
                flush(AudioProcessor.StreamMetadata.DEFAULT)
            }
        }
        next.start(target, channels)
    }

    override fun write(pcm: ByteBuffer) {
        val s = sonic ?: return next.write(pcm)
        val direct = ByteBuffer.allocateDirect(pcm.remaining()).order(ByteOrder.nativeOrder())
        direct.put(pcm).flip()
        while (direct.hasRemaining()) {
            s.queueInput(direct)
            pump(s)
        }
    }

    override fun finish() {
        sonic?.let { s ->
            s.queueEndOfStream()
            while (!s.isEnded) pump(s)
        }
        next.finish()
    }

    private fun pump(s: SonicAudioProcessor) {
        val out = s.output
        if (out.hasRemaining()) next.write(out.order(ByteOrder.LITTLE_ENDIAN))
    }

    override fun release() {
        sonic?.reset()
        next.release()
    }
}
