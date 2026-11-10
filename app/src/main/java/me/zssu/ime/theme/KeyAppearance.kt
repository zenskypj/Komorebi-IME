package me.zssu.ime.theme

import kotlinx.serialization.Serializable

@Serializable
enum class KeyFrame {
    FILLED, OUTLINE, DIVIDERS, NONE, DASHED, GRADIENT,
}

@Serializable
enum class KeyFont {
    SYSTEM, ROUNDED, SERIF, MONOSPACE,
}

@Serializable
enum class PressFeedback {
    FILL, OUTLINE, NONE,
}

@Serializable
enum class TapEffect { NONE, RING, SPARKLE }

@Serializable
enum class KeySound { SYSTEM, WOOD, GLASS, SOFT }

@Serializable
enum class WallpaperMotion { NONE, LIGHTS, SNOW }

@Serializable
enum class ThemeFlickGuide { INHERIT, PREVIEW, DIRECTIONS }

enum class BackgroundFilter(val label: String) {
    NATURAL("標準"), WARM("暖色"), COOL("寒色"), MONO("白黒"),
}

@Serializable
enum class ThemeWallpaper(val label: String, val asset: String) {
    MOSS("苔の森", "moss"),
    DUSK("湖の宵", "dusk"),
    PAPER("梅の和紙", "paper"),
    RAIN("夜の雨", "rain"),
    OCEAN("島の浅瀬", "ocean"),
    DESERT("夕暮れの砂丘", "desert"),
    SNOW("雪明かり", "snow"),
    AURORA("冬のオーロラ", "aurora"),
    INK("墨と和紙", "ink"),
}

@Serializable
enum class KeyTexture(val label: String, val asset: String?) {
    NONE("なし", null),
    OPAL("オパール", "opal"),
    SLATE("青い石板", "slate"),
    GLASS("すりガラス", "glass"),
    LINEN("麻布", "linen"),
    CAT("丸まる猫", "cat"),
    PLUM("梅の花", "plum"),
    MOON("三日月", "moon"),
    LEAF("いちょう", "leaf"),
    KOI("錦鯉", "koi"),
}

/** Null values inherit the selected theme. Colors use Android ARGB integers in layout JSON. */
@Serializable
data class KeyAppearance(
    val frame: KeyFrame? = null,
    val texture: KeyTexture? = null,
    val imageId: String? = null,
    val fillColor: Int? = null,
    val labelColor: Int? = null,
    val labelSizeSp: Float? = null,
    val showLabel: Boolean = true,
    val showLongPressHint: Boolean? = null,
    val labelBold: Boolean? = null,
)

internal fun KeyboardTheme.frameFor(key: KeyAppearance?): KeyFrame =
    key?.frame ?: keyFrame ?: if (flatKeys) KeyFrame.OUTLINE else KeyFrame.FILLED

internal fun KeyboardTheme.textureFor(key: KeyAppearance?, character: Boolean): KeyTexture =
    key?.texture ?: if (character) keyTexture else modifierTexture


/** Triggers use only text just committed by the IME. No surrounding-text reads are needed. */
@Serializable
data class KeywordEffect(
    val keyword: String,
    val texture: KeyTexture = KeyTexture.PLUM,
    val imageId: String? = null,
)
