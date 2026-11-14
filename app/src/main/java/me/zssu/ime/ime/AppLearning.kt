package me.zssu.ime.ime

import android.content.Context
import me.zssu.ime.mozc.SecureStore
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Opt-in supplemental ranking. Native Mozc and zenzai learning are managed separately. */
internal object AppLearning {
    const val FILE_NAME = "app_learning.enc"

    @Serializable
    data class Entry(val app: String, val reading: String, val value: String, val count: Int)
    @Volatile private var cache: List<Entry>? = null
    private val epoch = java.util.concurrent.atomic.AtomicInteger()
    fun generation(): Int = epoch.get()
    @Synchronized fun warm(context: Context) { entries(context) }
    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)
    private fun entries(context: Context): List<Entry> = cache ?: runCatching {
        SecureStore.read(file(context))?.let { Json.decodeFromString<List<Entry>>(it) }.orEmpty()
    }.getOrDefault(emptyList()).also { cache = it }

    @Synchronized
    fun apps(context: Context): List<String> = entries(context).map { it.app }.distinct().sorted()

    @Synchronized
    fun forget(context: Context, app: String?): Boolean {
        epoch.incrementAndGet()
        return store(context, entries(context).filterNot { app == null || it.app == app })
    }

    @Synchronized
    fun learn(context: Context, app: String, examples: List<ConfirmedLearning.Example>, generation: Int) {
        if (app.isBlank() || generation != epoch.get()) return
        var next = entries(context)
        val original = next
        for ((reading, value) in examples) {
            val normalized = ReadingSimilarity.normalize(reading)
            if (normalized.length !in 2..100 || value.length !in 1..160 ||
                ReadingSimilarity.normalize(value) == normalized) continue
            val previous = next.firstOrNull { it.app == app && it.reading == normalized && it.value == value }
            val entry = Entry(app, normalized, value, ((previous?.count ?: 0) + 1).coerceAtMost(12))
            next = (listOf(entry) + next.filterNot { it === previous }).take(400)
        }
        if (next !== original) store(context, next)
    }

    fun prioritize(app: String, reading: String, candidates: List<MozcSession.Candidate>): List<MozcSession.Candidate> {
        val normalized = ReadingSimilarity.normalize(reading)
        val known = cache.orEmpty().filter { it.app == app && it.reading == normalized && it.count >= 2 }
            .associate { it.value to it.count }
        if (known.isEmpty()) return candidates
        return candidates.map { candidate ->
            if (candidate.text in known && !candidate.correction && !candidate.prediction &&
                !EnglishCandidateOrder.isFallback(candidate)) candidate.copy(fromUserHistory = true, appLearned = true)
            else candidate
        }
    }

    private fun store(context: Context, next: List<Entry>): Boolean {
        return if (SecureStore.write(file(context), Json.encodeToString(next))) {
            cache = next
            true
        } else false
    }
}
