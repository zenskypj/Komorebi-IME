package me.zssu.ime.ime

import android.content.Context
import me.zssu.ime.mozc.SecureStore
import org.json.JSONArray
import java.io.File

class SnippetStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "snippets.enc")
    private val nameFile = File(context.applicationContext.filesDir, "snippet_name.enc")
    private val prefs = context.applicationContext.getSharedPreferences(me.zssu.ime.settings.ImeSettings.NAME, Context.MODE_PRIVATE)
    fun dateFormat(): String = prefs.getString("snippet_date_format", null)
        ?.takeIf(SnippetTemplate::validDateFormat) ?: "yyyy-MM-dd"
    fun saveDateFormat(pattern: String) { prefs.edit().putString("snippet_date_format", pattern).apply() }
    fun name(): String = runCatching { SecureStore.read(nameFile).orEmpty() }.getOrDefault("")
    fun saveName(value: String): Boolean = SecureStore.writeBytes(nameFile, value.trim().take(100).toByteArray())
    fun entries(): List<String> = runCatching {
        val array = JSONArray(SecureStore.read(file) ?: "[]")
        (0 until array.length()).map { array.getString(it) }.take(100)
    }.getOrDefault(emptyList())
    fun save(values: List<String>): Boolean = SecureStore.writeBytes(
        file, JSONArray(values.map { it.trim().take(2000) }.filter { it.isNotEmpty() }.distinct().take(100)).toString().toByteArray(),
    )
}
