package com.simpleconverter.app.convert.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Zuschneiden von PCM-Blöcken beim Kürzen (1000 Hz, Mono → 1 Frame = 1 ms = 2 Bytes). */
class ClipTest {

    private val rate = 1000
    private val channels = 1

    /** Block mit 100 Frames ab [ptsMs]; jedes Sample trägt seine eigene Nummer. */
    private fun block(): ByteBuffer {
        val b = ByteBuffer.allocate(200).order(ByteOrder.LITTLE_ENDIAN)
        repeat(100) { b.putShort(it.toShort()) }
        b.flip()
        return b
    }

    private fun us(ms: Long) = ms * 1000

    @Test
    fun `ohne Ausschnitt bleibt der Block unveraendert`() {
        val b = block()
        assertSame(b, PcmDecoder.clip(b, 0, 0, Long.MAX_VALUE, rate, channels))
    }

    @Test
    fun `Anfang mitten im Block schneidet vorne ab`() {
        val out = PcmDecoder.clip(block(), us(0), us(30), Long.MAX_VALUE, rate, channels)!!
        assertEquals(70 * 2, out.remaining())
        assertEquals(30.toShort(), out.order(ByteOrder.LITTLE_ENDIAN).getShort(out.position()))
    }

    @Test
    fun `Ende mitten im Block schneidet hinten ab`() {
        val out = PcmDecoder.clip(block(), us(0), 0, us(40), rate, channels)!!
        assertEquals(40 * 2, out.remaining())
    }

    @Test
    fun `Block ganz vor dem Anfang faellt weg`() {
        assertNull(PcmDecoder.clip(block(), us(0), us(500), Long.MAX_VALUE, rate, channels))
    }

    @Test
    fun `Block ganz nach dem Ende faellt weg`() {
        assertNull(PcmDecoder.clip(block(), us(1000), 0, us(500), rate, channels))
    }

    @Test
    fun `Block teilweise im Bereich bei spaeterem Zeitstempel`() {
        // Block deckt 200–300 ms ab, Bereich 250–280 ms → 30 Frames ab Sample 50
        val out = PcmDecoder.clip(block(), us(200), us(250), us(280), rate, channels)!!
        assertEquals(30 * 2, out.remaining())
        assertEquals(50.toShort(), out.order(ByteOrder.LITTLE_ENDIAN).getShort(out.position()))
    }
}
