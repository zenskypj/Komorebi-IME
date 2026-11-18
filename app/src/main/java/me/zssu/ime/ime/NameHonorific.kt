package me.zssu.ime.ime

/** Keep the typed honorific; only the exact name immediately before it is substituted. */
internal object NameHonorific {
    private val suffixes = listOf("ちゃん", "さん", "さま", "くん", "氏", "様", "君")
    fun match(reading: String): Pair<String, String>? = suffixes.firstOrNull {
        reading.length > it.length && reading.endsWith(it)
    }?.let { reading.dropLast(it.length) to it }
}
