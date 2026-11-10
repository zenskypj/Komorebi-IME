package me.zssu.ime.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.math.max

internal object LocalArt {
    const val KEY_ART_DIR = "key_art"
    private val idPattern = Regex("[0-9a-f]{64}")
    fun validId(id: String) = idPattern.matches(id)

    fun importKey(context: Context, uri: Uri): String {
        val bytes = photo(context, uri, 256)
        val id = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val file = keyFile(context, id) ?: error("画像を保存できません")
        if (!file.isFile) {
            file.parentFile?.mkdirs()
            require(file.parentFile?.list()?.count { it.endsWith(".webp") } ?: 0 < 80) {
                "キー画像は80枚まで保存できます"
            }
            write(file, bytes)
        }
        return id
    }

    fun keyFile(context: Context, id: String): File? =
        if (validId(id)) File(File(context.filesDir, KEY_ART_DIR), "$id.webp") else null

    fun photo(context: Context, uri: Uri, limit: Int): ByteArray {
        val resolver = context.contentResolver
        val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, info) }
        val longEdge = max(info.outWidth, info.outHeight)
        require(info.outWidth > 0 && info.outHeight > 0 && longEdge <= 32768) { "この画像形式または大きさには対応していません" }
        var sample = 1
        while (longEdge / sample > limit * 2) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("画像を開けません")
        try {
            val orientation = runCatching {
                resolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
            }.getOrNull()
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                }
                val scale = (limit.toFloat() / max(decoded.width, decoded.height)).coerceAtMost(1f)
                postScale(scale, scale)
            }
            val bitmap = if (matrix.isIdentity) decoded
                else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            try {
                return ByteArrayOutputStream().use { stream ->
                    @Suppress("DEPRECATION")
                    check(bitmap.compress(Bitmap.CompressFormat.WEBP, 84, stream))
                    stream.toByteArray()
                }
            } finally {
                if (bitmap !== decoded) bitmap.recycle()
            }
        } finally {
            decoded.recycle()
        }
    }

    fun write(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }
}
