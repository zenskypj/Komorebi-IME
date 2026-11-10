package me.zssu.ime.update

import java.math.BigInteger

/** SemVer precedence plus Komorebi's historical rc.57-dev notation. */
internal data class ReleaseVersion(
    val major: BigInteger, val minor: BigInteger, val patch: BigInteger, val pre: List<String>?,
) : Comparable<ReleaseVersion> {
    val isStable get() = pre == null
    override fun compareTo(other: ReleaseVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        if (pre == null || other.pre == null) return when { pre == other.pre -> 0; pre == null -> 1; else -> -1 }
        for (i in 0 until minOf(pre.size, other.pre.size)) {
            val a = pre[i]; val b = other.pre[i]
            val an = a.toBigIntegerOrNull(); val bn = b.toBigIntegerOrNull()
            val cmp = when { an != null && bn != null -> an.compareTo(bn); an != null -> -1; bn != null -> 1; else -> a.compareTo(b) }
            if (cmp != 0) return cmp
        }
        if (pre.size < other.pre.size && other.pre[pre.size] == "dev") return 1
        if (pre.size > other.pre.size && pre[other.pre.size] == "dev") return -1
        return pre.size.compareTo(other.pre.size)
    }
    companion object {
        private val pattern = Regex("^[vV]?(\\d{1,30})\\.(\\d{1,30})\\.(\\d{1,30})(?:-([0-9A-Za-z.-]{1,100}))?(?:\\+[0-9A-Za-z.-]{1,100})?$")
        fun parse(raw: String): ReleaseVersion? {
            val match = pattern.matchEntire(raw.trim()) ?: return null
            val suffix = match.groupValues[4]
            val pre = if (suffix.isEmpty()) null else suffix.split('.', '-').also { if (it.any(String::isEmpty)) return null }
            return ReleaseVersion(match.groupValues[1].toBigInteger(), match.groupValues[2].toBigInteger(), match.groupValues[3].toBigInteger(), pre)
        }
    }
}
