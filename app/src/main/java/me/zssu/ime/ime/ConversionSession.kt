package me.zssu.ime.ime

import android.content.Context
import me.zssu.ime.keyboard.InputStyle
import me.zssu.ime.settings.ImeSettings
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Context as ClientContext
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.KeyEvent

/** UI-owned settings/snapshots; every engine access, including initialization, stays on the worker. */
internal class ConversionSession(
    private val context: Context,
    private val priorityLookup: (String, String) -> PriorityMatch,
    private val queue: SerialInputQueue,
) {
    private data class Config(val enabled: Boolean, val alongside: Boolean, val style: InputStyle,
        val incognito: Boolean = false, val toggle: Boolean = false, val correction: Boolean = true)
    private val settings = ImeSettings(context)
    private var desired = Config(settings.zenzaiPlusEnabled, settings.zenzaiMozcEnabled, InputStyle.FLICK_HIRAGANA)
    private var applied: Config? = null // worker only
    private var backend: NativeConversionSession? = null // worker only
    private var resetRequested = false
    private var revertRequested = false
    private var preedit = ""
    val zenzaiEnabled get() = desired.enabled
    val inputStyle get() = desired.style
    var clientContext: ClientContext? = null
    var visibleConversionCandidateIds: List<Int> = emptyList()

    private suspend fun <T> call(operation: NativeConversionSession.() -> T): T {
        val config = desired
        val surrounding = clientContext
        val reset = resetRequested
        val revert = revertRequested
        resetRequested = false
        revertRequested = false
        return queue.native {
            val session = backend ?: NativeConversionSession(context, priorityLookup).also { backend = it }
            if (applied != config) {
                session.setZenzaiEnabled(config.enabled, config.alongside)
                session.setIncognitoMode(config.incognito)
                session.setPhoneToggleEnabled(config.toggle)
                session.setTypingCorrectionEnabled(config.correction)
                session.applyInputStyle(config.style)
                applied = config
            }
            if (reset) session.resetContext() else if (revert) session.revert()
            session.clientContext = surrounding
            session.operation()
        }
    }
    private suspend fun state(operation: NativeConversionSession.() -> MozcSession.State?): MozcSession.State? =
        call(operation).also { if (it != null) preedit = it.preedit }

    suspend fun isAvailable() = call { isAvailable }
    fun currentPreedit() = preedit
    fun applyInputStyle(value: InputStyle) { desired = desired.copy(style = value) }
    fun setIncognitoMode(value: Boolean) { desired = desired.copy(incognito = value) }
    fun setPhoneToggleEnabled(value: Boolean) { desired = desired.copy(toggle = value) }
    fun setTypingCorrectionEnabled(value: Boolean) { desired = desired.copy(correction = value) }
    fun setZenzaiEnabled(enabled: Boolean, alongside: Boolean) {
        if (desired.enabled != enabled || desired.alongside != alongside) clientContext = null
        desired = desired.copy(enabled = enabled, alongside = alongside)
    }
    suspend fun setInputActive(value: Boolean) = call { me.zssu.ime.mozc.MozcEngine.get(context)?.setInputActive(value) }
    suspend fun sendText(text: String, label: String = text) = state { sendText(text, label) }
    suspend fun sendSpecialKey(key: KeyEvent.SpecialKey, shift: Boolean = false) = state { sendSpecialKey(key, shift) }
    suspend fun submit() = state { submit() }
    suspend fun revert() = state { revert() }
    suspend fun resetContext() = state { resetContext() }
    suspend fun undo() = state { undo() }
    suspend fun selectCandidate(candidate: MozcSession.Candidate) = state { selectCandidate(candidate) }
    suspend fun highlightCandidate(id: Int) = state { highlightCandidate(id) }
    suspend fun deleteCandidateFromHistory(id: Int) = state { deleteCandidateFromHistory(id) }
    suspend fun stopKeyToggling() = state { stopKeyToggling() }
    suspend fun moveCursor(delta: Int, resize: Boolean) = state { moveCursor(delta, resize) }
    suspend fun convertOrSpace(): MozcSession.State? {
        val ids = visibleConversionCandidateIds.toList()
        return state { visibleConversionCandidateIds = ids; convertOrSpace() }
    }
    fun requestReset() { resetRequested = true; revertRequested = false; preedit = "" }
    fun requestRevert() { revertRequested = true }
    fun learnDictionaryCompletion(candidate: MozcSession.Candidate) = queue.background { backend?.learnDictionaryCompletion(candidate) }
    fun close() = queue.close { backend?.close(); backend = null }
}
