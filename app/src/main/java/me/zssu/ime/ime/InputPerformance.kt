package me.zssu.ime.ime

import android.content.Context

/** Aggregate timings only: no typed text, application names, or model prompts. */
internal object InputPerformance {
    fun enabled(context: Context): Boolean =
        context.getSharedPreferences("input_performance", Context.MODE_PRIVATE).getBoolean("enabled", false)

    fun report(context: Context): String {
        val prefs = context.getSharedPreferences("input_performance", Context.MODE_PRIVATE)
        val settings = me.zssu.ime.settings.ImeSettings(context)
        return buildString {
            appendLine("Komorebi IME 性能診断 / Android API ${android.os.Build.VERSION.SDK_INT}")
            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            appendLine("アプリ版: $version / 機種: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("入力処理エラー: ${prefs.getLong("error_count", 0)} 回")
            appendLine("有効な変換設定: ${settings.conversionEngine}")
            appendLine("Karukan モデル: ${if (me.zssu.ime.karukan.KarukanModelStore.files(context).ready) "取得済み" else "未取得"}")
            appendLine("zenzai-plus: ${if (ZenzaiEngine.isBundled(context)) "利用可能" else "未搭載"}")
            val proofreading = ProofreadModelStore.state(context, ProofreadModelStore.selected(context))
            appendLine("AI 文章校正モデル: ${if (proofreading is ProofreadModelStore.State.Ready) "取得済み" else "未取得"}")
            appendLine("復元予約: ${if (me.zssu.ime.mozc.ProfileBackup.hasStagedRestore(context)) "あり" else "なし"}")
            for ((key, label) in listOf("key" to "キー処理", "display" to "キー受付から候補の描画準備まで", "ai" to "AI 推論")) {
                val count = prefs.getLong(key + "_count", 0)
                val mean = prefs.getLong(key + "_total_us", 0) / count.coerceAtLeast(1) / 1000.0
                appendLine("$label: $count 回 / 平均 $mean ms / 最大 ${prefs.getLong(key + "_max_us", 0) / 1000.0} ms")
            }
        }.trimEnd()
    }

    @Synchronized
    fun clear(context: Context) {
        val prefs = context.getSharedPreferences("input_performance", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("enabled", prefs.getBoolean("enabled", false)).apply()
    }

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
