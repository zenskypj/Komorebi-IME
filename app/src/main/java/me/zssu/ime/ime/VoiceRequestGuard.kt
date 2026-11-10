package me.zssu.ime.ime

/** No editor content is sent to the module. Apply only if the same field is still unchanged. */
internal data class VoiceEditorAnchor(
    val packageName: String,
    val fieldId: Int,
    val fieldName: String?,
    val inputType: Int,
    val selectionStart: Int,
    val selectionEnd: Int,
    val startOffset: Int,
    val text: String,
) {
    fun matches(other: VoiceEditorAnchor?): Boolean = other != null && this == other
}

internal data class VoicePending(val id: Long, val anchor: VoiceEditorAnchor, val expiresAt: Long, var text: String? = null)
