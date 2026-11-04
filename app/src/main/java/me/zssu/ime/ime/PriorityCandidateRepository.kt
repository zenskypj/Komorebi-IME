package me.zssu.ime.ime

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.zssu.ime.mozc.SecureStore
import java.io.File

/**
 * Small encrypted store for candidates the user explicitly wants at the front of the strip.
 *
 * These entries do not bypass Mozc: they only re-rank a candidate Mozc actually returned, so the
 * normal candidate id and submit path remain authoritative.
 */
class PriorityCandidateRepository(context: Context) {
    @Serializable
    data class Entry(val reading: String, val value: String)

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var cached: List<Entry>? = null

    @Volatile
    private var indexByValue: Map<String, List<Entry>>? = null

    @Synchronized
    fun invalidate() { cached = null; indexByValue = null }

    @Synchronized
    fun entries(): List<Entry> = cached ?: read().also { cached = it }

    @Synchronized
    fun pin(reading: String, value: String): Boolean {
        val normalized = normalize(reading, value) ?: return false
        val current = entries()
        if (normalized in current) return true
        val next = (current.filterNot { it.reading == normalized.reading && it.value == normalized.value } +
            normalized).takeLast(MAX_ENTRIES)
        if (!write(next)) return false
        cached = next
        indexByValue = null
        return true
    }

    @Synchronized
    fun unpin(reading: String, value: String): Boolean {
        val normalized = normalize(reading, value) ?: return false
        val current = entries()
        val next = current.filterNot {
            it.reading == normalized.reading && it.value == normalized.value
        }
        if (next.size == current.size) return false
        if (!write(next)) return false
        cached = next
        indexByValue = null
        return true
    }

    fun match(reading: String, value: String): PriorityMatch {
        val normalizedReading = ReadingSimilarity.normalize(reading)
        if (normalizedReading.isEmpty() || value.isEmpty()) return PriorityMatch.NONE
        val candidates = ensureIndex()[value] ?: return PriorityMatch.NONE
        var similar = false
        for (entry in candidates) {
            val pinnedReading = ReadingSimilarity.normalize(entry.reading)
            if (pinnedReading == normalizedReading) return PriorityMatch.EXACT
            if (ReadingSimilarity.isNear(pinnedReading, normalizedReading)) similar = true
        }
        return if (similar) PriorityMatch.SIMILAR else PriorityMatch.NONE
    }

    private fun ensureIndex(): Map<String, List<Entry>> {
        indexByValue?.let { return it }
        val idx = entries().groupBy { it.value }
        indexByValue = idx
        return idx
    }

    private fun read(): List<Entry> {
        val encoded = SecureStore.read(file) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(Entry.serializer()), encoded)
                .mapNotNull { normalize(it.reading, it.value) }
                .distinct()
                .takeLast(MAX_ENTRIES)
        }.getOrDefault(emptyList())
    }

    private fun write(entries: List<Entry>): Boolean =
        SecureStore.writeBytes(
            file,
            json.encodeToString(ListSerializer(Entry.serializer()), entries).toByteArray(),
        )

    private fun normalize(reading: String, value: String): Entry? {
        val cleanReading = ReadingSimilarity.normalize(reading).take(MAX_READING_LENGTH)
        val cleanValue = value.trim().take(MAX_VALUE_LENGTH)
        if (cleanReading.isEmpty() || cleanValue.isEmpty()) return null
        return Entry(cleanReading, cleanValue)
    }

    companion object {
        private const val FILE_NAME = "priority_candidates.enc"
        private const val MAX_ENTRIES = 200
        private const val MAX_READING_LENGTH = 80
        private const val MAX_VALUE_LENGTH = 200
    }
}

enum class PriorityMatch { NONE, SIMILAR, EXACT }

/** Conservative local similarity used only to recover an explicitly pinned value. */
internal object ReadingSimilarity {
    fun normalize(value: String): String = buildString(value.length) {
        java.text.Normalizer.normalize(value.trim(), java.text.Normalizer.Form.NFKC).forEach { ch ->
            append(
                when (ch) {
                    in '\u30a1'..'\u30f6' -> (ch.code - 0x60).toChar()
                    else -> ch
                }
            )
        }
    }

    fun isNear(expected: String, actual: String): Boolean {
        if (expected.length < 4 || actual.length < 4) return false
        val maxDistance = if (maxOf(expected.length, actual.length) >= 8) 2 else 1
        return damerauLevenshteinAtMost(expected, actual, maxDistance)
    }

    private fun damerauLevenshteinAtMost(a: String, b: String, limit: Int): Boolean {
        if (kotlin.math.abs(a.length - b.length) > limit) return false
        val previousPrevious = IntArray(b.length + 1)
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            var rowMinimum = current[0]
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    current[j] = minOf(current[j], previousPrevious[j - 2] + 1)
                }
                rowMinimum = minOf(rowMinimum, current[j])
            }
            if (rowMinimum > limit) return false
            previous.copyInto(previousPrevious)
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length] <= limit
    }
}
