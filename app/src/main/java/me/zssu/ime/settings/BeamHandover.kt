package me.zssu.ime.settings

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * NFC で接続情報と一時トークンを交換したあと、Wi-Fi Direct のグループ内で
 * 大容量データをストリーミング転送する。
 *
 * 平文は AES-GCM で暗号化し、総バイト数と末尾の SHA-256 を照合する。
 * 送信側は Wi-Fi Direct のグループオーナーになり、受信側はグループオーナーの
 * アドレスへだけ接続する。クラウドや外部サーバーは使わない。
 */
object BeamHandover {
    const val DEFAULT_PORT = 49152
    const val MAX_TRANSFER_BYTES = 2L * 1024 * 1024 * 1024
    private const val BUFFER_SIZE = 65536
    private const val MAX_NAME_LENGTH = 100
    private const val CONNECT_ATTEMPTS = 10
    private val random = SecureRandom()
    private val macPattern = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
    private val ipv4Pattern = Regex("(\\d{1,3}\\.){3}\\d{1,3}")

    data class HandoverPayload(
        val targetType: String,
        val name: String,
        val size: Long,
        val mimeType: String,
        val ownerAddress: String,
        val ownerIp: String,
        val port: Int = DEFAULT_PORT,
        val token: String
    ) {
        fun toJson(): String = JSONObject()
            .put("targetType", targetType)
            .put("name", name)
            .put("size", size)
            .put("mimeType", mimeType)
            .put("ownerAddress", ownerAddress)
            .put("ownerIp", ownerIp)
            .put("port", port)
            .put("token", token)
            .toString()

        companion object {
            fun fromJson(json: String): HandoverPayload {
                val obj = JSONObject(json)
                val targetType = obj.getString("targetType")
                require(targetType == "file" || targetType == "dict")
                val name = obj.getString("name")
                val size = obj.getLong("size")
                val mimeType = obj.optString("mimeType", "application/octet-stream")
                val ownerAddress = obj.getString("ownerAddress")
                val ownerIp = obj.getString("ownerIp")
                val port = obj.optInt("port", DEFAULT_PORT)
                val token = obj.getString("token")
                require(name.isNotBlank() && name.length <= 200)
                require(size in 1..MAX_TRANSFER_BYTES)
                require(mimeType.isNotBlank() && mimeType.length <= 120)
                require(macPattern.matches(ownerAddress)) { "送信側の Wi-Fi Direct アドレスが不正です" }
                require(isIPv4(ownerIp)) { "送信側の IP アドレスが不正です" }
                require(port in 1..65535)
                require(token.length in 16..128)
                return HandoverPayload(targetType, name, size, mimeType, ownerAddress, ownerIp, port, token)
            }

            private fun isIPv4(value: String): Boolean =
                ipv4Pattern.matches(value) && value.split('.').all { it.toIntOrNull() in 0..255 }
        }
    }

    fun generateToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Keeps a remote display name from escaping the receive directory. */
    fun sanitizeFileName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").trim('.', ' ')
        val limited = cleaned.take(MAX_NAME_LENGTH).ifBlank { "beam_file" }
        return if (limited == "." || limited == "..") "beam_file" else limited
    }

    private fun deriveKey(token: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(digest, "AES")
    }

    /**
     * 送信側。Wi-Fi Direct のグループオーナーアドレスにだけバインドし、
     * 1 クライアントへ暗号化ストリームを送る。
     */
    fun sendStream(
        bindAddress: String,
        port: Int,
        token: String,
        input: InputStream,
        totalBytes: Long,
        onProgress: (bytesSent: Long, total: Long) -> Unit = { _, _ -> },
        cancelFlag: AtomicBoolean = AtomicBoolean(false)
    ): Boolean {
        require(totalBytes in 1..MAX_TRANSFER_BYTES) { "転送サイズが範囲外です" }
        require(bindAddress.isNotBlank()) { "Wi-Fi Direct のアドレスがありません" }
        val serverSocket = ServerSocket()
        serverSocket.reuseAddress = true
        serverSocket.bind(InetSocketAddress(InetAddress.getByName(bindAddress), port))
        serverSocket.soTimeout = 30000

        return try {
            val client = serverSocket.accept()
            client.soTimeout = 30000
            client.use { socket ->
                val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                val inStream = DataInputStream(BufferedInputStream(socket.getInputStream()))

                val clientNonce = ByteArray(12)
                inStream.readFully(clientNonce)
                val expectedProof = MessageDigest.getInstance("SHA-256")
                    .digest(token.toByteArray(Charsets.UTF_8) + clientNonce)
                val clientProof = ByteArray(32)
                inStream.readFully(clientProof)
                if (!MessageDigest.isEqual(expectedProof, clientProof)) {
                    out.writeByte(0xFF)
                    out.flush()
                    return false
                }
                out.writeByte(0x00)
                out.flush()

                val buffer = ByteArray(BUFFER_SIZE)
                val digest = MessageDigest.getInstance("SHA-256")
                var sent = 0L
                var zeroReads = 0
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val streamNonce = ByteArray(12).also(random::nextBytes)
                out.write(streamNonce)
                cipher.init(Cipher.ENCRYPT_MODE, deriveKey(token), GCMParameterSpec(128, streamNonce))

                while (sent < totalBytes && !cancelFlag.get()) {
                    val toRead = minOf(buffer.size.toLong(), totalBytes - sent).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read < 0) return false
                    if (read == 0) {
                        if (++zeroReads > 3) return false
                        continue
                    }
                    zeroReads = 0
                    digest.update(buffer, 0, read)
                    val encryptedChunk = cipher.update(buffer, 0, read)
                    if (encryptedChunk != null && encryptedChunk.isNotEmpty()) {
                        out.writeInt(encryptedChunk.size)
                        out.write(encryptedChunk)
                    }
                    sent += read
                    onProgress(sent, totalBytes)
                }

                if (cancelFlag.get() || sent != totalBytes) return false
                val finalBytes = cipher.doFinal()
                if (finalBytes != null && finalBytes.isNotEmpty()) {
                    out.writeInt(finalBytes.size)
                    out.write(finalBytes)
                }
                out.writeInt(-1)
                out.write(digest.digest())
                out.flush()
                true
            }
        } finally {
            runCatching { serverSocket.close() }
        }
    }

    /**
     * 受信側。グループオーナーの IP へだけ接続し、宣言サイズと SHA-256 を
     * 検証してから呼び出し元のストリームへ書き出す。
     */
    fun receiveStream(
        hostAddress: String,
        port: Int,
        token: String,
        output: OutputStream,
        totalBytes: Long,
        onProgress: (bytesReceived: Long, total: Long) -> Unit = { _, _ -> },
        cancelFlag: AtomicBoolean = AtomicBoolean(false)
    ): Boolean {
        require(totalBytes in 1..MAX_TRANSFER_BYTES) { "転送サイズが範囲外です" }
        require(hostAddress.isNotBlank()) { "Wi-Fi Direct のアドレスがありません" }

        var socket: Socket? = null
        for (attempt in 0 until CONNECT_ATTEMPTS) {
            try {
                socket = Socket().apply {
                    connect(InetSocketAddress(hostAddress, port), 3000)
                    soTimeout = 30000
                }
                break
            } catch (error: IOException) {
                runCatching { socket?.close() }
                socket = null
                if (attempt == CONNECT_ATTEMPTS - 1) return false
                Thread.sleep(500)
            }
        }
        val connected = socket ?: return false

        return try {
            connected.use { s ->
                val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                val inStream = DataInputStream(BufferedInputStream(s.getInputStream()))

                val clientNonce = ByteArray(12).also(random::nextBytes)
                out.write(clientNonce)
                val clientProof = MessageDigest.getInstance("SHA-256")
                    .digest(token.toByteArray(Charsets.UTF_8) + clientNonce)
                out.write(clientProof)
                out.flush()

                if (inStream.readByte().toInt() != 0) return false

                val streamNonce = ByteArray(12)
                inStream.readFully(streamNonce)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, deriveKey(token), GCMParameterSpec(128, streamNonce))
                val digest = MessageDigest.getInstance("SHA-256")

                var received = 0L
                while (!cancelFlag.get()) {
                    val chunkSize = inStream.readInt()
                    if (chunkSize == -1) break
                    if (chunkSize <= 0 || chunkSize > BUFFER_SIZE * 2) return false
                    val chunk = ByteArray(chunkSize)
                    inStream.readFully(chunk)
                    val decrypted = cipher.update(chunk)
                    if (decrypted != null && decrypted.isNotEmpty()) {
                        if (received + decrypted.size > totalBytes) return false
                        digest.update(decrypted)
                        output.write(decrypted)
                        received += decrypted.size
                        onProgress(received, totalBytes)
                    }
                }

                if (cancelFlag.get()) return false
                val finalBytes = cipher.doFinal()
                if (finalBytes != null && finalBytes.isNotEmpty()) {
                    if (received + finalBytes.size > totalBytes) return false
                    digest.update(finalBytes)
                    output.write(finalBytes)
                    received += finalBytes.size
                    onProgress(received, totalBytes)
                }
                val expectedHash = ByteArray(32)
                inStream.readFully(expectedHash)
                if (received != totalBytes ||
                    !MessageDigest.isEqual(digest.digest(), expectedHash)
                ) {
                    return false
                }
                output.flush()
                true
            }
        } catch (error: IOException) {
            false
        } catch (error: GeneralSecurityException) {
            false
        } finally {
            runCatching { connected.close() }
        }
    }
}
