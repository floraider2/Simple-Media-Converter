package com.simpleconverter.app.convert

import android.media.MediaCodec
import android.media.MediaMuxer
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.Muxer
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer

/**
 * WebM-Ausgabe für Media3 Transformer über den MediaMuxer des Systems.
 * Media3 selbst schreibt nur MP4; WebM erlaubt VP8/VP9 + Opus/Vorbis.
 *
 * WebM kennt keine Drehungs-Angabe – deshalb muss der Transformer mit
 * `setPortraitEncodingEnabled(true)` laufen, damit Hochkant-Videos auch hochkant kodiert werden.
 */
@OptIn(UnstableApi::class)
class WebmMuxer private constructor(path: String) : Muxer {

    class Factory : Muxer.Factory {
        override fun create(path: String): Muxer = WebmMuxer(path)

        override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> = when (trackType) {
            C.TRACK_TYPE_VIDEO -> ImmutableList.of(MimeTypes.VIDEO_VP9, MimeTypes.VIDEO_VP8)
            C.TRACK_TYPE_AUDIO -> ImmutableList.of(MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_VORBIS)
            else -> ImmutableList.of()
        }
    }

    private class Track(val index: Int) : Muxer.TrackToken

    private val muxer = try {
        MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM)
    } catch (e: Exception) {
        throw Muxer.MuxerException("WebM-Datei konnte nicht angelegt werden", e)
    }
    private var started = false

    override fun addTrack(format: Format): Muxer.TrackToken {
        if (started) throw Muxer.MuxerException("Spur nach dem Start hinzugefügt", IllegalStateException())
        return try {
            Track(muxer.addTrack(MediaFormatUtil.createMediaFormatFromFormat(format)))
        } catch (e: Exception) {
            throw Muxer.MuxerException("Spur ${format.sampleMimeType} passt nicht in WebM", e)
        }
    }

    override fun writeSampleData(trackToken: Muxer.TrackToken, data: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        try {
            if (!started) {
                muxer.start()
                started = true
            }
            muxer.writeSampleData((trackToken as Track).index, data, bufferInfo)
        } catch (e: Exception) {
            throw Muxer.MuxerException("Schreiben in WebM fehlgeschlagen", e)
        }
    }

    /** Metadaten wie Drehung oder Aufnahmezeit kann MediaMuxer in WebM nicht ablegen. */
    override fun addMetadataEntry(metadataEntry: Metadata.Entry) = Unit

    override fun close() {
        try {
            if (started) muxer.stop()
        } catch (e: Exception) {
            throw Muxer.MuxerException("WebM-Datei konnte nicht abgeschlossen werden", e)
        } finally {
            muxer.release()
        }
    }
}
