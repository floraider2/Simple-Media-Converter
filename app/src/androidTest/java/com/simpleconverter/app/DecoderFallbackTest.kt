package com.simpleconverter.app

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simpleconverter.app.convert.VideoConverter
import com.simpleconverter.app.convert.audio.AudioConverter
import com.simpleconverter.app.convert.audio.PcmDecoder
import com.simpleconverter.app.convert.audio.PcmSink
import com.simpleconverter.app.model.OutputFormat
import com.simpleconverter.app.model.Presets
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Rückfall auf andere Dekoder: Ein nicht vorhandener Dekoder steht an erster Stelle (wie ein kaputter
 * Hardware-Dekoder). Die Umwandlung muss trotzdem klappen – mit dem nächsten Dekoder, notfalls Software.
 */
@RunWith(AndroidJUnit4::class)
class DecoderFallbackTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "fallbackTest")
    private lateinit var mp4: Uri

    @Before
    fun setUp(): Unit = runBlocking {
        dir.deleteRecursively()
        dir.mkdirs()
        // Ausgangsdatei: H.264 + AAC, erzeugt aus der WMV-Testdatei (die liest FFmpeg, nicht MediaCodec).
        val wmv = File(dir, "test.wmv")
        InstrumentationRegistry.getInstrumentation().context.assets.open("test.wmv").use { input ->
            wmv.outputStream().use { input.copyTo(it) }
        }
        val source = File(dir, "source.mp4")
        VideoConverter.convert(context, uri(wmv), source, Presets.forFormat(OutputFormat.MP4).first().settings, 8_000, {})
        mp4 = uri(source)
    }

    @After
    fun tearDown() {
        VideoConverter.brokenDecoderForTest = false
        PcmDecoder.brokenDecoderForTest = false
        dir.deleteRecursively()
    }

    @Test
    fun videoKlapptTrotzKaputtemErstenDekoder(): Unit = runBlocking {
        VideoConverter.brokenDecoderForTest = true
        val out = File(dir, "out.mp4")
        VideoConverter.convert(context, mp4, out, Presets.forFormat(OutputFormat.MP4).first().settings, 8_000, {})
        assertTrue("Ergebnis fehlt", out.length() > 1000)
        assertLength(out, 8.0)
    }

    @Test
    fun tonKlapptTrotzKaputtemErstenDekoder(): Unit = runBlocking {
        PcmDecoder.brokenDecoderForTest = true
        val out = File(dir, "out.mp3")
        AudioConverter.convert(context, mp4, out, Presets.forFormat(OutputFormat.MP3).first().settings, {})
        PcmDecoder.brokenDecoderForTest = false
        assertLength(out, 8.0)
    }

    private fun uri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

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
