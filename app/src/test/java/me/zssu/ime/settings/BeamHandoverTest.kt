package me.zssu.ime.settings

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BeamHandoverTest {

    @Test
    fun handoverPayloadJsonRoundtrip() {
        val original = BeamHandover.HandoverPayload(
            targetType = "dict",
            name = "櫻坂46辞書パック.kdict",
            size = 1048576L,
            mimeType = "text/tab-separated-values",
            ssid = "DIRECT-Komorebi-Test",
            passphrase = "test-passphrase-1234",
            port = 49152,
            token = "secret-token-xyz",
            sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        )
        val json = original.toJson()
        val parsed = BeamHandover.HandoverPayload.fromJson(json)

        assertEquals(original.targetType, parsed.targetType)
        assertEquals(original.name, parsed.name)
        assertEquals(original.size, parsed.size)
        assertEquals(original.mimeType, parsed.mimeType)
        assertEquals(original.ssid, parsed.ssid)
        assertEquals(original.passphrase, parsed.passphrase)
        assertEquals(original.port, parsed.port)
        assertEquals(original.token, parsed.token)
        assertEquals(original.sha256, parsed.sha256)
    }

    @Test
    fun beamTransportSealsAndOpensExtendedTypes() {
        val password = "test-password".toCharArray()
        val types = listOf(
            BeamMessage("text", "こんにちは"),
            BeamMessage("url", "https://example.com/test"),
            BeamMessage("app", "me.zssu.ime"),
            BeamMessage("dict", "とうきょう\t東京\t名詞\t首都", name = "追加辞書", size = 20L),
            BeamMessage("file", "base64-or-path", name = "photo.jpg", mimeType = "image/jpeg", size = 1024L),
            BeamMessage("handover", "{\"test\":true}", name = "backup.tar", size = 5000000L)
        )

        for (msg in types) {
            val sealed = BeamTransport.seal(msg, password)
            val opened = BeamTransport.open(sealed, password)
            assertEquals(msg.type, opened.type)
            assertEquals(msg.value, opened.value)
            assertEquals(msg.name, opened.name)
            assertEquals(msg.mimeType, opened.mimeType)
            assertEquals(msg.size, opened.size)
        }
    }

    @Test
    fun beamHandoverStreamSendAndReceiveOverLoopback() {
        // Find an available ephemeral port
        val port = ServerSocket(0).use { it.localPort }
        val token = BeamHandover.generateToken()
        val testData = ByteArray(128 * 1024) { (it % 251).toByte() }

        val serverLatch = CountDownLatch(1)
        val clientLatch = CountDownLatch(1)
        var sendSuccess = false
        var receiveSuccess = false
        val receivedOutput = ByteArrayOutputStream()

        val serverThread = Thread {
            serverLatch.countDown()
            val input = ByteArrayInputStream(testData)
            sendSuccess = BeamHandover.sendStream(port, token, input, testData.size.toLong())
        }
        serverThread.start()

        assertTrue("Server failed to bind", serverLatch.await(5, TimeUnit.SECONDS))
        Thread.sleep(100) // Give serverSocket.accept a moment

        val clientThread = Thread {
            receiveSuccess = BeamHandover.receiveStream("127.0.0.1", port, token, receivedOutput, testData.size.toLong())
            clientLatch.countDown()
        }
        clientThread.start()

        assertTrue("Client failed to finish", clientLatch.await(10, TimeUnit.SECONDS))
        serverThread.join(5000)

        assertTrue("sendStream returned false", sendSuccess)
        assertTrue("receiveStream returned false", receiveSuccess)
        assertArrayEquals("Received data does not match sent data", testData, receivedOutput.toByteArray())
    }

    @Test
    fun beamHandoverRejectsInvalidToken() {
        val port = ServerSocket(0).use { it.localPort }
        val serverToken = BeamHandover.generateToken()
        val clientWrongToken = BeamHandover.generateToken()
        val testData = ByteArray(1024) { 1 }

        val serverLatch = CountDownLatch(1)
        val clientLatch = CountDownLatch(1)
        var sendSuccess = true
        var receiveSuccess = true

        val serverThread = Thread {
            serverLatch.countDown()
            sendSuccess = BeamHandover.sendStream(port, serverToken, ByteArrayInputStream(testData), testData.size.toLong())
        }
        serverThread.start()

        serverLatch.await(5, TimeUnit.SECONDS)
        Thread.sleep(100)

        val clientThread = Thread {
            receiveSuccess = BeamHandover.receiveStream("127.0.0.1", port, clientWrongToken, ByteArrayOutputStream(), testData.size.toLong())
            clientLatch.countDown()
        }
        clientThread.start()

        clientLatch.await(10, TimeUnit.SECONDS)
        serverThread.join(5000)

        assertFalse("Server should reject invalid token", sendSuccess)
        assertFalse("Client should fail on rejected token", receiveSuccess)
    }
}
