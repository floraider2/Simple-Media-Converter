package com.simpleconverter.app

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simpleconverter.app.convert.VideoConverter
import com.simpleconverter.app.convert.audio.AudioConverter
import com.simpleconverter.app.convert.audio.PcmDecoder
import com.simpleconverter.app.convert.audio.PcmSink
import com.simpleconverter.app.data.FileInspector
import com.simpleconverter.app.model.MediaKind
import com.simpleconverter.app.model.OutputFormat
import com.simpleconverter.app.model.Presets
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * WMV/WMA über FFmpeg: Erkennen und Umwandeln. Die Testdateien (8 s Testbild mit Ton, 640×360,
 * WMV2 + WMA2 stereo) liegen in androidTest/assets und wurden mit tools/testmedia erzeugt.
 * Läuft auf jedem Gerät, auch ohne WMV-Decoder des Herstellers (z. B. im Emulator).
 */
@RunWith(AndroidJUnit4::class)
class AsfConversionTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "asfTest")
    private lateinit var wmv: Uri
    private lateinit var wma: Uri

    @Before
    fun setUp() {
        dir.deleteRecursively()
        dir.mkdirs()
        wmv = asset("test.wmv")
        wma = asset("test.wma")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun wmvWirdErkannt() {
        val file = FileInspector.inspect(context, wmv)
        assertNotNull(file)
        assertEquals(MediaKind.VIDEO, file!!.kind)
        assertTrue("Dauer ${file.durationMs}", abs((file.durationMs ?: 0) - 8_000) < 200)
        assertTrue(file.hasAudio)
        assertEquals("video/x-ms-wmv", file.videoMime)
        assertEquals("audio/x-ms-wma", file.audioMime)
    }

    @Test
    fun wmaWirdAlsAudioErkannt() {
        val file = FileInspector.inspect(context, wma)!!
        assertEquals(MediaKind.AUDIO, file.kind)
        assertEquals(null, file.videoMime)
    }

    @Test
    fun wmvNachMp4(): Unit = runBlocking {
        val out = File(dir, "out.mp4")
        VideoConverter.convert(context, wmv, out, Presets.forFormat(OutputFormat.MP4).first().settings, 8_000, {})
        assertTracks(out, video = true, audio = true)
        assertLength(out, 8.0)
    }

    @Test
    fun wmvGekuerztNachMp4(): Unit = runBlocking {
        val out = File(dir, "kurz.mp4")
        val settings = Presets.forFormat(OutputFormat.MP4).first().settings.copy(trimStartMs = 2_000, trimEndMs = 6_000)
        VideoConverter.convert(context, wmv, out, settings, 8_000, {})
        assertTracks(out, video = true, audio = true)
        assertLength(out, 4.0)
    }

    @Test
    fun wmvNachWebm(): Unit = runBlocking {
        assumeTrue("WebM braucht Android 10", Build.VERSION.SDK_INT >= 29)
        val out = File(dir, "out.webm")
        VideoConverter.convert(context, wmv, out, Presets.forFormat(OutputFormat.WEBM).first().settings, 8_000, {})
        assertTracks(out, video = true, audio = true)
    }

    @Test
    fun wmvNachMp3(): Unit = runBlocking {
        val out = File(dir, "out.mp3")
        AudioConverter.convert(context, wmv, out, Presets.forFormat(OutputFormat.MP3).first().settings, {})
        assertLength(out, 8.0)
    }

    @Test
    fun wmaNachM4a(): Unit = runBlocking {
        val out = File(dir, "out.m4a")
        AudioConverter.convert(context, wma, out, Presets.forFormat(OutputFormat.M4A).first { !it.settings.passthrough }.settings, {})
        assertTracks(out, video = false, audio = true)
        assertLength(out, 8.0)
    }

    // ───────────── Hilfen ─────────────

    /** Testdatei aus den Assets des Test-APKs in den Cache kopieren, als content://-Uri wie aus einer anderen App. */
    private fun asset(name: String): Uri {
        val file = File(dir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    private fun assertTracks(file: File, video: Boolean, audio: Boolean) {
        assertTrue("${file.name} fehlt oder ist leer", file.length() > 1000)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val mimes = (0 until extractor.trackCount).mapNotNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
            assertEquals("Bildspur in ${file.name}: $mimes", video, mimes.any { it.startsWith("video/") })
            assertEquals("Tonspur in ${file.name}: $mimes", audio, mimes.any { it.startsWith("audio/") })
        } finally {
            extractor.release()
        }
    }

    /** Ton wieder einlesen und Länge prüfen (fängt abgeschnittene oder leere Ergebnisse). */
    private suspend fun assertLength(file: File, expectedSeconds: Double) {
        var bytes = 0L
        var rate = 0
        var channels = 0
        PcmDecoder.decode(context, Uri.fromFile(file), object : PcmSink {
            override fun start(sampleRate: Int, channels: Int) {
                rate = sampleRate
                setChannels(channels)
            }
            private fun setChannels(c: Int) { channels = c }
            override fun write(pcm: ByteBuffer) {
                bytes += pcm.remaining()
                pcm.position(pcm.limit())
            }
            override fun finish() = Unit
            override fun release() = Unit
        }, {})
        val seconds = bytes / 2.0 / channels / rate
        assertTrue("${file.name}: $seconds s statt $expectedSeconds s", abs(seconds - expectedSeconds) < 0.25)
    }
}
