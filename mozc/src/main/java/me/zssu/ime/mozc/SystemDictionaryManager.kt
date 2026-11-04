package me.zssu.ime.mozc

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Optional complete Mozc datasets. Neither downloads nor changes touch the personal profile. */
class SystemDictionaryManager private constructor(context: Context) {
    data class Pack(
        val id: String, val name: String, val version: String, val license: String,
        val source: String, val url: String, val compressedBytes: Long, val compressedSha256: String,
        val dataBytes: Long, val dataSha256: String, val dataVersion: String,
        val runtime: String, val notice: String, val sourceIds: List<String> = emptyList(),
    )
    data class State(val requested: String = "", val sources: Set<String> = emptySet(), val applied: String? = null,
                     val downloading: String? = null, val received: Long = 0,
                     val message: String = "", val revision: Long = 0)
    internal data class Selection(val id: String, val file: File, val version: String)

    private val app = context.applicationContext
    private val directory = File(app.noBackupFilesDir, "system-dictionaries").apply { mkdirs() }
        .also { folder -> folder.listFiles()?.filter { it.name.endsWith(".part") || it.name.endsWith(".installing") }?.forEach { it.delete() } }
    private val preferences = app.getSharedPreferences("system-dictionaries", Context.MODE_PRIVATE)
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "system-dictionary-download") }
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val runtime = app.assets.open("mozc-runtime.sha256").bufferedReader().use { it.readText().trim() }
    val packs: List<Pack> = app.assets.open("system-dictionaries.json").bufferedReader().use { reader ->
        val array = JSONObject(reader.readText()).getJSONArray("packs")
        List(array.length()) { index ->
            val p = array.getJSONObject(index)
            val sourceIds = p.optJSONArray("sourceIds")?.let { ids -> List(ids.length()) { ids.getString(it) } }.orEmpty()
            Pack(p.getString("id"), p.getString("name"), p.getString("version"), p.getString("license"),
                 p.getString("source"), p.getString("url"), p.getLong("compressedBytes"), p.getString("compressedSha256"),
                 p.getLong("dataBytes"), p.getString("dataSha256"), p.getString("dataVersion"),
                 p.getString("runtime"), p.getString("notice"), sourceIds).also {
                require(it.sourceIds.all { id -> id.matches(Regex("[a-z0-9-]+")) })
                require(it.id.matches(Regex("[a-z0-9-]+")))
                require(it.url.startsWith("https://github.com/zenskypj/Komorebi-IME/releases/download/"))
                require(it.dataBytes > 0 && it.compressedBytes > 0)
            }
        }
    }
    @Volatile private var state = State(
        requested = preferences.getString("requested", "").orEmpty(),
        sources = preferences.getString("requested_sources", "").orEmpty()
            .splitToSequence(',').filter { it.isNotEmpty() }.toSet(),
    )
    @Volatile internal var requestApply: (() -> Unit)? = null

    fun currentState(): State = state
    fun compatible(pack: Pack): Boolean = pack.runtime == runtime
    fun installed(pack: Pack): Boolean = file(pack).let { it.isFile && it.length() == pack.dataBytes }
    fun notice(pack: Pack): String = app.assets.open(pack.notice).bufferedReader().use { it.readText() }
    fun observe(listener: (State) -> Unit): AutoCloseable {
        listeners.add(listener)
        ui.post { if (listener in listeners) listener(state) }
        return AutoCloseable { listeners.remove(listener) }
    }

    @Synchronized private fun update(change: (State) -> State) {
        state = change(state).copy(revision = state.revision + 1)
        val snapshot = state
        ui.post { listeners.forEach { it(snapshot) } }
    }

    fun download(pack: Pack) {
        synchronized(this) {
            if (state.downloading != null) return
            require(compatible(pack)) { "このアプリと互換性がありません" }
            update { it.copy(downloading = pack.id, received = 0, message = "取得中") }
        }
        worker.execute {
            val archive = File(directory, pack.id + ".gz.part")
            try {
                val needed = Math.addExact(pack.compressedBytes, Math.addExact(pack.dataBytes, 32L * 1024 * 1024))
                check(directory.usableSpace >= needed) { "一時ファイルを含む空き容量が不足しています" }
                var url = URL(pack.url)
                var connection: HttpURLConnection? = null
                for (redirect in 0..5) {
                    check(url.protocol == "https") { "HTTPS以外の取得先には接続できません" }
                    connection = url.openConnection() as HttpURLConnection
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 30000
                    connection.readTimeout = 60000
                    val response = connection.responseCode
                    if (response in listOf(301, 302, 303, 307, 308)) {
                        val next = connection.getHeaderField("Location") ?: error("取得先の応答が不正です")
                        connection.disconnect()
                        check(redirect < 5) { "転送回数が多すぎます" }
                        url = URL(url, next)
                    } else {
                        check(response == 200) { "取得先の応答: HTTP $response" }
                        break
                    }
                }
                val source = checkNotNull(connection)
                try {
                    var lastUpdate = 0L
                    source.inputStream.use { input ->
                        DictionaryDataFiles.copyBounded(input, archive, pack.compressedBytes) { received ->
                            val now = System.nanoTime()
                            if (now - lastUpdate > 100_000_000L) {
                                lastUpdate = now
                                update { it.copy(received = received) }
                            }
                        }
                    }
                } finally { source.disconnect() }
                check(DictionaryDataFiles.sha256(archive) == pack.compressedSha256) { "取得ファイルのSHA-256が一致しません" }
                update { it.copy(message = "展開・整合性確認中") }
                DictionaryDataFiles.installGzip(archive, file(pack), pack.dataBytes, pack.dataSha256)
                update { it.copy(downloading = null, received = pack.compressedBytes, message = "導入済み。「使用する」で切り替えます") }
            } catch (error: Exception) {
                update { it.copy(downloading = null, message = error.message ?: "取得できませんでした。再試行できます") }
            } finally { archive.delete() }
        }
    }

    /** The catalog dataset that contains every selected source, or null when none does. */
    fun resolve(sources: Set<String>): Pack? = SystemDictionarySelection.resolve(sources, packs)

    fun select(sources: Set<String>) {
        val resolved = resolve(sources)
        val usable = resolved?.takeIf { compatible(it) && installed(it) }
        val editor = preferences.edit()
            .putString("requested_sources", sources.sorted().joinToString(","))
            .putString("requested", usable?.id.orEmpty())
        if (usable != null) editor.remove("delete_${usable.id}")
        editor.apply()
        val message = when {
            sources.isEmpty() -> "入力を終えた安全なタイミングで切り替えます"
            resolved == null -> "選んだ組み合わせを含む統合データセットはまだ提供されていません"
            !compatible(resolved) -> "このアプリのMozcとは互換性がありません"
            !installed(resolved) -> "「${resolved.name}」を取得してください"
            else -> "入力を終えた安全なタイミングで切り替えます"
        }
        update { it.copy(requested = usable?.id.orEmpty(), sources = sources, message = message) }
        if (usable != null) requestApply?.invoke()
    }

    fun select(id: String) = select(if (id.isEmpty()) emptySet() else setOf(id))

    fun delete(pack: Pack) {
        // Mapped files remain in place until the standard dataset is actually running.
        preferences.edit().putBoolean("delete_" + pack.id, true).apply()
        if (state.requested == pack.id || state.applied == pack.id) select("")
        worker.execute { cleanInactiveFiles() }
    }

    internal fun selection(): Selection? {
        val id = state.requested
        if (id.isEmpty()) return null
        val pack = packs.firstOrNull { it.id == id && compatible(it) }
        return try {
            checkNotNull(pack) { "アプリ更新により辞書の互換性が変わりました" }
            DictionaryDataFiles.verify(file(pack), pack.dataBytes, pack.dataSha256)
            Selection(pack.id, file(pack), pack.dataVersion)
        } catch (error: Exception) {
            fallback(error.message ?: "追加辞書を読み込めません")
            null
        }
    }

    internal fun applied(version: String) {
        val id = packs.firstOrNull { it.dataVersion == version && compatible(it) }?.id
            ?: if (version == "24.11.oss") "" else null
        update { it.copy(applied = id, message = if (id == it.requested) "適用済み" else "切り替え待ち") }
        worker.execute { cleanInactiveFiles() }
    }

    internal fun fallback(reason: String) {
        preferences.edit().putString("requested", "").apply()
        update { it.copy(requested = "", message = "$reason。標準辞書に戻します") }
    }

    private fun cleanInactiveFiles() {
        packs.filter { preferences.getBoolean("delete_" + it.id, false) }.forEach { pack ->
            if (state.applied != null && state.applied != pack.id && state.requested != pack.id) {
                if (!file(pack).exists() || file(pack).delete()) {
                    preferences.edit().remove("delete_" + pack.id).apply()
                    update { it.copy(message = "辞書を削除しました") }
                }
            }
        }
    }
    private fun file(pack: Pack) = File(directory, pack.id + ".data")

    companion object {
        @Volatile private var instance: SystemDictionaryManager? = null
        fun get(context: Context): SystemDictionaryManager = instance ?: synchronized(this) {
            instance ?: SystemDictionaryManager(context).also { instance = it }
        }
    }
}
