package com.simpleconverter.app.convert.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MediaExtractorCompat
import java.nio.ByteBuffer

/**
 * Gemeinsame Schnittstelle für die beiden Leser: den Media3-Leser (läuft im App-Prozess, schnell)
 * und den von Android (eigener Systemprozess, aber robuster bei manchen Formaten).
 */
internal interface AudioSource {
    val trackCount: Int
    fun getTrackFormat(index: Int): MediaFormat
    fun selectTrack(index: Int)

    /** Springt zum Sync-Punkt vor [timeUs]. */
    fun seekTo(timeUs: Long)
    fun readSampleData(buffer: ByteBuffer, offset: Int): Int
    val sampleTime: Long

    /** Größe des nächsten Blocks, -1 wenn unbekannt oder am Ende. */
    val sampleSize: Long
    fun advance(): Boolean
    fun release()

    /** Nur der Media3-Leser liefert Blockgrößen zuverlässig genug zum Bündeln. */
    val canBatch: Boolean

    companion object {
        private const val TAG = "AudioSource"

        /**
         * Media3 1.5 stürzt beim Lesen von FLAC am Dateiende ab (IndexOutOfBoundsException in
         * SampleDataQueue). FLAC in anderen Containern daher mit dem Leser von Android.
         */
        /** Beginnt die Datei mit „fLaC“ (oder einem ID3-Block, wie bei manchen FLAC-Dateien)? */
        private fun looksLikeFlac(context: Context, uri: Uri): Boolean = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val head = ByteArray(4)
                input.read(head) == 4 && (String(head, Charsets.ISO_8859_1) == "fLaC" || String(head, 0, 3, Charsets.ISO_8859_1) == "ID3")
            } ?: false
        }.getOrDefault(false)

        private val FRAMEWORK_ONLY = setOf(MediaFormat.MIMETYPE_AUDIO_FLAC)

        /**
         * FLAC-Dateien liest [FlacSource]. Alles andere der Media3-Leser, bei Problemen der von Android.
         */
        fun open(context: Context, uri: Uri): AudioSource {
            if (looksLikeFlac(context, uri)) {
                runCatching { return FlacSource(context, uri) }
                    .onFailure { Log.w(TAG, "Eigener FLAC-Leser kann die Datei nicht öffnen", it) }
            }
            val compat = runCatching { Media3Source(context, uri) }
                .onFailure { Log.w(TAG, "Media3-Leser kann die Datei nicht öffnen", it) }
                .getOrNull()
            if (compat != null) {
                val mimes = (0 until compat.trackCount).mapNotNull { compat.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                if (mimes.isNotEmpty() && mimes.none { it in FRAMEWORK_ONLY }) return compat
                compat.release()
            }
            return FrameworkSource(context, uri)
        }
    }
}

@OptIn(UnstableApi::class)
private class Media3Source(context: Context, uri: Uri) : AudioSource {
    private val extractor = MediaExtractorCompat(context)

    init {
        try {
            // Achtung: setDataSource(context, uri, headers) liefert in Media3 1.5 keine Daten.
            extractor.setDataSource(uri, 0)
        } catch (e: Exception) {
            extractor.release()
            throw e
        }
    }

    override val trackCount get() = extractor.trackCount
    override fun getTrackFormat(index: Int): MediaFormat = extractor.getTrackFormat(index)
    override fun selectTrack(index: Int) = extractor.selectTrack(index)
    override fun seekTo(timeUs: Long) = extractor.seekTo(timeUs, MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC)
    override fun readSampleData(buffer: ByteBuffer, offset: Int) = extractor.readSampleData(buffer, offset)
    override val sampleTime get() = extractor.sampleTime
    override val sampleSize get() = extractor.sampleSize
    override fun advance() = extractor.advance()
    override fun release() = extractor.release()
    override val canBatch get() = true
}

private class FrameworkSource(context: Context, uri: Uri) : AudioSource {
    private val extractor = MediaExtractor()

    init {
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            extractor.release()
            throw e
        }
    }

    override val trackCount get() = extractor.trackCount
    override fun getTrackFormat(index: Int): MediaFormat = extractor.getTrackFormat(index)
    override fun selectTrack(index: Int) = extractor.selectTrack(index)
    override fun seekTo(timeUs: Long) = extractor.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    override fun readSampleData(buffer: ByteBuffer, offset: Int) = extractor.readSampleData(buffer, offset)
    override val sampleTime get() = extractor.sampleTime
    override val sampleSize get() = if (Build.VERSION.SDK_INT >= 28) extractor.sampleSize else -1L
    override fun advance() = extractor.advance()
    override fun release() = extractor.release()
    override val canBatch get() = false
}
