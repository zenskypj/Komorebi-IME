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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.zssu.ime.theme.ZinnaTheme
import me.zssu.ime.theme.BackgroundFilter
import androidx.compose.ui.viewinterop.AndroidView
import me.zssu.ime.keyboard.KeyboardPanelView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var mediaRevision by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf(settings.backgroundFilter) }
    var brightness by remember { mutableFloatStateOf(settings.backgroundBrightness) }
    var blur by remember { mutableFloatStateOf(settings.backgroundBlur) }
    var horizontal by remember { mutableFloatStateOf(settings.backgroundHorizontal) }
    var vertical by remember { mutableFloatStateOf(settings.backgroundVertical) }
    var videoStart by remember { mutableFloatStateOf(settings.backgroundVideoStart) }
    var videoEnd by remember { mutableFloatStateOf(settings.backgroundVideoEnd) }
    var slideSeconds by remember { mutableFloatStateOf(settings.backgroundSlideSeconds.toFloat()) }
    var mediaMessage by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val photo = remember(mediaRevision) { settings.backgroundImage }
    val album = remember(mediaRevision) { settings.backgroundAlbum }
    val video = remember(mediaRevision) { settings.backgroundVideo }
    val hasImage = photo != null || album.isNotEmpty() || video != null
    var selectedPreset by remember { mutableStateOf(settings.backgroundPresetId) }
    var heightScale by remember { mutableFloatStateOf(settings.keyHeightScale) }
    var oneHandMode by remember { mutableStateOf(settings.oneHandMode) }
    var oneHandWidth by remember { mutableFloatStateOf(settings.oneHandWidth) }
    var candidateScale by remember { mutableFloatStateOf(settings.candidateTextScale) }
    var candidateRows by remember { mutableStateOf(settings.candidateRows) }

    fun importMedia(task: () -> Unit) {
        if (importing) return
        importing = true
        mediaMessage = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { task() } }
                .onSuccess {
                    mediaRevision++
                    selectedPreset = settings.backgroundPresetId
                    videoStart = settings.backgroundVideoStart
                    videoEnd = settings.backgroundVideoEnd
                }
                .onFailure { mediaMessage = it.message ?: "背景を保存できませんでした" }
            importing = false
        }
    }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importMedia { settings.setBackgroundPhotos(uris) }
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importMedia { settings.setBackgroundVideo(uri) }
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
                    Text("片手モードの幅 ${(oneHandWidth * 100).toInt()}%")
                    Slider(value = oneHandWidth, onValueChange = { oneHandWidth = it },
                        onValueChangeFinished = { settings.oneHandWidth = oneHandWidth }, valueRange = 0.6f..1f)
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
                    Text("候補の行数")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..2).forEach { count ->
                            FilterChip(selected = candidateRows == count, onClick = { candidateRows = count; settings.candidateRows = count }, label = { Text("${count}行") })
                        }
                    }
                    Text("候補の文字サイズ ${(candidateScale * 100).toInt()}%")
                    Slider(value = candidateScale, onValueChange = { candidateScale = it },
                        onValueChangeFinished = { settings.candidateTextScale = candidateScale }, valueRange = 0.8f..1.5f)
                }
            }

            Card(shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("キーボードの背景", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ImeSettings.BACKGROUND_PRESETS.forEach { preset ->
                            val thumb = remember(preset.id) {
                                context.assets.open(preset.assetPath).use {
                                    requireNotNull(BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 }))
                                }.asImageBitmap()
                            }
                            Card(onClick = {
                                importMedia { check(settings.setBackgroundPreset(preset.id)) { "画像を読み込めませんでした" } }
                            }, modifier = Modifier.width(120.dp), shape = RoundedCornerShape(10.dp)) {
                                Image(thumb, preset.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(60.dp))
                                Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (selectedPreset == preset.id) Icon(Icons.Filled.Check, contentDescription = "選択中", modifier = Modifier.size(16.dp))
                                    Text(preset.label, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickImages.launch(arrayOf("image/*")) }, enabled = !importing,
                            modifier = Modifier.weight(1f)) { Text("写真（1〜4枚）") }
                        OutlinedButton(onClick = { pickVideo.launch(arrayOf("video/*")) }, enabled = !importing,
                            modifier = Modifier.weight(1f)) { Text("動画") }
                    }
                    if (importing) Text("取り込み中…")
                    mediaMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    AnimatedVisibility(visible = hasImage) {
                        Column {
                            Text(if (video != null) "動画：無音でループ再生" else if (album.size > 1) "${album.size}枚の写真を順番に表示" else "写真・イラスト",
                                style = MaterialTheme.typography.bodySmall)
                            androidx.compose.runtime.key(mediaRevision) {
                                AndroidView(factory = { KeyboardPanelView(it) }, modifier = Modifier.fillMaxWidth().height(122.dp),
                                    update = { panel ->
                                        panel.setBackgroundColor(android.graphics.Color.DKGRAY)
                                        panel.setMedia(photo, album, video, opacity, brightness, blur, horizontal, vertical,
                                            slideSeconds.toInt(), videoStart, videoEnd, filter)
                                    })
                            }
                            Text("背景の濃さ  ${(opacity * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                            Slider(opacity, { opacity = it }, onValueChangeFinished = { settings.backgroundOpacity = opacity })
                            Text("背景フィルター", style = MaterialTheme.typography.bodySmall)
                            ChoiceChips(BackgroundFilter.entries.associateWith { it.label }, filter) {
                                filter = it; settings.backgroundFilter = it
                            }
                            Text("背景の明るさ  ${(brightness * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                            Slider(brightness, { brightness = it }, onValueChangeFinished = { settings.backgroundBrightness = brightness },
                                valueRange = 0.25f..1.5f)
                            if (video != null) {
                                Text("動画の開始位置 ${(videoStart * 100).toInt()}%")
                                var startDraft by remember(videoStart) { mutableFloatStateOf(videoStart) }
                                Slider(startDraft, { startDraft = minOf(it, videoEnd) }, onValueChangeFinished = {
                                    videoStart = startDraft; settings.backgroundVideoStart = startDraft
                                })
                                Text("動画の終了位置 ${(videoEnd * 100).toInt()}%")
                                var endDraft by remember(videoEnd) { mutableFloatStateOf(videoEnd) }
                                Slider(endDraft, { endDraft = maxOf(it, videoStart) }, onValueChangeFinished = {
                                    videoEnd = endDraft; settings.backgroundVideoEnd = endDraft
                                })
                            }
                            if (video == null || android.os.Build.VERSION.SDK_INT >= 31) {
                                Text("ぼかし", style = MaterialTheme.typography.bodySmall)
                                Slider(blur, { blur = it }, onValueChangeFinished = { settings.backgroundBlur = blur }, valueRange = 0f..20f)
                            }
                            Text("横位置", style = MaterialTheme.typography.bodySmall)
                            Slider(horizontal, { horizontal = it }, onValueChangeFinished = { settings.backgroundHorizontal = horizontal })
                            Text("縦位置", style = MaterialTheme.typography.bodySmall)
                            Slider(vertical, { vertical = it }, onValueChangeFinished = { settings.backgroundVertical = vertical })
                            if (album.size > 1) {
                                Text("切り替え間隔 ${slideSeconds.toInt()}秒", style = MaterialTheme.typography.bodySmall)
                                Slider(slideSeconds, { slideSeconds = it }, onValueChangeFinished = {
                                    settings.backgroundSlideSeconds = slideSeconds.toInt()
                                }, valueRange = 5f..120f)
                            }
                            OutlinedButton(onClick = { settings.clearBackgroundImage(); mediaRevision++; selectedPreset = null },
                                enabled = hasImage && !importing) { Text("選んだ背景を解除") }
                        }
                    }
                }
            }
        }
    }
}
