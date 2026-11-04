package me.zssu.ime.mozc

import android.content.Context
import android.util.Log
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Input
import org.mozc.android.inputmethod.japanese.protobuf.ProtoUserDictionaryStorage.UserDictionaryImportData
import java.io.File

/**
 * Supplementary dictionaries shipped inside the APK and loaded into mozc's user dictionary.
 *
 * They go in as *user* dictionaries rather than being compiled into mozc.data. That keeps
 * third_party/mozc a pristine upstream checkout — building them into the system dictionary would
 * mean patching its bazel dictionary sources — and it lets a dictionary be refreshed by dropping in
 * a new asset instead of rebuilding 18 MB of native data.
 *
 * mozc does the heavy lifting: `IMPORT_USER_DICTIONARY` parses the TSV on its own thread, replaces
 * any dictionary of the same name wholesale, reloads it so entries take effect immediately, and
 * saves it into the user profile directory. So this only has to run once per install.
 */
object BundledDictionaries {

    private const val TAG = "BundledDictionaries"
    private const val ASSET_DIR = "dictionaries"
    private const val STAMP_FILE = "bundled_dictionaries.stamp"

    /** Dictionary name as it appears in mozc's user dictionary list, keyed by asset file name. */
    private val DICTIONARIES = mapOf(
        "katakana-english.txt" to "カタカナ語→英語",
        "proper-nouns.txt" to "有名固有名詞",
        "ai-tech-nouns.txt" to "AI・テクノロジー",
    )

    data class Status(val name: String, val entryCount: Int)

    /**
     * Imports anything not yet imported for this install.
     *
     * Blocking and slow — several MB of TSV crosses into native code — so callers must be off the
     * main thread. Returns what is now installed, or null if nothing needed doing.
     */
    fun importIfNeeded(context: Context, engine: MozcEngine): List<Status>? {
        val stamp = File(context.filesDir, STAMP_FILE)
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        }.getOrElse { return null }

        if (stamp.isFile && stamp.readText().startsWith("$version\n")) return null

        val system = runCatching { context.assets.open("system_dictionaries.txt").bufferedReader().use { it.readLines().toSet() } }.getOrDefault(emptySet())
        val imported = mutableListOf<Status>()
        for ((assetName, dictionaryName) in DICTIONARIES) {
            val tsv = runCatching {
                context.assets.open("$ASSET_DIR/$assetName").bufferedReader().use { it.readText() }
            }.onFailure { Log.e(TAG, "cannot read $assetName", it) }.getOrNull() ?: continue

            // Clear legacy user copies of words now compiled into mozc.data.
            // English lookup is provided by the app only for an exact reading. Bulk user
            // dictionary import gives these words a personal-word cost advantage, including
            // during typo correction. Empty imports also migrate existing installations.
            val systemDictionary = assetName in system || assetName == "katakana-english.txt"
            val output = engine.eval(
                Input.newBuilder()
                    .setType(Input.CommandType.IMPORT_USER_DICTIONARY)
                    .setUserDictionaryImportData(
                        UserDictionaryImportData.newBuilder()
                            .setDictionaryName(dictionaryName)
                            .setData(if (systemDictionary) "# migrated to system dictionary\n" else tsv)
                    )
            )
            if (output == null) {
                Log.e(TAG, "IMPORT_USER_DICTIONARY failed for $dictionaryName")
                continue
            }
            // Comment lines carry the source's metadata header, not entries.
            val entries = tsv.lineSequence().count { it.isNotBlank() && !it.startsWith("#") }
            Log.i(TAG, "imported $dictionaryName ($entries entries)")
            imported += Status(dictionaryName, entries)
        }

        if (imported.isEmpty()) return null
        stamp.writeText(buildString {
            append(version).append('\n')
            imported.forEach { append(it.name).append('\t').append(it.entryCount).append('\n') }
        })
        return imported
    }

    /** What the last successful import installed, for display in settings. */
    fun installed(context: Context): List<Status> {
        val stamp = File(context.filesDir, STAMP_FILE)
        if (!stamp.isFile) return emptyList()
        return runCatching {
            stamp.readLines().drop(1).mapNotNull { line ->
                val (name, count) = line.split('\t').takeIf { it.size == 2 } ?: return@mapNotNull null
                Status(name, count.toIntOrNull() ?: return@mapNotNull null)
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Enables or disables a single bundled dictionary by (re-)importing it or clearing it.
     *
     * When [active] is true, reads the asset TSV and pushes it into mozc. When false, pushes an
     * empty TSV so mozc drops the dictionary from its index. Call this when the user toggles a
     * dictionary in settings.
     */
    fun setDictionaryActive(context: Context, engine: MozcEngine, assetName: String, active: Boolean) {
        val dictionaryName = DICTIONARIES[assetName] ?: return
        val tsv = if (active) {
            runCatching {
                context.assets.open("$ASSET_DIR/$assetName").bufferedReader().use { it.readText() }
            }.getOrNull()
        } else {
            "# cleared\n"
        }
        if (tsv == null) return

        val output = engine.eval(
            Input.newBuilder()
                .setType(Input.CommandType.IMPORT_USER_DICTIONARY)
                .setUserDictionaryImportData(
                    UserDictionaryImportData.newBuilder()
                        .setDictionaryName(dictionaryName)
                        .setData(tsv)
                )
        )
        if (output == null) {
            Log.e(TAG, "setDictionaryActive failed for $dictionaryName (active=$active)")
        } else {
            Log.i(TAG, "setDictionaryActive $dictionaryName active=$active")
        }
    }
}
