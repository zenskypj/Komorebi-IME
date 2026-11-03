package me.zssu.ime.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet

/** User-initiated download; typed text and surrounding context never leave the device. */
internal object Zenz32Model {
    private const val URL = "https://huggingface.co/Miwa-Keita/zenz-v3.2-small-gguf/resolve/main/ggml-model-Q5_K_M.gguf"
    private const val SIZE = 73871936L
    private const val HASH = "29c223d4c23327b80fd13ebb5ab2555057a46317997d5da391584ffbef0db673"
    sealed interface State {
        data object Missing : State
        data class Downloading(val bytes: Long) : State
        data object Ready : State
        data class Error(val message: String) : State
    }
    private val ui = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    @Volatile private var state: State? = null
    fun file(context: Context): File = File(context.noBackupFilesDir, "zenz32/model-$HASH.gguf")
    fun ready(context: Context): Boolean = file(context).let { it.isFile && it.length() == SIZE }
    fun state(context: Context): State = state ?: if (ready(context)) State.Ready else State.Missing
    fun observe(context: Context, listener: (State) -> Unit): AutoCloseable {
        listeners += listener
        listener(state(context))
        return AutoCloseable { listeners -= listener }
    }
    private fun publish(value: State) { state = value; ui.post { listeners.forEach { it(value) } } }
    @Synchronized fun install(context: Context) {
        if (state is State.Downloading || ready(context)) return
        val app = context.applicationContext
        publish(State.Downloading(0))
        Thread({
            val target = file(app)
            val directory = requireNotNull(target.parentFile)
            val temporary = File(directory, "model.part")
            try {
                check(directory.mkdirs() || directory.isDirectory)
                val connection = URL(URL).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 20000; connection.readTimeout = 30000
                    connection.instanceFollowRedirects = true
                    connection.setRequestProperty("User-Agent", "Komorebi-IME/Zenz32ModelInstaller")
                    check(connection.responseCode in 200..299) { "サーバー応答 ${connection.responseCode}" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var count = 0L; var last = 0L
                    connection.inputStream.use { input -> temporary.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            count += n; check(count <= SIZE) { "モデルのサイズが一致しません" }
                            output.write(buffer, 0, n); digest.update(buffer, 0, n)
                            if (count - last >= 1024 * 1024) { last = count; publish(State.Downloading(count)) }
                        }
                        output.fd.sync()
                    } }
                    val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    check(count == SIZE && hash == HASH) { "モデルの検証に失敗しました" }
                    check(temporary.renameTo(target)) { "モデルを保存できませんでした" }
                    publish(State.Ready)
                } finally { connection.disconnect() }
            } catch (e: Exception) { temporary.delete(); publish(State.Error(e.message ?: "ダウンロードに失敗しました")) }
        }, "zenz32-model-download").apply { isDaemon = true; start() }
    }
}

internal object Zenz32Native {
    val available = runCatching { System.loadLibrary("komorebi_zenz32") }.isSuccess
    external fun load(path: String): Long
    external fun generate(handle: Long, prompt: ByteArray, generation: Long): ByteArray?
    external fun cancel(): Long
    external fun free(handle: Long)
}
