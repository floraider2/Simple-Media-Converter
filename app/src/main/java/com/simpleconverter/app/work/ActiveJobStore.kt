package com.simpleconverter.app.work

import android.content.Context
import android.net.Uri
import com.simpleconverter.app.model.InputFile
import com.simpleconverter.app.model.MediaKind
import com.simpleconverter.app.model.OutputFormat
import java.util.UUID

/**
 * Merkt sich die laufende Umwandlung, damit die App sie nach einem Neustart
 * (z. B. wenn Android den Prozess beendet hat) wieder anzeigen kann.
 */
object ActiveJobStore {
    private const val PREFS = "active_job"

    data class ActiveJob(val workId: UUID, val file: InputFile, val format: OutputFormat)

    fun save(context: Context, job: ActiveJob) {
        prefs(context).edit()
            .putString("id", job.workId.toString())
            .putString("uri", job.file.uri.toString())
            .putString("name", job.file.name)
            .putLong("size", job.file.size)
            .putString("mime", job.file.mimeType)
            .putString("kind", job.file.kind.name)
            .putLong("duration", job.file.durationMs ?: 0L)
            .putString("format", job.format.name)
            .apply()
    }

    fun load(context: Context): ActiveJob? = runCatching {
        val p = prefs(context)
        val id = p.getString("id", null) ?: return null
        ActiveJob(
            workId = UUID.fromString(id),
            file = InputFile(
                uri = Uri.parse(p.getString("uri", null)),
                name = p.getString("name", null) ?: "",
                size = p.getLong("size", 0L),
                mimeType = p.getString("mime", null),
                kind = MediaKind.valueOf(p.getString("kind", null)!!),
                durationMs = p.getLong("duration", 0L).takeIf { it > 0 },
            ),
            format = OutputFormat.valueOf(p.getString("format", null)!!),
        )
    }.getOrNull()

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
