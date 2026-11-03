package me.zssu.ime.settings

import android.accessibilityservice.AccessibilityService
import android.net.Uri
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Reads a supported browser's address field only in response to the Beam toolbar action. */
class BeamAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    private fun readUrl(packageName: String): String? {
        val ids = BROWSER_FIELDS[packageName] ?: return null
        val roots = windows.orEmpty().mapNotNull { it.root } + listOfNotNull(rootInActiveWindow)
        for (root in roots) {
            if (root.packageName?.toString() != packageName) continue
            for (id in ids) {
                val nodes = root.findAccessibilityNodeInfosByViewId("$packageName:id/$id").orEmpty()
                for (node in nodes) {
                    for (candidate in listOf(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty())) {
                        if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) continue
                        val url = normalize(candidate)
                        if (url != null) return url
                    }
                }
            }
        }
        return null
    }

    companion object {
        @Volatile private var instance: BeamAccessibilityService? = null
        private val BROWSER_FIELDS = mapOf(
            "com.android.chrome" to listOf("url_bar"),
            "com.chrome.beta" to listOf("url_bar"),
            "com.chrome.dev" to listOf("url_bar"),
            "com.microsoft.emmx" to listOf("url_bar"),
            "com.brave.browser" to listOf("url_bar"),
            "com.sec.android.app.sbrowser" to listOf("location_bar_edit_text", "url_bar"),
            "org.mozilla.firefox" to listOf("mozac_browser_toolbar_url_view", "url_view", "url_bar"),
        )
        fun isBrowser(packageName: String): Boolean = packageName in BROWSER_FIELDS
        fun browserUrl(packageName: String): String? {
            val service = instance ?: return null
            if (!MeetsBeamSettings.enabled(service)) return null
            return runCatching { service.readUrl(packageName) }.getOrNull()
        }
        fun normalize(value: String): String? {
            val input = value.trim()
            if (input.length !in 1..8192 || input.any { it.isWhitespace() || it.isISOControl() }) return null
            val withScheme = if (input.startsWith("https://") || input.startsWith("http://")) input else "https://$input"
            val uri = runCatching { Uri.parse(withScheme) }.getOrNull() ?: return null
            if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) return null
            if (!uri.host!!.contains('.') && uri.host != "localhost") return null
            return uri.toString()
        }
    }
}
