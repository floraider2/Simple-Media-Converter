package com.simpleconverter.app.convert

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Geteilte Dateien (z. B. aus WhatsApp) dürfen wir nur lesen, solange unsere Activity lebt.
 * Damit die Umwandlung auch nach dem Schließen der App weiterläuft, kopieren wir solche
 * Dateien zuerst in den Cache. Dateien aus der Dateisuche haben dauerhafte Rechte und
 * werden direkt gelesen.
 */
object InputCache {

    private val MAX_AGE_MS = TimeUnit.HOURS.toMillis(6)

    fun needsCopy(context: Context, uri: Uri): Boolean {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return false
        if (uri.authority == "${context.packageName}.files") return false
        return context.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }
    }

    suspend fun copy(context: Context, uri: Uri, name: String, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "in").apply { mkdirs() }
            val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val target = File(dir, "${System.nanoTime()}_$safeName")
            val total = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull() ?: -1L
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw ConversionException("Die Datei ist nicht mehr erreichbar. Bitte erneut auswählen.")
                input.use {
                    target.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var copied = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0) onProgress(((copied * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            } catch (e: Throwable) {
                target.delete()
                throw e
            }
            target
        }

    /** Entfernt liegen gebliebene Dateien früherer, abgebrochener Läufe. */
    fun cleanup(context: Context) {
        val now = System.currentTimeMillis()
        listOf("in", "out").forEach { sub ->
            File(context.cacheDir, sub).listFiles()?.forEach { file ->
                if (now - file.lastModified() > MAX_AGE_MS) file.delete()
            }
        }
    }
}
