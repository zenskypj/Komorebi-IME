package me.zssu.ime.keyboard

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import me.zssu.ime.R
import me.zssu.ime.ime.MozcSession
import me.zssu.ime.style.TextStyle
import me.zssu.ime.theme.KeyboardTheme

/**
 * Horizontal strip of conversion candidates.
 *
 * Built out of plain TextViews rather than a RecyclerView: a candidate list is short and fully
 * replaced on every keystroke, so view recycling buys nothing and costs an adapter's worth of
 * latency on the hottest path in the app.
 */
@SuppressLint("ViewConstructor")
class CandidateStripView(context: Context) : HorizontalScrollView(context) {

    enum class ToolAction { CLIPBOARD, EMOJI, ONE_HAND_CYCLE, SETTINGS, AI_STYLE }
    enum class OneHandDisplayMode { FULL, LEFT, RIGHT }
    enum class EmojiPage { RECENT, EMOJI, KAOMOJI, FAVORITE }

    fun interface OnCandidateSelectedListener {
        fun onCandidateSelected(candidate: MozcSession.Candidate)
    }

    var listener: OnCandidateSelectedListener? = null
    var onCandidateLongPressed: ((MozcSession.Candidate) -> Unit)? = null
    var onToolAction: ((ToolAction) -> Unit)? = null
    var onClipboardItemSelected: ((String) -> Unit)? = null
    var onClipboardHistoryCleared: (() -> Unit)? = null

    var theme: KeyboardTheme = KeyboardTheme.Default
        set(value) {
            field = value
            for (i in 0 until container.childCount) {
                applyTheme(container.getChildAt(i) as? TextView ?: continue)
            }
            invalidate()
        }

    /** Which chip currently carries the focus highlight, so only the two that change are touched. */
    private var focusedChild = -1

    /** Texts of the last rendered candidate list; scroll resets only when content changes. */
    private var lastCandidateTexts: List<String> = emptyList()

    var oneHandMode: OneHandDisplayMode = OneHandDisplayMode.FULL

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private var currentEmojiPage = EmojiPage.RECENT

    init {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        addView(
            container,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT),
        )
        // Transparent so the panel behind — colour or the user's background image — shows through.
    }

    /**
     * Replaces the visible candidates.
     *
     * Reuses the chips already on screen instead of rebuilding them. This runs on every keystroke,
     * and constructing a TextView is not cheap — it resolves attributes, allocates layout state and
     * forces a full measure pass. Tearing down twenty of them and building twenty more per key was
     * enough to make fast typing feel like the keyboard was falling behind.
     *
     * Each property is written only when it actually differs: TextView.setText and setBackground
     * invalidate unconditionally, so assigning the same value still costs a layout pass.
     */
    fun setCandidates(candidates: List<MozcSession.Candidate>, focusedIndex: Int) {
        if (candidates.isEmpty()) {
            showTools()
            return
        }
        setContainerFillWidth(false)
        // Tear down any non-chip children left by showTools / showEmoji.
        for (i in container.childCount - 1 downTo 0) {
            if (container.getChildAt(i) !is TextView) container.removeViewAt(i)
        }
        while (container.childCount > candidates.size) {
            container.removeViewAt(container.childCount - 1)
        }
        while (container.childCount < candidates.size) {
            container.addView(
                newChip(),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        candidates.forEachIndexed { index, candidate ->
            val view = container.getChildAt(index) as TextView
            view.tag = candidate
            val label = when {
                candidate.priorityMatch != me.zssu.ime.ime.PriorityMatch.NONE -> candidate.text
                candidate.correction -> "補正 ${candidate.text}"
                else -> candidate.text
            }
            if (view.text != label) view.text = label
            val star = if (candidate.priorityMatch != me.zssu.ime.ime.PriorityMatch.NONE) ContextCompat.getDrawable(context, R.drawable.ic_ime_star)?.mutate()?.apply {
                setTint(theme.candidateTextColor); val size = (18 * resources.displayMetrics.density).toInt(); setBounds(0, 0, size, size)
            } else null
            view.setCompoundDrawablesRelative(star, null, null, null)
            view.compoundDrawablePadding = (4 * resources.displayMetrics.density).toInt()
            view.contentDescription = if (star != null) "最優先 ${candidate.text}" else label
        }

        if (focusedChild != focusedIndex) {
            highlight(focusedChild, false)
            highlight(focusedIndex, true)
            focusedChild = focusedIndex
        }
        val newTexts = candidates.map { it.text }
        if (newTexts != lastCandidateTexts) {
            scrollTo(0, 0)
            lastCandidateTexts = newTexts
        }
    }

    private fun highlight(index: Int, on: Boolean) {
        if (index < 0 || index >= container.childCount) return
        val view = container.getChildAt(index) ?: return
        if (on) view.setBackgroundColor(theme.keyPressedColor) else view.background = null
    }

    private fun newChip(): TextView = TextView(context).apply {
        gravity = Gravity.CENTER
        isSingleLine = true
        applyTheme(this)
        setOnClickListener { clicked ->
            if (theme.hapticFeedback) {
                clicked.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            (clicked.tag as? MozcSession.Candidate)?.let { listener?.onCandidateSelected(it) }
        }
        setOnLongClickListener { clicked ->
            if (theme.hapticFeedback) {
                clicked.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
            val candidate = clicked.tag as? MozcSession.Candidate
            if (candidate != null) {
                onCandidateLongPressed?.invoke(candidate)
            }
            true
        }
    }

    private fun applyTheme(view: TextView) {
        val padding = (12 * resources.displayMetrics.density).toInt()
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.labelSizeSp)
        view.setTextColor(theme.candidateTextColor)
        view.setPadding(padding, padding / 2, padding, padding / 2)
    }

    fun clear() = setCandidates(emptyList(), -1)

    fun showTools() {
        focusedChild = -1
        lastCandidateTexts = emptyList()
        container.removeAllViews()
        setContainerFillWidth(true)
        scrollTo(0, 0)
        container.addView(
            iconButton(R.drawable.ic_ai_style, "文体補正", true) {
                onToolAction?.invoke(ToolAction.AI_STYLE)
            }
        )
        container.addView(
            iconButton(R.drawable.ic_material_content_paste, "クリップボードを貼り付け", true) {
                onToolAction?.invoke(ToolAction.CLIPBOARD)
            }
        )
        container.addView(
            iconButton(R.drawable.ic_material_face, "絵文字", true) {
                onToolAction?.invoke(ToolAction.EMOJI)
            }
        )
        container.addView(
            oneHandButton(expand = true) {
                onToolAction?.invoke(ToolAction.ONE_HAND_CYCLE)
            }
        )
        container.addView(
            iconButton(R.drawable.ic_material_settings, "設定", true) {
                onToolAction?.invoke(ToolAction.SETTINGS)
            }
        )
    }

    fun showStyleSelector() {
        focusedChild = -1
        container.removeAllViews()
        setContainerFillWidth(false)
        scrollTo(0, 0)
        container.addView(
            iconButton(R.drawable.ic_material_arrow_back, "ツールバーに戻る") { showTools() }
        )
        for (style in TextStyle.entries) {
            container.addView(TextView(context).apply {
                text = style.label
                applyTheme(this)
                gravity = Gravity.CENTER
                isSingleLine = true
                setOnClickListener {
                    if (theme.hapticFeedback) {
                        it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                    onStyleSelected?.invoke(style)
                }
            })
        }
    }

    fun showClipboardHistory(items: List<String>) {
        focusedChild = -1
        container.removeAllViews()
        setContainerFillWidth(false)
        scrollTo(0, 0)
        val padding = (12 * resources.displayMetrics.density).toInt()
        container.addView(
            iconButton(R.drawable.ic_material_arrow_back, "ツールバーに戻る") { showTools() }
        )
        if (items.isEmpty()) {
            container.addView(TextView(context).apply {
                text = "履歴はありません"
                applyTheme(this)
                gravity = Gravity.CENTER
                isSingleLine = true
            })
            return
        }
        container.addView(TextView(context).apply {
            text = "すべて消去"
            applyTheme(this)
            gravity = Gravity.CENTER
            isSingleLine = true
            contentDescription = "クリップボード履歴をすべて消去"
            setOnClickListener {
                if (theme.hapticFeedback) {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                onClipboardHistoryCleared?.invoke()
            }
        })
        items.forEach { value ->
            container.addView(TextView(context).apply {
                text = value.replace(Regex("\\s+"), " ")
                applyTheme(this)
                gravity = Gravity.CENTER
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = (240 * resources.displayMetrics.density).toInt()
                contentDescription = "クリップボード履歴: $text"
                setOnClickListener {
                    if (theme.hapticFeedback) {
                        it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                    onClipboardItemSelected?.invoke(value)
                }
            })
        }
    }

    fun showEmoji(
        recents: List<String>,
        favorites: List<String>,
        page: EmojiPage? = null,
    ) {
        val selectedPage = page ?: currentEmojiPage
        currentEmojiPage = selectedPage
        container.removeAllViews()
        setContainerFillWidth(false)
        scrollTo(0, 0)
        val padding = (12 * resources.displayMetrics.density).toInt()
        val pages = listOf(
            EmojiPage.RECENT to "最近",
            EmojiPage.EMOJI to "絵文字",
            EmojiPage.KAOMOJI to "顔文字",
            EmojiPage.FAVORITE to "お気に入り",
        )
        pages.forEach { (target, label) ->
            container.addView(TextView(context).apply {
                text = if (target == EmojiPage.FAVORITE) "" else label
                contentDescription = label
                if (target == EmojiPage.FAVORITE) {
                    val star = ContextCompat.getDrawable(context, R.drawable.ic_ime_star)?.mutate()
                    star?.setTint(theme.candidateTextColor)
                    star?.setBounds(0, 0, (20 * resources.displayMetrics.density).toInt(), (20 * resources.displayMetrics.density).toInt())
                    setCompoundDrawablesRelative(star, null, null, null)
                }
                if (target == selectedPage) setBackgroundColor(theme.keyPressedColor)
                setTextColor(theme.candidateTextColor)
                setPadding(padding, padding / 2, padding, padding / 2)
                gravity = Gravity.CENTER
                setOnClickListener { showEmoji(recents, favorites, target) }
            })
        }
        val values = when (selectedPage) {
            EmojiPage.RECENT -> recents.ifEmpty { EmojiRepository.EMOJI.take(10) }
            EmojiPage.EMOJI -> EmojiRepository.EMOJI
            EmojiPage.KAOMOJI -> EmojiRepository.KAOMOJI
            EmojiPage.FAVORITE -> favorites
        }
        values.forEach { emoji ->
            container.addView(TextView(context).apply {
                text = emoji
                contentDescription = if (emoji in favorites) "$emoji お気に入り" else emoji
                if (emoji in favorites) {
                    val star = ContextCompat.getDrawable(context, R.drawable.ic_ime_star)?.mutate()
                    star?.setTint(theme.candidateTextColor)
                    star?.setBounds(0, 0, (14 * resources.displayMetrics.density).toInt(), (14 * resources.displayMetrics.density).toInt())
                    setCompoundDrawablesRelative(null, null, star, null)
                }
                setTextColor(theme.candidateTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.labelSizeSp + 4f)
                setPadding(padding, padding / 2, padding, padding / 2)
                gravity = Gravity.CENTER
                setOnClickListener { onEmojiSelected?.invoke(emoji) }
                setOnLongClickListener {
                    onEmojiFavoriteToggled?.invoke(emoji)
                    true
                }
            })
        }
        container.addView(
            iconButton(R.drawable.ic_material_arrow_back, "ツールバーに戻る") { showTools() }
        )
    }

    var onEmojiSelected: ((String) -> Unit)? = null
    var onEmojiFavoriteToggled: ((String) -> Unit)? = null

    /**
     * Reuses the strip as a compact candidate-inspector, avoiding a dialog window above the IME.
     */
    fun showCandidateDetails(
        candidate: MozcSession.Candidate,
        definitions: List<String>,
    ) {
        container.removeAllViews()
        setContainerFillWidth(false)
        scrollTo(0, 0)
        val padding = (12 * resources.displayMetrics.density).toInt()
        container.addView(
            iconButton(R.drawable.ic_material_arrow_back, "候補一覧に戻る") {
                onCandidateDetailsClosed?.invoke()
            }
        )
        container.addView(TextView(context).apply {
            text = buildString {
                append(candidate.text)
                if (definitions.isNotEmpty()) {
                    append("　")
                    append(definitions.joinToString(" / "))
                } else {
                    append("　意味辞書には登録されていません")
                }
            }
            setTextColor(theme.candidateTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.labelSizeSp)
            setPadding(padding, padding / 2, padding, padding / 2)
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
        })
        if (candidate.deletable) {
            container.addView(TextView(context).apply {
                text = "学習削除"
                setTextColor(theme.candidateTextColor)
                setPadding(padding, padding / 2, padding, padding / 2)
                gravity = Gravity.CENTER
                setOnClickListener { onCandidateDeleteRequested?.invoke(candidate) }
            })
        }
    }

    var onCandidateDeleteRequested: ((MozcSession.Candidate) -> Unit)? = null
    var onCandidateDetailsClosed: (() -> Unit)? = null
    var onStyleSelected: ((TextStyle) -> Unit)? = null

    private fun iconButton(
        @DrawableRes icon: Int,
        description: String,
        expand: Boolean = false,
        action: () -> Unit,
    ): ImageButton = ImageButton(context).apply {
        setImageDrawable(ContextCompat.getDrawable(context, icon))
        imageTintList = ColorStateList.valueOf(theme.candidateTextColor)
        contentDescription = description
        background = null
        val padding = (12 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setOnClickListener {
            if (theme.hapticFeedback) it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            action()
        }
        layoutParams = toolbarItemLayoutParams(expand)
    }

    private fun oneHandButton(
        expand: Boolean = false,
        action: () -> Unit,
    ): ImageButton = ImageButton(context).apply {
        setImageDrawable(
            OneHandModeDrawable(
                oneHandMode,
                theme.candidateTextColor,
                resources.displayMetrics.density,
            )
        )
        contentDescription = when (oneHandMode) {
            OneHandDisplayMode.FULL -> "片手モード: 全幅。タップして左寄せ"
            OneHandDisplayMode.LEFT -> "片手モード: 左寄せ。タップして右寄せ"
            OneHandDisplayMode.RIGHT -> "片手モード: 右寄せ。タップして全幅"
        }
        background = null
        val padding = (11 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setOnClickListener {
            if (theme.hapticFeedback) it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            action()
        }
        layoutParams = toolbarItemLayoutParams(expand)
    }

    private fun toolbarItemLayoutParams(expand: Boolean) =
        if (expand) {
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f,
            )
        } else {
            LinearLayout.LayoutParams(
                (56 * resources.displayMetrics.density).toInt(),
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
        }

    private fun setContainerFillWidth(fill: Boolean) {
        val width = if (fill) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT
        if (container.layoutParams.width != width) {
            container.layoutParams = LayoutParams(width, LayoutParams.MATCH_PARENT)
        }
    }
}
