package me.zssu.ime.keyboard

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
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

    enum class ToolAction { CLIPBOARD, EMOJI, ONE_HAND_CYCLE, PROOFREAD, SETTINGS, SNIPPETS, AI_STYLE, MEETS_BEAM, EDIT, RECONVERT, HEIGHT, INPUT_HELP, VOICE }
    enum class OneHandDisplayMode { FULL, LEFT, RIGHT }
    enum class EmojiPage { RECENT, EMOJI, KAOMOJI, FAVORITE }

    fun interface OnCandidateSelectedListener {
        fun onCandidateSelected(candidate: MozcSession.Candidate)
    }

    var listener: OnCandidateSelectedListener? = null
    var onCandidateLongPressed: ((MozcSession.Candidate) -> Unit)? = null
    var onPanelExpanded: ((Boolean) -> Unit)? = null
    var onEditCommand: ((String) -> Unit)? = null
    var onConversionPageChanged: ((List<Int>) -> Unit)? = null
    var onSegmentEditRequested: (() -> Unit)? = null
    var segmentEditing = false
    private var conversionData: Pair<List<MozcSession.Candidate>, Int>? = null
    private var conversionNavigate: ((Int) -> Boolean)? = null
    private var swipeX = 0f
    private var swipeY = 0f
    var onCandidateFocusRequested: ((MozcSession.Candidate) -> Unit)? = null
    var onToolAction: ((ToolAction) -> Unit)? = null
    var onSnippetSelected: ((String) -> Unit)? = null
    var onManageSnippets: (() -> Unit)? = null
    var onClipboardItemSelected: ((String) -> Unit)? = null
    var onClipboardHistoryCleared: (() -> Unit)? = null
    private var voicePanelShowing = false

    var theme: KeyboardTheme = KeyboardTheme.Default
        set(value) {
            field = value
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                val chips = if (child is TextView) listOf(child) else if (child in twoRowColumns)
                    (0 until (child as LinearLayout).childCount).map { child.getChildAt(it) as TextView } else emptyList()
                chips.forEach {
                    applyTheme(it)
                    it.compoundDrawablesRelative.filterNotNull().forEach { icon -> icon.setTint(value.candidateTextColor) }
                    if (it.isSelected) it.setBackgroundColor(value.keyPressedColor)
                }
            }
            invalidate()
        }

    /** Which chip currently carries the focus highlight, so only the two that change are touched. */
    private var focusedChild = -1
    private var conversionExpanded = false
    private var conversionBaseHeight = 0
    private var conversionPresentation = 0
    private data class ConversionPageCache(
        val texts: List<String>, val width: Int, val rows: Int, val textSize: Float,
        val widths: List<Int>, val pages: List<me.zssu.ime.ime.CandidatePaging.Page>,
    )
    private var conversionPageCache: ConversionPageCache? = null

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
    private var emojiPanel: View? = null

    /**
     * What to draw when the tool menu is closed, if it was opened over something else.
     *
     * The host installs this when the menu is opened from the always-visible bar button so a menu
     * opened during conversion returns to the candidate list instead of dumping the user on the
     * tool icons. Null means "no previous view": the menu closes back to the default toolbar.
     */
    private var restoreAfterMenu: (() -> Unit)? = null

    /**
     * Opens the tool menu from outside the scrolling strip (the fixed bar button).
     *
     * [restore] is invoked instead of the default toolbar when the menu is closed.
     */
    fun showMenu(restore: (() -> Unit)? = null) {
        restoreAfterMenu = restore
        showToolMenu()
    }

    private fun closeToolMenu() {
        val restore = restoreAfterMenu
        restoreAfterMenu = null
        if (restore != null) restore() else showTools()
    }

    private fun resizeEmojiPanel(viewportWidth: Int) {
        val panel = emojiPanel ?: return
        if (panel.parent !== container || viewportWidth <= 0) return
        val target = (viewportWidth - paddingLeft - paddingRight).coerceAtLeast(1)
        val params = panel.layoutParams ?: return
        if (params.width != target) { params.width = target; panel.layoutParams = params }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Palettes can be opened before this view has its final width (including one-hand mode).
        // Constrain their child before measuring; onSizeChanged alone leaves a stale first frame
        // when only the palette changes and the outer view keeps the same width.
        val panel = emojiPanel?.takeIf { it.parent === container }
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        if (panel != null && available > 0 && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            panel.layoutParams.width = available
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resizeEmojiPanel(w)
        if (w != oldw) conversionData?.let { data ->
            onConversionPageChanged?.invoke(emptyList())
            post { if (conversionData === data) setCandidates(data.first, data.second, converting = true) }
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (conversionNavigate == null) return super.onInterceptTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { swipeX = event.x; swipeY = event.y }
        val dx = kotlin.math.abs(event.x - swipeX)
        return event.actionMasked == MotionEvent.ACTION_MOVE && dx > ViewConfiguration.get(context).scaledTouchSlop && dx > kotlin.math.abs(event.y - swipeY)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val navigate = conversionNavigate ?: return super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && kotlin.math.abs(event.x - swipeX) > dp(32)) {
            navigate(if (event.x < swipeX) 1 else -1)
        }
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (conversionNavigate != null) {
            info.isScrollable = true
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        conversionNavigate?.let { navigate ->
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return navigate(1)
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return navigate(-1)
        }
        return super.performAccessibilityAction(action, arguments)
    }

    private fun paletteHeight(expanded: Boolean) {
        if (expanded && conversionData != null) {
            conversionData = null
            conversionNavigate = null
            onConversionPageChanged?.invoke(emptyList())
        }
        onPanelExpanded?.invoke(expanded)
        val params = layoutParams ?: return
        if (expanded) {
            if (normalHeight == 0) normalHeight = params.height
            params.height = (210 * resources.displayMetrics.density).toInt()
        } else if (normalHeight != 0) {
            emojiPanel = null
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
    fun setCandidates(candidates: List<MozcSession.Candidate>, focusedIndex: Int, converting: Boolean = false) {
        if (voicePanelShowing) return
        paletteHeight(false)
        if (converting && candidates.any { !it.directCommit }) {
            showConversionCandidates(candidates, focusedIndex)
            return
        }
        resetConversionPresentation()
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
            bindChip(container.getChildAt(index) as TextView, candidate, index == focusedIndex)
        }
        focusedChild = focusedIndex
        val newTexts = candidates.map { it.text }
        if (newTexts != lastCandidateTexts) {
            scrollTo(0, 0)
            lastCandidateTexts = newTexts
        }
    }

    private fun resetConversionPresentation() {
        if (conversionData == null && conversionNavigate == null && !conversionExpanded && conversionBaseHeight == 0) return
        conversionPresentation++
        conversionData = null
        conversionPageCache = null
        conversionNavigate = null
        onConversionPageChanged?.invoke(emptyList())
        conversionExpanded = false
        if (conversionBaseHeight != 0) {
            layoutParams = layoutParams.apply { height = conversionBaseHeight }
            conversionBaseHeight = 0
        }
    }

    private fun showConversionCandidates(all: List<MozcSession.Candidate>, focusedIndex: Int) {
        conversionData = all to focusedIndex
        val native = all
        val focusedId = all.getOrNull(focusedIndex)?.id
        val focus = native.indexOfFirst { it.id == focusedId }.coerceAtLeast(0)
        val settings = me.zssu.ime.settings.ImeSettings(context)
        val textSp = 20f * settings.candidateTextScale
        val paint = android.text.TextPaint().apply { textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSp, resources.displayMetrics) }
        val rowHeight = maxOf(dp(48), (paint.fontMetrics.bottom - paint.fontMetrics.top).toInt() + dp(20))
        val viewportWidth = (width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - paddingLeft - paddingRight
        val availableWidth = (viewportWidth - dp(48)).coerceAtLeast(1)
        val texts = native.map { if (it.directCommit) "全文  ${it.text}" else it.text }
        val cached = conversionPageCache?.takeIf {
            it.texts == texts && it.width == availableWidth && it.rows == settings.candidateRows && it.textSize == paint.textSize
        } ?: run {
            val widths = texts.map { maxOf(dp(48), kotlin.math.ceil(paint.measureText(it)).toInt() + dp(28)).coerceAtMost(availableWidth) }
            ConversionPageCache(texts, availableWidth, settings.candidateRows, paint.textSize, widths,
                me.zssu.ime.ime.CandidatePaging.pages(widths, availableWidth, settings.candidateRows)).also { conversionPageCache = it }
        }
        val widths = cached.widths
        val pages = cached.pages
        val pageIndex = pages.indexOfFirst { focus in it.indices }.coerceAtLeast(0)
        val page = pages[pageIndex]
        onConversionPageChanged?.invoke(all.filterNot { it.directCommit }.map { it.id })
        conversionNavigate = if (conversionExpanded) null else { delta ->
            val next = pages.getOrNull(pageIndex + delta)?.indices?.firstOrNull()
            if (next == null) false else { onCandidateFocusRequested?.invoke(native[next]); true }
        }
        if (conversionBaseHeight == 0) conversionBaseHeight = layoutParams.height
        layoutParams = layoutParams.apply { height = if (conversionExpanded) dp(320) else rowHeight * settings.candidateRows }
        onPanelExpanded?.invoke(conversionExpanded)
        focusedChild = -1
        lastCandidateTexts = emptyList()
        container.removeAllViews()
        setContainerFillWidth(false)
        scrollTo(0, 0)
        val presentation = ++conversionPresentation
        val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun toggle(): ImageButton = ImageButton(context).apply {
            setImageResource(R.drawable.ic_ime_expand_more)
            imageTintList = ColorStateList.valueOf(theme.candidateTextColor)
            background = null
            rotation = if (conversionExpanded) 180f else 0f
            contentDescription = if (conversionExpanded) "候補一覧を閉じる" else "候補一覧を開く"
            setOnClickListener { conversionExpanded = !conversionExpanded; setCandidates(all, focusedIndex, converting = true) }
        }
        fun bindConversionChip(view: TextView, candidate: MozcSession.Candidate, full: Boolean): TextView = view.apply {
            tag = candidate
            text = if (candidate.directCommit) "全文  ${candidate.text}" else candidate.text
            textSize = textSp
            setPadding(dp(14), dp(6), dp(14), dp(6))
            if (full) { setSingleLine(false); maxLines = 3; ellipsize = TextUtils.TruncateAt.END }
            else { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
            contentDescription = candidate.text + (if (candidate.directCommit) " 全文を確定" else "") + if (candidate.id == focusedId) " 選択中" else ""
            isSelected = candidate.id == focusedId
            if (isSelected) setBackgroundColor(theme.keyPressedColor) else background = null
        }
        if (conversionExpanded) {
            val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            header.addView(TextView(context).apply {
                text = if (segmentEditing) "文節操作を閉じる" else "文節を調整"
                textSize = 16f; gravity = Gravity.CENTER; setTextColor(theme.candidateTextColor)
                setOnClickListener {
                    conversionExpanded = false
                    onSegmentEditRequested?.invoke()
                    setCandidates(all, focusedIndex, converting = true)
                }
            }, LinearLayout.LayoutParams(0, rowHeight, 1f))
            header.addView(toggle(), LinearLayout.LayoutParams(dp(48), rowHeight))
            panel.addView(header)
            val list = android.widget.ListView(context).apply {
                divider = null
                adapter = object : android.widget.BaseAdapter() {
                    override fun getCount() = all.size
                    override fun getItem(position: Int) = all[position]
                    override fun getItemId(position: Int) = position.toLong()
                    override fun getView(position: Int, recycled: View?, parent: android.view.ViewGroup): View =
                        bindConversionChip((recycled as? TextView) ?: newChip(), all[position], full = true).apply { minimumHeight = rowHeight }
                }
                if (focusedIndex in all.indices) post { if (presentation == conversionPresentation) setSelection(focusedIndex) }
            }
            panel.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            val body = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            repeat(settings.candidateRows) { rowIndex ->
                val row = LinearLayout(context)
                page.rows.getOrNull(rowIndex).orEmpty().forEach { index ->
                    row.addView(bindConversionChip(newChip(), native[index], full = false), LinearLayout.LayoutParams(widths[index], rowHeight))
                }
                rows.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowHeight))
            }
            body.addView(rows, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            body.addView(toggle(), LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.MATCH_PARENT))
            panel.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        emojiPanel = panel
        container.addView(panel, LinearLayout.LayoutParams(viewportWidth, LinearLayout.LayoutParams.MATCH_PARENT))
    }

    private val twoRowColumns = mutableListOf<LinearLayout>()

    private fun showTwoRows(candidates: List<MozcSession.Candidate>, focusedIndex: Int) {
        setContainerFillWidth(false)
        if (twoRowColumns.isEmpty() || twoRowColumns.first().parent !== container ||
            container.childCount != twoRowColumns.size) {
            container.removeAllViews()
            twoRowColumns.clear()
        }
        val count = (candidates.size + 1) / 2
        while (twoRowColumns.size > count) container.removeView(twoRowColumns.removeAt(twoRowColumns.lastIndex))
        while (twoRowColumns.size < count) {
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            repeat(2) { column.addView(newChip(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)) }
            twoRowColumns.add(column)
            container.addView(column, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        twoRowColumns.forEachIndexed { columnIndex, column ->
            repeat(2) { row ->
                val index = columnIndex * 2 + row
                val chip = column.getChildAt(row) as TextView
                val candidate = candidates.getOrNull(index)
                chip.visibility = if (candidate == null) View.INVISIBLE else View.VISIBLE
                if (candidate != null) bindChip(chip, candidate, index == focusedIndex)
                else { chip.text = ""; chip.tag = null }
            }
        }
        focusedChild = -1
        val texts = candidates.map { it.text }
        if (texts != lastCandidateTexts) { scrollTo(0, 0); lastCandidateTexts = texts }
    }

    private fun bindChip(view: TextView, candidate: MozcSession.Candidate, focused: Boolean) {
        val old = view.tag as? MozcSession.Candidate
        val pinned = candidate.priorityMatch != me.zssu.ime.ime.PriorityMatch.NONE
        val wasPinned = old?.priorityMatch?.let { it != me.zssu.ime.ime.PriorityMatch.NONE }
        view.tag = candidate
        val label = if (candidate.correction && !pinned) "補正 ${candidate.text}" else candidate.text
        if (view.text != label) view.text = label
        if (wasPinned != pinned) {
            val star = if (pinned) ContextCompat.getDrawable(context, R.drawable.ic_ime_star)?.mutate()?.apply {
                setTint(theme.candidateTextColor)
                val size = dp(18); setBounds(0, 0, size, size)
            } else null
            view.setCompoundDrawablesRelative(star, null, null, null)
            view.compoundDrawablePadding = dp(4)
        }
        view.contentDescription = if (pinned) "最優先 ${candidate.text}" else label
        if (view.isSelected != focused || old == null) {
            view.isSelected = focused
            if (focused) view.setBackgroundColor(theme.keyPressedColor) else view.background = null
        }
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
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f * me.zssu.ime.settings.ImeSettings(context).candidateTextScale)
        view.setTextColor(theme.candidateTextColor)
        view.setPadding(padding, padding / 2, padding, padding / 2)
    }

    fun clear() = setCandidates(emptyList(), -1)

    private val toolPrefs get() = context.getSharedPreferences("keyboard_toolbar", Context.MODE_PRIVATE)
    private fun toolOrder(): List<ToolAction> {
        val saved = toolPrefs.getString("order", "").orEmpty().split(",")
            .mapNotNull { name -> ToolAction.entries.firstOrNull { it.name == name } }.distinct()
        return saved + ToolAction.entries.filterNot { it in saved }
    }
    private fun toolLabel(action: ToolAction): String = when (action) {
        ToolAction.AI_STYLE -> "文体補正"
        ToolAction.CLIPBOARD -> "クリップボード"
        ToolAction.EMOJI -> "絵文字"
        ToolAction.ONE_HAND_CYCLE -> "片手モード"
        ToolAction.SNIPPETS -> "定型文"
        ToolAction.HEIGHT -> "キーボードの高さ"
        ToolAction.SETTINGS -> "設定"
        ToolAction.MEETS_BEAM -> "Meets Beam"
        ToolAction.EDIT -> "カーソル・範囲選択"
        ToolAction.RECONVERT -> "確定を戻す"
        ToolAction.PROOFREAD -> "AI 文章校正"
        ToolAction.INPUT_HELP -> "入力の使い方"
        ToolAction.VOICE -> "音声入力"
    }
    private fun toolIcon(action: ToolAction): Int = when (action) {
        ToolAction.AI_STYLE -> R.drawable.ic_ai_style
        ToolAction.CLIPBOARD -> R.drawable.ic_material_content_paste
        ToolAction.EMOJI -> R.drawable.ic_material_face
        ToolAction.ONE_HAND_CYCLE -> R.drawable.ic_material_settings
        ToolAction.SNIPPETS -> R.drawable.ic_material_content_paste
        ToolAction.HEIGHT -> R.drawable.ic_ime_height
        ToolAction.SETTINGS -> R.drawable.ic_material_settings
        ToolAction.MEETS_BEAM -> R.drawable.ic_meets_beam
        ToolAction.EDIT -> R.drawable.ic_ime_cursor_right
        ToolAction.RECONVERT -> R.drawable.ic_ime_undo
        ToolAction.PROOFREAD -> R.drawable.ic_ai_style
        ToolAction.INPUT_HELP -> R.drawable.ic_material_settings
        ToolAction.VOICE -> R.drawable.ic_voice
    }
    fun showTools() {
        if (voicePanelShowing) return
        paletteHeight(false)
        resetConversionPresentation()
        // The menu button itself lives in the fixed bar beside this strip (see the host), so it
        // stays reachable while conversion candidates are showing. Only the tools live in here.
        val visibleTools = toolOrder().filter {
            toolPrefs.getBoolean("visible_" + it.name, true) &&
                (it != ToolAction.MEETS_BEAM || me.zssu.ime.settings.MeetsBeamSettings.enabled(context))
        }.take(5)
        val next = ToolbarState(visibleTools, oneHandMode, theme)
        if (toolbarState == next) return
        focusedChild = -1
        lastCandidateTexts = emptyList()
        container.removeAllViews()
        setContainerFillWidth(true)
        applyStripBackdrop(theme.toolbarBackgroundColor)
        scrollTo(0, 0)
        for (action in visibleTools) {
            val button = if (action == ToolAction.ONE_HAND_CYCLE) oneHandButton(expand = true) { onToolAction?.invoke(action) }
                else iconButton(toolIcon(action), toolLabel(action), true) { onToolAction?.invoke(action) }
            container.addView(button)
        }
        toolbarState = next
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int = 16) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }

    /** Keeps dictation controls in the keyboard without transferring editor focus. */
    fun showVoicePanel(status: String, result: String, state: Int, onStop: () -> Unit,
                       onApply: () -> Unit, onRetry: () -> Unit, onClose: () -> Unit) {
        if (!voicePanelShowing) paletteHeight(true)
        voicePanelShowing = true
        layoutParams = layoutParams.apply { height = dp(180) }
        setContainerFillWidth(false)
        setContainerFillWidth(true)
        container.removeAllViews()
        scrollTo(0, 0)
        val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(6)) }
        val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(TextView(context).apply { text = "●  音声入力"; textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(theme.candidateTextColor) }, LinearLayout.LayoutParams(0, dp(32), 1f))
        heading.addView(TextView(context).apply { text = "閉じる"; textSize = 15f; gravity = Gravity.CENTER; setTextColor(theme.candidateTextColor); setOnClickListener { onClose() } }, LinearLayout.LayoutParams(dp(60), dp(40)))
        panel.addView(heading)
        panel.addView(TextView(context).apply { text = status; textSize = 14f; setTextColor(theme.candidateTextColor) })
        val transcript = TextView(context).apply {
            text = result.ifEmpty { "ここに聞き取った言葉を表示します" }; textSize = 19f
            setTextColor(theme.candidateTextColor)
            gravity = Gravity.CENTER_VERTICAL
        }
        panel.addView(android.widget.ScrollView(context).apply { addView(transcript); isFillViewport = true },
            LinearLayout.LayoutParams(-1, 0, 1f))
        val controls = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        fun button(label: String, action: () -> Unit) = TextView(context).apply {
            text = label; textSize = 16f; gravity = Gravity.CENTER; setTextColor(theme.candidateTextColor)
            background = rounded(theme.keyPressedColor, 12); setOnClickListener { action() }
        }
        if (state == 0) controls.addView(button("話し終える", onStop), LinearLayout.LayoutParams(0, dp(44), 1f))
        if (state == 1 || state == 2) controls.addView(button(if (state == 2) "準備する" else "もう一度", onRetry), LinearLayout.LayoutParams(0, dp(44), 1f))
        if (state == 1 && result.isNotBlank()) controls.addView(button("入力する", onApply), LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) })
        panel.addView(controls)
        container.addView(panel, LinearLayout.LayoutParams(-1, -1))
    }

    fun dismissVoicePanel() { voicePanelShowing = false; showTools() }
    private fun showToolMenu(editing: Boolean = false) {
        paletteHeight(true)
        layoutParams = layoutParams.apply { height = dp(180) }
        container.removeAllViews()
        setContainerFillWidth(false)
        scrollTo(0, 0)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), dp(4))
        }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(iconButton(R.drawable.ic_material_arrow_back, "閉じる") { closeToolMenu() }, LinearLayout.LayoutParams(dp(40), dp(36)))
        header.addView(TextView(context).apply {
            text = if (editing) "標準順に戻す" else "ツール"
            textSize = 13f; setTextColor(theme.candidateTextColor); gravity = Gravity.CENTER_VERTICAL
            if (editing) setOnClickListener {
                toolPrefs.edit().apply {
                    remove("order")
                    ToolAction.entries.forEach { remove("visible_" + it.name) }
                }.apply()
                showToolMenu(true)
            }
        }, LinearLayout.LayoutParams(0, dp(36), 1f))
        header.addView(TextView(context).apply {
            text = if (editing) "完了" else "編集"
            gravity = Gravity.CENTER; textSize = 13f; setTextColor(theme.candidateTextColor)
            setOnClickListener { showToolMenu(!editing) }
        }, LinearLayout.LayoutParams(dp(48), dp(36)))
        panel.addView(header)
        val order = toolOrder()
        val grid = android.widget.GridLayout(context).apply { columnCount = 3 }
        order.forEachIndexed { index, action ->
            val pinned = toolPrefs.getBoolean("visible_" + action.name, true)
            val tile = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(4), 0)
                background = rounded(if (editing && pinned) theme.keyPressedColor else theme.keyColor, 12)
                alpha = if (editing && !pinned) 0.5f else 1f
                contentDescription = toolLabel(action) + if (editing) { if (pinned) "、上部に表示" else "、メニューのみ" } else ""
            }
            tile.addView(android.widget.ImageView(context).apply {
                if (action == ToolAction.ONE_HAND_CYCLE) setImageDrawable(OneHandModeDrawable(oneHandMode, theme.candidateTextColor, resources.displayMetrics.density))
                else setImageResource(toolIcon(action))
                setColorFilter(theme.candidateTextColor)
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
            tile.addView(TextView(context).apply {
                text = toolLabel(action); textSize = 11f; setTextColor(theme.candidateTextColor)
                maxLines = 2; setPadding(dp(7), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            tile.setOnClickListener {
                if (editing) {
                    toolPrefs.edit().putBoolean("visible_" + action.name, !pinned).apply()
                    showToolMenu(true)
                } else { showTools(); onToolAction?.invoke(action) }
            }
            tile.setOnLongClickListener {
                it.startDragAndDrop(android.content.ClipData.newPlainText("tool", action.name), View.DragShadowBuilder(it), action, 0); true
            }
            tile.setOnDragListener { _, event ->
                when (event.action) {
                    android.view.DragEvent.ACTION_DRAG_STARTED -> event.localState is ToolAction
                    android.view.DragEvent.ACTION_DROP -> {
                        val source = event.localState as? ToolAction
                        if (source != null) {
                            val changed = order.toMutableList(); changed.remove(source); changed.add(index, source)
                            toolPrefs.edit().putString("order", changed.joinToString(",") { it.name }).apply()
                            showToolMenu(editing)
                        }; true
                    }
                    else -> true
                }
            }
            grid.addView(tile, android.widget.GridLayout.LayoutParams().apply {
                width = 0; height = dp(48)
                columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        val menuScroll = android.widget.ScrollView(context).apply {
            isFillViewport = true
            addView(grid)
        }
        panel.addView(menuScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        emojiPanel = panel
        container.addView(panel, LinearLayout.LayoutParams(width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels, LinearLayout.LayoutParams.MATCH_PARENT))
    }

    fun showSnippets(items: List<String>) {
        paletteHeight(true)
        container.removeAllViews()
        setContainerFillWidth(false)
        val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(iconButton(R.drawable.ic_material_arrow_back, "閉じる") { showTools() })
        panel.addView(TextView(context).apply {
            text = "定型文を追加・編集"; applyTheme(this)
            setOnClickListener { onManageSnippets?.invoke() }
        })
        for (value in items) panel.addView(TextView(context).apply {
            text = value; maxLines = 2; applyTheme(this)
            setOnClickListener { onSnippetSelected?.invoke(value) }
        })
        val scroll = android.widget.ScrollView(context).apply { addView(panel) }
        container.addView(scroll, LinearLayout.LayoutParams(width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels, dp(180)))
    }

    fun showEditPanel() {
        paletteHeight(true)
        layoutParams = layoutParams.apply { height = dp(180) }
        container.removeAllViews()
        setContainerFillWidth(false)
        val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(iconButton(R.drawable.ic_material_arrow_back, "閉じる") { showTools() })
        for (group in listOf(listOf("前の単語" to "word_left", "左" to "left", "右" to "right", "次の単語" to "word_right"), listOf("行頭" to "home", "行末" to "end", "選択開始・終了" to "select"), listOf("全選択" to "all", "コピー" to "copy", "切り取り" to "cut", "貼り付け" to "paste"))) {
            val row = LinearLayout(context)
            for ((label, command) in group) row.addView(TextView(context).apply {
                text = label; applyTheme(this); gravity = Gravity.CENTER
                setOnClickListener { onEditCommand?.invoke(command) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            panel.addView(row)
        }
        emojiPanel = panel
        container.addView(panel, LinearLayout.LayoutParams(resources.displayMetrics.widthPixels, LinearLayout.LayoutParams.MATCH_PARENT))
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
        emojiPanel = panel
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
        // This strip can be narrower than the screen (Beam icon, insets, or one-hand mode).
        // Match the measured viewport so only the category tabs scroll horizontally.
        val viewport = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        container.addView(panel, LinearLayout.LayoutParams(viewport, LinearLayout.LayoutParams.MATCH_PARENT))
        post { resizeEmojiPanel(width) }
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

    private val toolbarTint: Int
        get() = theme.toolbarIconColor.takeUnless { it == android.graphics.Color.TRANSPARENT }
            ?: theme.candidateTextColor

    private var stripBackdrop = android.graphics.Color.TRANSPARENT

    private fun applyStripBackdrop(color: Int) {
        if (stripBackdrop == color) return
        stripBackdrop = color
        setBackgroundColor(color)
    }

    private fun applyCandidateBackdrop() {
        applyStripBackdrop(if (theme.useCandidateBackground) theme.candidateBackgroundColor else android.graphics.Color.TRANSPARENT)
    }

    private fun iconButton(
        @DrawableRes icon: Int,
        description: String,
        expand: Boolean = false,
        action: () -> Unit,
    ): ImageButton = ImageButton(context).apply {
        setImageDrawable(ContextCompat.getDrawable(context, icon))
        imageTintList = ColorStateList.valueOf(if (expand) toolbarTint else theme.candidateTextColor)
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
                toolbarTint,
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

    private data class ToolbarState(val tools: List<ToolAction>, val hand: OneHandDisplayMode, val theme: KeyboardTheme)
    private var toolbarState: ToolbarState? = null

    private fun setContainerFillWidth(fill: Boolean) {
        if (!fill) {
            toolbarState = null
            applyCandidateBackdrop()
        }
        val width = if (fill) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT
        if (container.layoutParams.width != width) {
            container.layoutParams = LayoutParams(width, LayoutParams.MATCH_PARENT)
        }
    }
}
