package me.zssu.ime.ime

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/** Immutable indexes loaded once off the input thread; never perform fuzzy English lookup. */
internal object SupplementaryLexicons {
    private val loading = AtomicBoolean()
    @Volatile private var english: Map<String, List<String>> = emptyMap()
    @Volatile private var canonical: Set<String> = emptySet()

    fun load(context: Context) {
        if (!loading.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread({
            try {
                val words = linkedMapOf<String, MutableList<String>>()
                app.assets.open("dictionaries/katakana-english.txt").bufferedReader().useLines { lines ->
                    lines.filterNot { it.startsWith("#") }.forEach { line ->
                        val fields = line.split('\t')
                        if (fields.size >= 2 && fields[0].isNotBlank() && fields[1].isNotBlank()) {
                            words.getOrPut(ReadingSimilarity.normalize(fields[0])) { mutableListOf() }.add(fields[1])
                        }
                    }
                }
                english = words.mapValues { it.value.distinct() }
                canonical = app.assets.open("orthographic-corrections.tsv").bufferedReader().useLines { lines ->
                    lines.filterNot { it.startsWith("#") }.filter { '\t' in it }.toHashSet()
                }
            } catch (error: Exception) {
                android.util.Log.w("SupplementaryLexicons", "Supplementary lookup unavailable", error)
            }
        }, "supplementary-lexicons").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    fun english(reading: String): List<String> = english[ReadingSimilarity.normalize(reading)].orEmpty()
    fun isCanonical(reading: String, value: String): Boolean =
        ReadingSimilarity.normalize(reading) + "\t" + value in canonical
}
