package me.zssu.ime.ime

import android.content.Context
import android.content.ClipboardManager
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.UnderlineSpan
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
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
        } else if ((key == me.zssu.ime.mozc.UserDictionary.REVISION_KEY || key == "zenzai_train_dictionary") && session.zenzaiEnabled) {
            zenzai.syncDictionary { zenzaiDictionary = it }
        }
    }
    private lateinit var karukanEngine: KarukanEngine

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
    private var karukanReading = ""

    /**
     * Whether mozc currently holds a composition. Mirrors the last rendered state so Enter can
     * decide between 確定 and the editor's action *before* it submits and destroys the evidence.
     */
    private var isComposing = false
    private var isConverting = false
    private var currentSegmentCount = 0
    private var suppressPartialLearning = false
    private var renderedPreeditCursor = 0

    /**
     * Set while a state we rendered is still settling in the editor.
     *
     * commitText + setComposingText inside one batch edit make the editor post a selection
     * callback whose composing-span coordinates can lag the text we just wrote. Comparing that
     * stale span against the new caret made the callback look like an external edit, so the
     * continuation candidates a conversion just produced were cleared and the toolbar flashed
     * back over them. A collapsed-caret update that arrives while this is set is our own echo and
     * is ignored; a range selection is still the user grabbing the text, so it is honoured.
     */
    private var ownSelectionUpdatePending = false
    private var editorStateUpdateInProgress = false
    private var editorSelectionUpdateObserved = false

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
                    candidateView?.setCandidates(lastCandidates, lastFocusedCandidateIndex)
                }
            }
        }

        val candidates = CandidateStripView(this).apply {
            theme = this@ZinnaImeService.theme
            listener = CandidateStripView.OnCandidateSelectedListener { candidate ->
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
                setCandidates(lastCandidates, lastFocusedCandidateIndex)
            }
            onToolAction = ::handleToolAction
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
            (theme.keyHeightDp * 0.8f * settings.candidateRows * resources.displayMetrics.density).toInt()
        } else {
            0
        }

        val keyboard = FlickKeyboardView(this).apply {
            theme = this@ZinnaImeService.theme
            layout = loaded?.let(::adaptLayoutForStyle)
            guideOverflowTop = stripHeight.toFloat()
            guideStyle = settings.flickGuideStyle
            listener = FlickKeyboardView.OnKeyOutputListener(::onKeyOutput)
        }

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
            if (me.zssu.ime.settings.MeetsBeamSettings.enabled(this@ZinnaImeService) && stripHeight > 0) {
                addView(android.widget.ImageButton(this@ZinnaImeService).apply {
                    setImageResource(R.drawable.ic_meets_beam)
                    setColorFilter(theme.candidateTextColor)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    contentDescription = "Meets Beam"
                    setOnClickListener { handleToolAction(CandidateStripView.ToolAction.MEETS_BEAM) }
                }, LinearLayout.LayoutParams((44 * resources.displayMetrics.density).toInt(), stripHeight))
            }
            addView(candidates, LinearLayout.LayoutParams(0, stripHeight, 1f))
        }
        content.addView(candidateRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
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
        loaded?.let { session.applyInputStyle(it.inputStyle) }
        candidates.showTools()
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
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("beam_package", pkg)
                if (url != null) intent.putExtra("beam_url", url)
                startActivity(intent)
            }
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
        ownSelectionUpdatePending = false
        when (InputRestartRouting.target(restarting, isComposing)) {
            InputRestartRouting.Target.PRESERVE_COMPOSITION -> {
                // Android is restarting the same editor with its composing span intact. Keep both
                // sides of that composition alive, including candidates lost by a view rebuild.
                keyboardView?.isConversionAvailable =
                    layout?.inputStyle?.fullWidthSpace == true
                candidateView?.setCandidates(lastCandidates, lastFocusedCandidateIndex)
            }
            InputRestartRouting.Target.RESET_SESSION -> {
                // In particular, a restart while our editor-facing state is idle must not retain
                // an invisible Mozc conversion. Its result could otherwise surface on the next key.
                cancelKarukanConversion(clearReading = true, revertMozc = false)
                session.resetContext()
                isComposing = false
                isConverting = false
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
        renderedPreeditCursor = 0
        keyboardView?.isConversionAvailable = false
        ownSelectionUpdatePending = false
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
     * Updates produced by our own [render] leave the selection collapsed at Mozc's cursor inside
     * the composing span, so they are ignored. Any other selection means the editor is now
     * authoritative and retaining the native composition would make the two sides disagree.
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
            editorSelectionUpdateObserved = true
            return
        }
        if (ownSelectionUpdatePending) {
            ownSelectionUpdatePending = false
            // Our render leaves the caret collapsed; a range selection means the user intervened.
            if (newSelStart == newSelEnd) return
        }
        if (!SelectionUpdate.invalidatesComposition(
                isComposing = isComposing,
                newSelectionStart = newSelStart,
                newSelectionEnd = newSelEnd,
                composingStart = candidatesStart,
                composingEnd = candidatesEnd,
                preeditCursor = renderedPreeditCursor,
            )
        ) {
            return
        }

        // Clear the guard first: finishComposingText can synchronously cause another selection
        // callback in some editors.
        isComposing = false
        isConverting = false
        renderedPreeditCursor = 0
        keyboardView?.isConversionAvailable = false
        cancelKarukanConversion(clearReading = true, revertMozc = false)
        session.resetContext()
        currentInputConnection?.finishComposingText()
        candidateView?.clear()
    }

    override fun onDestroy() {
        enginePreferences.unregisterOnSharedPreferenceChangeListener(enginePreferenceListener)
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
                adjustSegment = isConverting &&
                    direction != FlickDirection.CENTER &&
                    (key.center.action is KeyAction.Space ||
                        key.center.action is KeyAction.Convert),
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
        if (state == null || !state.consumed) {
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

    private fun handleCursorMove(delta: Int, adjustSegment: Boolean) {
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
        val special = if (delta < 0) KeyEvent.SpecialKey.LEFT else KeyEvent.SpecialKey.RIGHT
        // In conversion, ordinary arrows move the focused clause. A sideways space flick supplies
        // Shift+Arrow, Mozc's standard command for shrinking/expanding that clause boundary.
        val state = session.sendSpecialKey(special, shift = adjustSegment)
        if (state != null && state.hasComposition) {
            render(state)
            return
        }
        // Outside a composition, move the editor caret instead.
        val ic = currentInputConnection ?: return
        val extracted = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0) ?: return
        val target = (extracted.selectionStart + delta).coerceIn(0, extracted.text?.length ?: 0)
        ic.setSelection(target, target)
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

    private fun render(state: MozcSession.State?) {
        val ic = currentInputConnection ?: return
        if (state == null) {
            recoverFromLostSession(ic)
            return
        }

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
        isConverting = state.isConverting
        currentSegmentCount = if (state.isConverting) state.segments.size else 0
        renderedPreeditCursor = state.preeditCursor
        keyboardView?.isConversionAvailable =
            state.hasComposition && layout?.inputStyle?.fullWidthSpace == true
        editorStateUpdateInProgress = true
        editorSelectionUpdateObserved = false
        ic.beginBatchEdit()
        try {
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

                // Android can place newly supplied composing text only at/beyond its edges. Put it
                // down with the caret at the end, then explicitly restore Mozc's character offset.
                // Without this second step every interior offset collapses to the beginning or end,
                // leaving the editor and Mozc at different positions.
                ic.setComposingText(styled, 1)
                if (state.preeditCursor != state.preedit.length) {
                    val extracted = ic.getExtractedText(
                        android.view.inputmethod.ExtractedTextRequest(),
                        0,
                    )
                    if (extracted != null) {
                        val composingEnd = extracted.startOffset + extracted.selectionEnd
                        val target = SelectionUpdate.absolutePreeditCursor(
                            composingEnd = composingEnd,
                            preeditLength = state.preedit.length,
                            preeditCursor = state.preeditCursor,
                        )
                        ic.setSelection(target, target)
                    }
                }
            }
        } finally {
            ic.endBatchEdit()
            editorStateUpdateInProgress = false
            // If endBatchEdit already delivered the selection callback synchronously, there is no
            // future self-update to ignore. Otherwise one collapsed asynchronous callback is
            // expected from this composition render.
            ownSelectionUpdatePending = state.hasComposition && !editorSelectionUpdateObserved
        }

        lastCandidates = state.candidates
        lastFocusedCandidateIndex = state.focusedCandidateIndex
        candidateView?.setCandidates(state.candidates, state.focusedCandidateIndex)
        requestZenzai(state)
    }

    private fun refreshZenzai() {
        val policy = InputFieldPolicy.from(currentInputEditorInfo)
        val enabled = settings.zenzaiPlusEnabled && ZenzaiEngine.isBundled(this) && !policy.incognito
        zenzai.cancel()
        zenzaiCandidate = null
        zenzaiState = null
        session.setZenzaiEnabled(enabled, settings.zenzaiMozcEnabled)
        zenzai.setEnabled(enabled)
        if (enabled) zenzai.syncDictionary { zenzaiDictionary = it }
    }

    private fun requestZenzai(state: MozcSession.State) {
        if (state.isConverting || !ZenzaiConversion.canConvert(session.zenzaiEnabled, state.hasComposition,
                layout?.inputStyle?.fullWidthSpace == true, fieldPolicy.incognito)) return
        val reading = state.preeditReading
        val preceding = session.clientContext?.precedingText.orEmpty()
        val prompt = ZenzaiConversion.prompt(reading, preceding) ?: return
        val registered = zenzaiDictionary.filter { ZenzaiConversion.prompt(it.reading, "") == ZenzaiConversion.prompt(reading, "") }
            .map { MozcSession.Candidate(-100, it.word, directCommit = true) }
        val base = (registered + state.candidates).distinctBy { it.text }
        candidateView?.setCandidates(base, state.focusedCandidateIndex)
        zenzai.request(prompt) { candidate ->
            if (zenzaiState !== state || !isComposing) return@request
            zenzaiCandidate = candidate
            lastCandidates = ZenzaiConversion.candidates(base, candidate)
            lastFocusedCandidateIndex = 0
            candidateView?.setCandidates(lastCandidates, 0)
        }
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
        currentSegmentCount = 0
        renderedPreeditCursor = 0
        ownSelectionUpdatePending = false
        keyboardView?.isConversionAvailable = false
        lastCandidates = emptyList()
        lastFocusedCandidateIndex = -1
        candidateView?.clear()
    }

    private fun shouldUseKarukan(): Boolean =
        !settings.zenzaiPlusEnabled &&
        layout?.inputStyle?.fullWidthSpace == true &&
        isComposing && !isConverting &&
            !fieldPolicy.incognito &&
            settings.conversionEngine == ImeSettings.ConversionEngine.KARUKAN &&
            KarukanModelStore.files(this).ready &&
            karukanEngine.isNativeAvailable

    /**
     * Runs only explicit conversion through Karukan. Mozc continues to own the flick/12-key
     * composition, so dakuten, small kana, cursor editing and every fallback remain dependable.
     */
    private fun startKarukanConversion() {
        val stopped = session.stopKeyToggling()
        if (stopped != null) render(stopped)
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
                    candidateView?.setCandidates(candidates, focused)
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
        if (state == null || !state.consumed) {
            val fullWidth = layout?.inputStyle?.fullWidthSpace ?: false
            currentInputConnection?.commitText(if (fullWidth) FULL_WIDTH_SPACE else " ", 1)
        }
        if (state != null) render(state)
    }

    private fun submitCurrentComposition() {
        zenzaiCandidate?.takeIf { isComposing }?.let { render(session.selectCandidate(it)); return }
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
        candidateView?.setCandidates(lastCandidates, lastFocusedCandidateIndex)
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
        private val CONVERSION_RANGE_COLOR = Color.argb(72, 255, 152, 0)
        private val CONVERSION_FOCUSED_COLOR = Color.argb(184, 255, 122, 0)
    }
}
