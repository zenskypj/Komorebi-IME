package me.zssu.ime.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Downloads a pinned public model only after a user action. Never sends audio or input text. */
internal object VoiceModelStore {
    private const val URL = "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip"
    private const val SIZE = 49704573L
    private const val HASH = "efa092d280153a77615e9e0c7d7283e93e600de3d19d3bec686c57ef19d52eac"
    private const val ROOT = "vosk-model-small-ja-0.22/"
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var worker: Thread? = null
    @Volatile var listener: ((String) -> Unit)? = null
    @Volatile var status = ""
        private set
    fun directory(context: Context) = File(context.noBackupFilesDir, "voice-model-ja-0.22")
    fun ready(context: Context): Boolean = File(directory(context), ".ready").isFile
    fun busy() = worker?.isAlive == true
    private fun publish(s: String) { status = s; handler.post { listener?.invoke(s) } }

    @Synchronized fun install(context: Context) {
        if (busy()) return
        val app = context.applicationContext
        worker = Thread({
            val base = app.noBackupFilesDir
            val partial = File(base, "voice-model.zip.part")
            val stage = File(base, "voice-model-stage")
            var outcome: String
            try {
                stage.deleteRecursively()
                val conn = URL(URL).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 20_000; conn.readTimeout = 30_000
                    check(conn.responseCode == 200) { "サーバー応答 ${conn.responseCode}" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    conn.inputStream.use { input -> FileOutputStream(partial).use { output ->
                        val buf = ByteArray(32 * 1024)
                        var reported = -1L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            count += n; check(count <= SIZE) { "モデルのサイズが不正です" }
                            output.write(buf, 0, n); digest.update(buf, 0, n)
                            if (count / 1_000_000 != reported) { reported = count / 1_000_000; publish("取得中: ${count * 100 / SIZE}%") }
                        }
                        output.fd.sync()
                    } }
                    check(count == SIZE && digest.digest().joinToString("") { "%02x".format(it) } == HASH) { "モデルの検証に失敗しました" }
                } finally { conn.disconnect() }
                publish("モデルを展開中…")
                check(stage.mkdirs())
                var total = 0L
                ZipInputStream(partial.inputStream().buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        check(entry.name.startsWith(ROOT)) { "不正なモデル形式です" }
                        val rel = entry.name.removePrefix(ROOT)
                        if (rel.isEmpty()) continue
                        val file = File(stage, rel)
                        check(file.canonicalPath.startsWith(stage.canonicalPath + File.separator)) { "不正なファイル名です" }
                        if (entry.isDirectory) { file.mkdirs(); continue }
                        file.parentFile?.mkdirs()
                        file.outputStream().buffered().use { output ->
                            val buf = ByteArray(32 * 1024)
                            while (true) {
                                val n = zip.read(buf); if (n < 0) break
                                total += n; check(total <= 120_000_000L) { "展開サイズが不正です" }
                                output.write(buf, 0, n)
                            }
                        }
                    }
                }
                check(File(stage, "am/final.mdl").isFile && File(stage, "graph/HCLr.fst").isFile) { "モデルが不完全です" }
                File(stage, ".ready").writeText(HASH)
                val dest = directory(app)
                check(!dest.exists() || dest.deleteRecursively())
                check(stage.renameTo(dest)) { "モデルを保存できませんでした" }
                outcome = "取得が完了しました"
            } catch (e: Exception) {
                stage.deleteRecursively()
                outcome = "取得できませんでした: ${e.message ?: "通信エラー"}"
            } finally { partial.delete(); worker = null }
            publish(outcome)
        }, "voice-model-download").apply { isDaemon = true; start() }
    }
    @Synchronized fun delete(context: Context): Boolean {
        if (busy()) return false
        val ok = directory(context).deleteRecursively()
        publish(if (ok) "モデルを削除しました" else "削除できませんでした")
        return ok
    }
}
