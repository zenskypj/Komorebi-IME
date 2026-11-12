package me.zssu.ime.voice

import android.Manifest
import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.ResultReceiver
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/** Signature-protected, bound only while the keyboard is visible. No audio is sent to the IME. */
class VoiceRecognitionService : Service() {
    private val handler = Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            1 -> { client = msg.replyTo; begin() }
            2 -> { speech?.stop(); send(11, "認識結果をまとめています…") }
            3 -> cleanup()
        }
        true
    }
    private val messenger = Messenger(handler)
    private var client: Messenger? = null
    private var generation = 0
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null
    private var text = ""
    private val timeout = Runnable { speech?.stop() }
    private val idleTimeout = Runnable { if (client == null) stopRecordingService() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        @Suppress("DEPRECATION")
        val receiver = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("receiver", ResultReceiver::class.java)
            else intent?.getParcelableExtra("receiver")
        try {
            val channel = "voice_recording"
            if (Build.VERSION.SDK_INT >= 26) {
                getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(channel, "音声入力", NotificationManager.IMPORTANCE_LOW))
            }
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel) else Notification.Builder(this)
            val notification = builder.setSmallIcon(R.drawable.ic_voice_module)
                .setContentTitle("Komorebi 音声入力")
                .setContentText("音声入力の準備中・使用中です")
                .setOngoing(true).build()
            startForeground(42, notification)
            handler.removeCallbacks(idleTimeout)
            handler.postDelayed(idleTimeout, 30_000)
            receiver?.send(0, Bundle())
        } catch (e: Exception) {
            receiver?.send(1, Bundle().apply { putString("text", "マイクを開始できませんでした。権限と端末の設定を確認してください") })
            stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun stopRecordingService() {
        handler.removeCallbacks(idleTimeout)
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }
    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onUnbind(intent: Intent?): Boolean { cleanup(); client = null; stopRecordingService(); return false }
    override fun onDestroy() { cleanup(); super.onDestroy() }
    private fun send(what: Int, value: String = "") {
        runCatching { client?.send(Message.obtain(null, what).apply { data = Bundle().apply { putString("text", value) } }) }
    }
    private fun begin() {
        cleanup()
        handler.removeCallbacks(idleTimeout)
        if (!VoiceModelStore.ready(this) || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            send(10, "モデルの取得とマイクの許可が必要です"); stopRecordingService(); return
        }
        val ticket = ++generation
        text = ""
        send(11, "音声モデルを準備しています…")
        val path = VoiceModelStore.directory(this).absolutePath
        Thread({
            val loaded = runCatching { Model(path) }
            handler.post {
                val m = loaded.getOrNull()
                if (ticket != generation || client == null) { m?.close(); return@post }
                if (m == null) { stopRecordingService(); send(14, "モデルを読み込めません。音声入力アプリで再取得してください"); return@post }
                model = m
                try {
                    val rec = Recognizer(m, 16000f); recognizer = rec
                    val audio = SpeechService(rec, 16000f); speech = audio
                    check(audio.startListening(object : RecognitionListener {
                        private fun value(json: String?, key: String) = runCatching { JSONObject(json ?: "{}").optString(key).replace(" ", "") }.getOrDefault("")
                        override fun onPartialResult(h: String?) { if (ticket == generation) send(12, (text + value(h, "partial")).take(4000)) }
                        override fun onResult(h: String?) { if (ticket == generation) { text = (text + value(h, "text")).take(4000); send(12, text) } }
                        override fun onFinalResult(h: String?) {
                            if (ticket != generation) return
                            val result = (text + value(h, "text")).take(4000)
                            cleanup(); stopRecordingService(); send(13, result)
                        }
                        override fun onError(e: Exception?) { if (ticket == generation) { cleanup(); stopRecordingService(); send(14, "マイクを利用できません。権限と端末の設定を確認してください") } }
                        override fun onTimeout() { if (ticket == generation) speech?.stop() }
                    }))
                    handler.postDelayed(timeout, 60_000)
                    send(11, "話してください")
                } catch (e: Exception) { cleanup(); stopRecordingService(); send(14, "音声認識を開始できませんでした。マイクの権限を確認してください") }
            }
        }, "voice-model-load").apply { isDaemon = true; start() }
    }
    private fun cleanup() {
        generation++
        handler.removeCallbacks(timeout)
        speech?.cancel(); speech?.shutdown(); speech = null
        recognizer?.close(); recognizer = null
        model?.close(); model = null
    }
}
