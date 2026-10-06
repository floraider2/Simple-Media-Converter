package com.simpleconverter.app.convert.audio

import android.content.Context
import android.net.Uri
import android.os.Build
import com.simpleconverter.app.convert.ConversionException
import com.simpleconverter.app.model.ConversionSettings
import com.simpleconverter.app.model.OutputFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Tonspur → WAV, FLAC, Opus oder MP3. Dekodiert wird immer mit MediaCodec,
 * kodiert je nach Ziel mit dem System-Encoder (FLAC, Opus) oder LAME (MP3).
 */
object AudioConverter {

    suspend fun convert(
        context: Context,
        input: Uri,
        output: File,
        settings: ConversionSettings,
        onProgress: (Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        PcmDecoder.decode(context, input, sinkFor(output, settings), onProgress)
    }

    private fun sinkFor(output: File, settings: ConversionSettings): PcmSink = when (settings.format) {
        OutputFormat.WAV -> WavSink(output)
        OutputFormat.FLAC -> FlacSink(output)
        OutputFormat.MP3 -> StereoDownmix(Mp3Sink(output, settings.audioBitrate))
        OutputFormat.OPUS -> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                throw ConversionException("Opus braucht mindestens Android 10.")
            }
            StereoDownmix(
                Resampler(
                    targetRate = { rate -> if (rate in OpusSink.SAMPLE_RATES) rate else 48_000 },
                    next = OpusSink(output, settings.audioBitrate),
                )
            )
        }
        else -> error("Kein reines Audioformat: ${settings.format}")
    }
}
