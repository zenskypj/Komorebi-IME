package me.zssu.ime.ime


internal object SnippetTemplate {
    data class Expansion(val text: String, val caret: Int)
    fun date(pattern: String): String = java.text.SimpleDateFormat(pattern, java.util.Locale.JAPAN).format(java.util.Date())
    fun validDateFormat(pattern: String): Boolean = pattern.length in 1..40 && runCatching { date(pattern) }.isSuccess

    private val token = Regex("""\{\{(名前|日付|選択|カーソル)}}""")
    fun expand(template: String, name: String, selection: String, date: String): Expansion {
        val output = StringBuilder()
        var from = 0
        var caret: Int? = null
        for (match in token.findAll(template)) {
            output.append(template, from, match.range.first)
            when (match.groupValues[1]) {
                "名前" -> output.append(name)
                "日付" -> output.append(date)
                "選択" -> output.append(selection)
                "カーソル" -> if (caret == null) caret = output.length
            }
            from = match.range.last + 1
        }
        output.append(template, from, template.length)
        return Expansion(output.toString(), caret ?: output.length)
    }
}
