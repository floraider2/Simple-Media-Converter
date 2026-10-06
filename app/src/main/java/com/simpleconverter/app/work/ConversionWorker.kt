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
import com.simpleconverter.app.R
import com.simpleconverter.app.convert.ImageConverter
import com.simpleconverter.app.convert.InputCache
import com.simpleconverter.app.convert.OutputStore
import com.simpleconverter.app.convert.VideoConverter
import com.simpleconverter.app.convert.audio.AudioConverter
import com.simpleconverter.app.convert.audio.Loudness
import com.simpleconverter.app.data.RecentStore
import com.simpleconverter.app.data.formatSize
import com.simpleconverter.app.model.ConversionSettings
import com.simpleconverter.app.model.FileResult
import com.simpleconverter.app.model.InputFile
import com.simpleconverter.app.model.MediaKind
import com.simpleconverter.app.model.OutputFormat
import com.simpleconverter.app.model.RecentItem
import com.simpleconverter.app.model.outputFileName
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wandelt einen Stapel von Dateien nacheinander um (eine einzelne Datei ist ein Stapel mit
 * einem Eintrag). Nacheinander statt parallel, weil Hardware-Encoder begrenzt sind.
 * Schlägt eine Datei fehl, geht es mit der nächsten weiter.
 */
class ConversionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private data class Status(val index: Int, val count: Int, val name: String, val phase: String, val progress: Int) {
        /** Fortschritt über den ganzen Stapel. */
        val overall get() = ((index * 100 + progress) / count).coerceIn(0, 100)
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        val jobId = inputData.getString(KEY_JOB_ID)?.let(UUID::fromString) ?: return Result.failure()
        val job = JobStore.loadJob(context, jobId)
            ?: return Result.failure(workDataOf(KEY_ERROR to context.getString(R.string.err_job_missing)))
        val files = job.files
        val count = files.size

        Notifications.createChannels(context)
        InputCache.cleanup(context)
        JobStore.cleanup(context, keep = jobId)
        Notifications.cancelResult(context)
        val status = MutableStateFlow(Status(0, count, files.first().name, PHASE_CONVERT, 0))
        runCatching { setForeground(foregroundInfo(status.value)) }

        val copies = mutableMapOf<Int, File>()
        val results = mutableListOf<FileResult>()

        return coroutineScope {
            val reporter = launch {
                status.collect { s ->
                    setProgress(
                        workDataOf(
                            KEY_INDEX to s.index, KEY_COUNT to s.count, KEY_NAME to s.name,
                            KEY_PHASE to s.phase, KEY_PROGRESS to s.progress, KEY_OVERALL to s.overall,
                        )
                    )
                    Notifications.update(context, Notifications.ID_PROGRESS, progressNotification(s))
                }
            }
            try {
                prepareInputs(files, copies) { i, p -> status.value = Status(i, count, files[i].name, PHASE_COPY, p) }

                files.forEachIndexed { i, file ->
                    status.value = Status(i, count, file.name, PHASE_CONVERT, 0)
                    results += convertOne(
                        file, copies[i], job.settings,
                        onCopy = { p -> status.value = Status(i, count, file.name, PHASE_COPY, p) },
                        onProgress = { p -> status.value = Status(i, count, file.name, PHASE_CONVERT, p) },
                    )
                    copies.remove(i)?.delete()
                    JobStore.saveResults(context, jobId, results)
                }

                if (!appInForeground()) notifyFinished(results)
                Result.success(workDataOf(KEY_JOB_ID to jobId.toString()))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val message = ErrorMessages.forThrowable(applicationContext, t)
                if (!appInForeground()) Notifications.failed(context, files.first().name, message)
                Result.failure(workDataOf(KEY_ERROR to message))
            } finally {
                reporter.cancel()
                copies.values.forEach { it.delete() }
            }
        }
    }

    /**
     * Geteilte Dateien dürfen wir nur lesen, solange die Activity lebt. Wenn genug Platz ist,
     * kopieren wir deshalb alle vorab; sonst einzeln direkt vor der Umwandlung (siehe [convertOne]).
     */
    private suspend fun prepareInputs(files: List<InputFile>, copies: MutableMap<Int, File>, onProgress: (Int, Int) -> Unit) {
        val context = applicationContext
        val toCopy = files.indices.filter { InputCache.needsCopy(context, files[it].uri) }
        if (toCopy.size < 2) return
        val needed = toCopy.sumOf { files[it].size }
        val free = context.cacheDir.usableSpace - RESERVE_BYTES
        if (needed > free) return
        for (i in toCopy) {
            copies[i] = InputCache.copy(context, files[i].uri, files[i].name) { onProgress(i, it) }
        }
    }

    private suspend fun convertOne(
        file: InputFile,
        preparedCopy: File?,
        settings: ConversionSettings,
        onCopy: (Int) -> Unit,
        onProgress: (Int) -> Unit,
    ): FileResult {
        val context = applicationContext
        val outputName = outputFileName(file.name, settings.format)
        val temp = File(File(context.cacheDir, "out").apply { mkdirs() }, "${UUID.randomUUID()}.${settings.format.extension}")
        var lateCopy: File? = null
        return try {
            val input: Uri = when {
                preparedCopy != null -> Uri.fromFile(preparedCopy)
                InputCache.needsCopy(context, file.uri) ->
                    InputCache.copy(context, file.uri, file.name, onCopy).also { lateCopy = it }.let(Uri::fromFile)
                else -> file.uri
            }
            onProgress(0)
            // Lautstärke angleichen: erster Durchgang misst (0–40 %), zweiter wandelt um (40–100 %).
            val withAudio = settings.format.kind == MediaKind.AUDIO || (file.hasAudio && !settings.removeAudio)
            val normalize = settings.normalizeLoudness && withAudio && settings.format.kind != MediaKind.IMAGE
            val gainDb = if (normalize) Loudness.measureGainDb(context, input, settings) { onProgress(it * 40 / 100) } else 0.0
            val convertProgress: (Int) -> Unit = if (normalize) { p -> onProgress(40 + p * 60 / 100) } else onProgress
            when (settings.format) {
                OutputFormat.MP4, OutputFormat.WEBM, OutputFormat.M4A ->
                    VideoConverter.convert(context, input, temp, settings, file.durationMs, convertProgress, gainDb)
                OutputFormat.MP3, OutputFormat.OPUS, OutputFormat.FLAC, OutputFormat.WAV ->
                    AudioConverter.convert(context, input, temp, settings, convertProgress, gainDb)
                OutputFormat.JPG, OutputFormat.PNG, OutputFormat.WEBP ->
                    ImageConverter.convert(context, input, temp, settings)
            }
            val outputSize = temp.length()
            val outputUri = OutputStore.save(context, temp, outputName, settings.format, settings.outputFolder)
            RecentStore.add(
                context,
                RecentItem(file.name, outputName, outputUri, settings.format.mimeType, file.size, outputSize, System.currentTimeMillis()),
            )
            FileResult(file.name, file.size, outputUri, outputName, outputSize, settings.format.mimeType, null)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Throwable statt Exception: auch OutOfMemoryError bei riesigen Bildern abfangen.
            FileResult(file.name, file.size, null, null, 0L, settings.format.mimeType, ErrorMessages.forThrowable(applicationContext, t))
        } finally {
            temp.delete()
            lateCopy?.delete()
        }
    }

    private fun notifyFinished(results: List<FileResult>) {
        val context = applicationContext
        val ok = results.filter { it.ok }
        when {
            results.size == 1 && ok.size == 1 -> with(ok.first()) {
                Notifications.done(context, outputName!!, "${formatSize(inputSize)} → ${formatSize(outputSize)}", outputUri!!, mimeType)
            }
            results.size == 1 -> Notifications.failed(context, results.first().inputName, results.first().error ?: "")
            else -> Notifications.batchDone(
                context, ok.size, results.size,
                "${formatSize(ok.sumOf { it.inputSize })} → ${formatSize(ok.sumOf { it.outputSize })}",
            )
        }
    }

    private suspend fun appInForeground(): Boolean = withContext(Dispatchers.Main) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    private fun progressNotification(s: Status) = Notifications.progress(
        applicationContext,
        title = if (s.count > 1) applicationContext.getString(R.string.notif_file_n, s.index + 1, s.count, s.name) else s.name,
        text = if (s.phase == PHASE_COPY) applicationContext.getString(R.string.notif_preparing, s.progress) else "${s.overall} %",
        progress = s.overall,
        cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
    )

    private fun foregroundInfo(s: Status): ForegroundInfo {
        val notification = progressNotification(s)
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
                ForegroundInfo(Notifications.ID_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                ForegroundInfo(Notifications.ID_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> ForegroundInfo(Notifications.ID_PROGRESS, notification)
        }
    }

    companion object {
        const val KEY_JOB_ID = "jobId"
        const val KEY_INDEX = "index"
        const val KEY_COUNT = "count"
        const val KEY_NAME = "name"
        const val KEY_PHASE = "phase"
        const val KEY_PROGRESS = "progress"
        const val KEY_OVERALL = "overall"
        const val KEY_ERROR = "error"

        const val PHASE_COPY = "copy"
        const val PHASE_CONVERT = "convert"

        /** So viel Platz bleibt beim Vorab-Kopieren mindestens frei. */
        private const val RESERVE_BYTES = 500L * 1024 * 1024

        fun inputData(jobId: UUID): Data = workDataOf(KEY_JOB_ID to jobId.toString())
    }
}
