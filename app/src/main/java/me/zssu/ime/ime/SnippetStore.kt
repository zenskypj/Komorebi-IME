package me.zssu.ime.ime

import android.content.Context
import me.zssu.ime.mozc.SecureStore
import org.json.JSONArray
import java.io.File

class SnippetStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "snippets.enc")
    fun entries(): List<String> = runCatching {
        val array = JSONArray(SecureStore.read(file) ?: "[]")
        (0 until array.length()).map { array.getString(it) }.take(100)
    }.getOrDefault(emptyList())
    fun save(values: List<String>): Boolean = SecureStore.writeBytes(
        file, JSONArray(values.map { it.trim().take(2000) }.filter { it.isNotEmpty() }.distinct().take(100)).toString().toByteArray(),
    )
}
