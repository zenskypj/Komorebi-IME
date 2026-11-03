package me.zssu.ime.ime

/**
 * Builds the instruction a proofreading run sends to the on-device model.
 *
 * Kept apart from [ProofreadActivity] so the wording, the length limits and the control-token
 * handling can be tested without an Android context, and so every entry point produces the exact
 * same prompt. Nothing here touches the network or the disk; it is pure text assembly.
 */
internal object ProofreadPrompt {

    /** The rewrite the user asked for. [FREE] carries the user's own instruction. */
    enum class Style(val label: String, val description: String) {
        FIX("誤字・文法を直す", "誤字脱字と文法の誤りだけを直す"),
        POLITE("丁寧にする", "丁寧で礼儀正しい敬語の表現に書き換える"),
        CONCISE("簡潔にする", "できるだけ簡潔で無駄のない表現に書き換える"),
        NATURAL("自然な文章にする", "日本語として自然で読みやすい文章に書き換える"),
        FREE("自由に指示する", "利用者が指定した方針で書き換える"),
    }

    /** Longest text a single review may cover, in Java chars. */
    const val MAX_TEXT = 600

    /** Longest free-form instruction accepted, in Java chars. */
    const val MAX_INSTRUCTION = 200

    private const val DEFAULT_FREE_INSTRUCTION = "自然で読みやすい文章に書き換えてください。"

    /** Any `<|...|>` run, i.e. a ChatML/llama control token. */
    private val CONTROL_TOKEN = Regex("<\\|[^|>]*\\|>")

    /**
     * Strips control tokens and normalises newlines.
     *
     * The prompt is assembled by hand around the user's text, so a pasted `<|im_end|>` or
     * `<|im_start|>` could otherwise forge a new role and hijack the model. Removing every
     * `<|...|>` run keeps the text from ever being read as structure. The token itself is not
     * content, so it is dropped rather than replaced with a space.
     */
    fun sanitize(value: String): String =
        value.replace(CONTROL_TOKEN, "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()

    fun textWithinLimit(value: String): Boolean = sanitize(value).length <= MAX_TEXT

    fun instructionWithinLimit(value: String): Boolean = sanitize(value).length <= MAX_INSTRUCTION

    /** The concrete rewrite instruction for [style], resolving [FREE] against the user's text. */
    fun instructionFor(style: Style, freeInstruction: String): String =
        if (style == Style.FREE) {
            sanitize(freeInstruction).ifBlank { DEFAULT_FREE_INSTRUCTION }
        } else {
            "${style.description}。意味や語句の選び方は変えないでください。"
        }

    fun systemInstruction(style: Style, freeInstruction: String): String =
        "あなたは日本語の校正・推敲を行う編集者です。" +
            instructionFor(style, freeInstruction) +
            "書き換えた文章だけを返し、説明や前置きは書かないでください。"

    /** The full ChatML prompt for one review. The text is sanitised before it is embedded. */
    fun prompt(text: String, style: Style, freeInstruction: String = ""): String = buildString {
        append("<|im_start|>system\n")
        append(systemInstruction(style, freeInstruction))
        append("<|im_end|>\n<|im_start|>user\n")
        append(sanitize(text))
        append(" /no_think")
        append("<|im_end|>\n<|im_start|>assistant\n")
    }
}
