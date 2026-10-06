package com.simpleconverter.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.simpleconverter.app.R
import com.simpleconverter.app.convert.OutputStore
import com.simpleconverter.app.data.AppSettings
import com.simpleconverter.app.data.FileInspector
import com.simpleconverter.app.data.RecentStore
import com.simpleconverter.app.data.SettingsStore
import com.simpleconverter.app.model.ConversionSettings
import com.simpleconverter.app.model.FileResult
import com.simpleconverter.app.model.InputFile
import com.simpleconverter.app.model.MediaKind
import com.simpleconverter.app.model.OutputFormat
import com.simpleconverter.app.model.Presets
import com.simpleconverter.app.model.RecentItem
import com.simpleconverter.app.model.commonTargets
import com.simpleconverter.app.work.ConversionWorker
import com.simpleconverter.app.work.JobStore
import com.simpleconverter.app.work.Notifications
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data class Setup(
        val files: List<InputFile>,
        val format: OutputFormat,
        /** null = „Eigene“ Einstellungen. */
        val presetId: String?,
        val settings: ConversionSettings,
        /** Für diese Umwandlung gewählter Ordner; null = Standard aus den Einstellungen. */
        val customFolder: String? = null,
    ) : Screen {
        /** Wohin das Ergebnis geht: eigener Ordner, sonst Standard für den Ziel-Dateityp. */
        fun folder(defaults: AppSettings): String? = customFolder ?: defaults.folderFor(format.kind)
    }
    data class Working(
        val files: List<InputFile>,
        val format: OutputFormat,
        val workId: UUID,
        /** Index der Datei, die gerade dran ist. */
        val index: Int = 0,
        /** Fortschritt über den ganzen Stapel. */
        val overall: Int = 0,
        /** true, solange eine geteilte Datei erst in den Cache kopiert wird. */
        val preparing: Boolean = false,
    ) : Screen
    data class Done(val files: List<InputFile>, val results: List<FileResult>) : Screen
    data class Failed(val files: List<InputFile>, val message: String) : Screen
}

class ConverterViewModel(app: Application) : AndroidViewModel(app) {

    private val workManager = WorkManager.getInstance(app)
    private val settingsStore = SettingsStore.get(app)
    val appSettings: StateFlow<AppSettings> = settingsStore.settings

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _recents = MutableStateFlow<List<RecentItem>>(emptyList())
    val recents: StateFlow<List<RecentItem>> = _recents.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** true, solange ausgewählte Dateien untersucht werden. */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private var observeJob: Job? = null

    init {
        refreshRecents()
        restoreActiveJob()
    }

    /** Läuft noch eine Umwandlung aus einer früheren Sitzung? Dann dort weitermachen. */
    private fun restoreActiveJob() {
        val id = JobStore.active(getApplication()) ?: return
        val job = JobStore.loadJob(getApplication(), id)
        if (job == null) {
            JobStore.setActive(getApplication(), null)
            return
        }
        _screen.value = Screen.Working(job.files, job.settings.format, id)
        observe(id, job.files, job.settings.format)
    }

    /** Eingehende Teilen-/Öffnen-Intents (eine oder mehrere Dateien). */
    fun handleIntent(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.streamUri() ?: intent.clipData?.getItemAt(0)?.uri)
            Intent.ACTION_SEND_MULTIPLE -> intent.streamUris()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        if (_screen.value is Screen.Working) {
            _message.value = str(R.string.msg_already_running)
            return
        }
        openFiles(uris)
    }

    fun openFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _loading.value = true
            val inspected = withContext(Dispatchers.IO) {
                uris.distinct().map { FileInspector.inspect(getApplication(), it) }
            }
            _loading.value = false
            val files = inspected.filterNotNull()
            val skipped = inspected.size - files.size
            val targets = commonTargets(files)
            when {
                files.isEmpty() ->
                    _message.value = str(if (uris.size == 1) R.string.msg_not_media_single else R.string.msg_not_media_all)
                targets.isEmpty() ->
                    _message.value = str(R.string.msg_mixed_kinds)
                else -> {
                    if (skipped > 0) _message.value = plural(R.plurals.msg_skipped, skipped)
                    _screen.value = setupFor(files, preferredFormat(files))
                }
            }
        }
    }

    fun selectFormat(format: OutputFormat) {
        val current = _screen.value as? Screen.Setup ?: return
        // Ein für diese Umwandlung gewählter Ordner bleibt beim Formatwechsel erhalten.
        val next = setupFor(current.files, format)
        _screen.value = next.copy(
            customFolder = current.customFolder,
            settings = next.settings.withUserChoicesFrom(current.settings),
        )
    }

    /** Ausschnitt setzen (null = vom Anfang / bis zum Ende). */
    fun setTrim(startMs: Long?, endMs: Long?) {
        val current = _screen.value as? Screen.Setup ?: return
        val full = current.files.singleOrNull()?.durationMs ?: return
        val start = startMs?.coerceIn(0L, full)?.takeIf { it > 0 }
        val end = endMs?.coerceIn(0L, full)?.takeIf { it < full }
        _screen.value = current.copy(settings = current.settings.copy(trimStartMs = start, trimEndMs = end))
    }

    /** Ordner nur für diese Umwandlung (null = zurück zum Standard). */
    fun chooseFolderForThisConversion(tree: Uri?) {
        val current = _screen.value as? Screen.Setup ?: return
        tree?.let(::keepAccess)
        _screen.value = current.copy(customFolder = tree?.toString())
    }

    /** Standardordner für einen Dateityp in den Einstellungen (null = Filme/Musik/Bilder). */
    fun chooseDefaultFolder(kind: MediaKind, tree: Uri?) {
        tree?.let(::keepAccess)
        settingsStore.update { it.withFolder(kind, tree?.toString()) }
    }

    /** Dauerhafte Rechte, damit der Hintergrund-Worker auch später noch in den Ordner schreiben darf. */
    private fun keepAccess(tree: Uri) {
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    fun selectPreset(id: String) {
        val current = _screen.value as? Screen.Setup ?: return
        val preset = Presets.forFormat(current.format).firstOrNull { it.id == id } ?: return
        _screen.value = current.copy(presetId = id, settings = preset.settings.withUserChoicesFrom(current.settings))
    }

    /** Änderung unter „Erweitert“ – macht aus der Vorgabe eigene Einstellungen. */
    fun updateSettings(transform: (ConversionSettings) -> ConversionSettings) {
        val current = _screen.value as? Screen.Setup ?: return
        val updated = transform(current.settings)
        // „Kameradaten behalten“ ist unabhängig von der Vorgabe.
        val matching = Presets.forFormat(current.format)
            .firstOrNull { it.settings.withUserChoicesFrom(updated) == updated }
        _screen.value = current.copy(settings = updated, presetId = matching?.id)
    }

    /** Eine Datei aus dem Stapel entfernen. */
    fun removeFile(file: InputFile) {
        val current = _screen.value as? Screen.Setup ?: return
        val remaining = current.files - file
        if (remaining.isEmpty()) {
            goHome()
            return
        }
        val targets = commonTargets(remaining)
        _screen.value = if (current.format in targets) current.copy(files = remaining) else setupFor(remaining, targets.first())
    }

    fun startConversion() {
        val setup = _screen.value as? Screen.Setup ?: return
        val id = UUID.randomUUID()
        val settings = setup.settings.copy(outputFolder = setup.folder(appSettings.value))
        JobStore.saveJob(getApplication(), JobStore.Job(id, setup.files, settings))
        workManager.enqueue(
            OneTimeWorkRequestBuilder<ConversionWorker>()
                .setId(id)
                .setInputData(ConversionWorker.inputData(id))
                .build()
        )
        JobStore.setActive(getApplication(), id)
        _screen.value = Screen.Working(setup.files, setup.format, id)
        observe(id, setup.files, setup.format)
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
        val files = when (val s = _screen.value) {
            is Screen.Failed -> s.files
            is Screen.Done -> s.files
            else -> return
        }
        _screen.value = setupFor(files, preferredFormat(files))
    }

    fun openSettings() {
        if (_screen.value is Screen.Home) _screen.value = Screen.Settings
    }

    fun updateAppSettings(transform: (AppSettings) -> AppSettings) = settingsStore.update(transform)

    /** „Speichern unter …“ für ein Ergebnis. */
    fun saveCopy(result: FileResult, target: Uri) {
        val from = result.outputUri ?: return
        viewModelScope.launch {
            _message.value = withContext(Dispatchers.IO) {
                runCatching { OutputStore.copy(getApplication(), from, target) }
                    .fold({ str(R.string.msg_saved) }, { str(R.string.msg_save_failed) })
            }
        }
    }

    /** Alle erfolgreichen Ergebnisse in einen Ordner kopieren. */
    fun saveAllTo(results: List<FileResult>, tree: Uri) {
        val files = results.filter { it.ok }.map { Triple(it.outputUri!!, it.outputName!!, it.mimeType) }
        viewModelScope.launch {
            val copied = withContext(Dispatchers.IO) {
                runCatching { OutputStore.copyToFolder(getApplication(), tree, files) }.getOrDefault(0)
            }
            _message.value = if (copied == files.size) plural(R.plurals.msg_saved_n, copied) else str(R.string.msg_saved_some, copied, files.size)
        }
    }

    fun clearRecents() {
        RecentStore.clear(getApplication())
        refreshRecents()
    }

    /** Die App ist wieder sichtbar: Ergebnis steht auf dem Bildschirm, die Benachrichtigung ist überflüssig. */
    fun onAppVisible() {
        if (_screen.value is Screen.Done || _screen.value is Screen.Failed) {
            Notifications.cancelResult(getApplication())
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun observe(id: UUID, files: List<InputFile>, format: OutputFormat) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(id).collect { info ->
                if (info == null) {
                    // Auftrag existiert nicht mehr (z. B. von WorkManager aufgeräumt).
                    JobStore.setActive(getApplication(), null)
                    _screen.value = Screen.Home
                    observeJob?.cancel()
                    return@collect
                }
                if (info.state.isFinished) JobStore.setActive(getApplication(), null)
                when (info.state) {
                    WorkInfo.State.SUCCEEDED -> {
                        val results = withContext(Dispatchers.IO) { JobStore.loadResults(getApplication(), id) }
                        _screen.value = Screen.Done(files, results)
                        refreshRecents()
                        observeJob?.cancel()
                    }
                    WorkInfo.State.FAILED -> {
                        val error = info.outputData.getString(ConversionWorker.KEY_ERROR) ?: str(R.string.err_unknown)
                        _screen.value = Screen.Failed(files, error)
                        observeJob?.cancel()
                    }
                    WorkInfo.State.CANCELLED -> {
                        val done = withContext(Dispatchers.IO) { JobStore.loadResults(getApplication(), id) }.count { it.ok }
                        _message.value = if (done > 0) plural(R.plurals.msg_cancelled_some_done, done) else str(R.string.msg_cancelled)
                        _screen.value = setupFor(files, format)
                        refreshRecents()
                        observeJob?.cancel()
                    }
                    else -> {
                        val p = info.progress
                        _screen.value = Screen.Working(
                            files = files,
                            format = format,
                            workId = id,
                            index = p.getInt(ConversionWorker.KEY_INDEX, 0),
                            overall = p.getInt(ConversionWorker.KEY_OVERALL, 0),
                            preparing = p.getString(ConversionWorker.KEY_PHASE) == ConversionWorker.PHASE_COPY,
                        )
                    }
                }
            }
        }
    }

    private fun str(@StringRes id: Int, vararg args: Any) = getApplication<Application>().getString(id, *args)

    private fun plural(@PluralsRes id: Int, count: Int) =
        getApplication<Application>().resources.getQuantityString(id, count, count)

    private fun setupFor(files: List<InputFile>, format: OutputFormat): Screen.Setup {
        val first = Presets.forFormat(format).first()
        val settings = first.settings.copy(keepMetadata = appSettings.value.keepMetadata)
        return Screen.Setup(files, format, first.id, settings)
    }

    /** Standardformat aus den Einstellungen, falls es für alle Dateien passt. */
    private fun preferredFormat(files: List<InputFile>): OutputFormat {
        val targets = commonTargets(files)
        val preferred = appSettings.value.defaultFor(files.first().kind)
        return if (preferred in targets) preferred else targets.first()
    }

    private fun refreshRecents() {
        viewModelScope.launch {
            _recents.value = withContext(Dispatchers.IO) { RecentStore.load(getApplication()) }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamUri(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun Intent.streamUris(): List<Uri> {
        val extra = if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        if (!extra.isNullOrEmpty()) return extra
        val clip = clipData ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }
}
