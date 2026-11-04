package me.zssu.ime.ime

/**
 * Builds the instruction a proofreading run sends to the on-device model.
 *
 * Kept apart from [ProofreadActivity] so the wording, the length limits and the control-token
 * handling can be tested without an Android context. Nothing here touches the network or the disk;
 * it is pure text assembly.
 *
 * The native runtime does NOT apply a chat template: it tokenizes the bytes we hand it (parsing
 * special tokens) and decodes greedily. The prompt therefore has to be framed for the target
 * family — see [Format]. The wording is deliberately explicit and rule-based so that small models
 * with weak instruction-following still return just the rewritten sentence.
 */
internal object ProofreadPrompt {

    /** The rewrite the user asked for. [FREE] carries the user's own instruction. */
    enum class Style(val label: String, val description: String) {
        FIX("誤字・文法を直す", "誤字脱字と文法の誤りだけを直す。意味や語句の選び方は変えない"),
        POLITE("丁寧にする", "意味を変えずに、丁寧で礼儀正しい敬語の表現に書き換える"),
        CONCISE("簡潔にする", "意味を変えずに、できるだけ簡潔で無駄のない表現に書き換える"),
        NATURAL("自然な文章にする", "意味を変えずに、日本語として自然で読みやすい文章に書き換える"),
        FREE("自由に指示する", "利用者が指定した方針で書き換える"),
    }

    /** How a model family frames its chat turns (and disables reasoning). */
    enum class Format { QWEN, GEMMA }

    /** Longest text a single review may cover, in Java chars. */
    const val MAX_TEXT = 600

    /** Longest free-form instruction accepted, in Java chars. */
    const val MAX_INSTRUCTION = 200

    private const val DEFAULT_FREE_INSTRUCTION = "自然で読みやすい文章に書き換えてください。"

    /** A ChatML/llama control token such as `<|im_start|>`. */
    private val USER_CONTROL = Regex("<\\|[^|>]*\\|>")

    /** Any control token, including Gemma's `<start_of_turn>` / `<channel|>` forms. */
    private val OUTPUT_CONTROL = Regex("<\\|?[A-Za-z0-9_]+\\|?>")

    /** Qwen3's thinking delimiters, assembled from parts so the literal stays plain ASCII. */
    private val THINK_OPEN = "<" + "think" + ">"
    private val THINK_CLOSE = "<" + "/" + "think" + ">"

    /**
     * Strips control tokens and normalises newlines in user-supplied text.
     *
     * The prompt is assembled by hand around the user's text, so a pasted `<|im_end|>` or
     * `<|im_start|>` could otherwise forge a new role and hijack the model. Only the `<|...|>`
     * shape is removed here, so ordinary angle brackets in the text survive.
     */
    fun sanitize(value: String): String =
        value.replace(USER_CONTROL, "")
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
            "${style.description}。"
        }

    /**
     * The rules every model must follow.
     *
     * Deliberately short. A small on-device model follows four tight constraints more reliably
     * than a long numbered list, and every extra sentence is prefill the runtime must process on a
     * 2048-token context (with the reviewed text competing for the same budget). The four that
     * carry their weight are: body only, no explanation/markup, do not change meaning or translate,
     * and leave already-correct text alone.
     */
    fun systemInstruction(style: Style, freeInstruction: String): String =
        "日本語の校正者として、与えられた文だけを直し、直した本文だけを出力します。" +
            "説明・前置き・見出し・箇条書き・引用符・マークダウンは付けません。" +
            "意味・数値・改行を変えず、翻訳や加筆をしません。" +
            "直す必要がない箇所はそのままにします。方針: " +
            instructionFor(style, freeInstruction)

    /** The full prompt for one review. The text is sanitised before it is embedded. */
    fun prompt(
        text: String,
        style: Style,
        freeInstruction: String = "",
        format: Format = Format.QWEN,
    ): String = when (format) {
        Format.QWEN -> buildString {
            append("<|im_start|>system\n")
            append(systemInstruction(style, freeInstruction))
            append("<|im_end|>\n<|im_start|>user\n")
            append(sanitize(text))
            append("<|im_end|>\n<|im_start|>assistant\n")
            // Prefilling a closed thinking block hard-disables reasoning. This is exactly what
            // Qwen3's own chat template writes when enable_thinking is false, so the first
            // generated token should be the rewritten sentence. Keep reasoning controls in the
            // assistant prefix, never appended to the user's text where they can be copied.
            append(THINK_OPEN).append("\n\n").append(THINK_CLOSE).append("\n\n")
        }
        Format.GEMMA -> buildString {
            // Gemma 4 supports a native system role. Omitting <|think|> disables thinking.
            append("<start_of_turn>system\n")
            append(systemInstruction(style, freeInstruction))
            append("<end_of_turn>\n<start_of_turn>user\n")
            append(sanitize(text))
            append("<end_of_turn>\n<start_of_turn>model\n")
        }
    }

    private val REASONING_SWITCH = Regex("/(?:no_think|think)(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)

    /** Reject leaked switches, while preserving documents which actually discuss those commands. */
    fun hasUnexpectedReasoningSwitch(source: String, result: String): Boolean {
        fun counts(value: String) = REASONING_SWITCH.findAll(value)
            .groupingBy { it.value.lowercase(java.util.Locale.ROOT) }.eachCount()
        val original = counts(source)
        return counts(result).any { (command, count) -> count > (original[command] ?: 0) }
    }

    /**
     * The final answer from a raw model response.
     *
     * Models sometimes emit a reasoning block before the answer — Qwen as `...</think>` and Gemma 4
     * as `<|channel>thought ... <channel|>`. Everything up to the last marker is dropped, then any
     * leftover control tokens are removed so the caller gets plain text.
     */
    fun extract(raw: String): String {
        val body = when {
            "<channel|>" in raw -> raw.substringAfter("<channel|>")
            THINK_CLOSE in raw -> raw.substringAfter(THINK_CLOSE)
            else -> raw
        }
        return body.replace(OUTPUT_CONTROL, "").trim()
    }
}
