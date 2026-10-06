package com.simpleconverter.app.convert

import android.media.MediaCodecList
import android.media.MediaFormat

/** Welche Encoder bringt das Gerät mit? Ergebnis wird einmal ermittelt und gemerkt. */
object Encoders {
    private val available: Set<String> by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder }
            .flatMap { it.supportedTypes.asList() }
            .map { it.lowercase() }
            .toSet()
    }

    fun has(mime: String) = mime.lowercase() in available

    val hevc get() = has(MediaFormat.MIMETYPE_VIDEO_HEVC)
}
