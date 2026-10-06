package com.simpleconverter.app.convert.audio

import android.content.Context
import android.net.Uri
import android.os.Build
import com.simpleconverter.app.R
import com.simpleconverter.app.convert.ConversionException
import com.simpleconverter.app.model.ConversionSettings
import com.simpleconverter.app.model.OutputFormat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tonspur → WAV, FLAC, Opus, M4A (AAC) oder MP3. Dekodiert wird immer mit MediaCodec,
 * kodiert je nach Ziel mit dem System-Encoder (FLAC, Opus, AAC) oder LAME (MP3).
 * „Original-Ton“ (M4A ohne Neu-Kodieren) läuft dagegen über Media3, siehe VideoConverter.
 */
object AudioConverter {

    suspend fun convert(
        context: Context,
        input: Uri,
        output: File,
        settings: ConversionSettings,
        onProgress: (Int) -> Unit,
        gainDb: Double = 0.0,
    ) = withContext(Dispatchers.IO) {
        // Kodieren auf eigenem Thread (außer WAV, das ist nur Kopieren); die Verstärkung rechnet der Dekoder-Thread.
        val sink = sinkFor(output, settings)
            .let { if (settings.format == OutputFormat.WAV) it else PipelineSink(it) }
            .let { if (gainDb == 0.0) it else GainSink(it, Loudness.linear(gainDb)) }
        PcmDecoder.decode(
            context, input, sink, onProgress,
            startUs = (settings.trimStartMs ?: 0L) * 1000,
            endUs = settings.trimEndMs?.let { it * 1000 },
        )
    }

    private fun sinkFor(output: File, settings: ConversionSettings): PcmSink = when (settings.format) {
        OutputFormat.WAV -> WavSink(output)
        OutputFormat.FLAC -> FlacSink(output)
        OutputFormat.MP3 -> StereoDownmix(Mp3Sink(output, settings.audioBitrate))
        OutputFormat.M4A -> StereoDownmix(
            Resampler(
                targetRate = { rate -> if (rate in AacSink.SAMPLE_RATES) rate else 48_000 },
                next = AacSink(output, settings.audioBitrate),
            )
        )
        OutputFormat.OPUS -> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                throw ConversionException(R.string.err_opus_needs_android10)
            }
            StereoDownmix(
                Resampler(
                    targetRate = { rate -> if (rate in OpusSink.SAMPLE_RATES) rate else 48_000 },
                    next = OpusSink(output, settings.audioBitrate),
                )
            )
        }
        else -> error("Not an audio format: ${settings.format}")
    }
}
