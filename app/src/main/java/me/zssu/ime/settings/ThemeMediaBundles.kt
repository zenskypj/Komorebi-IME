package me.zssu.ime.settings

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.zssu.ime.theme.BackgroundFilter

/** Optional local media snapshot for a named preset. Full encrypted backup includes the snapshots. */
internal class ThemeMediaBundles(private val context: Context) {
    companion object {
        const val DIRECTORY = "theme_media"

        fun readFolder(dir: File): DressUpMedia? = runCatching {
            val metadata = File(dir, "media.json")
            if (!metadata.isFile || metadata.length() > 1024L * 1024) return null
            val data = Json.decodeFromString<Options>(metadata.readText())
            require(data.opacity in 0f..1f && data.brightness in .25f..1.5f && data.blur in 0f..20f &&
                data.x in 0f..1f && data.y in 0f..1f && data.slide in 5..120 && data.start in 0f..1f && data.end in 0f..1f)
            val photo = File(dir, "photo.webp").takeIf(File::isFile)
            val video = File(dir, "video.mp4").takeIf(File::isFile)
            val album = (0..3).mapNotNull { File(dir, "album_$it.webp").takeIf(File::isFile) }
            require(listOf(photo != null, video != null, album.isNotEmpty()).count { it } == 1)
            for (file in listOfNotNull(photo) + album) {
                val info = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, info)
                require(info.outWidth in 1..4096 && info.outHeight in 1..4096)
            }
            if (video != null) {
                val properties = android.media.MediaMetadataRetriever()
                try {
                    properties.setDataSource(video.absolutePath)
                    val duration = properties.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                    val width = properties.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val height = properties.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    require(duration in 1..120000 && width in 1..1920 && height in 1..1920)
                } finally { properties.release() }
            }
            DressUpMedia(metadata.lastModified().hashCode(), photo, album, video, data.opacity,
                data.brightness, data.blur, data.x, data.y, data.slide, data.start, data.end, data.filter)
        }.getOrNull()
    }
    private val root get() = File(context.filesDir, DIRECTORY)
    private val idPattern = Regex("[A-Za-z0-9_-]{1,64}")

    @Serializable
    private data class Options(val opacity: Float, val brightness: Float, val blur: Float,
        val x: Float, val y: Float, val slide: Int, val start: Float, val end: Float, val filter: BackgroundFilter)

    fun folder(id: String): File? = if (idPattern.matches(id)) File(root, id) else null
    fun load(id: String): DressUpMedia? = folder(id)?.let { dir ->
        // Installed snapshots have already been validated during import or creation.
        runCatching {
            val metadata = File(dir, "media.json")
            if (!metadata.isFile || metadata.length() > 1024L * 1024) return null
            val data = Json.decodeFromString<Options>(metadata.readText())
            val photo = File(dir, "photo.webp").takeIf(File::isFile)
            val video = File(dir, "video.mp4").takeIf(File::isFile)
            val album = (0..3).mapNotNull { File(dir, "album_$it.webp").takeIf(File::isFile) }
            DressUpMedia(metadata.lastModified().hashCode(), photo, album, video, data.opacity,
                data.brightness, data.blur, data.x, data.y, data.slide, data.start, data.end, data.filter)
                .takeIf { it.hasImage }
        }.getOrNull()
    }

    fun save(id: String, media: DressUpMedia) {
        require(idPattern.matches(id) && media.hasImage)
        val files = listOfNotNull(media.photo, media.video) + media.album
        val existingBytes = root.listFiles().orEmpty().filter { it.isDirectory && it.name != id && idPattern.matches(it.name) }
            .sumOf { dir -> dir.listFiles().orEmpty().sumOf(File::length) }
        require(files.sumOf(File::length) + existingBytes <= 48L * 1024 * 1024) {
            "テーマに保存する背景は合計48 MBまでです。不要なテーマを削除してください。"
        }
        check(root.isDirectory || root.mkdirs())
        val temp = File(root, ".${java.util.UUID.randomUUID()}.tmp")
        check(temp.mkdir())
        val destination = File(root, id)
        val previous = File(root, ".$id.old")
        try {
            media.photo?.copyTo(File(temp, "photo.webp"))
            media.video?.copyTo(File(temp, "video.mp4"))
            media.album.take(4).forEachIndexed { i, file -> file.copyTo(File(temp, "album_$i.webp")) }
            File(temp, "media.json").writeText(Json.encodeToString(Options(media.opacity, media.brightness, media.blur,
                media.horizontal, media.vertical, media.slideSeconds, media.videoStart, media.videoEnd, media.filter)))
            check(previous.deleteRecursively())
            if (destination.exists()) check(destination.renameTo(previous))
            if (!temp.renameTo(destination)) {
                previous.renameTo(destination)
                error("テーマの背景を保存できません")
            }
            previous.deleteRecursively()
        } finally {
            temp.deleteRecursively()
        }
    }

    fun delete(id: String) {
        if (idPattern.matches(id)) File(root, id).deleteRecursively()
    }
}
