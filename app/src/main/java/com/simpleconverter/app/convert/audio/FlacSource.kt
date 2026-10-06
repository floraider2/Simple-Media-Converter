package com.simpleconverter.app.convert.audio

import android.content.Context
import android.media.MediaFormat
import android.net.Uri
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Eigener Leser für FLAC-Dateien: zerlegt die Datei in Frames und reicht sie unverändert an den Dekoder.
 *
 * Warum nicht die Leser von Android oder Media3? Beide liefern bei manchen gültigen Dateien falsche
 * Blöcke (mehrere Frames zusammen bzw. Absturz am Dateiende). FLAC ist einfach aufgebaut: Jeder Frame
 * beginnt mit einem Sync-Code und trägt seine Nummer und zwei Prüfsummen. Eine Frame-Grenze gilt nur,
 * wenn Kopf-Prüfsumme (CRC-8), laufende Nummer und Frame-Prüfsumme (CRC-16) stimmen – Fehltreffer
 * mitten in den Audiodaten sind damit praktisch ausgeschlossen.
 */
internal class FlacSource(private val context: Context, private val uri: Uri) : AudioSource {

    private class StreamInfo(
        val sampleRate: Int,
        val channels: Int,
        val maxBlockSize: Int,
        val minFrameSize: Int,
        val maxFrameSize: Int,
        val bitsPerSample: Int,
        val totalSamples: Long,
        val raw: ByteArray,
    )

    private class Header(val number: Long, val blockSize: Int, val variable: Boolean)

    private val info: StreamInfo
    private var stream: InputStream
    private var buf = ByteArray(1 shl 20)
    private var len = 0 // gültige Bytes in buf
    private var pos = 0 // Anfang des aktuellen Frames in buf
    private var eof = false

    private var current: Header? = null
    private var currentSize = -1
    private var fixedBlockSize = 0

    init {
        stream = openStream()
        info = try {
            readMetadata()
        } catch (e: Exception) {
            stream.close()
            throw e
        }
        firstFrame()
    }

    // ───────────── AudioSource ─────────────

    override val trackCount get() = 1

    override fun getTrackFormat(index: Int): MediaFormat =
        MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, info.sampleRate, info.channels).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxFrameBytes())
            // Der Dekoder erwartet „fLaC“ + STREAMINFO-Block (als letzter Metadaten-Block markiert).
            val csd = ByteArray(4 + 4 + 34)
            "fLaC".toByteArray().copyInto(csd)
            csd[4] = 0x80.toByte()
            csd[7] = 34
            info.raw.copyInto(csd, 8)
            setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            if (info.totalSamples > 0) setLong(MediaFormat.KEY_DURATION, info.totalSamples * 1_000_000 / info.sampleRate)
        }

    override fun selectTrack(index: Int) = Unit

    override fun seekTo(timeUs: Long) {
        // Jeder FLAC-Frame ist ein Sync-Punkt: von vorn bis zum Frame laufen, der timeUs enthält.
        stream.close()
        stream = openStream()
        len = 0
        pos = 0
        eof = false
        readMetadata()
        firstFrame()
        while (currentSize >= 0 && endTimeUs() <= timeUs) advance()
    }

    override fun readSampleData(buffer: ByteBuffer, offset: Int): Int {
        if (currentSize < 0) return -1
        require(buffer.capacity() - offset >= currentSize) { "Puffer zu klein für FLAC-Frame ($currentSize Bytes)" }
        buffer.limit(buffer.capacity()).position(offset)
        buffer.put(buf, pos, currentSize)
        buffer.limit(offset + currentSize).position(offset)
        return currentSize
    }

    override val sampleTime: Long
        get() = current?.let { startSample(it) * 1_000_000 / info.sampleRate } ?: -1L

    override val sampleSize: Long get() = currentSize.toLong()

    override fun advance(): Boolean {
        val h = current ?: return false
        pos += currentSize
        nextFrame(expected = h.number + if (h.variable) h.blockSize.toLong() else 1L)
        return currentSize >= 0
    }

    override fun release() {
        runCatching { stream.close() }
    }

    override val canBatch get() = false

    // ───────────── Einlesen ─────────────

    private fun openStream(): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw IOException("Datei lässt sich nicht öffnen")

    /** Liest bis zu den Audiodaten; ein vorangestellter ID3-Block wird übersprungen. */
    private fun readMetadata(): StreamInfo {
        var head = readFully(4)
        if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
            val rest = readFully(6)
            val flags = rest[1].toInt()
            val size = (rest[2].toInt() and 0x7F shl 21) or (rest[3].toInt() and 0x7F shl 14) or
                (rest[4].toInt() and 0x7F shl 7) or (rest[5].toInt() and 0x7F)
            skipFully(size.toLong() + if (flags and 0x10 != 0) 10 else 0)
            head = readFully(4)
        }
        if (!head.contentEquals("fLaC".toByteArray())) throw IOException("Keine FLAC-Datei")
        var streamInfo: StreamInfo? = null
        do {
            val block = readFully(4)
            val last = block[0].toInt() and 0x80 != 0
            val type = block[0].toInt() and 0x7F
            val length = (block[1].u() shl 16) or (block[2].u() shl 8) or block[3].u()
            if (type == 0 && length >= 34) {
                val raw = readFully(34)
                skipFully(length - 34L)
                streamInfo = parseStreamInfo(raw)
            } else {
                skipFully(length.toLong())
            }
        } while (!last)
        return streamInfo ?: throw IOException("FLAC ohne STREAMINFO")
    }

    private fun parseStreamInfo(raw: ByteArray): StreamInfo {
        var bits = 0L
        for (i in 10 until 18) bits = (bits shl 8) or raw[i].u().toLong()
        val rate = (bits ushr 44).toInt()
        if (rate <= 0) throw IOException("FLAC ohne Samplerate")
        return StreamInfo(
            sampleRate = rate,
            channels = ((bits ushr 41) and 0x7).toInt() + 1,
            maxBlockSize = (raw[2].u() shl 8) or raw[3].u(),
            minFrameSize = (raw[4].u() shl 16) or (raw[5].u() shl 8) or raw[6].u(),
            maxFrameSize = (raw[7].u() shl 16) or (raw[8].u() shl 8) or raw[9].u(),
            bitsPerSample = ((bits ushr 36) and 0x1F).toInt() + 1,
            totalSamples = bits and 0xFFFFFFFFFL,
            raw = raw,
        )
    }

    /** Größter möglicher Frame: angegeben oder unkomprimiert plus Reserve. */
    private fun maxFrameBytes(): Int {
        if (info.maxFrameSize > 0) return info.maxFrameSize + 64
        val block = if (info.maxBlockSize > 0) info.maxBlockSize else 65_535
        return block * info.channels * ((info.bitsPerSample + 7) / 8) + 1024
    }

    private fun readFully(n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = stream.read(out, off, n - off)
            if (r < 0) throw IOException("FLAC-Datei zu kurz")
            off += r
        }
        return out
    }

    private fun skipFully(n: Long) {
        var left = n
        while (left > 0) {
            val s = stream.skip(left)
            if (s <= 0) {
                if (stream.read() < 0) throw IOException("FLAC-Datei zu kurz")
                left--
            } else {
                left -= s
            }
        }
    }

    /** Sorgt dafür, dass ab [pos] mindestens [n] Bytes im Puffer liegen (oder das Dateiende erreicht ist). */
    private fun ensure(n: Int) {
        if (len - pos >= n || eof) return
        if (pos > 0) {
            buf.copyInto(buf, 0, pos, len)
            len -= pos
            pos = 0
        }
        if (n > buf.size) {
            if (n > MAX_BUFFER) throw IOException("FLAC-Frame zu groß")
            buf = buf.copyOf(maxOf(n, buf.size * 2).coerceAtMost(MAX_BUFFER))
        }
        while (len < n && !eof) {
            val r = stream.read(buf, len, buf.size - len)
            if (r < 0) eof = true else len += r
        }
    }

    // ───────────── Frames ─────────────

    private fun firstFrame() {
        current = null
        currentSize = -1
        fixedBlockSize = 0
        // Den ersten gültigen Frame-Kopf suchen (normalerweise direkt am Anfang).
        while (true) {
            ensure(MAX_HEADER)
            if (len - pos < MIN_HEADER) return
            val h = parseHeader(pos, expected = null)
            if (h != null) {
                if (!h.variable) fixedBlockSize = h.blockSize
                findEnd(h)
                return
            }
            pos++
        }
    }

    private fun nextFrame(expected: Long) {
        current = null
        currentSize = -1
        ensure(MAX_HEADER)
        if (len - pos < MIN_HEADER) return
        val h = parseHeader(pos, expected) ?: parseHeader(pos, expected = null) ?: return
        findEnd(h)
    }

    /** Sucht das Ende des Frames ab [pos]: den nächsten Kopf, bei dem Nummer und beide Prüfsummen stimmen. */
    private fun findEnd(h: Header) {
        current = h
        val next = h.number + if (h.variable) h.blockSize.toLong() else 1L
        var j = maxOf(MIN_HEADER + 2, info.minFrameSize)
        while (true) {
            ensure(j + MAX_HEADER)
            val available = len - pos
            if (j + MIN_HEADER > available) {
                // Dateiende: der Rest ist der letzte Frame (ohne ein angehängtes ID3v1-Tag).
                currentSize = available
                val tag = pos + available - 128
                if (available > 128 + MIN_HEADER && buf[tag] == 'T'.code.toByte() && buf[tag + 1] == 'A'.code.toByte() &&
                    buf[tag + 2] == 'G'.code.toByte()
                ) {
                    currentSize -= 128
                }
                if (currentSize <= MIN_HEADER) {
                    current = null
                    currentSize = -1
                }
                return
            }
            val at = pos + j
            if (buf[at] == SYNC0 && (buf[at + 1].toInt() and 0xFE) == 0xF8 &&
                parseHeader(at, next) != null && crc16(buf, pos, j - 2) == ((buf[at - 2].u() shl 8) or buf[at - 1].u())
            ) {
                currentSize = j
                return
            }
            j++
        }
    }

    /** Liest einen Frame-Kopf bei [at]; null, wenn er ungültig ist oder die Nummer nicht passt. */
    private fun parseHeader(at: Int, expected: Long?): Header? {
        if (len - at < MIN_HEADER) return null
        if (buf[at] != SYNC0 || (buf[at + 1].toInt() and 0xFE) != 0xF8) return null
        val variable = buf[at + 1].toInt() and 1 == 1
        val bsCode = buf[at + 2].u() ushr 4
        val srCode = buf[at + 2].u() and 0xF
        val chCode = buf[at + 3].u() ushr 4
        val ssCode = (buf[at + 3].u() ushr 1) and 0x7
        if (bsCode == 0 || srCode == 15 || chCode > 10 || ssCode == 3 || buf[at + 3].u() and 1 != 0) return null
        var i = at + 4
        // Frame- bzw. Sample-Nummer, UTF-8-artig kodiert.
        val first = buf[i].u()
        val extra = when {
            first < 0x80 -> 0
            first and 0xE0 == 0xC0 -> 1
            first and 0xF0 == 0xE0 -> 2
            first and 0xF8 == 0xF0 -> 3
            first and 0xFC == 0xF8 -> 4
            first and 0xFE == 0xFC -> 5
            first == 0xFE -> 6
            else -> return null
        }
        if (len - i < 1 + extra + 5) return null // + Blockgröße/Samplerate (je bis 2) + CRC-8
        var number = (if (extra == 0) first else first and (0x3F ushr extra)).toLong()
        for (k in 1..extra) {
            val c = buf[i + k].u()
            if (c and 0xC0 != 0x80) return null
            number = (number shl 6) or (c and 0x3F).toLong()
        }
        i += 1 + extra
        val blockSize = when (bsCode) {
            1 -> 192
            in 2..5 -> 576 shl (bsCode - 2)
            6 -> buf[i++].u() + 1
            7 -> ((buf[i++].u() shl 8) or buf[i++].u()) + 1
            else -> 256 shl (bsCode - 8)
        }
        i += when (srCode) {
            12 -> 1
            13, 14 -> 2
            else -> 0
        }
        if (crc8(buf, at, i - at) != buf[i].u()) return null
        if (expected != null && number != expected) return null
        if (fixedBlockSize > 0 && variable) return null
        return Header(number, blockSize, variable)
    }

    private fun startSample(h: Header) = if (h.variable) h.number else h.number * fixedBlockSize

    private fun endTimeUs(): Long {
        val h = current ?: return Long.MAX_VALUE
        return (startSample(h) + h.blockSize) * 1_000_000 / info.sampleRate
    }

    private fun Byte.u() = toInt() and 0xFF

    private companion object {
        const val SYNC0 = 0xFF.toByte()
        const val MIN_HEADER = 6
        const val MAX_HEADER = 16
        const val MAX_BUFFER = 64 shl 20

        val CRC8 = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 0x80 != 0) (c shl 1) xor 0x07 else c shl 1 }
            c and 0xFF
        }
        val CRC16 = IntArray(256) { n ->
            var c = n shl 8
            repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x8005 else c shl 1 }
            c and 0xFFFF
        }

        fun crc8(b: ByteArray, from: Int, count: Int): Int {
            var c = 0
            for (k in from until from + count) c = CRC8[c xor (b[k].toInt() and 0xFF)]
            return c
        }

        fun crc16(b: ByteArray, from: Int, count: Int): Int {
            var c = 0
            for (k in from until from + count) c = ((c shl 8) xor CRC16[(c ushr 8) xor (b[k].toInt() and 0xFF)]) and 0xFFFF
            return c
        }
    }
}
