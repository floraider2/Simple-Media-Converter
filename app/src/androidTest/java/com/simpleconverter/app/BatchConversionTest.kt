package com.simpleconverter.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Ende-zu-Ende: Dateien werden wie aus einer anderen App per „Teilen“ (SEND_MULTIPLE)
 * an die App geschickt, umgewandelt und das Ergebnis auf dem Bildschirm geprüft.
 * Die Testdateien erzeugt der Test selbst – keine Nutzerdateien nötig.
 */
@RunWith(AndroidJUnit4::class)
class BatchConversionTest {

    /** Sonst verdeckt beim ersten Start der Erlaubnis-Dialog die App. */
    @get:Rule
    val notifications: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        else GrantPermissionRule.grant()


    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val dir = File(context.cacheDir, "androidTest")

    @Before
    fun setUp() {
        dir.deleteRecursively()
        dir.mkdirs()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun dreiAudiodateienWerdenAlsStapelUmgewandelt() {
        val uris = (1..3).map { wav("ton_$it.wav", seconds = 2, frequency = 300.0 * it) }
        share(uris, "audio/*")

        clickWhenVisible(convertN(3))
        assertVisible(plural(R.plurals.done_all, 3), timeoutMs = 60_000)
    }

    @Test
    fun kaputteDateiImStapelStopptDieAnderenNicht() {
        val good = listOf(wav("gut_1.wav", 1, 440.0), wav("gut_2.wav", 1, 550.0))
        val broken = File(dir, "kaputt.wav").apply { writeBytes(ByteArray(4096) { it.toByte() }) }.let(::uri)
        share(good + broken, "audio/*")

        clickWhenVisible(convertN(3))
        assertVisible(context.getString(R.string.done_some, 2, 3), timeoutMs = 60_000)
    }

    @Test
    fun bilderStapelNachJpg() {
        val uris = (1..4).map { png("bild_$it.png", Color.rgb(60 * it, 120, 200)) }
        share(uris, "image/*")

        clickWhenVisible(convertN(4))
        assertVisible(plural(R.plurals.done_all, 4), timeoutMs = 60_000)
    }

    @Test
    fun bilderUndMusikGemischtWerdenAbgelehnt() {
        share(listOf(png("bild.png", Color.RED), wav("ton.wav", 1, 440.0)), "*/*")

        assertVisible(context.getString(R.string.msg_mixed_kinds).take(30), timeoutMs = 10_000)
    }

    // ───────────── Hilfen ─────────────

    /** Texte aus den Ressourcen – so läuft der Test in jeder Gerätesprache. */
    private fun plural(id: Int, n: Int) = context.resources.getQuantityString(id, n, n)

    private fun convertN(n: Int) = plural(R.plurals.setup_convert_n, n)

    private fun share(uris: List<Uri>, mime: String) {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setClass(context, MainActivity::class.java)
            .setType(mime)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    }

    private fun clickWhenVisible(text: String) {
        val button = device.wait(Until.findObject(By.text(text)), 10_000)
        assertNotNull("„$text“ nicht gefunden", button)
        button.click()
    }

    private fun assertVisible(textPart: String, timeoutMs: Long) {
        assertNotNull("„$textPart“ nicht erschienen", device.wait(Until.findObject(By.textContains(textPart)), timeoutMs))
    }

    private fun uri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    private fun png(name: String, color: Int): Uri {
        val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val file = File(dir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return uri(file)
    }

    private fun wav(name: String, seconds: Int, frequency: Double): Uri {
        val rate = 44_100
        val samples = rate * seconds
        val data = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(samples) { i -> data.putShort((sin(2 * PI * frequency * i / rate) * 10_000).toInt().toShort()) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(samples * 2)
        }
        val file = File(dir, name)
        file.outputStream().use { it.write(header.array()); it.write(data.array()) }
        return uri(file)
    }
}
