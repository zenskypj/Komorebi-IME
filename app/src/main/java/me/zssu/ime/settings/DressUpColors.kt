package me.zssu.ime.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import androidx.core.graphics.ColorUtils
import me.zssu.ime.theme.KeyboardTheme
import java.io.File

/** Small bitmap sampling keeps image decoding and palette generation off the UI thread. */
internal object DressUpColors {
    fun fromFile(file: File): Int? {
        val bitmap = if (file.name == "keyboard_video" || file.extension.equals("mp4", true)) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try { retriever.setDataSource(file.absolutePath); retriever.getFrameAtTime(0) }
                finally { retriever.release() }
            }.getOrNull()
        } else BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = 8 })
        return bitmap?.let(::sample)
    }

    fun fromAsset(context: Context, asset: String): Int? = runCatching {
        context.assets.open("theme-art/$asset.webp").use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 8 })
        }?.let(::sample)
    }.getOrNull()

    private fun sample(original: Bitmap): Int? {
        val small = Bitmap.createScaledBitmap(original, 32, 32, true)
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val c = small.getPixel(x, y)
            if (Color.alpha(c) < 128) continue
            r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
        }
        if (small !== original) small.recycle()
        original.recycle()
        return if (n == 0) null else Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /** Custom media stays visible; solid colors remain available for guides and tool tiles. */
    fun apply(theme: KeyboardTheme, sampled: Int, customMedia: Boolean = false): KeyboardTheme {
        val hsv = FloatArray(3); Color.colorToHSV(sampled, hsv)
        val light = ColorUtils.calculateContrast(Color.BLACK, sampled) >= ColorUtils.calculateContrast(Color.WHITE, sampled)
        fun tone(saturation: Float, value: Float): Int = Color.HSVToColor(floatArrayOf(hsv[0], saturation, value))
        val saturation = hsv[1].coerceIn(0.08f, 0.30f)
        val background = tone(saturation, if (light) 0.94f else 0.18f)
        val keys = tone(saturation * 0.55f, if (light) 0.99f else 0.27f)
        val modifiers = tone(saturation, if (light) 0.86f else 0.22f)
        val pressed = tone(saturation.coerceAtLeast(0.18f), if (light) 0.77f else 0.38f)
        val text = if (light) Color.rgb(24, 26, 28) else Color.WHITE
        val accent = tone(hsv[1].coerceIn(0.35f, 0.65f), if (light) 0.36f else 0.9f)
        return theme.copy(backgroundColor = background, keyColor = keys, modifierKeyColor = modifiers,
            keyPressedColor = pressed, labelColor = text, lineColor = text,
            candidateBackgroundColor = background, candidateTextColor = text, useCandidateBackground = !customMedia,
            toolbarBackgroundColor = if (customMedia) Color.TRANSPARENT else background, toolbarIconColor = text,
            flickGuideColor = keys, flickGuideLabelColor = text, flickGuideSelectedLabelColor = accent,
            keyOpacity = if (customMedia) 0.08f else 1f, labelShadow = !light)
    }

    fun highContrast(theme: KeyboardTheme, light: Boolean, customMedia: Boolean): KeyboardTheme {
        val foreground = if (light) Color.BLACK else Color.WHITE
        val background = if (light) Color.WHITE else Color.BLACK
        return unifiedText(theme, foreground).copy(
            backgroundColor = background, lineColor = foreground, labelBold = true, labelShadow = customMedia,
            toolbarBackgroundColor = if (customMedia) Color.TRANSPARENT else background,
            candidateBackgroundColor = background, useCandidateBackground = !customMedia,
            keyColor = background, modifierKeyColor = background,
            keyPressedColor = if (light) 0xffc4c4c4.toInt() else 0xff444444.toInt(),
            keyOpacity = if (customMedia) 0.08f else 1f, flickGuideColor = background,
        )
    }

    fun unifiedText(theme: KeyboardTheme, color: Int): KeyboardTheme = theme.copy(
        labelColor = color, candidateTextColor = color, toolbarIconColor = color,
        flickGuideLabelColor = color, flickGuideSelectedLabelColor = color,
    )
}
