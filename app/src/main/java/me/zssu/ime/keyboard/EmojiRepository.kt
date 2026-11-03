package me.zssu.ime.keyboard

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Small, local-only usage store for the emoji/kaomoji palette. */
class EmojiRepository(context: Context) {
    private val application = context.applicationContext
    private val prefs = application.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private val json = Json

    /** Bundled data works offline; a monthly background check refreshes the Unicode list. */
    fun unicodeEmoji(): Map<String, List<String>> {
        val result = synchronized(CACHE_LOCK) { unicodeCache ?: loadUnicode().also { unicodeCache = it } }
        refreshUnicodeIfDue()
        return result
    }

    private fun parseUnicode(reader: java.io.BufferedReader): Map<String, List<String>> {
        val groups = linkedMapOf<String, MutableList<String>>()
        val paint = android.graphics.Paint()
        var group = ""
        reader.useLines { lines ->
            lines.forEach { line ->
                if (line.startsWith("# group: ")) group = line.substringAfter(": ").trim()
                else if ("; fully-qualified" in line && group.isNotEmpty()) {
                    val points = line.substringBefore(';').trim().split(' ').filter { it.isNotEmpty() }
                    val value = runCatching { String(points.map { it.toInt(16) }.toIntArray(), 0, points.size) }.getOrNull()
                    if (value != null && paint.hasGlyph(value)) groups.getOrPut(group) { mutableListOf() }.add(value)
                }
            }
        }
        return groups
    }

    private fun loadUnicode(): Map<String, List<String>> {
        val cached = File(application.filesDir, "unicode-emoji-test.txt")
        if (cached.isFile) runCatching { parseUnicode(cached.bufferedReader()) }.getOrNull()?.let {
            if (it.isNotEmpty()) return it
        }
        return parseUnicode(application.assets.open("unicode/emoji-test.txt").bufferedReader())
    }

    private fun refreshUnicodeIfDue() {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("unicode_update_attempt", 0L) < 30L * 24 * 60 * 60 * 1000) return
        if (!UPDATING.compareAndSet(false, true)) return
        prefs.edit().putLong("unicode_update_attempt", now).apply()
        Thread({
            val temp = File(application.filesDir, "unicode-emoji-test.tmp")
            try {
                val connection = URL("https://www.unicode.org/Public/emoji/latest/emoji-test.txt").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 15000; connection.readTimeout = 30000
                    check(connection.responseCode == HttpURLConnection.HTTP_OK)
                    connection.inputStream.use { input -> temp.outputStream().use { output ->
                        val buffer = ByteArray(16384); var total = 0
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            total += n; check(total <= 2 * 1024 * 1024)
                            output.write(buffer, 0, n)
                        }
                    } }
                    check(temp.bufferedReader().use { it.readLine() } == "# emoji-test.txt")
                    val parsed = parseUnicode(temp.bufferedReader())
                    check(parsed.values.sumOf { it.size } >= 100)
                    check(temp.renameTo(File(application.filesDir, "unicode-emoji-test.txt")))
                    synchronized(CACHE_LOCK) { unicodeCache = parsed }
                } finally { connection.disconnect() }
            } catch (_: Exception) { temp.delete() }
            finally { UPDATING.set(false) }
        }, "unicode-emoji-update").apply { isDaemon = true; start() }
    }

    fun recents(): List<String> = read(KEY_RECENTS)
    fun favorites(): List<String> = read(KEY_FAVORITES)

    fun recordUsed(value: String) {
        write(KEY_RECENTS, (listOf(value) + recents().filterNot { it == value }).take(MAX_RECENTS))
    }

    fun toggleFavorite(value: String): Boolean {
        val old = favorites()
        val added = value !in old
        write(KEY_FAVORITES, if (added) old + value else old.filterNot { it == value })
        return added
    }

    private fun read(key: String): List<String> = runCatching {
        json.decodeFromString(ListSerializer(String.serializer()), prefs.getString(key, "[]")!!)
    }.getOrDefault(emptyList())

    private fun write(key: String, values: List<String>) {
        prefs.edit().putString(
            key,
            json.encodeToString(ListSerializer(String.serializer()), values),
        ).apply()
    }

    companion object {
        private val CACHE_LOCK = Any()
        private val UPDATING = AtomicBoolean(false)
        private var unicodeCache: Map<String, List<String>>? = null
        private const val NAME = "emoji_palette"
        private const val KEY_RECENTS = "recents"
        private const val KEY_FAVORITES = "favorites"
        private const val MAX_RECENTS = 30

        val EMOJI = listOf(
            "😀", "😂", "🥰", "😊", "😭", "😢", "😎", "🤔", "🫡", "🥳",
            "👍", "👎", "👏", "🙏", "💪", "👌", "✌️", "👀", "❤️", "💛",
            "✨", "🔥", "🎉", "✅", "❌", "⚠️", "💡", "📌", "🚀", "🌿",
        )
        val KAOMOJI = listOf(
            "(^^)", "(^_^)", "(*´ω｀*)", "(・∀・)", "(｀・ω・´)", "(´・ω・｀)",
            "＼(^o^)／", "m(_ _)m", "(>_<)", "(T_T)", "(￣▽￣)", "(；´Д｀)",
            "Σ(ﾟДﾟ)", "ｷﾀ━━━━(ﾟ∀ﾟ)━━━━!!", "ﾉｼ", "乙", "w", "草",
            "(｡•̀ᴗ-)✧", "(*^▽^*)", "(≧▽≦)", "(⌒▽⌒)", "(*´∀｀)", "(๑˃̵ᴗ˂̵)و",
            "(〃▽〃)", "(⁎˃ᴗ˂⁎)", "(๑•̀ㅂ•́)و✧", "(ง •̀_•́)ง", "(o・ω・o)", "(｡･ω･｡)",
            "(；ω；)", "(´；ω；｀)", "(╥﹏╥)", "( ; ω ; )", "(｡ŏ﹏ŏ)", "(￣^￣)",
            "(；一_一)", "(ﾟωﾟ)", "(°Д°)", "(⊙_⊙)", "(・・?)", "(・_・;)",
            "(つω`*)", "( ˘ω˘ )", "( ˘︶˘ ).｡.:*♡", "(づ｡◕‿‿◕｡)づ", "(っ´ω`)っ",
            "ヾ(≧▽≦*)o", "٩(๑❛ᴗ❛๑)۶", "ヽ(・∀・)ﾉ", "(ﾉ◕ヮ◕)ﾉ*:･ﾟ✧", "ฅ^•ﻌ•^ฅ",
            "(=^･ω･^=)", "U・ᴥ・U", "ʕ•ᴥ•ʔ", "(ᵔᴥᵔ)", "(・◇・)", "( ˙꒳˙ )",
            "(｀･ω･´)ゞ", "(￣^￣)ゞ", "(人´∀｀)", "(ㅅ´ ˘ `)", "(*ᴗ͈ˬᴗ͈)ꕤ*.ﾟ", "(｡-人-｡)",
        )
    }
}
