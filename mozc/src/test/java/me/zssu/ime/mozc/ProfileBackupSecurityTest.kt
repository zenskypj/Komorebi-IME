package me.zssu.ime.mozc

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ProfileBackupSecurityTest {
    @Test fun archiveAuthenticatesPasswordAndPayload() {
        val payload = "学習と辞書".toByteArray()
        val archive = requireNotNull(ProfileBackup.seal(payload, "long passphrase".toCharArray()))
        val opened = ProfileBackup.open(archive, "long passphrase".toCharArray()) as ProfileBackup.Opened.Payload
        assertArrayEquals(payload, opened.bytes)
        assertTrue(ProfileBackup.open(archive, "wrong phrase".toCharArray()) is ProfileBackup.Opened.Failure)
        archive[archive.lastIndex] = (archive.last().toInt() xor 1).toByte()
        assertTrue(ProfileBackup.open(archive, "long passphrase".toCharArray()) is ProfileBackup.Opened.Failure)
    }
    @Test fun restoreCannotEscapeFilesDirectory() {
        val parent = Files.createTempDirectory("backup-path").toFile()
        try {
            val files = java.io.File(parent, "files").apply { mkdirs() }
            ProfileBackup.restoreFile(files, "../outside", byteArrayOf(1))
            assertFalse(java.io.File(parent, "outside").exists())
            ProfileBackup.restoreFile(files, "layouts/custom.json", byteArrayOf(2))
            assertArrayEquals(byteArrayOf(2), java.io.File(files, "layouts/custom.json").readBytes())
        } finally { parent.deleteRecursively() }
    }
}
