package me.zssu.ime.mozc

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Bounded, verified, atomic installation. The standard asset/profile are never destinations. */
internal object DictionaryDataFiles {
    private val magic = byteArrayOf(0xef.toByte(), 0x4d, 0x4f, 0x5a, 0x43, 0x0d, 0x0a)

    fun sha256(file: File): String = FileInputStream(file).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun verify(file: File, bytes: Long, hash: String) {
        require(bytes > magic.size && file.length() == bytes) { "データのサイズが一致しません" }
        FileInputStream(file).use { input ->
            val header = ByteArray(magic.size)
            require(input.read(header) == header.size && header.contentEquals(magic)) { "Mozcデータ形式ではありません" }
        }
        require(sha256(file) == hash) { "データのSHA-256が一致しません" }
    }

    fun copyBounded(input: InputStream, target: File, expectedBytes: Long, progress: (Long) -> Unit = {}) {
        require(expectedBytes > 0)
        FileOutputStream(target).use { output ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("取得を中止しました")
                val count = input.read(buffer)
                if (count < 0) break
                require(count.toLong() <= expectedBytes - total) { "データが予定サイズを超えています" }
                output.write(buffer, 0, count)
                total += count
                progress(total)
            }
            require(total == expectedBytes) { "データの取得が途中で終了しました" }
            output.fd.sync()
        }
    }

    fun installGzip(archive: File, target: File, bytes: Long, hash: String) {
        val temporary = File(target.parentFile, target.name + ".installing")
        try {
            GZIPInputStream(FileInputStream(archive)).use { copyBounded(it, temporary, bytes) }
            verify(temporary, bytes, hash)
            check(temporary.renameTo(target)) { "検証済みデータを保存できません" }
        } finally {
            temporary.delete()
        }
    }
}
