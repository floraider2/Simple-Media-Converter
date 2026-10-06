package com.simpleconverter.app.convert.audio

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.simpleconverter.app.model.ConversionSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Lautheit nach ITU-R BS.1770-4 / EBU R128: K-Filter, 400-ms-Blöcke mit 75 % Überlappung,
 * absolutes Gate −70 LUFS, relatives Gate −10 LU. Misst zusätzlich den Spitzenpegel.
 *
 * Ist selbst ein [PcmSink] und kann deshalb direkt hinter den [PcmDecoder] gehängt werden.
 */
class LoudnessMeter : PcmSink {
    private var channels = 0
    private lateinit var filters: Array<KFilter>
    private lateinit var weights: DoubleArray
    private var samplesPerSubBlock = 0
    private var subBlockFill = 0
    private lateinit var subBlockSum: DoubleArray

    /** Gewichtete Leistung je 100-ms-Teilblock; ein 400-ms-Block = vier aufeinanderfolgende. */
    private val subBlocks = ArrayList<Double>()
    private var peak = 0

    override fun start(sampleRate: Int, channels: Int) {
        this.channels = channels
        filters = Array(channels) { KFilter(sampleRate) }
        weights = DoubleArray(channels) { channelWeight(it, channels) }
        samplesPerSubBlock = sampleRate / 10
        subBlockSum = DoubleArray(channels)
    }

    override fun write(pcm: ByteBuffer) {
        val shorts = pcm.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        while (shorts.remaining() >= channels) {
            for (c in 0 until channels) {
                val s = shorts.get().toInt()
                peak = max(peak, abs(s))
                val y = filters[c].process(s / 32768.0)
                subBlockSum[c] += y * y
            }
            if (++subBlockFill == samplesPerSubBlock) {
                var z = 0.0
                for (c in 0 until channels) {
                    z += weights[c] * subBlockSum[c] / samplesPerSubBlock
                    subBlockSum[c] = 0.0
                }
                subBlocks += z
                subBlockFill = 0
            }
        }
        pcm.position(pcm.limit())
    }

    override fun finish() = Unit
    override fun release() = Unit

    /** Integrierte Lautheit in LUFS; −∞ bei Stille oder zu kurzer Datei (< 400 ms). */
    val integratedLufs: Double
        get() {
            val blocks = (3 until subBlocks.size).map { i -> (subBlocks[i - 3] + subBlocks[i - 2] + subBlocks[i - 1] + subBlocks[i]) / 4 }
            val aboveAbsolute = blocks.filter { lufs(it) > ABSOLUTE_GATE }
            if (aboveAbsolute.isEmpty()) return Double.NEGATIVE_INFINITY
            val relativeGate = lufs(aboveAbsolute.average()) - 10.0
            val gated = aboveAbsolute.filter { lufs(it) > relativeGate }
            return if (gated.isEmpty()) Double.NEGATIVE_INFINITY else lufs(gated.average())
        }

    /** Höchster Sample-Pegel in dBFS. */
    val peakDbfs: Double get() = if (peak == 0) Double.NEGATIVE_INFINITY else 20 * log10(peak / 32768.0)

    private fun lufs(power: Double) = -0.691 + 10 * log10(power)

    /** Kanalgewichte nach BS.1770 in Android-Reihenfolge: FL, FR, FC, LFE, BL, BR … */
    private fun channelWeight(index: Int, count: Int): Double = when {
        count < 6 -> 1.0
        index == 3 -> 0.0          // LFE zählt nicht
        index == 4 || index == 5 -> 1.41
        else -> 1.0
    }

    /** Zweistufiges K-Filter (Höhenanhebung + Hochpass), Koeffizienten für jede Samplerate berechnet. */
    private class KFilter(rate: Int) {
        private val shelf = highShelf(rate)
        private val highPass = highPass(rate)

        fun process(x: Double) = highPass.process(shelf.process(x))

        private companion object {
            /** Stufe 1: High-Shelf (+4 dB ab ca. 1,7 kHz). */
            fun highShelf(rate: Int): Biquad {
                val g = 3.999843853973347
                val q = 0.7071752369554196
                val k = tan(PI * 1681.974450955533 / rate)
                val vh = 10.0.pow(g / 20)
                val vb = vh.pow(0.4996667741545416)
                val a0 = 1 + k / q + k * k
                return Biquad(
                    b0 = (vh + vb * k / q + k * k) / a0,
                    b1 = 2 * (k * k - vh) / a0,
                    b2 = (vh - vb * k / q + k * k) / a0,
                    a1 = 2 * (k * k - 1) / a0,
                    a2 = (1 - k / q + k * k) / a0,
                )
            }

            /** Stufe 2: Hochpass (ca. 38 Hz). */
            fun highPass(rate: Int): Biquad {
                val q = 0.5003270373238773
                val k = tan(PI * 38.13547087602444 / rate)
                val a0 = 1 + k / q + k * k
                return Biquad(
                    b0 = 1.0, b1 = -2.0, b2 = 1.0,
                    a1 = 2 * (k * k - 1) / a0,
                    a2 = (1 - k / q + k * k) / a0,
                )
            }
        }
    }

    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    companion object {
        private const val ABSOLUTE_GATE = -70.0
    }
}

object Loudness {
    /** Ziel wie bei Streaming-Diensten (Spotify, YouTube). */
    const val TARGET_LUFS = -14.0

    /** Spitzen bleiben mindestens so weit unter der Vollaussteuerung. */
    const val PEAK_CEILING_DBFS = -1.0

    /**
     * Verstärkung in dB, die [measuredLufs] auf das Ziel bringt, ohne dass die Spitze
     * [PEAK_CEILING_DBFS] überschreitet. 0 bei Stille.
     */
    fun gainDb(measuredLufs: Double, peakDbfs: Double): Double {
        if (measuredLufs.isInfinite()) return 0.0
        val wanted = TARGET_LUFS - measuredLufs
        val allowed = if (peakDbfs.isInfinite()) wanted else PEAK_CEILING_DBFS - peakDbfs
        return min(wanted, allowed)
    }

    fun linear(gainDb: Double): Float = 10.0.pow(gainDb / 20).toFloat()

    /** Erster Durchgang: Tonspur (im gewählten Ausschnitt) dekodieren und messen. Ergebnis: Verstärkung in dB. */
    suspend fun measureGainDb(context: Context, input: Uri, settings: ConversionSettings, onProgress: (Int) -> Unit): Double =
        withContext(Dispatchers.IO) {
            val meter = LoudnessMeter()
            PcmDecoder.decode(
                context, input, meter, onProgress,
                startUs = (settings.trimStartMs ?: 0L) * 1000,
                endUs = settings.trimEndMs?.let { it * 1000 },
            )
            gainDb(meter.integratedLufs, meter.peakDbfs)
        }
}

/** Verstärkt 16-Bit-PCM um einen festen Faktor, mit Begrenzung statt Übersteuerung. */
class GainSink(private val next: PcmSink, private val gain: Float) : PcmSink {
    override fun start(sampleRate: Int, channels: Int) = next.start(sampleRate, channels)

    override fun write(pcm: ByteBuffer) {
        if (gain == 1f) return next.write(pcm)
        val input = pcm.order(ByteOrder.LITTLE_ENDIAN)
        val out = ByteBuffer.allocate(input.remaining()).order(ByteOrder.LITTLE_ENDIAN)
        while (input.remaining() >= 2) out.putShort(applyGain(input.short, gain))
        out.flip()
        next.write(out)
    }

    override fun finish() = next.finish()
    override fun release() = next.release()
}

internal fun applyGain(sample: Short, gain: Float): Short =
    (sample * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

/** Dieselbe Verstärkung für den Media3-Weg (Video, M4A). */
@OptIn(UnstableApi::class)
class GainAudioProcessor(private val gain: Float) : BaseAudioProcessor() {

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return if (gain == 1f) AudioProcessor.AudioFormat.NOT_SET else inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        while (inputBuffer.remaining() >= 2) out.putShort(applyGain(inputBuffer.short, gain))
        out.flip()
    }
}
