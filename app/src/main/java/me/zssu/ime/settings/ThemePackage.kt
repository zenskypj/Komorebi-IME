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
import me.zssu.ime.theme.LocalArt
import me.zssu.ime.keyboard.KeyboardLayout
import java.security.MessageDigest

/** Shares only the selected theme and its optional background. No profiles or input data. */
internal object ThemePackage {
    private val json = Json { ignoreUnknownKeys = true }
    private const val LIMIT = 48L * 1024 * 1024
    private val assets = setOf("media.json", "photo.webp", "video.mp4", "album_0.webp", "album_1.webp", "album_2.webp", "album_3.webp")

    class Draft(val theme: KeyboardTheme, val directory: File, val size: Long, val media: DressUpMedia?, val layout: KeyboardLayout?, val images: List<File>) {
        fun close() { directory.deleteRecursively() }
    }

    fun write(context: Context, output: OutputStream, theme: KeyboardTheme, folder: File?, includeMedia: Boolean, layout: KeyboardLayout?) {
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("theme.json"))
            zip.write(json.encodeToString(KeyboardTheme.serializer(), theme).toByteArray())
            zip.closeEntry()
            if (layout != null) {
                zip.putNextEntry(ZipEntry("layout.json"))
                zip.write(json.encodeToString(KeyboardLayout.serializer(), layout).toByteArray())
                zip.closeEntry()
            }
            val ids = theme.keywordEffects.mapNotNull { it.imageId } +
                layout?.rows.orEmpty().flatMap { it.keys }.mapNotNull { it.appearance?.imageId }
            for (id in ids.distinct()) {
                val image = LocalArt.keyFile(context, id) ?: error("キー画像の識別子が不正です")
                require(image.isFile && image.length() <= 2 * 1024 * 1024) { "キー画像が見つからないか大きすぎます" }
                zip.putNextEntry(ZipEntry("key_$id.webp"))
                image.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
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
                    require(!entry.isDirectory && (entry.name == "theme.json" || entry.name == "layout.json" ||
                        entry.name.matches(Regex("key_[0-9a-f]{64}\\.webp")) || entry.name in assets) &&
                        seen.size < 100 && seen.add(entry.name)) {
                        "テーマに対応していないファイルが含まれています"
                    }
                    val limit = when {
                        entry.name.endsWith(".json") -> 1024L * 1024
                        entry.name.startsWith("key_") -> 2L * 1024 * 1024
                        else -> LIMIT
                    }
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
            val layoutFile = File(dir, "layout.json")
            val layout = if (layoutFile.isFile) json.decodeFromString<KeyboardLayout>(layoutFile.readText()) else null
            val images = dir.listFiles().orEmpty().filter { it.name.startsWith("key_") }
            for (image in images) {
                val digest = MessageDigest.getInstance("SHA-256").digest(image.readBytes()).joinToString("") { "%02x".format(it) }
                require(image.name == "key_$digest.webp") { "キー画像が壊れています" }
                val info = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(image.path, info)
                require(info.outWidth in 1..256 && info.outHeight in 1..256) { "キー画像の形式が不正です" }
            }
            val required = (theme.keywordEffects.mapNotNull { it.imageId } +
                layout?.rows.orEmpty().flatMap { it.keys }.mapNotNull { it.appearance?.imageId }).distinct()
            require(required.all { id -> images.any { it.name == "key_$id.webp" } }) { "キー画像が足りません" }
            val media = ThemeMediaBundles.readFolder(dir)
            require(seen.none { it in assets } || media != null) { "背景の設定が壊れています" }
            return Draft(theme, dir, total, media, layout, images)
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }
}
