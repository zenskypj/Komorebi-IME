package me.zssu.ime.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.NetworkInfo
import android.net.Uri
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.cardemulation.CardEmulation
import android.nfc.cardemulation.HostApduService
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import me.zssu.ime.theme.ZinnaTheme
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object MeetsBeamSettings {
    private const val PREF = "meets_beam"
    fun enabled(context: Context): Boolean = context.getSharedPreferences(PREF, 0).getBoolean("enabled", false)
    fun setEnabled(context: Context, enabled: Boolean) { context.getSharedPreferences(PREF, 0).edit().putBoolean("enabled", enabled).apply() }
}

internal data class BeamMessage(
    val type: String,
    val value: String,
    val name: String = "",
    val mimeType: String = "",
    val size: Long = 0L,
    val extra: String = "",
    val targetType: String = ""
)

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
        require(message.type in setOf("text", "url", "app", "dict", "file", "handover"))
        val obj = JSONObject().put("type", message.type).put("value", message.value)
        if (message.name.isNotEmpty()) obj.put("name", message.name)
        if (message.mimeType.isNotEmpty()) obj.put("mimeType", message.mimeType)
        if (message.size > 0L) obj.put("size", message.size)
        val data = obj.toString().toByteArray(Charsets.UTF_8)
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
        require(type in setOf("text", "url", "app", "dict", "file", "handover") && value.isNotEmpty() && value.toByteArray().size <= MAX_BYTES)
        if (type == "app") require(value.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")))
        if (type == "url") {
            val parsed = runCatching { java.net.URI(value) }.getOrNull()
            require(parsed != null && (parsed.scheme == "http" || parsed.scheme == "https") && !parsed.host.isNullOrBlank())
        }
        val name = obj.optString("name", "")
        val mimeType = obj.optString("mimeType", "")
        val size = obj.optLong("size", 0L)
        return BeamMessage(type, value, name, mimeType, size)
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
            preparedKeys = NfcMigrationSession.keys()
            val options = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 1000) }
            adapter.enableReaderMode(activity, { tag -> dispatchTag(tag) },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, options)
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
    }
}

// Wi-Fi Direct calls are reached only after withP2pPermission() granted the runtime permission.
@SuppressLint("MissingPermission")
class MeetsBeamActivity : ComponentActivity() {
    private companion object {
        const val MAX_DICTIONARY_BYTES = 5 * 1024 * 1024
        const val P2P_CONNECT_TIMEOUT_MS = 180000L
        const val P2P_DIRECT_CONNECT_DELAY_MS = 5000L
        const val P2P_CONNECT_RETRIES = 2
    }

    private var status by mutableStateOf("")
    private var received by mutableStateOf<BeamMessage?>(null)
    private var receivedFile by mutableStateOf<File?>(null)
    private var pendingDictionary by mutableStateOf<BeamMessage?>(null)
    private var enabled by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var receiver: BeamReceiver? = null
    private var receivingSelected = false
    private var outgoing: BeamMessage? = null
    private val adapter by lazy { NfcAdapter.getDefaultAdapter(this) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var transferCancel = AtomicBoolean(false)

    private var p2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var p2pReceiver: BroadcastReceiver? = null
    private var localP2pDevice: WifiP2pDevice? = null
    private var receivePayload: BeamHandover.HandoverPayload? = null
    private var p2pConnecting = false
    private var p2pConnectAttempts = 0
    private var pendingSenderPayload: BeamHandover.HandoverPayload? = null
    private var p2pSendTransferStarted = false
    private var p2pReceiveTransferStarted = false
    private var directTransferActive = false
    private var pendingP2pAction: (() -> Unit)? = null

    private val p2pPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingP2pAction
        pendingP2pAction = null
        if (granted) {
            if (locationServicesReady()) action?.invoke() else status = "Wi-Fi Direct を使うには位置情報をオンにしてください"
        } else {
            status = "Wi-Fi Direct の権限が必要です"
        }
    }

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
                Text("双方の端末で有効にして NFC をオンにし、画面を開いたまま近づけてください。大容量ファイルや辞書パックは Wi-Fi Direct（P2P）で端末間を直接転送します。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Meets Beam を有効にする", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = {
                        enabled = it; MeetsBeamSettings.setEnabled(this@MeetsBeamActivity, it)
                        if (!it) {
                            receiver?.stop(); NfcMigrationSession.clear(); receivingSelected = false; busy = false
                            transferCancel.set(true); removeP2pGroup(); directTransferActive = false
                        }
                    })
                }
                Text("ブラウザの URL 自動取得は、送信操作時だけ対応ブラウザのアドレス欄を読みます。")
                OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("URL 自動取得のユーザー補助設定") }
                outgoing?.let { value ->
                    Text(when (value.type) {
                        "app" -> "送信するアプリ: ${value.value}"
                        "url" -> "送信する URL: ${value.value}"
                        "dict" -> "送信する辞書: ${value.name} (${value.value.lines().count { it.isNotBlank() }}語)"
                        "handover" -> "送信するデータ (Wi-Fi Direct): ${value.name} (${formatSize(value.size)})"
                        "file" -> "送信するファイル: ${value.name} (${formatSize(value.size)})"
                        else -> "選択したテキストを送信します"
                    })
                }
                if (status.isNotEmpty()) Text(status)
                if (adapter == null) Text("この端末では NFC を利用できません。")
                else if (!packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) Text("この端末は NFC の送信機能に対応していません。受信側としてお試しください。")
                Button(enabled = enabled && !busy && outgoing != null && adapter?.isEnabled == true && packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION), onClick = {
                    val message = outgoing ?: return@Button
                    if (message.type == "handover") {
                        withP2pPermission { prepareP2pSend(message) }
                    } else {
                        startNfcSend(message)
                    }
                }) { Text("送信側になる") }
                Button(enabled = enabled && !busy && adapter?.isEnabled == true, onClick = {
                    receivingSelected = true
                    startReceiver()
                }) { Text("受信側になる") }
                if (adapter != null && adapter?.isEnabled != true) OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }) { Text("NFC をオンにする") }
                if (busy) OutlinedButton(onClick = {
                    receivingSelected = false; receiver?.stop(); NfcMigrationSession.clear()
                    transferCancel.set(true); removeP2pGroup(); directTransferActive = false
                    p2pConnecting = false; p2pReceiveTransferStarted = false
                    adapter?.let { runCatching { CardEmulation.getInstance(it).unsetPreferredService(this@MeetsBeamActivity) } }
                    busy = false; status = "中止しました。"
                }) { Text("中止") }
                received?.let { message ->
                    if (message.type == "url") Button(onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(message.value))) }) { Text("受信した URL を開く") }
                    if (message.type == "app") Button(onClick = { openApp(message.value) }) { Text("受信したアプリを開く") }
                    if (message.type == "dict") {
                        Button(onClick = { pendingDictionary = message }) { Text("ユーザー辞書に取り込む") }
                    }
                    if (message.type == "handover") {
                        val payload = runCatching { BeamHandover.HandoverPayload.fromJson(message.value) }.getOrNull()
                        if (payload != null) {
                            Button(enabled = enabled && !busy, onClick = { withP2pPermission { beginP2pReceive(payload) } }) { Text("Wi-Fi Direct で受信を開始") }
                        }
                    }
                    receivedFile?.let { file ->
                        Button(onClick = { openReceivedFile(file, message.mimeType) }) { Text("ファイルを開く／共有") }
                    }
                }
                if (!busy && receivingSelected) OutlinedButton(onClick = { startReceiver() }) { Text("受信を再試行") }
                OutlinedButton(onClick = { finish() }) { Text("戻る") }
            }
        }
        pendingDictionary?.let { message ->
            AlertDialog(
                onDismissRequest = { pendingDictionary = null },
                title = { Text("ユーザー辞書を取り込む") },
                text = { Text("現在のユーザー辞書を、受信した内容で置き換えます。必要な場合は先に書き出してください。") },
                confirmButton = {
                    TextButton(onClick = {
                        importDictionary(message)
                        pendingDictionary = null
                    }) { Text("置き換える") }
                },
                dismissButton = { TextButton(onClick = { pendingDictionary = null }) { Text("キャンセル") } },
            )
        }
        } }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024 * 1024))
    }

    private fun resolveFileInfo(uri: Uri): Pair<String, Long> {
        var name = "shared_file"
        var size = 0L
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                }
            }
            if (size <= 0L) {
                size = runCatching {
                    contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
                }.getOrDefault(0L)
            }
        }
        return name to size
    }

    private fun outgoingFrom(intent: Intent): BeamMessage? {
        intent.getStringExtra("beam_dict_tsv")?.let { tsv ->
            val name = intent.getStringExtra("beam_dict_name") ?: "ユーザー辞書"
            val size = tsv.toByteArray(Charsets.UTF_8).size.toLong()
            return if (size <= 28000) {
                BeamMessage("dict", tsv, name = name, mimeType = "text/tab-separated-values", size = size)
            } else {
                BeamMessage("handover", "", name = name, mimeType = "text/tab-separated-values", size = size, extra = tsv, targetType = "dict")
            }
        }

        if (intent.action == Intent.ACTION_SEND) {
            val streamUri = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (streamUri != null && streamUri.scheme == "content") {
                val mime = intent.type ?: contentResolver.getType(streamUri) ?: "application/octet-stream"
                val (name, size) = resolveFileInfo(streamUri)
                if (size > 0) {
                    val target = if (mime.startsWith("text/") || name.endsWith(".tsv")) "dict" else "file"
                    return BeamMessage("handover", "", name = name, mimeType = mime, size = size, extra = streamUri.toString(), targetType = target)
                }
            }
        }

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

    private fun withP2pPermission(action: () -> Unit) {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            status = "この端末は Wi-Fi Direct に対応していません"
            return
        }
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            if (locationServicesReady()) action() else status = "Wi-Fi Direct を使うには位置情報をオンにしてください"
        } else {
            pendingP2pAction = action
            p2pPermissionLauncher.launch(permission)
        }
    }

    private fun locationServicesReady(): Boolean {
        val manager = getSystemService(LocationManager::class.java) ?: return true
        val enabled = if (Build.VERSION.SDK_INT >= 28) {
            manager.isLocationEnabled
        } else {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
        if (!enabled) {
            status = "Wi-Fi Direct の検出には端末の位置情報をオンにしてください（位置の記録・送信はしません）"
        }
        return enabled
    }

    private fun ensureP2p(): Boolean {
        p2pManager?.let { return true }
        val manager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager ?: run {
            status = "この端末は Wi-Fi Direct に対応していません"
            return false
        }
        val channel = manager.initialize(this, mainLooper, null) ?: run {
            status = "Wi-Fi Direct を初期化できません"
            return false
        }
        p2pManager = manager
        p2pChannel = channel
        registerP2pReceiver()
        return true
    }

    private fun registerP2pReceiver() {
        if (p2pReceiver != null) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        if (intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                            status = "Wi-Fi をオンにしてください"
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        val networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO) as? NetworkInfo
                        if (networkInfo == null || networkInfo.isConnected) {
                            requestP2pConnectionInfo()
                        } else if (p2pConnecting) {
                            status = "Wi-Fi Direct の接続成立を待っています..."
                        }
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> tryConnectToOwner()
                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        val device = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE) as? WifiP2pDevice
                        if (device != null) localP2pDevice = device
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        p2pReceiver = receiver
    }

    private fun prepareP2pSend(message: BeamMessage) {
        val size = message.size
        if (size !in 1..BeamHandover.MAX_TRANSFER_BYTES) {
            status = "ファイルサイズを取得できません。別のアプリから共有してください"
            return
        }
        if (!ensureP2p()) return
        val manager = p2pManager ?: return
        val channel = p2pChannel ?: return
        receivingSelected = false
        receiver?.stop()
        receivePayload = null
        p2pConnecting = false
        p2pReceiveTransferStarted = false
        busy = true
        status = "Wi-Fi Direct の準備をしています..."
        transferCancel = AtomicBoolean(false)
        directTransferActive = true
        p2pSendTransferStarted = false
        pendingSenderPayload = null
        outgoing = message
        val attempt = transferCancel
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            private fun proceed() { if (!attempt.get() && transferCancel === attempt) beginSenderHandshake(manager, channel, message) }
            override fun onSuccess() = proceed()
            override fun onFailure(reason: Int) = proceed()
        })
    }

    private fun beginSenderHandshake(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        message: BeamMessage,
    ) {
        val attempt = transferCancel
        withLocalP2pAddress { ownerAddress ->
            if (attempt.get() || transferCancel !== attempt) return@withLocalP2pAddress
            val payload = BeamHandover.HandoverPayload(
                targetType = message.targetType.ifBlank { "file" },
                name = message.name.ifBlank { "beam_file" },
                size = message.size,
                mimeType = message.mimeType.ifBlank { "application/octet-stream" },
                ownerAddress = if (BeamHandover.isUsableP2pAddress(ownerAddress)) ownerAddress ?: "" else "",
                ownerIp = "192.168.49.1",
                port = BeamHandover.DEFAULT_PORT,
                token = BeamHandover.generateToken(),
                ownerName = localP2pDevice?.deviceName.orEmpty(),
            )
            pendingSenderPayload = payload
            val updated = message.copy(value = payload.toJson())
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    status = "相手の接続を待っています..."
                }

                override fun onFailure(reason: Int) {
                    status = "Wi-Fi Direct の検索を開始できません ($reason)。NFC は送信できます"
                }
            })
            startNfcSend(updated)
            mainHandler.postDelayed({
                if (!attempt.get() && transferCancel === attempt && !p2pSendTransferStarted && pendingSenderPayload === payload) {
                    busy = false
                    directTransferActive = false
                    status = "Wi-Fi Direct の接続がタイムアウトしました"
                    removeP2pGroup()
                }
            }, P2P_CONNECT_TIMEOUT_MS)
        }
    }

    private fun createGroup(manager: WifiP2pManager, channel: WifiP2pManager.Channel) {
        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                status = "Wi-Fi Direct グループを作成しました。接続情報を準備しています..."
                var proceeded = false
                fun proceed(groupOwnerAddress: String?, groupOwnerName: String?) {
                    if (proceeded) return
                    proceeded = true
                    manager.requestConnectionInfo(channel) { info: WifiP2pInfo ->
                        val infoAddress = info.groupOwnerAddress?.hostAddress
                        val ownerIp = listOfNotNull(infoAddress, BeamHandover.findP2pIpv4())
                            .firstOrNull { BeamHandover.isAssignedIpv4(it) }
                            ?: infoAddress
                            ?: "192.168.49.1"
                        withLocalP2pAddress(groupOwnerAddress) { ownerAddress ->
                            val resolvedAddress = if (BeamHandover.isUsableP2pAddress(ownerAddress)) {
                                ownerAddress ?: ""
                            } else {
                                ""
                            }
                            val resolvedName = groupOwnerName?.takeIf { it.isNotBlank() }
                                ?: localP2pDevice?.deviceName.orEmpty()
                            val draft = outgoing
                            if (draft == null) {
                                busy = false
                                status = "送信データがありません"
                                directTransferActive = false
                                removeP2pGroup()
                                return@withLocalP2pAddress
                            }
                            status = if (resolvedAddress.isEmpty()) {
                                "端末アドレスを取得できないため、端末名と探索結果で接続します..."
                            } else {
                                "Wi-Fi Direct の受信サーバーを準備しています..."
                            }
                            Thread({
                                try {
                                    val server = BeamHandover.openServer(ownerIp)
                                    runOnUiThread {
                                        status = "受信サーバーを開きました (${ownerIp}:${server.localPort})。NFC で接続情報を送ります..."
                                        val payload = BeamHandover.HandoverPayload(
                                            targetType = draft.targetType.ifBlank { "file" },
                                            name = draft.name.ifBlank { "beam_file" },
                                            size = draft.size,
                                            mimeType = draft.mimeType.ifBlank { "application/octet-stream" },
                                            ownerAddress = resolvedAddress,
                                            ownerIp = ownerIp,
                                            port = server.localPort,
                                            token = BeamHandover.generateToken(),
                                            ownerName = resolvedName,
                                        )
                                        val updated = draft.copy(value = payload.toJson())
                                        startDirectSend(updated, payload, server) { startNfcSend(updated) }
                                    }
                                } catch (e: Exception) {
                                    runOnUiThread {
                                        busy = false
                                        directTransferActive = false
                                        status = "Wi-Fi Direct サーバーを開けません: ${e.message}"
                                        removeP2pGroup()
                                    }
                                }
                            }, "beam-server-open").start()
                        }
                    }
                }
                manager.requestGroupInfo(channel) { group ->
                    proceed(group?.owner?.deviceAddress, group?.owner?.deviceName)
                }
                mainHandler.postDelayed({ proceed(localP2pDevice?.deviceAddress, localP2pDevice?.deviceName) }, 3000)
            }

            override fun onFailure(reason: Int) {
                busy = false
                directTransferActive = false
                status = "Wi-Fi Direct グループを作成できません ($reason)。Wi-Fi を確認してください"
            }
        })
    }

    private fun withLocalP2pAddress(
        preferred: String? = null,
        attempt: Int = 0,
        block: (String?) -> Unit,
    ) {
        val current = localP2pDevice?.deviceAddress
        if (BeamHandover.isUsableP2pAddress(current)) {
            block(current)
            return
        }
        if (BeamHandover.isUsableP2pAddress(preferred)) {
            block(preferred)
            return
        }
        val manager = p2pManager
        val channel = p2pChannel
        if (Build.VERSION.SDK_INT >= 29 && manager != null && channel != null) {
            manager.requestDeviceInfo(channel) { device ->
                localP2pDevice = device
                val address = device?.deviceAddress
                if (BeamHandover.isUsableP2pAddress(address)) {
                    block(address)
                } else {
                    retryLocalP2pAddress(preferred, attempt, block)
                }
            }
        } else {
            retryLocalP2pAddress(preferred, attempt, block)
        }
    }

    private fun retryLocalP2pAddress(preferred: String?, attempt: Int, block: (String?) -> Unit) {
        if (attempt >= 5) {
            block(null)
            return
        }
        mainHandler.postDelayed({
            withLocalP2pAddress(preferred, attempt + 1, block)
        }, 1000)
    }

    private fun startNfcSend(message: BeamMessage) {
        receivingSelected = false
        receiver?.stop()
        if (adapter?.isEnabled != true) {
            status = "NFC をオンにしてください"
            transferCancel.set(true)
            directTransferActive = false
            busy = false
            removeP2pGroup()
            return
        }
        runCatching {
            val password = NfcMigrationSession.newPassword()
            val chars = password.toCharArray()
            val bytes = try { BeamTransport.seal(message, chars) } finally { chars.fill('\u0000') }
            NfcMigrationSession.prepare(bytes, password, BeamTransport.aid,
                onDetected = { runOnUiThread { status = "相手の NFC を検出しました。端末を近づけたままにしてください。" } },
                onComplete = { runOnUiThread {
                    adapter?.let { runCatching { CardEmulation.getInstance(it).unsetPreferredService(this@MeetsBeamActivity) } }
                    if (message.type != "handover" || !directTransferActive) busy = false
                    status = if (message.type == "handover") {
                        "接続情報を送信しました。受信側で「Wi-Fi Direct で受信を開始」を押してください。"
                    } else {
                        "送信しました。受信側を確認してください。"
                    }
                } })
            check(CardEmulation.getInstance(adapter).setPreferredService(this, ComponentName(this, MeetsBeamHostService::class.java))) { "NFC の送信サービスを有効にできません" }
            busy = true
            status = "送信待ちです。受信側に端末を近づけてください。"
        }.onFailure {
            NfcMigrationSession.clear()
            transferCancel.set(true)
            busy = false
            directTransferActive = false
            removeP2pGroup()
            status = "送信準備に失敗しました: ${it.message}"
        }
    }

    private fun startDirectSend(
        message: BeamMessage,
        payload: BeamHandover.HandoverPayload,
        server: ServerSocket,
        onReady: () -> Unit,
    ) {
        val stream = runCatching {
            when {
                message.extra.startsWith("content://") -> contentResolver.openInputStream(Uri.parse(message.extra))
                payload.targetType == "dict" && message.extra.isNotEmpty() -> message.extra.byteInputStream(Charsets.UTF_8)
                else -> null
            }
        }.getOrNull()
        if (stream == null) {
            runCatching { server.close() }
            busy = false
            directTransferActive = false
            removeP2pGroup()
            status = "送信データを開けません"
            return
        }
        Thread({
            try {
                stream.use { input ->
                    val success = BeamHandover.sendStream(
                        server = server,
                        token = payload.token,
                        input = input,
                        totalBytes = payload.size,
                        onProgress = { cur, total ->
                            runOnUiThread { status = "送信中... ${formatSize(cur)} / ${formatSize(total)}" }
                        },
                        cancelFlag = transferCancel,
                    )
                    if (!success) error("転送データを検証できませんでした")
                }
                runOnUiThread {
                    busy = false
                    directTransferActive = false
                    status = "Wi-Fi Direct 転送完了: ${payload.name}"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    directTransferActive = false
                    status = "Wi-Fi Direct 送信エラー: ${e.message}"
                }
            } finally {
                mainHandler.postDelayed({ removeP2pGroup() }, 5000)
            }
        }, "beam-direct-sender").start()
        status = "サーバー準備完了。NFC で接続情報を送ります..."
        onReady()
    }

    private fun beginP2pReceive(payload: BeamHandover.HandoverPayload) {
        if (!ensureP2p()) return
        val manager = p2pManager ?: return
        val channel = p2pChannel ?: return
        pendingSenderPayload = null
        p2pSendTransferStarted = false
        receivePayload = payload
        p2pConnecting = false
        p2pConnectAttempts = 0
        p2pReceiveTransferStarted = false
        directTransferActive = true
        transferCancel = AtomicBoolean(false)
        busy = true
        status = "Wi-Fi Direct の相手を探しています..."
        val attempt = transferCancel
        fun active() = !attempt.get() && transferCancel === attempt && directTransferActive
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { if (active()) discoverPeers(manager, channel) }
            override fun onFailure(reason: Int) = onSuccess()
        })
        mainHandler.postDelayed({ if (active() && !p2pConnecting && !p2pReceiveTransferStarted) tryConnectToOwner(direct = true) }, P2P_DIRECT_CONNECT_DELAY_MS)
        mainHandler.postDelayed({
            if (active() && !p2pReceiveTransferStarted) {
                busy = false
                directTransferActive = false
                p2pConnecting = false
                status = "Wi-Fi Direct の接続がタイムアウトしました"
                removeP2pGroup()
            }
        }, P2P_CONNECT_TIMEOUT_MS)
    }

    private fun discoverPeers(manager: WifiP2pManager, channel: WifiP2pManager.Channel) {
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                status = "Wi-Fi Direct の相手を探しています..."
            }
            override fun onFailure(reason: Int) {
                status = "Wi-Fi Direct の検索を開始できません ($reason)"
            }
        })
    }

    private fun tryConnectToOwner(direct: Boolean = false) {
        val payload = receivePayload ?: return
        val attempt = transferCancel
        if (!directTransferActive || attempt.get()) return
        if (p2pConnecting || p2pReceiveTransferStarted) return
        val manager = p2pManager ?: return
        val channel = p2pChannel ?: return
        manager.requestPeers(channel) { peers ->
            if (attempt.get() || transferCancel !== attempt || !directTransferActive || p2pConnecting || p2pReceiveTransferStarted) return@requestPeers
            val devices = peers.deviceList
            val byAddress = if (BeamHandover.isUsableP2pAddress(payload.ownerAddress)) {
                devices.firstOrNull { it.deviceAddress.equals(payload.ownerAddress, ignoreCase = true) }
            } else {
                null
            }
            val byName = if (byAddress == null && payload.ownerName.isNotBlank()) {
                devices.firstOrNull { it.deviceName == payload.ownerName }
            } else {
                null
            }
            val chosen = byAddress ?: byName ?: devices.singleOrNull()
            when {
                chosen != null -> connectToOwner(chosen.deviceAddress)
                direct && BeamHandover.isUsableP2pAddress(payload.ownerAddress) -> connectToOwner(payload.ownerAddress)
                direct -> status = "Wi-Fi Direct の相手を特定できません。端末名を確認して再試行してください"
            }
        }
    }

    private fun connectToOwner(address: String) {
        val attempt = transferCancel
        if (p2pConnecting || !directTransferActive || attempt.get()) return
        val manager = p2pManager ?: return
        val channel = p2pChannel ?: return
        p2pConnecting = true
        val config = WifiP2pConfig().apply {
            deviceAddress = address
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0
        }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (attempt.get() || transferCancel !== attempt) return
                p2pConnectAttempts = 0
                status = "Wi-Fi Direct に接続要求を送信しました。接続成立を待っています..."
            }

            override fun onFailure(reason: Int) {
                if (attempt.get() || transferCancel !== attempt) return
                p2pConnecting = false
                if (p2pConnectAttempts < P2P_CONNECT_RETRIES) {
                    p2pConnectAttempts++
                    status = "Wi-Fi Direct に再接続しています... ($reason)"
                    mainHandler.postDelayed({ if (!attempt.get() && transferCancel === attempt) connectToOwner(address) }, 2000)
                } else {
                    status = "Wi-Fi Direct に接続できません ($reason)。両端末で Beam を開き直してください"
                }
            }
        })
    }

    private fun requestP2pConnectionInfo() {
        val attempt = transferCancel
        if (!directTransferActive || attempt.get() || p2pReceiveTransferStarted || p2pSendTransferStarted) return
        val manager = p2pManager ?: return
        val channel = p2pChannel ?: return
        manager.requestConnectionInfo(channel) { info: WifiP2pInfo ->
            if (!directTransferActive || attempt.get() || transferCancel !== attempt || p2pReceiveTransferStarted || p2pSendTransferStarted) return@requestConnectionInfo
            if (!info.groupFormed) {
                if (p2pConnecting) status = "Wi-Fi Direct の接続成立を待っています..."
                return@requestConnectionInfo
            }
            when {
                receivePayload != null -> {
                    p2pReceiveTransferStarted = true
                    p2pConnecting = false
                    startReceiveTransfer(info)
                }
                pendingSenderPayload != null -> {
                    p2pSendTransferStarted = true
                    startSenderTransfer(info)
                }
            }
        }
    }

    private fun startSenderTransfer(info: WifiP2pInfo) {
        val attempt = transferCancel
        val payload = pendingSenderPayload ?: return
        val message = outgoing ?: return
        val ownerIp = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
        busy = true
        status = if (info.isGroupOwner) {
            "グループ成立（送信側GO）。受信を待っています..."
        } else {
            "グループ成立（送信側クライアント）。接続しています..."
        }
        Thread({
            val stream = runCatching {
                when {
                    message.extra.startsWith("content://") -> contentResolver.openInputStream(Uri.parse(message.extra))
                    payload.targetType == "dict" && message.extra.isNotEmpty() -> message.extra.byteInputStream(Charsets.UTF_8)
                    else -> null
                }
            }.getOrNull()
            if (stream == null) {
                runOnUiThread {
                    if (attempt.get() || transferCancel !== attempt) return@runOnUiThread
                    busy = false
                    directTransferActive = false
                    status = "送信データを開けません"
                    removeP2pGroup()
                }
                return@Thread
            }
            try {
                stream.use { input ->
                    val success = if (info.isGroupOwner) {
                        val server = BeamHandover.openServerInRange(ownerIp)
                        BeamHandover.sendStream(
                            server = server,
                            token = payload.token,
                            input = input,
                            totalBytes = payload.size,
                            onProgress = { cur, total ->
                                runOnUiThread { if (transferCancel === attempt && !attempt.get()) status = "送信中... ${formatSize(cur)} / ${formatSize(total)}" }
                            },
                            cancelFlag = attempt,
                        )
                    } else {
                        BeamHandover.sendStreamToHost(
                            hostAddress = ownerIp,
                            ports = BeamHandover.PORT_RANGE,
                            token = payload.token,
                            input = input,
                            totalBytes = payload.size,
                            onProgress = { cur, total ->
                                runOnUiThread { if (transferCancel === attempt && !attempt.get()) status = "送信中... ${formatSize(cur)} / ${formatSize(total)}" }
                            },
                            cancelFlag = attempt,
                        )
                    }
                    if (!success) error("転送データを検証できませんでした")
                }
                runOnUiThread {
                    if (attempt.get() || transferCancel !== attempt) return@runOnUiThread
                    busy = false
                    directTransferActive = false
                    status = "Wi-Fi Direct 転送完了: ${payload.name}"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (attempt.get() || transferCancel !== attempt) return@runOnUiThread
                    busy = false
                    directTransferActive = false
                    status = "Wi-Fi Direct 送信エラー: ${e.message}"
                }
            } finally {
                mainHandler.postDelayed({ if (transferCancel === attempt && !attempt.get()) removeP2pGroup() }, 5000)
            }
        }, "beam-direct-sender").start()
    }

    private fun startReceiveTransfer(info: WifiP2pInfo) {
        val attempt = transferCancel
        val payload = receivePayload ?: return
        val safeName = BeamHandover.sanitizeFileName(payload.name)
        val directory = File(filesDir, "beam").apply { mkdirs() }
        val file = File(directory, "${System.currentTimeMillis()}_$safeName")
        val ownerIp = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
        busy = true
        status = "受信中... ${formatSize(payload.size)}"
        Thread({
            try {
                file.outputStream().use { out ->
                    val success = if (info.isGroupOwner) {
                        val server = BeamHandover.openServerInRange(ownerIp)
                        BeamHandover.receiveStreamFromHost(
                            server = server,
                            token = payload.token,
                            output = out,
                            totalBytes = payload.size,
                            onProgress = { cur, total ->
                                runOnUiThread { if (transferCancel === attempt && !attempt.get()) status = "受信中... ${formatSize(cur)} / ${formatSize(total)}" }
                            },
                            cancelFlag = attempt,
                        )
                    } else {
                        BeamHandover.receiveStreamFromRange(
                            hostAddress = ownerIp,
                            ports = BeamHandover.PORT_RANGE,
                            token = payload.token,
                            output = out,
                            totalBytes = payload.size,
                            onProgress = { cur, total ->
                                runOnUiThread { if (transferCancel === attempt && !attempt.get()) status = "受信中... ${formatSize(cur)} / ${formatSize(total)}" }
                            },
                            cancelFlag = attempt,
                        )
                    }
                    if (!success) error("転送データを検証できませんでした")
                }
                runOnUiThread {
                    if (attempt.get() || transferCancel !== attempt) { file.delete(); return@runOnUiThread }
                    busy = false
                    directTransferActive = false
                    if (payload.targetType == "dict") {
                        if (file.length() > MAX_DICTIONARY_BYTES) {
                            file.delete()
                            status = "辞書が 5 MB を超えているため取り込めません"
                        } else {
                            val text = file.readText()
                            file.delete()
                            receivedFile = null
                            received = BeamMessage("dict", text, name = payload.name, mimeType = payload.mimeType, size = payload.size)
                            status = "辞書を受信しました。取り込む前に内容を確認してください。"
                        }
                    } else {
                        receivedFile = file
                        received = BeamMessage("file", file.absolutePath, name = payload.name, mimeType = payload.mimeType, size = payload.size)
                        status = "受信完了: ${payload.name} (${formatSize(payload.size)})"
                    }
                }
            } catch (e: Exception) {
                file.delete()
                runOnUiThread {
                    if (attempt.get() || transferCancel !== attempt) return@runOnUiThread
                    busy = false
                    directTransferActive = false
                    status = "Wi-Fi Direct 受信エラー: ${e.message}"
                }
            } finally {
                mainHandler.postDelayed({ if (transferCancel === attempt && !attempt.get()) removeP2pGroup() }, 2000)
            }
        }, "beam-direct-receiver").start()
    }

    private fun importDictionary(message: BeamMessage) {
        val result = runCatching { me.zssu.ime.mozc.UserDictionary(this).importTsv(message.value) }
        val entries = result.getOrNull()?.getOrNull()
        status = if (entries != null) {
            "ユーザー辞書を取り込みました（${entries.size}語）"
        } else {
            val detail = result.exceptionOrNull()?.message
                ?: result.getOrNull()?.exceptionOrNull()?.message
                ?: "TSV の形式を確認してください"
            "辞書の取り込みに失敗しました: $detail"
        }
    }

    private fun openReceivedFile(file: File, mimeType: String) {
        val type = mimeType.ifBlank { "application/octet-stream" }
        val uri = runCatching { FileProvider.getUriForFile(this, "$packageName.fileprovider", file) }.getOrNull()
        if (uri == null) {
            status = "ファイルを共有できません"
            return
        }
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(view) }.onFailure {
            val share = Intent(Intent.ACTION_SEND).setType(type)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { startActivity(Intent.createChooser(share, "ファイルを共有")) }
                .onFailure { error -> status = "開けるアプリがありません: ${error.message}" }
        }
    }

    private fun removeP2pGroup() {
        val manager = p2pManager ?: return
        val channel = p2pChannel ?: return
        runCatching {
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = Unit
                override fun onFailure(reason: Int) = Unit
            })
        }
    }

    private fun present(message: BeamMessage) {
        received = message
        receivedFile = null
        when (message.type) {
            "text" -> {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Meets Beam", message.value))
                status = "テキストを受信し、クリップボードにコピーしました。"
            }
            "url" -> status = "URL を受信しました: ${message.value}"
            "app" -> status = "アプリを受信しました: ${message.value}"
            "dict" -> status = "辞書パックを受信しました: ${message.name.ifEmpty { "追加辞書" }} (${message.value.lines().count { it.isNotBlank() }}語)"
            "handover" -> {
                val payload = runCatching { BeamHandover.HandoverPayload.fromJson(message.value) }.getOrNull()
                status = if (payload != null) "高速転送リクエストを受信しました: ${payload.name} (${formatSize(payload.size)})"
                else "データを受信しました: ${message.name}"
            }
            "file" -> status = "ファイルを受信しました: ${message.name}"
            else -> status = "データを受信しました。"
        }
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
            { detail -> status = detail; busy = false; receiver?.stop() }, { status = "NFC を検出しました。通信中は端末を近づけたままにしてください。" })
        receiver = next
        busy = next.start()
        status = if (busy) "受信待ちです。送信側に端末を近づけてください。" else "NFC を確認してください。"
    }

    override fun onResume() {
        super.onResume()
        if (receivingSelected && enabled && adapter?.isEnabled == true) startReceiver()
    }

    override fun onPause() {
        val wasSending = busy && !receivingSelected && !directTransferActive
        receiver?.stop(); NfcMigrationSession.clear()
        if (!directTransferActive) busy = false
        if (wasSending) status = "画面を離れたため送信を停止しました。もう一度送信側を選んでください。"
        adapter?.let { runCatching { CardEmulation.getInstance(it).unsetPreferredService(this@MeetsBeamActivity) } }
        super.onPause()
    }

    override fun onDestroy() {
        transferCancel.set(true)
        receivingSelected = false
        receiver?.stop()
        NfcMigrationSession.clear()
        p2pReceiver?.let { runCatching { unregisterReceiver(it) } }
        p2pReceiver = null
        removeP2pGroup()
        p2pManager = null
        p2pChannel = null
        super.onDestroy()
    }
}
