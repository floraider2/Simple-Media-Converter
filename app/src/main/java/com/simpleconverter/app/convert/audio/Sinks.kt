package com.simpleconverter.app.convert.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.simpleconverter.app.convert.ConversionException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    override fun write(pcm: ByteBuffer) {
        val bytes = ByteArray(pcm.remaining())
        pcm.get(bytes)
        out.write(bytes)
        dataBytes += bytes.size
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
 */
abstract class EncoderSink(private val mime: String) : PcmSink {
    private lateinit var codec: MediaCodec
    private var sampleRate = 0
    private var channels = 0
    private var framesQueued = 0L
    private val info = MediaCodec.BufferInfo()

    protected abstract fun configure(format: MediaFormat)
    protected abstract fun onOutputFormat(format: MediaFormat)
    protected abstract fun onEncoded(data: ByteBuffer, info: MediaCodec.BufferInfo)
    protected open fun onFinished(totalFrames: Long) {}

    override fun start(sampleRate: Int, channels: Int) {
        this.sampleRate = sampleRate
        this.channels = channels
        val format = MediaFormat.createAudioFormat(mime, sampleRate, channels)
        configure(format)
        codec = try {
            MediaCodec.createEncoderByType(mime)
        } catch (e: Exception) {
            throw ConversionException("Dein Gerät kann dieses Audioformat nicht erzeugen.")
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    override fun write(pcm: ByteBuffer) {
        val frameBytes = channels * 2
        while (pcm.hasRemaining()) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) {
                drain(false)
                continue
            }
            val buffer = codec.getInputBuffer(index)!!
            buffer.clear()
            // Nur ganze Frames einreihen, damit die Zeitstempel stimmen.
            val bytes = minOf(buffer.remaining(), pcm.remaining()) / frameBytes * frameBytes
            val chunk = pcm.duplicate().apply { limit(position() + bytes) }
            buffer.put(chunk)
            pcm.position(pcm.position() + bytes)
            codec.queueInputBuffer(index, 0, bytes, framesQueued * 1_000_000 / sampleRate, 0)
            framesQueued += bytes / frameBytes
            drain(false)
        }
    }

    override fun finish() {
        while (true) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, framesQueued * 1_000_000 / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                break
            }
            drain(false)
        }
        drain(true)
        onFinished(framesQueued)
    }

    private fun drain(untilEnd: Boolean) {
        while (true) {
            // Beim Füttern nicht warten: nur abholen, was schon fertig ist. Sonst kostet jeder
            // kleine Opus-Block (20 ms) bis zu 10 ms Wartezeit – bei langen Dateien Minuten.
            val index = codec.dequeueOutputBuffer(info, if (untilEnd) TIMEOUT_US else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEnd) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onOutputFormat(codec.outputFormat)
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)!!
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    if (info.size > 0) onEncoded(buffer, info)
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    override fun release() {
        if (::codec.isInitialized) {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
    }
}

/**
 * FLAC über den System-Encoder. MediaMuxer kann kein FLAC schreiben, also bauen wir die Datei
 * selbst: Codec-Konfiguration („fLaC“ + STREAMINFO) und danach die rohen Frames.
 */
class FlacSink(private val output: File, private val compressionLevel: Int = 5) : EncoderSink(MediaFormat.MIMETYPE_AUDIO_FLAC) {
    private val out = RandomAccessFile(output, "rw").apply { setLength(0) }
    private var headerWritten = false

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
            throw ConversionException("FLAC-Encoder lieferte keinen Dateikopf.")
        }
        out.write(bytes)
    }

    /** Trägt die Gesamtzahl der Samples in STREAMINFO ein, damit Player die Dauer kennen. */
    override fun onFinished(totalFrames: Long) {
        // „fLaC“(4) + Blockkopf(4) + min/max Blockgröße(4) + min/max Framegröße(6) = Byte 18:
        // 20 Bit Samplerate, 3 Bit Kanäle, 5 Bit Bittiefe, 36 Bit Samples.
        if (out.length() < 26) return
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
    }

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

// ───────────────────────── Vorverarbeitung ─────────────────────────

/** Mischt Mehrkanal-Ton (z. B. 5.1) auf Stereo herunter. */
class StereoDownmix(private val next: PcmSink) : PcmSink {
    private var channels = 0

    override fun start(sampleRate: Int, channels: Int) {
        this.channels = channels
        next.start(sampleRate, minOf(channels, 2))
    }

    override fun write(pcm: ByteBuffer) {
        if (channels <= 2) return next.write(pcm)
        val input = pcm.order(ByteOrder.LITTLE_ENDIAN)
        val frames = input.remaining() / (channels * 2)
        val out = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) {
            val s = ShortArray(channels) { input.short }
            // Reihenfolge laut Android: FL, FR, FC, LFE, BL, BR …
            val center = if (channels > 2) s[2] * 0.707f else 0f
            val back = if (channels > 5) Pair(s[4] * 0.707f, s[5] * 0.707f) else Pair(0f, 0f)
            val l = (s[0] + center + back.first) / 1.707f
            val r = (s[1] + center + back.second) / 1.707f
            out.putShort(l.toInt().coerceIn(-32768, 32767).toShort())
            out.putShort(r.toInt().coerceIn(-32768, 32767).toShort())
        }
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
                flush()
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
