package com.simpleconverter.app

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Datenschutz-Versprechen prüfen: Der Standort verschwindet immer,
 * Kameradaten nur dann nicht, wenn „Kameradaten behalten“ an ist.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29) // Ergebnis wird über MediaStore gelesen
class ImageMetadataTest {

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
    fun standardmaessigWerdenAlleMetadatenEntfernt() {
        val name = "exif_aus_${System.currentTimeMillis()}"
        open(photoWithExif("$name.jpg"))
        click(context.getString(R.string.setup_convert))
        assertNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.share))), 30_000))

        val exif = readResult("$name.jpg")
        assertNull("Kamera hätte entfernt werden müssen", exif.getAttribute(ExifInterface.TAG_MAKE))
        assertNull("Standort hätte entfernt werden müssen", exif.latLong)
    }

    @Test
    fun kameradatenBleibenStandortNicht() {
        val name = "exif_an_${System.currentTimeMillis()}"
        open(photoWithExif("$name.jpg"))
        click(context.getString(R.string.setup_advanced))
        click(context.getString(R.string.adv_keep_metadata))
        click(context.getString(R.string.setup_convert))
        assertNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.share))), 30_000))

        val exif = readResult("$name.jpg")
        assertEquals("TestKamera", exif.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("2026:10:06 12:00:00", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertNull("Standort darf nie übernommen werden", exif.latLong)
    }

    // ───────────── Hilfen ─────────────

    private fun photoWithExif(name: String): Uri {
        val file = File(dir, name)
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(30, 140, 90)) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_MAKE, "TestKamera")
            setAttribute(ExifInterface.TAG_MODEL, "Modell 1")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:06 12:00:00")
            setLatLong(50.1109, 8.6821)
            saveAttributes()
        }
        // Vorbedingung: Das Testfoto hat wirklich einen Standort.
        assertNotNull(ExifInterface(file.absolutePath).latLong)
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    private fun open(uri: Uri) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setClass(context, MainActivity::class.java)
                .setDataAndType(uri, "image/jpeg")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private fun click(text: String) {
        val node = device.wait(Until.findObject(By.text(text)), 10_000)
        assertNotNull("„$text“ nicht gefunden", node)
        node.click()
        device.waitForIdle()
    }

    /** Das Ergebnis liegt im MediaStore; die App hat es selbst angelegt und darf es lesen. */
    private fun readResult(name: String): ExifInterface {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val id = resolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(name),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
        assertNotNull("Ergebnis $name nicht im MediaStore", id)
        val uri = ContentUris.withAppendedId(collection, id!!)
        return resolver.openInputStream(uri)!!.use { ExifInterface(it) }
    }
}
