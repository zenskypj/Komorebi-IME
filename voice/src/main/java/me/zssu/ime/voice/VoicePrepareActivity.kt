package me.zssu.ime.voice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import android.widget.TextView

/** Runs only in response to the keyboard's explicit microphone action. */
class VoicePrepareActivity : Activity() {
    private var receiver: ResultReceiver? = null
    private var completed = false
    private var resumed = false
    private var needsPrepare = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        receiver = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("receiver", ResultReceiver::class.java)
            else intent.getParcelableExtra("receiver")
        if (receiver == null) { finish(); return }
        setContentView(TextView(this).apply {
            text = "音声入力を準備しています…"; textSize = 18f
            setPadding(32, 32, 32, 32)
        })
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (needsPrepare && !completed) { needsPrepare = false; prepare() }
    }

    override fun onPause() { resumed = false; super.onPause() }

    private fun prepare() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val prefs = getSharedPreferences("voice_setup", MODE_PRIVATE)
            if (prefs.getBoolean("microphone_asked", false) &&
                !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("マイクの許可が必要です")
                    .setMessage("端末のアプリ設定で、Komorebi 音声入力のマイクを許可してください。")
                    .setNegativeButton("キャンセル") { _, _ -> complete(1, "マイクが許可されていません") }
                    .setPositiveButton("設定を開く") { _, _ ->
                        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:$packageName")))
                        complete(1, "設定後、音声入力をもう一度押してください")
                    }.setOnCancelListener { complete(1, "マイクが許可されていません") }.show()
            } else {
                prefs.edit().putBoolean("microphone_asked", true).apply()
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            }
        } else if (!VoiceModelStore.ready(this)) {
            complete(2, "日本語モデルを取得してから、もう一度音声入力を押してください")
            startActivity(Intent(this, ModuleInfoActivity::class.java))
        } else {
            val callback = object : ResultReceiver(android.os.Handler(mainLooper)) {
                override fun onReceiveResult(code: Int, data: Bundle?) {
                    complete(code, data?.getString("text").orEmpty())
                }
            }
            val service = Intent(this, VoiceRecognitionService::class.java).putExtra("receiver", callback)
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
            } catch (e: Exception) {
                complete(1, "マイクを準備できませんでした。音声入力をもう一度お試しください")
            }
        }
    }

    @Deprecated("Android callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                if (resumed) prepare() else needsPrepare = true
            } else complete(1, "音声入力にはマイクの許可が必要です。端末のアプリ設定でも変更できます")
        }
    }

    private fun complete(code: Int, message: String) {
        if (completed) return
        completed = true
        receiver?.send(code, Bundle().apply { putString("text", message) })
        receiver = null
        finish()
    }

    override fun onDestroy() {
        if (isFinishing && !completed) complete(1, "音声入力の準備をキャンセルしました")
        super.onDestroy()
    }
}
