package me.zssu.ime.ime

import android.content.Context

/** Aggregate timings only: no typed text, application names, or model prompts. */
internal object InputPerformance {
    fun record(context: Context, kind: String, nanos: Long) {
        val prefs = context.getSharedPreferences("input_performance", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) return
        val micros = (nanos / 1000).coerceAtLeast(0)
        synchronized(this) {
            prefs.edit().putLong(kind + "_count", prefs.getLong(kind + "_count", 0) + 1)
                .putLong(kind + "_total_us", prefs.getLong(kind + "_total_us", 0) + micros)
                .putLong(kind + "_max_us", maxOf(prefs.getLong(kind + "_max_us", 0), micros)).apply()
        }
    }
}
