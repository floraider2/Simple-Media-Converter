package com.simpleconverter.app.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.simpleconverter.app.model.InputFile
import com.simpleconverter.app.model.MediaKind

object FileInspector {

    /** Liest Name, Größe, Typ und Dauer. Gibt null zurück, wenn die Datei kein Video, Audio oder Bild ist. */
    fun inspect(context: Context, uri: Uri): InputFile? {
        val resolver = context.contentResolver
        var name: String? = null
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        }
        val displayName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "datei"
        val extension = displayName.substringAfterLast('.', "").lowercase()
        val mime = resolver.getType(uri)?.takeIf { it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)

        val kind = when {
            mime == null -> null
            mime.startsWith("video/") -> MediaKind.VIDEO
            mime.startsWith("audio/") -> MediaKind.AUDIO
            mime.startsWith("image/") -> MediaKind.IMAGE
            else -> null
        } ?: return null

        if (size < 0) {
            size = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: 0L
        }
        val meta = if (kind == MediaKind.IMAGE) null else readMeta(context, uri)
        return InputFile(
            uri, displayName, size, mime, kind,
            durationMs = meta?.first,
            hasAudio = kind == MediaKind.AUDIO || (meta?.second ?: true),
        )
    }

    /**
     * Zählt die Spuren selbst – METADATA_KEY_HAS_AUDIO ist nicht auf allen Geräten
     * zuverlässig (Samsung liefert bei Bildschirmaufnahmen null).
     */
    private fun hasAudioTrack(context: Context, uri: Uri): Boolean? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount).any {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
        } catch (e: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    /** Dauer und ob eine Tonspur vorhanden ist. */
    private fun readMeta(context: Context, uri: Uri): Pair<Long?, Boolean?>? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            duration to hasAudioTrack(context, uri)
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024L -> "%d KB".format(bytes / 1024)
    else -> "$bytes B"
}

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
