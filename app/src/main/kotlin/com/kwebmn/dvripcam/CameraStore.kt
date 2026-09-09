package com.kwebmn.dvripcam

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Сохранённая камера. Пароль хранится как есть — это личный инструмент под свою камеру. */
data class CameraEntry(
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    val pass: String,
)

/** Простое хранилище списка камер в SharedPreferences (JSON-массив). */
class CameraStore(context: Context) {
    private val prefs = context.getSharedPreferences("cameras", Context.MODE_PRIVATE)

    fun list(): List<CameraEntry> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CameraEntry(
                    name = o.optString("name"),
                    host = o.optString("host"),
                    port = o.optInt("port", 34567),
                    user = o.optString("user", "admin"),
                    pass = o.optString("pass", ""),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Добавить/обновить по host:port. */
    fun save(entry: CameraEntry) {
        val cur = list().filterNot { it.host == entry.host && it.port == entry.port }
        write(cur + entry)
    }

    fun remove(host: String, port: Int) {
        write(list().filterNot { it.host == host && it.port == port })
    }

    private fun write(items: List<CameraEntry>) {
        val arr = JSONArray()
        for (e in items) {
            arr.put(
                JSONObject()
                    .put("name", e.name).put("host", e.host).put("port", e.port)
                    .put("user", e.user).put("pass", e.pass),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object { const val KEY = "list" }
}
