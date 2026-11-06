package me.zssu.ime.settings

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BeamHandoverTest {

    @Test
    fun handoverPayloadJsonRoundtrip() {
        val original = BeamHandover.HandoverPayload(
            targetType = "dict",
            name = "辭書パック.tsv",
            size = 1048576L,
            mimeType = "text/tab-separated-values",
            ownerAddress = "02:11:22:33:44:55",
            ownerIp = "192.168.49.1",
            port = 49152,
            token = "secret-token-xyz-1234",
            ownerName = "Komorebi Sender",
        )
        val json = original.toJson()
        val parsed = BeamHandover.HandoverPayload.fromJson(json)

        assertEquals(original.targetType, parsed.targetType)
        assertEquals(original.name, parsed.name)
        assertEquals(original.size, parsed.size)
        assertEquals(original.mimeType, parsed.mimeType)
        assertEquals(original.ownerAddress, parsed.ownerAddress)
        assertEquals(original.ownerIp, parsed.ownerIp)
        assertEquals(original.port, parsed.port)
        assertEquals(original.token, parsed.token)
        assertEquals(original.ownerName, parsed.ownerName)
    }

    @Test
    fun handoverPayloadAllowsAnEmptyAddressWhenTheOwnerNameIsKnown() {
        val original = BeamHandover.HandoverPayload(
            targetType = "file",
            name = "photo.jpg",
            size = 1024L,
            mimeType = "image/jpeg",
            ownerAddress = "",
            ownerIp = "192.168.49.1",
            token = "secret-token-xyz-1234",
            ownerName = "Komorebi Sender",
        )
        val parsed = BeamHandover.HandoverPayload.fromJson(original.toJson())
        assertEquals("", parsed.ownerAddress)
        assertEquals("Komorebi Sender", parsed.ownerName)
    }

    @Test
    fun beamTransportSealsAndOpensExtendedTypes() {
        val password = "test-password".toCharArray()
        val types = listOf(
            BeamMessage("text", "こんにちは"),
            BeamMessage("url", "https://example.com/test"),
            BeamMessage("app", "me.zssu.ime"),
            BeamMessage("dict", "とうきょう\t東京\t名詞\t首都", name = "追加辞書", size = 20L),
            BeamMessage("file", "content://example/photo", name = "photo.jpg", mimeType = "image/jpeg", size = 1024L),
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
            assertEquals("", opened.extra)
        }
    }

    @Test
    fun largeLocalExtraNeverCrossesTheNfcLimit() {
        val password = "test-password".toCharArray()
        val message = BeamMessage(
            type = "handover",
            value = "{\"name\":\"backup.tar\"}",
            name = "backup.tar",
            mimeType = "application/octet-stream",
            size = 100_000L,
            extra = "x".repeat(100_000)
        )

        val sealed = BeamTransport.seal(message, password)
        val opened = BeamTransport.open(sealed, password)

        assertEquals("", opened.extra)
        assertEquals(100_000L, opened.size)
    }

    @Test
    fun sanitizeFileNameKeepsOnlyOneSafeComponent() {
        assertEquals("passwd", BeamHandover.sanitizeFileName("../../etc/passwd"))
        assertEquals("辞書.tsv", BeamHandover.sanitizeFileName("辞書.tsv"))
        assertEquals("beam_file", BeamHandover.sanitizeFileName(".."))
        assertEquals("c.txt", BeamHandover.sanitizeFileName("a/b\\c.txt"))
    }

    @Test
    fun placeholderP2pAddressIsRejected() {
        assertFalse(BeamHandover.isUsableP2pAddress("02:00:00:00:00:00"))
        assertFalse(BeamHandover.isUsableP2pAddress("00:00:00:00:00:00"))
        assertFalse(BeamHandover.isUsableP2pAddress(null))
        assertTrue(BeamHandover.isUsableP2pAddress("02:11:22:33:44:55"))
    }

    @Test
    fun beamHandoverStreamSendAndReceiveOverLoopback() {
        val port = ServerSocket(0).use { it.localPort }
        val token = BeamHandover.generateToken()
        val testData = ByteArray(128 * 1024) { (it % 251).toByte() }

        val readyLatch = CountDownLatch(1)
        val clientLatch = CountDownLatch(1)
        var sendSuccess = false
        var receiveSuccess = false
        val receivedOutput = ByteArrayOutputStream()

        val serverThread = Thread {
            sendSuccess = BeamHandover.sendStream(
                "127.0.0.1",
                port,
                token,
                ByteArrayInputStream(testData),
                testData.size.toLong(),
                onReady = { readyLatch.countDown() },
            )
        }
        serverThread.start()

        assertTrue("Server failed to bind", readyLatch.await(5, TimeUnit.SECONDS))

        val clientThread = Thread {
            receiveSuccess = BeamHandover.receiveStream(
                "127.0.0.1",
                port,
                token,
                receivedOutput,
                testData.size.toLong(),
            )
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
    fun serverReceiverAndClientSenderOverLoopback() {
        val server = BeamHandover.openServer("127.0.0.1", 0)
        val port = server.localPort
        val token = BeamHandover.generateToken()
        val testData = ByteArray(96 * 1024) { (it % 239).toByte() }
        val receivedOutput = ByteArrayOutputStream()

        var sendSuccess = false
        var receiveSuccess = false

        val serverThread = Thread {
            receiveSuccess = BeamHandover.receiveStreamFromHost(
                server,
                token,
                receivedOutput,
                testData.size.toLong(),
            )
        }
        serverThread.start()

        val clientThread = Thread {
            sendSuccess = BeamHandover.sendStreamToHost(
                "127.0.0.1",
                port..port,
                token,
                ByteArrayInputStream(testData),
                testData.size.toLong(),
            )
        }
        clientThread.start()

        clientThread.join(10000)
        serverThread.join(10000)
        assertTrue("sendStreamToHost returned false", sendSuccess)
        assertTrue("receiveStreamFromHost returned false", receiveSuccess)
        assertArrayEquals(testData, receivedOutput.toByteArray())
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
            sendSuccess = BeamHandover.sendStream(
                "127.0.0.1",
                port,
                serverToken,
                ByteArrayInputStream(testData),
                testData.size.toLong(),
            )
        }
        serverThread.start()

        serverLatch.await(5, TimeUnit.SECONDS)
        Thread.sleep(100)

        val clientThread = Thread {
            receiveSuccess = BeamHandover.receiveStream(
                "127.0.0.1",
                port,
                clientWrongToken,
                ByteArrayOutputStream(),
                testData.size.toLong(),
            )
            clientLatch.countDown()
        }
        clientThread.start()

        clientLatch.await(10, TimeUnit.SECONDS)
        serverThread.join(5000)

        assertFalse("Server should reject invalid token", sendSuccess)
        assertFalse("Client should fail on rejected token", receiveSuccess)
    }

    @Test
    fun receiverRejectsAStreamThatDoesNotMatchTheDeclaredSize() {
        val port = ServerSocket(0).use { it.localPort }
        val token = BeamHandover.generateToken()
        val testData = ByteArray(4096) { 7 }

        val serverThread = Thread {
            BeamHandover.sendStream(
                "127.0.0.1",
                port,
                token,
                ByteArrayInputStream(testData),
                testData.size.toLong(),
            )
        }
        serverThread.start()
        Thread.sleep(100)

        val receiveSuccess = BeamHandover.receiveStream(
            "127.0.0.1",
            port,
            token,
            ByteArrayOutputStream(),
            testData.size.toLong() + 1,
        )
        serverThread.join(5000)

        assertFalse("A size mismatch must fail", receiveSuccess)
    }

    @Test
    fun streamRequiresAPositiveBoundedSize() {
        assertThrows(IllegalArgumentException::class.java) {
            BeamHandover.sendStream(
                "127.0.0.1",
                1,
                "token-1234567890",
                ByteArrayInputStream(ByteArray(1)),
                0,
            )
        }
    }
}
