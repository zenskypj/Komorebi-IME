package me.zssu.ime.voice

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Visible launcher explains the optional companion and exposes model management. */
class ModuleInfoActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var install: Button
    private lateinit var delete: Button
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt()) }
        content.addView(TextView(this).apply { text = "Komorebi 音声入力"; textSize = 24f })
        content.addView(TextView(this).apply {
            text = "Komorebi IME と一緒に使う、任意の音声入力モジュールです。キーボードのツールメニューから起動できます。\n\n「話す」を押して許可したときだけマイクを使います。音声認識は端末内で行い、音声を送信しません。モデルは取得を選んだときだけ公式配布元から取得します。\n\n日本語モデル：約48 MB、展開後約99 MB（別途、一時領域が必要）"
            textSize = 16f
        })
        status = TextView(this).apply { textSize = 16f }
        install = Button(this).apply { text = "日本語モデルを取得"; setOnClickListener { VoiceModelStore.install(this@ModuleInfoActivity); refresh() } }
        delete = Button(this).apply { text = "モデルを削除"; setOnClickListener { VoiceModelStore.delete(this@ModuleInfoActivity); refresh() } }
        content.addView(status); content.addView(install); content.addView(delete)
        content.addView(TextView(this).apply { text = "提供: ZenSky Project\nソースコード: https://github.com/zenskypj/Komorebi-IME\nモデル: Vosk 日本語 small（Apache 2.0）"; textSize = 14f })
        setContentView(ScrollView(this).apply { addView(content) })
        VoiceModelStore.listener = { refresh() }; refresh()
    }
    private fun refresh() {
        val ready = VoiceModelStore.ready(this)
        val busy = VoiceModelStore.busy()
        status.text = VoiceModelStore.status.ifEmpty { if (ready) "モデルを取得済み" else "モデルは未取得です" }
        install.isEnabled = !ready && !busy
        delete.isEnabled = ready && !busy
    }
    override fun onDestroy() { VoiceModelStore.listener = null; super.onDestroy() }
}
