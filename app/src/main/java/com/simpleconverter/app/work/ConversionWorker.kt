package com.simpleconverter.app.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.simpleconverter.app.convert.ImageConverter
import com.simpleconverter.app.convert.InputCache
import com.simpleconverter.app.convert.OutputStore
import com.simpleconverter.app.convert.VideoConverter
import com.simpleconverter.app.convert.WavConverter
import com.simpleconverter.app.data.RecentStore
import com.simpleconverter.app.data.formatSize
import com.simpleconverter.app.model.ConversionSettings
import com.simpleconverter.app.model.InputFile
import com.simpleconverter.app.model.OutputFormat
import com.simpleconverter.app.model.RecentItem
import com.simpleconverter.app.model.outputFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Führt eine Umwandlung im Hintergrund aus, mit Fortschritt in der Benachrichtigungsleiste. */
class ConversionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private data class Status(val phase: String, val progress: Int)

    override suspend fun doWork(): Result {
        val context = applicationContext
        val original = Uri.parse(inputData.getString(KEY_INPUT_URI))
        val inputName = inputData.getString(KEY_INPUT_NAME) ?: "datei"
        val inputSize = inputData.getLong(KEY_INPUT_SIZE, 0L)
        val durationMs = inputData.getLong(KEY_DURATION, 0L).takeIf { it > 0 }
        val settings = ConversionSettings.fromData(inputData)
        val outputName = outputFileName(inputName, settings.format)

        Notifications.createChannels(context)
        InputCache.cleanup(context)
        Notifications.cancelResult(context)
        runCatching { setForeground(foregroundInfo(outputName, Status(PHASE_CONVERT, 0))) }

        val outDir = File(context.cacheDir, "out").apply { mkdirs() }
        val temp = File(outDir, "$id.${settings.format.extension}")
        var copiedInput: File? = null
        val status = MutableStateFlow(Status(PHASE_CONVERT, 0))

        return coroutineScope {
            val reporter = launch {
                status.collect { s ->
                    setProgress(workDataOf(KEY_PROGRESS to s.progress, KEY_PHASE to s.phase))
                    Notifications.update(context, Notifications.ID_PROGRESS, progressNotification(outputName, s))
                }
            }
            try {
                val input = if (InputCache.needsCopy(context, original)) {
                    status.value = Status(PHASE_COPY, 0)
                    InputCache.copy(context, original, inputName) { status.value = Status(PHASE_COPY, it) }
                        .also { copiedInput = it }
                        .let(Uri::fromFile)
                } else {
                    original
                }
                status.value = Status(PHASE_CONVERT, 0)
                val onProgress: (Int) -> Unit = { status.value = Status(PHASE_CONVERT, it) }

                when (settings.format) {
                    OutputFormat.MP4, OutputFormat.M4A ->
                        VideoConverter.convert(context, input, temp, settings, durationMs, onProgress)
                    OutputFormat.WAV ->
                        WavConverter.convert(context, input, temp, onProgress)
                    OutputFormat.JPG, OutputFormat.PNG, OutputFormat.WEBP ->
                        ImageConverter.convert(context, input, temp, settings)
                }

                val outputSize = temp.length()
                val outputUri = OutputStore.save(context, temp, outputName, settings.format)
                RecentStore.add(
                    context,
                    RecentItem(
                        inputName, outputName, outputUri, settings.format.mimeType,
                        inputSize, outputSize, System.currentTimeMillis(),
                    ),
                )
                if (!appInForeground()) {
                    Notifications.done(
                        context, outputName, "${formatSize(inputSize)} → ${formatSize(outputSize)}",
                        outputUri, settings.format.mimeType,
                    )
                }
                Result.success(
                    workDataOf(
                        KEY_OUTPUT_URI to outputUri.toString(),
                        KEY_OUTPUT_NAME to outputName,
                        KEY_OUTPUT_SIZE to outputSize,
                        KEY_OUTPUT_MIME to settings.format.mimeType,
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Throwable statt Exception: auch OutOfMemoryError bei riesigen Bildern abfangen.
                val message = ErrorMessages.forThrowable(t)
                if (!appInForeground()) Notifications.failed(context, inputName, message)
                Result.failure(workDataOf(KEY_ERROR to message))
            } finally {
                reporter.cancel()
                temp.delete()
                copiedInput?.delete()
            }
        }
    }

    private suspend fun appInForeground(): Boolean = withContext(Dispatchers.Main) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    private fun progressNotification(name: String, status: Status) = Notifications.progress(
        applicationContext,
        title = name,
        text = if (status.phase == PHASE_COPY) "Datei wird vorbereitet … ${status.progress} %" else "${status.progress} %",
        progress = status.progress,
        cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
    )

    private fun foregroundInfo(name: String, status: Status): ForegroundInfo {
        val notification = progressNotification(name, status)
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
                ForegroundInfo(Notifications.ID_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                ForegroundInfo(Notifications.ID_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> ForegroundInfo(Notifications.ID_PROGRESS, notification)
        }
    }

    companion object {
        const val KEY_INPUT_URI = "inputUri"
        const val KEY_INPUT_NAME = "inputName"
        const val KEY_INPUT_SIZE = "inputSize"
        const val KEY_DURATION = "duration"
        const val KEY_PROGRESS = "progress"
        const val KEY_PHASE = "phase"
        const val KEY_OUTPUT_URI = "outputUri"
        const val KEY_OUTPUT_NAME = "outputName"
        const val KEY_OUTPUT_SIZE = "outputSize"
        const val KEY_OUTPUT_MIME = "outputMime"
        const val KEY_ERROR = "error"

        const val PHASE_COPY = "copy"
        const val PHASE_CONVERT = "convert"

        fun inputData(file: InputFile, settings: ConversionSettings): Data =
            Data.Builder()
                .putAll(settings.toData())
                .putString(KEY_INPUT_URI, file.uri.toString())
                .putString(KEY_INPUT_NAME, file.name)
                .putLong(KEY_INPUT_SIZE, file.size)
                .putLong(KEY_DURATION, file.durationMs ?: 0L)
                .build()
    }
}
