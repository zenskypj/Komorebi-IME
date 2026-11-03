package me.zssu.ime.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Explicit, resumable installer for the on-device proofreading models.
 *
 * The download path only ever fetches immutable public GGUF files; no input text is involved.
 * A partial file survives process or Activity recreation and is resumed with HTTP Range on the
 * next attempt. State is kept here rather than in an Activity so the review window and the model
 * manager see the same progress and readiness.
 */
internal object ProofreadModelStore {

    /**
     * A downloadable quantisation.
     *
     * [revision] pins the exact Hugging Face commit so a later upstream re-quantisation cannot
     * silently change the bytes under [sha256]. The size and hash double as the integrity gate.
     */
    data class Spec(
        val id: String,
        val label: String,
        val repository: String,
        val revision: String,
        val filename: String,
        val bytes: Long,
        val sha256: String,
    ) {
        val source get() = "https://huggingface.co/$repository"
        val downloadUrl get() = "$source/resolve/$revision/$filename"
    }

    /** Smallest first; index 0 is the constrained-device default. */
    val specs: List<Spec> = listOf(
        Spec(
            id = "qwen3.5-0.8b-q4_k_m",
            label = "Qwen3.5 0.8B（約533 MB・軽量）",
            repository = "unsloth/Qwen3.5-0.8B-GGUF",
            revision = "6ab461498e2023f6e3c1baea90a8f0fe38ab64d0",
            filename = "Qwen3.5-0.8B-Q4_K_M.gguf",
            bytes = 532517120L,
            sha256 = "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517",
        ),
        Spec(
            id = "qwen3-1.7b-q4_k_m",
            label = "Qwen3 1.7B（約1.11 GB・高精度）",
            repository = "unsloth/Qwen3-1.7B-GGUF",
            revision = "d7f544eead698dbd1f15126ef60b45a1e1933222",
            filename = "Qwen3-1.7B-Q4_K_M.gguf",
            bytes = 1107409472L,
            sha256 = "b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897",
        ),
    )

    sealed interface State {
        data object Missing : State
        data class Downloading(val bytes: Long, val total: Long) : State
        data object Ready : State
        data class Error(val message: String) : State
    }

    private const val PREFS = "proofread"
    private const val KEY_SELECTED = "model_id"

    private val ui = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(String, State) -> Unit>()
    private val states = ConcurrentHashMap<String, State>()
    private val cancelled = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null
    @Volatile private var activeId: String? = null

    fun file(context: Context, spec: Spec): File =
        File(context.noBackupFilesDir, "proofread/${spec.filename}")

    private fun partial(context: Context, spec: Spec): File =
        File(file(context, spec).parentFile, "${spec.filename}.part")

    fun ready(context: Context, spec: Spec): Boolean =
        file(context, spec).let { it.isFile && it.length() == spec.bytes }

    fun storedBytes(context: Context, spec: Spec): Long =
        if (ready(context, spec)) spec.bytes
        else partial(context, spec).takeIf { it.isFile }?.length() ?: 0L

    // ---- selection ------------------------------------------------------------------------------

    fun selectedId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SELECTED, null)
            ?.takeIf { id -> specs.any { it.id == id } }
            ?: specs.first().id

    fun selected(context: Context): Spec = specs.first { it.id == selectedId(context) }

    fun select(context: Context, spec: Spec) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SELECTED, spec.id).apply()
    }

    // ---- download state -------------------------------------------------------------------------

    fun state(context: Context, spec: Spec): State =
        states[spec.id] ?: if (ready(context, spec)) State.Ready else State.Missing

    fun observe(context: Context, listener: (String, State) -> Unit): AutoCloseable {
        listeners += listener
        specs.forEach { listener(it.id, state(context, it)) }
        return AutoCloseable { listeners -= listener }
    }

    private fun publish(id: String, value: State) {
        states[id] = value
        ui.post { listeners.forEach { it(id, value) } }
    }

    @Synchronized
    fun install(context: Context, spec: Spec) {
        if (worker?.isAlive == true || ready(context, spec)) return
        val app = context.applicationContext
        cancelled.set(false)
        activeId = spec.id
        publish(spec.id, State.Downloading(partial(app, spec).length().coerceAtMost(spec.bytes), spec.bytes))
        worker = Thread({
            val target = file(app, spec)
            val directory = requireNotNull(target.parentFile)
            val temporary = partial(app, spec)
            try {
                check(directory.mkdirs() || directory.isDirectory)
                if (temporary.length() > spec.bytes) temporary.delete()
                var offset = temporary.length()
                val connection = URL(spec.downloadUrl).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 20_000
                    connection.readTimeout = 30_000
                    connection.instanceFollowRedirects = true
                    connection.setRequestProperty("User-Agent", "Komorebi-IME/ProofreadModelInstaller")
                    if (offset > 0 && offset < spec.bytes) connection.setRequestProperty("Range", "bytes=$offset-")
                    if (offset < spec.bytes) {
                        val code = connection.responseCode
                        check(code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) { "サーバー応答 $code" }
                        if (offset > 0 && code == HttpURLConnection.HTTP_PARTIAL) {
                            val range = connection.getHeaderField("Content-Range").orEmpty()
                            check(range.startsWith("bytes $offset-")) { "ダウンロードの再開位置が一致しません" }
                        } else if (code == HttpURLConnection.HTTP_OK) {
                            offset = 0
                        }
                        var count = offset
                        var last = offset
                        connection.inputStream.use { input ->
                            FileOutputStream(temporary, offset > 0).use { output ->
                                val buffer = ByteArray(65536)
                                while (true) {
                                    if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    count += read
                                    check(count <= spec.bytes) { "モデルのサイズが一致しません" }
                                    output.write(buffer, 0, read)
                                    if (count - last >= 1024 * 1024) {
                                        last = count
                                        publish(spec.id, State.Downloading(count, spec.bytes))
                                    }
                                }
                                output.fd.sync()
                            }
                        }
                    }
                    if (cancelled.get()) throw InterruptedException()
                    val digest = MessageDigest.getInstance("SHA-256")
                    temporary.inputStream().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            if (cancelled.get()) throw InterruptedException()
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                        }
                    }
                    val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    if (temporary.length() != spec.bytes || hash != spec.sha256) {
                        if (temporary.length() == spec.bytes) temporary.delete()
                        error("モデルの検証に失敗しました")
                    }
                    check(temporary.renameTo(target)) { "モデルを保存できませんでした" }
                    publish(spec.id, State.Ready)
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                if (cancelled.get() || e is InterruptedException) publish(spec.id, State.Missing)
                else publish(spec.id, State.Error(e.message ?: "ダウンロードに失敗しました"))
            } finally {
                if (activeId == spec.id) activeId = null
            }
        }, "proofread-model-download").apply {
            isDaemon = true
            start()
        }
    }

    fun cancel() {
        cancelled.set(true)
        worker?.interrupt()
    }

    @Synchronized
    fun delete(context: Context, spec: Spec): Boolean {
        if (worker?.isAlive == true && activeId == spec.id) return false
        val removed = listOf(file(context, spec), partial(context, spec)).all { !it.exists() || it.delete() }
        if (removed) publish(spec.id, State.Missing)
        return removed
    }
}
