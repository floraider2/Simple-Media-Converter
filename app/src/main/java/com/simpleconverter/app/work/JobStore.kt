package com.simpleconverter.app.work

import android.content.Context
import android.net.Uri
import com.simpleconverter.app.model.ConversionSettings
import com.simpleconverter.app.model.FileResult
import com.simpleconverter.app.model.InputFile
import com.simpleconverter.app.model.MediaKind
import com.simpleconverter.app.model.OutputFormat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Stapel-Aufträge liegen als JSON-Dateien im App-Speicher, weil WorkManager-Data auf 10 KB
 * begrenzt ist (50 Dateien mit langen URIs passen da nicht hinein).
 *
 * - `jobs/<id>.json`         Dateien + Einstellungen
 * - `jobs/<id>.result.json`  Ergebnis je Datei
 * - Preferences „active“      ID des Auftrags, den die UI gerade verfolgt
 */
object JobStore {

    data class Job(val id: UUID, val files: List<InputFile>, val settings: ConversionSettings)

    private const val PREFS = "active_job"
    private val MAX_AGE_MS = TimeUnit.DAYS.toMillis(2)

    // ───────────── Auftrag ─────────────

    fun saveJob(context: Context, job: Job) {
        val files = JSONArray()
        job.files.forEach { f ->
            files.put(
                JSONObject()
                    .put("uri", f.uri.toString())
                    .put("name", f.name)
                    .put("size", f.size)
                    .put("mime", f.mimeType)
                    .put("kind", f.kind.name)
                    .put("duration", f.durationMs ?: 0L)
                    .put("hasAudio", f.hasAudio)
            )
        }
        val st = job.settings
        val settings = JSONObject()
            .put("format", st.format.name)
            .put("videoShortSide", st.videoShortSide ?: 0)
            .put("videoBitrate", st.videoBitrate ?: 0)
            .put("targetSizeBytes", st.targetSizeBytes ?: 0L)
            .put("removeAudio", st.removeAudio)
            .put("audioBitrate", st.audioBitrate)
            .put("imageMaxSide", st.imageMaxSide ?: 0)
            .put("imageQuality", st.imageQuality)
        write(file(context, job.id, ""), JSONObject().put("files", files).put("settings", settings))
    }

    fun loadJob(context: Context, id: UUID): Job? = runCatching {
        val o = JSONObject(file(context, id, "").readText())
        val arr = o.getJSONArray("files")
        val files = (0 until arr.length()).map { i ->
            val f = arr.getJSONObject(i)
            InputFile(
                uri = Uri.parse(f.getString("uri")),
                name = f.getString("name"),
                size = f.getLong("size"),
                mimeType = f.optString("mime").takeIf { it.isNotEmpty() && it != "null" },
                kind = MediaKind.valueOf(f.getString("kind")),
                durationMs = f.getLong("duration").takeIf { it > 0 },
                hasAudio = f.optBoolean("hasAudio", true),
            )
        }
        val s = o.getJSONObject("settings")
        val settings = ConversionSettings(
            format = OutputFormat.valueOf(s.getString("format")),
            videoShortSide = s.getInt("videoShortSide").takeIf { it > 0 },
            videoBitrate = s.getInt("videoBitrate").takeIf { it > 0 },
            targetSizeBytes = s.getLong("targetSizeBytes").takeIf { it > 0 },
            removeAudio = s.getBoolean("removeAudio"),
            audioBitrate = s.getInt("audioBitrate"),
            imageMaxSide = s.getInt("imageMaxSide").takeIf { it > 0 },
            imageQuality = s.getInt("imageQuality"),
        )
        Job(id, files, settings)
    }.getOrNull()

    // ───────────── Ergebnisse ─────────────

    fun saveResults(context: Context, id: UUID, results: List<FileResult>) {
        val arr = JSONArray()
        results.forEach { r ->
            arr.put(
                JSONObject()
                    .put("inName", r.inputName)
                    .put("inSize", r.inputSize)
                    .put("uri", r.outputUri?.toString())
                    .put("outName", r.outputName)
                    .put("outSize", r.outputSize)
                    .put("mime", r.mimeType)
                    .put("error", r.error)
            )
        }
        write(file(context, id, ".result"), JSONObject().put("results", arr))
    }

    fun loadResults(context: Context, id: UUID): List<FileResult> = runCatching {
        val arr = JSONObject(file(context, id, ".result").readText()).getJSONArray("results")
        (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            FileResult(
                inputName = r.getString("inName"),
                inputSize = r.getLong("inSize"),
                outputUri = r.optString("uri").takeIf { it.isNotEmpty() && it != "null" }?.let(Uri::parse),
                outputName = r.optString("outName").takeIf { it.isNotEmpty() && it != "null" },
                outputSize = r.getLong("outSize"),
                mimeType = r.getString("mime"),
                error = r.optString("error").takeIf { it.isNotEmpty() && it != "null" },
            )
        }
    }.getOrDefault(emptyList())

    // ───────────── Aktiver Auftrag (für die UI) ─────────────

    fun setActive(context: Context, id: UUID?) {
        prefs(context).edit().apply { if (id == null) remove("id") else putString("id", id.toString()) }.apply()
    }

    fun active(context: Context): UUID? =
        prefs(context).getString("id", null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    /** Alte Auftragsdateien entfernen. */
    fun cleanup(context: Context, keep: UUID) {
        val now = System.currentTimeMillis()
        dir(context).listFiles()?.forEach { f ->
            if (!f.name.startsWith(keep.toString()) && now - f.lastModified() > MAX_AGE_MS) f.delete()
        }
    }

    private fun write(target: File, json: JSONObject) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(target)
    }

    private fun dir(context: Context) = File(context.filesDir, "jobs").apply { mkdirs() }
    private fun file(context: Context, id: UUID, suffix: String) = File(dir(context), "$id$suffix.json")
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
