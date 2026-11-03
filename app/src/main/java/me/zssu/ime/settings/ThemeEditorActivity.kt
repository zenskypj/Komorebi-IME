package me.zssu.ime.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.core.content.ContextCompat
import me.zssu.ime.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.zssu.ime.keyboard.LayoutRepository
import me.zssu.ime.theme.KeyboardTheme
import me.zssu.ime.theme.ZinnaTheme

class ThemeEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val themeId = intent.getStringExtra(EXTRA_THEME_ID)
        setContent { ZinnaTheme { ThemeEditorScreen(initialThemeId = themeId) } }
    }
    companion object {
        const val EXTRA_THEME_ID = "theme_id"
    }
}

private fun Color.toArgbInt(): Int = this.toArgb()
private fun Int.toComposeColor(): Color = Color(this)

private data class ColorProp(val key: String, val get: (KeyboardTheme) -> Int, val set: (KeyboardTheme, Int) -> KeyboardTheme)
private data class DimProp(val key: String, val get: (KeyboardTheme) -> Float, val set: (KeyboardTheme, Float) -> KeyboardTheme, val range: ClosedFloatingPointRange<Float>)

private val COLOR_PROPS = listOf(
    ColorProp("背景", { it.backgroundColor }, { t, v -> t.copy(backgroundColor = v) }),
    ColorProp("キー", { it.keyColor }, { t, v -> t.copy(keyColor = v) }),
    ColorProp("押下キー", { it.keyPressedColor }, { t, v -> t.copy(keyPressedColor = v) }),
    ColorProp("修飾キー", { it.modifierKeyColor }, { t, v -> t.copy(modifierKeyColor = v) }),
    ColorProp("ラベル", { it.labelColor }, { t, v -> t.copy(labelColor = v) }),
    ColorProp("ガイド背景", { it.flickGuideColor }, { t, v -> t.copy(flickGuideColor = v) }),
    ColorProp("ガイド文字", { it.flickGuideLabelColor }, { t, v -> t.copy(flickGuideLabelColor = v) }),
    ColorProp("ガイド選択", { it.flickGuideSelectedLabelColor }, { t, v -> t.copy(flickGuideSelectedLabelColor = v) }),
    ColorProp("候補背景", { it.candidateBackgroundColor }, { t, v -> t.copy(candidateBackgroundColor = v) }),
    ColorProp("候補文字", { it.candidateTextColor }, { t, v -> t.copy(candidateTextColor = v) }),
)

private val DIM_PROPS = listOf(
    DimProp("キー高さ (dp)", { it.keyHeightDp }, { t, v -> t.copy(keyHeightDp = v) }, 32f..96f),
    DimProp("キー間隔 (dp)", { it.keyGapDp }, { t, v -> t.copy(keyGapDp = v) }, 0f..16f),
    DimProp("角丸 (dp)", { it.keyCornerRadiusDp }, { t, v -> t.copy(keyCornerRadiusDp = v) }, 0f..32f),
    DimProp("文字サイズ (sp)", { it.labelSizeSp }, { t, v -> t.copy(labelSizeSp = v) }, 10f..32f),
)

private val SWATCH_COLORS = listOf(
    Color(0xFF1B1C1E), Color(0xFF2E3033), Color(0xFF4A4D52), Color(0xFFECEDEE),
    Color(0xFFFFFFFF), Color(0xFF3D6BD6), Color(0xFFD6DEF0), Color(0xFFE7E9EC),
    Color(0xFF1A2E1A), Color(0xFF1A2332), Color(0xFF2D1F24), Color(0xFF2D2218),
    Color(0xFF1F1A2E), Color(0xFF1A1A1A), Color(0xFF1A2E25), Color(0xFF2E1A1A),
    Color(0xFF8FCB78), Color(0xFF4C7DF0), Color(0xFFF08C7D), Color(0xFFF0A64C),
    Color(0xFFB87DF0), Color(0xFF888888), Color(0xFF50D4C0), Color(0xFFE04C4C),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ThemeEditorScreen(initialThemeId: String?) {
    val context = LocalContext.current
    val repository = remember { LayoutRepository(context) }
    val settings = remember { ImeSettings(context) }

    var theme by remember { mutableStateOf(initialThemeId?.let { repository.loadTheme(it) } ?: KeyboardTheme.Default) }
    var themeId by remember { mutableStateOf(initialThemeId ?: "new_theme") }
    var themeLabel by remember { mutableStateOf(theme.label) }
    var message by remember { mutableStateOf<String?>(null) }
    var editingColorProp by remember { mutableStateOf(COLOR_PROPS.first()) }
    var hue by remember { mutableFloatStateOf(0f) }
    var saturation by remember { mutableFloatStateOf(1f) }
    var lightness by remember { mutableFloatStateOf(0.5f) }

    val currentColor = editingColorProp.get(theme)
    LaunchedEffect(editingColorProp, currentColor) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(currentColor, hsv)
        hue = hsv[0]; saturation = hsv[1]; lightness = 1f - hsv[2]
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("テーマエディタ") },
                navigationIcon = {
                    IconButton(onClick = { (context as? ComponentActivity)?.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // Preview
            Card(shape = RoundedCornerShape(16.dp)) {
                ThemePreview(theme, Modifier.fillMaxWidth().height(180.dp))
            }

            // ID / Label
            OutlinedTextField(value = themeId, onValueChange = { themeId = it },
                label = { Text("テーマID（ファイル名）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = themeLabel, onValueChange = { themeLabel = it },
                label = { Text("表示名") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            HorizontalDivider()

            // Color editor
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("色の編集", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        COLOR_PROPS.forEach { prop ->
                            FilterChip(
                                selected = editingColorProp.key == prop.key,
                                onClick = { editingColorProp = prop },
                                label = { Text(prop.key, style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            )
                        }
                    }

                    val cur = editingColorProp.get(theme).toComposeColor()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(cur).border(2.dp, Color.Gray.copy(alpha = 0.3f), CircleShape))
                        Column(Modifier.weight(1f)) {
                            Text("色相", style = MaterialTheme.typography.labelSmall)
                            Slider(value = hue, onValueChange = {
                                hue = it; theme = editingColorProp.set(theme, Color.hsl(hue, saturation, 1f - lightness).toArgbInt())
                            }, valueRange = 0f..360f)
                            Text("鮮やかさ", style = MaterialTheme.typography.labelSmall)
                            Slider(value = saturation, onValueChange = {
                                saturation = it; theme = editingColorProp.set(theme, Color.hsl(hue, saturation, 1f - lightness).toArgbInt())
                            }, valueRange = 0f..1f)
                            Text("明るさ", style = MaterialTheme.typography.labelSmall)
                            Slider(value = lightness, onValueChange = {
                                lightness = it; theme = editingColorProp.set(theme, Color.hsl(hue, saturation, 1f - lightness).toArgbInt())
                            }, valueRange = 0f..1f)
                        }
                    }

                    Text("プリセット色", style = MaterialTheme.typography.labelSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SWATCH_COLORS.forEach { swatch ->
                            Box(Modifier.size(28.dp).clip(CircleShape).background(swatch).border(1.dp, Color.Gray.copy(alpha = 0.3f), CircleShape)
                                .clickable { editingColorProp?.let { prop -> theme = prop.set(theme, swatch.toArgbInt()) } })
                        }
                    }
                }
            }

            // Dimensions
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("寸法", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    DIM_PROPS.forEach { prop ->
                        Text("${prop.key}  ${"%.0f".format(prop.get(theme))}", style = MaterialTheme.typography.labelSmall)
                        Slider(value = prop.get(theme), onValueChange = { theme = prop.set(theme, it) }, valueRange = prop.range)
                    }
                }
            }

            // Toggles
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("フラットキー", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = theme.flatKeys, onCheckedChange = { theme = theme.copy(flatKeys = it) })
                    }
                }
            }

            // Save
            Button(onClick = {
                if (themeId.isBlank()) { message = "IDを入力してください"; return@Button }
                val saved = theme.copy(id = themeId, label = themeLabel)
                repository.saveTheme(saved)
                settings.activeThemeId = themeId
                message = "$themeId を保存して使用します"
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Save, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("保存して使用")
            }

            message?.let {
                Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Text(it, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    }
}

@Composable
private fun ThemePreview(theme: KeyboardTheme, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val operationIcons = remember(context) {
        mapOf("↶" to R.drawable.ic_ime_undo, "⌫" to R.drawable.ic_ime_backspace,
            "◀" to R.drawable.ic_ime_cursor_left, "▶" to R.drawable.ic_ime_cursor_right,
            "↵" to R.drawable.ic_ime_enter).mapValues { ContextCompat.getDrawable(context, it.value)?.mutate() }
    }
    val measurer = rememberTextMeasurer()
    val labelStyle = remember { TextStyle(fontSize = 11.sp) }
    Canvas(modifier.clip(RoundedCornerShape(8.dp))) {
        val w = size.width; val h = size.height
        val bgColor = Color(theme.backgroundColor)
        val keyColor = Color(theme.keyColor)
        val modColor = Color(theme.modifierKeyColor)
        val pressedColor = Color(theme.keyPressedColor)
        val labelColor = Color(theme.labelColor)
        val candBg = Color(theme.candidateBackgroundColor)
        val candText = Color(theme.candidateTextColor)
        val guideBg = Color(theme.flickGuideColor)

        drawRect(bgColor, Offset.Zero, Size(w, h))
        val stripH = h * 0.14f
        drawRect(candBg, Offset(0f, 0f), Size(w, stripH))
        val candResult = measurer.measure("変換  候補1  候補2  候補3", labelStyle, maxLines = 1)
        drawText(candResult, topLeft = Offset(w * 0.04f, stripH / 2f - candResult.size.height / 2f), color = candText)

        val keyTop = stripH + 4f; val keyH = (h - keyTop) / 4f; val gap = 2f; val keyW = (w - gap * 5f) / 4f
        val keys = listOf(
            listOf("↶" to true, "あ" to false, "か" to false, "⌫" to true),
            listOf("◀" to true, "た" to false, "な" to false, "▶" to true),
            listOf("数字" to true, "ま" to false, "や" to false, "空白" to true),
            listOf("英数" to true, "゛" to true, "わ" to false, "↵" to true),
        )
        for (ri in 0 until 4) for (ci in 0 until 4) {
            val x = gap + ci * (keyW + gap); val y = keyTop + ri * keyH
            val fillColor = if (keys[ri][ci].second) modColor else if (ri == 3 && ci == 0) pressedColor else keyColor
            drawRoundRect(fillColor, Offset(x, y), Size(keyW, keyH - gap), CornerRadius(theme.keyCornerRadiusDp * 4f))
            val icon = operationIcons[keys[ri][ci].first]
            if (icon != null) {
                val iconSize = minOf(keyW, keyH - gap) * 0.42f
                val centerX = x + keyW / 2f
                val centerY = y + (keyH - gap) / 2f
                icon.setTint(theme.labelColor)
                icon.setBounds((centerX - iconSize / 2).toInt(), (centerY - iconSize / 2).toInt(),
                    (centerX + iconSize / 2).toInt(), (centerY + iconSize / 2).toInt())
                drawIntoCanvas { icon.draw(it.nativeCanvas) }
            } else {
                val tr = measurer.measure(keys[ri][ci].first, labelStyle, maxLines = 1)
                drawText(tr, topLeft = Offset(x + keyW / 2f - tr.size.width / 2f, y + (keyH - gap) / 2f - tr.size.height / 2f), color = labelColor)
            }
        }
        val gx = gap + keyW + gap; val gy = keyTop
        drawRoundRect(guideBg.copy(alpha = 0.4f), Offset(gx, gy - keyH), Size(keyW, keyH * 2), CornerRadius(theme.keyCornerRadiusDp * 4f))
    }
}
