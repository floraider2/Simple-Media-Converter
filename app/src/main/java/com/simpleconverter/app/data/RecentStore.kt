package com.simpleconverter.app.data

import android.content.Context
import android.net.Uri
import com.simpleconverter.app.model.RecentItem
import org.json.JSONArray
import org.json.JSONObject

/** Kleiner Verlauf der letzten Umwandlungen, lokal in den SharedPreferences. */
object RecentStore {
    private const val PREFS = "recent"
    private const val KEY = "items"
    private const val MAX_ITEMS = 200

    @Synchronized
    fun load(context: Context): List<RecentItem> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                RecentItem(
                    inputName = o.getString("in"),
                    outputName = o.getString("out"),
                    outputUri = Uri.parse(o.getString("uri")),
                    mimeType = o.getString("mime"),
                    inputSize = o.getLong("inSize"),
                    outputSize = o.getLong("outSize"),
                    timestamp = o.getLong("time"),
                )
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(context: Context, item: RecentItem) {
        save(context, (listOf(item) + load(context)).take(MAX_ITEMS))
    }

    private fun save(context: Context, items: List<RecentItem>) {
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject()
                    .put("in", it.inputName)
                    .put("out", it.outputName)
                    .put("uri", it.outputUri.toString())
                    .put("mime", it.mimeType)
                    .put("inSize", it.inputSize)
                    .put("outSize", it.outputSize)
                    .put("time", it.timestamp)
            )
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    @Synchronized
    fun remove(context: Context, timestamp: Long) {
        save(context, load(context).filterNot { it.timestamp == timestamp })
    }

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
