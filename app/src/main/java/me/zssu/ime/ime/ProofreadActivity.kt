package me.zssu.ime.ime

import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** One in-process request; text is never written to disk or sent over the network. */
internal object ProofreadRequest {
    var text: String? = null
    var apply: ((String) -> Boolean)? = null
    var result: String? = null

    /** True when the screen was opened only to manage the model, from Settings. */
    var modelManagement: Boolean = false
    fun clear() { text = null; apply = null; result = null; modelManagement = false }
}

internal object ProofreadNative {
    val available = runCatching { System.loadLibrary("komorebi_proofread") }.isSuccess
    external fun cancel(): Long
    external fun generate(path: String, prompt: ByteArray, ticket: Long): ByteArray?
}

/**
 * Compact, bottom-anchored review window.
 *
 * Everything happens on the device: the model is a downloaded GGUF and inference runs through
 * [ProofreadNative]. The screen lets the user fix up what was captured (or type the text by hand
 * when nothing could be grabbed), pick what kind of rewrite they want, preview a highlighted diff,
 * and then apply, discard or copy the result. It deliberately does not apply a result whose inputs
 * changed after it was generated — see [generatedFrom].
 */
class ProofreadActivity : Activity() {

    private data class Model(val id: String, val label: String, val revision: String, val bytes: Long, val sha: String) {
        val filename get() = "Qwen3-$id-Q4_K_M.gguf"
        val url get() = "https://huggingface.co/unsloth/Qwen3-$id-GGUF/resolve/$revision/$filename"
    }

    /** The inputs a result was produced from, so a later edit can invalidate it. */
    private data class Snapshot(val text: String, val style: ProofreadPrompt.Style, val instruction: String)

    private val models = listOf(
        Model("0.6B", "Qwen3 0.6B（約397 MB）", "50968a4468ef4233ed78cd7c3de230dd1d61a56b", 396705472, "ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a"),
        Model("1.7B", "Qwen3 1.7B（約1.11 GB）", "d7f544eead698dbd1f15126ef60b45a1e1933222", 1107409472, "b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897"),
    )

    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    @Volatile private var busy = false
    private var recommended = 0
    private var selected = 0
    private var style = ProofreadPrompt.Style.FIX
    private var output: String? = null
    private var outputHighlight: ProofreadDiff.Result? = null
    private var generatedFrom: Snapshot? = null
    private var error: String? = null
    private var progress: String? = null
    private var modelExpanded = false

    private lateinit var textField: EditText
    private lateinit var instructionField: EditText
    private lateinit var modelSection: LinearLayout
    private lateinit var styleRow: LinearLayout
    private lateinit var resultSection: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var actionRow: LinearLayout

    private val reviewMode get() = !ProofreadRequest.modelManagement

    private val dark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val surface get() = Color.parseColor(if (dark) "#151820" else "#F7F8FC")
    private val fieldColor get() = Color.parseColor(if (dark) "#202634" else "#FFFFFF")
    private val inkColor get() = Color.parseColor(if (dark) "#EFF1F8" else "#202635")
    private val accent get() = Color.parseColor(if (dark) "#B8C7FF" else "#385AC5")
    private val onAccent get() = if (dark) Color.parseColor("#18254A") else Color.WHITE
    private val border get() = Color.parseColor(if (dark) "#3B4355" else "#D5DBE8")

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun modelFile(m: Model) = File(noBackupFilesDir, "proofread/${m.filename}")
    private fun modelReady(m: Model) = modelFile(m).length() == m.bytes
    private fun post(action: () -> Unit) = ui.post { if (!isFinishing && !isDestroyed) action() }
    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun rounded(color: Int, radius: Int = 14) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (reviewMode && (ProofreadRequest.text == null || ProofreadRequest.apply == null)) { finish(); return }
        modelExpanded = ProofreadRequest.modelManagement
        val memory = ActivityManager.MemoryInfo().also {
            (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }
        recommended = ProofreadRecommendation.select(memory.availMem, noBackupFilesDir.usableSpace)
        selected = getSharedPreferences("proofread", MODE_PRIVATE).getInt("model", recommended).coerceIn(0, 1)

        window?.apply {
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.35f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 22)
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(container)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, dp(8), bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        scroll.post {
            val cap = (resources.displayMetrics.heightPixels * 0.82f).toInt()
            if (scroll.height > cap) {
                scroll.layoutParams = scroll.layoutParams.apply { height = cap }
            }
        }

        buildContainer(container)
        if (reviewMode) renderStyleRow()
        renderModelSection()
        refreshResult()
    }

    private fun buildContainer(container: LinearLayout) {
        container.addView(label(if (reviewMode) "AI 文章校正" else "AI 文章校正モデル", 22f))
        container.addView(label("文章は端末内で処理します。モデルの取得にはネット接続が必要です。", 12f, dim = true))

        if (reviewMode) {
            container.addView(label("校正する文章（編集できます）", 15f))
            textField = EditText(this).apply {
                setText(ProofreadRequest.text.orEmpty())
                setSelection(text.length)
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 3
                maxLines = 7
                textSize = 15f
                // Do not let the IME build a personalization profile from this scratch field.
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                setTextColor(inkColor)
                setHintTextColor(withAlpha(inkColor, 0x66))
                hint = "文章を取得できないときは、ここに入力してください"
                background = rounded(fieldColor, 12)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                addTextChangedListener(onEdit)
            }
            container.addView(textField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            container.addView(label("書き換えの種類", 15f))
            styleRow = LinearLayout(this)
            container.addView(styleRow)
            instructionField = EditText(this).apply {
                setText("")
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 2
                maxLines = 4
                textSize = 15f
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                setTextColor(inkColor)
                setHintTextColor(withAlpha(inkColor, 0x66))
                hint = "例: やわらかい口調にして、敬語を外して…"
                background = rounded(fieldColor, 12)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                addTextChangedListener(onEdit)
            }
            container.addView(instructionField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        container.addView(label("モデル", 15f))
        modelSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(modelSection)

        statusText = label("", 12f, dim = true)
        container.addView(statusText)

        if (reviewMode) {
            container.addView(label("校正結果（色付きが変更箇所）", 15f))
            resultSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            container.addView(resultSection)
            actionRow = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            container.addView(actionRow)
        } else {
            resultSection = LinearLayout(this)
            actionRow = LinearLayout(this)
        }

        container.addView(button(if (reviewMode) "閉じる" else "閉じる", primary = false) {
            ProofreadRequest.result = null
            finish()
        })
    }

    // ---- style selector -------------------------------------------------------------------------

    private fun renderStyleRow() {
        styleRow.removeAllViews()
        val firstRow = LinearLayout(this)
        ProofreadPrompt.Style.entries.take(4).forEach { firstRow.addView(styleButton(it)) }
        styleRow.addView(firstRow)
        val secondRow = LinearLayout(this)
        secondRow.addView(styleButton(ProofreadPrompt.Style.FREE))
        styleRow.addView(secondRow)
        instructionField.visibility = if (style == ProofreadPrompt.Style.FREE) View.VISIBLE else View.GONE
    }

    private fun styleButton(candidate: ProofreadPrompt.Style): Button = Button(this).apply {
        text = candidate.label
        isAllCaps = false
        textSize = 13f
        val chosen = candidate == style
        background = GradientDrawable().apply {
            setColor(if (chosen) accent else Color.TRANSPARENT)
            cornerRadius = dp(12).toFloat()
            if (!chosen) setStroke(dp(1), border)
        }
        setTextColor(if (chosen) onAccent else inkColor)
        setPadding(dp(8), 0, dp(8), 0)
        layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
        setOnClickListener {
            if (busy) return@setOnClickListener
            style = candidate
            renderStyleRow()
            refreshResult()
        }
    }

    // ---- model section --------------------------------------------------------------------------

    private fun renderModelSection() {
        modelSection.removeAllViews()
        val model = models[selected]
        val ready = modelReady(model)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("現在: ${model.label}${if (ready) "（取得済み）" else "（未取得）"}", 13f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(button(if (modelExpanded) "閉じる" else "管理", primary = false) { modelExpanded = !modelExpanded; renderModelSection() })
        modelSection.addView(header)
        if (!modelExpanded) return

        for (i in models.indices) {
            val m = models[i]
            val mark = (if (i == recommended) " おすすめ" else "") + (if (i == selected) " ・選択中" else "")
            modelSection.addView(button(m.label + mark, primary = false, enabled = !busy) {
                selected = i; output = null; error = null; progress = null
                getSharedPreferences("proofread", MODE_PRIVATE).edit().putInt("model", i).apply()
                renderModelSection(); refreshResult()
            })
        }
        modelSection.addView(label("Qwen3 / Apache 2.0 · Unsloth。zenzai-plus（変換用）とは別のモデルです。", 11f, dim = true))
        if (ready) modelSection.addView(button("選択中のモデルを削除", primary = false, enabled = !busy) {
            if (!modelFile(model).delete()) error = "モデルを削除できませんでした"
            else { output = null; error = null; progress = "モデルを削除しました" }
            renderModelSection(); refreshResult()
        })
        if (!ready) modelSection.addView(button("モデルをダウンロード", primary = true, enabled = !busy && ProofreadNative.available) {
            download(model)
        })
        if (!ProofreadNative.available) modelSection.addView(label("この端末では校正ランタイムを利用できません（arm64 専用）。", 12f, dim = true))
        if (busy) modelSection.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).apply { setMargins(0, dp(10), 0, dp(6)) }
        })
    }

    // ---- result + actions -----------------------------------------------------------------------

    private fun currentSnapshot(): Snapshot = Snapshot(
        text = ProofreadPrompt.sanitize(textField.text.toString()),
        style = style,
        instruction = ProofreadPrompt.sanitize(instructionField.text.toString()),
    )

    private fun isStale(): Boolean = output != null && generatedFrom != null && generatedFrom != currentSnapshot()

    private fun refreshResult() {
        statusText.text = buildString {
            progress?.let { append(it) }
            error?.let { if (isNotEmpty()) append('\n'); append(it) }
        }
        statusText.visibility = if (statusText.text.isNullOrBlank()) View.GONE else View.VISIBLE
        if (!reviewMode) return

        val ready = modelReady(models[selected])
        val text = textField.text.toString()
        val withinLimit = ProofreadPrompt.textWithinLimit(text)
        val instructionOk = style != ProofreadPrompt.Style.FREE || ProofreadPrompt.instructionWithinLimit(instructionField.text.toString())
        if (text.isNotBlank() && !withinLimit) {
            statusText.visibility = View.VISIBLE
            statusText.text = "校正する文章は ${ProofreadPrompt.MAX_TEXT} 文字以内にしてください。"
        } else if (style == ProofreadPrompt.Style.FREE && !instructionOk) {
            statusText.visibility = View.VISIBLE
            statusText.text = "指示は ${ProofreadPrompt.MAX_INSTRUCTION} 文字以内にしてください。"
        }

        resultSection.removeAllViews()
        val result = output
        if (result != null) {
            // Diff against the text the result was actually produced from, so the highlight stays
            // meaningful even after the source field is edited.
            val diff = outputHighlight ?: generatedFrom?.let { ProofreadDiff.compare(it.text, result) }
            if (diff != null) {
                resultSection.addView(label(highlighted(diff.before, Color.rgb(255, 190, 190))))
                resultSection.addView(label(highlighted(diff.after, Color.rgb(255, 220, 120))))
            }
            if (isStale()) resultSection.addView(label("文章または指示を変更したため、この結果は古くなっています。もう一度校正してください。", 12f, dim = true))
        }

        actionRow.removeAllViews()
        if (reviewMode) {
            actionRow.addView(button("校正する", primary = true, enabled = !busy && ready && ProofreadNative.available && text.isNotBlank() && withinLimit && instructionOk) {
                generate(models[selected])
            })
            if (result != null) {
                actionRow.addView(button("適用", primary = true, enabled = !busy && !isStale()) {
                    ProofreadRequest.result = result
                    finish()
                })
                actionRow.addView(button("適用しない", primary = false, enabled = !busy) {
                    ProofreadRequest.result = null
                    finish()
                })
                actionRow.addView(button("コピー", primary = false, enabled = !busy) { copyToClipboard(result) })
            }
            if (busy) actionRow.addView(button("中止", primary = false) {
                cancelled = true
                if (ProofreadNative.available) ProofreadNative.cancel()
                progress = "中止しています…"
                refreshResult()
            })
            actionRow.addView(button("モデルを準備", primary = false, enabled = !busy && !ready) {
                modelExpanded = true; renderModelSection()
            })
        }
    }

    private val onEdit = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) { refreshResult() }
    }

    private fun copyToClipboard(value: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("校正結果", value))
        Toast.makeText(this, "校正結果をコピーしました", Toast.LENGTH_SHORT).show()
    }

    // ---- inference ------------------------------------------------------------------------------

    private fun generate(model: Model) {
        val snapshot = currentSnapshot()
        if (snapshot.text.isBlank()) { error = "校正する文章を入力してください。"; refreshResult(); return }
        if (snapshot.text.length > ProofreadPrompt.MAX_TEXT) { error = "校正する文章は ${ProofreadPrompt.MAX_TEXT} 文字以内にしてください。"; refreshResult(); return }
        busy = true; cancelled = false; error = null; output = null; outputHighlight = null; generatedFrom = null
        progress = "端末内で校正中…"; refreshResult()
        val prompt = ProofreadPrompt.prompt(snapshot.text, snapshot.style, snapshot.instruction)
        val ticket = if (ProofreadNative.available) ProofreadNative.cancel() else 0L
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try {
                val raw = ProofreadNative.generate(modelFile(model).absolutePath, prompt.toByteArray(Charsets.UTF_8), ticket)?.toString(Charsets.UTF_8)
                val cleaned = ProofreadPrompt.sanitize(raw?.substringAfter("</think>", raw).orEmpty())
                check(cleaned.isNotBlank() && cleaned.length <= 1800 && !cleaned.contains('\uFFFD')) { "校正結果を取得できませんでした" }
                post {
                    busy = false; progress = null; output = cleaned
                    outputHighlight = ProofreadDiff.compare(snapshot.text, cleaned)
                    generatedFrom = snapshot
                    refreshResult()
                }
            } catch (e: Exception) {
                post { busy = false; progress = null; error = if (cancelled) "中止しました" else e.message ?: "校正に失敗しました"; refreshResult() }
            }
        }, "proofread-inference").start()
    }

    private fun download(model: Model) {
        busy = true; cancelled = false; error = null; progress = "ダウンロードを開始しています"; renderModelSection(); refreshResult()
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
                        while (true) {
                            if (cancelled) throw InterruptedException()
                            val n = input.read(b); if (n < 0) break
                            count += n; check(count <= model.bytes); out.write(b, 0, n); digest.update(b, 0, n)
                            if (count - notified >= 8L * 1024 * 1024) { notified = count; post { progress = "ダウンロード中 ${count * 100 / model.bytes}%"; renderModelSection(); refreshResult() } }
                        }
                    } }
                    check(count == model.bytes && digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == model.sha) { "モデルの検証に失敗しました" }
                    check(!cancelled); check(temp.renameTo(target)) { "保存に失敗しました" }
                } finally { con.disconnect() }
                post { busy = false; progress = "モデルの準備ができました"; renderModelSection(); refreshResult() }
            } catch (e: Exception) {
                temp.delete()
                post { busy = false; progress = null; error = if (cancelled) "中止しました" else e.message ?: "ダウンロードに失敗しました"; renderModelSection(); refreshResult() }
            }
        }, "proofread-download").start()
    }

    override fun onDestroy() {
        cancelled = true
        if (ProofreadNative.available) ProofreadNative.cancel()
        if (isFinishing && ProofreadRequest.result == null && !ProofreadRequest.modelManagement) ProofreadRequest.clear()
        super.onDestroy()
    }

    // ---- small view helpers ---------------------------------------------------------------------

    private fun label(text: CharSequence, size: Float = 14f, dim: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(if (dim) withAlpha(inkColor, 0x99) else inkColor)
        setPadding(0, dp(8), 0, dp(4))
        setLineSpacing(dp(3).toFloat(), 1f)
        if (size >= 19f) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun button(text: String, primary: Boolean, enabled: Boolean = true, click: () -> Unit) = Button(this).apply {
        this.text = text
        isEnabled = enabled
        isAllCaps = false
        textSize = 14f
        background = GradientDrawable().apply {
            setColor(if (primary) accent else Color.TRANSPARENT)
            cornerRadius = dp(14).toFloat()
            if (!primary) setStroke(dp(1), border)
        }
        setTextColor(if (primary) onAccent else inkColor)
        alpha = if (enabled) 1f else 0.4f
        setPadding(dp(14), 0, dp(14), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { setMargins(0, dp(4), 0, dp(4)) }
        setOnClickListener { if (isEnabled) click() }
    }

    private fun highlighted(pieces: List<ProofreadDiff.Piece>, background: Int): CharSequence {
        val full = SpannableString(pieces.joinToString("") { it.text })
        var offset = 0
        for (piece in pieces) {
            val end = offset + piece.text.length
            if (piece.changed && end > offset) {
                full.setSpan(BackgroundColorSpan(background), offset, end, 0)
                full.setSpan(ForegroundColorSpan(Color.BLACK), offset, end, 0)
            }
            offset = end
        }
        return full
    }
}
