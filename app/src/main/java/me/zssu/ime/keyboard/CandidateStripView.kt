package me.zssu.ime.keyboard

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.GridView
import android.widget.BaseAdapter
import android.view.ViewGroup
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
    private var currentEmojiGroup: String? = null
    private var normalHeight = 0

    private fun paletteHeight(expanded: Boolean) {
        val params = layoutParams ?: return
        if (expanded) {
            if (normalHeight == 0) normalHeight = params.height
            params.height = (210 * resources.displayMetrics.density).toInt()
        } else if (normalHeight != 0) {
            params.height = normalHeight
            normalHeight = 0
        } else return
        layoutParams = params
    }

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
        paletteHeight(false)
        if (candidates.isEmpty()) {
            showTools()
            return
        }
        if (me.zssu.ime.settings.ImeSettings(context).candidateRows == 2) {
            showTwoRows(candidates, focusedIndex)
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

    private fun showTwoRows(candidates: List<MozcSession.Candidate>, focusedIndex: Int) {
        setContainerFillWidth(false)
        container.removeAllViews()
        focusedChild = -1
        candidates.chunked(2).forEachIndexed { columnIndex, group ->
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            group.forEachIndexed { row, candidate ->
                val chip = newChip()
                chip.tag = candidate
                val label = if (candidate.correction) "補正 ${candidate.text}" else candidate.text
                chip.text = label
                if (candidate.priorityMatch != me.zssu.ime.ime.PriorityMatch.NONE) {
                    val star = ContextCompat.getDrawable(context, R.drawable.ic_ime_star)?.mutate()?.apply {
                        setTint(theme.candidateTextColor)
                        val size = (18 * resources.displayMetrics.density).toInt()
                        setBounds(0, 0, size, size)
                    }
                    chip.setCompoundDrawablesRelative(star, null, null, null)
                    chip.contentDescription = "最優先 ${candidate.text}"
                } else chip.contentDescription = label
                if (columnIndex * 2 + row == focusedIndex) chip.setBackgroundColor(theme.keyPressedColor)
                column.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            }
            if (group.size == 1) column.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
            container.addView(column, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        val texts = candidates.map { it.text }
        if (texts != lastCandidateTexts) { scrollTo(0, 0); lastCandidateTexts = texts }
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
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.labelSizeSp * me.zssu.ime.settings.ImeSettings(context).candidateTextScale)
        view.setTextColor(theme.candidateTextColor)
        view.setPadding(padding, padding / 2, padding, padding / 2)
    }

    fun clear() = setCandidates(emptyList(), -1)

    fun showTools() {
        paletteHeight(false)
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
        paletteHeight(true)
        container.removeAllViews()
        setContainerFillWidth(false)
        scrollTo(0, 0)
        val density = resources.displayMetrics.density
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val headings = listOf(EmojiPage.RECENT to "最近", EmojiPage.EMOJI to "絵文字", EmojiPage.KAOMOJI to "顔文字", EmojiPage.FAVORITE to "お気に入り")
        headings.forEach { (target, label) ->
            header.addView(TextView(context).apply {
                text = label; contentDescription = label; gravity = Gravity.CENTER
                setTextColor(theme.candidateTextColor)
                setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
                if (target == selectedPage) setBackgroundColor(theme.keyPressedColor)
                setOnClickListener { showEmoji(recents, favorites, target) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        header.addView(iconButton(R.drawable.ic_material_arrow_back, "閉じる") { showTools() })
        panel.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false; addView(header)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (42 * density).toInt()))
        val groups = if (selectedPage == EmojiPage.EMOJI) EmojiRepository(context).unicodeEmoji() else emptyMap()
        if (groups.isNotEmpty()) {
            if (currentEmojiGroup !in groups) currentEmojiGroup = groups.keys.first()
            val labels = mapOf("Smileys & Emotion" to "表情", "People & Body" to "人", "Animals & Nature" to "自然", "Food & Drink" to "食べ物", "Travel & Places" to "場所", "Activities" to "活動", "Objects" to "物", "Symbols" to "記号", "Flags" to "旗")
            val tabs = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            groups.keys.forEach { group -> tabs.addView(TextView(context).apply {
                text = labels[group] ?: group; contentDescription = text; gravity = Gravity.CENTER
                setTextColor(theme.candidateTextColor); setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
                if (group == currentEmojiGroup) setBackgroundColor(theme.keyPressedColor)
                setOnClickListener { currentEmojiGroup = group; showEmoji(recents, favorites, EmojiPage.EMOJI) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)) }
            panel.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(tabs) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (36 * density).toInt()))
        }
        val values = when (selectedPage) {
            EmojiPage.RECENT -> recents.ifEmpty { EmojiRepository.EMOJI.take(10) }
            EmojiPage.EMOJI -> groups[currentEmojiGroup] ?: EmojiRepository.EMOJI
            EmojiPage.KAOMOJI -> EmojiRepository.KAOMOJI
            EmojiPage.FAVORITE -> favorites
        }
        val grid = GridView(context).apply {
            numColumns = if (selectedPage == EmojiPage.KAOMOJI) 2 else 6
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            adapter = object : BaseAdapter() {
                override fun getCount() = values.size
                override fun getItem(position: Int) = values[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val value = getItem(position)
                    val cell = (convertView as? TextView) ?: TextView(context).apply {
                        gravity = Gravity.CENTER
                        setPadding((4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt())
                    }
                    cell.text = value; cell.contentDescription = if (value in favorites) "$value お気に入り" else value
                    cell.setTextColor(theme.candidateTextColor)
                    cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (selectedPage == EmojiPage.KAOMOJI) theme.labelSizeSp else theme.labelSizeSp + 6f)
                    cell.setOnClickListener { onEmojiSelected?.invoke(value) }
                    cell.setOnLongClickListener { onEmojiFavoriteToggled?.invoke(value); true }
                    return cell
                }
            }
        }
        panel.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        container.addView(panel, LinearLayout.LayoutParams(resources.displayMetrics.widthPixels, LinearLayout.LayoutParams.MATCH_PARENT))
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
