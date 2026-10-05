package com.simpleconverter.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.simpleconverter.app.MainActivity
import com.simpleconverter.app.R
import com.simpleconverter.app.convert.ImageConverter
import com.simpleconverter.app.convert.OutputStore
import com.simpleconverter.app.convert.VideoConverter
import com.simpleconverter.app.convert.WavConverter
import com.simpleconverter.app.data.RecentStore
import com.simpleconverter.app.model.ConversionSettings
import com.simpleconverter.app.model.InputFile
import com.simpleconverter.app.model.OutputFormat
import com.simpleconverter.app.model.RecentItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Führt eine Umwandlung im Hintergrund aus, mit Fortschritt in der Benachrichtigungsleiste. */
class ConversionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val notifications = context.getSystemService(NotificationManager::class.java)

    override suspend fun doWork(): Result {
        val input = Uri.parse(inputData.getString(KEY_INPUT_URI))
        val inputName = inputData.getString(KEY_INPUT_NAME) ?: "datei"
        val inputSize = inputData.getLong(KEY_INPUT_SIZE, 0L)
        val durationMs = inputData.getLong(KEY_DURATION, 0L).takeIf { it > 0 }
        val settings = ConversionSettings.fromData(inputData)
        val outputName = inputName.substringBeforeLast('.') + "." + settings.format.extension

        createChannel()
        runCatching { setForeground(foregroundInfo(outputName, 0)) }

        val outDir = File(applicationContext.cacheDir, "out").apply { mkdirs() }
        val temp = File(outDir, "$id.${settings.format.extension}")
        val progress = MutableStateFlow(0)

        return coroutineScope {
            val reporter = launch {
                progress.collect { p ->
                    setProgress(workDataOf(KEY_PROGRESS to p))
                    runCatching { notifications.notify(NOTIFICATION_ID, buildNotification(outputName, p)) }
                }
            }
            try {
                when (settings.format) {
                    OutputFormat.MP4, OutputFormat.M4A ->
                        VideoConverter.convert(applicationContext, input, temp, settings, durationMs) { progress.value = it }
                    OutputFormat.WAV ->
                        WavConverter.convert(applicationContext, input, temp) { progress.value = it }
                    OutputFormat.JPG, OutputFormat.PNG, OutputFormat.WEBP ->
                        ImageConverter.convert(applicationContext, input, temp, settings)
                }
                val outputSize = temp.length()
                val outputUri = OutputStore.save(applicationContext, temp, outputName, settings.format)
                RecentStore.add(
                    applicationContext,
                    RecentItem(inputName, outputName, outputUri, settings.format.mimeType, inputSize, outputSize, System.currentTimeMillis()),
                )
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
            } catch (e: Exception) {
                Result.failure(workDataOf(KEY_ERROR to friendlyError(e)))
            } finally {
                reporter.cancel()
                temp.delete()
            }
        }
    }

    private fun friendlyError(e: Exception): String {
        val detail = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.firstOrNull()
        return if (detail.isNullOrBlank()) "Unbekannter Fehler (${e.javaClass.simpleName})" else detail
    }

    private fun foregroundInfo(name: String, progress: Int): ForegroundInfo {
        val notification = buildNotification(name, progress)
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
                ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(name: String, progress: Int) =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Wandle um: $name")
            .setContentText("$progress %")
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    applicationContext, 0,
                    Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .addAction(0, "Abbrechen", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Umwandlungen", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        const val KEY_INPUT_URI = "inputUri"
        const val KEY_INPUT_NAME = "inputName"
        const val KEY_INPUT_SIZE = "inputSize"
        const val KEY_DURATION = "duration"
        const val KEY_PROGRESS = "progress"
        const val KEY_OUTPUT_URI = "outputUri"
        const val KEY_OUTPUT_NAME = "outputName"
        const val KEY_OUTPUT_SIZE = "outputSize"
        const val KEY_OUTPUT_MIME = "outputMime"
        const val KEY_ERROR = "error"

        private const val CHANNEL_ID = "conversion"
        private const val NOTIFICATION_ID = 1

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
