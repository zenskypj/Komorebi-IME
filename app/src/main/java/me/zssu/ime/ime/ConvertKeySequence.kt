package me.zssu.ime.ime

import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.KeyEvent

/**
 * Which special keys to send, in order, when the user asks to convert.
 *
 * A freshly entered 12-key character is intentionally left open so ゛/小/゜ can modify it. A plain
 * Space can therefore be spent closing that chunk and only a *second* press reaches Mozc's Convert
 * command, which the user experiences as "nothing happened the first time". HENKAN maps straight
 * onto Convert, so sending it first opens the candidate list on the very first press. Space stays
 * as the fallback: it is the traditional convert key and also the only sensible choice when nothing
 * is composing.
 */
internal object ConvertKeySequence {
    fun order(composing: Boolean): List<KeyEvent.SpecialKey> =
        if (composing) {
            listOf(KeyEvent.SpecialKey.HENKAN, KeyEvent.SpecialKey.SPACE)
        } else {
            listOf(KeyEvent.SpecialKey.SPACE)
        }

    /** The first press opens on candidate zero; later presses advance exactly once. */
    fun convert(
        composing: Boolean,
        wasConverting: Boolean,
        stop: () -> MozcSession.State?,
        send: (KeyEvent.SpecialKey) -> MozcSession.State?,
        highlight: (Int) -> MozcSession.State?,
    ): MozcSession.State? {
        fun first(state: MozcSession.State): MozcSession.State {
            if (wasConverting || !state.isConverting || state.focusedCandidateIndex == 0) return state
            val candidate = state.candidates.firstOrNull()?.takeUnless { it.directCommit } ?: return state
            return highlight(candidate.id) ?: state
        }
        val stopped = stop()
        if (!stopped?.committedText.isNullOrEmpty()) return stopped
        if (wasConverting) {
            if (stopped?.isConverting != true) return stopped
            val next = CandidatePaging.next(stopped.candidates.size, stopped.focusedCandidateIndex) ?: return stopped
            return highlight(stopped.candidates[next].id) ?: stopped
        }
        if (!wasConverting && reachedConversion(stopped)) return first(stopped!!)
        var last: MozcSession.State? = stopped
        val keys = order(composing)
        for (key in keys) {
            last = send(key)
            if (reachedConversion(last)) return first(last!!)
            if (!canRetry(last)) break
        }
        return last
    }

    /** True once Mozc has actually opened a conversion, which is what we were trying to reach. */
    fun reachedConversion(state: MozcSession.State?): Boolean = state?.isConverting == true

    /**
     * Whether sending the next key is worth trying.
     *
     * A key that consumed the composition (or that Mozc declined outright) has left nothing to
     * convert, so pressing Space afterwards would only import a stray space into the editor.
     */
    fun canRetry(state: MozcSession.State?): Boolean = state == null || state.hasComposition
}
