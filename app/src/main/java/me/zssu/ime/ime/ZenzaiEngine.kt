package me.zssu.ime.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import me.zssu.ime.mozc.UserDictionary

/** Owns the native model on one worker; input and lifecycle callbacks only enqueue work. */
internal class ZenzaiEngine(context: Context, private val onError: () -> Unit) {
    private val app = context.applicationContext
    private val ui = Handler(Looper.getMainLooper())
    private val worker = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private var pending: ScheduledFuture<*>? = null
    @Volatile private var enabled = false
    @Volatile private var generation = 0L
    private var nativeReady = false
    @Volatile private var useZenz32 = false
    fun usesZenz32(): Boolean = useZenz32
    private var failed = false
    // Accessed exclusively on worker, including destruction.
    private var handle = 0L
    private var handleIsZenz32 = false
    @Volatile private var activation = 0L
    private val trainer = ZenzaiTrainer(app, ::modelFile, ::publish) {
        ui.post { if (enabled) onError() }
    }

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        activation++
        failed = false
        cancel()
        if (value) {
            useZenz32 = !(ZenzPlusModel.ready(app) && ZenzaiNative.available) && Zenz32Model.ready(app) && Zenz32Native.available
            nativeReady = useZenz32 || ZenzaiNative.available
            if (!nativeReady) { failed = true; onError() }
        } else {
            worker.execute(::release)
        }
        trainer.setEnabled(value && nativeReady && !useZenz32, activation)
    }

    fun cancel() {
        pending?.cancel(false)
        pending = null
        generation = if (!nativeReady) generation + 1 else if (useZenz32) Zenz32Native.cancel() else ZenzaiNative.cancel()
        ui.removeCallbacksAndMessages(null)
    }

    fun request(prompt: String, onResult: (MozcSession.Candidate) -> Unit) {
        cancel()
        if (!enabled || !nativeReady || failed) return
        val ticket = generation
        pending = worker.schedule({
            if (ticket != generation || !enabled) return@schedule
            try {
                if (handle == 0L) {
                    handleIsZenz32 = useZenz32
                    handle = if (handleIsZenz32) Zenz32Native.load(Zenz32Model.file(app).absolutePath) else ZenzaiNative.load(modelFile().absolutePath)
                }
                if (ticket != generation || !enabled) return@schedule
                val bytes = prompt.toByteArray(Charsets.UTF_8)
                val result = (if (useZenz32) Zenz32Native.generate(handle, bytes, ticket) else ZenzaiNative.generate(handle, bytes, 192, ticket))
                    ?.toString(Charsets.UTF_8)?.let(ZenzaiConversion::candidate)
                if (result != null) ui.post {
                    if (ticket == generation && enabled) onResult(result)
                }
            } catch (_: Exception) {
                ui.post {
                    if (ticket == generation && enabled && !failed) {
                        failed = true
                        onError()
                    }
                }
            }
        }, 120, TimeUnit.MILLISECONDS)
    }

    fun close() {
        trainer.close()
        enabled = false
        cancel()
        worker.execute(::release)
        worker.shutdown()
    }

    fun confirm(reading: String, preceding: String, output: String) {
        if (!enabled || !nativeReady || failed || useZenz32) return
        val prompt = ZenzaiConversion.prompt(reading, preceding) ?: return
        trainer.confirm(prompt, output)
    }

    fun syncDictionary(onEntries: (List<UserDictionary.Entry>) -> Unit) {
        if (useZenz32) {
            worker.execute {
                val entries = UserDictionary(app).entries()
                ui.post { if (enabled) onEntries(entries) }
            }
        } else trainer.dictionary { entries -> ui.post { if (enabled) onEntries(entries) } }
    }

    private fun publish(version: Long, epoch: Long) {
        if (worker.isShutdown) { ZenzaiNative.freeVersion(version); return }
        try {
            worker.execute {
                try {
                    if (enabled && activation == epoch) {
                        if (handle == 0L) handle = ZenzaiNative.load(modelFile().absolutePath)
                        ZenzaiNative.importVersion(handle, version)
                    }
                } finally { ZenzaiNative.freeVersion(version) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { ZenzaiNative.freeVersion(version) }
    }

    private fun release() {
        if (handle != 0L) { if (handleIsZenz32) Zenz32Native.free(handle) else ZenzaiNative.free(handle) }
        handle = 0L
    }

    @Synchronized private fun modelFile(): File {
        if (ZenzPlusModel.ready(app)) return ZenzPlusModel.file(app)
        val metadata = app.assets.open("zenzai/model.json").bufferedReader().use { JSONObject(it.readText()) }
        val hash = metadata.getString("sha256")
        require(hash.matches(Regex("[0-9a-f]{64}")))
        val dir = File(app.noBackupFilesDir, "zenzai").apply { mkdirs() }
        val model = File(dir, "$hash.gguf")
        if (model.isFile && model.length() == metadata.getLong("size")) return model
        val temporary = File(dir, "$hash.partial")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            app.assets.open("zenzai/zenzai-plus-ft-f16.gguf").use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            check(actual == hash && temporary.length() == metadata.getLong("size"))
            check(temporary.renameTo(model))
            dir.listFiles()?.filter { it != model && it.extension == "gguf" }?.forEach { it.delete() }
            return model
        } finally { temporary.delete() }
    }

    companion object {
        fun isBundled(context: Context): Boolean =
            (Zenz32Model.ready(context) && Zenz32Native.available) ||
                (ZenzPlusModel.ready(context) && ZenzaiNative.available) || runCatching { context.assets.open("zenzai/model.json").use { } }.isSuccess
    }
}

/** Exact JNI names are kept in proguard-rules.pro. All text crosses JNI as standard UTF-8. */
internal object ZenzaiNative {
    val available = runCatching { System.loadLibrary("zinna_zenzai") }.isSuccess
    external fun load(path: String): Long
    external fun generate(handle: Long, prompt: ByteArray, maxTokens: Int, generation: Long): ByteArray?
    external fun cancel(): Long
    external fun free(handle: Long)
    external fun train(handle: Long, document: ByteArray)
    external fun learnWord(handle: Long, prefix: ByteArray, output: ByteArray): Int
    external fun exportVersion(handle: Long): Long
    external fun importVersion(handle: Long, version: Long)
    external fun freeVersion(version: Long)
    external fun memoryFile(): Int
    external fun snapshot(handle: Long, fd: Int, cursor: Long)
    external fun restore(handle: Long, fd: Int): Long
}
