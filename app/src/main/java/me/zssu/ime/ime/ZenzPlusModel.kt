package me.zssu.ime.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.io.FileOutputStream

/** User-initiated download; typed text and surrounding context never leave the device. */
internal object ZenzPlusModel {
    private const val URL = "https://huggingface.co/soichi1208/zenzai-plus/resolve/main/zenzai-plus-ft-f16.gguf"
    private const val SIZE = 192132704L
    private const val HASH = "928fc0f32dbafb4cd2b26275d1b40cefeb47aa27cd7b7144f92924363a7ec8f4"
    sealed interface State {
        data object Missing : State
        data class Downloading(val bytes: Long) : State
        data object Ready : State
        data class Error(val message: String) : State
    }
    private val ui = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    @Volatile private var state: State? = null
    private val cancelled = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null
    private fun partial(context: Context) = File(requireNotNull(file(context).parentFile), "plus-model.part")
    fun storedBytes(context: Context): Long = if (ready(context)) file(context).length() else partial(context).length()
    fun cancel() { cancelled.set(true); worker?.interrupt() }
    @Synchronized fun delete(context: Context): Boolean {
        if (worker?.isAlive == true) return false
        val ok = listOf(file(context), partial(context)).all { !it.exists() || it.delete() }
        if (ok) publish(State.Missing)
        return ok
    }
    fun file(context: Context): File = File(context.noBackupFilesDir, "zenzai/$HASH.gguf")
    fun ready(context: Context): Boolean = file(context).let { it.isFile && it.length() == SIZE }
    fun state(context: Context): State = state ?: if (ready(context)) State.Ready else State.Missing
    fun observe(context: Context, listener: (State) -> Unit): AutoCloseable {
        listeners += listener
        listener(state(context))
        return AutoCloseable { listeners -= listener }
    }
    private fun publish(value: State) { state = value; ui.post { listeners.forEach { it(value) } } }
    @Synchronized fun install(context: Context) {
        if (worker?.isAlive == true || ready(context)) return
        val app = context.applicationContext
        cancelled.set(false)
        publish(State.Downloading(partial(app).length().coerceAtMost(SIZE)))
        worker = Thread({
            val target = file(app)
            val directory = requireNotNull(target.parentFile)
            val temporary = partial(app)
            try {
                check(directory.mkdirs() || directory.isDirectory)
                if (temporary.length() > SIZE) temporary.delete()
                var offset = temporary.length()
                val connection = URL(URL).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 20000; connection.readTimeout = 30000
                    connection.instanceFollowRedirects = true
                    connection.setRequestProperty("User-Agent", "Komorebi-IME/ZenzPlusModelInstaller")
                    if (offset > 0 && offset < SIZE) connection.setRequestProperty("Range", "bytes=$offset-")
                    if (offset < SIZE) {
                        val code = connection.responseCode
                        check(code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) { "サーバー応答 $code" }
                        if (offset > 0 && code == HttpURLConnection.HTTP_PARTIAL) {
                            val range = connection.getHeaderField("Content-Range").orEmpty()
                            check(range.startsWith("bytes $offset-")) { "ダウンロードの再開位置が一致しません" }
                        } else if (code == HttpURLConnection.HTTP_OK) offset = 0
                        var count = offset
                        var last = offset
                        connection.inputStream.use { input -> FileOutputStream(temporary, offset > 0).use { output ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
                                val n = input.read(buffer); if (n < 0) break
                                count += n; check(count <= SIZE) { "モデルのサイズが一致しません" }
                                output.write(buffer, 0, n)
                                if (count - last >= 1024 * 1024) { last = count; publish(State.Downloading(count)) }
                            }
                            output.fd.sync()
                        } }
                    }
                    if (cancelled.get()) throw InterruptedException()
                    val digest = MessageDigest.getInstance("SHA-256")
                    temporary.inputStream().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            if (cancelled.get()) throw InterruptedException()
                            val n = input.read(buffer); if (n < 0) break
                            digest.update(buffer, 0, n)
                        }
                    }
                    val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    if (temporary.length() != SIZE || hash != HASH) {
                        if (temporary.length() == SIZE) temporary.delete()
                        error("モデルの検証に失敗しました")
                    }
                    check(temporary.renameTo(target)) { "モデルを保存できませんでした" }
                    publish(State.Ready)
                } finally { connection.disconnect() }
            } catch (e: Exception) {
                if (cancelled.get() || e is InterruptedException) publish(State.Missing)
                else publish(State.Error(e.message ?: "ダウンロードに失敗しました"))
            }
        }, "zenzplus-model-download").apply { isDaemon = true; start() }
    }

}
