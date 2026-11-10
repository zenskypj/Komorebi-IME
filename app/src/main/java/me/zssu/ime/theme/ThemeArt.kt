package me.zssu.ime.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache

/** Shared small decoded resources; no file access or bitmap allocation during key drawing. */
internal object ThemeArt {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun loadLocal(context: Context, id: String): Bitmap? {
        val key = "local:$id"
        cache.get(key)?.let { return it }
        val file = LocalArt.keyFile(context, id)?.takeIf { it.isFile } ?: return null
        return BitmapFactory.decodeFile(file.absolutePath)?.also { cache.put(key, it) }
    }

    fun load(context: Context, name: String): Bitmap? = cache.get(name) ?: runCatching {
        context.assets.open("theme-art/$name.webp").use { BitmapFactory.decodeStream(it) }
            ?.also { cache.put(name, it) }
    }.getOrNull()
}
