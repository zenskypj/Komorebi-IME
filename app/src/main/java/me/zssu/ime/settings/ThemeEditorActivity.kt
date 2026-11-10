package me.zssu.ime.settings

import android.os.Bundle
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import me.zssu.ime.keyboard.FlickKeyboardView
import me.zssu.ime.keyboard.KeyboardLayout
import me.zssu.ime.keyboard.KeyboardPanelView
import me.zssu.ime.keyboard.LayoutRepository
import me.zssu.ime.theme.*

class ThemeEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val themeId = intent.getStringExtra(EXTRA_THEME_ID)
        setContent { ZinnaTheme { ThemeEditorScreen(themeId) } }
    }

    companion object {
        const val EXTRA_THEME_ID = "theme_id"
    }
}

internal val frameNames = mapOf(
    KeyFrame.FILLED to "塗りつぶし", KeyFrame.OUTLINE to "個別の枠",
    KeyFrame.DIVIDERS to "仕切り線", KeyFrame.NONE to "枠なし",
    KeyFrame.DASHED to "破線の枠", KeyFrame.GRADIENT to "グラデーション",
)

private enum class EditorPage(val label: String) {
    SHAPE("枠と画像"), COLORS("配色"), TEXT("文字と反応"), EFFECTS("入力エフェクト"),
}

private data class ColorProperty(
    val title: String, val get: (KeyboardTheme) -> Int, val set: (KeyboardTheme, Int) -> KeyboardTheme,
)

private val colors = listOf(
    ColorProperty("背景", { it.backgroundColor }, { t, v -> t.copy(backgroundColor = v) }),
    ColorProperty("文字キー", { it.keyColor }, { t, v -> t.copy(keyColor = v) }),
    ColorProperty("操作キー", { it.modifierKeyColor }, { t, v -> t.copy(modifierKeyColor = v) }),
    ColorProperty("枠・仕切り線", { it.lineColor }, { t, v -> t.copy(lineColor = v) }),
    ColorProperty("押したキー", { it.keyPressedColor }, { t, v -> t.copy(keyPressedColor = v) }),
    ColorProperty("キー文字", { it.labelColor }, { t, v -> t.copy(labelColor = v) }),
    ColorProperty("フリックガイド背景", { it.flickGuideColor }, { t, v -> t.copy(flickGuideColor = v) }),
    ColorProperty("フリックガイド文字", { it.flickGuideLabelColor }, { t, v -> t.copy(flickGuideLabelColor = v) }),
    ColorProperty("フリックガイド選択色", { it.flickGuideSelectedLabelColor }, { t, v -> t.copy(flickGuideSelectedLabelColor = v) }),
    ColorProperty("候補の背景", { it.candidateBackgroundColor }, { t, v -> t.copy(candidateBackgroundColor = v, useCandidateBackground = true) }),
    ColorProperty("候補の文字", { it.candidateTextColor }, { t, v -> t.copy(candidateTextColor = v) }),
    ColorProperty("ツールバー背景", { it.toolbarBackgroundColor }, { t, v -> t.copy(toolbarBackgroundColor = v) }),
    ColorProperty("ツールバーアイコン", { it.toolbarIconColor }, { t, v -> t.copy(toolbarIconColor = v) }),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ThemeEditorScreen(initialId: String?) {
    val context = LocalContext.current
    val repo = remember { LayoutRepository(context) }
    val settings = remember { ImeSettings(context) }
    val initial = remember(initialId) {
        val id = initialId ?: settings.activeThemeId
        repo.loadTheme(id) ?: if (id == MaterialYouTheme.ID) MaterialYouTheme.create(context) else KeyboardTheme.Default
    }
    var theme by remember(initial) { mutableStateOf(initial) }
    val isNew = initialId == null || initialId == MaterialYouTheme.ID
    var id by remember(initialId) {
        val used = repo.availableThemeIds().toSet()
        val newId = generateSequence(1) { it + 1 }.map { if (it == 1) "my_theme" else "my_theme_$it" }
            .first { it !in used }
        mutableStateOf(if (isNew) newId else requireNotNull(initialId))
    }
    var name by remember(initial) { mutableStateOf(if (isNew) "マイテーマ" else initial.label) }
    var page by remember { mutableStateOf(EditorPage.SHAPE) }
    var qwerty by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val layout = remember(qwerty) {
        repo.loadLayout(if (qwerty) "qwerty_ascii" else LayoutRepository.DEFAULT_LAYOUT_ID)
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("テーマを編集") }, navigationIcon = {
            IconButton(onClick = { (context as? ComponentActivity)?.finish() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る")
            }
        }, actions = {
            TextButton(onClick = {
                runCatching {
                    require(id != MaterialYouTheme.ID) { "別のテーマIDを指定してください" }
                    require(name.isNotBlank()) { "表示名を入力してください" }
                    repo.saveTheme(theme.copy(id = id, label = name))
                    settings.markCustomizationChanged()
                    settings.activeThemeId = id
                }.onSuccess { message = "「$name」を適用しました" }
                    .onFailure { message = it.message ?: "保存できませんでした" }
            }) { Text("保存して適用") }
        })
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!qwerty, { qwerty = false }, label = { Text("フリック") })
                    FilterChip(qwerty, { qwerty = true }, label = { Text("QWERTY") })
                }
                if (layout != null) ThemeKeyboardPreview(theme, layout)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EditorPage.entries.forEach { option ->
                        FilterChip(page == option, { page = option }, label = { Text(option.label) })
                    }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            HorizontalDivider()
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (page) {
                    EditorPage.SHAPE -> {
                        Text("キーの区切り", style = MaterialTheme.typography.titleMedium)
                        ChoiceChips(frameNames, theme.frameFor(null)) { theme = theme.copy(keyFrame = it) }
                        ThemeSlider("線の太さ", theme.lineWidthDp, 0.5f..4f, "dp") { theme = theme.copy(lineWidthDp = it) }
                        ThemeSlider("角の丸み", theme.keyCornerRadiusDp, 0f..32f, "dp") { theme = theme.copy(keyCornerRadiusDp = it) }
                        ThemeSlider("キーの間隔", theme.keyGapDp, 0f..16f, "dp") { theme = theme.copy(keyGapDp = it) }
                        ThemeSlider("キーの高さ", theme.keyHeightDp, 32f..96f, "dp") { theme = theme.copy(keyHeightDp = it) }
                        ThemeSlider("キー面の濃さ", theme.keyOpacity * 100f, 0f..100f, "%") { theme = theme.copy(keyOpacity = it / 100f) }
                        Text("背景イラスト", style = MaterialTheme.typography.titleMedium)
                        ArtPicker(listOf(null) + ThemeWallpaper.entries, theme.wallpaper,
                            { it?.label ?: "なし" }, { it?.asset }, { theme = theme.copy(wallpaper = it) })
                        if (theme.wallpaper != null) {
                            ThemeSlider("背景の濃さ", theme.wallpaperOpacity * 100f, 0f..100f, "%") {
                                theme = theme.copy(wallpaperOpacity = it / 100f)
                            }
                        }
                        Text("背景の演出", style = MaterialTheme.typography.titleMedium)
                        ChoiceChips(mapOf(WallpaperMotion.NONE to "なし", WallpaperMotion.LIGHTS to "光の粒",
                            WallpaperMotion.SNOW to "雪"), theme.wallpaperMotion) { theme = theme.copy(wallpaperMotion = it) }
                        Text("文字キーの画像", style = MaterialTheme.typography.titleMedium)
                        TexturePicker(theme.keyTexture) { theme = theme.copy(keyTexture = it ?: KeyTexture.NONE) }
                        Text("操作キーの画像", style = MaterialTheme.typography.titleMedium)
                        TexturePicker(theme.modifierTexture) { theme = theme.copy(modifierTexture = it ?: KeyTexture.NONE) }
                        Text("外観で写真や動画を選んでいる場合は、その背景が優先されます。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    EditorPage.COLORS -> {
                        ThemeToggle("候補に背景色を付ける", theme.useCandidateBackground) { theme = theme.copy(useCandidateBackground = it) }
                        var prop by remember { mutableStateOf(colors.first()) }
                        ChoiceChips(colors.associateWith { it.title }, prop) { prop = it }
                        key(prop) {
                            var hex by remember { mutableStateOf("#%08X".format(prop.get(theme))) }
                            val parsed = parseThemeColor(hex)
                            OutlinedTextField(hex, {
                                hex = it.take(9)
                                parseThemeColor(hex)?.let { color -> theme = prop.set(theme, color) }
                            }, label = { Text(prop.title) }, singleLine = true, isError = parsed == null,
                                supportingText = { Text("#RRGGBB または #AARRGGBB") }, modifier = Modifier.fillMaxWidth())
                            if (parsed != null) {
                                val hsv = FloatArray(3)
                                android.graphics.Color.colorToHSV(parsed, hsv)
                                ThemeSlider("色相", hsv[0], 0f..360f, "°") {
                                    hsv[0] = it
                                    hex = "#%08X".format(android.graphics.Color.HSVToColor(android.graphics.Color.alpha(parsed), hsv))
                                    theme = prop.set(theme, requireNotNull(parseThemeColor(hex)))
                                }
                                ThemeSlider("鮮やかさ", hsv[1] * 100, 0f..100f, "%") {
                                    hsv[1] = it / 100
                                    val color = android.graphics.Color.HSVToColor(android.graphics.Color.alpha(parsed), hsv)
                                    hex = "#%08X".format(color); theme = prop.set(theme, color)
                                }
                                ThemeSlider("明るさ", hsv[2] * 100, 0f..100f, "%") {
                                    hsv[2] = it / 100
                                    val color = android.graphics.Color.HSVToColor(android.graphics.Color.alpha(parsed), hsv)
                                    hex = "#%08X".format(color); theme = prop.set(theme, color)
                                }
                                ThemeSlider("色の不透明度", android.graphics.Color.alpha(parsed) * 100f / 255, 0f..100f, "%") {
                                    val color = (parsed and 0xffffff) or ((it * 255 / 100).toInt() shl 24)
                                    hex = "#%08X".format(color); theme = prop.set(theme, color)
                                }
                            }
                        }
                    }
                    EditorPage.EFFECTS -> KeywordEffectsEditor(theme.keywordEffects) {
                        theme = theme.copy(keywordEffects = it)
                    }
                    EditorPage.TEXT -> {
                        Text("キートップの書体", style = MaterialTheme.typography.titleMedium)
                        ChoiceChips(mapOf(KeyFont.SYSTEM to "標準", KeyFont.ROUNDED to "丸ゴシック系",
                            KeyFont.SERIF to "明朝系", KeyFont.MONOSPACE to "等幅"), theme.labelFont) { theme = theme.copy(labelFont = it) }
                        ThemeSlider("キー文字の大きさ", theme.labelSizeSp, 10f..32f, "sp") { theme = theme.copy(labelSizeSp = it) }
                        ThemeToggle("太字", theme.labelBold) { theme = theme.copy(labelBold = it) }
                        ThemeToggle("文字の影", theme.labelShadow) { theme = theme.copy(labelShadow = it) }
                        ThemeToggle("長押しのヒント", theme.showLongPressHints) { theme = theme.copy(showLongPressHints = it) }
                        ThemeToggle("フリックガイド", theme.showFlickGuides) { theme = theme.copy(showFlickGuides = it) }
                        if (theme.showFlickGuides) {
                            ChoiceChips(mapOf(ThemeFlickGuide.INHERIT to "入力設定に従う", ThemeFlickGuide.PREVIEW to "選択中の文字",
                                ThemeFlickGuide.DIRECTIONS to "全方向"), theme.flickGuideMode) { theme = theme.copy(flickGuideMode = it) }
                        }
                        ThemeToggle("キー音", theme.keyClickSound) { theme = theme.copy(keyClickSound = it) }
                        if (theme.keyClickSound) {
                            ChoiceChips(mapOf(KeySound.SYSTEM to "端末標準", KeySound.WOOD to "木", KeySound.GLASS to "ガラス",
                                KeySound.SOFT to "ソフト"), theme.keySound) { theme = theme.copy(keySound = it) }
                            if (theme.keySound != KeySound.SYSTEM) ThemeSlider("キー音の音量", theme.keySoundVolume * 100, 0f..100f, "%") {
                                theme = theme.copy(keySoundVolume = it / 100)
                            }
                        }
                        Text("タップの演出", style = MaterialTheme.typography.titleMedium)
                        ChoiceChips(mapOf(TapEffect.NONE to "なし", TapEffect.RING to "波紋",
                            TapEffect.SPARKLE to "きらめき"), theme.tapEffect) { theme = theme.copy(tapEffect = it) }
                        Text("押したときの強調", style = MaterialTheme.typography.titleMedium)
                        ChoiceChips(mapOf(PressFeedback.FILL to "面の色", PressFeedback.OUTLINE to "枠線",
                            PressFeedback.NONE to "なし"), theme.pressFeedback) { theme = theme.copy(pressFeedback = it) }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                OutlinedTextField(name, { name = it.take(80) }, label = { Text("表示名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(id, { id = it.take(64) }, label = { Text("テーマID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
internal fun ThemeKeyboardPreview(theme: KeyboardTheme, layout: KeyboardLayout) {
    AndroidView(
        factory = { context ->
            KeyboardPanelView(context).apply {
                val strip = LinearLayout(context)
                for (sample in listOf("候補", "変換", "設定")) {
                    strip.addView(android.widget.TextView(context).apply {
                        text = sample
                        gravity = android.view.Gravity.CENTER
                        textSize = 13f
                    }, LinearLayout.LayoutParams(0, (28 * resources.displayMetrics.density).toInt(), 1f))
                }
                addView(strip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(FlickKeyboardView(context), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
            }
        },
        modifier = Modifier.fillMaxWidth(),
        update = { panel ->
            panel.setBackgroundColor(theme.backgroundColor)
            panel.setWallpaper(theme.wallpaper, theme.wallpaperOpacity, theme.wallpaperMotion)
            val strip = panel.getChildAt(0) as LinearLayout
            for (i in 0 until strip.childCount) {
                (strip.getChildAt(i) as android.widget.TextView).apply {
                    val isTool = i == strip.childCount - 1
                    val toolbarTint = theme.toolbarIconColor.takeUnless { it == android.graphics.Color.TRANSPARENT }
                    setTextColor(if (isTool) toolbarTint ?: theme.candidateTextColor else theme.candidateTextColor)
                    setBackgroundColor(if (isTool) theme.toolbarBackgroundColor else
                        if (theme.useCandidateBackground) theme.candidateBackgroundColor else android.graphics.Color.TRANSPARENT)
                }
            }
            (panel.getChildAt(1) as FlickKeyboardView).apply {
                this.theme = theme.copy(keyHeightDp = (theme.keyHeightDp * 0.62f).coerceAtMost(46f))
                if (this.layout != layout) this.layout = layout
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> ChoiceChips(options: Map<T, String>, value: T, onChange: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (option, name) ->
            FilterChip(value == option, { onChange(option) }, label = { Text(name) })
        }
    }
}

@Composable
internal fun <T> ArtPicker(options: List<T>, value: T, label: (T) -> String, asset: (T) -> String?, select: (T) -> Unit) {
    val context = LocalContext.current
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { item ->
            val name = asset(item)
            val bitmap = remember(name) { name?.let { ThemeArt.load(context, it)?.asImageBitmap() } }
            OutlinedCard(onClick = { select(item) }, modifier = Modifier.width(100.dp)) {
                bitmap?.let {
                    Image(it, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(56.dp))
                }
                Text((if (item == value) "✓ " else "") + label(item), Modifier.padding(8.dp),
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
internal fun TexturePicker(value: KeyTexture?, includeInherit: Boolean = false, select: (KeyTexture?) -> Unit) {
    ArtPicker((if (includeInherit) listOf(null) else emptyList()) + KeyTexture.entries, value,
        { it?.label ?: "テーマに従う" }, { it?.asset }, select)
}

@Composable
internal fun ThemeSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String, onChange: (Float) -> Unit) {
    Text("$label  ${"%.1f".format(value).removeSuffix(".0")}$unit", style = MaterialTheme.typography.bodyMedium)
    Slider(value.coerceIn(range), onChange, valueRange = range)
}

@Composable
internal fun ThemeToggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, onChange)
    }
}

internal fun parseThemeColor(raw: String): Int? = raw.takeIf {
    it.startsWith("#") && (it.length == 7 || it.length == 9)
}?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() }
