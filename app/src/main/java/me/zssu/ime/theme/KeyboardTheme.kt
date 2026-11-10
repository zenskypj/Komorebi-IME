package me.zssu.ime.theme

import android.graphics.Color
import kotlinx.serialization.Serializable

/**
 * Everything a user can restyle about the keyboard surface.
 *
 * Themes are serialized to JSON and stored alongside layouts, so a theme is shareable as a single
 * file. Colors are held as ARGB ints and parsed from `#RRGGBB` / `#AARRGGBB` strings on the way in,
 * which keeps hand-written theme files readable.
 */
@Serializable
data class KeyboardTheme(
    val id: String,
    val label: String,
    @Serializable(with = ColorAsStringSerializer::class) val backgroundColor: Int,
    @Serializable(with = ColorAsStringSerializer::class) val keyColor: Int,
    @Serializable(with = ColorAsStringSerializer::class) val keyPressedColor: Int,
    @Serializable(with = ColorAsStringSerializer::class) val modifierKeyColor: Int,
    @Serializable(with = ColorAsStringSerializer::class) val labelColor: Int,
    @Serializable(with = ColorAsStringSerializer::class) val flickGuideColor: Int,
    @Serializable(with = ColorAsStringSerializer::class) val flickGuideLabelColor: Int,
    @Serializable(with = ColorAsStringSerializer::class) val flickGuideSelectedLabelColor: Int,
    @Serializable(with = ColorAsStringSerializer::class) val candidateBackgroundColor: Int,
    @Serializable(with = ColorAsStringSerializer::class) val candidateTextColor: Int,
    val keyHeightDp: Float = 56f,
    val keyGapDp: Float = 2f,
    val keyCornerRadiusDp: Float = 8f,
    val labelSizeSp: Float = 17f,
    val hapticFeedback: Boolean = true,
    // Older theme files use this to select between outlined and filled keys.
    val flatKeys: Boolean = true,
    val keyFrame: KeyFrame? = null,
    val useCandidateBackground: Boolean = false,
    @Serializable(with = ColorAsStringSerializer::class) val lineColor: Int = Color.TRANSPARENT,
    @Serializable(with = ColorAsStringSerializer::class) val toolbarBackgroundColor: Int = Color.TRANSPARENT,
    @Serializable(with = ColorAsStringSerializer::class) val toolbarIconColor: Int = Color.TRANSPARENT,
    val lineWidthDp: Float = 1f,
    val keyOpacity: Float = 1f,
    val wallpaper: ThemeWallpaper? = null,
    val wallpaperOpacity: Float = 0.8f,
    val keyTexture: KeyTexture = KeyTexture.NONE,
    val modifierTexture: KeyTexture = KeyTexture.NONE,
    val labelFont: KeyFont = KeyFont.SYSTEM,
    val labelBold: Boolean = false,
    val labelShadow: Boolean = false,
    val showLongPressHints: Boolean = true,
    val showFlickGuides: Boolean = true,
    val pressFeedback: PressFeedback = PressFeedback.FILL,
    val keyClickSound: Boolean = false,
    val keySound: KeySound = KeySound.SYSTEM,
    val keySoundVolume: Float = 0.6f,
    val wallpaperMotion: WallpaperMotion = WallpaperMotion.NONE,
    val flickGuideMode: ThemeFlickGuide = ThemeFlickGuide.INHERIT,
    val tapEffect: TapEffect = TapEffect.NONE,
    val keywordEffects: List<KeywordEffect> = emptyList(),
) {
    /**
     * True-black variant for OLED panels, where a black pixel is an unlit pixel.
     *
     * Only the large flat areas go to black — key press feedback and labels keep the source
     * theme's colours, since blacking those out would just make the keyboard unreadable.
     */
    fun asPureBlack(): KeyboardTheme = copy(
        backgroundColor = Color.BLACK,
        candidateBackgroundColor = Color.BLACK,
        keyColor = Color.BLACK,
        modifierKeyColor = Color.BLACK,
    )

    companion object {
        val Default = KeyboardTheme(
            id = "default-dark",
            label = "Default Dark",
            backgroundColor = 0xFF1B1C1E.toInt(),
            keyColor = 0xFF2E3033.toInt(),
            keyPressedColor = 0xFF4A4D52.toInt(),
            modifierKeyColor = 0xFF232528.toInt(),
            labelColor = 0xFFECEDEE.toInt(),
            flickGuideColor = 0xFF3D6BD6.toInt(),
            flickGuideLabelColor = 0xFFD6DEF0.toInt(),
            flickGuideSelectedLabelColor = 0xFFFFFFFF.toInt(),
            candidateBackgroundColor = 0xFF1B1C1E.toInt(),
            candidateTextColor = 0xFFECEDEE.toInt(),
        )

        val Light = Default.copy(
            id = "default-light",
            label = "Default Light",
            backgroundColor = 0xFFE7E9EC.toInt(),
            keyColor = 0xFFFDFDFD.toInt(),
            keyPressedColor = 0xFFC9CDD3.toInt(),
            modifierKeyColor = 0xFFD3D7DD.toInt(),
            labelColor = 0xFF1B1C1E.toInt(),
            flickGuideColor = 0xFF4C7DF0.toInt(),
            flickGuideLabelColor = 0xFFEDF1FB.toInt(),
            flickGuideSelectedLabelColor = 0xFFFFFFFF.toInt(),
            candidateBackgroundColor = 0xFFE7E9EC.toInt(),
            candidateTextColor = 0xFF1B1C1E.toInt(),
        )
    }
}
