package me.zssu.ime.ime

import android.content.ClipboardManager

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

    private data class Entry(val text: String, val addedAt: Long, val pinned: Boolean = false)
    private val entries = ArrayDeque<Entry>()

    init {
        require(limit > 0) { "limit must be positive" }
    }

    fun items(retentionMillis: Long = Long.MAX_VALUE): List<String> {
        val currentTime = now()
        entries.removeAll { !it.pinned && currentTime - it.addedAt > retentionMillis }
        return entries.sortedByDescending { it.pinned }.map { it.text }
    }

    fun clear() = entries.clear()
    fun pinnedItems(): Set<String> = entries.filter { it.pinned }.mapTo(mutableSetOf()) { it.text }
    fun remove(text: String) { entries.removeAll { it.text == text } }
    fun togglePinned(text: String) {
        val index = entries.indexOfFirst { it.text == text }
        if (index >= 0) entries[index] = entries[index].let { it.copy(pinned = !it.pinned) }
    }

    fun record(clipboard: ClipboardManager?) {
        runCatching {
            val clip = clipboard?.primaryClip ?: return
            if (clip.description.extras?.getBoolean(EXTRA_IS_SENSITIVE, false) == true) return

            // Read only text explicitly supplied by the clipboard provider; never open URIs.
            val text = (0 until minOf(clip.itemCount, 20)).mapNotNull {
                clip.getItemAt(it).text?.take(16_000)?.toString()
            }.joinToString("\n").take(16_000)

            add(text)
        }
    }

    internal fun add(text: String) {
        if (text.isBlank()) return
        val previous = entries.firstOrNull { it.text == text }
        remove(text)
        entries.addFirst(Entry(text, now(), previous?.pinned == true))
        while (entries.size > limit) {
            val evict = entries.indexOfLast { !it.pinned }
            entries.removeAt(if (evict < 0) entries.lastIndex else evict)
        }
    }

    private companion object {
        const val DEFAULT_LIMIT = 20

        // ClipDescription.EXTRA_IS_SENSITIVE is API 33, but sources have used this stable key since
        // Android 12. The literal lets API 24–32 builds honour the flag without referencing a newer
        // framework field.
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}
