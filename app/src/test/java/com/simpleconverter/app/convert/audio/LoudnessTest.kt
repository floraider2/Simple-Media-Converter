package com.simpleconverter.app.convert.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Referenzwerte aus ITU-R BS.1770 / EBU Tech 3341: Ein 997-Hz-Sinus mit 0 dBFS auf einem
 * Kanal ergibt −3,01 LUFS; auf zwei Kanälen 0 LUFS. Jede Pegeländerung wirkt 1:1.
 */
class LoudnessTest {

    private fun measure(levelDbfs: Double, channels: Int, seconds: Int = 5, rate: Int = 48_000, freq: Double = 997.0): LoudnessMeter {
        val amplitude = 32767 * 10.0.pow(levelDbfs / 20)
        val meter = LoudnessMeter()
        meter.start(rate, channels)
        val buffer = ByteBuffer.allocate(rate * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        var n = 0L
        repeat(seconds) {
            buffer.clear()
            repeat(rate) {
                val s = (amplitude * sin(2 * PI * freq * n++ / rate)).toInt().toShort()
                repeat(channels) { buffer.putShort(s) }
            }
            buffer.flip()
            meter.write(buffer)
        }
        return meter
    }

    @Test
    fun `Sinus 0 dBFS mono ergibt -3,01 LUFS`() =
        assertEquals(-3.01, measure(0.0, channels = 1).integratedLufs, 0.1)

    @Test
    fun `Sinus 0 dBFS stereo ergibt 0 LUFS`() =
        assertEquals(0.0, measure(0.0, channels = 2).integratedLufs, 0.1)

    @Test
    fun `Pegel -20 dBFS ergibt -23 LUFS`() =
        assertEquals(-23.01, measure(-20.0, channels = 1).integratedLufs, 0.1)

    @Test
    fun `auch bei 44,1 kHz richtig`() =
        assertEquals(-23.01, measure(-20.0, channels = 1, rate = 44_100).integratedLufs, 0.1)

    @Test
    fun `Spitzenpegel wird gemessen`() =
        assertEquals(-6.0, measure(-6.0, channels = 1).peakDbfs, 0.05)

    @Test
    fun `Stille ergibt keine Lautheit und keine Verstaerkung`() {
        val meter = measure(-200.0, channels = 1)
        assertTrue(meter.integratedLufs.isInfinite())
        assertEquals(0.0, Loudness.gainDb(meter.integratedLufs, meter.peakDbfs), 0.0)
    }

    @Test
    fun `leise Datei wird bis zum Ziel angehoben`() =
        assertEquals(9.0, Loudness.gainDb(measuredLufs = -23.0, peakDbfs = -20.0), 0.001)

    @Test
    fun `Spitzenbegrenzung hat Vorrang vor dem Ziel`() =
        // Wollte +9 dB, aber Spitze bei -4 dBFS erlaubt nur +3 dB bis -1 dBFS
        assertEquals(3.0, Loudness.gainDb(measuredLufs = -23.0, peakDbfs = -4.0), 0.001)

    @Test
    fun `zu laute Datei wird abgesenkt`() =
        assertEquals(-6.0, Loudness.gainDb(measuredLufs = -8.0, peakDbfs = -0.1), 0.001)

    @Test
    fun `Verstaerkung begrenzt statt zu uebersteuern`() {
        assertEquals(Short.MAX_VALUE, applyGain(30_000, 2f))
        assertEquals(Short.MIN_VALUE, applyGain(-30_000, 2f))
        assertEquals(2_000.toShort(), applyGain(1_000, 2f))
    }
}
