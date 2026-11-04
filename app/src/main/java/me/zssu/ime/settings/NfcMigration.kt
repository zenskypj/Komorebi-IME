package me.zssu.ime.settings

import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.cardemulation.CardEmulation
import android.nfc.cardemulation.HostApduService
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zssu.ime.mozc.ProfileBackup
import me.zssu.ime.theme.ZinnaTheme
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Short-lived key and encrypted backup live in memory only while the sender screen is open. */
internal object NfcMigrationSession {
    val aid = byteArrayOf(0xF0.toByte(), 0x4B, 0x4F, 0x4D, 0x4F, 0x52, 0x45, 0x42, 0x49)
    const val MAX_ARCHIVE = 32 * 1024 * 1024
    private val ok = byteArrayOf(0x90.toByte(), 0)
    private val failure = byteArrayOf(0x6F, 0)
    private data class Ready(val archive: ByteArray, val password: String, val keyPair: KeyPair, val aid: ByteArray, val onDetected: (() -> Unit)?, val onComplete: (() -> Unit)?, var servedBytes: Int = 0, var detected: Boolean = false)
    @Volatile private var ready: Ready? = null
    private val random = SecureRandom()

    fun newPassword(): String = ByteArray(32).also(random::nextBytes).let { Base64.encodeToString(it, Base64.NO_WRAP) }
    fun prepare(archive: ByteArray, password: String, selectedAid: ByteArray = aid, onDetected: (() -> Unit)? = null, onComplete: (() -> Unit)? = null) {
        require(archive.size in 1..MAX_ARCHIVE)
        ready = Ready(archive, password, keys(), selectedAid.copyOf(), onDetected, onComplete)
    }
    fun clear() { ready = null }
    internal fun keys(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    internal fun selectCommand(selectedAid: ByteArray): ByteArray =
        byteArrayOf(0, 0xA4.toByte(), 4, 0, selectedAid.size.toByte()) + selectedAid + byteArrayOf(0)
    internal fun dataCommand(instruction: Int, data: ByteArray): ByteArray {
        require(data.size <= 255)
        return byteArrayOf(0x80.toByte(), instruction.toByte(), 0, 0, data.size.toByte()) + data + byteArrayOf(0)
    }

    class TransferFailure(val step: String, cause: Throwable) : java.io.IOException("$step: ${cause.message.orEmpty()}", cause)
    private fun shared(pair: KeyPair, publicBytes: ByteArray): ByteArray {
        val other = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicBytes))
        return MessageDigest.getInstance("SHA-256").digest(KeyAgreement.getInstance("ECDH").run {
            init(pair.private); doPhase(other, true); generateSecret()
        })
    }
    @Synchronized fun handle(command: ByteArray, selectedAid: ByteArray = aid): ByteArray = runCatching {
        val data = ready ?: return failure
        if (!data.aid.contentEquals(selectedAid)) return failure
        if (command.size >= 5 && command[0] == 0.toByte() && command[1] == 0xA4.toByte()) {
            val length = command[4].toInt() and 0xff
            if (command.size < 5 + length || !command.copyOfRange(5, 5 + length).contentEquals(selectedAid)) return failure
            if (!data.detected) { data.detected = true; data.onDetected?.invoke() }
            return ok
        }
        if (command.size < 5 || command[0] != 0x80.toByte()) return failure
        val length = command[4].toInt() and 0xff
        // Accept both ISO 7816 case 3 (no Le) and case 4 (Le=0) APDUs.
        if (command.size != 5 + length &&
            !(command.size == 6 + length && command.last() == 0.toByte())) return failure
        when (command[1].toInt() and 0xff) {
            0x10 -> {
                val clientPublic = command.copyOfRange(5, 5 + length)
                val nonce = ByteArray(12).also(random::nextBytes)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(shared(data.keyPair, clientPublic), "AES"), GCMParameterSpec(128, nonce))
                }
                val wrapped = cipher.doFinal(data.password.toByteArray(Charsets.UTF_8))
                val public = data.keyPair.public.encoded
                byteArrayOf(public.size.toByte()) + public + nonce + ByteBuffer.allocate(4).putInt(data.archive.size).array() + wrapped + ok
            }
            0x20 -> {
                if (length != 4) return failure
                val offset = ByteBuffer.wrap(command, 5, 4).int
                if (offset < 0 || offset >= data.archive.size) return failure
                val end = minOf(offset + 240, data.archive.size)
                if (offset <= data.servedBytes) data.servedBytes = maxOf(data.servedBytes, end)
                data.archive.copyOfRange(offset, end) + ok
            }
            0x30 -> {
                if (length != 0 || data.servedBytes != data.archive.size) return failure
                ready = null
                data.onComplete?.invoke()
                ok
            }
            else -> failure
        }
    }.getOrElse { failure }

    fun receive(tag: Tag, selectedAid: ByteArray = aid, verify: ((ByteArray, CharArray) -> Unit)? = null,
        preparedKeys: KeyPair? = null, report: (Int, Int) -> Unit): Pair<ByteArray, CharArray> {
        val iso = IsoDep.get(tag) ?: error("相手が NFC カードエミュレーションに対応していません")
        val keyPair = preparedKeys ?: keys()
        var step = "接続"
        try {
        iso.use {
            iso.connect()
            iso.timeout = 20000
            fun exchange(cmd: ByteArray): ByteArray {
                val result = iso.transceive(cmd)
                check(result.size >= 2 && result[result.size - 2] == 0x90.toByte() && result.last() == 0.toByte()) { "NFC 接続が切れました" }
                return result.copyOf(result.size - 2)
            }
            step = "接続先の選択"
            exchange(selectCommand(selectedAid))
            step = "鍵交換"
            val public = keyPair.public.encoded
            val response = exchange(dataCommand(0x10, public))
            check(response.isNotEmpty())
            val size = response[0].toInt() and 0xff
            check(size in 1..128 && response.size > 1 + size + 16)
            val peer = response.copyOfRange(1, 1 + size)
            val nonce = response.copyOfRange(1 + size, 1 + size + 12)
            val archiveSize = ByteBuffer.wrap(response, 1 + size + 12, 4).int
            check(archiveSize in 1..MAX_ARCHIVE) { "移行データが大きすぎます" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(shared(keyPair, peer), "AES"), GCMParameterSpec(128, nonce))
            }
            val password = cipher.doFinal(response.copyOfRange(1 + size + 16, response.size)).toString(Charsets.UTF_8).toCharArray()
            val out = ByteArrayOutputStream(archiveSize)
            step = "データ受信"
            while (out.size() < archiveSize) {
                val offset = out.size()
                val chunk = exchange(dataCommand(0x20, ByteBuffer.allocate(4).putInt(offset).array()))
                check(chunk.isNotEmpty() && offset + chunk.size <= archiveSize)
                out.write(chunk)
                if (offset % 24000 == 0 || out.size() == archiveSize) report(out.size(), archiveSize)
            }
            val bytes = out.toByteArray()
            if (verify != null) {
                try { verify(bytes, password) } catch (e: Exception) { password.fill('\u0000'); throw e }
                // A lost acknowledgement must not discard data already verified by the receiver.
                runCatching { exchange(byteArrayOf(0x80.toByte(), 0x30, 0, 0, 0)) }
            }
            return bytes to password
        }
        } catch (e: Exception) { throw TransferFailure(step, e) }
    }
}

class NfcMigrationHostService : HostApduService() {
    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray =
        commandApdu?.let(NfcMigrationSession::handle) ?: byteArrayOf(0x6F, 0)
    override fun onDeactivated(reason: Int) = Unit
}

/**
 * While the migration screen is in the foreground, receive exactly one encrypted backup.
 *
 * Mirrors [BeamReceiver]: reader mode plus a foreground-dispatch fallback for vendor NFC stacks,
 * the ephemeral key generated before the phones touch, and the transfer kept off the NFC callback
 * thread. It also supplies the verification callback, which is what makes the receiver send the
 * final acknowledgement the sender waits for.
 */
internal class NfcMigrationReceiver(
    private val activity: ComponentActivity,
    private val onProgress: (Int) -> Unit,
    private val onRestored: (ProfileBackup.Result) -> Unit,
    private val onError: (String) -> Unit,
    private val onDetected: () -> Unit,
) {
    private val adapter = NfcAdapter.getDefaultAdapter(activity)
    private val working = AtomicBoolean(false)
    @Volatile private var active = false
    private var preparedKeys: KeyPair? = null

    fun start(): Boolean {
        if (adapter?.isEnabled != true) return false
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
            // Some vendor NFC stacks fall back to Android's ordinary tag intent. Route it to the
            // already-open migration screen as well, without replacing reader mode.
            val intent = Intent(activity, NfcMigrationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getActivity(activity, 0x4d49, intent, flags)
            runCatching { adapter.enableForegroundDispatch(activity, pending, null, null) }
            true
        }.getOrElse { active = false; false }
    }

    fun dispatchTag(tag: Tag) {
        if (!active || !working.compareAndSet(false, true)) return
        activity.runOnUiThread { if (active) onDetected() }
        Thread({
            try {
                var restored: ProfileBackup.Result? = null
                val (_, password) = NfcMigrationSession.receive(tag,
                    verify = { bytes, secret -> restored = ProfileBackup.stageRestore(activity, bytes, secret) },
                    preparedKeys = requireNotNull(preparedKeys)) { done, total ->
                    if (!active) throw java.io.IOException("cancelled")
                    onProgress((done * 100L / total).toInt())
                }
                password.fill('\u0000')
                val result = requireNotNull(restored)
                activity.runOnUiThread { if (active) { stop(); onRestored(result) } }
            } catch (e: Exception) {
                val detail = when {
                    e is NfcMigrationSession.TransferFailure && e.cause is android.nfc.TagLostException -> "${e.step}中に NFC 接続が切れました。端末を近づけたまま試してください。"
                    e is NfcMigrationSession.TransferFailure -> "${e.step}で NFC 通信に失敗しました。端末を近づけ直してください。"
                    e is android.nfc.TagLostException -> "NFC 接続が切れました。端末を近づけたまま試してください。"
                    e is java.io.IOException -> "NFC 通信に失敗しました。端末を近づけ直してください。"
                    else -> "受信データを復元できませんでした。"
                }
                activity.runOnUiThread { if (active) onError(detail) }
            } finally { working.set(false) }
        }, "nfc-migration-reader").apply { isDaemon = true; start() }
    }

    fun stop() {
        active = false
        preparedKeys = null
        runCatching { adapter?.disableReaderMode(activity) }
        runCatching { adapter?.disableForegroundDispatch(activity) }
    }
}

class NfcMigrationActivity : ComponentActivity() {
    private var status by mutableStateOf("両端末でこの画面を開き、送信側と受信側を選んでください。")
    private var busy by mutableStateOf(false)
    private var sending by mutableStateOf(false)
    private var receiving by mutableStateOf(false)
    private var restoreReady by mutableStateOf(false)
    private val adapter by lazy { NfcAdapter.getDefaultAdapter(this) }
    private val generation = AtomicInteger()
    private var receiver: NfcMigrationReceiver? = null
    private var pendingTag: Tag? = null

    private fun stopSending() {
        adapter?.let { runCatching { CardEmulation.getInstance(it).unsetPreferredService(this) } }
    }

    private fun startReceiver() {
        receiver?.stop()
        val next = NfcMigrationReceiver(
            this,
            onProgress = { percent -> runOnUiThread { status = "受信中 $percent%" } },
            onRestored = { result ->
                adapter?.disableReaderMode(this)
                runCatching { adapter?.disableForegroundDispatch(this) }
                busy = false; receiving = false
                restoreReady = result == ProfileBackup.Result.Ok
                status = if (restoreReady) "受信しました。アプリを終了して開き直すと反映されます。" else "受信データを復元できませんでした。"
            },
            onError = { detail -> busy = false; status = detail },
            onDetected = { status = "NFC を検出しました。通信中は端末を近づけたままにしてください。" },
        )
        receiver = next
        receiving = true
        busy = next.start()
        status = if (busy) "受信待ちです。送信側に端末を近づけてください。" else "NFC を確認してください。"
        pendingTag?.let { pendingTag = null; next.dispatchTag(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ZinnaTheme { Surface(Modifier.fillMaxSize()) {
            val scope = rememberCoroutineScope()
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("NFC で設定・学習を移行", style = MaterialTheme.typography.headlineSmall)
                Text("Mozc の学習履歴・ユーザー辞書・設定を送ります。受信側の同じ項目は上書きされます。zenzai-plus の TTT 状態とモデルは対象外です。")
                Text("両方の端末で NFC をオンにし、画面を開いたまま背面を近づけ続けてください。相手の本人確認は行わないため、自分の端末同士で操作してください。データ量によって時間がかかります。")
                Text(status)
                if (adapter == null) Text("この端末では NFC を利用できません。")
                else if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) Text("この端末では NFC 受信はできますが、送信に必要なカードエミュレーションは利用できません。")
                if (adapter != null && adapter?.isEnabled != true) Button(onClick = { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }) { Text("NFC をオンにする") }
                Button(enabled = !busy && adapter?.isEnabled == true && packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_NFC_HOST_CARD_EMULATION), onClick = {
                    val ticket = generation.incrementAndGet()
                    busy = true; sending = true; status = "送信データを暗号化しています…"
                    scope.launch {
                        val password = NfcMigrationSession.newPassword()
                        val archive = withContext(Dispatchers.IO) { ProfileBackup.export(this@NfcMigrationActivity, password.toCharArray(), BACKUP_EXTRAS) }
                        if (ticket != generation.get()) return@launch
                        if (archive == null || archive.size > NfcMigrationSession.MAX_ARCHIVE) {
                            status = "データを準備できません。32 MiB 以下のデータでお試しください。"; busy = false; sending = false
                        } else {
                            runCatching {
                                NfcMigrationSession.prepare(archive, password,
                                    onDetected = { runOnUiThread { if (generation.get() == ticket) status = "受信側を検出しました。端末を近づけたままにしてください。" } },
                                    onComplete = { runOnUiThread {
                                        if (generation.get() != ticket) return@runOnUiThread
                                        stopSending(); busy = false; sending = false
                                        status = "送信しました。受信側で「終了して反映」を押してください。"
                                    } })
                                // Route the migration AID to this service while the screen is open;
                                // Meets Beam sets the same preference for its own service.
                                adapter?.let { CardEmulation.getInstance(it).setPreferredService(this@NfcMigrationActivity, ComponentName(this@NfcMigrationActivity, NfcMigrationHostService::class.java)) }
                                status = "送信待ちです。受信側を起動して端末同士を近づけてください。"
                            }.onFailure { stopSending(); busy = false; sending = false; status = "送信を開始できませんでした。もう一度お試しください。" }
                        }
                    }
                }) { Text("送信する（設定と学習）") }
                Button(enabled = !busy && adapter?.isEnabled == true, onClick = { startReceiver() }) { Text("受信する（設定と学習）") }
                if (busy) OutlinedButton(onClick = {
                    generation.incrementAndGet(); receiver?.stop(); NfcMigrationSession.clear(); stopSending()
                    busy = false; sending = false; receiving = false; status = "中止しました。"
                }) { Text("中止") }
                if (!busy && receiving) OutlinedButton(onClick = { startReceiver() }) { Text("受信を再試行") }
                if (restoreReady) OutlinedButton(onClick = { finishAffinity(); kotlin.system.exitProcess(0) }) { Text("終了して反映") }
                OutlinedButton(onClick = { finish() }) { Text("戻る") }
            }
        } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        if (receiving && tag != null) {
            if (busy) receiver?.dispatchTag(tag) else pendingTag = tag
        }
    }

    override fun onResume() {
        super.onResume()
        if (receiving && adapter?.isEnabled == true) startReceiver()
    }

    override fun onPause() {
        receiver?.stop()
        NfcMigrationSession.clear()
        stopSending()
        if (sending) { generation.incrementAndGet(); status = "画面を離れたため送信を停止しました。もう一度送信側を選んでください。" }
        busy = false; sending = false
        super.onPause()
    }

    override fun onDestroy() {
        receiving = false
        receiver?.stop()
        generation.incrementAndGet()
        NfcMigrationSession.clear()
        stopSending()
        super.onDestroy()
    }
}
