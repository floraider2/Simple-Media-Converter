package com.simpleconverter.app.convert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import com.simpleconverter.app.R
import com.simpleconverter.app.model.ConversionSettings
import com.simpleconverter.app.model.OutputFormat
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bilder über die Bord-APIs des Systems. Beim Neu-Kodieren gehen EXIF-Daten
 * (GPS, Kamera, Zeitstempel) automatisch verloren – gewollt für den Datenschutz.
 * Auf Wunsch werden Kameradaten zurückkopiert, der Standort aber nie.
 */
object ImageConverter {

    suspend fun convert(context: Context, input: Uri, output: File, settings: ConversionSettings) =
        withContext(Dispatchers.IO) {
            val decoded = decode(context, input, settings.imageMaxSide)
            val bitmap = if (settings.format == OutputFormat.JPG && decoded.hasAlpha()) {
                flattenOnWhite(decoded).also { decoded.recycle() }
            } else {
                decoded
            }
            try {
                output.outputStream().use { stream ->
                    val ok = bitmap.compress(compressFormat(settings.format), settings.imageQuality, stream)
                    if (!ok) throw ConversionException(R.string.err_image_save)
                }
            } finally {
                bitmap.recycle()
            }
            if (settings.keepMetadata) copyMetadata(context, input, output)
        }

    /** Kopiert unbedenkliche EXIF-Felder. GPS-Felder stehen absichtlich nicht in der Liste. */
    private fun copyMetadata(context: Context, input: Uri, output: File) {
        runCatching {
            val source = context.contentResolver.openInputStream(input)?.use { ExifInterface(it) } ?: return
            val target = ExifInterface(output.absolutePath)
            var copied = 0
            for (tag in KEPT_TAGS) {
                source.getAttribute(tag)?.let {
                    target.setAttribute(tag, it)
                    copied++
                }
            }
            if (copied == 0) return
            // Das Bild ist schon richtig gedreht.
            target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            target.saveAttributes()
        }
    }

    private val KEPT_TAGS = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_IMAGE_DESCRIPTION,
    )

    private fun decode(context: Context, input: Uri, maxSide: Int?): Bitmap =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, input)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                if (maxSide != null && max(w, h) > maxSide) {
                    val scale = maxSide.toDouble() / max(w, h)
                    decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
                }
            }
        } else {
            decodeLegacy(context, input, maxSide)
        }

    /** Android 8/8.1: BitmapFactory kennt keine EXIF-Drehung, die wenden wir selbst an. */
    private fun decodeLegacy(context: Context, input: Uri, maxSide: Int?): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(input)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        if (maxSide != null) {
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        var bitmap = resolver.openInputStream(input)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw ConversionException(R.string.err_image_unsupported)

        val rotation = runCatching {
            resolver.openInputStream(input)?.use { ExifInterface(it).rotationDegrees } ?: 0
        }.getOrDefault(0)
        if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        }
        if (maxSide != null && max(bitmap.width, bitmap.height) > maxSide) {
            val scale = maxSide.toDouble() / max(bitmap.width, bitmap.height)
            bitmap = bitmap.scale(
                (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            )
        }
        return bitmap
    }

    private fun flattenOnWhite(src: Bitmap): Bitmap {
        val out = createBitmap(src.width, src.height)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, 0f, 0f, null)
        }
        return out
    }

    @Suppress("DEPRECATION")
    private fun compressFormat(format: OutputFormat): Bitmap.CompressFormat = when (format) {
        OutputFormat.JPG -> Bitmap.CompressFormat.JPEG
        OutputFormat.PNG -> Bitmap.CompressFormat.PNG
        OutputFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
        else -> error("Not an image format: $format")
    }
}
