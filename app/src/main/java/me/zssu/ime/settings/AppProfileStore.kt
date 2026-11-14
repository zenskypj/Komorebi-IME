package me.zssu.ime.settings

import android.content.Context
import me.zssu.ime.keyboard.KeyboardStyle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Per-application keyboard overrides.
 *
 * Android intentionally limits installed-app discovery. The IME does not request broader package
 * visibility; instead it remembers package names received in EditorInfo as the user actually types.
 */
class AppProfileStore(context: Context) {
    @Serializable
    data class Profile(
        val packageName: String,
        val label: String = "",
        val keyboardStyle: String = KeyboardStyle.FLICK.name,
        val oneHandMode: String = ImeSettings.OneHandMode.OFF.name,
        val keyHeightScale: Float = 1f,
        val incognito: Boolean = false,
        val neuralEnabled: Boolean = true,
        val typingCorrection: Boolean = true,
        val themeId: String? = null,
    ) {
        val resolvedKeyboardStyle: KeyboardStyle get() = KeyboardStyle.of(keyboardStyle)
        val resolvedOneHandMode: ImeSettings.OneHandMode get() = runCatching {
            ImeSettings.OneHandMode.valueOf(oneHandMode)
        }.getOrDefault(ImeSettings.OneHandMode.OFF)
    }

    @Serializable
    private data class Store(
        val revision: Int = 0,
        val seenPackages: List<String> = emptyList(),
        val profiles: List<Profile> = emptyList(),
    )

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var cached: Store? = null

    @Synchronized
    fun profiles(): List<Profile> = read().profiles.sortedBy { it.packageName }

    @Synchronized
    fun profileFor(packageName: String?): Profile? =
        packageName?.let { name -> read().profiles.firstOrNull { it.packageName == name } }

    @Synchronized
    fun seenPackages(): List<String> = read().seenPackages.sorted()

    @Synchronized
    fun recordSeenPackage(packageName: String?) {
        val name = packageName?.takeIf(::validPackageName) ?: return
        val current = read()
        if (name in current.seenPackages) return
        write(current.copy(seenPackages = (current.seenPackages + name).takeLast(MAX_SEEN)))
    }

    @Synchronized
    fun save(profile: Profile) {
        require(validPackageName(profile.packageName)) { "正しいパッケージ名を入力してください" }
        val normalized = profile.copy(
            label = profile.label.take(80),
            themeId = profile.themeId?.takeIf { Regex("[A-Za-z0-9_-]{1,64}").matches(it) },
            keyHeightScale = profile.keyHeightScale.coerceIn(
                ImeSettings.MIN_KEY_HEIGHT_SCALE,
                ImeSettings.MAX_KEY_HEIGHT_SCALE,
            ),
        )
        val current = read()
        write(
            current.copy(
                revision = current.revision + 1,
                seenPackages = (current.seenPackages + normalized.packageName).distinct()
                    .takeLast(MAX_SEEN),
                profiles = current.profiles.filterNot {
                    it.packageName == normalized.packageName
                } + normalized,
            )
        )
    }

    @Synchronized
    fun delete(packageName: String): Boolean {
        val current = read()
        val next = current.profiles.filterNot { it.packageName == packageName }
        if (next.size == current.profiles.size) return false
        write(current.copy(revision = current.revision + 1, profiles = next))
        return true
    }

    val revision: Int @Synchronized get() = read().revision

    private fun read(): Store {
        cached?.let { return it }
        if (!file.isFile) return Store()
        val store =
            runCatching { json.decodeFromString<Store>(file.readText()) }.getOrDefault(Store())
        cached = store
        return store
    }

    private fun write(value: Store) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(Store.serializer(), value))
        tmp.renameTo(file)
        cached = value
    }

    companion object {
        private const val FILE_NAME = "app_profiles.json"
        private const val MAX_SEEN = 100
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
        fun validPackageName(value: String): Boolean = PACKAGE_NAME.matches(value)
    }
}
