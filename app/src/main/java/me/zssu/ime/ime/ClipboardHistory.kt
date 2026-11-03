package me.zssu.ime.ime

import android.content.ClipboardManager
import android.content.Context

/**
 * Process-local clipboard history.
 *
 * Nothing is persisted: Android destroys the entries with the IME process. Clips marked sensitive
 * by their source are deliberately ignored, and clipboard access failures are treated as an empty
 * clipboard because OEM clipboard providers are outside our control.
 */
internal class ClipboardHistory(
    private val limit: Int = DEFAULT_LIMIT,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private data class Entry(val text: String, val addedAt: Long)
    private val entries = ArrayDeque<Entry>()

    init {
        require(limit > 0) { "limit must be positive" }
    }

    fun items(retentionMillis: Long = Long.MAX_VALUE): List<String> {
        val currentTime = now()
        entries.removeAll { currentTime - it.addedAt > retentionMillis }
        return entries.map { it.text }
    }

    fun clear() = entries.clear()

    fun record(context: Context, clipboard: ClipboardManager?) {
        runCatching {
            val clip = clipboard?.primaryClip ?: return
            if (clip.description.extras?.getBoolean(EXTRA_IS_SENSITIVE, false) == true) return

            val text = buildList {
                for (index in 0 until clip.itemCount) {
                    val itemText = runCatching {
                        clip.getItemAt(index).coerceToText(context)?.toString()
                    }.getOrNull()
                    if (itemText != null) add(itemText)
                }
            }.joinToString("\n")

            add(text)
        }
    }

    internal fun add(text: String) {
        if (text.isBlank()) return
        entries.removeAll { it.text == text }
        entries.addFirst(Entry(text, now()))
        while (entries.size > limit) entries.removeLast()
    }

    private companion object {
        const val DEFAULT_LIMIT = 20

        // ClipDescription.EXTRA_IS_SENSITIVE is API 33, but sources have used this stable key since
        // Android 12. The literal lets API 24–32 builds honour the flag without referencing a newer
        // framework field.
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}
