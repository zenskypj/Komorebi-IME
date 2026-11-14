package me.zssu.ime.settings

import android.content.Context
import android.content.res.Configuration
import java.util.Calendar

internal class ThemeSchedule(context: Context) {
    private val prefs = context.getSharedPreferences(ImeSettings.NAME, Context.MODE_PRIVATE)

    enum class Mode(val title: String) { OFF("自動切替なし"), DARK("端末のダークモード"), HOURS("設定した時間帯") }

    var mode: Mode
        get() = Mode.entries.firstOrNull { it.name == prefs.getString("theme_schedule_mode", null) } ?: Mode.OFF
        set(value) { prefs.edit().putString("theme_schedule_mode", value.name).apply() }
    var themeId: String?
        get() = prefs.getString("theme_schedule_id", null)
        set(value) { prefs.edit().putString("theme_schedule_id", value).apply() }
    var start: Int
        get() = prefs.getInt("theme_schedule_start", 19)
        set(value) { prefs.edit().putInt("theme_schedule_start", value).apply() }
    var end: Int
        get() = prefs.getInt("theme_schedule_end", 6)
        set(value) { prefs.edit().putInt("theme_schedule_end", value).apply() }

    fun effectiveId(config: Configuration, profile: AppProfileStore.Profile?, fallback: String): String {
        val match = when (mode) {
            Mode.OFF -> false
            Mode.DARK -> config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            Mode.HOURS -> includesHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY), start, end)
        }
        return profile?.themeId ?: themeId?.takeIf { match } ?: fallback
    }

    companion object {
        fun includesHour(hour: Int, start: Int, end: Int): Boolean = when {
            start == end -> false
            start < end -> hour in start until end
            else -> hour >= start || hour < end
        }
    }
}
