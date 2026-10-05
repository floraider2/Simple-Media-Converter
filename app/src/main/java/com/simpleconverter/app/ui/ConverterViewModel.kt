package com.simpleconverter.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.simpleconverter.app.data.FileInspector
import com.simpleconverter.app.data.RecentStore
import com.simpleconverter.app.model.ConversionSettings
import com.simpleconverter.app.model.InputFile
import com.simpleconverter.app.model.OutputFormat
import com.simpleconverter.app.model.Presets
import com.simpleconverter.app.model.RecentItem
import com.simpleconverter.app.work.ActiveJobStore
import com.simpleconverter.app.work.ConversionWorker
import com.simpleconverter.app.work.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface Screen {
    data object Home : Screen
    data class Setup(
        val file: InputFile,
        val format: OutputFormat,
        /** null = „Eigene“ Einstellungen. */
        val presetId: String?,
        val settings: ConversionSettings,
    ) : Screen
    data class Working(
        val file: InputFile,
        val format: OutputFormat,
        val progress: Int,
        val workId: UUID,
        /** true, solange eine geteilte Datei erst in den Cache kopiert wird. */
        val preparing: Boolean = false,
    ) : Screen
    data class Done(
        val file: InputFile,
        val outputUri: Uri,
        val outputName: String,
        val outputSize: Long,
        val mimeType: String,
    ) : Screen
    data class Failed(val file: InputFile, val message: String) : Screen
}

class ConverterViewModel(app: Application) : AndroidViewModel(app) {

    private val workManager = WorkManager.getInstance(app)

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _recents = MutableStateFlow<List<RecentItem>>(emptyList())
    val recents: StateFlow<List<RecentItem>> = _recents.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var observeJob: Job? = null

    init {
        refreshRecents()
        restoreActiveJob()
    }

    /** Läuft noch eine Umwandlung aus einer früheren Sitzung? Dann dort weitermachen. */
    private fun restoreActiveJob() {
        val job = ActiveJobStore.load(getApplication()) ?: return
        _screen.value = Screen.Working(job.file, job.format, 0, job.workId)
        observe(job.workId, job.file, job.format)
    }

    /** Eingehende Teilen-/Öffnen-Intents. */
    fun handleIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_SEND -> intent.streamUri()
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } ?: return
        if (_screen.value is Screen.Working) {
            _message.value = "Es läuft bereits eine Umwandlung."
            return
        }
        openFile(uri)
    }

    fun openFile(uri: Uri) {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { FileInspector.inspect(getApplication(), uri) }
            if (file == null) {
                _message.value = "Diese Datei ist kein Video, Audio oder Bild."
                return@launch
            }
            val format = OutputFormat.targetsFor(file.kind).first()
            _screen.value = setupFor(file, format)
        }
    }

    fun selectFormat(format: OutputFormat) {
        val current = _screen.value as? Screen.Setup ?: return
        _screen.value = setupFor(current.file, format)
    }

    fun selectPreset(id: String) {
        val current = _screen.value as? Screen.Setup ?: return
        val preset = Presets.forFormat(current.format).firstOrNull { it.id == id } ?: return
        _screen.value = current.copy(presetId = id, settings = preset.settings)
    }

    /** Änderung unter „Erweitert“ – macht aus der Vorgabe eigene Einstellungen. */
    fun updateSettings(transform: (ConversionSettings) -> ConversionSettings) {
        val current = _screen.value as? Screen.Setup ?: return
        val updated = transform(current.settings)
        val matching = Presets.forFormat(current.format).firstOrNull { it.settings == updated }
        _screen.value = current.copy(settings = updated, presetId = matching?.id)
    }

    fun startConversion() {
        val setup = _screen.value as? Screen.Setup ?: return
        val request = OneTimeWorkRequestBuilder<ConversionWorker>()
            .setInputData(ConversionWorker.inputData(setup.file, setup.settings))
            .build()
        workManager.enqueue(request)
        ActiveJobStore.save(getApplication(), ActiveJobStore.ActiveJob(request.id, setup.file, setup.format))
        _screen.value = Screen.Working(setup.file, setup.format, 0, request.id)
        observe(request.id, setup.file, setup.format)
    }

    fun cancelConversion() {
        val working = _screen.value as? Screen.Working ?: return
        workManager.cancelWorkById(working.workId)
    }

    fun goHome() {
        observeJob?.cancel()
        _screen.value = Screen.Home
        refreshRecents()
    }

    fun backToSetup() {
        val file = when (val s = _screen.value) {
            is Screen.Failed -> s.file
            is Screen.Done -> s.file
            else -> return
        }
        _screen.value = setupFor(file, OutputFormat.targetsFor(file.kind).first())
    }

    fun clearRecents() {
        RecentStore.clear(getApplication())
        refreshRecents()
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun observe(id: UUID, file: InputFile, format: OutputFormat) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(id).collect { info ->
                if (info == null) {
                    // Job existiert nicht mehr (z. B. von WorkManager aufgeräumt).
                    ActiveJobStore.clear(getApplication())
                    _screen.value = Screen.Home
                    observeJob?.cancel()
                    return@collect
                }
                if (info.state.isFinished) {
                    ActiveJobStore.clear(getApplication())
                    Notifications.cancelResult(getApplication())
                }
                when (info.state) {
                    WorkInfo.State.SUCCEEDED -> {
                        val out = info.outputData
                        _screen.value = Screen.Done(
                            file = file,
                            outputUri = Uri.parse(out.getString(ConversionWorker.KEY_OUTPUT_URI)),
                            outputName = out.getString(ConversionWorker.KEY_OUTPUT_NAME) ?: "",
                            outputSize = out.getLong(ConversionWorker.KEY_OUTPUT_SIZE, 0L),
                            mimeType = out.getString(ConversionWorker.KEY_OUTPUT_MIME) ?: "*/*",
                        )
                        refreshRecents()
                        observeJob?.cancel()
                    }
                    WorkInfo.State.FAILED -> {
                        val error = info.outputData.getString(ConversionWorker.KEY_ERROR) ?: "Unbekannter Fehler"
                        _screen.value = Screen.Failed(file, error)
                        observeJob?.cancel()
                    }
                    WorkInfo.State.CANCELLED -> {
                        _message.value = "Umwandlung abgebrochen."
                        _screen.value = setupFor(file, format)
                        observeJob?.cancel()
                    }
                    else -> {
                        val progress = info.progress.getInt(ConversionWorker.KEY_PROGRESS, 0)
                        val preparing = info.progress.getString(ConversionWorker.KEY_PHASE) == ConversionWorker.PHASE_COPY
                        _screen.value = Screen.Working(file, format, progress, id, preparing)
                    }
                }
            }
        }
    }

    private fun setupFor(file: InputFile, format: OutputFormat): Screen.Setup {
        val first = Presets.forFormat(format).first()
        return Screen.Setup(file, format, first.id, first.settings)
    }

    private fun refreshRecents() {
        viewModelScope.launch {
            _recents.value = withContext(Dispatchers.IO) { RecentStore.load(getApplication()) }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamUri(): Uri? =
        if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableExtra(Intent.EXTRA_STREAM)
}
