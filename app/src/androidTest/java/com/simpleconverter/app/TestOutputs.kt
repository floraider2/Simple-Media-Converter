package com.simpleconverter.app

import android.content.Context
import android.os.Build
import android.provider.MediaStore

/**
 * Räumt nach einem Geräte-Test auf: Ergebnisse, die die (Debug-)App während des Tests in
 * Movies/Music/Pictures gespeichert hat, sollen nicht im Telefon liegen bleiben.
 */
class TestOutputs(private val context: Context) {
    private val startSeconds = System.currentTimeMillis() / 1000 - 1

    fun deleteAll() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            context.contentResolver.delete(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND ${MediaStore.MediaColumns.DATE_ADDED} >= ?",
                arrayOf(context.packageName, startSeconds.toString()),
            )
        }
    }
}
