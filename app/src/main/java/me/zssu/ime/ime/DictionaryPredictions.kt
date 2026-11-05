package me.zssu.ime.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import me.zssu.ime.mozc.UserDictionary
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Prefix completion for bundled names/terms and personal registrations. No English dictionary. */
internal class DictionaryPredictions(context: Context, private val pins: () -> List<PriorityCandidateRepository.Entry>) {
    private data class Word(val reading: String, val value: String, val pinned: Boolean = false)
    private class Index(entries: List<Word>) {
        private val words = entries.sortedWith(compareBy<Word> { it.reading }.thenBy { it.value })
            .distinctBy { it.reading to it.value }

        fun find(prefix: String): List<Word> {
            var low = 0
            var high = words.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (words[middle].reading < prefix) low = middle + 1 else high = middle
            }
            val result = ArrayList<Word>()
            var inspected = 0
            while (low < words.size && inspected++ < 128) {
                val word = words[low++]
                if (!word.reading.startsWith(prefix)) break
                if (word.reading == prefix || prefix.length >= 2) result.add(word)
            }
            return result.sortedWith(compareBy<Word> { it.reading != prefix }
                .thenBy { !it.pinned }.thenBy { it.reading.length }).take(8)
        }
    }

    private val app = context.applicationContext
    private val ui = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dictionary-predictions").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    @Volatile private var closed = false
    @Volatile private var proper = Index(emptyList())
    @Volatile private var technology = Index(emptyList())
    private data class Personal(val index: Index, val suppressed: Set<Pair<String, String>>)
    @Volatile private var personal = Personal(Index(emptyList()), emptySet())
    private var publicLoaded = false

    fun refresh(onReady: () -> Unit = {}) {
        if (closed) return
        val ticket = generation.incrementAndGet()
        worker.execute {
            if (closed || ticket != generation.get()) return@execute
            try {
                if (!publicLoaded) {
                    proper = load("proper-nouns.txt")
                    technology = load("ai-tech-nouns.txt")
                    publicLoaded = true
                }
                val entries = UserDictionary(app).entries()
                val suppressed = entries.filter { it.pos == "抑制単語" || it.pos == "SUPPRESSION_WORD" }
                    .mapTo(hashSetOf()) { ReadingSimilarity.normalize(it.reading) to it.word }
                val registrations = entries.filterNot { it.pos == "抑制単語" || it.pos == "SUPPRESSION_WORD" }
                    .map { Word(ReadingSimilarity.normalize(it.reading), it.word) }
                val preferred = pins().map { Word(ReadingSimilarity.normalize(it.reading), it.value, pinned = true) }
                val index = Index(preferred + registrations)
                if (!closed && ticket == generation.get()) {
                    personal = Personal(index, suppressed)
                    ui.post { if (!closed && ticket == generation.get()) onReady() }
                }
            } catch (error: Exception) {
                android.util.Log.w("DictionaryPredictions", "Dictionary index unavailable", error)
            }
        }
    }

    private fun load(name: String): Index = app.assets.open("dictionaries/$name").bufferedReader().useLines { lines ->
        Index(lines.filterNot { it.startsWith("#") }.mapNotNull { line ->
            val columns = line.split('\t')
            if (columns.size < 2 || columns[0].isBlank() || columns[1].isBlank()) null
            else Word(ReadingSimilarity.normalize(columns[0]), columns[1])
        }.toList())
    }

    fun candidates(reading: String, useProper: Boolean, useTechnology: Boolean): List<MozcSession.Candidate> {
        val prefix = ReadingSimilarity.normalize(reading)
        if (prefix.isEmpty()) return emptyList()
        val own = personal
        val words = own.index.find(prefix) + (if (useProper) proper.find(prefix) else emptyList()) +
            (if (useTechnology) technology.find(prefix) else emptyList())
        return words.filterNot { it.reading to it.value in own.suppressed }
            .distinctBy { it.reading to it.value }.sortedWith(compareBy<Word> { it.reading != prefix }
            .thenBy { !it.pinned }.thenBy { it.reading.length }).take(12).mapIndexed { index, word ->
            MozcSession.Candidate(-50_000 - index, word.value, inputReading = reading,
                sourceReading = word.reading, prediction = word.reading != prefix,
                directCommit = true, dictionaryCompletion = true)
        }
    }

    fun close() { closed = true; generation.incrementAndGet(); worker.shutdownNow() }
}
