package me.zssu.ime.settings

import android.app.PendingIntent
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.nfc.cardemulation.HostApduService
import android.nfc.cardemulation.CardEmulation
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.zssu.ime.theme.ZinnaTheme
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object MeetsBeamSettings {
    private const val PREF = "meets_beam"
    fun enabled(context: Context): Boolean = context.getSharedPreferences(PREF, 0).getBoolean("enabled", false)
    fun setEnabled(context: Context, enabled: Boolean) { context.getSharedPreferences(PREF, 0).edit().putBoolean("enabled", enabled).apply() }
}

internal data class BeamMessage(val type: String, val value: String)

internal object BeamInbox {
    @Volatile private var pending: BeamMessage? = null
    @Synchronized fun put(message: BeamMessage) { pending = message }
    @Synchronized fun take(): BeamMessage? = pending.also { pending = null }
}

internal object BeamTransport {
    val aid = byteArrayOf(0xF0.toByte(), 0x4D, 0x45, 0x45, 0x54, 0x53, 0x42, 0x45, 0x41, 0x4D)
    private const val MAX_BYTES = 32768
    private fun key(password: CharArray): SecretKeySpec = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(String(password).toByteArray(Charsets.UTF_8)), "AES")
    fun seal(message: BeamMessage, password: CharArray): ByteArray {
        require(message.type in setOf("text", "url", "app"))
        val data = JSONObject().put("type", message.type).put("value", message.value).toString().toByteArray(Charsets.UTF_8)
        require(data.size in 1..MAX_BYTES)
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(password), GCMParameterSpec(128, nonce)) }
        return nonce + cipher.doFinal(data)
    }
    fun open(data: ByteArray, password: CharArray): BeamMessage {
        require(data.size in 29..MAX_BYTES + 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(password), GCMParameterSpec(128, data.copyOfRange(0, 12))) }
        val obj = JSONObject(cipher.doFinal(data.copyOfRange(12, data.size)).toString(Charsets.UTF_8))
        val type = obj.getString("type"); val value = obj.getString("value")
        require(type in setOf("text", "url", "app") && value.isNotEmpty() && value.toByteArray().size <= MAX_BYTES)
        if (type == "app") require(value.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")))
        if (type == "url") require(BeamAccessibilityService.normalize(value) == value)
        return BeamMessage(type, value)
    }
}

class MeetsBeamHostService : HostApduService() {
    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray =
        if (MeetsBeamSettings.enabled(this) && commandApdu != null) NfcMigrationSession.handle(commandApdu, BeamTransport.aid) else byteArrayOf(0x6F, 0)
    override fun onDeactivated(reason: Int) = Unit
}

/** While an activity is in the foreground, receive exactly one encrypted NFC message. */
internal class BeamReceiver(private val activity: ComponentActivity, private val onMessage: (BeamMessage) -> Unit, private val onError: (String) -> Unit = {}, private val onDetected: () -> Unit = {}) {
    private val adapter = NfcAdapter.getDefaultAdapter(activity)
    private val working = AtomicBoolean(false)
    @Volatile private var active = false
    private var preparedKeys: java.security.KeyPair? = null
    fun start(): Boolean {
        if (!MeetsBeamSettings.enabled(activity) || adapter?.isEnabled != true) return false
        active = true
        return runCatching {
            // Generate the ephemeral key before the phones touch; key generation while connected
            // can exceed the NFC presence-check window on slower devices.
            preparedKeys = NfcMigrationSession.keys()
            val options = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 1000) }
            adapter.enableReaderMode(activity, { tag -> dispatchTag(tag) },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, options)
            // Some vendor NFC stacks fall back to Android's ordinary tag intent. Route it to
            // the already-open Beam screen as well, without replacing reader mode.
            if (activity is MeetsBeamActivity) {
                val intent = Intent(activity, MeetsBeamActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val pending = PendingIntent.getActivity(activity, 0x4d42, intent, flags)
                runCatching { adapter.enableForegroundDispatch(activity, pending, null, null) }
            }
            true
        }.getOrElse { active = false; false }
    }

    fun dispatchTag(tag: Tag) {
        if (!active || !working.compareAndSet(false, true)) return
        activity.runOnUiThread { if (active) onDetected() }
        Thread({
            try {
                if (IsoDep.get(tag) == null) error("ISO-DEP 非対応: ${tag.techList.joinToString { it.substringAfterLast('.') }}")
                var message: BeamMessage? = null
                val (_, password) = NfcMigrationSession.receive(tag, BeamTransport.aid,
                    verify = { data, secret -> message = BeamTransport.open(data, secret) },
                    preparedKeys = requireNotNull(preparedKeys)) { _, _ ->
                    if (!active) throw java.io.IOException("cancelled")
                }
                password.fill('\u0000')
                val verified = requireNotNull(message)
                activity.runOnUiThread { if (active) { stop(); onMessage(verified) } }
            } catch (e: Exception) {
                val detail = when {
                    e is NfcMigrationSession.TransferFailure && e.cause is android.nfc.TagLostException -> "${e.step}中に NFC 接続が切れました。端末を近づけたまま試してください。"
                    e is NfcMigrationSession.TransferFailure -> "${e.step}で NFC 通信に失敗しました。端末を近づけ直してください。"
                    e is android.nfc.TagLostException -> "NFC 接続が切れました。端末を近づけたまま試してください。"
                    e is java.io.IOException -> "NFC 通信に失敗しました。端末を近づけ直してください。"
                    e.message?.startsWith("ISO-DEP 非対応") == true -> e.message!!
                    else -> "送受信データを確認できませんでした。両端末の Beam を再起動してください。"
                }
                activity.runOnUiThread { if (active) onError(detail) }
            } finally { working.set(false) }
        }, "meets-beam-reader").apply { isDaemon = true; start() }
    }

    fun stop() {
        active = false
        preparedKeys = null
        runCatching { adapter?.disableReaderMode(activity) }
        if (activity is MeetsBeamActivity) runCatching { adapter?.disableForegroundDispatch(activity) }
    }
}

class MeetsBeamActivity : ComponentActivity() {
    private var status by mutableStateOf("")
    private var received by mutableStateOf<BeamMessage?>(null)
    private var enabled by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var receiver: BeamReceiver? = null
    private var receivingSelected = false
    private var pendingTag: Tag? = null
    private var outgoing: BeamMessage? = null
    private val adapter by lazy { NfcAdapter.getDefaultAdapter(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enabled = MeetsBeamSettings.enabled(this)
        outgoing = outgoingFrom(intent)
        if (intent.getBooleanExtra("beam_inbox", false)) BeamInbox.take()?.let(::present)
        if (outgoing == null && BeamAccessibilityService.isBrowser(intent.getStringExtra("beam_package").orEmpty())) {
            status = "URL を取得できませんでした。URL 自動取得を有効にするか、ブラウザの共有メニューから送ってください。"
        }
        setContent { ZinnaTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Meets Beam", style = MaterialTheme.typography.headlineSmall)
                Text("双方の端末で有効にして NFC をオンにし、画面を開いたまま近づけてください。相手の本人確認は行わないため、自分の端末同士で操作してください。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Meets Beam を有効にする", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it; MeetsBeamSettings.setEnabled(this@MeetsBeamActivity, it); if (!it) { receiver?.stop(); NfcMigrationSession.clear(); receivingSelected = false; busy = false } })
                }
                Text("ブラウザの URL 自動取得は、送信操作時だけ対応ブラウザのアドレス欄を読みます。")
                OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("URL 自動取得のユーザー補助設定") }
                outgoing?.let { value -> Text(when (value.type) { "app" -> "送信するアプリ: ${value.value}"; "url" -> "送信する URL: ${value.value}"; else -> "選択したテキストを送信します" }) }
                if (status.isNotEmpty()) Text(status)
                if (adapter == null) Text("この端末では NFC を利用できません。")
                else if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) Text("この端末は NFC の送信機能に対応していません。受信側としてお試しください。")
                Button(enabled = enabled && !busy && outgoing != null && adapter?.isEnabled == true && packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_NFC_HOST_CARD_EMULATION), onClick = {
                    val message = outgoing ?: return@Button
                    runCatching {
                        val password = NfcMigrationSession.newPassword()
                        val chars = password.toCharArray()
                        val bytes = try { BeamTransport.seal(message, chars) } finally { chars.fill('\u0000') }
                        NfcMigrationSession.prepare(bytes, password, BeamTransport.aid,
                            onDetected = { runOnUiThread { status = "相手の NFC を検出しました。端末を近づけたままにしてください。" } },
                            onComplete = { runOnUiThread {
                                adapter?.let { runCatching { CardEmulation.getInstance(it).unsetPreferredService(this@MeetsBeamActivity) } }
                                busy = false; status = "送信しました。受信側を確認してください。"
                            } })
                        runCatching { CardEmulation.getInstance(adapter).setPreferredService(this@MeetsBeamActivity, ComponentName(this@MeetsBeamActivity, MeetsBeamHostService::class.java)) }
                        busy = true; status = "送信待ちです。受信側に端末を近づけてください。"
                    }.onFailure { status = "送信できません。テキストは 32 KB 以下にしてください。" }
                }) { Text("送信側になる") }
                Button(enabled = enabled && !busy && adapter?.isEnabled == true, onClick = {
                    receivingSelected = true
                    startReceiver()
                }) { Text("受信側になる") }
                if (adapter != null && adapter?.isEnabled != true) OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }) { Text("NFC をオンにする") }
                if (busy) OutlinedButton(onClick = {
                    receivingSelected = false; receiver?.stop(); NfcMigrationSession.clear()
                    adapter?.let { runCatching { CardEmulation.getInstance(it).unsetPreferredService(this@MeetsBeamActivity) } }
                    busy = false; status = "中止しました。"
                }) { Text("中止") }
                received?.let { message ->
                    if (message.type == "url") Button(onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(message.value))) }) { Text("受信した URL を開く") }
                    if (message.type == "app") Button(onClick = { openApp(message.value) }) { Text("受信したアプリを開く") }
                }
                OutlinedButton(onClick = { finish() }) { Text("戻る") }
            }
        } } }
    }

    private fun outgoingFrom(intent: Intent): BeamMessage? {
        val shared = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            else -> null
        }
        if (!shared.isNullOrBlank()) {
            val url = BeamAccessibilityService.normalize(shared)
            return BeamMessage(if (url != null && (shared.startsWith("https://") || shared.startsWith("http://"))) "url" else "text", url?.takeIf { shared.startsWith("http") } ?: shared)
        }
        intent.getStringExtra("beam_url")?.let { BeamAccessibilityService.normalize(it)?.let { url -> return BeamMessage("url", url) } }
        val pkg = intent.getStringExtra("beam_package").orEmpty()
        if (pkg.isBlank() || BeamAccessibilityService.isBrowser(pkg)) return null
        return BeamMessage("app", pkg)
    }

    private fun present(message: BeamMessage) {
        received = message
        if (message.type == "text") {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Meets Beam", message.value))
            status = "テキストを受信し、クリップボードにコピーしました。"
        } else status = if (message.type == "url") "URL を受信しました: ${message.value}" else "アプリを受信しました: ${message.value}"
    }
    private fun openApp(pkg: String) {
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(launch) }.getOrElse {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))) }
                .getOrElse { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))) }
        }
    }
    private fun startReceiver() {
        receiver?.stop()
        val next = BeamReceiver(this, { message -> receivingSelected = false; present(message); busy = false },
            { detail -> status = detail }, { status = "NFC を検出しました。通信中は端末を近づけたままにしてください。" })
        receiver = next
        busy = next.start()
        status = if (busy) "受信待ちです。送信側に端末を近づけてください。" else "NFC を確認してください。"
        pendingTag?.let { pendingTag = null; next.dispatchTag(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        if (receivingSelected && tag != null) {
            if (busy) receiver?.dispatchTag(tag) else pendingTag = tag
        }
    }

    override fun onResume() {
        super.onResume()
        if (receivingSelected && enabled && adapter?.isEnabled == true) startReceiver()
    }
    override fun onPause() {
        val wasSending = busy && !receivingSelected
        receiver?.stop(); NfcMigrationSession.clear(); busy = false
        if (wasSending) status = "画面を離れたため送信を停止しました。もう一度送信側を選んでください。"
        adapter?.let { runCatching { CardEmulation.getInstance(it).unsetPreferredService(this@MeetsBeamActivity) } }
        super.onPause()
    }
    override fun onDestroy() { receivingSelected = false; receiver?.stop(); NfcMigrationSession.clear(); super.onDestroy() }
}
