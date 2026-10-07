package com.simpleconverter.app.convert.wmv

import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer

/**
 * WMV/WMA (ASF-Container) über FFmpeg in Software – auf jedem Gerät gleich, unabhängig davon,
 * ob der Hersteller passende Decoder mitbringt. Nur für ASF-Dateien; alles andere läuft wie bisher.
 *
 * Nicht threadsicher: ein Decoder gehört zu genau einem Thread.
 */
class AsfDecoder private constructor(private var handle: Long) : Closeable {

    /** Eckdaten der Datei; fehlende Spuren mit 0. */
    class Info(
        val durationUs: Long,
        val width: Int,
        val height: Int,
        val frameRate: Float,
        val sampleRate: Int,
        val channels: Int,
        val videoCodec: String?,
        val audioCodec: String?,
    ) {
        val hasVideo get() = videoCodec != null && width > 0 && height > 0
        val hasAudio get() = audioCodec != null && sampleRate > 0 && channels > 0
    }

    enum class Result { VIDEO, AUDIO, END }

    val info: Info = run {
        val v = nativeInfo(handle)
        val codecs = nativeCodecs(handle)
        Info(
            durationUs = v[0],
            width = v[1].toInt(),
            height = v[2].toInt(),
            frameRate = v[3] / 1000f,
            sampleRate = v[4].toInt(),
            channels = v[5].toInt(),
            videoCodec = codecs[0].ifEmpty { null },
            audioCodec = codecs[1].ifEmpty { null },
        )
    }

    /** Ton landet hier als 16-Bit-PCM (verschachtelt). Reicht für jeden WMA-Block. */
    val audio: ByteBuffer = ByteBuffer.allocateDirect(AUDIO_BUFFER_BYTES)

    /** Zeitstempel des zuletzt gelesenen Bilds bzw. Tonblocks (ab Dateianfang). */
    val ptsUs get() = nativePtsUs(handle)

    /** Decoder öffnen; nur die gewünschten Spuren werden dekodiert. */
    fun start(video: Boolean, audio: Boolean) {
        if (!nativeStart(handle, video, audio)) throw IOException("WMV/WMA-Decoder lässt sich nicht starten")
    }

    fun seekTo(timeUs: Long) {
        nativeSeek(handle, timeUs)
    }

    /**
     * Nächstes Bild oder nächsten Tonblock dekodieren. Bei [Result.AUDIO] steht das PCM in [audio]
     * (Position 0 bis Limit). Ein Bild muss mit [copyTo] oder [dropFrame] abgeholt werden.
     */
    fun read(): Result = when (val r = nativeRead(handle, audio)) {
        1 -> Result.VIDEO
        2 -> {
            audio.clear().limit(nativeAudioBytes(handle))
            Result.AUDIO
        }
        0 -> Result.END
        else -> throw IOException("WMV/WMA-Dekodierung fehlgeschlagen ($r)")
    }

    /** Aktuelles Bild in ein Android-Bild (YUV_420_888 oder RGBA_8888) schreiben. */
    fun copyTo(image: Image) {
        val p = image.planes
        val ok = if (image.format == ImageFormat.YUV_420_888) {
            nativeCopyYuv(
                handle,
                p[0].buffer, p[0].rowStride,
                p[1].buffer, p[1].rowStride, p[1].pixelStride,
                p[2].buffer, p[2].rowStride, p[2].pixelStride,
                image.width, image.height,
            )
        } else {
            nativeCopyRgba(handle, p[0].buffer, p[0].rowStride, image.width, image.height)
        }
        if (!ok) throw IOException("Unerwartetes Bildformat")
    }

    fun dropFrame() = nativeDropFrame(handle)

    override fun close() {
        if (handle != 0L) nativeClose(handle)
        handle = 0
    }

    private external fun nativeInfo(handle: Long): LongArray
    private external fun nativeCodecs(handle: Long): Array<String>
    private external fun nativeStart(handle: Long, video: Boolean, audio: Boolean): Boolean
    private external fun nativeSeek(handle: Long, timeUs: Long): Boolean
    private external fun nativeRead(handle: Long, audioOut: ByteBuffer): Int
    private external fun nativePtsUs(handle: Long): Long
    private external fun nativeAudioBytes(handle: Long): Int
    private external fun nativeCopyYuv(
        handle: Long, y: ByteBuffer, yStride: Int, u: ByteBuffer, uStride: Int, uPixel: Int, v: ByteBuffer, vStride: Int, vPixel: Int,
        maxWidth: Int, maxHeight: Int,
    ): Boolean
    private external fun nativeCopyRgba(handle: Long, rgba: ByteBuffer, stride: Int, maxWidth: Int, maxHeight: Int): Boolean
    private external fun nativeDropFrame(handle: Long)
    private external fun nativeClose(handle: Long)

    companion object {
        private const val AUDIO_BUFFER_BYTES = 1 shl 20

        init {
            System.loadLibrary("asf")
        }

        /** ASF-Dateien (WMV, WMA, ASF) beginnen mit dieser GUID. */
        private val ASF_MAGIC = byteArrayOf(
            0x30, 0x26, 0xB2.toByte(), 0x75, 0x8E.toByte(), 0x66, 0xCF.toByte(), 0x11,
            0xA6.toByte(), 0xD9.toByte(), 0x00, 0xAA.toByte(), 0x00, 0x62, 0xCE.toByte(), 0x6C,
        )

        /** Ist das eine WMV/WMA-Datei? Prüft nur den Dateianfang. */
        fun isAsf(context: Context, uri: Uri): Boolean = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val head = ByteArray(ASF_MAGIC.size)
                input.read(head) == head.size && head.contentEquals(ASF_MAGIC)
            } ?: false
        }.getOrDefault(false)

        /** Öffnet die Datei; FFmpeg liest direkt über einen eigenen Dateideskriptor. */
        fun open(context: Context, uri: Uri): AsfDecoder {
            val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Datei lässt sich nicht öffnen")
            val fd = pfd.detachFd() // gehört jetzt dem nativen Teil, der ihn auch schließt
            val handle = nativeOpen(fd)
            if (handle == 0L) throw IOException("Keine lesbare WMV/WMA-Datei")
            return AsfDecoder(handle)
        }

        @JvmStatic
        private external fun nativeOpen(fd: Int): Long
    }
}
