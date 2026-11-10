package me.zssu.ime.keyboard

import android.content.Context
import android.util.Log
import me.zssu.ime.theme.KeyboardTheme
import me.zssu.ime.theme.MaterialYouTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Loads layouts and themes, preferring user-supplied files over the bundled defaults.
 *
 * Lookup order for both kinds of resource is user directory first, then assets. That single rule is
 * what makes the app customizable: saving `layouts/flick_kana.json` in the app's files directory
 * overrides the shipped layout of the same id, and deleting it restores the default. Both the
 * visual editor and JSON studio are writers for these same files.
 */
class LayoutRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val layoutsDir = File(context.filesDir, LAYOUTS_DIR)
    private val themesDir = File(context.filesDir, THEMES_DIR)

    fun loadLayout(id: String): KeyboardLayout? =
        readOverridable("$LAYOUTS_DIR/$id.json", layoutsDir, "$id.json")
            ?.let { decode(it, "layout $id") { text -> json.decodeFromString<KeyboardLayout>(text) } }

    fun loadTheme(id: String): KeyboardTheme? =
        readOverridable("$THEMES_DIR/$id.json", themesDir, "$id.json")
            ?.let { decode(it, "theme $id") { text -> json.decodeFromString<KeyboardTheme>(text) } }

    /** Ids of every layout available, user overrides and bundled defaults merged. */
    fun availableLayoutIds(): List<String> = availableIds(LAYOUTS_DIR, layoutsDir)

    /**
     * Bundled and user themes, plus the dynamic Material You theme. That one has no file behind it
     * — it is derived from the system palette at runtime — so it has to be named explicitly or the
     * settings screen would not list the theme that is actually in use.
     */
    fun availableThemeIds(): List<String> =
        (listOf(MaterialYouTheme.ID) + availableIds(THEMES_DIR, themesDir)).distinct()

    fun saveLayout(layout: KeyboardLayout) {
        require(ID_PATTERN.matches(layout.id)) { "配列IDは英数字・ハイフン・下線で1〜64文字です" }
        validate(layout)
        layoutsDir.mkdirs()
        File(layoutsDir, "${layout.id}.json")
            .writeText(json.encodeToString(KeyboardLayout.serializer(), layout))
    }

    fun saveTheme(theme: KeyboardTheme) {
        require(ID_PATTERN.matches(theme.id)) { "テーマIDは英数字・ハイフン・下線で1〜64文字です" }
        validate(theme)
        themesDir.mkdirs()
        File(themesDir, "${theme.id}.json")
            .writeText(json.encodeToString(KeyboardTheme.serializer(), theme))
    }

    sealed interface ImportedResource {
        val id: String
        data class Layout(override val id: String) : ImportedResource
        data class Theme(override val id: String) : ImportedResource
    }

    /**
     * Validates before touching disk. IDs are deliberately filename-safe: imported JSON must never
     * be able to escape the app-owned resource directories.
     */
    fun importJson(text: String, expectTheme: Boolean? = null): Result<ImportedResource> = runCatching {
        require(text.toByteArray().size <= MAX_JSON_BYTES) { "JSON は 1 MB 以下にしてください" }
        val root = json.parseToJsonElement(text).jsonObject
        val id = root["id"]?.jsonPrimitive?.content.orEmpty()
        require(ID_PATTERN.matches(id)) {
            "id は英数字・ハイフン・アンダースコア（1〜64文字）で指定してください"
        }
        if (expectTheme != null) require(expectTheme == ("rows" !in root)) {
            if (expectTheme) "テーマのJSONを選んでください" else "配列のJSONを選んでください"
        }
        if ("rows" in root) {
            val value = json.decodeFromString<KeyboardLayout>(text)
            saveLayout(value)
            ImportedResource.Layout(value.id)
        } else {
            val value = json.decodeFromString<KeyboardTheme>(text)
            saveTheme(value)
            ImportedResource.Theme(value.id)
        }
    }

    fun exportLayout(id: String): String? =
        loadLayout(id)?.let { json.encodeToString(KeyboardLayout.serializer(), it) }

    fun exportTheme(id: String): String? =
        if (id == MaterialYouTheme.ID) null
        else loadTheme(id)?.let { json.encodeToString(KeyboardTheme.serializer(), it) }

    fun hasUserOverride(directory: String, id: String): Boolean {
        val dir = if (directory == LAYOUTS_DIR) layoutsDir else themesDir
        return File(dir, "$id.json").isFile
    }

    private fun validate(layout: KeyboardLayout) {
        require(layout.rows.isNotEmpty() && layout.rows.size <= 12) { "行数は 1〜12 行にしてください" }
        require(layout.rows.all { it.keys.isNotEmpty() && it.keys.size <= 20 }) {
            "各行のキー数は 1〜20 個にしてください"
        }
        require(layout.rows.all { it.weight in 0.1f..10f && it.padStart in 0f..20f && it.padEnd in 0f..20f }) {
            "行の高さ・余白が範囲外です"
        }
        require(layout.rows.all { row -> row.keys.all { key ->
            key.appearance?.let { look ->
                (look.labelSizeSp == null || look.labelSizeSp in 10f..32f) &&
                    (look.imageId == null || me.zssu.ime.theme.LocalArt.validId(look.imageId))
            } ?: true
        } }) { "キーの文字サイズまたは画像IDが範囲外です" }
        require(layout.rows.flatMap { it.keys }.all { it.weight > 0f && it.weight <= 10f }) {
            "キー幅は 0 より大きく 10 以下にしてください"
        }
    }

    private fun validate(theme: KeyboardTheme) {
        require(theme.keyHeightDp in 32f..96f) { "キー高さは 32〜96dp にしてください" }
        require(theme.keyGapDp in 0f..16f && theme.keyCornerRadiusDp in 0f..32f) {
            "キー間隔または角丸が範囲外です"
        }
        require(theme.lineWidthDp in 0.5f..4f && theme.keyOpacity in 0f..1f && theme.wallpaperOpacity in 0f..1f && theme.keySoundVolume in 0f..1f) {
            "線幅または濃さが範囲外です"
        }
        require(theme.keywordEffects.size <= 8 && theme.keywordEffects.all {
            it.keyword.isNotBlank() && it.keyword.length <= 32 && (it.texture != me.zssu.ime.theme.KeyTexture.NONE || it.imageId != null) &&
                (it.imageId == null || me.zssu.ime.theme.LocalArt.validId(it.imageId))
        }) { "入力エフェクトは8件、キーワードは32文字までです" }
        require(theme.labelSizeSp in 10f..32f) { "文字サイズは 10〜32sp にしてください" }
    }

    /** Removes a user override, falling back to the bundled version if one exists. */
    fun deleteOverride(directory: String, id: String): Boolean {
        val dir = if (directory == LAYOUTS_DIR) layoutsDir else themesDir
        return File(dir, "$id.json").delete()
    }

    private fun availableIds(assetDir: String, userDir: File): List<String> {
        val fromAssets = runCatching { context.assets.list(assetDir)?.toList() }.getOrNull().orEmpty()
        val fromUser = userDir.listFiles()?.map { it.name }.orEmpty()
        return (fromAssets + fromUser)
            .filter { it.endsWith(".json") }
            .map { it.removeSuffix(".json") }
            .distinct()
            .sorted()
    }

    private fun readOverridable(assetPath: String, userDir: File, fileName: String): String? {
        val userFile = File(userDir, fileName)
        if (userFile.isFile) {
            runCatching { return userFile.readText() }
                .onFailure { Log.w(TAG, "unreadable override $userFile, falling back to asset", it) }
        }
        return runCatching {
            context.assets.open(assetPath).bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    private fun <T> decode(text: String, what: String, block: (String) -> T): T? =
        runCatching { block(text) }
            .onFailure { Log.e(TAG, "failed to parse $what", it) }
            .getOrNull()

    companion object {
        private const val TAG = "LayoutRepository"
        const val LAYOUTS_DIR = "layouts"
        const val THEMES_DIR = "themes"
        const val DEFAULT_LAYOUT_ID = "flick_kana"
        private const val MAX_JSON_BYTES = 1024 * 1024
        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
