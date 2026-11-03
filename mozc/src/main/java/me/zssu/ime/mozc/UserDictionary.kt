package me.zssu.ime.mozc

import android.content.Context
import android.util.Log
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Input
import org.mozc.android.inputmethod.japanese.protobuf.ProtoUserDictionaryStorage.UserDictionaryImportData
import java.io.File

/**
 * The user's own words, editable one at a time.
 *
 * This version of mozc no longer exposes a per-entry protocol — `SEND_USER_DICTIONARY_COMMAND` is
 * reserved and the only way in is `IMPORT_USER_DICTIONARY`, which replaces a whole dictionary by
 * name. So we keep the authoritative list here and re-import the lot after every edit. A personal
 * dictionary is small enough that rewriting it costs nothing, and the whole-dictionary replacement
 * means add, edit and delete are all the same operation.
 *
 * Entries live in the exact TSV mozc consumes, which makes the file itself importable into desktop
 * Mozc or Google Japanese Input without conversion.
 */
class UserDictionary(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)

    /** Decrypted contents, or "" when there is nothing readable. */
    private fun contents(): String = SecureStore.read(file).orEmpty()

    data class Entry(
        val reading: String,
        val word: String,
        val pos: String = DEFAULT_POS,
        val comment: String = "",
    )

    fun entries(): List<Entry> {
        return runCatching {
            contents().lines().mapNotNull { line ->
                if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
                val f = line.split('\t')
                if (f.size < 2 || f[0].isEmpty() || f[1].isEmpty()) return@mapNotNull null
                Entry(f[0], f[1], f.getOrNull(2)?.ifEmpty { DEFAULT_POS } ?: DEFAULT_POS,
                    f.getOrNull(3).orEmpty())
            }
        }.onFailure { Log.e(TAG, "cannot read $FILE_NAME", it) }.getOrDefault(emptyList())
    }

    /** Appends [entry], or replaces the one at [replacing] when editing. */
    fun save(entry: Entry, replacing: Entry? = null): List<Entry> {
        val sanitized = entry.sanitized() ?: return entries()
        val current = entries().toMutableList()
        val index = replacing?.let { current.indexOf(it) } ?: -1
        if (index >= 0) current[index] = sanitized else current += sanitized
        return write(current)
    }

    fun delete(entry: Entry): List<Entry> = write(entries() - entry)

    /** Plain Mozc-compatible TSV for an explicit SAF export. Decryption never leaves this call. */
    fun exportTsv(): String = contents()

    /**
     * Replaces the dictionary from Mozc / Google Japanese Input compatible TSV.
     * Parsing and size checks finish before the encrypted authoritative file is touched.
     */
    fun importTsv(tsv: String, syncEngine: Boolean = true): Result<List<Entry>> = runCatching {
        require(tsv.toByteArray().size <= MAX_IMPORT_BYTES) { "辞書は 5 MB 以下にしてください" }
        var skippedPos = 0
        val parsed = tsv.lines().mapNotNull { line ->
            if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
            val fields = line.split('\t')
            require(fields.size >= 2) { "TSVとして読めない行があります" }
            val pos = fields.getOrNull(2)?.ifBlank { DEFAULT_POS } ?: DEFAULT_POS
            if (pos !in POS_TYPES) {
                skippedPos++
                return@mapNotNull null
            }
            Entry(
                fields[0],
                fields[1],
                pos,
                fields.getOrNull(3).orEmpty(),
            ).sanitized()
        }
        require(parsed.isNotEmpty()) { "有効な単語がありません" }
        if (skippedPos > 0) {
            Log.w(TAG, "skipped $skippedPos row(s) with unrecognized part-of-speech")
        }
        write(parsed.distinctBy { it.reading to it.word }, syncEngine)
    }

    internal fun restoreTsv(tsv: String) {
        if (tsv.isBlank()) write(emptyList(), syncEngine = false)
        else importTsv(tsv, syncEngine = false).getOrThrow()
    }

    private fun write(entries: List<Entry>, syncEngine: Boolean = true): List<Entry> {
        SecureStore.write(
            file,
            entries.joinToString("\n") { "${it.reading}\t${it.word}\t${it.pos}\t${it.comment}" },
        )
        val preferences = appContext.getSharedPreferences("ime_settings", Context.MODE_PRIVATE)
        preferences.edit().putLong(REVISION_KEY, preferences.getLong(REVISION_KEY, 0) + 1).apply()
        if (syncEngine) sync()
        return entries
    }

    /**
     * Pushes the current list into mozc.
     *
     * An empty payload makes mozc drop the dictionary entirely, which is what should happen when
     * the last entry goes. Called after every edit and once at startup, so a profile wiped by
     * "clear app data" refills itself.
     */
    fun sync() {
        sync(MozcEngine.get(appContext) ?: return)
    }

    /** Overload for callers that already hold the engine, e.g. its own start-up path. */
    fun sync(engine: MozcEngine) {
        val tsv = contents()
        val output = engine.eval(
            Input.newBuilder()
                .setType(Input.CommandType.IMPORT_USER_DICTIONARY)
                .setUserDictionaryImportData(
                    UserDictionaryImportData.newBuilder()
                        .setDictionaryName(DICTIONARY_NAME)
                        .setData(tsv)
                )
        )
        if (output == null) Log.e(TAG, "IMPORT_USER_DICTIONARY failed for $DICTIONARY_NAME")
    }

    /**
     * TSV has no escapes, so a tab or newline in a field would silently shift every later column.
     * Strip them rather than rejecting the edit — the user meant the text, not the whitespace.
     */
    private fun Entry.sanitized(): Entry? {
        fun clean(s: String) = s.replace(Regex("[\\t\\r\\n]"), " ").trim()
        val r = clean(reading)
        val w = clean(word)
        if (r.isEmpty() || w.isEmpty()) return null
        return Entry(r, w, clean(pos).ifEmpty { DEFAULT_POS }, clean(comment))
    }

    companion object {
        private const val TAG = "UserDictionary"
        private const val FILE_NAME = "user_dictionary.enc"
        private const val MAX_IMPORT_BYTES = 5 * 1024 * 1024

        /** Shown in mozc's dictionary list, and kept distinct from the bundled dictionaries. */
        const val REVISION_KEY = "user_dictionary_revision"

        const val DICTIONARY_NAME = "ユーザー辞書"

        const val DEFAULT_POS = "名詞"

        /**
         * Part-of-speech names mozc accepts in TSV, in the order its own proto declares them —
         * the common ones come first, so the picker needs no separate curation.
         *
         * Source: UserDictionary.PosType in protocol/user_dictionary_storage.proto.
         */
        val POS_TYPES = listOf(
            "名詞", "短縮よみ", "サジェストのみ", "固有名詞", "人名", "姓", "名",
            "組織", "地名", "名詞サ変", "名詞形動", "数", "アルファベット", "記号", "顔文字",
            "副詞", "連体詞", "接続詞", "感動詞",
            "接頭語", "助数詞", "接尾一般", "接尾人名", "接尾地名",
            "動詞ワ行五段", "動詞カ行五段", "動詞サ行五段", "動詞タ行五段", "動詞ナ行五段",
            "動詞マ行五段", "動詞ラ行五段", "動詞ガ行五段", "動詞バ行五段", "動詞ハ行四段",
            "動詞一段", "動詞カ変", "動詞サ変", "動詞ザ変", "動詞ラ変",
            "形容詞", "終助詞", "句読点", "独立語", "抑制単語", "品詞なし",
        )
    }
}
