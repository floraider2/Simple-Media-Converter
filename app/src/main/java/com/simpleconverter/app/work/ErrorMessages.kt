package com.simpleconverter.app.work

import android.content.Context
import android.graphics.ImageDecoder
import android.os.Build
import android.system.ErrnoException
import android.system.OsConstants
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import com.simpleconverter.app.R
import com.simpleconverter.app.convert.ConversionException
import java.io.FileNotFoundException

/** Übersetzt technische Fehler in Sätze, mit denen man etwas anfangen kann. */
@OptIn(UnstableApi::class)
object ErrorMessages {

    fun forThrowable(context: Context, t: Throwable): String {
        val chain = generateSequence(t) { it.cause }.toList()

        (chain.firstOrNull { it is ConversionException } as ConversionException?)?.let {
            return context.getString(it.messageRes, *it.args)
        }
        (chain.firstOrNull { it is ExportException } as ExportException?)?.let { return context.getString(forExport(it), it.errorCodeName) }

        if (chain.any { it is OutOfMemoryError }) {
            return context.getString(R.string.err_out_of_memory)
        }
        if (chain.any { isNoSpace(it) }) {
            return context.getString(R.string.err_no_space)
        }
        if (chain.any { it is SecurityException || it is FileNotFoundException }) {
            return context.getString(R.string.err_file_unreachable)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && chain.any { it is ImageDecoder.DecodeException }) {
            return context.getString(R.string.err_image_unsupported)
        }
        return context.getString(R.string.err_unexpected, t.javaClass.simpleName)
    }

    /** Text-ID; err_export_failed bekommt den Fehlercode als Argument, die anderen ignorieren es. */
    private fun forExport(e: ExportException): Int = when (e.errorCode) {
        ExportException.ERROR_CODE_DECODER_INIT_FAILED,
        ExportException.ERROR_CODE_DECODING_FAILED,
        ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            R.string.err_cannot_read_format
        ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
        ExportException.ERROR_CODE_ENCODING_FAILED,
        ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED ->
            R.string.err_cannot_encode
        ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
        ExportException.ERROR_CODE_IO_NO_PERMISSION ->
            R.string.err_file_unreachable
        ExportException.ERROR_CODE_MUXING_FAILED ->
            R.string.err_cannot_write_output
        else -> R.string.err_export_failed
    }

    private fun isNoSpace(t: Throwable): Boolean =
        (t is ErrnoException && t.errno == OsConstants.ENOSPC) ||
            t.message?.contains("ENOSPC") == true ||
            t.message?.contains("No space left", ignoreCase = true) == true
}
