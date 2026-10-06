package com.simpleconverter.app.convert.audio

import com.simpleconverter.app.R
import com.simpleconverter.app.convert.ConversionException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Dünne Hülle um die native LAME-Bibliothek (siehe src/main/cpp/mp3_jni.c). */
internal object Lame {
    init {
        System.loadLibrary("mp3")
    }

    @JvmStatic external fun nativeInit(sampleRate: Int, channels: Int, bitrateKbps: Int, quality: Int): Long
    @JvmStatic external fun nativeEncode(handle: Long, pcm: ShortArray, frames: Int, channels: Int, out: ByteArray): Int
    @JvmStatic external fun nativeFlush(handle: Long, out: ByteArray): Int
    @JvmStatic external fun nativeLameTag(handle: Long, out: ByteArray): Int
    @JvmStatic external fun nativeClose(handle: Long)
}

/** MP3 mit konstanter Bitrate über LAME. Mehr als zwei Kanäle vorher mit [StereoDownmix] reduzieren. */
class Mp3Sink(private val output: File, private val bitrate: Int) : PcmSink {
    private var handle = 0L
    private var channels = 0
    private lateinit var out: RandomAccessFile
    private var buffer = ByteArray(0)
    private var samples = ShortArray(0)

    override fun start(sampleRate: Int, channels: Int) {
        require(channels in 1..2) { "MP3 supports mono or stereo only" }
        this.channels = channels
        handle = Lame.nativeInit(sampleRate, channels, (bitrate / 1000).coerceIn(32, 320), QUALITY)
        if (handle == 0L) throw ConversionException(R.string.err_mp3_start)
        out = RandomAccessFile(output, "rw").apply { setLength(0) }
    }

    override fun write(pcm: ByteBuffer) {
        val shorts = pcm.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val count = shorts.remaining()
        if (samples.size < count) samples = ShortArray(count)
        shorts.get(samples, 0, count)
        pcm.position(pcm.limit())
        val frames = count / channels
        if (frames == 0) return
        // Faustregel aus der LAME-Doku: 1,25 × Samples + 7200 Bytes reichen immer.
        val needed = (frames * 5 / 4) + 7200
        if (buffer.size < needed) buffer = ByteArray(needed)
        val written = Lame.nativeEncode(handle, samples, frames, channels, buffer)
        if (written < 0) throw ConversionException(R.string.err_mp3_encode, written)
        out.write(buffer, 0, written)
    }

    override fun finish() {
        if (buffer.size < 7200) buffer = ByteArray(7200)
        val written = Lame.nativeFlush(handle, buffer)
        if (written > 0) out.write(buffer, 0, written)
        // Info-Tag (Länge, Encoder-Verzögerung) in den reservierten ersten Frame schreiben.
        val tag = ByteArray(2880)
        val tagSize = Lame.nativeLameTag(handle, tag)
        if (tagSize in 1..tag.size) {
            out.seek(0)
            out.write(tag, 0, tagSize)
        }
    }

    override fun release() {
        if (handle != 0L) {
            Lame.nativeClose(handle)
            handle = 0L
        }
        if (::out.isInitialized) runCatching { out.close() }
    }

    private companion object {
        /** 0 = beste, 9 = schnellste. 2 ist die übliche „hohe Qualität“. */
        const val QUALITY = 2
    }
}
