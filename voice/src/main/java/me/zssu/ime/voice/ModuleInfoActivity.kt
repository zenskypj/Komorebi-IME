package me.zssu.ime.voice

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** First-time model and microphone setup; recording is controlled by the keyboard. */
class ModuleInfoActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var install: Button
    private lateinit var permission: Button
    private lateinit var delete: Button
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()
        val ink = Color.rgb(30, 45, 40)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(24)); setBackgroundColor(Color.rgb(244, 248, 245)) }
        content.addView(TextView(this).apply { text = "音声入力の準備"; textSize = 26f; setTextColor(ink); setTypeface(null, Typeface.BOLD) })
        content.addView(TextView(this).apply { text = "準備が終わったら、キーボードの音声入力からそのまま話せます。"; textSize = 16f; setTextColor(ink); setPadding(0, dp(10), 0, dp(18)) })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(12))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(18).toFloat() }
        }
        card.addView(TextView(this).apply { text = "① 日本語モデル"; textSize = 19f; setTypeface(null, Typeface.BOLD); setTextColor(ink) })
        card.addView(TextView(this).apply { text = "約48 MBを取得し、端末内で認識します。展開後は約99 MBと一時領域が必要です。"; textSize = 15f; setTextColor(ink); setPadding(0, dp(8), 0, dp(8)) })
        status = TextView(this).apply { textSize = 15f; setTextColor(ink) }
        install = Button(this).apply { text = "モデルを取得する"; setOnClickListener { VoiceModelStore.install(this@ModuleInfoActivity); refresh() } }
        card.addView(status); card.addView(install)
        content.addView(card)
        val second = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(12)); background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(18).toFloat() } }
        second.addView(TextView(this).apply { text = "② マイクの許可"; textSize = 19f; setTypeface(null, Typeface.BOLD); setTextColor(ink) })
        second.addView(TextView(this).apply { text = "キーボードで音声入力を開いた間だけ使用します。音声は端末の外へ送りません。"; textSize = 15f; setTextColor(ink); setPadding(0, dp(8), 0, dp(8)) })
        permission = Button(this).apply { text = "マイクを許可する"; setOnClickListener {
            val asked = getSharedPreferences("voice_setup", MODE_PRIVATE).getBoolean("microphone_asked", false)
            if (asked && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$packageName"))) }
            } else {
                getSharedPreferences("voice_setup", MODE_PRIVATE).edit().putBoolean("microphone_asked", true).apply()
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            }
        } }
        second.addView(permission)
        content.addView(second, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        content.addView(Button(this).apply { text = "キーボードに戻る"; setOnClickListener { finish() } }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        delete = Button(this).apply { text = "モデルを削除"; setOnClickListener {
            AlertDialog.Builder(this@ModuleInfoActivity).setTitle("モデルを削除しますか？").setMessage("再び使うにはダウンロードが必要です。")
                .setNegativeButton("キャンセル", null).setPositiveButton("削除") { _, _ -> VoiceModelStore.delete(this@ModuleInfoActivity); refresh() }.show()
        } }
        content.addView(delete)
        content.addView(TextView(this).apply { text = "Vosk 日本語 small / Apache 2.0\n提供: ZenSky Project"; textSize = 13f; gravity = Gravity.CENTER; setTextColor(Color.DKGRAY); setPadding(0, dp(12), 0, 0) })
        setContentView(ScrollView(this).apply { addView(content) })
        VoiceModelStore.listener = { refresh() }; refresh()
    }
    private fun refresh() {
        val ready = VoiceModelStore.ready(this); val busy = VoiceModelStore.busy()
        status.text = if (busy) VoiceModelStore.status.ifEmpty { "取得中…" } else if (ready) "✓ モデルの準備ができました" else VoiceModelStore.status.ifEmpty { "まだ取得されていません" }
        install.isEnabled = !ready && !busy
        permission.isEnabled = checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        permission.text = if (permission.isEnabled) "マイクを許可する" else "✓ マイクの許可済み"
        delete.isEnabled = ready && !busy
    }
    @Deprecated("Android callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { super.onRequestPermissionsResult(requestCode, permissions, grantResults); refresh() }
    override fun onResume() { super.onResume(); if (::status.isInitialized) refresh() }
    override fun onDestroy() { VoiceModelStore.listener = null; super.onDestroy() }
}
