package me.zssu.ime.update

import org.json.JSONArray

/** GitHub's prerelease flag has not always matched Komorebi's historical RC tags. */
internal object ReleaseSelector {
    data class Releases(val latest: String, val stable: String?)
    fun select(body: String): Releases {
        val array = JSONArray(body)
        var bestTag: String? = null
        var best: ReleaseVersion? = null
        var stableTag: String? = null
        var stable: ReleaseVersion? = null
        for (i in 0 until array.length()) {
            val release = array.optJSONObject(i) ?: continue
            if (release.optBoolean("draft") || release.isNull("published_at")) continue
            val tag = release.optString("tag_name")
            val version = ReleaseVersion.parse(tag) ?: continue
            if (best == null || version > best) { best = version; bestTag = tag }
            if (version.isStable && (stable == null || version > stable)) { stable = version; stableTag = tag }
        }
        return Releases(checkNotNull(bestTag) { "No published versioned release" }, stableTag)
    }
}
