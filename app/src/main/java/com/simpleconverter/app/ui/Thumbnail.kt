package com.simpleconverter.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.simpleconverter.app.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Vorschaubilder über das System (MediaStore bzw. Dokumenten-Anbieter), im Speicher zwischengespeichert. */
object Thumbnails {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing = mutableSetOf<String>()

    suspend fun load(context: Context, uri: Uri, px: Int, skip: Boolean = false): Bitmap? {
        if (skip || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val key = "$uri@$px"
        cache.get(key)?.let { return it }
        if (key in missing) return null
        return withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(uri, Size(px, px), null) }.getOrNull()
        }?.also { cache.put(key, it) } ?: run {
            missing += key
            null
        }
    }
}

/** Aus den Einstellungen: Vorschaubilder anzeigen? Wird in MainActivity gesetzt. */
val LocalShowThumbnails = staticCompositionLocalOf { true }

/** Quadratisches Vorschaubild; solange keins da ist (oder es keins gibt), ein Symbol für den Dateityp. */
@Composable
fun Thumbnail(uri: Uri, kind: MediaKind, size: Dp = 48.dp) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    // Für Audio gibt es kein Bild – dann gar nicht erst laden.
    val enabled = LocalShowThumbnails.current
    var bitmap by remember(uri, px, enabled) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri, px, kind, enabled) {
        bitmap = Thumbnails.load(context, uri, px, skip = kind == MediaKind.AUDIO || !enabled)
    }
    val shape = RoundedCornerShape(8.dp)
    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(shape),
        )
    } else {
        Box(
            Modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when (kind) {
                    MediaKind.VIDEO -> "🎬"
                    MediaKind.AUDIO -> "🎵"
                    MediaKind.IMAGE -> "🖼️"
                },
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}
