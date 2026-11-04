package me.zssu.ime.ime

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

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

/**
 * Compact review window, anchored to the bottom of a translucent screen.
 *
 * Opened from the keyboard's tool menu after [ZinnaImeService.startProofreading] has captured the
 * text and the apply callback. It lets the user fix up what was captured (or type by hand when
 * nothing could be grabbed), pick a rewrite style, preview a highlighted diff, and then apply,
 * discard or copy. A result whose inputs changed after it was generated is never applied — see
 * [generatedFrom]. Model downloads live in [ProofreadModelActivity].
 */
class ProofreadActivity : Activity() {

    /** The inputs a result was produced from, so a later edit can invalidate it. */
    private data class Snapshot(val text: String, val style: ProofreadPrompt.Style, val instruction: String)

    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    @Volatile private var busy = false
    private var style = ProofreadPrompt.Style.FIX
    private var output: String? = null
    private var outputHighlight: ProofreadDiff.Result? = null
    private var generatedFrom: Snapshot? = null
    private var error: String? = null
    private var progress: String? = null

    private lateinit var textField: EditText
    private lateinit var instructionField: EditText
    private lateinit var styleRow: LinearLayout
    private lateinit var resultSection: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var modelStatus: TextView
    private lateinit var actionRow: LinearLayout

    private val dark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val surface get() = Color.parseColor(if (dark) "#151820" else "#F7F8FC")
    private val fieldColor get() = Color.parseColor(if (dark) "#202634" else "#FFFFFF")
    private val inkColor get() = Color.parseColor(if (dark) "#EFF1F8" else "#202635")
    private val accent get() = Color.parseColor(if (dark) "#B8C7FF" else "#385AC5")
    private val onAccent get() = if (dark) Color.parseColor("#18254A") else Color.WHITE
    private val border get() = Color.parseColor(if (dark) "#3B4355" else "#D5DBE8")

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    private fun rounded(color: Int, radius: Int = 14) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun post(action: () -> Unit) = ui.post { if (!isFinishing && !isDestroyed) action() }
    private fun selectedSpec() = ProofreadModelStore.selected(this)
    private fun modelReady() = ProofreadModelStore.ready(this, selectedSpec())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ProofreadRequest.text == null || ProofreadRequest.apply == null) { finish(); return }
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.4f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 22)
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(panel)
        }
        // Anchor the panel to the bottom of a translucent window. A plain content view would be
        // MATCH_PARENT tall and stretch the panel to the top of the screen.
        val root = FrameLayout(this)
        root.addView(
            scroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            scroll.setPadding(bars.left, dp(8), bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        root.post {
            val cap = (resources.displayMetrics.heightPixels * 0.85f).toInt()
            if (scroll.height > cap) {
                scroll.layoutParams = (scroll.layoutParams as FrameLayout.LayoutParams).apply { height = cap }
            }
        }

        buildPanel(panel)
        renderStyleRow()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // The user may have downloaded the model while the review window was in the background.
        refresh()
    }

    private fun buildPanel(panel: LinearLayout) {
        panel.addView(label("AI 文章校正", 22f))
        panel.addView(label("文章は端末内で処理します。", 12f, dim = true))

        panel.addView(label("校正する文章（編集できます）", 15f))
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
        panel.addView(textField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        panel.addView(label("書き換えの種類", 15f))
        styleRow = LinearLayout(this)
        panel.addView(styleRow)
        instructionField = EditText(this).apply {
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
        panel.addView(instructionField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        modelStatus = label("", 12f, dim = true)
        panel.addView(modelStatus)

        statusText = label("", 12f, dim = true)
        panel.addView(statusText)

        panel.addView(label("校正結果（色付きが変更箇所）", 15f))
        resultSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(resultSection)

        actionRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(actionRow)

        panel.addView(button("閉じる", primary = false) {
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
            refresh()
        }
    }

    // ---- result + actions -----------------------------------------------------------------------

    private fun currentSnapshot(): Snapshot = Snapshot(
        text = ProofreadPrompt.sanitize(textField.text.toString()),
        style = style,
        instruction = ProofreadPrompt.sanitize(instructionField.text.toString()),
    )

    private fun isStale(): Boolean = output != null && generatedFrom != null && generatedFrom != currentSnapshot()

    private fun refresh() {
        if (!::actionRow.isInitialized) return
        val spec = selectedSpec()
        val ready = modelReady()
        modelStatus.text = if (ready) "モデル: ${spec.label}（取得済み）" else "モデル: ${spec.label}（未取得）"

        statusText.text = buildString {
            progress?.let { append(it) }
            error?.let { if (isNotEmpty()) append('\n'); append(it) }
        }
        statusText.visibility = if (statusText.text.isNullOrBlank()) View.GONE else View.VISIBLE

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
            if (result == generatedFrom?.text) resultSection.addView(label("今回の校正では変更がありませんでした。誤りがないことを保証するものではありません。", 12f, dim = true))
            if (isStale()) resultSection.addView(label("文章または指示を変更したため、この結果は古くなっています。もう一度校正してください。", 12f, dim = true))
        }

        actionRow.removeAllViews()
        actionRow.addView(button("校正する", primary = true, enabled = !busy && ready && ProofreadNative.available && text.isNotBlank() && withinLimit && instructionOk) {
            generate(spec)
        })
        if (!ready) actionRow.addView(label("校正するには「モデルを管理」からモデルをダウンロードしてください。", 12f, dim = true))
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
        if (busy) {
            actionRow.addView(button("中止", primary = false) {
                cancelled = true
                if (ProofreadNative.available) ProofreadNative.cancel()
                progress = "中止しています…"
                refresh()
            })
        }
        actionRow.addView(button(if (ready) "モデルを管理" else "モデルをダウンロード", primary = false) {
            startActivity(Intent(this, ProofreadModelActivity::class.java))
        })
    }

    private val onEdit = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) { refresh() }
    }

    private fun copyToClipboard(value: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("校正結果", value))
        Toast.makeText(this, "校正結果をコピーしました", Toast.LENGTH_SHORT).show()
    }

    // ---- inference ------------------------------------------------------------------------------

    private fun generate(spec: ProofreadModelStore.Spec) {
        val snapshot = currentSnapshot()
        if (snapshot.text.isBlank()) { error = "校正する文章を入力してください。"; refresh(); return }
        if (snapshot.text.length > ProofreadPrompt.MAX_TEXT) { error = "校正する文章は ${ProofreadPrompt.MAX_TEXT} 文字以内にしてください。"; refresh(); return }
        val modelFile = ProofreadModelStore.file(this, spec)
        busy = true; cancelled = false; error = null; output = null; outputHighlight = null; generatedFrom = null
        progress = "端末内で校正中…"; refresh()
        val prompt = ProofreadPrompt.prompt(snapshot.text, snapshot.style, snapshot.instruction, spec.format)
        val ticket = if (ProofreadNative.available) ProofreadNative.cancel() else 0L
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try {
                val raw = ProofreadNative.generate(modelFile.absolutePath, prompt.toByteArray(Charsets.UTF_8), ticket)?.toString(Charsets.UTF_8)
                val extracted = ProofreadPrompt.extract(raw.orEmpty())
                check(extracted.isNotBlank() && extracted.length <= 1800 && !extracted.contains('\uFFFD')) { "校正結果を取得できませんでした" }
                check(!ProofreadPrompt.hasUnexpectedReasoningSwitch(snapshot.text, extracted)) {
                    "結果に内部制御用の文字列が混ざったため破棄しました。元の文章は変更していません。"
                }
                val cleaned = if (snapshot.style == ProofreadPrompt.Style.FIX) ProofreadReview.apply(snapshot.text, extracted) else extracted
                check(cleaned.isNotBlank() && cleaned.length <= 1800 && !cleaned.contains('\uFFFD')) { "校正結果を取得できませんでした" }
                post {
                    busy = false; progress = null; output = cleaned
                    outputHighlight = ProofreadDiff.compare(snapshot.text, cleaned)
                    generatedFrom = snapshot
                    refresh()
                }
            } catch (e: Exception) {
                post { busy = false; progress = null; error = if (cancelled) "中止しました" else e.message ?: "校正に失敗しました"; refresh() }
            }
        }, "proofread-inference").start()
    }

    override fun onDestroy() {
        cancelled = true
        if (ProofreadNative.available) ProofreadNative.cancel()
        if (isFinishing && ProofreadRequest.result == null) ProofreadRequest.clear()
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
