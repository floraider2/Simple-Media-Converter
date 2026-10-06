package com.simpleconverter.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BitrateTest {

    @Test
    fun `Video passt in die Zielgröße`() {
        val target = 16L * 1024 * 1024
        val durationMs = 60_000L
        val audio = 128_000
        val video = Bitrate.videoForTargetSize(target, durationMs, audio, withAudio = true)
        val bytes = (video + audio).toLong() * 60 / 8
        assertTrue("Ergebnis $bytes Bytes ist größer als $target", bytes <= target)
        assertTrue("Puffer zu groß, Qualität wird verschenkt", bytes > target * 0.9)
    }

    @Test
    fun `ohne Ton steht die ganze Größe dem Video zur Verfügung`() {
        val withAudio = Bitrate.videoForTargetSize(8L * 1024 * 1024, 30_000, 128_000, withAudio = true)
        val withoutAudio = Bitrate.videoForTargetSize(8L * 1024 * 1024, 30_000, 128_000, withAudio = false)
        assertEquals(128_000.0, (withoutAudio - withAudio).toDouble(), 1.0)
    }

    @Test
    fun `sehr lange Videos fallen nicht unter die Mindestbitrate`() {
        val video = Bitrate.videoForTargetSize(8L * 1024 * 1024, 3 * 60 * 60 * 1000L, 128_000, withAudio = true)
        assertEquals(Bitrate.MIN_VIDEO, video)
    }

    @Test
    fun `sehr kurze Videos werden auf die Höchstbitrate begrenzt`() {
        val video = Bitrate.videoForTargetSize(500L * 1024 * 1024, 1_000, 128_000, withAudio = true)
        assertEquals(Bitrate.MAX_VIDEO, video)
    }

    @Test
    fun `Bitrate wird auf die des Originals begrenzt`() {
        assertEquals(400_000, Bitrate.capToSource(2_000_000, 400_000))
        assertEquals(2_000_000, Bitrate.capToSource(2_000_000, 8_000_000))
        assertEquals(2_000_000, Bitrate.capToSource(2_000_000, null))
        assertEquals(Bitrate.MIN_VIDEO, Bitrate.capToSource(2_000_000, 50_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `Dauer null ist ungültig`() {
        Bitrate.videoForTargetSize(1024, 0, 128_000, withAudio = true)
    }
}

class OutputFileNameTest {

    @Test
    fun `Endung wird ersetzt`() = assertEquals("urlaub.mp4", outputFileName("urlaub.mov", OutputFormat.MP4))

    @Test
    fun `nur die letzte Endung wird ersetzt`() =
        assertEquals("song.final.m4a", outputFileName("song.final.mp3", OutputFormat.M4A))

    @Test
    fun `Datei ohne Endung`() = assertEquals("foto.jpg", outputFileName("foto", OutputFormat.JPG))

    @Test
    fun `versteckte Datei ohne Namen`() = assertEquals("umgewandelt.png", outputFileName(".heic", OutputFormat.PNG))
}

class SettingsAndPresetsTest {

    @Test
    fun `Einstellungen überstehen den Weg durch WorkManager-Data`() {
        val settings = ConversionSettings(
            format = OutputFormat.MP4,
            videoShortSide = 720,
            videoBitrate = 2_000_000,
            targetSizeBytes = 24L * 1024 * 1024,
            removeAudio = true,
            audioBitrate = 96_000,
            imageMaxSide = 1600,
            imageQuality = 75,
        )
        assertEquals(settings, ConversionSettings.fromData(settings.toData()))
    }

    @Test
    fun `leere Optionen bleiben leer`() {
        val settings = ConversionSettings(OutputFormat.JPG)
        assertEquals(settings, ConversionSettings.fromData(settings.toData()))
    }

    @Test
    fun `jedes Zielformat hat Vorgaben mit passendem Format und eindeutigen IDs`() {
        OutputFormat.entries.forEach { format ->
            val presets = Presets.forFormat(format)
            assertTrue("$format hat keine Vorgaben", presets.isNotEmpty())
            presets.forEach { assertEquals("${it.id} für $format", format, it.settings.format) }
            assertEquals("doppelte IDs bei $format", presets.size, presets.map { it.id }.toSet().size)
            assertEquals("doppelte Einstellungen bei $format", presets.size, presets.map { it.settings }.toSet().size)
        }
    }

    @Test
    fun `gemeinsame Zielformate eines Stapels`() {
        val video = OutputFormat.targetsFor(MediaKind.VIDEO)
        val silentVideo = OutputFormat.targetsFor(MediaKind.VIDEO, hasAudio = false)
        val audio = OutputFormat.targetsFor(MediaKind.AUDIO)
        val image = OutputFormat.targetsFor(MediaKind.IMAGE)
        assertEquals(listOf(OutputFormat.MP4), silentVideo)
        assertEquals(video, OutputFormat.intersect(listOf(video, video)))
        assertEquals(audio, OutputFormat.intersect(listOf(video, audio)))
        assertEquals(listOf(OutputFormat.MP4), OutputFormat.intersect(listOf(video, silentVideo)))
        assertEquals(emptyList<OutputFormat>(), OutputFormat.intersect(listOf(image, audio)))
        assertEquals(emptyList<OutputFormat>(), OutputFormat.intersect(emptyList()))
    }

    @Test
    fun `Opus erst ab Android 10`() {
        assertTrue(OutputFormat.OPUS !in OutputFormat.targetsFor(MediaKind.AUDIO, sdkInt = 28))
        assertTrue(OutputFormat.OPUS in OutputFormat.targetsFor(MediaKind.AUDIO, sdkInt = 29))
    }

    @Test
    fun `jeder Medientyp hat mindestens ein Zielformat`() {
        MediaKind.entries.forEach { assertTrue(OutputFormat.targetsFor(it).isNotEmpty()) }
    }
}
