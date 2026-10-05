package com.simpleconverter.app.convert

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.simpleconverter.app.model.MediaKind
import com.simpleconverter.app.model.OutputFormat
import java.io.File

/** Legt das Ergebnis dort ab, wo man es erwartet: Filme, Musik oder Bilder → „SimpleConverter“. */
object OutputStore {
    private const val FOLDER = "SimpleConverter"

    fun save(context: Context, file: File, displayName: String, format: OutputFormat): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(context, file, displayName, format)
        } else {
            saveToAppFolder(context, file, displayName, format)
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStore(context: Context, file: File, displayName: String, format: OutputFormat): Uri {
        val (collection, dir) = when (format.kind) {
            MediaKind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
            MediaKind.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MUSIC
            MediaKind.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_PICTURES
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, format.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw ConversionException("Speichern fehlgeschlagen.")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw ConversionException("Speichern fehlgeschlagen.")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /**
     * Android 8/9: ohne Speicherberechtigung landet die Datei im App-Ordner
     * (Android/data/…). Über Teilen/Öffnen ist sie trotzdem erreichbar.
     */
    private fun saveToAppFolder(context: Context, file: File, displayName: String, format: OutputFormat): Uri {
        val type = when (format.kind) {
            MediaKind.VIDEO -> Environment.DIRECTORY_MOVIES
            MediaKind.AUDIO -> Environment.DIRECTORY_MUSIC
            MediaKind.IMAGE -> Environment.DIRECTORY_PICTURES
        }
        val dir = File(context.getExternalFilesDir(type), FOLDER).apply { mkdirs() }
        var target = File(dir, displayName)
        var n = 1
        while (target.exists()) {
            target = File(dir, "${displayName.substringBeforeLast('.')} ($n).${format.extension}")
            n++
        }
        file.copyTo(target)
        return FileProvider.getUriForFile(context, "${context.packageName}.files", target)
    }
}
