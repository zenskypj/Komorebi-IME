package me.zssu.ime.settings

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class NfcMigrationSessionTest {
    @Test fun encryptedHandshakeAndChunkedTransfer() {
        val archive = ByteArray(601) { it.toByte() }
        NfcMigrationSession.prepare(archive, "test-secret")
        try {
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val client = pair.public.encoded
            val result = NfcMigrationSession.handle(byteArrayOf(0x80.toByte(), 0x10, 0, 0, client.size.toByte()) + client)
            assertEquals(0x90.toByte(), result[result.size - 2])
            val length = result[0].toInt() and 255
            val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(result.copyOfRange(1, 1 + length)))
            val secret = KeyAgreement.getInstance("ECDH").run { init(pair.private); doPhase(peer, true); generateSecret() }
            val key = MessageDigest.getInstance("SHA-256").digest(secret)
            val nonce = result.copyOfRange(1 + length, 13 + length)
            assertEquals(archive.size, ByteBuffer.wrap(result, 13 + length, 4).int)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }
            assertEquals("test-secret", cipher.doFinal(result.copyOfRange(17 + length, result.size - 2)).toString(Charsets.UTF_8))
            val chunks = (0 until archive.size step 240).flatMap { offset ->
                val response = NfcMigrationSession.handle(byteArrayOf(0x80.toByte(), 0x20, 0, 0, 4) + ByteBuffer.allocate(4).putInt(offset).array())
                assertEquals(0x90.toByte(), response[response.size - 2])
                response.dropLast(2)
            }.toByteArray()
            assertArrayEquals(archive, chunks)
            assertEquals(0x6F.toByte(), NfcMigrationSession.handle(byteArrayOf(0x80.toByte(), 0x20, 0, 0, 4) + ByteBuffer.allocate(4).putInt(archive.size).array())[0])
        } finally { NfcMigrationSession.clear() }
    }
}
