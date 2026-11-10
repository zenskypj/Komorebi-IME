package me.zssu.ime.voice

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/** The microphone is used only while this activity is visible and the user is speaking. */
class VoiceActivity : Activity(), RecognitionListener {
    private var receiver: ResultReceiver? = null
    private var finished = false
    private var generation = 0
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null
    private var recognized = ""
    private val handler = Handler(Looper.getMainLooper())
    private val stopAfterTimeout = Runnable { speech?.stop(); stop.isEnabled = false }
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var send: Button
    private lateinit var install: Button
    private lateinit var delete: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        receiver = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("receiver", ResultReceiver::class.java)
            else intent.getParcelableExtra("receiver")
        if (receiver == null) { finish(); return }
        val dp = resources.displayMetrics.density
        fun px(n: Int) = (n * dp).toInt()
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(px(24), px(16), px(24), px(16)) }
        container.addView(TextView(this).apply { text = "音声入力"; textSize = 24f })
        container.addView(TextView(this).apply {
            text = "日本語の軽量音声モデル（Vosk）を端末に取得して使います。通信するのはモデル取得時だけです。約48 MBの通信と展開後約99 MBの保存領域が必要です。"
            textSize = 15f; setPadding(0, px(12), 0, px(12))
        })
        status = TextView(this).apply { textSize = 16f }
        result = TextView(this).apply { textSize = 18f; setPadding(0, px(12), 0, px(12)) }
        install = Button(this).apply { text = "日本語モデルを取得"; setOnClickListener { VoiceModelStore.install(this@VoiceActivity); refresh() } }
        delete = Button(this).apply { text = "モデルを削除"; setOnClickListener { VoiceModelStore.delete(this@VoiceActivity); refresh() } }
        start = Button(this).apply { text = "話す"; setOnClickListener { begin() } }
        stop = Button(this).apply { text = "話し終える"; isEnabled = false; setOnClickListener { speech?.stop(); isEnabled = false; status.text = "認識結果を確認しています…" } }
        send = Button(this).apply { text = "入力する"; isEnabled = false; setOnClickListener { complete(RESULT_OK, recognized) } }
        container.addView(status); container.addView(result); container.addView(install); container.addView(delete)
        container.addView(start); container.addView(stop); container.addView(send)
        container.addView(Button(this).apply { text = "キャンセル"; setOnClickListener { complete(RESULT_CANCELED, null) } })
        container.addView(Button(this).apply {
            text = "モデルとライセンス"
            setOnClickListener {
                android.app.AlertDialog.Builder(this@VoiceActivity).setTitle("モデルとライセンス")
                    .setMessage("日本語モデル: vosk-model-small-ja-0.22（Apache License 2.0）\nhttps://alphacephei.com/vosk/models\n\nVosk Android 0.3.75、JNA 5.18.1（Apache License 2.0）\nライセンス全文は APK 内 assets/legal に収録しています。")
                    .setPositiveButton("閉じる", null).show()
            }
        })
        setContentView(ScrollView(this).apply { addView(container) })
        VoiceModelStore.listener = { refresh() }
        refresh()
    }

    private fun refresh() {
        val ready = VoiceModelStore.ready(this)
        val busy = VoiceModelStore.busy()
        install.isEnabled = !ready && !busy
        delete.isEnabled = ready && !busy && speech == null
        start.isEnabled = ready && !busy && speech == null
        status.text = VoiceModelStore.status.ifEmpty { if (ready) "「話す」を押してください" else "最初に日本語モデルを取得してください" }
    }
    private fun begin() {
        if (!VoiceModelStore.ready(this)) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        val ticket = ++generation
        start.isEnabled = false; delete.isEnabled = false; send.isEnabled = false
        recognized = ""; result.text = ""; status.text = "モデルを読み込んでいます…"
        val path = VoiceModelStore.directory(this).absolutePath
        Thread({
            val loaded = runCatching { Model(path) }
            Handler(Looper.getMainLooper()).post {
                val m = loaded.getOrNull()
                if (ticket != generation || isFinishing || isDestroyed) { m?.close(); return@post }
                if (m == null) { status.text = "モデルを読み込めません。削除して再取得してください。"; refreshButtons(); return@post }
                model = m
                try {
                    val rec = Recognizer(m, 16000f); recognizer = rec
                    val service = SpeechService(rec, 16000f); speech = service
                    check(service.startListening(this))
                    handler.postDelayed(stopAfterTimeout, 60_000)
                    stop.isEnabled = true; status.text = "話してください"
                } catch (e: Exception) { cleanup(); status.text = "マイクまたは音声認識を開始できませんでした"; refreshButtons() }
            }
        }, "voice-model-load").apply { isDaemon = true; start() }
    }
    private fun refreshButtons() { start.isEnabled = VoiceModelStore.ready(this); delete.isEnabled = start.isEnabled; stop.isEnabled = false }
    @Deprecated("Android callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) { if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) begin() else status.text = "音声入力にはマイクの許可が必要です" }
    }
    private fun value(json: String?, key: String): String = runCatching { JSONObject(json ?: "{}").optString(key) }.getOrDefault("").replace(" ", "")
    override fun onPartialResult(hypothesis: String?) { result.text = (recognized + value(hypothesis, "partial")).take(4000) }
    override fun onResult(hypothesis: String?) { recognized = (recognized + value(hypothesis, "text")).take(4000); result.text = recognized }
    override fun onFinalResult(hypothesis: String?) {
        recognized = (recognized + value(hypothesis, "text")).take(4000); result.text = recognized
        cleanup(); refreshButtons(); send.isEnabled = recognized.isNotBlank()
        status.text = if (recognized.isBlank()) "聞き取れませんでした。もう一度お試しください。" else "内容を確認してください"
    }
    override fun onError(exception: Exception?) { cleanup(); refreshButtons(); status.text = "音声認識に失敗しました" }
    override fun onTimeout() { speech?.stop(); stop.isEnabled = false; status.text = "時間になったため終了しました" }
    private fun cleanup() { handler.removeCallbacks(stopAfterTimeout); speech?.cancel(); speech?.shutdown(); speech = null; recognizer?.close(); recognizer = null; model?.close(); model = null }
    private fun complete(code: Int, text: String?) {
        if (finished) return
        finished = true; generation++; cleanup()
        val callback = receiver; receiver = null; finish()
        Handler(Looper.getMainLooper()).post { callback?.send(code, Bundle().apply { if (text != null) putString("text", text) }) }
    }
    override fun onStop() { generation++; cleanup(); if (!finished && ::start.isInitialized) refreshButtons(); super.onStop() }
    override fun onDestroy() {
        VoiceModelStore.listener = null; cleanup()
        if (isFinishing && !finished) receiver?.send(RESULT_CANCELED, null)
        super.onDestroy()
    }
}
