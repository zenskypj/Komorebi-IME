package me.zssu.ime.settings

import android.util.Base64
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * NFC での接続確立・認証後、大容量データ（画像・動画・音楽・辞書パック・移行データ）を
 * 端末間 Wi-Fi Direct（P2P）へハンドオフして高速ストリーミング転送する仕組み。
 *
 * クラウドや外部サーバーを介さず、端末間の直接ソケット通信と AES-GCM 暗号化で完結する。
 */
object BeamHandover {
    const val DEFAULT_PORT = 49152
    private const val BUFFER_SIZE = 65536
    private val random = SecureRandom()

    data class HandoverPayload(
        val targetType: String, // "file", "dict", "migration"
        val name: String,
        val size: Long,
        val mimeType: String,
        val ssid: String,
        val passphrase: String,
        val port: Int = DEFAULT_PORT,
        val token: String,
        val sha256: String = ""
    ) {
        fun toJson(): String = JSONObject()
            .put("targetType", targetType)
            .put("name", name)
            .put("size", size)
            .put("mimeType", mimeType)
            .put("ssid", ssid)
            .put("passphrase", passphrase)
            .put("port", port)
            .put("token", token)
            .put("sha256", sha256)
            .toString()

        companion object {
            fun fromJson(json: String): HandoverPayload {
                val obj = JSONObject(json)
                return HandoverPayload(
                    targetType = obj.getString("targetType"),
                    name = obj.getString("name"),
                    size = obj.getLong("size"),
                    mimeType = obj.optString("mimeType", "application/octet-stream"),
                    ssid = obj.getString("ssid"),
                    passphrase = obj.getString("passphrase"),
                    port = obj.optInt("port", DEFAULT_PORT),
                    token = obj.getString("token"),
                    sha256 = obj.optString("sha256", "")
                )
            }
        }
    }

    fun generateToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun deriveKey(token: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(digest, "AES")
    }

    /**
     * 送信側：ソケット経由でデータを AES-GCM 暗号化ストリーミング送信する。
     */
    fun sendStream(
        port: Int,
        token: String,
        input: InputStream,
        totalBytes: Long,
        onProgress: (bytesSent: Long, total: Long) -> Unit = { _, _ -> },
        cancelFlag: AtomicBoolean = AtomicBoolean(false)
    ): Boolean {
        val serverSocket = ServerSocket()
        serverSocket.reuseAddress = true
        serverSocket.bind(InetSocketAddress(port))
        serverSocket.soTimeout = 30000

        return try {
            val client = serverSocket.accept()
            client.soTimeout = 30000
            client.use { socket ->
                val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                val inStream = DataInputStream(BufferedInputStream(socket.getInputStream()))

                // 認証トークンの相互検証
                val clientNonce = ByteArray(12)
                inStream.readFully(clientNonce)
                val expectedProof = MessageDigest.getInstance("SHA-256")
                    .digest(token.toByteArray(Charsets.UTF_8) + clientNonce)
                val clientProof = ByteArray(32)
                inStream.readFully(clientProof)
                if (!expectedProof.contentEquals(clientProof)) {
                    out.writeByte(0xFF)
                    out.flush()
                    return false
                }
                out.writeByte(0x00)
                out.flush()

                // ストリーミング暗号化
                val buffer = ByteArray(BUFFER_SIZE)
                var sent = 0L
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val streamNonce = ByteArray(12).also(random::nextBytes)
                out.write(streamNonce)
                cipher.init(Cipher.ENCRYPT_MODE, deriveKey(token), GCMParameterSpec(128, streamNonce))

                while (sent < totalBytes && !cancelFlag.get()) {
                    val toRead = minOf(buffer.size.toLong(), totalBytes - sent).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read <= 0) break
                    val encryptedChunk = cipher.update(buffer, 0, read)
                    if (encryptedChunk != null && encryptedChunk.isNotEmpty()) {
                        out.writeInt(encryptedChunk.size)
                        out.write(encryptedChunk)
                    }
                    sent += read
                    onProgress(sent, totalBytes)
                }

                if (cancelFlag.get()) return false
                val finalBytes = cipher.doFinal()
                if (finalBytes != null && finalBytes.isNotEmpty()) {
                    out.writeInt(finalBytes.size)
                    out.write(finalBytes)
                }
                out.writeInt(-1)
                out.flush()
                true
            }
        } finally {
            runCatching { serverSocket.close() }
        }
    }

    /**
     * 受信側：送信側ソケットへ接続し、トークンを検証して復号しながらローカルストリームへ書き出す。
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
        val socket = Socket()
        socket.connect(InetSocketAddress(hostAddress, port), 15000)
        socket.soTimeout = 30000

        return socket.use { s ->
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            val inStream = DataInputStream(BufferedInputStream(s.getInputStream()))

            // 認証トークンの証明送信
            val clientNonce = ByteArray(12).also(random::nextBytes)
            out.write(clientNonce)
            val clientProof = MessageDigest.getInstance("SHA-256")
                .digest(token.toByteArray(Charsets.UTF_8) + clientNonce)
            out.write(clientProof)
            out.flush()

            val authStatus = inStream.readByte().toInt()
            if (authStatus != 0) return false

            val streamNonce = ByteArray(12)
            inStream.readFully(streamNonce)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(token), GCMParameterSpec(128, streamNonce))

            var received = 0L
            while (!cancelFlag.get()) {
                val chunkSize = inStream.readInt()
                if (chunkSize == -1) break
                if (chunkSize > BUFFER_SIZE * 2 || chunkSize <= 0) return false
                val chunk = ByteArray(chunkSize)
                inStream.readFully(chunk)
                val decrypted = cipher.update(chunk)
                if (decrypted != null && decrypted.isNotEmpty()) {
                    output.write(decrypted)
                    received += decrypted.size
                    onProgress(received, totalBytes)
                }
            }

            if (cancelFlag.get()) return false
            val finalBytes = cipher.doFinal()
            if (finalBytes != null && finalBytes.isNotEmpty()) {
                output.write(finalBytes)
                received += finalBytes.size
                onProgress(received, totalBytes)
            }
            output.flush()
            true
        }
    }
}
