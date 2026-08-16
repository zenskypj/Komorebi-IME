package me.zssu.ime.settings

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.zssu.ime.theme.ZinnaTheme

class AppearanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ZinnaTheme { AppearanceScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceScreen() {
    val context = LocalContext.current
    val settings = remember { ImeSettings(context) }
    var pureBlack by remember { mutableStateOf(settings.pureBlack) }
    var opacity by remember { mutableFloatStateOf(settings.backgroundOpacity) }
    var hasImage by remember { mutableStateOf(settings.backgroundImage != null) }
    var selectedPreset by remember { mutableStateOf(settings.backgroundPresetId) }
    var heightScale by remember { mutableFloatStateOf(settings.keyHeightScale) }
    var oneHandMode by remember { mutableStateOf(settings.oneHandMode) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes != null && settings.setBackgroundImage(bytes)) { hasImage = true; selectedPreset = null }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("外観") },
                navigationIcon = {
                    IconButton(onClick = { (context as? ComponentActivity)?.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("ピュアブラック", style = MaterialTheme.typography.bodyLarge)
                            Text("有機EL向け、背景を完全な黒に", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = pureBlack, onCheckedChange = { pureBlack = it; settings.pureBlack = it })
                    }
                }
            }

            Card(shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("キーの高さ  ${(heightScale * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
                    Slider(value = heightScale, onValueChange = { heightScale = it },
                        onValueChangeFinished = { settings.keyHeightScale = heightScale },
                        valueRange = ImeSettings.MIN_KEY_HEIGHT_SCALE..ImeSettings.MAX_KEY_HEIGHT_SCALE)
                }
            }

            Card(shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("片手モード", style = MaterialTheme.typography.titleMedium)
                    Text("候補がないときのツールバーからも切り替えられます", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = oneHandMode == ImeSettings.OneHandMode.LEFT, onClick = {
                            oneHandMode = ImeSettings.OneHandMode.LEFT; settings.oneHandMode = ImeSettings.OneHandMode.LEFT
                        }, label = { Text("左寄せ") }, modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer))
                        FilterChip(selected = oneHandMode == ImeSettings.OneHandMode.OFF, onClick = {
                            oneHandMode = ImeSettings.OneHandMode.OFF; settings.oneHandMode = ImeSettings.OneHandMode.OFF
                        }, label = { Text("全幅") }, modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer))
                        FilterChip(selected = oneHandMode == ImeSettings.OneHandMode.RIGHT, onClick = {
                            oneHandMode = ImeSettings.OneHandMode.RIGHT; settings.oneHandMode = ImeSettings.OneHandMode.RIGHT
                        }, label = { Text("右寄せ") }, modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer))
                    }
                }
            }

            Card(shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("キーボードの背景画像", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ImeSettings.BACKGROUND_PRESETS.forEach { preset ->
                            val thumb = remember(preset.id) {
                                context.assets.open(preset.assetPath).use {
                                    requireNotNull(BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 }))
                                }.asImageBitmap()
                            }
                            Card(onClick = {
                                if (settings.setBackgroundPreset(preset.id)) { selectedPreset = preset.id; hasImage = true }
                            }, modifier = Modifier.width(120.dp), shape = RoundedCornerShape(10.dp)) {
                                Image(thumb, preset.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(60.dp))
                                Text(if (selectedPreset == preset.id) "✓ ${preset.label}" else preset.label,
                                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(6.dp))
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { pickImage.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f)) {
                            Text(if (hasImage) "画像を変更" else "画像を選択")
                        }
                        OutlinedButton(onClick = { settings.clearBackgroundImage(); hasImage = false; selectedPreset = null },
                            enabled = hasImage, modifier = Modifier.weight(1f)) { Text("解除") }
                    }
                    AnimatedVisibility(visible = hasImage) {
                        Column {
                            Text("画像の濃さ  ${(opacity * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                            Slider(value = opacity, onValueChange = { opacity = it },
                                onValueChangeFinished = { settings.backgroundOpacity = opacity }, valueRange = 0f..1f)
                        }
                    }
                }
            }
        }
    }
}
