package me.zssu.ime.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import me.zssu.ime.keyboard.FlickGuideStyle
import me.zssu.ime.keyboard.KeyboardStyle
import java.io.File

/**
 * User-tunable appearance settings.
 *
 * Separate from [me.zssu.ime.theme.KeyboardTheme]: a theme is a shareable file describing a look,
 * while these are per-device choices layered on top of whichever theme is active. Pure black is
 * about this screen's panel, and a background photo belongs to this user — neither travels with a
 * theme someone hands you.
 *
 * Backed by SharedPreferences rather than DataStore because the IME reads them synchronously while
 * building its input view, on a path where suspending would mean rendering the keyboard twice.
 */
class ImeSettings(context: Context) {

    enum class OneHandMode { OFF, LEFT, RIGHT }
    enum class FlickInputMode { FLICK_ONLY, FLICK_AND_TOGGLE }
    enum class ConversionEngine { MOZC, KARUKAN }
    var zenzaiPlusEnabled: Boolean
        get() = prefs.getBoolean("zenzai_plus_enabled", false)
        set(value) { prefs.edit().putBoolean("zenzai_plus_enabled", value).apply(); bumpRevision() }
    var zenzaiMozcEnabled: Boolean
        get() = prefs.getBoolean("zenzai_mozc_enabled", false)
        set(value) { prefs.edit().putBoolean("zenzai_mozc_enabled", value).apply(); bumpRevision() }


    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private val backgroundDir = File(appContext.filesDir, BACKGROUND_DIR)

    /** Forces a true-black panel. On OLED those pixels are switched off entirely. */
    var pureBlack: Boolean
        get() = prefs.getBoolean(KEY_PURE_BLACK, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PURE_BLACK, value).apply()
            bumpRevision()
        }

    /** How strongly the background image shows through, 0f–1f. */
    var backgroundOpacity: Float
        get() = prefs.getFloat(KEY_BACKGROUND_OPACITY, DEFAULT_BACKGROUND_OPACITY)
        set(value) {
            prefs.edit().putFloat(KEY_BACKGROUND_OPACITY, value.coerceIn(0f, 1f)).apply()
            bumpRevision()
        }

    /**
     * Multiplier on the theme's key height.
     *
     * A scale rather than an absolute height so it composes with whatever theme is active instead
     * of overriding a value the theme author chose. The range stops well short of filling the
     * screen: past that the candidate strip and the editor stop sharing the display usefully.
     */
    var keyHeightScale: Float
        get() = prefs.getFloat(KEY_HEIGHT_SCALE, 1f)
        set(value) {
            prefs.edit()
                .putFloat(
                    KEY_HEIGHT_SCALE,
                    value.coerceIn(MIN_KEY_HEIGHT_SCALE, MAX_KEY_HEIGHT_SCALE),
                )
                .apply()
            bumpRevision()
        }

    /**
     * Flick, qwerty, or one of each. See [KeyboardStyle].
     *
     * Stored by name so adding a combination later does not invalidate what is on disk; an
     * unreadable value falls back to flick rather than leaving the user with no keyboard.
     */
    var keyboardStyle: KeyboardStyle
        get() = KeyboardStyle.of(prefs.getString(KEY_KEYBOARD_STYLE, null))
        set(value) {
            prefs.edit().putString(KEY_KEYBOARD_STYLE, value.name).apply()
            bumpRevision()
        }

    /**
     * Whether a centre tap is always a literal centre flick, or repeated taps cycle the key's row.
     *
     * Flick-only remains the default so fast repeated input never changes meaning unless the user
     * explicitly enables traditional mobile-phone input.
     */
    var flickInputMode: FlickInputMode
        get() = runCatching {
            FlickInputMode.valueOf(
                prefs.getString(KEY_FLICK_INPUT_MODE, FlickInputMode.FLICK_ONLY.name)!!
            )
        }.getOrDefault(FlickInputMode.FLICK_ONLY)
        set(value) {
            prefs.edit().putString(KEY_FLICK_INPUT_MODE, value.name).apply()
            bumpRevision()
        }

    /** Theme selected in the in-app studio. `material_you` remains the safe dynamic default. */
    var activeThemeId: String
        get() = prefs.getString(KEY_ACTIVE_THEME, DEFAULT_THEME_ID) ?: DEFAULT_THEME_ID
        set(value) {
            prefs.edit().putString(KEY_ACTIVE_THEME, value).apply()
            bumpRevision()
        }

    /**
     * Optional custom entry plane. Once opened, a layout's own SwitchLayout actions continue to
     * define its family, so completely new multi-plane keyboards can be shipped as JSON alone.
     */
    var activeLayoutId: String?
        get() = prefs.getString(KEY_ACTIVE_LAYOUT, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_ACTIVE_LAYOUT) else putString(KEY_ACTIVE_LAYOUT, value)
            }.apply()
            bumpRevision()
        }

    var oneHandMode: OneHandMode
        get() = runCatching {
            OneHandMode.valueOf(prefs.getString(KEY_ONE_HAND_MODE, OneHandMode.OFF.name)!!)
        }.getOrDefault(OneHandMode.OFF)
        set(value) {
            prefs.edit().putString(KEY_ONE_HAND_MODE, value.name).apply()
            bumpRevision()
        }

    /**
     * What a flick key shows while it is held. See [FlickGuideStyle].
     *
     * Defaults to the preview: the cross of four directions covers the neighbouring keys, which is
     * where the eye is looking, and most of the time only one of the four is wanted.
     */
    var flickGuideStyle: FlickGuideStyle
        get() = FlickGuideStyle.of(prefs.getString(KEY_FLICK_GUIDE, null))
        set(value) {
            prefs.edit().putString(KEY_FLICK_GUIDE, value.name).apply()
            bumpRevision()
        }

    /** Global key vibration switch, independent of the selected visual theme. */
    var hapticFeedbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC_FEEDBACK, true)
        set(value) {
            prefs.edit { putBoolean(KEY_HAPTIC_FEEDBACK, value) }
            bumpRevision()
        }

    /**
     * The keyboard background image, or null for a plain colour.
     *
     * A copy under our own files directory, not the content URI the user picked: the IME runs in a
     * process that never held the SAF grant, and the picked document can be deleted or unmounted
     * at any time. Copying makes the setting survive both.
     */
    val backgroundImage: File?
        get() = File(backgroundDir, BACKGROUND_FILE).takeIf { it.isFile }

    val backgroundPresetId: String?
        get() = prefs.getString(KEY_BACKGROUND_PRESET, null)

    /** Replaces the background image with [bytes]. Returns false on write failure. */
    fun setBackgroundImage(bytes: ByteArray): Boolean {
        backgroundDir.mkdirs()
        return runCatching {
            File(backgroundDir, BACKGROUND_FILE).writeBytes(bytes)
            prefs.edit().remove(KEY_BACKGROUND_PRESET).apply()
            bumpRevision()
            true
        }.getOrDefault(false)
    }

    fun setBackgroundPreset(id: String): Boolean {
        val preset = BACKGROUND_PRESETS.firstOrNull { it.id == id } ?: return false
        val bytes = runCatching {
            appContext.assets.open(preset.assetPath).use { it.readBytes() }
        }.getOrNull() ?: return false
        backgroundDir.mkdirs()
        return runCatching {
            File(backgroundDir, BACKGROUND_FILE).writeBytes(bytes)
            prefs.edit().putString(KEY_BACKGROUND_PRESET, id).apply()
            bumpRevision()
            true
        }.getOrDefault(false)
    }

    fun clearBackgroundImage() {
        File(backgroundDir, BACKGROUND_FILE).delete()
        prefs.edit().remove(KEY_BACKGROUND_PRESET).apply()
        bumpRevision()
    }

    /** Whether the bundled proper-noun dictionary is active. Defaults to true. */
    var useProperNounDictionary: Boolean
        get() = prefs.getBoolean(KEY_USE_PRONOUN_DICT, true)
        set(value) {
            prefs.edit().putBoolean(KEY_USE_PRONOUN_DICT, value).apply()
            bumpRevision()
        }

    /** Whether the bundled AI/Tech dictionary is active. Defaults to true. */
    var useAiTechDictionary: Boolean
        get() = prefs.getBoolean(KEY_USE_AI_TECH_DICT, true)
        set(value) {
            prefs.edit().putBoolean(KEY_USE_AI_TECH_DICT, value).apply()
            bumpRevision()
        }

    /**
     * Kana composition always retains Mozc's mature mobile tables. KARUKAN replaces explicit
     * kana-kanji conversion with local neural inference when its optional model is ready.
     */
    var conversionEngine: ConversionEngine
        get() = runCatching {
            ConversionEngine.valueOf(
                prefs.getString(KEY_CONVERSION_ENGINE, ConversionEngine.MOZC.name)!!
            )
        }.getOrDefault(ConversionEngine.MOZC)
        set(value) {
            prefs.edit().putString(KEY_CONVERSION_ENGINE, value.name).apply()
            bumpRevision()
        }

    /**
     * Changes whenever anything here does.
     *
     * The IME compares this against the value it built its current view from, so it can rebuild
     * exactly when something moved instead of on every input session.
     */
    val revision: Int
        get() {
            var h = prefs.getInt(KEY_REVISION, 0)
            h = h * 31 + pureBlack.hashCode()
            h = h * 31 + (backgroundOpacity * 100).toInt()
            h = h * 31 + (keyHeightScale * 100).toInt()
            return h
        }

    private fun bumpRevision() {
        prefs.edit().putInt(KEY_REVISION, prefs.getInt(KEY_REVISION, 0) + 1).apply()
    }

    companion object {
        const val NAME = "ime_settings"
        private const val KEY_PURE_BLACK = "pure_black"
        private const val KEY_BACKGROUND_OPACITY = "background_opacity"
        private const val KEY_REVISION = "revision"
        const val BACKGROUND_DIR = "backgrounds"
        private const val BACKGROUND_FILE = "keyboard_background"
        private const val KEY_HEIGHT_SCALE = "key_height_scale"
        private const val KEY_KEYBOARD_STYLE = "keyboard_style"
        private const val KEY_FLICK_INPUT_MODE = "flick_input_mode"
        private const val KEY_ACTIVE_THEME = "active_theme"
        private const val KEY_ACTIVE_LAYOUT = "active_layout"
        private const val KEY_ONE_HAND_MODE = "one_hand_mode"
        private const val KEY_FLICK_GUIDE = "flick_guide_style"
        private const val KEY_HAPTIC_FEEDBACK = "haptic_feedback"
        private const val KEY_BACKGROUND_PRESET = "background_preset"
        private const val KEY_USE_PRONOUN_DICT = "use_pronoun_dict"
        private const val KEY_USE_AI_TECH_DICT = "use_ai_tech_dict"
        private const val KEY_CONVERSION_ENGINE = "conversion_engine"
        const val DEFAULT_THEME_ID = "material_you"
        const val DEFAULT_BACKGROUND_OPACITY = 0.45f
        const val MIN_KEY_HEIGHT_SCALE = 0.7f
        const val MAX_KEY_HEIGHT_SCALE = 1.5f
        const val TOGGLE_TIMEOUT_MILLIS = 650

        val BACKGROUND_PRESETS = listOf(
            BackgroundPreset("cozy_friends", "森のともだち", "backgrounds/cozy_friends.webp"),
            BackgroundPreset(
                "crystal_wisteria",
                "水晶の藤",
                "backgrounds/crystal_wisteria.webp",
            ),
            BackgroundPreset("cyber_ink", "サイバー墨", "backgrounds/cyber_ink.webp"),
            BackgroundPreset(
                "moonlit_forest",
                "月夜のこもれび",
                "backgrounds/moonlit_forest.webp",
            ),
        )
    }
}

data class BackgroundPreset(val id: String, val label: String, val assetPath: String)
