package me.zssu.ime.ime

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** One in-process request; text is never written to disk or sent over the network. */
internal object ProofreadRequest {
    var text: String? = null
    var apply: ((String) -> Boolean)? = null
    var result: String? = null
    fun clear() { text = null; apply = null; result = null }
}
internal object ProofreadNative {
    val available = runCatching { System.loadLibrary("komorebi_proofread") }.isSuccess
    external fun cancel(): Long
    external fun generate(path: String, prompt: ByteArray, ticket: Long): ByteArray?
}

class ProofreadActivity : Activity() {
    private data class Model(val id: String, val label: String, val revision: String, val bytes: Long, val sha: String) {
        val filename get() = "Qwen3-$id-Q4_K_M.gguf"
        val url get() = "https://huggingface.co/unsloth/Qwen3-$id-GGUF/resolve/$revision/$filename"
    }
    private val models = listOf(
        Model("0.6B", "Qwen3 0.6B（約397 MB）", "50968a4468ef4233ed78cd7c3de230dd1d61a56b", 396705472, "ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a"),
        Model("1.7B", "Qwen3 1.7B（約1.11 GB）", "d7f544eead698dbd1f15126ef60b45a1e1933222", 1107409472, "b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897"),
    )
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    @Volatile private var busy = false
    private lateinit var container: LinearLayout
    private var output: String? = null
    private var error: String? = null
    private var progress: String? = null
    private var selected = 0
    private val dark get() = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
    private val surface get() = Color.parseColor(if (dark) "#151820" else "#F7F8FC")
    private val foreground get() = Color.parseColor(if (dark) "#EFF1F8" else "#202635")
    private val accent get() = Color.parseColor(if (dark) "#B8C7FF" else "#385AC5")
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun modelFile(m: Model) = File(noBackupFilesDir, "proofread/${m.filename}")
    private fun post(action: () -> Unit) = ui.post { if (!isFinishing && !isDestroyed) action() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ProofreadRequest.text == null || ProofreadRequest.apply == null) { finish(); return }
        val mem = ActivityManager.MemoryInfo().also { (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        val recommended = ProofreadRecommendation.select(mem.availMem, noBackupFilesDir.usableSpace)
        selected = getSharedPreferences("proofread", MODE_PRIVATE).getInt("model", recommended).coerceIn(0, 1)
        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(24)); setBackgroundColor(surface) }
        val scroll = ScrollView(this).apply { setBackgroundColor(surface); isFillViewport = true; addView(container) }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        render(recommended)
    }
    private fun label(text: CharSequence, size: Float = 14f) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(this@ProofreadActivity.foreground)
        setPadding(0, dp(6), 0, dp(6)); setLineSpacing(dp(3).toFloat(), 1f)
        if (size >= 19f) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private fun button(text: String, enabled: Boolean = true, click: () -> Unit) = Button(this).apply {
        this.text = text; isEnabled = enabled; isAllCaps = false; textSize = 14f
        val primary = text == "校正する" || text == "この結果を適用" || text == "モデルをダウンロード"
        val chosen = text.contains("選択中")
        val fill = if (primary) accent else if (chosen) Color.parseColor(if (dark) "#293653" else "#E8EEFF") else Color.TRANSPARENT
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(fill); cornerRadius = dp(14).toFloat()
            if (!primary) setStroke(dp(1), Color.parseColor(if (dark) "#3B4355" else "#D5DBE8"))
        }
        setTextColor(if (primary) { if (dark) Color.parseColor("#18254A") else Color.WHITE } else this@ProofreadActivity.foreground)
        alpha = if (enabled) 1f else 0.4f
        setPadding(dp(14), 0, dp(14), 0)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { setMargins(0, dp(5), 0, dp(5)) }
        setOnClickListener { click() }
    }
    private fun render(recommended: Int) {
        container.removeAllViews()
        container.addView(label("AI 文章校正", 24f))
        container.addView(label("文章は端末内で処理します。モデルの取得にはネット接続が必要です。"))
        for (i in models.indices) {
            val m = models[i]
            val mark = (if (i == recommended) " おすすめ" else "") + (if (i == selected) " ・選択中" else "") + (if (modelFile(m).length() == m.bytes) "（取得済み）" else "")
            container.addView(button(m.label + mark, !busy) { selected = i; output = null; error = null; progress = null
                getSharedPreferences("proofread", MODE_PRIVATE).edit().putInt("model", i).apply(); render(recommended) })
        }
        val model = models[selected]
        container.addView(label("Qwen3 / Apache 2.0 · Unsloth", 11f))
        if (modelFile(model).length() == model.bytes) container.addView(button("選択中のモデルを削除", !busy) {
            if (!modelFile(model).delete()) error = "モデルを削除できませんでした"
            else { output = null; error = null; progress = "モデルを削除しました" }
            render(recommended)
        })
        if (modelFile(model).length() != model.bytes) container.addView(button("モデルをダウンロード", !busy && ProofreadNative.available) { download(model, recommended) })
        if (!ProofreadNative.available) container.addView(label("この端末では校正ランタイムを利用できません。"))
        if (busy) container.addView(android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)).apply { setMargins(0, dp(12), 0, dp(8)) }
        })
        progress?.let { container.addView(label(it)) }
        error?.let { container.addView(label(it)) }
        val input = ProofreadRequest.text.orEmpty()
        val resultDiff = output?.let { ProofreadDiff.compare(input, it) }
        fun highlighted(pieces: List<ProofreadDiff.Piece>, background: Int): CharSequence {
            val full = pieces.joinToString("") { it.text }
            val span = SpannableString(full)
            var offset = 0
            for (piece in pieces) {
                val end = offset + piece.text.length
                if (piece.changed && end > offset) {
                    span.setSpan(BackgroundColorSpan(background), offset, end, 0)
                    span.setSpan(ForegroundColorSpan(Color.BLACK), offset, end, 0)
                }
                offset = end
            }
            return span
        }
        container.addView(label("元の文章", 19f))
        container.addView(label(resultDiff?.let { highlighted(it.before, Color.rgb(255, 190, 190)) } ?: input))
        output?.let { result ->
            container.addView(label("校正結果（色付きが変更箇所）", 19f))
            container.addView(label(highlighted(resultDiff!!.after, Color.rgb(255, 220, 120))))
            container.addView(button("この結果を適用", !busy) {
                ProofreadRequest.result = result
                finish()
            })
        }
        container.addView(button("校正する", !busy && modelFile(model).length() == model.bytes && ProofreadNative.available) { generate(model, input, recommended) })
        container.addView(button("閉じる", !busy) { ProofreadRequest.clear(); finish() })
        if (busy) container.addView(button("中止") { cancelled = true; if (ProofreadNative.available) ProofreadNative.cancel(); progress = "中止しています…"; render(recommended) })
    }
    private fun download(model: Model, recommended: Int) {
        busy = true; cancelled = false; error = null; progress = "ダウンロードを開始しています"; render(recommended)
        Thread({
            val target = modelFile(model); val temp = File(target.parentFile, target.name + ".part")
            try {
                check(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true)
                check(target.parentFile!!.usableSpace > model.bytes + 64L * 1024 * 1024) { "保存容量が不足しています" }
                val con = URL(model.url).openConnection() as HttpURLConnection
                try {
                    con.connectTimeout = 30000; con.readTimeout = 30000; con.instanceFollowRedirects = true
                    check(con.responseCode == 200) { "ダウンロードに失敗しました (${con.responseCode})" }
                    val digest = MessageDigest.getInstance("SHA-256"); var count = 0L; var notified = 0L
                    con.inputStream.use { input -> temp.outputStream().use { out ->
                        val b = ByteArray(65536)
                        while (true) { if (cancelled) throw InterruptedException(); val n = input.read(b); if (n < 0) break; count += n; check(count <= model.bytes); out.write(b, 0, n); digest.update(b, 0, n)
                            if (count - notified >= 8L * 1024 * 1024) { notified = count; val pct = count * 100 / model.bytes; post { progress = "ダウンロード中 $pct%"; render(recommended) } }
                        }
                    } }
                    check(count == model.bytes && digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == model.sha) { "モデルの検証に失敗しました" }
                    check(!cancelled); check(temp.renameTo(target)) { "保存に失敗しました" }
                } finally { con.disconnect() }
                post { busy = false; progress = "モデルの準備ができました"; render(recommended) }
            } catch (e: Exception) { temp.delete(); post { busy = false; progress = null; error = if (cancelled) "中止しました" else e.message ?: "ダウンロードに失敗しました"; render(recommended) } }
        }, "proofread-download").start()
    }
    private fun generate(model: Model, input: String, recommended: Int) {
        if (input.isBlank() || input.length > 600 || input.contains("<|im_")) { error = "校正する文章は 600 文字以内にしてください。"; render(recommended); return }
        busy = true; cancelled = false; error = null; output = null; progress = "端末内で校正中…"; render(recommended)
        val ticket = ProofreadNative.cancel()
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try {
                val prompt = "<|im_start|>system\nあなたは日本語の校正者です。意味や文体をできる限り保ち、誤字脱字と不自然な文法だけを修正してください。修正後の文章だけを返してください。<|im_end|>\n<|im_start|>user\n$input /no_think<|im_end|>\n<|im_start|>assistant\n"
                val raw = ProofreadNative.generate(modelFile(model).absolutePath, prompt.toByteArray(Charsets.UTF_8), ticket)?.toString(Charsets.UTF_8)
                val result = raw?.substringAfter("</think>", raw)?.trim().orEmpty()
                check(result.isNotBlank() && result.length <= 1800 && !result.contains('\uFFFD')) { "校正結果を取得できませんでした" }
                post { busy = false; progress = null; output = result; render(recommended) }
            } catch (e: Exception) { post { busy = false; progress = null; error = if (cancelled) "中止しました" else e.message ?: "校正に失敗しました"; render(recommended) } }
        }, "proofread-inference").start()
    }
    override fun onDestroy() { cancelled = true; if (ProofreadNative.available) ProofreadNative.cancel(); if (isFinishing && ProofreadRequest.result == null) ProofreadRequest.clear(); super.onDestroy() }
}
