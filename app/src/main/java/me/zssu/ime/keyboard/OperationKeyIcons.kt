package me.zssu.ime.keyboard
import me.zssu.ime.R
/** Keep typed symbols and custom labels as text. */
internal object OperationKeyIcons {
    fun description(action: KeyAction, label: String): String = when (action) {
        is KeyAction.Backspace -> "削除"
        is KeyAction.Enter -> "Enter"
        is KeyAction.Undo -> "元に戻す"
        is KeyAction.MoveCursor -> if (action.delta < 0) "カーソルを左へ移動" else "カーソルを右へ移動"
        is KeyAction.Space -> "空白"
        is KeyAction.Shift -> "シフト"
        else -> label
    }
    fun resource(action: KeyAction, label: String): Int? = when {
        action is KeyAction.Backspace && label == "⌫" -> R.drawable.ic_ime_backspace
        action is KeyAction.Enter && label == "↵" -> R.drawable.ic_ime_enter
        action is KeyAction.Undo && label == "↶" -> R.drawable.ic_ime_undo
        action is KeyAction.MoveCursor && label == "◀" -> R.drawable.ic_ime_cursor_left
        action is KeyAction.MoveCursor && label == "▶" -> R.drawable.ic_ime_cursor_right
        action is KeyAction.Space && label == "␣" -> R.drawable.ic_ime_space
        action is KeyAction.Shift && label == "⇧" -> R.drawable.ic_ime_shift
        action is KeyAction.Shift && label == "⬆" -> R.drawable.ic_ime_shift_active
        action is KeyAction.Shift && label == "⇪" -> R.drawable.ic_ime_shift_locked
        else -> null
    }
}
