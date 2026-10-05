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
import androidx.exifinterface.media.ExifInterface
import com.simpleconverter.app.model.ConversionSettings
import com.simpleconverter.app.model.OutputFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Bilder über die Bord-APIs des Systems. Beim Neu-Kodieren gehen EXIF-Daten
 * (GPS, Kamera, Zeitstempel) automatisch verloren – gewollt für den Datenschutz.
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
                    if (!ok) throw ConversionException("Bild konnte nicht gespeichert werden.")
                }
            } finally {
                bitmap.recycle()
            }
        }

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
            ?: throw ConversionException("Bildformat wird auf diesem Gerät nicht unterstützt.")

        val rotation = runCatching {
            resolver.openInputStream(input)?.use { ExifInterface(it).rotationDegrees } ?: 0
        }.getOrDefault(0)
        if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        }
        if (maxSide != null && max(bitmap.width, bitmap.height) > maxSide) {
            val scale = maxSide.toDouble() / max(bitmap.width, bitmap.height)
            bitmap = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        }
        return bitmap
    }

    private fun flattenOnWhite(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
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
        else -> error("Kein Bildformat: $format")
    }
}
