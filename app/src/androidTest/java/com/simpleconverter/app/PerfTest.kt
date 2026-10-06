package com.simpleconverter.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.simpleconverter.app.convert.ImageConverter
import com.simpleconverter.app.convert.VideoConverter
import com.simpleconverter.app.convert.audio.AudioConverter
import com.simpleconverter.app.convert.audio.Loudness
import com.simpleconverter.app.convert.audio.LoudnessMeter
import com.simpleconverter.app.convert.audio.PcmDecoder
import com.simpleconverter.app.convert.audio.PcmSink
import com.simpleconverter.app.data.FileInspector
import com.simpleconverter.app.model.ConversionSettings
import com.simpleconverter.app.model.OutputFormat
import com.simpleconverter.app.model.Presets
import com.simpleconverter.app.work.ConversionWorker
import com.simpleconverter.app.work.JobStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Leistungsmessung (kein normaler Funktionstest): erzeugt eigene Testdateien im Cache,
 * misst die Dauer typischer Umwandlungen und schreibt sie als „PERF“ ins Log.
 * Start: ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.simpleconverter.app.PerfTest
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class PerfTest {

    @Test
    fun audio(): Unit = runBlocking {
        for (format in listOf(OutputFormat.MP3, OutputFormat.M4A, OutputFormat.OPUS, OutputFormat.FLAC, OutputFormat.WAV)) {
            val settings = Presets.forFormat(format).first().settings
            val file = out(format)
            measure("Audio 10 min WAV → ${format.name}") {
                AudioConverter.convert(context, wav10, file, settings, {})
            }
            verifyLength(file, 600.0)
            if (format == OutputFormat.FLAC) {
                measure("Audio FLAC 10 min → MP3") { AudioConverter.convert(context, uri(file), out(OutputFormat.MP3), Presets.forFormat(OutputFormat.MP3).first().settings, {}) }
                // FLAC wieder einlesen, mit Sprung an den Anfang des Ausschnitts.
                val mp3 = out(OutputFormat.MP3)
                val trimmed = Presets.forFormat(OutputFormat.MP3).first().settings.copy(trimStartMs = 60_000, trimEndMs = 360_000)
                measure("Audio FLAC 1:00–6:00 → MP3") { AudioConverter.convert(context, uri(file), mp3, trimmed, {}) }
                verifyLength(mp3, 300.0)
            }
        }
        val mp3 = Presets.forFormat(OutputFormat.MP3).first().settings
        var result: File? = null
        measure("Audio 10 min WAV → MP3 + Lautstärke") { result = normalizedMp3(wav10, mp3) }
        verifyLength(result!!, 600.0)
        measure("Audio 10 min M4A → MP3 + Lautstärke") { result = normalizedMp3(m4a10, mp3) }
        verifyLength(result!!, 600.0)
        measure("Audio M4A 1:00–6:00 → MP3 + Lautstärke") {
            result = normalizedMp3(m4a10, mp3.copy(trimStartMs = 60_000, trimEndMs = 360_000))
        }
        verifyLength(result!!, 300.0)
    }

    /** Einzelteile getrennt messen, um die Bremse zu finden. */
    @Test
    fun parts(): Unit = runBlocking {
        val nullSink = object : PcmSink {
            var bytes = 0L
            override fun start(sampleRate: Int, channels: Int) = Unit
            override fun write(pcm: ByteBuffer) { bytes += pcm.remaining(); pcm.position(pcm.limit()) }
            override fun finish() = Unit
            override fun release() = Unit
        }
        measure("Nur dekodieren: 10 min WAV") { PcmDecoder.decode(context, wav10, nullSink, {}) }
        Log.i("PERF", "  → %.2f s Audio".format(nullSink.bytes / 4.0 / 48_000))
        nullSink.bytes = 0
        measure("Nur dekodieren: 10 min M4A (AAC)") { PcmDecoder.decode(context, m4a10, nullSink, {}) }
        Log.i("PERF", "  → %.2f s Audio (Soll 600)".format(nullSink.bytes / 4.0 / 48_000))
        measure("Dekodieren + Lautheit messen: 10 min WAV") { PcmDecoder.decode(context, wav10, LoudnessMeter(), {}) }
        measure("Dekodieren + Lautheit messen: 10 min M4A") { PcmDecoder.decode(context, m4a10, LoudnessMeter(), {}) }
        Log.i("PERF", "M4A-Quelle: ${FileInspector.inspect(context, m4a10)}")
    }

    @Test
    fun video(): Unit = runBlocking {
        val file = FileInspector.inspect(context, video60)!!
        val m4a = Presets.forFormat(OutputFormat.M4A).first().settings
        // Wie in der App: neu kodiertes M4A über den Audio-Weg, „Original-Ton“ über Media3.
        val m4aOut = out(OutputFormat.M4A)
        measure("Video 60 s → Nur Ton M4A") { AudioConverter.convert(context, video60, m4aOut, m4a, {}) }
        verifyLength(m4aOut, 60.0)
        val max = Presets.forFormat(OutputFormat.MP4).first { it.id == "max" }.settings
        measure("Video 60 s → MP4 gekürzt 10–40 s (Max. Qualität)") {
            VideoConverter.convert(context, video60, out(OutputFormat.MP4), max.copy(trimStartMs = 10_000, trimEndMs = 40_000), file.durationMs, {})
        }
        // Falls vorhanden: Vorgabe ohne Neu-Kodieren
        Presets.forFormat(OutputFormat.MP4).firstOrNull { it.id == "copy" }?.let { copy ->
            measure("Video 60 s → MP4 gekürzt 10–40 s (Original behalten)") {
                VideoConverter.convert(context, video60, out(OutputFormat.MP4), copy.settings.copy(trimStartMs = 10_000, trimEndMs = 40_000), file.durationMs, {})
            }
        }
        Presets.forFormat(OutputFormat.M4A).firstOrNull { it.id == "copy" }?.let { copy ->
            measure("Video 60 s → Nur Ton M4A (Original-Ton)") {
                VideoConverter.convert(context, video60, out(OutputFormat.M4A), copy.settings, file.durationMs, {})
            }
        }
    }

    @Test
    fun imageBatch() {
        val settings = Presets.forFormat(OutputFormat.JPG).first().settings
        val files = images.map { FileInspector.inspect(context, it)!! }
        val id = UUID.randomUUID()
        JobStore.saveJob(context, JobStore.Job(id, files, settings))
        val start = System.nanoTime()
        val wm = WorkManager.getInstance(context)
        wm.enqueue(OneTimeWorkRequestBuilder<ConversionWorker>().setId(id).setInputData(ConversionWorker.inputData(id)).build())
        var info: WorkInfo?
        do {
            Thread.sleep(200)
            info = wm.getWorkInfoById(id).get()
        } while (info != null && !info.state.isFinished)
        val ms = (System.nanoTime() - start) / 1_000_000
        val results = JobStore.loadResults(context, id)
        results.mapNotNull { it.outputUri }.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        log("Bilder-Stapel ${files.size} × 12 MP PNG → JPG", ms)
        assertTrue("nicht alle Bilder umgewandelt", results.count { it.ok } == files.size)
    }

    // ───────────── Hilfen ─────────────

    /** Ergebnis wieder einlesen: stimmt die Länge? (Fängt kaputte oder abgeschnittene Dateien.) */
    private suspend fun verifyLength(file: File, expectedSeconds: Double) {
        var bytes = 0L
        var rate = 0
        var channels = 0
        PcmDecoder.decode(context, uri(file), object : PcmSink {
            override fun start(sampleRate: Int, channels: Int) { rate = sampleRate; this.channelsSet(channels) }
            private fun channelsSet(c: Int) { channels = c }
            override fun write(pcm: ByteBuffer) { bytes += pcm.remaining(); pcm.position(pcm.limit()) }
            override fun finish() = Unit
            override fun release() = Unit
        }, {})
        val seconds = bytes / 2.0 / channels / rate
        Log.i("PERF", "  → ${file.extension}: %.2f s Audio, $rate Hz, $channels Kanäle".format(seconds))
        assertTrue("${file.name}: Länge $seconds statt $expectedSeconds", kotlin.math.abs(seconds - expectedSeconds) < 0.2)
    }

    /** Wie im Worker: messen und dabei WAV-Zwischendatei schreiben, dann aus der Zwischendatei kodieren. */
    private suspend fun normalizedMp3(input: Uri, settings: ConversionSettings): File {
        val pcm = File(dir, "pcm_${System.nanoTime()}.wav")
        val result = out(OutputFormat.MP3)
        try {
            val gain = Loudness.measureToWav(context, input, settings, pcm) {}
            AudioConverter.convert(context, Uri.fromFile(pcm), result, settings.copy(trimStartMs = null, trimEndMs = null), {}, gain)
        } finally {
            pcm.delete()
        }
        return result
    }

    private inline fun measure(name: String, block: () -> Unit) {
        val start = System.nanoTime()
        block()
        log(name, (System.nanoTime() - start) / 1_000_000)
    }

    companion object {
        private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
        private val dir by lazy { File(context.cacheDir, "perf").apply { mkdirs() } }
        private lateinit var wav10: Uri
        private lateinit var m4a10: Uri
        private lateinit var video60: Uri
        private lateinit var images: List<Uri>

        private fun log(name: String, ms: Long) = Log.i("PERF", "%-55s %7.1f s".format(name, ms / 1000.0))

        private fun out(format: OutputFormat) = File(dir, "out_${System.nanoTime()}.${format.extension}")

        private fun uri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

        @JvmStatic
        @BeforeClass
        fun createMedia(): Unit = runBlocking {
            dir.deleteRecursively()
            dir.mkdirs()
            wav10 = uri(wav("ton10.wav", seconds = 600))
            // AAC-Quelle für „Lautstärke aus komprimierter Datei“
            val m4aFile = File(dir, "ton10.m4a")
            VideoConverter.convert(context, wav10, m4aFile, ConversionSettings(OutputFormat.M4A), 600_000, {})
            m4a10 = uri(m4aFile)
            video60 = uri(videoWithAac("video60.mp4", seconds = 60))
            images = (1..24).map { uri(png("bild_$it.png", it)) }
        }

        @JvmStatic
        @AfterClass
        fun cleanUp() {
            dir.deleteRecursively()
        }

        private fun wav(name: String, seconds: Int, rate: Int = 48_000): File {
            val file = File(dir, name)
            val frames = rate * seconds
            file.outputStream().buffered(1 shl 20).use { out ->
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                    put("RIFF".toByteArray()); putInt(36 + frames * 4); put("WAVE".toByteArray())
                    put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(2); putInt(rate); putInt(rate * 4)
                    putShort(4); putShort(16); put("data".toByteArray()); putInt(frames * 4)
                }
                out.write(header.array())
                val chunk = ByteBuffer.allocate(rate * 4).order(ByteOrder.LITTLE_ENDIAN)
                var n = 0L
                repeat(seconds) {
                    chunk.clear()
                    repeat(rate) {
                        // Ton + etwas Rauschen, damit Encoder realistisch arbeiten müssen
                        val v = (6000 * sin(2 * PI * 440 * n / rate) + Random.nextInt(-800, 800)).toInt().toShort()
                        chunk.putShort(v); chunk.putShort(v); n++
                    }
                    out.write(chunk.array())
                }
            }
            return file
        }

        private fun png(name: String, seed: Int): File {
            val bmp = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint().apply { shader = LinearGradient(0f, 0f, 4000f, 3000f, Color.rgb(20 * seed % 255, 90, 200), Color.rgb(250, 180, 30 * seed % 255), Shader.TileMode.CLAMP) }
            canvas.drawRect(0f, 0f, 4000f, 3000f, paint)
            val rnd = Random(seed)
            val dot = Paint()
            repeat(4000) {
                dot.color = Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
                canvas.drawCircle(rnd.nextFloat() * 4000, rnd.nextFloat() * 3000, rnd.nextFloat() * 40, dot)
            }
            val file = File(dir, name)
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            return file
        }

        /** Standbild + Ton → MP4 (H.264 + AAC), erzeugt mit Media3 selbst. */
        private suspend fun videoWithAac(name: String, seconds: Int): File {
            val png = png("standbild.png", 99)
            val audio = wav("ton_kurz.wav", seconds)
            val image = EditedMediaItem.Builder(
                MediaItem.Builder().setUri(uri(png)).setImageDurationMs(seconds * 1000L).build()
            ).setFrameRate(30).build()
            val sound = EditedMediaItem.Builder(MediaItem.fromUri(uri(audio))).build()
            val composition = Composition.Builder(EditedMediaItemSequence.withVideoFrom(listOf(image)), EditedMediaItemSequence.withAudioFrom(listOf(sound))).build()
            val file = File(dir, name)
            suspendCancellableCoroutine { cont ->
                Handler(Looper.getMainLooper()).post {
                    Transformer.Builder(context)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) = cont.resume(Unit)
                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) =
                                cont.resumeWithException(exportException)
                        })
                        .build()
                        .start(composition, file.absolutePath)
                }
            }
            return file
        }
    }
}
