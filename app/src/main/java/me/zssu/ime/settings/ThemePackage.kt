package me.zssu.ime.settings

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import me.zssu.ime.theme.KeyboardTheme

/** Shares only the selected theme and its optional background. No profiles or input data. */
internal object ThemePackage {
    private val json = Json { ignoreUnknownKeys = true }
    private const val LIMIT = 48L * 1024 * 1024
    private val assets = setOf("media.json", "photo.webp", "video.mp4", "album_0.webp", "album_1.webp", "album_2.webp", "album_3.webp")

    class Draft(val theme: KeyboardTheme, val directory: File, val size: Long, val media: DressUpMedia?) {
        fun close() { directory.deleteRecursively() }
    }

    fun write(output: OutputStream, theme: KeyboardTheme, folder: File?, includeMedia: Boolean) {
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("theme.json"))
            zip.write(json.encodeToString(KeyboardTheme.serializer(), theme).toByteArray())
            zip.closeEntry()
            if (includeMedia) {
                folder?.listFiles().orEmpty().filter { it.isFile && it.name in assets }.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    fun read(context: Context, input: InputStream): Draft {
        val dir = File(context.cacheDir, "theme-import-${java.util.UUID.randomUUID()}")
        check(dir.mkdir())
        try {
            var total = 0L
            val seen = mutableSetOf<String>()
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && (entry.name == "theme.json" || entry.name in assets) && seen.add(entry.name)) {
                        "テーマに対応していないファイルが含まれています"
                    }
                    val limit = if (entry.name.endsWith(".json")) 1024L * 1024 else LIMIT
                    File(dir, entry.name).outputStream().use { out ->
                        val buffer = ByteArray(16_384)
                        var length = 0L
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            total += count
                            length += count
                            require(length <= limit && total <= LIMIT + 1024L * 1024) { "テーマファイルが大きすぎます" }
                            out.write(buffer, 0, count)
                        }
                    }
                    zip.closeEntry()
                }
            }
            val theme = json.decodeFromString<KeyboardTheme>(File(dir, "theme.json").readText())
            val media = ThemeMediaBundles.readFolder(dir)
            require(seen.size == 1 || media != null) { "背景の設定が壊れています" }
            return Draft(theme, dir, total, media)
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }
}
