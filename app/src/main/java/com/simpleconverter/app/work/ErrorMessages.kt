package com.simpleconverter.app.work

import android.graphics.ImageDecoder
import android.os.Build
import android.system.ErrnoException
import android.system.OsConstants
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import com.simpleconverter.app.convert.ConversionException
import java.io.FileNotFoundException

/** Übersetzt technische Fehler in Sätze, mit denen man etwas anfangen kann. */
@OptIn(UnstableApi::class)
object ErrorMessages {

    fun forThrowable(t: Throwable): String {
        val chain = generateSequence(t) { it.cause }.toList()

        chain.firstOrNull { it is ConversionException }?.message?.let { return it }
        chain.firstOrNull { it is ExportException }?.let { return forExport(it as ExportException) }

        if (chain.any { it is OutOfMemoryError }) {
            return "Die Datei ist zu groß für den Arbeitsspeicher. Wähle unter „Erweitert“ eine kleinere Größe."
        }
        if (chain.any { isNoSpace(it) }) {
            return "Nicht genug Speicherplatz frei."
        }
        if (chain.any { it is SecurityException || it is FileNotFoundException }) {
            return "Die Datei ist nicht mehr erreichbar. Bitte erneut auswählen."
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && chain.any { it is ImageDecoder.DecodeException }) {
            return "Dieses Bildformat kann dein Gerät nicht lesen."
        }
        return "Unerwarteter Fehler (${t.javaClass.simpleName}). Versuche andere Einstellungen."
    }

    private fun forExport(e: ExportException): String = when (e.errorCode) {
        ExportException.ERROR_CODE_DECODER_INIT_FAILED,
        ExportException.ERROR_CODE_DECODING_FAILED,
        ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "Dein Gerät kann dieses Format nicht lesen."
        ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
        ExportException.ERROR_CODE_ENCODING_FAILED,
        ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED ->
            "Dein Gerät kann mit diesen Einstellungen nicht speichern. Versuche eine kleinere Auflösung."
        ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
        ExportException.ERROR_CODE_IO_NO_PERMISSION ->
            "Die Datei ist nicht mehr erreichbar. Bitte erneut auswählen."
        ExportException.ERROR_CODE_MUXING_FAILED ->
            "Die Ergebnisdatei konnte nicht geschrieben werden."
        else -> "Umwandlung fehlgeschlagen (${e.errorCodeName})."
    }

    private fun isNoSpace(t: Throwable): Boolean =
        (t is ErrnoException && t.errno == OsConstants.ENOSPC) ||
            t.message?.contains("ENOSPC") == true ||
            t.message?.contains("No space left", ignoreCase = true) == true
}
