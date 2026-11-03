package me.zssu.ime.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.cardemulation.HostApduService
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
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
    private data class Ready(val archive: ByteArray, val password: String, val keyPair: KeyPair)
    @Volatile private var ready: Ready? = null
    private val random = SecureRandom()

    fun newPassword(): String = ByteArray(32).also(random::nextBytes).let { Base64.encodeToString(it, Base64.NO_WRAP) }
    fun prepare(archive: ByteArray, password: String) {
        require(archive.size in 1..MAX_ARCHIVE)
        ready = Ready(archive, password, keys())
    }
    fun clear() { ready = null }
    private fun keys(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private fun shared(pair: KeyPair, publicBytes: ByteArray): ByteArray {
        val other = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicBytes))
        return MessageDigest.getInstance("SHA-256").digest(KeyAgreement.getInstance("ECDH").run {
            init(pair.private); doPhase(other, true); generateSecret()
        })
    }
    fun handle(command: ByteArray): ByteArray = runCatching {
        val data = ready ?: return failure
        if (command.size >= 5 && command[0] == 0.toByte() && command[1] == 0xA4.toByte()) {
            val length = command[4].toInt() and 0xff
            if (command.size < 5 + length || !command.copyOfRange(5, 5 + length).contentEquals(aid)) return failure
            return ok
        }
        if (command.size < 5 || command[0] != 0x80.toByte()) return failure
        val length = command[4].toInt() and 0xff
        if (command.size != 5 + length) return failure
        when (command[1].toInt() and 0xff) {
            0x10 -> {
                val clientPublic = command.copyOfRange(5, command.size)
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
                data.archive.copyOfRange(offset, minOf(offset + 240, data.archive.size)) + ok
            }
            else -> failure
        }
    }.getOrElse { failure }

    fun receive(tag: Tag, report: (Int, Int) -> Unit): Pair<ByteArray, CharArray> {
        val iso = IsoDep.get(tag) ?: error("相手が NFC カードエミュレーションに対応していません")
        iso.use {
            iso.timeout = 20000
            iso.connect()
            fun exchange(cmd: ByteArray): ByteArray {
                val result = iso.transceive(cmd)
                check(result.size >= 2 && result[result.size - 2] == 0x90.toByte() && result.last() == 0.toByte()) { "NFC 接続が切れました" }
                return result.copyOf(result.size - 2)
            }
            exchange(byteArrayOf(0, 0xA4.toByte(), 4, 0, aid.size.toByte()) + aid)
            val keyPair = keys()
            val public = keyPair.public.encoded
            val response = exchange(byteArrayOf(0x80.toByte(), 0x10, 0, 0, public.size.toByte()) + public)
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
            while (out.size() < archiveSize) {
                val offset = out.size()
                val chunk = exchange(byteArrayOf(0x80.toByte(), 0x20, 0, 0, 4) + ByteBuffer.allocate(4).putInt(offset).array())
                check(chunk.isNotEmpty() && offset + chunk.size <= archiveSize)
                out.write(chunk)
                if (offset % 24000 == 0 || out.size() == archiveSize) report(out.size(), archiveSize)
            }
            return out.toByteArray() to password
        }
    }
}

class NfcMigrationHostService : HostApduService() {
    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray =
        commandApdu?.let(NfcMigrationSession::handle) ?: byteArrayOf(0x6F, 0)
    override fun onDeactivated(reason: Int) = Unit
}

class NfcMigrationActivity : ComponentActivity() {
    private var status by mutableStateOf("両端末でこの画面を開き、送信側と受信側を選んでください。")
    private var busy by mutableStateOf(false)
    private var restoreReady by mutableStateOf(false)
    private val adapter by lazy { NfcAdapter.getDefaultAdapter(this) }
    private val generation = AtomicInteger()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ZinnaTheme { Surface(Modifier.fillMaxSize()) {
            val scope = rememberCoroutineScope()
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("NFC で設定・学習を移行", style = MaterialTheme.typography.headlineSmall)
                Text("Mozc の学習履歴・ユーザー辞書・設定を送ります。受信側の同じ項目は上書きされます。zenzai-plus の TTT 状態とモデルは対象外です。")
                Text("両方の端末で NFC をオンにし、画面を開いたまま背面を近づけ続けてください。相手の本人確認は行わないため、自分の端末同士で操作してください。データ量によって時間がかかります。")
                Text(status)
                if (adapter == null) Text("この端末では NFC を利用できません。")
                else if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) Text("この端末では NFC 受信はできますが、送信に必要なカードエミュレーションは利用できません。")
                if (adapter != null && adapter?.isEnabled != true) Button(onClick = { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }) { Text("NFC をオンにする") }
                Button(enabled = !busy && adapter?.isEnabled == true && packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_NFC_HOST_CARD_EMULATION), onClick = {
                    val ticket = generation.incrementAndGet()
                    busy = true; status = "送信データを暗号化しています…"
                    scope.launch {
                        val password = NfcMigrationSession.newPassword()
                        val archive = withContext(Dispatchers.IO) { ProfileBackup.export(this@NfcMigrationActivity, password.toCharArray(), BACKUP_EXTRAS) }
                        if (ticket != generation.get()) return@launch
                        if (archive == null || archive.size > NfcMigrationSession.MAX_ARCHIVE) {
                            status = "データを準備できません。32 MiB 以下のデータでお試しください。"; busy = false
                        } else {
                            NfcMigrationSession.prepare(archive, password)
                            status = "送信待ちです。受信側を起動して端末同士を近づけてください。"
                        }
                    }
                }) { Text("送信する（設定と学習）") }
                Button(enabled = !busy && adapter?.isEnabled == true, onClick = {
                    val ticket = generation.incrementAndGet()
                    busy = true; status = "受信待ちです。送信側に端末を近づけてください。"
                    adapter?.enableReaderMode(this@NfcMigrationActivity, { tag ->
                        try {
                            val (archive, password) = NfcMigrationSession.receive(tag) { done, total ->
                                if (generation.get() != ticket) throw java.io.IOException("cancelled")
                                runOnUiThread { if (generation.get() == ticket) status = "受信中 ${done * 100L / total}%" }
                            }
                            check(generation.get() == ticket)
                            val result = try { ProfileBackup.stageRestore(this@NfcMigrationActivity, archive, password) } finally { password.fill('\u0000') }
                            runOnUiThread {
                                if (generation.get() != ticket) return@runOnUiThread
                                adapter?.disableReaderMode(this@NfcMigrationActivity)
                                busy = false
                                restoreReady = result == ProfileBackup.Result.Ok
                                status = if (restoreReady) "受信しました。アプリを終了して開き直すと反映されます。" else "受信データを復元できませんでした。"
                            }
                        } catch (_: Exception) {
                            runOnUiThread { if (generation.get() == ticket) { busy = false; adapter?.disableReaderMode(this@NfcMigrationActivity); status = "受信に失敗しました。端末を離さず、もう一度お試しください。" } }
                        }
                    }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
                }) { Text("受信する（設定と学習）") }
                if (busy) OutlinedButton(onClick = {
                    generation.incrementAndGet(); adapter?.disableReaderMode(this@NfcMigrationActivity); NfcMigrationSession.clear(); busy = false; status = "中止しました。"
                }) { Text("中止") }
                if (restoreReady) OutlinedButton(onClick = { finishAffinity(); kotlin.system.exitProcess(0) }) { Text("終了して反映") }
                OutlinedButton(onClick = { finish() }) { Text("戻る") }
            }
        } } }
    }
    override fun onDestroy() { generation.incrementAndGet(); adapter?.disableReaderMode(this); NfcMigrationSession.clear(); super.onDestroy() }
}
