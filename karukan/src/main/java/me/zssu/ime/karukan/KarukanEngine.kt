package me.zssu.ime.karukan

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local Karukan runtime.
 *
 * Loading and inference are intentionally serial and never block the IME main thread. A generation
 * number lets the caller invalidate a request when the user types again; stale neural output is
 * then dropped instead of unexpectedly replacing or committing a newer composition.
 */
class KarukanEngine(context: Context) : Closeable {
    sealed interface Result {
        data class Success(val generation: Long, val candidates: List<String>) : Result
        data class Failure(val generation: Long, val message: String) : Result
    }

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "karukan-inference").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val latestGeneration = AtomicLong(0)
    private val closed = AtomicBoolean(false)
    private val inferenceThreads = recommendedThreadCount(
        availableProcessors = Runtime.getRuntime().availableProcessors(),
        lowRamDevice = appContext.getSystemService(ActivityManager::class.java)?.isLowRamDevice
            == true,
    )
    private var handle = 0L
    private var loadedFingerprint: String? = null

    val isNativeAvailable: Boolean get() = KarukanNative.isAvailable

    fun invalidate(): Long = latestGeneration.incrementAndGet()

    fun convert(
        reading: String,
        context: String,
        candidateCount: Int = 3,
        callback: (Result) -> Unit,
    ): Long {
        val generation = invalidate()
        if (closed.get()) return generation
        executor.execute {
            if (closed.get() || generation != latestGeneration.get()) return@execute
            val result = runCatching {
                check(KarukanNative.isAvailable) { "この端末のCPUではKarukanを利用できません" }
                val files = KarukanModelStore.files(appContext)
                check(files.ready) { "Karukanモデルが未導入です" }
                val fingerprint = "${files.model.length()}:${files.tokenizer.length()}"
                if (handle == 0L || loadedFingerprint != fingerprint) {
                    if (handle != 0L) {
                        val old = handle
                        handle = 0L
                        loadedFingerprint = null
                        KarukanNative.nativeDestroy(old)
                    }
                    handle = KarukanNative.nativeCreate(
                        files.model.absolutePath,
                        files.tokenizer.absolutePath,
                        inferenceThreads,
                    )
                    check(handle != 0L) { "Karukanモデルを読み込めませんでした" }
                    loadedFingerprint = fingerprint
                }
                if (closed.get() || generation != latestGeneration.get()) return@execute
                KarukanNative.nativeConvert(
                    handle,
                    reading,
                    context.takeLast(MAX_CONTEXT_CHARS),
                    candidateCount,
                ).asList().distinct().filter(String::isNotBlank)
            }.fold(
                onSuccess = { Result.Success(generation, it) },
                onFailure = {
                    Result.Failure(generation, it.message ?: "Karukan変換に失敗しました")
                },
            )
            if (generation == latestGeneration.get()) {
                mainHandler.post {
                    if (generation == latestGeneration.get()) callback(result)
                }
            }
        }
        return generation
    }

    fun unload() {
        invalidate()
        if (closed.get()) return
        executor.execute {
            destroyHandle()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        invalidate()
        // Destruction stays ordered after any running inference, but InputMethodService.onDestroy
        // must never wait for a neural generation. The daemon worker owns native cleanup and can
        // finish without blocking Android's main thread.
        executor.execute(::destroyHandle)
        executor.shutdown()
    }

    private fun destroyHandle() {
        if (handle != 0L) {
            KarukanNative.nativeDestroy(handle)
            handle = 0L
            loadedFingerprint = null
        }
    }

    companion object {
        private const val MAX_CONTEXT_CHARS = 192

        internal fun recommendedThreadCount(availableProcessors: Int, lowRamDevice: Boolean): Int {
            if (lowRamDevice) return availableProcessors.coerceIn(1, 2)
            // Leave one core for the editor/UI and cap parallelism: the x-small model stops gaining
            // useful latency beyond four workers but continues increasing power and memory traffic.
            return (availableProcessors - 1).coerceIn(1, 4)
        }
    }
}

data class KarukanModelFiles(
    val directory: File,
    val model: File,
    val tokenizer: File,
) {
    val ready: Boolean get() =
        model.isFile && model.length() == KarukanModelStore.MODEL_SIZE &&
            tokenizer.isFile && tokenizer.length() == KarukanModelStore.TOKENIZER_SIZE
}

object KarukanModelStore {
    const val MODEL_SIZE = 31_178_432L
    const val TOKENIZER_SIZE = 2_047_609L
    const val MODEL_SHA256 =
        "bb3110f06e539bf8596756df85a48b3946f1378e6cb912322b9c368be06d79aa"
    const val TOKENIZER_SHA256 =
        "dde9713961ba536b14f20ed0c6e166abeeb5444886b966c590da1ad44dc9a3af"
    const val MODEL_URL =
        "https://huggingface.co/togatogah/jinen-v1-xsmall.gguf/resolve/main/" +
            "jinen-v1-xsmall-Q5_K_M.gguf"
    const val TOKENIZER_URL =
        "https://huggingface.co/togatogah/jinen-v1-xsmall.gguf/resolve/main/tokenizer.json"

    fun files(context: Context): KarukanModelFiles {
        val directory = File(context.filesDir, "karukan/jinen-v1-xsmall-q5")
        return KarukanModelFiles(
            directory = directory,
            model = File(directory, "model.gguf"),
            tokenizer = File(directory, "tokenizer.json"),
        )
    }
}
