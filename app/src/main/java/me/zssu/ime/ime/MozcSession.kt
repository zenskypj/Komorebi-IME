package me.zssu.ime.ime

import android.content.Context
import me.zssu.ime.keyboard.InputStyle
import me.zssu.ime.mozc.MozcEngine
import java.text.Normalizer
import org.mozc.android.inputmethod.japanese.protobuf.ProtoConfig.Config
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.CompositionMode
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.DecoderExperimentParams
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Input
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.KeyEvent
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Output
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Request
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.SessionCommand
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCandidateWindow.Category
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCandidateWindow.CandidateAttribute
import me.zssu.ime.settings.ImeSettings

/**
 * Task-level view of a mozc session: sends key events and session commands, and flattens the
 * [Output] proto into the three things the IME actually renders.
 *
 * The [Request] configured here is what tells mozc it is driving a mobile 12-key surface. Getting
 * it wrong is the classic reason a from-scratch mozc client produces desktop-style behaviour on a
 * phone (no mobile prediction, wrong segment sizing), so it is set once at session creation and
 * refreshed whenever the layout's [InputStyle] changes.
 */
class MozcSession(
    context: Context,
    private val priorityLookup: (reading: String, value: String) -> PriorityMatch =
        { _, _ -> PriorityMatch.NONE },
) {

    private val engine: MozcEngine? = MozcEngine.get(context)

    val isAvailable: Boolean get() = engine != null

    data class State(
        /** Text mozc has finalised; append it to the editor. */
        val committedText: String = "",
        /** Current composition, to be shown underlined. */
        val preedit: String = "",
        /** Caret position within [preedit], in Java chars. */
        val preeditCursor: Int = 0,
        val preeditReading: String = "",
        val candidates: List<Candidate> = emptyList(),
        val focusedCandidateIndex: Int = -1,
        /** Mozc's visible preedit/conversion segments, retained so the client can draw boundaries. */
        val segments: List<Segment> = emptyList(),
        /** True after conversion starts; suggestions and predictions do not count as conversion. */
        val isConverting: Boolean = false,
        /**
         * Whether mozc handled the key at all.
         *
         * False means it declined and expects the client to act — which it does for any key it has
         * no use for in the current state, notably space on an empty composition. Treating that as
         * "nothing to do" silently swallows the keystroke.
         */
        val consumed: Boolean = false,
    ) {
        val hasComposition: Boolean get() = preedit.isNotEmpty()
    }

    data class Candidate(
        val id: Int,
        val text: String,
        /** True when Mozc marks the candidate with its DELETABLE history attribute. */
        val deletable: Boolean = false,
        /** Used to keep supplementary user dictionaries from overwhelming live suggestions. */
        internal val fromUserDictionary: Boolean = false,
        /** Reading currently present in the composition, used when explicitly pinning this value. */
        val inputReading: String = "",
        /** Mozc's reading for this candidate; differs from [inputReading] for prediction/correction. */
        internal val sourceReading: String = "",
        /** True only for candidates produced by Mozc's typo/spelling correction paths. */
        internal val correction: Boolean = false,
        /** True for a prediction whose reading differs from the current composition. */
        internal val prediction: Boolean = false,
        /** Explicit user priority; exact pins outrank fuzzy recovery matches. */
        val priorityMatch: PriorityMatch = PriorityMatch.NONE,
        /** Client-generated kana spelling that commits directly instead of addressing a Mozc id. */
        internal val directCommit: Boolean = false,
    )
    data class Segment(val text: String, val highlighted: Boolean)

    private var requestedStyle: InputStyle? = null
    var clientContext: org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Context? = null
    private var currentStyle: InputStyle? = null
    private var configApplied = false
    private var configuredFullWidthSpace: Boolean? = null
    private var incognito = false
    private var phoneToggleEnabled = false
    private var deletableCandidateIds: Set<Int> = emptySet()
    /** Last literal composition reading, retained while conversion preedit displays kanji. */
    private var lastInputReading = ""

    /** Current preedit text from the last state. Empty when not composing. */
    private var currentPreeditText: String = ""

    fun currentPreedit(): String = currentPreeditText

    fun applyInputStyle(style: InputStyle) {
        requestedStyle = style
        val effectiveStyle = if (phoneToggleEnabled && style == InputStyle.FLICK_HIRAGANA) {
            InputStyle.TOGGLE_FLICK_HIRAGANA
        } else {
            style
        }
        if (effectiveStyle == currentStyle) return
        val engine = engine ?: return
        val request = Request.newBuilder()
            .setSpecialRomanjiTable(
                Request.SpecialRomanjiTable.forNumber(effectiveStyle.mozcTableNumber)
                    ?: Request.SpecialRomanjiTable.DEFAULT_TABLE
            )
            .setZeroQuerySuggestion(true)
            .setMixedConversion(true)
            .setUpdateInputModeFromSurroundingText(false)
            .setAutoPartialSuggestion(true)
            .setSpaceOnAlphanumeric(Request.SpaceOnAlphanumeric.COMMIT)
            // Keep an unfinished reading intact at either edge. The keyboard's arrows move within
            // the composition one character at a time; crossing an edge must not silently commit
            // the whole reading and hand the movement to the editor.
            .setCrossingEdgeBehavior(Request.CrossingEdgeBehavior.DO_NOTHING)
            // Forgives a missing or stray dakuten/small kana: "かつこう" still finds 学校. Costs
            // nothing extra — it widens an existing dictionary lookup rather than adding one.
            .setKanaModifierInsensitiveConversion(true)
            // Let corrected readings consult learned history as well as the static dictionary.
            // One query retains the strongest learned correction without multiplying the work
            // performed after every tap.
            .setDecoderExperimentParams(
                DecoderExperimentParams.newBuilder()
                    .setTypingCorrectionApplyUserHistorySize(1)
            )
            .build()
        engine.eval(
            Input.newBuilder()
                .setType(Input.CommandType.SET_REQUEST)
                .setRequest(request)
        )
        applyConfig(engine)

        // The table alone is not enough. It controls transliteration; the composition mode controls
        // whether mozc runs conversion over the result, so the latin and numeric planes have to be
        // put into HALF_ASCII or they come back with kanji candidates for "abc".
        engine.sendCommand(
            SessionCommand.newBuilder()
                .setType(SessionCommand.CommandType.SWITCH_COMPOSITION_MODE)
                .setCompositionMode(
                    CompositionMode.forNumber(effectiveStyle.mozcCompositionMode)
                        ?: CompositionMode.HIRAGANA
                )
                .build()
        )
        currentStyle = effectiveStyle
    }

    /**
     * Sends one key.
     *
     * [text] is a *romanji-table key*, not the character the user sees. mozc's FLICK_TO_HIRAGANA
     * table is keyed on ASCII — `1` produces あ, `_` produces い, `*` cycles dakuten/small forms —
     * and the table is what implements dakuten cycling and small-kana rules. Sending the kana
     * directly would bypass all of it, so a single ASCII char goes out as `key_code` and lets mozc
     * do the composing.
     *
     * Anything outside ASCII is text we resolved ourselves and mozc has no table entry for (emoji,
     * a symbol run), so it goes out as `key_string` for direct insertion.
     */
    fun sendText(text: String): State? {
        // In flick-only mode, finish the *previous* ordinary key immediately before starting the
        // next one. Finishing after every key breaks the table's suffix modifiers: `か` must still
        // be open when `*`, `[`, `]`, or backtick arrives so it can become が/ぱ/small kana.
        // Doing it here still makes repeated centre taps produce separate characters.
        if (!phoneToggleEnabled && !FlickTableInput.isModifierKey(currentStyle, text)) {
            stopKeyToggling()
        }

        val key = KeyEvent.newBuilder()
        val singleChar = text.length == 1 && text[0].code in 0x20..0x7E
        if (singleChar) {
            key.keyCode = text[0].code
        } else {
            key.keyString = text
        }
        return engine?.sendKey(key.build(), clientContext)?.toState()
    }

    fun sendSpecialKey(
        specialKey: KeyEvent.SpecialKey,
        shift: Boolean = false,
    ): State? {
        val key = KeyEvent.newBuilder().setSpecialKey(specialKey)
        if (shift) key.addModifierKeys(KeyEvent.ModifierKey.SHIFT)
        return engine?.sendKey(key.build(), clientContext)?.toState()
    }

    /**
     * Opens the conversion candidate list with one user action.
     *
     * A freshly entered 12-key character is intentionally left open for ゛小゜ modification.
     * Sending Space directly can therefore spend the first press only closing that table chunk.
     * Close it explicitly, then send Space so the same press reaches Mozc's Convert command.
     */
    fun convertOrSpace(): State? {
        stopKeyToggling()
        return sendSpecialKey(KeyEvent.SpecialKey.SPACE)
    }

    /**
     * Turns on the two correction features. Both are AND-ed with the Request flags above and both
     * default to off.
     *
     * `use_typing_correction` is what reaches our TypingCorrectionModel: upstream leaves the
     * predictor's typing-correction path in place but ships a stub supplemental model, so on a
     * stock OSS build this flag changes nothing. See
     * patches/0001-rule-based-typing-correction.patch.
     */
    private fun applyConfig(engine: MozcEngine) {
        val fullWidthSpace = requestedStyle?.fullWidthSpace ?: false
        if (configApplied && configuredFullWidthSpace == fullWidthSpace) return
        val output = engine.eval(Input.newBuilder().setType(Input.CommandType.GET_CONFIG))
        val builder =
            if (output != null && output.hasConfig()) output.config.toBuilder()
            else Config.newBuilder()
        engine.eval(
            Input.newBuilder()
                .setType(Input.CommandType.SET_CONFIG)
                .setConfig(
                    builder
                        .setSpaceCharacterForm(
                            if (fullWidthSpace) Config.FundamentalCharacterForm.FUNDAMENTAL_FULL_WIDTH
                            else Config.FundamentalCharacterForm.FUNDAMENTAL_HALF_WIDTH
                        )
                        .setUseTypingCorrection(true)
                        .setUseKanaModifierInsensitiveConversion(true)
                        .setUseHistorySuggest(true)
                        .setSuggestionsSize(9)
                        .setComposingTimeoutThresholdMsec(
                            if (phoneToggleEnabled) ImeSettings.TOGGLE_TIMEOUT_MILLIS else 0
                        )
                        .setIncognitoMode(incognito)
                )
        )
        configApplied = true
        configuredFullWidthSpace = fullWidthSpace
    }

    /**
     * Prevents both learning from and consulting mutable history in sensitive editors.
     *
     * This is Mozc's own incognito switch, not merely a hidden candidate view: conversion requests
     * therefore cannot accidentally update history while a password field is active.
     */
    fun setIncognitoMode(enabled: Boolean) {
        if (incognito == enabled && configApplied) return
        incognito = enabled
        configApplied = false
        engine?.let(::applyConfig)
    }

    fun setPhoneToggleEnabled(enabled: Boolean) {
        if (phoneToggleEnabled == enabled && configApplied) return
        phoneToggleEnabled = enabled
        configApplied = false
        currentStyle = null
        engine?.let(::applyConfig)
        requestedStyle?.let(::applyInputStyle)
    }

    fun submit(): State? = sendSessionCommand(SessionCommand.CommandType.SUBMIT)

    fun revert(): State? = sendSessionCommand(SessionCommand.CommandType.REVERT)

    fun resetContext(): State? = sendSessionCommand(SessionCommand.CommandType.RESET_CONTEXT)

    /** Pulls the last commit back into a composition so it can be re-converted. */
    fun undo(): State? = sendSessionCommand(SessionCommand.CommandType.UNDO)

    /** Backspace on an empty composition is the editor's problem, not mozc's. */
    fun undoOrRewind(): State? = sendSessionCommand(SessionCommand.CommandType.UNDO_OR_REWIND)

    fun selectCandidate(candidate: Candidate): State? {
        if (!candidate.directCommit) {
            return engine?.sendCommand(
                SessionCommand.newBuilder()
                    .setType(SessionCommand.CommandType.SUBMIT_CANDIDATE)
                    .setId(candidate.id)
                    .build()
            )?.toState()
        }

        // Script fallbacks may not exist in Mozc's bounded candidate result. Clear its composition
        // before returning the selected spelling as a normal commit, otherwise the old reading
        // would remain alive behind the editor text and reappear on the next key.
        resetContext()
        lastInputReading = ""
        return State(committedText = candidate.text, consumed = true)
    }

    fun highlightCandidate(candidateId: Int): State? = engine?.sendCommand(
        SessionCommand.newBuilder()
            .setType(SessionCommand.CommandType.HIGHLIGHT_CANDIDATE)
            .setId(candidateId)
            .build()
    )?.toState()

    fun deleteCandidateFromHistory(candidateId: Int): State? = engine?.sendCommand(
        SessionCommand.newBuilder()
            .setType(SessionCommand.CommandType.DELETE_CANDIDATE_FROM_HISTORY)
            .setId(candidateId)
            .build()
    )?.toState()

    /**
     * Ends any toggle-in-progress. Flick input still needs this: a same-key tap sequence is what
     * mozc uses to cycle characters, and moving the caret must not be absorbed by that cycle.
     */
    fun stopKeyToggling(): State? = sendSessionCommand(SessionCommand.CommandType.STOP_KEY_TOGGLING)

    fun close() {
        engine?.deleteSession()
        requestedStyle = null
        currentStyle = null
        lastInputReading = ""
    }

    private fun sendSessionCommand(type: SessionCommand.CommandType): State? =
        engine?.sendCommand(SessionCommand.newBuilder().setType(type).build())?.toState()

    private fun Output.toState(): State {
        val preeditText = StringBuilder()
        var cursor = 0
        if (hasPreedit()) {
            preedit.segmentList.forEach { preeditText.append(it.value) }
            cursor = preedit.cursor
        }

        val displayedPreedit = preeditText.toString()
        currentPreeditText = displayedPreedit
        val convertingOutput = hasCandidateWindow() &&
            candidateWindow.category == Category.CONVERSION &&
            candidateWindow.hasFocusedIndex()
        if (displayedPreedit.isNotEmpty() && !convertingOutput) {
            lastInputReading = displayedPreedit
        }
        val reading =
            if (convertingOutput && lastInputReading.isNotEmpty()) lastInputReading
            else displayedPreedit
        val candidateMetadata = if (hasAllCandidateWords()) {
            allCandidateWords.candidatesList.take(CandidateRanking.MAX_LIVE_POOL_SIZE)
        } else {
            emptyList()
        }
        val metadataById = candidateMetadata.associateBy { it.id }
        if (hasAllCandidateWords()) {
            // The toolbar can display only a handful of candidates. Scanning and allocating
            // metadata for Mozc's entire result on every keystroke adds latency without changing
            // anything the user can act on.
            deletableCandidateIds = candidateMetadata
                .filter { CandidateAttribute.DELETABLE in it.attributesList }
                .map { it.id }
                .toSet()
        } else if (!hasCandidateWindow()) {
            deletableCandidateIds = emptySet()
        }
        fun candidate(
            id: Int,
            value: String,
            fallbackDeletable: Boolean = false,
        ): Candidate {
            val metadata = metadataById[id]
            val attributes = metadata?.attributesList.orEmpty()
            val corrected =
                CandidateAttribute.TYPING_CORRECTION in attributes ||
                    CandidateAttribute.SPELLING_CORRECTION in attributes
            val differentReading = metadata?.hasKey() == true && metadata.key != reading
            return Candidate(
                id = id,
                text = value,
                deletable = fallbackDeletable ||
                    CandidateAttribute.DELETABLE in attributes ||
                    id in deletableCandidateIds,
                fromUserDictionary = CandidateAttribute.USER_DICTIONARY in attributes,
                inputReading = reading,
                sourceReading =
                    if (metadata?.hasKey() == true) metadata.key else reading,
                correction = corrected,
                prediction = differentReading && !corrected,
                priorityMatch = priorityLookup(reading, value),
            )
        }
        val candidates = if (hasCandidateWindow()) {
            val visible = candidateWindow.candidateList.map {
                candidate(it.id, it.value)
            }

            // User dictionaries intentionally receive a strong native ranking boost. That is right
            // for someone's own entries, but it made the bundled 24k-entry kana→English dictionary
            // occupy nearly the whole toolbar. During an unfocused Japanese suggestion only, draw
            // additional candidates from Mozc's complete result and apply a small stable penalty to
            // Latin user-dictionary values. Explicit conversion, focused prediction, and the ASCII
            // keyboard retain Mozc's original order.
            val isLiveJapaneseSuggestion =
                candidateWindow.category == Category.SUGGESTION &&
                    !candidateWindow.hasFocusedIndex() &&
                    (currentStyle == InputStyle.FLICK_HIRAGANA ||
                        currentStyle == InputStyle.TOGGLE_FLICK_HIRAGANA)
            if (isLiveJapaneseSuggestion) {
                val complete = if (hasAllCandidateWords()) {
                    allCandidateWords.candidatesList.asSequence()
                        .take(CandidateRanking.MAX_LIVE_POOL_SIZE)
                        .filter { it.value.isNotEmpty() }
                        .map {
                            candidate(
                                id = it.id,
                                value = it.value,
                                fallbackDeletable =
                                    CandidateAttribute.DELETABLE in it.attributesList,
                            )
                        }
                        .toList()
                } else {
                    emptyList()
                }
                CandidateRanking.rankLiveJapaneseSuggestions(
                    candidates = (visible + complete).distinctBy { it.id },
                    limit = visible.size,
                )
            } else {
                visible
            }
        } else {
            emptyList()
        }
        val segments = if (hasPreedit()) {
            preedit.segmentList.map {
                Segment(
                    text = it.value,
                    highlighted = it.annotation ==
                        org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Preedit
                            .Segment.Annotation.HIGHLIGHT,
                )
            }
        } else {
            emptyList()
        }

        val focusedId = if (hasCandidateWindow() && candidateWindow.hasFocusedIndex()) {
            candidates.getOrNull(candidateWindow.focusedIndex)?.id
        } else {
            null
        }
        val reorderedFocused =
            focusedId?.let { id -> candidates.indexOfFirst { it.id == id } } ?: -1

        return State(
            committedText = if (hasResult()) result.value else "",
            preedit = displayedPreedit,
            preeditReading = reading,
            preeditCursor = cursor.coerceIn(0, preeditText.length),
            candidates = candidates,
            focusedCandidateIndex = reorderedFocused,
            segments = segments,
            isConverting = hasCandidateWindow() &&
                candidateWindow.category == Category.CONVERSION &&
                candidateWindow.hasFocusedIndex(),
            consumed = consumed,
        )
    }
}

/** Mozc table keys that transform the preceding chunk rather than starting a new character. */
internal object FlickTableInput {
    fun isModifierKey(style: InputStyle?, text: String): Boolean = when (style) {
        InputStyle.FLICK_HIRAGANA,
        InputStyle.TOGGLE_FLICK_HIRAGANA,
        -> text == "*" || text == "[" || text == "]" || text == "`"

        InputStyle.FLICK_HALFWIDTH_ASCII,
        InputStyle.TOGGLE_FLICK_HALFWIDTH_ASCII,
        -> text == "*"

        else -> false
    }
}

/**
 * Provenance-aware client-side adjustment for the unfocused Japanese toolbar.
 *
 * Explicit pins come first, followed by useful full-reading conversions, the verbatim reading,
 * typo corrections, and predictions of text the user has not typed yet. Native order is stable
 * within each lane. This prevents a more aggressive corrector from hiding intentional input while
 * keeping genuine recovery candidates ahead of speculative completions.
 */
internal object CandidateRanking {
    const val MAX_LIVE_POOL_SIZE = 32
    private const val LATIN_USER_DICTIONARY_PENALTY = 6
    private const val LANE_STRIDE = 1_000

    fun rankLiveJapaneseSuggestions(
        candidates: List<MozcSession.Candidate>,
        limit: Int,
    ): List<MozcSession.Candidate> {
        if (limit <= 0 || candidates.isEmpty()) return emptyList()

        val ranked = candidates.withIndex()
            .sortedBy { indexed ->
                lane(indexed.value) * LANE_STRIDE +
                    indexed.index + if (isLatinUserDictionaryEntry(indexed.value)) {
                    LATIN_USER_DICTIONARY_PENALTY
                } else {
                    0
                }
            }
            .map { it.value }

        return KanaScriptVariants.ensurePresentAtEnd(ranked, limit)
    }

    private fun lane(candidate: MozcSession.Candidate): Int = when {
        candidate.priorityMatch == PriorityMatch.EXACT -> 0
        candidate.priorityMatch == PriorityMatch.SIMILAR -> 1
        hasKatakanaGrammarInPrediction(candidate) -> 6
        isFullReadingConversion(candidate) -> 2
        !candidate.correction && !candidate.prediction -> 3
        candidate.correction -> 4
        else -> 5
    }

    /**
     * Promotes "了解です" above the verbatim "りょうかいです" without promoting a longer
     * completion such as "了解ですので". Mozc sometimes marks a same-length alternate reading as
     * prediction, so source/input length is the reliable distinction for this toolbar.
     */
    private fun isFullReadingConversion(candidate: MozcSession.Candidate): Boolean {
        if (candidate.correction || isLatinUserDictionaryEntry(candidate)) return false
        val input = ReadingSimilarity.normalize(candidate.inputReading)
        if (input.isEmpty()) return false
        if (ReadingSimilarity.normalize(candidate.text) == input) return false
        if (!candidate.prediction) return true

        val source = ReadingSimilarity.normalize(candidate.sourceReading)
        return (source.isNotEmpty() && source.length == input.length) ||
            preservesTypedHiraganaSuffix(candidate.text, input)
    }

    /**
     * Mozc can represent "了解です" as a prediction from the shorter key "りょうかい", even though
     * the visible candidate covers the already typed "りょうかいです". Keeping a two-kana suffix
     * proves this is a full phrase conversion rather than a continuation such as "了解ですので".
     */
    private fun preservesTypedHiraganaSuffix(text: String, input: String): Boolean {
        if (!text.any(::isKanji)) return false
        var textIndex = text.lastIndex
        var inputIndex = input.lastIndex
        var matched = 0
        while (textIndex >= 0 && inputIndex >= 0) {
            val textChar = text[textIndex]
            val inputChar = input[inputIndex]
            if (textChar != inputChar || textChar !in '\u3040'..'\u309f') break
            matched++
            textIndex--
            inputIndex--
        }
        return matched >= 2
    }

    /**
     * Mixed-script predictions such as "了解デス" are almost always accidental grammatical
     * suffixes, while legitimate words such as "六本木ヒルズ" contain a longer Katakana run.
     * Keep these candidates available at the end instead of letting them displace natural text.
     */
    private fun hasKatakanaGrammarInPrediction(candidate: MozcSession.Candidate): Boolean {
        if (!candidate.prediction || !candidate.text.any(::isKanjiOrHiragana)) return false
        return KATAKANA_RUN.findAll(candidate.text).any { it.value in KATAKANA_GRAMMAR }
    }

    private fun isLatinUserDictionaryEntry(candidate: MozcSession.Candidate): Boolean =
        candidate.fromUserDictionary &&
            candidate.text.any { it in 'A'..'Z' || it in 'a'..'z' } &&
            !containsJapaneseScript(candidate.text)

    private fun isKanjiOrHiragana(ch: Char): Boolean =
        ch in '\u3040'..'\u309f' ||
            isKanji(ch)

    private fun isKanji(ch: Char): Boolean =
        ch in '\u3400'..'\u4dbf' || ch in '\u4e00'..'\u9fff'

    private fun containsJapaneseScript(text: String): Boolean = text.any { ch ->
        ch in '\u3040'..'\u30ff' || // Hiragana, Katakana
            ch in '\u3400'..'\u4dbf' || // CJK Extension A
            ch in '\u4e00'..'\u9fff' || // CJK Unified Ideographs
            ch == '\u3005' || ch == '\u3006' || ch == '\u303b'
    }

    private val KATAKANA_RUN = Regex("[\\u30a0-\\u30ffー]+")
    private val KATAKANA_GRAMMAR = setOf(
        "ハ", "ガ", "ヲ", "ニ", "ヘ", "ト", "デ", "ノ", "モ", "ヤ", "カ", "ネ", "ヨ",
        "デス", "デシタ", "マス", "マシタ", "ダ", "ナイ", "タイ",
    )
}

/**
 * Guarantees direct hiragana, full-width Katakana, and half-width Katakana spellings in a live
 * Japanese candidate list. A spelling that naturally won ranking remains the answer at the front;
 * the remaining spellings are fallbacks and therefore sit at the end without displacing useful
 * conversion candidates any earlier than necessary.
 */
internal object KanaScriptVariants {
    private const val HIRAGANA_ID = -1
    private const val KATAKANA_ID = -2
    private const val HALF_KATAKANA_ID = -3

    fun ensurePresentAtEnd(
        ranked: List<MozcSession.Candidate>,
        limit: Int,
    ): List<MozcSession.Candidate> {
        if (ranked.isEmpty() || limit <= 0) return emptyList()
        val reading = ranked.firstNotNullOfOrNull { it.inputReading.takeIf(String::isNotEmpty) }
            ?: return ranked.take(limit)
        val variants = variants(reading)
        if (variants.isEmpty()) return ranked.take(limit)

        val naturalAnswer = ranked.firstOrNull()?.text
        val required = variants.map { (id, text) ->
            ranked.firstOrNull { it.text == text } ?: MozcSession.Candidate(
                id = id,
                text = text,
                inputReading = reading,
                sourceReading = reading,
                directCommit = true,
            )
        }
        val frontVariants = required.filter {
            it.text == naturalAnswer || it.priorityMatch != PriorityMatch.NONE
        }
        val fallbackVariants = required.filterNot { it in frontVariants }
        val requiredTexts = required.mapTo(mutableSetOf()) { it.text }
        val ordinary = ranked.filterNot { it.text in requiredTexts }
        val mustKeepNaturalAnswer = naturalAnswer != null && naturalAnswer !in requiredTexts
        val minimumSize = required.size + if (mustKeepNaturalAnswer) 1 else 0
        val resultLimit = maxOf(limit, minimumSize)
        val front = (frontVariants + ordinary)
            .distinctBy { it.text }
            .take((resultLimit - fallbackVariants.size).coerceAtLeast(0))
        return (front + fallbackVariants)
            .distinctBy { it.text }
            .take(resultLimit)
    }

    private fun variants(reading: String): List<Pair<Int, String>> {
        if (reading.none(::isKana)) return emptyList()
        val hiragana = reading.map { ch ->
            when (ch) {
                in '\u30a1'..'\u30f6' -> (ch.code - 0x60).toChar()
                '\u30fd' -> '\u309d'
                '\u30fe' -> '\u309e'
                else -> ch
            }
        }.joinToString("")
        val katakana = hiragana.map { ch ->
            when (ch) {
                in '\u3041'..'\u3096' -> (ch.code + 0x60).toChar()
                '\u309d' -> '\u30fd'
                '\u309e' -> '\u30fe'
                else -> ch
            }
        }.joinToString("")
        val halfKatakana = toHalfWidthKatakana(katakana)
        return listOf(
            HIRAGANA_ID to hiragana,
            KATAKANA_ID to katakana,
            HALF_KATAKANA_ID to halfKatakana,
        ).distinctBy { it.second }
    }

    private fun toHalfWidthKatakana(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        return buildString(decomposed.length) {
            decomposed.forEach { ch ->
                append(FULL_TO_HALF_KATAKANA[ch] ?: ch)
            }
        }
    }

    private fun isKana(ch: Char): Boolean =
        ch in '\u3041'..'\u3096' || ch in '\u30a1'..'\u30fa'

    private val FULL_TO_HALF_KATAKANA: Map<Char, Char> = buildMap {
        val full = "。「」、・ヲァィゥェォャュョッーアイウエオカキクケコサシスセソ" +
            "タチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワン"
        val half = "｡｢｣､･ｦｧｨｩｪｫｬｭｮｯｰｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿ" +
            "ﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ"
        full.zip(half).forEach { (source, target) -> put(source, target) }
        put('ヵ', 'ｶ')
        put('ヶ', 'ｹ')
        put('\u3099', 'ﾞ')
        put('\u309a', 'ﾟ')
    }
}
