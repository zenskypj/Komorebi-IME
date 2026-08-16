package me.zssu.ime.settings

import android.content.Context
import android.os.Handler
import android.os.Looper
import me.zssu.ime.karukan.KarukanModelFiles
import me.zssu.ime.karukan.KarukanModelStore
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Explicit, resumable installer for Karukan's on-device model.
 *
 * No input text is involved in this network path: it downloads two immutable public model files.
 * A partial file survives Activity recreation and is resumed with HTTP Range on the next attempt.
 */
class KarukanModelManager private constructor(private val files: KarukanModelFiles) {
    sealed interface State {
        data object NotInstalled : State
        data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : State
        data object Installed : State
        data class Failed(val message: String) : State
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    private val cancelled = AtomicBoolean(false)
    @Volatile private var state: State =
        if (files.ready) State.Installed else State.NotInstalled
    @Volatile private var worker: Thread? = null

    fun currentState(): State = state

    fun observe(listener: (State) -> Unit): AutoCloseable {
        listeners += listener
        listener(state)
        return AutoCloseable { listeners -= listener }
    }

    fun install() {
        if (worker?.isAlive == true || state is State.Installed) return
        cancelled.set(false)
        worker = Thread({
            runCatching {
                files.directory.mkdirs()
                download(
                    KarukanModelStore.TOKENIZER_URL,
                    files.tokenizer,
                    KarukanModelStore.TOKENIZER_SIZE,
                    offset = 0,
                )
                check(sha256(files.tokenizer) == KarukanModelStore.TOKENIZER_SHA256) {
                    files.tokenizer.delete()
                    "tokenizerの検証に失敗しました。もう一度ダウンロードしてください"
                }
                download(
                    KarukanModelStore.MODEL_URL,
                    files.model,
                    KarukanModelStore.MODEL_SIZE,
                    offset = KarukanModelStore.TOKENIZER_SIZE,
                )
                check(sha256(files.model) == KarukanModelStore.MODEL_SHA256) {
                    files.model.delete()
                    "モデルの検証に失敗しました。もう一度ダウンロードしてください"
                }
                check(files.ready) { "モデルファイルが不完全です" }
            }.fold(
                onSuccess = { publish(State.Installed) },
                onFailure = { error ->
                    if (cancelled.get()) {
                        publish(State.NotInstalled)
                    } else {
                        publish(State.Failed(error.message ?: "ダウンロードに失敗しました"))
                    }
                },
            )
        }, "karukan-model-download").apply {
            isDaemon = true
            start()
        }
    }

    fun cancel() {
        cancelled.set(true)
        worker?.interrupt()
    }

    fun delete() {
        cancel()
        files.directory.deleteRecursively()
        publish(State.NotInstalled)
    }

    private fun download(url: String, target: File, expectedSize: Long, offset: Long) {
        if (target.isFile && target.length() == expectedSize) return
        val partial = File(target.parentFile, "${target.name}.part")
        if (partial.length() > expectedSize) partial.delete()
        var existing = partial.length()
        var lastReported = existing
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Komorebi-IME/KarukanModelInstaller")
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            connection.connect()
            if (existing > 0 && connection.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                partial.delete()
                existing = 0
            }
            check(connection.responseCode in 200..299) {
                "サーバー応答 ${connection.responseCode}"
            }
            FileOutputStream(partial, existing > 0).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (cancelled.get() || Thread.currentThread().isInterrupted) {
                            error("キャンセルしました")
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        existing += read
                        if (existing - lastReported >= PROGRESS_STEP_BYTES ||
                            existing == expectedSize
                        ) {
                            lastReported = existing
                            publish(
                                State.Downloading(
                                    downloadedBytes = offset + existing,
                                    totalBytes =
                                        KarukanModelStore.TOKENIZER_SIZE +
                                            KarukanModelStore.MODEL_SIZE,
                                )
                            )
                        }
                    }
                    output.fd.sync()
                }
            }
            check(partial.length() == expectedSize) {
                "ファイルサイズが一致しません (${partial.length()} / $expectedSize)"
            }
            if (target.exists()) target.delete()
            check(partial.renameTo(target)) { "モデルを保存できませんでした" }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun publish(next: State) {
        state = next
        mainHandler.post { listeners.forEach { it(next) } }
    }

    companion object {
        private const val PROGRESS_STEP_BYTES = 256 * 1024L
        @Volatile private var instance: KarukanModelManager? = null

        fun get(context: Context): KarukanModelManager =
            instance ?: synchronized(this) {
                instance ?: KarukanModelManager(
                    KarukanModelStore.files(context.applicationContext)
                ).also { instance = it }
            }
    }
}
