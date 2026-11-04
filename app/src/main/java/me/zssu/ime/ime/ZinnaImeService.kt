package me.zssu.ime.ime

import android.content.Context
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.UnderlineSpan
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.view.Gravity
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.updatePadding
import me.zssu.ime.R
import me.zssu.ime.keyboard.CandidateStripView
import me.zssu.ime.keyboard.EmojiRepository
import me.zssu.ime.keyboard.FlickDirection
import me.zssu.ime.keyboard.FlickKeyboardView
import me.zssu.ime.keyboard.KeyAction
import me.zssu.ime.keyboard.KeyOutput
import me.zssu.ime.keyboard.KeySpec
import me.zssu.ime.keyboard.KeyboardLayout
import me.zssu.ime.keyboard.KeyboardPanelView
import me.zssu.ime.keyboard.LayoutRepository
import me.zssu.ime.keyboard.MeaningPopupView
import me.zssu.ime.karukan.KarukanEngine
import me.zssu.ime.karukan.KarukanModelStore
import me.zssu.ime.settings.ImeSettings
import me.zssu.ime.settings.AppProfileStore
import me.zssu.ime.settings.SettingsActivity
import me.zssu.ime.dictionary.MeaningDictionaryRepository
import me.zssu.ime.theme.KeyboardTheme
import me.zssu.ime.theme.MaterialYouTheme
import me.zssu.ime.style.TextStyle
import me.zssu.ime.style.TextStyleEngine
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.KeyEvent

/**
 * The input method itself.
 *
 * Responsibilities are kept narrow on purpose: translate [KeyOutput]s into mozc calls, and push
 * mozc's resulting [MozcSession.State] into the editor and the candidate strip. Flick geometry
 * lives in [FlickKeyboardView], conversion lives in mozc, and layout/theme data lives in
 * [LayoutRepository].
 */
class ZinnaImeService : InputMethodService() {

    private lateinit var repository: LayoutRepository
    private lateinit var settings: ImeSettings
    private lateinit var appProfiles: AppProfileStore
    private lateinit var emojiRepository: EmojiRepository
    private lateinit var meaningDictionaries: MeaningDictionaryRepository
    private lateinit var priorityCandidates: PriorityCandidateRepository
    private lateinit var clipboardHistory: ClipboardHistory
    private var clipboardManager: ClipboardManager? = null
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        // Capture copy events without polling while the IME is hidden.
        if (::settings.isInitialized && settings.clipboardHistoryEnabled && !fieldPolicy.incognito) {
            clipboardHistory.record(this, clipboardManager)
        }
    }
    private lateinit var session: ConversionSession
    private lateinit var zenzai: ZenzaiEngine
    private var zenzaiCandidate: MozcSession.Candidate? = null
    private var zenzaiState: MozcSession.State? = null
    private var zenzaiDictionary = emptyList<me.zssu.ime.mozc.UserDictionary.Entry>()
    private val enginePreferences by lazy { getSharedPreferences(ImeSettings.NAME, Context.MODE_PRIVATE) }
    private val enginePreferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "zenzai_plus_enabled" || key == "zenzai_mozc_enabled") {
            submitCurrentComposition()
            refreshZenzai()
            layout?.let { session.applyInputStyle(it.inputStyle) }
        } else if (key == "zenzai_reset_learning") {
            zenzai.resetLearning()
        } else if ((key == me.zssu.ime.mozc.UserDictionary.REVISION_KEY || key == "zenzai_train_dictionary") && session.zenzaiEnabled) {
            zenzai.syncDictionary { zenzaiDictionary = it }
        }
    }
    private lateinit var karukanEngine: KarukanEngine

    private var editAnchor: Int? = null
    private var segmentBar: LinearLayout? = null
    private var segmentEditing = false
    private val conversionInteraction = ConversionInteraction()
    private var segmentSummary: LinearLayout? = null
    private var keyboardView: FlickKeyboardView? = null
    private var panelView: KeyboardPanelView? = null
    private var contentLayout: LinearLayout? = null
    private var candidateView: CandidateStripView? = null
    private var meaningPopup: MeaningPopupView? = null
    private var layout: KeyboardLayout? = null
    private var lastNightMode = Int.MIN_VALUE
    private var theme: KeyboardTheme = KeyboardTheme.Default
    private var fieldPolicy = InputFieldPolicy.DEFAULT
    private var activeProfile: AppProfileStore.Profile? = null
    private var lastCandidates: List<MozcSession.Candidate> = emptyList()
    private var lastFocusedCandidateIndex: Int = -1
    private var karukanConversionActive = false
    private var karukanSuggestionPending: Runnable? = null
    private var karukanSuggestionActive = false
    private var karukanSuggestionCandidate: MozcSession.Candidate? = null
    private var zenzaiFallbackPending: Runnable? = null
    private var karukanReading = ""

    /**
     * Whether mozc currently holds a composition. Mirrors the last rendered state so Enter can
     * decide between 確定 and the editor's action *before* it submits and destroys the evidence.
     */
    private var isComposing = false
    private var isConverting = false
        set(value) { field = value; conversionInteraction.observe(value); if (!value) segmentEditing = false }
    private var currentSegmentCount = 0
    private var suppressPartialLearning = false
    private var renderedPreeditCursor = 0
    private var renderedPreedit = ""

    private var editorStateUpdateInProgress = false

    /** Used to retry applying a proofreading result once the editor connection comes back up. */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var proofreadApplyAttempts = 0
    private val proofreadApplyRetry = Runnable { tryApplyProofreadResult() }

    /** [ImeSettings.revision] the current input view was built from. */
    private var builtFromRevision = Int.MIN_VALUE
    private var builtStructureFingerprint = Int.MIN_VALUE

    /**
     * The system-UI intersection last dispatched to us.
     *
     * Kept because a replacement view can draw before fresh insets arrive. A visible zero later
     * clears the seed on windows that already place navigation outside the IME; an actual overlap
     * on edge-to-edge windows remains as interactive-content padding.
     */
    private var systemInsetLeft = 0
    private var systemInsetRight = 0
    private var systemInsetBottom = 0

    override fun onCreate() {
        super.onCreate()
        repository = LayoutRepository(this)
        settings = ImeSettings(this)
        appProfiles = AppProfileStore(this)
        emojiRepository = EmojiRepository(this)
        meaningDictionaries = MeaningDictionaryRepository(this)
        priorityCandidates = PriorityCandidateRepository(this)
        clipboardHistory = ClipboardHistory()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
        session = ConversionSession(this, priorityCandidates::match)
        zenzai = ZenzaiEngine(this) {
            Toast.makeText(this, "zenzai-plusを利用できません。かな入力は継続できます", Toast.LENGTH_LONG).show()
        }
        enginePreferences.registerOnSharedPreferenceChangeListener(enginePreferenceListener)
        refreshZenzai()
        karukanEngine = KarukanEngine(this)
        if (!session.isAvailable) {
            // Without the native engine there is nothing useful to do; the keyboard still renders
            // so the user can switch away rather than being stuck with a dead input field.
            Log.e(TAG, "mozc engine unavailable — conversion disabled")
        }
    }

    override fun onCreateInputView(): View {
        // onStartInputView normally makes this available first. Keep an explicitly selected policy
        // if an OEM calls view creation with no EditorInfo instead of falling back to normal text.
        currentInputEditorInfo?.let {
            activeProfile = appProfiles.profileFor(it.packageName)
            fieldPolicy = effectivePolicy(InputFieldPolicy.from(it), activeProfile)
        }
        val loaded = repository.loadLayout(initialLayoutId(fieldPolicy))
            ?: repository.loadLayout(LayoutRepository.DEFAULT_LAYOUT_ID)
        if (loaded == null) Log.e(TAG, "default layout missing from assets")
        layout = loaded
        theme = resolveTheme()

        val panel = KeyboardPanelView(this).apply {
            setBackgroundColor(this@ZinnaImeService.theme.backgroundColor)
            setBackgroundImage(settings.backgroundImage, settings.backgroundOpacity)
            // Seed all previously observed safe areas. A replacement view can draw before Android
            // dispatches fresh insets; starting at zero lets its first frame overlap OS controls.
            updatePadding(
                left = systemInsetLeft,
                right = systemInsetRight,
                bottom = systemInsetBottom,
            )
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.navigationBars() or
                        WindowInsetsCompat.Type.mandatorySystemGestures() or
                        WindowInsetsCompat.Type.tappableElement() or
                        WindowInsetsCompat.Type.displayCutout()
                )
                val padding = KeyboardInsetPolicy.contentPadding(
                    left = bars.left,
                    right = bars.right,
                    bottom = bars.bottom,
                    navigationVisible = insets.isVisible(
                        WindowInsetsCompat.Type.navigationBars()
                    ),
                    previous = KeyboardInsetPolicy.Padding(
                        systemInsetLeft,
                        systemInsetRight,
                        systemInsetBottom,
                    ),
                )
                systemInsetLeft = padding.left
                systemInsetRight = padding.right
                systemInsetBottom = padding.bottom
                view.updatePadding(
                    left = padding.left,
                    right = padding.right,
                    bottom = padding.bottom,
                )
                // Preserve dispatch for the popup and any OEM parent handling.
                insets
            }
            // And ask for a fresh dispatch once attached, so a stale seed (after a rotation, say)
            // is corrected rather than persisting for the rest of the session.
            doOnAttach { ViewCompat.requestApplyInsets(it) }
            // During an IME switch some devices draw one frame before delivering navigation
            // insets. Compare the laid-out panel with the physical navigation area so a window
            // already positioned above it gets no extra padding.
            viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (!isAttachedToWindow || height <= 0) return true
                    val windowInsets = ViewCompat.getRootWindowInsets(this@apply)
                    val navigationType = WindowInsetsCompat.Type.navigationBars()
                    if (windowInsets != null && !windowInsets.isVisible(navigationType)) return true
                    val stableHeight = windowInsets?.getInsetsIgnoringVisibility(navigationType)?.bottom ?: 0
                    val navResource = resources.getIdentifier("navigation_bar_height", "dimen", "android")
                    val resourceHeight = if (navResource != 0) resources.getDimensionPixelSize(navResource) else 0
                    val navigationHeight = if (stableHeight > 0) stableHeight else resourceHeight
                    @Suppress("DEPRECATION")
                    val display = (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
                    val realSize = android.graphics.Point()
                    @Suppress("DEPRECATION")
                    display.getRealSize(realSize)
                    val location = IntArray(2)
                    getLocationOnScreen(location)
                    val overlap = KeyboardInsetPolicy.bottomOverlap(location[1] + height, realSize.y, navigationHeight)
                    if (overlap > paddingBottom) {
                        systemInsetBottom = maxOf(systemInsetBottom, overlap)
                        updatePadding(bottom = systemInsetBottom)
                        return false
                    }
                    return true
                }
            })
        }

        val root = FrameLayout(this)
        root.addView(panel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        val popupView = MeaningPopupView(this).apply {
            applyTheme(this@ZinnaImeService.theme)
            onDismiss = {
                if (isComposing) {
                    candidateView?.setCandidates(lastCandidates, lastFocusedCandidateIndex, converting = isConverting)
                }
            }
        }

        val candidates = CandidateStripView(this).apply {
            theme = this@ZinnaImeService.theme
            listener = CandidateStripView.OnCandidateSelectedListener { candidate ->
                if (lastCandidates.none { it.id == candidate.id && it.text == candidate.text }) return@OnCandidateSelectedListener
                // Preserve the native candidate window until a Mozc id is submitted. A direct
                // neural candidate resets it inside selectCandidate.
                cancelKarukanConversion(clearReading = true, revertMozc = false)
                val partial = isConverting && currentSegmentCount > 1 && !candidate.directCommit
                suppressPartialLearning = partial
                try { render(session.selectCandidate(candidate)) } finally { suppressPartialLearning = false }
            }
            onCandidateLongPressed = { candidate ->
                val entries = meaningDictionaries.lookup(candidate.text)
                val definitions = entries.flatMap { entry ->
                    entry.meanings.map { meaning ->
                        buildString {
                            if (entry.tags.isNotEmpty()) append("[${entry.tags.joinToString("・")}] ")
                            append(meaning)
                            entry.source?.let { append(" — $it") }
                        }
                    }
                }
                val exactPriority = candidate.priorityMatch == PriorityMatch.EXACT
                popupView.show(
                    term = candidate.text,
                    reading = entries.firstOrNull()?.reading ?: candidate.inputReading,
                    definitions = definitions,
                    canDelete = candidate.deletable,
                    onDelete = {
                        render(session.deleteCandidateFromHistory(candidate.id))
                        Toast.makeText(
                            this@ZinnaImeService,
                            "「${candidate.text}」を学習候補から削除しました",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    canPrioritize = candidate.inputReading.isNotEmpty(),
                    isPrioritized = exactPriority,
                    onTogglePriority = {
                        val saved = if (exactPriority) {
                            priorityCandidates.unpin(candidate.inputReading, candidate.text)
                        } else {
                            priorityCandidates.pin(candidate.inputReading, candidate.text)
                        }
                        if (saved) {
                            refreshCandidatePriorities()
                            Toast.makeText(
                                this@ZinnaImeService,
                                if (exactPriority) {
                                    "「${candidate.text}」の最優先を解除しました"
                                } else {
                                    "「${candidate.text}」を最優先にしました"
                                },
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            Toast.makeText(
                                this@ZinnaImeService,
                                "最優先設定を保存できませんでした",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            }
            onCandidateDeleteRequested = { candidate ->
                render(session.deleteCandidateFromHistory(candidate.id))
                Toast.makeText(
                    this@ZinnaImeService,
                    "「${candidate.text}」を学習候補から削除しました",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            onCandidateDetailsClosed = {
                setCandidates(lastCandidates, lastFocusedCandidateIndex, converting = isConverting)
            }
            onConversionPageChanged = { ids -> session.visibleConversionCandidateIds = ids }
            onSegmentEditRequested = {
                segmentEditing = !segmentEditing
                candidateView?.segmentEditing = segmentEditing
                segmentBar?.visibility = if (isConverting && segmentEditing) View.VISIBLE else View.GONE
            }
            onCandidateFocusRequested = { candidate ->
                if (isConverting && !candidate.directCommit && lastCandidates.any { it.id == candidate.id && it.text == candidate.text }) {
                    conversionInteraction.chooseCandidates()
                    cancelKarukanConversion(clearReading = true, revertMozc = false)
                    render(session.highlightCandidate(candidate.id), requestNeural = false)
                }
            }
            onToolAction = ::handleToolAction
            onSnippetSelected = { text -> if (!fieldPolicy.incognito) { handleSymbol(text); showTools() } }
            onManageSnippets = { startActivity(Intent(this@ZinnaImeService, me.zssu.ime.settings.SnippetActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            onStyleSelected = { style ->
                applyStyleCorrection(style)
                candidateView?.showTools()
            }
            onClipboardItemSelected = { text ->
                currentInputConnection?.commitText(text, 1)
                showTools()
            }
            onClipboardHistoryCleared = {
                clipboardHistory.clear()
                showClipboardHistory(emptyList())
                Toast.makeText(
                    this@ZinnaImeService,
                    "クリップボード履歴を消去しました",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            onEmojiSelected = { emoji ->
                currentInputConnection?.commitText(emoji, 1)
                emojiRepository.recordUsed(emoji)
                showEmoji(emojiRepository.recents(), emojiRepository.favorites())
            }
            onEmojiFavoriteToggled = { emoji ->
                val added = emojiRepository.toggleFavorite(emoji)
                Toast.makeText(
                    this@ZinnaImeService,
                    if (added) "お気に入りに追加しました" else "お気に入りから外しました",
                    Toast.LENGTH_SHORT,
                ).show()
                showEmoji(emojiRepository.recents(), emojiRepository.favorites())
            }
            oneHandMode = when (effectiveOneHandMode()) {
                ImeSettings.OneHandMode.OFF -> CandidateStripView.OneHandDisplayMode.FULL
                ImeSettings.OneHandMode.LEFT -> CandidateStripView.OneHandDisplayMode.LEFT
                ImeSettings.OneHandMode.RIGHT -> CandidateStripView.OneHandDisplayMode.RIGHT
            }
        }
        val stripHeight = if (fieldPolicy.showCandidates) {
            maxOf((theme.keyHeightDp * 0.8f * resources.displayMetrics.density).toInt(),
                (android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 20f * settings.candidateTextScale, resources.displayMetrics) * 1.35f + 20f * resources.displayMetrics.density).toInt()) * settings.candidateRows
        } else {
            0
        }

        // Sits outside the horizontally scrolling strip so the tool menu stays reachable while a
        // conversion candidate list is showing — the strip scrolls, this button never does.
        val menuButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_tool_menu)
            imageTintList = ColorStateList.valueOf(this@ZinnaImeService.theme.candidateTextColor)
            contentDescription = "ツールメニュー"
            background = null
            val padding = (12 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setOnClickListener {
                if (this@ZinnaImeService.theme.hapticFeedback) {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                // Closing the menu returns to whatever was on screen — usually the candidates a
                // conversion just produced — rather than dropping the user on the tool icons.
                candidates.showMenu {
                    if (lastCandidates.isNotEmpty()) {
                        candidates.setCandidates(lastCandidates, lastFocusedCandidateIndex, converting = isConverting)
                    } else {
                        candidates.showTools()
                    }
                }
            }
        }
        val menuButtonWidth = (44 * resources.displayMetrics.density).toInt()

        val keyboard = FlickKeyboardView(this).apply {
            theme = this@ZinnaImeService.theme
            layout = loaded?.let(::adaptLayoutForStyle)
            guideOverflowTop = stripHeight.toFloat()
            guideStyle = settings.flickGuideStyle
            listener = FlickKeyboardView.OnKeyOutputListener(::onKeyOutput)
        }

        candidates.onPanelExpanded = { expanded ->
            keyboard.visibility = if (expanded) View.GONE else View.VISIBLE
            menuButton.visibility = if (expanded) View.GONE else View.VISIBLE
            segmentBar?.visibility = if (!expanded && isConverting && segmentEditing) View.VISIBLE else View.GONE
        }
        candidates.onEditCommand = ::handleEditCommand
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            gravity = when (effectiveOneHandMode()) {
                ImeSettings.OneHandMode.LEFT -> Gravity.START
                ImeSettings.OneHandMode.RIGHT -> Gravity.END
                ImeSettings.OneHandMode.OFF -> Gravity.CENTER_HORIZONTAL
            }
        }
        panel.gravity = when (effectiveOneHandMode()) {
            ImeSettings.OneHandMode.LEFT -> Gravity.START
            ImeSettings.OneHandMode.RIGHT -> Gravity.END
            ImeSettings.OneHandMode.OFF -> Gravity.CENTER_HORIZONTAL
        }
        val oneHandWidth =
            (resources.displayMetrics.widthPixels * settings.oneHandWidth).toInt()
        val contentWidth = if (effectiveOneHandMode() == ImeSettings.OneHandMode.OFF) {
            LinearLayout.LayoutParams.MATCH_PARENT
        } else {
            oneHandWidth
        }
        val candidateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(menuButton, LinearLayout.LayoutParams(menuButtonWidth, stripHeight))
            addView(candidates, LinearLayout.LayoutParams(0, stripHeight, 1f))
        }
        content.addView(candidateRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val segments = LinearLayout(this).apply {
            visibility = View.GONE
            val operations = listOf("前の文節" to -2, "縮める" to -1, "広げる" to 1, "次の文節" to 2)
            for ((label, code) in operations) addView(android.widget.TextView(context).apply {
                text = label; textSize = 12f; gravity = Gravity.CENTER
                setTextColor(this@ZinnaImeService.theme.candidateTextColor)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(this@ZinnaImeService.theme.keyColor)
                    cornerRadius = 12 * resources.displayMetrics.density
                }
                isFocusable = true
                setOnClickListener { if (isConverting) handleCursorMove(if (code < 0) -1 else 1, kotlin.math.abs(code) == 1, forceClauseMove = kotlin.math.abs(code) == 2) }
            }, LinearLayout.LayoutParams(0, (48 * resources.displayMetrics.density).toInt(), 1f).apply {
                val gap = (3 * resources.displayMetrics.density).toInt(); setMargins(gap, gap, gap, gap)
            })
        }
        val summary = LinearLayout(this)
        val segmentScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(summary)
        }
        segmentSummary = summary
        val segmentControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(segmentScroll)
            addView(segments)
        }
        segments.visibility = View.VISIBLE
        segmentBar = segmentControls
        content.addView(segmentControls)
        content.addView(
            keyboard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        panel.addView(
            content,
            LinearLayout.LayoutParams(contentWidth, LinearLayout.LayoutParams.WRAP_CONTENT),
        )

        root.addView(
            popupView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        candidateView = candidates
        panelView = panel
        contentLayout = content
        keyboard.onTrackpadMove = { delta -> handleCursorMove(delta, adjustSegment = false, forceClauseMove = true) }
        keyboardView = keyboard
        meaningPopup = popupView
        builtFromRevision = combinedRevision()
        builtStructureFingerprint = viewStructureFingerprint()
        lastNightMode = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        session.setPhoneToggleEnabled(
            settings.flickInputMode == ImeSettings.FlickInputMode.FLICK_AND_TOGGLE
        )
        session.setIncognitoMode(fieldPolicy.incognito)
        session.setTypingCorrectionEnabled(activeProfile?.typingCorrection ?: true)
        loaded?.let { session.applyInputStyle(it.inputStyle) }
        candidates.showTools()
        val onboarding = getSharedPreferences(ImeSettings.NAME, MODE_PRIVATE)
        if (!onboarding.getBoolean("flick_onboarding_done", false)) {
            fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
            fun background(color: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = dp(18).toFloat() }
            val chooser = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                this.background = background(theme.backgroundColor)
                elevation = dp(8).toFloat()
                setPadding(dp(16), dp(12), dp(16), dp(12))
                addView(android.widget.TextView(context).apply {
                    text = "入力方法を選ぶ"; setTextColor(theme.candidateTextColor); textSize = 18f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
            }
            val choices = LinearLayout(this)
            fun option(title: String, description: String, mode: ImeSettings.FlickInputMode) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
                    this.background = background(theme.keyColor)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    contentDescription = "$title。$description"
                    isFocusable = true
                    addView(android.widget.TextView(context).apply {
                        text = title; setTextColor(theme.candidateTextColor); textSize = 14f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    })
                    addView(android.widget.TextView(context).apply {
                        text = description; setTextColor(theme.candidateTextColor); textSize = 11f; alpha = 0.7f
                    })
                    setOnClickListener {
                        settings.flickInputMode = mode
                        session.setPhoneToggleEnabled(mode == ImeSettings.FlickInputMode.FLICK_AND_TOGGLE)
                        onboarding.edit().putBoolean("flick_onboarding_done", true).apply()
                        root.removeView(chooser)
                    }
                }
                choices.addView(card, LinearLayout.LayoutParams(0, dp(76), 1f).apply { setMargins(dp(3), dp(10), dp(3), dp(6)) })
            }
            option("フリックのみ", "連続タップでも同じ文字", ImeSettings.FlickInputMode.FLICK_ONLY)
            option("タップも使う", "連続タップで あ→い→う", ImeSettings.FlickInputMode.FLICK_AND_TOGGLE)
            chooser.addView(choices)
            chooser.addView(android.widget.TextView(this).apply {
                text = "あとから入力設定で変更できます"; textSize = 11f; alpha = 0.65f; setTextColor(theme.candidateTextColor)
            })
            root.addView(chooser, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(164), Gravity.CENTER).apply {
                setMargins(dp(12), 0, dp(12), 0)
            })
        }
        return root
    }

    private fun handleToolAction(action: CandidateStripView.ToolAction) {
        if (!fieldPolicy.incognito &&
            (currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0) {
            val stats = getSharedPreferences("input_statistics", Context.MODE_PRIVATE)
            if (stats.getBoolean("enabled", false)) {
                val key = "tool_${action.name}"
                stats.edit().putLong(key, stats.getLong(key, 0L) + 1).apply()
            }
        }
        when (action) {
            CandidateStripView.ToolAction.MEETS_BEAM -> {
                val pkg = currentInputEditorInfo?.packageName.orEmpty()
                val url = me.zssu.ime.settings.BeamAccessibilityService.browserUrl(pkg)
                val intent = Intent(this, me.zssu.ime.settings.MeetsBeamActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    .putExtra("beam_package", pkg)
                if (url != null) intent.putExtra("beam_url", url)
                startActivity(intent)
            }
            CandidateStripView.ToolAction.SNIPPETS -> {
                if (!fieldPolicy.incognito) candidateView?.showSnippets(SnippetStore(this).entries())
                else Toast.makeText(this, "機密モードでは定型文を表示しません", Toast.LENGTH_SHORT).show()
            }
            CandidateStripView.ToolAction.PROOFREAD -> startProofreading()
            CandidateStripView.ToolAction.RECONVERT -> {
                cancelKarukanConversion(clearReading = true, revertMozc = false)
                render(session.undo(), requestNeural = false)
            }
            CandidateStripView.ToolAction.EDIT -> candidateView?.showEditPanel()
            CandidateStripView.ToolAction.AI_STYLE -> candidateView?.showStyleSelector()
            CandidateStripView.ToolAction.CLIPBOARD -> {
                if (settings.clipboardHistoryEnabled && !fieldPolicy.incognito) {
                    clipboardHistory.record(this, getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    candidateView?.showClipboardHistory(clipboardHistory.items(settings.clipboardRetentionMinutes * 60_000L))
                } else {
                    candidateView?.showClipboardHistory(emptyList())
                }
            }
            CandidateStripView.ToolAction.EMOJI -> candidateView?.showEmoji(
                emojiRepository.recents(),
                emojiRepository.favorites(),
                CandidateStripView.EmojiPage.RECENT,
            )
            CandidateStripView.ToolAction.ONE_HAND_CYCLE -> cycleOneHandMode()
            CandidateStripView.ToolAction.SETTINGS -> startActivity(
                Intent(this, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Applies the selected [style] to the current text. If Mozc holds a composition its preedit
     * is transformed; otherwise the text immediately before the cursor is rewritten.
     */
    private fun applyStyleCorrection(style: TextStyle) {
        val ic = currentInputConnection ?: return
        if (isComposing) {
            val preedit = session.currentPreedit()
            if (preedit.isNotEmpty()) {
                val corrected = TextStyleEngine.apply(preedit, style)
                cancelKarukanConversion(clearReading = true, revertMozc = false)
                session.resetContext()
                isComposing = false
                isConverting = false
                segmentBar?.visibility = View.GONE
                currentSegmentCount = 0
                renderedPreeditCursor = 0
                keyboardView?.isConversionAvailable = false
                ic.commitText(corrected, 1)
                candidateView?.clear()
            }
        } else {
            val before = ic.getTextBeforeCursor(MAX_STYLE_CORRECTION_LENGTH, 0) ?: return
            if (before.isBlank()) return
            var text = before.toString()
            if (text.length >= MAX_STYLE_CORRECTION_LENGTH) {
                val boundary = maxOf(
                    text.lastIndexOf('。'),
                    text.lastIndexOf('！'),
                    text.lastIndexOf('？'),
                    text.lastIndexOf('\n'),
                )
                if (boundary >= 0) text = text.substring(boundary + 1)
            }
            if (text.isBlank()) return
            val leading = text.trimStart()
            val prefixLen = text.length - leading.length
            val corrected = TextStyleEngine.apply(leading, style)
            if (corrected != leading) {
                ic.deleteSurroundingText(text.length, 0)
                val prefix = text.take(prefixLen)
                ic.commitText(prefix + corrected, 1)
            }
        }
        Toast.makeText(
            this,
            "「${style.label}」に補正しました",
            Toast.LENGTH_SHORT,
        ).show()
    }

    /**
     * One toolbar button walks 全幅 → 左 → 右 → 全幅. Cycling (rather than two directional
     * buttons) means the way back is the same tap that got you here, so nobody is stranded in a
     * narrow keyboard with no obvious exit.
     *
     * Updates the existing view's gravity and width in place instead of rebuilding, so a live
     * composition survives the switch.
     */
    private fun cycleOneHandMode() {
        val next = when (effectiveOneHandMode()) {
            ImeSettings.OneHandMode.OFF -> ImeSettings.OneHandMode.LEFT
            ImeSettings.OneHandMode.LEFT -> ImeSettings.OneHandMode.RIGHT
            ImeSettings.OneHandMode.RIGHT -> ImeSettings.OneHandMode.OFF
        }
        val profile = activeProfile
        if (profile != null) {
            activeProfile = profile.copy(oneHandMode = next.name).also(appProfiles::save)
        } else {
            settings.oneHandMode = next
        }
        val content = contentLayout
        val panel = panelView
        if (content == null || panel == null) {
            setInputView(onCreateInputView())
            return
        }
        val mode = effectiveOneHandMode()
        val gravity = when (mode) {
            ImeSettings.OneHandMode.LEFT -> Gravity.START
            ImeSettings.OneHandMode.RIGHT -> Gravity.END
            ImeSettings.OneHandMode.OFF -> Gravity.CENTER_HORIZONTAL
        }
        content.gravity = gravity
        panel.gravity = gravity
        val lp = content.layoutParams as? LinearLayout.LayoutParams
        if (lp != null) {
            lp.width = if (mode == ImeSettings.OneHandMode.OFF) {
                LinearLayout.LayoutParams.MATCH_PARENT
            } else {
                (resources.displayMetrics.widthPixels * settings.oneHandWidth).toInt()
            }
            content.layoutParams = lp
        }
        candidateView?.oneHandMode = when (mode) {
            ImeSettings.OneHandMode.OFF -> CandidateStripView.OneHandDisplayMode.FULL
            ImeSettings.OneHandMode.LEFT -> CandidateStripView.OneHandDisplayMode.LEFT
            ImeSettings.OneHandMode.RIGHT -> CandidateStripView.OneHandDisplayMode.RIGHT
        }
    }

    /** Keeps the bottom-left key as the language switch on the mixed QWERTY alphabet plane. */
    private fun adaptLayoutForStyle(source: KeyboardLayout): KeyboardLayout {
        return effectiveKeyboardStyle().adapt(source)
    }

    /**
     * Material You unless the user has dropped a theme file in.
     *
     * A user override wins because they asked for it explicitly; otherwise we follow the system
     * palette, which also means the keyboard tracks light/dark without a setting.
     */
    private fun resolveTheme(): KeyboardTheme {
        val selected = settings.activeThemeId
        val base = if (selected == MaterialYouTheme.ID) {
            MaterialYouTheme.create(this, forceDark = settings.pureBlack)
        } else {
            repository.loadTheme(selected)
                ?: MaterialYouTheme.create(this, forceDark = settings.pureBlack)
        }
        val sized = base.copy(
            keyHeightDp = base.keyHeightDp * effectiveKeyHeightScale(),
            hapticFeedback = settings.hapticFeedbackEnabled,
        )
        return if (settings.pureBlack) sized.asPureBlack() else sized
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        tryApplyProofreadResult()
        editAnchor = null
        priorityCandidates.invalidate()
        refreshZenzai()
        meaningPopup?.hide(notify = false)
        appProfiles.recordSeenPackage(info?.packageName)
        val nextProfile = appProfiles.profileFor(info?.packageName)
        val nextPolicy = effectivePolicy(InputFieldPolicy.from(info), nextProfile)
        // Settings live in another process's Activity, so this is the first moment we can notice
        // they changed. Rebuilding only on a revision bump keeps the common case free.
        val structureChanged =
            nextPolicy != fieldPolicy ||
                nextProfile != activeProfile ||
                viewStructureFingerprint() != builtStructureFingerprint
        when (
            InputViewRefreshPolicy.target(
                structureChanged = structureChanged,
                revisionChanged = combinedRevision() != builtFromRevision,
            )
        ) {
            InputViewRefreshPolicy.Target.REBUILD -> {
                activeProfile = nextProfile
                fieldPolicy = nextPolicy
                setInputView(onCreateInputView())
            }
            InputViewRefreshPolicy.Target.UPDATE_PANEL -> {
                // Background and opacity are paint-only settings. Keep the already inset, attached
                // view in place so it cannot be transiently measured across the navigation bar.
                panelView?.setBackgroundImage(
                    settings.backgroundImage,
                    settings.backgroundOpacity,
                )
                builtFromRevision = combinedRevision()
            }
            InputViewRefreshPolicy.Target.NONE -> Unit
        }
        session.setIncognitoMode(nextPolicy.incognito)
        session.setTypingCorrectionEnabled(activeProfile?.typingCorrection ?: true)
        when (InputRestartRouting.target(restarting, isComposing)) {
            InputRestartRouting.Target.PRESERVE_COMPOSITION -> {
                // Android is restarting the same editor with its composing span intact. Keep both
                // sides of that composition alive, including candidates lost by a view rebuild.
                keyboardView?.isConversionAvailable =
                    layout?.inputStyle?.fullWidthSpace == true
                candidateView?.setCandidates(lastCandidates, lastFocusedCandidateIndex, converting = isConverting)
            }
            InputRestartRouting.Target.RESET_SESSION -> {
                // In particular, a restart while our editor-facing state is idle must not retain
                // an invisible Mozc conversion. Its result could otherwise surface on the next key.
                cancelKarukanConversion(clearReading = true, revertMozc = false)
                session.resetContext()
                isComposing = false
                isConverting = false
                segmentBar?.visibility = View.GONE
                currentSegmentCount = 0
                renderedPreeditCursor = 0
                keyboardView?.isConversionAvailable = false
                lastCandidates = emptyList()
                lastFocusedCandidateIndex = -1
                candidateView?.clear()
            }
        }
    }

    /**
     * Rebuilds the keyboard so a light/dark switch or a wallpaper recolour is picked up. The
     * dynamic palette is read at view-creation time, so without this the keyboard keeps the colours
     * it was born with until the IME process restarts.
     *
     * Only a night-mode change triggers a rebuild: locale, font scale and other configuration
     * changes do not affect the Material You palette, and a full rebuild destroys any live
     * composition.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val nightMode = newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        if (nightMode == lastNightMode) return
        lastNightMode = nightMode
        setInputView(onCreateInputView())
    }

    override fun onFinishInput() {
        super.onFinishInput()
        zenzai.cancel()
        zenzaiState = null
        zenzaiCandidate = null
        meaningPopup?.hide(notify = false)
        cancelKarukanConversion(clearReading = true, revertMozc = false)
        // Anything half-composed at this point can no longer be committed anywhere sensible.
        session.resetContext()
        isComposing = false
        isConverting = false
        segmentBar?.visibility = View.GONE
        currentSegmentCount = 0
        renderedPreeditCursor = 0
        keyboardView?.isConversionAvailable = false
        currentInputConnection?.finishComposingText()
        candidateView?.clear()
    }

    /**
     * Drops mozc's composition when the editor moves or replaces its composing span behind us.
     *
     * Editors are allowed to mutate their text without sending a key event. Chrome's address-bar
     * clear button is a common example: without this callback, the underline disappears from the
     * editor but the old reading remains inside mozc and is prepended to the next word.
     *
     * Reading edits restore a verified interior caret. Conversion keeps the editor caret at the
     * composing end and shows clause focus with spans. Delayed in-span notifications must not
     * cancel that conversion; actual outside selections or removed spans reset the session.
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )
        // setComposingText has to place the caret at the end before setSelection can move it into
        // the middle. Some editors report that temporary position synchronously, even inside a
        // batch edit; it is an implementation detail of our render, not an external edit.
        if (editorStateUpdateInProgress) {
            return
        }
        if (!SelectionUpdate.invalidatesComposition(
                isComposing = isComposing,
                newSelectionStart = newSelStart,
                newSelectionEnd = newSelEnd,
                composingStart = candidatesStart,
                composingEnd = candidatesEnd,
                preeditCursor = renderedPreeditCursor,
                converting = isConverting,
            )
        ) {
            return
        }

        // Rapid edits can queue several callbacks. A callback for an older render must not
        // finish the newer composing span and silently commit its kana.
        val request = android.view.inputmethod.ExtractedTextRequest().apply { flags = InputConnection.GET_TEXT_WITH_STYLES }
        val extracted = runCatching { currentInputConnection?.getExtractedText(request, 0) }.getOrNull()
        if (extracted != null) {
            if (SelectionUpdate.isStale(newSelStart, newSelEnd,
                    extracted.startOffset + extracted.selectionStart,
                    extracted.startOffset + extracted.selectionEnd)) return
            // Some editors combine the new caret with old composing coordinates. A verified
            // live span is stronger evidence than those delayed coordinates.
            val liveStart = if (isConverting) CompositionCursor.verifiedStart(renderedPreedit, compositionSnapshot(extracted)) else null
            if (liveStart != null && newSelStart == newSelEnd &&
                newSelStart.toLong() in liveStart.toLong()..(liveStart.toLong() + renderedPreedit.length)) return
        }

        // Clear the guard first: finishComposingText can synchronously cause another selection
        // callback in some editors.
        isComposing = false
        isConverting = false
        segmentBar?.visibility = View.GONE
        currentSegmentCount = 0
        renderedPreeditCursor = 0
        keyboardView?.isConversionAvailable = false
        cancelKarukanConversion(clearReading = true, revertMozc = false)
        session.resetContext()
        currentInputConnection?.finishComposingText()
        candidateView?.clear()
    }

    override fun onDestroy() {
        enginePreferences.unregisterOnSharedPreferenceChangeListener(enginePreferenceListener)
        mainHandler.removeCallbacks(proofreadApplyRetry)
        cancelKarukanSuggestions()
        zenzai.close()
        clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
        clipboardManager = null
        panelView?.releaseBitmap()
        karukanEngine.close()
        session.close()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            // The optional neural model is the largest reclaimable allocation. Mozc remains warm,
            // so ordinary input is instant and Karukan is loaded again only if explicitly used.
            karukanEngine.unload()
        }
    }

    private fun onKeyOutput(output: KeyOutput, key: KeySpec, direction: FlickDirection) {
        val started = System.nanoTime()
        try { handleKeyOutput(output, key, direction) }
        finally { InputPerformance.record(this, "key", System.nanoTime() - started) }
    }

    private fun handleKeyOutput(output: KeyOutput, key: KeySpec, direction: FlickDirection) {
        val clientContext = org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Context.newBuilder()
        if (fieldPolicy.incognito) {
            clientContext.inputFieldType = org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Context.InputFieldType.PASSWORD
        } else {
            val before = currentInputConnection?.getTextBeforeCursor(160, 0)?.toString().orEmpty()
            clientContext.precedingText = before.dropLast(if (isComposing) renderedPreeditCursor.coerceAtMost(before.length) else 0).takeLast(80)
        }
        session.clientContext = clientContext.build()
        when (val action = output.action) {
            is KeyAction.Input -> {
                cancelKarukanConversion()
                render(session.sendText(action.text, output.label))
            }

            // The dakuten/small-kana cycle is a table entry, not a session command: mozc's flick
            // table maps '*' onto "advance the preceding kana one step" (あ→ぁ→あ, は→ば→ぱ→は).
            is KeyAction.ModifyChar -> {
                cancelKarukanConversion()
                render(session.sendText(CYCLE_MODIFIER_KEY))
            }

            is KeyAction.InsertSymbol -> handleSymbol(action.text)

            is KeyAction.Undo -> {
                cancelKarukanConversion(clearReading = true)
                render(session.undo())
            }

            is KeyAction.Backspace -> handleBackspace()

            is KeyAction.Space -> handleSpace()

            // The state decides the visible operation: convert while composing and insert the
            // plane's normal space while idle. Enter has its own key.
            is KeyAction.Convert -> handleSpace()

            is KeyAction.Enter -> handleEnter()

            is KeyAction.MoveCursor -> handleCursorMove(
                delta = action.delta,
                adjustSegment = isConverting && (key.center.action is KeyAction.Space || key.center.action is KeyAction.Convert),
            )

            is KeyAction.SwitchLayout -> switchLayout(action.layoutId)

            // Consumed by the keyboard view, which owns the shift state because it has to draw it.
            // Reaching here would mean the view stopped intercepting it.
            is KeyAction.Shift -> Log.w(TAG, "shift reached the service; view did not consume it")

            is KeyAction.ShowImePicker -> {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            }
        }
    }

    /**
     * Symbols finalise whatever is composing and then go straight into the editor.
     *
     * They are not conversion input — nobody wants "あ(" offered as a candidate — and committing
     * first means the symbol lands after the kana rather than in the middle of it.
     */
    private fun handleSymbol(text: String) {
        if (isComposing) submitCurrentComposition()
        currentInputConnection?.commitText(text, 1)
    }

    /**
     * Space, which mozc only handles while something is composing.
     *
     * With an empty composition it returns the key unconsumed on every plane — it has nowhere to
     * put a space and leaves the character to the client. Rendering that empty response was doing
     * nothing at all, so space appeared to work only as the second keystroke of a word.
     */
    private fun handleSpace() {
        conversionInteraction.convertPressed(isComposing, isConverting)
        if (karukanConversionActive) {
            // The user is navigating Mozc's candidates; discard an outstanding neural result.
            cancelKarukanConversion(clearReading = true, revertMozc = false)
            handleMozcSpace()
            return
        }
        if (shouldUseKarukan()) {
            startKarukanConversion()
            return
        }
        val state = session.convertOrSpace()
        if (state == null && isComposing) {
            currentInputConnection?.let(::recoverFromLostSession)
            return
        }
        if ((state == null || !state.consumed) && !isComposing) {
            val fullWidth = layout?.inputStyle?.fullWidthSpace ?: false
            currentInputConnection?.commitText(if (fullWidth) FULL_WIDTH_SPACE else " ", 1)
        }
        if (state != null) render(state)
    }

    private fun handleBackspace() {
        // Route from the state *before* sending Backspace. If the last composing character is
        // removed, Mozc's response is empty — indistinguishable from a key pressed while already
        // idle — and inspecting only that response used to delete one extra editor character.
        val hadComposition = isComposing
        cancelKarukanConversion()
        val ic = currentInputConnection
        val rawKeyEvents = fieldPolicy.rawKeyEvents
        val hasSelection = if (hadComposition || rawKeyEvents) {
            false
        } else {
            runCatching { !ic?.getSelectedText(0).isNullOrEmpty() }.getOrDefault(false)
        }
        val state = if (hadComposition) {
            session.sendSpecialKey(KeyEvent.SpecialKey.BACKSPACE)
        } else {
            null
        }
        when (BackspaceRouting.target(hadComposition, hasSelection, rawKeyEvents)) {
            BackspaceRouting.Target.MOZC_COMPOSITION -> Unit
            BackspaceRouting.Target.EDITOR_SELECTION -> ic?.commitText("", 1)
            BackspaceRouting.Target.EDITOR_PREVIOUS_CODE_POINT -> {
                if (ic != null && !ic.deleteSurroundingTextInCodePoints(1, 0)) {
                    // Some older/custom InputConnections implement only the legacy UTF-16 API.
                    if (!ic.deleteSurroundingText(1, 0)) sendRawBackspace(ic)
                }
            }
            BackspaceRouting.Target.RAW_KEY_EVENT -> ic?.let(::sendRawBackspace)
        }
        render(state)
    }

    private fun sendRawBackspace(ic: android.view.inputmethod.InputConnection) {
        sendRawKeyPress(ic, android.view.KeyEvent.KEYCODE_DEL)
    }

    private fun sendRawKeyPress(
        ic: android.view.inputmethod.InputConnection,
        keyCode: Int,
    ) {
        val now = android.os.SystemClock.uptimeMillis()
        ic.sendKeyEvent(
            android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0)
        )
        ic.sendKeyEvent(
            android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, keyCode, 0)
        )
    }

    /**
     * 確定 when something is composing, otherwise the editor's own Enter.
     *
     * The composing check has to happen *before* submitting — after a successful SUBMIT the
     * composition is by definition gone, so inspecting the resulting state would make every Enter
     * also fire the editor action and send the message you were only trying to finalise.
     */
    private fun handleEnter() {
        val partial = ConversionNavigation.partialCandidate(
            isConverting, currentSegmentCount, lastCandidates, lastFocusedCandidateIndex,
        )
        if (partial != null) {
            cancelKarukanConversion(clearReading = true, revertMozc = false)
            suppressPartialLearning = true
            try { render(session.selectCandidate(partial)) } finally { suppressPartialLearning = false }
            return
        }
        zenzaiCandidate?.takeIf { isComposing && !isConverting }?.let {
            render(session.selectCandidate(it))
            return
        }
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val imeOptions = info?.imeOptions ?: 0
        val inputType = info?.inputType ?: 0
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        var target: EnterRouting.Target? =
            EnterRouting.targetForEditor(
                hadComposition = isComposing,
                rawKeyEvents = fieldPolicy.rawKeyEvents,
                imeOptions = imeOptions,
                inputType = inputType,
            )

        while (target != null) {
            val handled = when (target) {
                EnterRouting.Target.SUBMIT_COMPOSITION -> {
                    submitCurrentComposition()
                    true
                }
                EnterRouting.Target.EDITOR_ACTION -> ic.performEditorAction(action)
                EnterRouting.Target.NEWLINE -> ic.commitText("\n", 1)
                EnterRouting.Target.RAW_KEY_EVENT -> {
                    sendRawKeyPress(ic, android.view.KeyEvent.KEYCODE_ENTER)
                    true
                }
            }
            if (handled) return
            target = EnterRouting.fallbackAfterFailure(target, inputType)
        }
    }

    private fun startProofreading() {
        if (fieldPolicy.incognito ||
            (currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) {
            Toast.makeText(this, "この入力欄ではAI文章校正を利用できません", Toast.LENGTH_SHORT).show()
            return
        }
        val ic = currentInputConnection
        if (isComposing) {
            ic?.finishComposingText()
            session.resetContext()
            isComposing = false
            isConverting = false
            segmentBar?.visibility = View.GONE
            currentSegmentCount = 0
            renderedPreeditCursor = 0
            segmentBar?.visibility = View.GONE
            candidateView?.clear()
        }
        val selection = runCatching { ic?.getSelectedText(0)?.toString() }.getOrNull().orEmpty()
        val beforeCursor = runCatching {
            ic?.getTextBeforeCursor(ProofreadPrompt.MAX_TEXT, 0)?.toString()
        }.getOrNull().orEmpty()
        val original = if (selection.isNotEmpty()) selection else beforeCursor
        // Grab whatever text is available, but always open the screen: with an empty field the user
        // can type the text by hand, so the button never looks dead just because nothing was
        // selected.
        if (ic == null) {
            Toast.makeText(this, "この入力欄を読み取れません。校正画面で直接入力できます", Toast.LENGTH_SHORT).show()
        }
        val editorPackage = currentInputEditorInfo?.packageName
        val editorFieldId = currentInputEditorInfo?.fieldId ?: 0
        val editorInputType = currentInputEditorInfo?.inputType ?: 0
        ProofreadRequest.text = original
        ProofreadRequest.result = null
        ProofreadRequest.apply = buildProofreadApply(
            original = original,
            wasSelection = selection.isNotEmpty(),
            editorPackage = editorPackage,
            editorFieldId = editorFieldId,
            editorInputType = editorInputType,
        )
        startProofreadActivity()
    }

    private fun startProofreadActivity() {
        startActivity(
            Intent(this, ProofreadActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
        )
    }

    /**
     * The closure the proofreading screen calls to apply its result.
     *
     * It re-checks the editor identity (package, field id, input type) and the reviewed text
     * against the live editor, so a result can never land in a different field or overwrite text
     * that changed while the screen was open. A throwing connection is contained by the caller.
     */
    private fun buildProofreadApply(
        original: String,
        wasSelection: Boolean,
        editorPackage: String?,
        editorFieldId: Int,
        editorInputType: Int,
    ): (String) -> Boolean = { replacement ->
        val ic = currentInputConnection
        if (ic == null ||
            currentInputEditorInfo?.packageName != editorPackage ||
            currentInputEditorInfo?.fieldId != editorFieldId ||
            currentInputEditorInfo?.inputType != editorInputType
        ) {
            false
        } else if (wasSelection) {
            selectedText(ic) == original && ic.commitText(replacement, 1)
        } else if (original.isEmpty()) {
            // Nothing could be captured, so the user typed the text by hand; there is nothing to
            // verify against, and the result belongs at the caret.
            ic.commitText(replacement, 1)
        } else {
            replaceBeforeCursor(ic, original, replacement)
        }
    }

    private fun selectedText(ic: InputConnection): String? =
        runCatching { ic.getSelectedText(0)?.toString() }.getOrNull()

    /**
     * Replaces the reviewed text that still sits immediately before the caret.
     *
     * The preceding text is confirmed through every source the editor offers before anything is
     * written, and the replacement then re-selects exactly that span so a mismatch can still be
     * caught and aborted. If no source corroborates the original, nothing is touched.
     */
    private fun replaceBeforeCursor(ic: InputConnection, original: String, replacement: String): Boolean {
        if (original.isEmpty()) return false
        val before = runCatching { ic.getTextBeforeCursor(original.length, 0)?.toString() }.getOrNull()
        val extracted = runCatching {
            ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        }.getOrNull()
        val collapsed = extracted == null || extracted.selectionStart == extracted.selectionEnd
        if (!collapsed) return false
        val extractedBefore = extracted?.let { e ->
            val text = e.text ?: return@let null
            text.substring(0, e.selectionEnd.coerceIn(0, text.length))
        }
        val surroundingBefore = surroundingBeforeCursor(ic, original.length)
        if (!ProofreadApply.beforeCursorMatches(original, before, extractedBefore, surroundingBefore)) return false
        val cursor = extracted?.let { it.startOffset + it.selectionEnd }
        if (cursor != null && cursor >= original.length) {
            return replaceBySelection(ic, cursor, original, replacement)
        }
        // No absolute anchor was available, so delete the verified prefix and commit in one batch.
        return runCatching {
            ic.beginBatchEdit()
            try {
                if (!ic.deleteSurroundingText(original.length, 0)) false
                else { ic.commitText(replacement, 1); true }
            } finally { ic.endBatchEdit() }
        }.getOrDefault(false)
    }

    private fun replaceBySelection(ic: InputConnection, cursor: Int, original: String, replacement: String): Boolean =
        runCatching {
            ic.beginBatchEdit()
            try {
                if (!ic.setSelection(cursor - original.length, cursor)) {
                    false
                } else if (selectedText(ic) != original) {
                    ic.setSelection(cursor, cursor); false
                } else if (ic.commitText(replacement, 1)) {
                    true
                } else {
                    ic.setSelection(cursor, cursor); false
                }
            } finally { ic.endBatchEdit() }
        }.getOrDefault(false)

    /**
     * Text before the caret from the API 31+ surrounding-text API.
     *
     * WebView and a few OEM connections answer getTextBeforeCursor with null while still exposing
     * surrounding text, so this is the last source consulted before giving up.
     */
    private fun surroundingBeforeCursor(ic: InputConnection, length: Int): String? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return null
        val surrounding = runCatching { ic.getSurroundingText(length, 0, 0) }.getOrNull() ?: return null
        val text = surrounding.text?.toString() ?: return null
        return text.substring(0, surrounding.selectionStart.coerceIn(0, text.length))
    }

    /**
     * Applies a result handed back by the proofreading screen once the editor is ready.
     *
     * Returning from an Activity does not guarantee [currentInputConnection] is live yet, so a
     * missing connection is retried briefly rather than dropping the result. The apply closure
     * re-verifies the target itself, and a hostile or throwing InputConnection is contained so the
     * IME stays up.
     */
    private fun tryApplyProofreadResult() {
        val result = ProofreadRequest.result ?: return
        val apply = ProofreadRequest.apply
        if (apply == null) { ProofreadRequest.clear(); return }
        if (currentInputConnection == null && proofreadApplyAttempts < PROOFREAD_APPLY_MAX_ATTEMPTS) {
            proofreadApplyAttempts++
            mainHandler.postDelayed(proofreadApplyRetry, PROOFREAD_APPLY_RETRY_MS)
            return
        }
        val applied = runCatching { apply(result) }.getOrDefault(false)
        ProofreadRequest.clear()
        proofreadApplyAttempts = 0
        Toast.makeText(
            this,
            if (applied) "校正結果を適用しました"
            else "入力欄の文章が変わったため適用できませんでした。コピーをご利用ください",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun handleEditCommand(command: String) {
        val ic = currentInputConnection ?: return
        when (command) {
            "all" -> { ic.performContextMenuAction(android.R.id.selectAll); return }
            "copy" -> { ic.performContextMenuAction(android.R.id.copy); return }
            "cut" -> { ic.performContextMenuAction(android.R.id.cut); return }
            "paste" -> { ic.performContextMenuAction(android.R.id.paste); return }
        }
        val extracted = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0) ?: return
        val text = extracted.text?.toString() ?: return
        val cursor = extracted.selectionEnd.coerceIn(0, text.length)
        if (command == "select") {
            editAnchor = if (editAnchor == null) extracted.startOffset + cursor else null
            return
        }
        val target = when (command) {
            "home" -> text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)).let { if (cursor == 0) 0 else it + 1 }
            "end" -> text.indexOf('\n', cursor).let { if (it < 0) text.length else it }
            "word_left", "word_right" -> {
                val breaker = java.text.BreakIterator.getWordInstance(java.util.Locale.JAPANESE)
                breaker.setText(text)
                val next = if (command == "word_left") breaker.preceding(cursor) else breaker.following(cursor)
                if (next == java.text.BreakIterator.DONE) cursor else next
            }
            "left" -> if (cursor > 0) text.offsetByCodePoints(cursor, -1) else 0
            else -> if (cursor < text.length) text.offsetByCodePoints(cursor, 1) else cursor
        }
        val absolute = extracted.startOffset + target
        ic.setSelection(editAnchor ?: absolute, absolute)
    }

    private fun handleCursorMove(delta: Int, adjustSegment: Boolean, forceClauseMove: Boolean = false) {
        if (karukanConversionActive) cancelKarukanConversion(clearReading = true, revertMozc = false)
        session.stopKeyToggling()
        if (
            CursorRouting.target(
                hasComposition = isComposing,
                rawKeyEvents = fieldPolicy.rawKeyEvents,
            ) == CursorRouting.Target.RAW_KEY_EVENT
        ) {
            val rawKey = if (delta < 0) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT
            } else {
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            }
            currentInputConnection?.let { sendRawKeyPress(it, rawKey) }
            return
        }
        val resize = isConverting && conversionInteraction.resize(adjustSegment, forceClauseMove)
        val wasComposing = isComposing
        val state = if (isComposing) session.moveCursor(delta, resize) else null
        if (wasComposing) {
            if (state == null) render(null, requestNeural = false)
            else if (state.hasComposition) render(state, requestNeural = false)
            return
        }
        // Outside a composition, move the editor caret instead.
        val ic = currentInputConnection ?: return
        val extracted = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0) ?: return
        val start = minOf(extracted.selectionStart, extracted.selectionEnd)
        val end = maxOf(extracted.selectionStart, extracted.selectionEnd)
        val target = if (start != end) { if (delta < 0) start else end }
            else EditorCursor.step(extracted.text?.toString().orEmpty(), start, delta)
        ic.setSelection(extracted.startOffset + target, extracted.startOffset + target)
    }

    private fun switchLayout(layoutId: String) {
        // The layouts point inside their own family; the user's style decides which family the
        // kana and alphabet planes actually come from, so every switch goes through it.
        val resolved = effectiveKeyboardStyle().resolve(layoutId)
        val next = repository.loadLayout(resolved)
        if (next == null) {
            Log.w(TAG, "layout $resolved not found; staying on ${layout?.id}")
            return
        }
        // Finalise before swapping planes so a half-typed kana is not silently discarded.
        submitCurrentComposition()
        layout = next
        keyboardView?.layout = adaptLayoutForStyle(next)
        session.applyInputStyle(next.inputStyle)
    }

    private fun initialLayoutId(policy: InputFieldPolicy): String = when (policy.plane) {
        InputFieldPolicy.Plane.USER_DEFAULT ->
            settings.activeLayoutId
                ?.takeIf { activeProfile == null }
                ?.takeIf { repository.loadLayout(it) != null }
                ?: effectiveKeyboardStyle().defaultLayoutId
        InputFieldPolicy.Plane.ASCII -> effectiveKeyboardStyle().resolve("qwerty_ascii")
        InputFieldPolicy.Plane.NUMERIC -> when (effectiveKeyboardStyle()) {
            me.zssu.ime.keyboard.KeyboardStyle.QWERTY -> "qwerty_symbol"
            else -> "flick_symbol"
        }
    }

    private fun compositionSnapshot(extracted: android.view.inputmethod.ExtractedText): CompositionCursor.Snapshot? {
        val text = extracted.text as? Spanned ?: return null
        val composing = text.getSpans(0, text.length, Any::class.java).filter { text.getSpanFlags(it) and Spanned.SPAN_COMPOSING != 0 }
        if (composing.isEmpty()) return null
        return CompositionCursor.Snapshot(text.toString(), extracted.startOffset,
            composing.minOf { text.getSpanStart(it) }, composing.maxOf { text.getSpanEnd(it) })
    }

    private fun render(state: MozcSession.State?, requestNeural: Boolean = true) {
        val ic = currentInputConnection ?: return
        if (state == null) {
            recoverFromLostSession(ic)
            return
        }

        cancelKarukanSuggestions()
        zenzai.cancel()
        zenzaiCandidate = null
        if (state.committedText.isNotEmpty() && !suppressPartialLearning && !fieldPolicy.incognito &&
            (currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0) {
            zenzai.confirm(zenzaiState?.preeditReading.orEmpty(),
                session.clientContext?.precedingText.orEmpty(), state.committedText)
        }
        zenzaiState = state
        val hadComposition = isComposing
        val removeOldEditorComposition =
            BackspaceRouting.shouldRemoveOldEditorComposition(
                hadComposition = hadComposition,
                nextHasComposition = state.hasComposition,
                committedText = state.committedText,
            )
        isComposing = state.hasComposition
        if (state.committedText.isNotEmpty()) conversionInteraction.observe(false)
        isConverting = state.isConverting
        if (!isConverting) segmentEditing = false
        candidateView?.segmentEditing = segmentEditing
        segmentBar?.visibility = if (isConverting && segmentEditing) View.VISIBLE else View.GONE
        currentSegmentCount = if (state.isConverting) state.segments.size else 0
        val focusedSegment = state.segments.indexOfFirst { it.highlighted }
        segmentSummary?.apply {
            removeAllViews()
            if (state.isConverting) state.segments.forEachIndexed { index, segment ->
                addView(android.widget.TextView(this@ZinnaImeService).apply {
                    text = if (segment.highlighted) "【${segment.text}】" else segment.text
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setPadding(16, 0, 16, 0)
                    setTextColor(theme.candidateTextColor)
                    setBackgroundColor(if (segment.highlighted) theme.keyColor else android.graphics.Color.TRANSPARENT)
                    contentDescription = "文節${index + 1} ${segment.text}" + if (segment.highlighted) " 選択中" else ""
                    setOnClickListener {
                        if (zenzaiState !== state || !isConverting || focusedSegment < 0) return@setOnClickListener
                        repeat(kotlin.math.abs(index - focusedSegment)) {
                            handleCursorMove(if (index < focusedSegment) -1 else 1, adjustSegment = false, forceClauseMove = true)
                        }
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (48 * resources.displayMetrics.density).toInt()))
            }
            getChildAt(focusedSegment)?.let { selected ->
                post { if (zenzaiState === state) selected.requestRectangleOnScreen(android.graphics.Rect(0, 0, selected.width, selected.height)) }
            }
        }
        segmentBar?.let { bar ->
            val controls = bar.getChildAt(1) as? LinearLayout
            controls?.getChildAt(0)?.isEnabled = focusedSegment > 0
            controls?.getChildAt(3)?.isEnabled = focusedSegment >= 0 && focusedSegment < state.segments.lastIndex
        }
        val cursorBefore = if (!state.isConverting && hadComposition && state.preeditCursor < state.preedit.length &&
            state.committedText.isEmpty() && state.deleteBeforeCodePoints == 0) {
            val request = android.view.inputmethod.ExtractedTextRequest().apply { flags = InputConnection.GET_TEXT_WITH_STYLES }
            runCatching { ic.getExtractedText(request, 0) }.getOrNull()?.let(::compositionSnapshot)
        } else null
        val cursorPlan = CompositionCursor.plan(state.preedit, state.preeditCursor, state.isConverting, renderedPreedit, cursorBefore)
        renderedPreeditCursor = cursorPlan.displayOffset
        renderedPreedit = state.preedit
        keyboardView?.isConversionAvailable =
            state.hasComposition && layout?.inputStyle?.fullWidthSpace == true
        editorStateUpdateInProgress = true
        ic.beginBatchEdit()
        try {
            if (state.deleteBeforeCodePoints > 0 && !ic.deleteSurroundingTextInCodePoints(state.deleteBeforeCodePoints, 0)) {
                session.resetContext()
                isComposing = false
                isConverting = false
                segmentBar?.visibility = View.GONE
                currentSegmentCount = 0
                renderedPreeditCursor = 0
                candidateView?.clear()
                return
            }
            if (state.committedText.isNotEmpty()) {
                val committed = ic.commitText(state.committedText, 1)
                if (committed && !fieldPolicy.incognito &&
                    (currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0) {
                    val stats = getSharedPreferences("input_statistics", Context.MODE_PRIVATE)
                    if (stats.getBoolean("enabled", false)) {
                        val length = state.committedText.codePointCount(0, state.committedText.length)
                        stats.edit().putLong("converted_codepoints", stats.getLong("converted_codepoints", 0L) + length).apply()
                    }
                }
            }
            if (state.preedit.isEmpty()) {
                if (removeOldEditorComposition) {
                    // finishComposingText would confirm the previous editor-side preedit. Replacing
                    // its composing span with empty text is what makes the last Backspace visible.
                    ic.commitText("", 1)
                } else {
                    ic.finishComposingText()
                }
            } else {
                val styled = SpannableString(state.preedit).apply {
                    if (state.isConverting && isNotEmpty()) {
                        setSpan(
                            BackgroundColorSpan(CONVERSION_RANGE_COLOR),
                            0,
                            length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                    var start = 0
                    for (segment in state.segments) {
                        val end = (start + segment.text.length).coerceAtMost(length)
                        if (end > start) {
                            setSpan(
                                UnderlineSpan(),
                                start,
                                end,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                            if (segment.highlighted) {
                                setSpan(
                                    BackgroundColorSpan(CONVERSION_FOCUSED_COLOR),
                                    start,
                                    end,
                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                                )
                            }
                        }
                        start = end
                    }
                    if (state.segments.isEmpty() && isNotEmpty()) {
                        setSpan(UnderlineSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }

                // In conversion the caret stays at the end. Interior reading edits use a
                // verified composing span captured BEFORE replacement, never a stale end caret.
                val written = ic.setComposingText(styled, 1)
                if (written && cursorPlan.absoluteSelection != null) {
                    if (!ic.setSelection(cursorPlan.absoluteSelection, cursorPlan.absoluteSelection)) {
                        renderedPreeditCursor = state.preedit.length
                    }
                } else if (!written) {
                    recoverFromLostSession(ic)
                    return
                }
            }
        } finally {
            ic.endBatchEdit()
            editorStateUpdateInProgress = false
        }

        lastCandidates = state.candidates
        lastFocusedCandidateIndex = state.focusedCandidateIndex
        candidateView?.setCandidates(state.candidates, state.focusedCandidateIndex, converting = isConverting)
        if (requestNeural) requestZenzai(state)
    }

    private fun refreshZenzai() {
        val policy = InputFieldPolicy.from(currentInputEditorInfo)
        val enabled = settings.zenzaiPlusEnabled && activeProfile?.neuralEnabled != false && ZenzaiEngine.isBundled(this) && !policy.incognito
        zenzai.cancel()
        zenzaiCandidate = null
        zenzaiState = null
        session.setZenzaiEnabled(enabled, settings.zenzaiMozcEnabled)
        zenzai.setEnabled(enabled)
        if (enabled) zenzai.syncDictionary { zenzaiDictionary = it }
    }

    private fun requestZenzai(state: MozcSession.State) {
        if (state.isConverting || !state.hasComposition || layout?.inputStyle?.fullWidthSpace != true || fieldPolicy.incognito) return
        val reading = state.preeditReading
        val preceding = session.clientContext?.precedingText.orEmpty()
        val prompt = if (session.zenzaiEnabled) ZenzaiConversion.prompt(reading, preceding) else null
        val registered = if (prompt != null) zenzaiDictionary.filter { ZenzaiConversion.prompt(it.reading, "") == ZenzaiConversion.prompt(reading, "") }
            .map { MozcSession.Candidate(-100, it.word, directCommit = true) } else emptyList()
        val base = (registered + state.candidates).distinctBy { it.text }
        var neural: MozcSession.Candidate? = null
        var karukan: List<MozcSession.Candidate> = emptyList()
        fun publish() {
            if (zenzaiState !== state || !isComposing || isConverting) return
            val combined = (karukan + base).distinctBy { it.text }
            karukanSuggestionCandidate = karukan.firstOrNull()
            lastCandidates = neural?.let { ZenzaiConversion.candidates(combined, it) } ?: combined
            lastFocusedCandidateIndex = if (neural != null || karukan.isNotEmpty()) 0 else state.focusedCandidateIndex
            candidateView?.setCandidates(lastCandidates, lastFocusedCandidateIndex, converting = isConverting)
        }
        fun fallback() {
            zenzaiFallbackPending?.let { mainHandler.removeCallbacks(it) }
            zenzaiFallbackPending = null
            if (!canUseKarukan(aiCandidateAvailable = false)) return
            requestKarukanSuggestions(state) { candidates ->
                karukan = candidates.mapIndexed { index, value -> MozcSession.Candidate(-20_000 - index, value, inputReading = reading, directCommit = true) }
                publish()
            }
        }
        publish()
        if (prompt == null) { fallback(); return }
        if (settings.conversionEngine == ImeSettings.ConversionEngine.KARUKAN) fallback()
        else if (settings.conversionEngine == ImeSettings.ConversionEngine.AUTO) {
            val timeout = Runnable { if (zenzaiState === state && neural == null) fallback() }
            zenzaiFallbackPending = timeout
            mainHandler.postDelayed(timeout, 1500)
        }
        zenzai.request(prompt, onUnavailable = { if (zenzaiState === state) fallback() }) { candidate ->
            if (zenzaiState !== state || !isComposing) return@request
            zenzaiFallbackPending?.let { mainHandler.removeCallbacks(it) }
            zenzaiFallbackPending = null
            neural = candidate
            zenzaiCandidate = candidate
            publish()
        }
    }

    private fun requestKarukanSuggestions(state: MozcSession.State, onResult: (List<String>) -> Unit) {
        // Keep the same Japanese-reading eligibility as zenzai; don't infer over unfinished Latin text.
        if (ZenzaiConversion.prompt(state.preeditReading, "") == null) return
        if (karukanSuggestionPending != null || karukanSuggestionActive) return
        val pending = Runnable {
            karukanSuggestionPending = null
            if (zenzaiState !== state || !isComposing || isConverting || !canUseKarukan(false)) return@Runnable
            karukanSuggestionActive = true
            karukanEngine.convert(state.preeditReading, KarukanContextWindow.extract(session.clientContext?.precedingText.orEmpty(), state.preeditReading, KARUKAN_CONTEXT_WINDOW_CHARS), KARUKAN_CANDIDATE_COUNT) { result ->
                karukanSuggestionActive = false
                if (zenzaiState === state && isComposing && !isConverting && result is KarukanEngine.Result.Success) onResult(result.candidates)
            }
        }
        karukanSuggestionPending = pending
        mainHandler.postDelayed(pending, 600)
    }

    private fun cancelKarukanSuggestions() {
        zenzaiFallbackPending?.let { mainHandler.removeCallbacks(it) }
        zenzaiFallbackPending = null
        karukanSuggestionCandidate = null
        karukanSuggestionPending?.let { mainHandler.removeCallbacks(it) }
        karukanSuggestionPending = null
        if (karukanSuggestionActive) karukanEngine.invalidate()
        karukanSuggestionActive = false
    }

    /**
     * A null JNI response leaves Mozc's state unknowable. Preserve the visible characters as
     * ordinary editor text, then clear every local/native conversion marker so the next key starts
     * cleanly instead of releasing an old confirmed string later.
     */
    private fun recoverFromLostSession(ic: android.view.inputmethod.InputConnection) {
        Log.w(TAG, "conversion engine returned no state; finishing visible composition")
        val plan = LostSessionRecovery.plan(isComposing)
        cancelKarukanConversion(clearReading = true, revertMozc = false)
        if (plan.finishEditorComposition) ic.finishComposingText()
        session.resetContext()
        isComposing = false
        isConverting = false
        segmentBar?.visibility = View.GONE
        currentSegmentCount = 0
        renderedPreeditCursor = 0
        keyboardView?.isConversionAvailable = false
        lastCandidates = emptyList()
        lastFocusedCandidateIndex = -1
        candidateView?.clear()
    }

    private fun canUseKarukan(aiCandidateAvailable: Boolean): Boolean = KarukanFallbackPolicy.shouldUse(
        mode = settings.conversionEngine,
        ready = KarukanModelStore.files(this).ready && karukanEngine.isNativeAvailable,
        permitted = activeProfile?.neuralEnabled != false && layout?.inputStyle?.fullWidthSpace == true && !fieldPolicy.incognito &&
            (currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0,
        aiCandidateAvailable = aiCandidateAvailable,
    )

    private fun shouldUseKarukan(): Boolean = isComposing && !isConverting &&
        canUseKarukan(session.zenzaiEnabled && zenzaiCandidate != null)

    /**
     * Runs only explicit conversion through Karukan. Mozc continues to own the flick/12-key
     * composition, so dakuten, small kana, cursor editing and every fallback remain dependable.
     */
    private fun startKarukanConversion() {
        // convertOrSpace owns stopping the toggle and opening the first candidate. Doing it
        // here as well can open conversion early and make that call advance to candidate two.
        val reading = session.currentPreedit()
        if (reading.isEmpty()) {
            handleMozcSpace()
            return
        }
        // Keep Mozc's native conversion alive as a fast baseline. Karukan results are merged into
        // it rather than replacing it, so agreement can be promoted and every native candidate
        // keeps its id/learning behaviour.
        val mozcFallback = session.convertOrSpace()
        val mozcCandidates = mozcFallback?.candidates.orEmpty()
        karukanConversionActive = true
        karukanReading = reading
        render(
            mozcFallback?.takeIf { it.hasComposition } ?: MozcSession.State(
                preedit = reading,
                preeditCursor = reading.length,
                segments = listOf(MozcSession.Segment(reading, highlighted = true)),
                isConverting = true,
                consumed = true,
            )
        )
        val context = if (fieldPolicy.incognito) {
            ""
        } else {
            val before = currentInputConnection
                ?.getTextBeforeCursor(KARUKAN_CONTEXT_CHARS, 0)
                ?.toString()
                .orEmpty()
            KarukanContextWindow.extract(before, reading, KARUKAN_CONTEXT_WINDOW_CHARS)
        }
        karukanEngine.convert(reading, context, KARUKAN_CANDIDATE_COUNT) { result ->
            if (!karukanConversionActive || karukanReading != reading || !isComposing) {
                return@convert
            }
            when (result) {
                is KarukanEngine.Result.Success -> {
                    val candidates = ConversionNavigation.appendNeuralCandidates(
                        native = mozcCandidates,
                        neural = result.candidates,
                        reading = reading,
                        limit = mozcCandidates.size + KARUKAN_CANDIDATE_COUNT,
                    )
                    // Keep Mozc's segmented preedit and focus; whole-sentence AI choices can be
                    // selected explicitly without replacing the active segment conversion.
                    val focused = lastFocusedCandidateIndex
                    cancelKarukanConversion(clearReading = true, revertMozc = false)
                    lastCandidates = candidates
                    lastFocusedCandidateIndex = focused
                    candidateView?.setCandidates(candidates, focused, converting = isConverting)
                }
                is KarukanEngine.Result.Failure -> fallbackFromKarukan(
                    result.message,
                    mozcFallback,
                )
            }
        }
    }

    private fun fallbackFromKarukan(message: String, fallback: MozcSession.State?) {
        cancelKarukanConversion(clearReading = true, revertMozc = false)
        Toast.makeText(this, "$message", Toast.LENGTH_SHORT).show()
        render(fallback ?: session.convertOrSpace())
    }

    private fun handleMozcSpace() {
        val state = session.convertOrSpace()
        if (state == null && isComposing) {
            currentInputConnection?.let(::recoverFromLostSession)
            return
        }
        if ((state == null || !state.consumed) && !isComposing) {
            val fullWidth = layout?.inputStyle?.fullWidthSpace ?: false
            currentInputConnection?.commitText(if (fullWidth) FULL_WIDTH_SPACE else " ", 1)
        }
        if (state != null) render(state)
    }

    private fun submitCurrentComposition() {
        (zenzaiCandidate ?: karukanSuggestionCandidate)?.takeIf { isComposing }?.let { render(session.selectCandidate(it)); return }
        if (karukanConversionActive) {
            val candidate = lastCandidates.getOrNull(lastFocusedCandidateIndex)
                ?: lastCandidates.firstOrNull()
            cancelKarukanConversion(clearReading = true, revertMozc = false)
            if (candidate != null) {
                render(session.selectCandidate(candidate))
            } else {
                render(session.submit())
            }
        } else {
            render(session.submit())
        }
    }

    private fun cancelKarukanConversion(
        clearReading: Boolean = true,
        revertMozc: Boolean = true,
    ) {
        cancelKarukanSuggestions()
        // Hybrid conversion leaves Mozc's baseline candidate window open behind the neural UI.
        // Before ordinary typing/backspace, restore the literal reading so Mozc cannot commit its
        // focused candidate as a side effect of handling the new key.
        if (karukanConversionActive && revertMozc) session.revert()
        if (karukanConversionActive || karukanReading.isNotEmpty()) karukanEngine.invalidate()
        karukanConversionActive = false
        if (clearReading) karukanReading = ""
    }

    /** Refreshes only explicit priority state; candidate ids and Mozc's conversion state stay put. */
    private fun refreshCandidatePriorities() {
        val focusedId = lastCandidates.getOrNull(lastFocusedCandidateIndex)?.id
        lastCandidates = lastCandidates.map { candidate ->
            candidate.copy(
                priorityMatch = priorityCandidates.match(
                    candidate.inputReading,
                    candidate.text,
                )
            )
        }.withIndex()
            .sortedWith(
                compareBy<IndexedValue<MozcSession.Candidate>> {
                    when (it.value.priorityMatch) {
                        PriorityMatch.EXACT -> 0
                        PriorityMatch.SIMILAR -> 1
                        PriorityMatch.NONE -> 2
                    }
                }.thenBy { it.index }
            )
            .map { it.value }
        lastFocusedCandidateIndex =
            focusedId?.let { id -> lastCandidates.indexOfFirst { it.id == id } } ?: -1
        candidateView?.setCandidates(lastCandidates, lastFocusedCandidateIndex, converting = isConverting)
    }

    private fun effectiveKeyboardStyle() =
        activeProfile?.resolvedKeyboardStyle ?: settings.keyboardStyle

    private fun effectiveOneHandMode() =
        activeProfile?.resolvedOneHandMode ?: settings.oneHandMode

    private fun effectiveKeyHeightScale() =
        activeProfile?.keyHeightScale ?: settings.keyHeightScale

    private fun effectivePolicy(
        base: InputFieldPolicy,
        profile: AppProfileStore.Profile?,
    ): InputFieldPolicy = base.copy(incognito = base.incognito || profile?.incognito == true)

    private fun combinedRevision(): Int = settings.revision * 31 + appProfiles.revision

    /** Settings that can change layout, measurement, labels or haptics of the input view. */
    private fun viewStructureFingerprint(): Int = listOf(
        settings.pureBlack,
        settings.keyHeightScale,
        settings.keyboardStyle,
        settings.flickInputMode,
        settings.activeThemeId,
        settings.activeLayoutId,
        settings.oneHandMode,
        settings.oneHandWidth,
        settings.candidateRows,
        settings.candidateTextScale,
        me.zssu.ime.settings.MeetsBeamSettings.enabled(this),
        settings.flickGuideStyle,
        settings.hapticFeedbackEnabled,
    ).hashCode()

    companion object {
        private const val TAG = "ZinnaImeService"

        /** '*' in mozc's flick/12-key tables. See data/preedit/flick-hiragana.tsv upstream. */
        private const val CYCLE_MODIFIER_KEY = "*"

        /** U+3000 IDEOGRAPHIC SPACE, what a Japanese input mode types for the space key. */
        private const val FULL_WIDTH_SPACE = "　"
        private const val MAX_STYLE_CORRECTION_LENGTH = 500
        private const val KARUKAN_CONTEXT_CHARS = 320
        private const val KARUKAN_CONTEXT_WINDOW_CHARS = 192
        private const val KARUKAN_CANDIDATE_COUNT = 5

        /** How long to wait for the editor connection to return before applying a proofread result. */
        private const val PROOFREAD_APPLY_MAX_ATTEMPTS = 25
        private const val PROOFREAD_APPLY_RETRY_MS = 40L
        private val CONVERSION_RANGE_COLOR = Color.argb(72, 255, 152, 0)
        private val CONVERSION_FOCUSED_COLOR = Color.argb(184, 255, 122, 0)
    }
}
