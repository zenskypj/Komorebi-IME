package me.zssu.ime.ime

import java.util.Base64

/** Host-only adapter: exercise the actual app prompt and postprocessor, without Android. */
fun main(args: Array<String>) {
    val format = ProofreadPrompt.Format.valueOf(args[0])
    val baseline = args.getOrNull(1) == "baseline"
    fun decode(s: String) = String(Base64.getDecoder().decode(s), Charsets.UTF_8)
    fun encode(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
    generateSequence(::readLine).forEach { line ->
        try {
            val parts = line.split('\t')
            val source = decode(parts[1])
            val result = if (parts[0] == "P") ProofreadPrompt.prompt(source, ProofreadPrompt.Style.FIX, format = format)
                else {
                    val extracted = ProofreadPrompt.extract(decode(parts[2]))
                    check(extracted.isNotBlank() && extracted.length <= 1800 && '\uFFFD' !in extracted)
                    check(!ProofreadPrompt.hasUnexpectedReasoningSwitch(source, extracted))
                    if (baseline) extracted else ProofreadReview.apply(source, extracted)
                }
            println("OK\t" + encode(result))
        } catch (e: Exception) { println("ERR\t" + encode(e.message ?: "Invalid output")) }
    }
}
