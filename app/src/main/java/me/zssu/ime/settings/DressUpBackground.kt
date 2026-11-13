package me.zssu.ime.settings

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import me.zssu.ime.theme.BackgroundFilter
import me.zssu.ime.keyboard.KeyboardPanelView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import java.io.File

internal data class DressUpMedia(
    val revision: Int,
    val photo: File?,
    val album: List<File>,
    val video: File?,
    val opacity: Float,
    val brightness: Float,
    val blur: Float,
    val horizontal: Float,
    val vertical: Float,
    val slideSeconds: Int,
    val videoStart: Float,
    val videoEnd: Float,
    val filter: BackgroundFilter,
) {
    val hasImage get() = photo != null || album.isNotEmpty() || video != null

    fun render(panel: KeyboardPanelView) {
        panel.setMedia(photo, album, video, opacity, brightness, blur, horizontal, vertical,
            slideSeconds, videoStart, videoEnd, filter)
    }
}

internal class DressUpBackground(
    val media: DressUpMedia,
    val busy: Boolean,
    val controls: @Composable () -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberDressUpBackground(settings: ImeSettings, onImageColor: (Int) -> Unit): DressUpBackground {
    val context = LocalContext.current
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
    var selectedPreset by remember { mutableStateOf(settings.backgroundPresetId) }
    fun importMedia(task: () -> Unit) {
        if (importing) return
        importing = true
        mediaMessage = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                task()
                (settings.backgroundImage ?: settings.backgroundAlbum.firstOrNull() ?: settings.backgroundVideo)
                    ?.let { runCatching { DressUpColors.fromFile(it) }.getOrNull() }
            } }
                .onSuccess { color ->
                    if (color != null) onImageColor(color)
                    else mediaMessage = "画像は設定しましたが、配色を読み取れませんでした"
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

    val photo = remember(mediaRevision) { settings.backgroundImage }
    val album = remember(mediaRevision) { settings.backgroundAlbum }
    val video = remember(mediaRevision) { settings.backgroundVideo }
    val snapshot = DressUpMedia(mediaRevision, photo, album, video, opacity, brightness, blur,
        horizontal, vertical, slideSeconds.toInt(), videoStart, videoEnd, filter)
    val hasImage = snapshot.hasImage
    return DressUpBackground(snapshot, importing) {
        Text("選んだ写真・動画はすぐ背景に反映されます。", style = MaterialTheme.typography.bodySmall)
        Card(shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("キーボードの背景", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickImages.launch(arrayOf("image/*")) }, enabled = !importing,
                        modifier = Modifier.weight(1f)) { Text("アルバムから選ぶ") }
                    OutlinedButton(onClick = { pickVideo.launch(arrayOf("video/*")) }, enabled = !importing) { Text("動画") }
                }
                Text("画像を選ぶと文字やキーの色をまとめて合わせます。後から配色を変更できます。", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val custom = remember(mediaRevision) { settings.customBackgroundPreview }
                    if (custom != null) {
                        val thumb by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, custom, mediaRevision) {
                            value = withContext(Dispatchers.IO) {
                                if (custom.extension.equals("mp4", true)) null else
                                    BitmapFactory.decodeFile(custom.absolutePath, BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap()
                            }
                        }
                        Card(onClick = { importMedia { check(settings.selectCustomBackground()) { "画像を読み込めませんでした" } } },
                            modifier = Modifier.width(120.dp), shape = RoundedCornerShape(10.dp)) {
                            if (thumb != null) Image(thumb!!, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(64.dp))
                            else androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) { Text("動画") }
                            Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (selectedPreset == null && hasImage) Icon(Icons.Filled.Check, contentDescription = "選択中", modifier = Modifier.size(16.dp))
                                Text("自分の画像・動画", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    ImeSettings.BACKGROUND_PRESETS.forEach { preset ->
                        val thumb = remember(preset.id) {
                            context.assets.open(preset.assetPath).use {
                                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 })
                            }?.asImageBitmap()
                        }
                        Card(onClick = {
                            importMedia { check(settings.setBackgroundPreset(preset.id)) { "画像を読み込めませんでした" } }
                        }, modifier = Modifier.width(120.dp), shape = RoundedCornerShape(10.dp)) {
                            thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(64.dp)) }
                            Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (selectedPreset == preset.id) Icon(Icons.Filled.Check, contentDescription = "選択中", modifier = Modifier.size(16.dp))
                                Text(preset.label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                if (settings.customBackgroundPreview != null) {
                    androidx.compose.material3.TextButton(onClick = {
                        settings.deleteCustomBackground(); mediaRevision++; selectedPreset = settings.backgroundPresetId
                    }, enabled = !importing) { Text("自分の画像・動画を一覧から削除") }
                }
                if (importing) Text("取り込み中…")
                mediaMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                AnimatedVisibility(visible = hasImage) {
                    Column {
                        Text(if (video != null) "動画：無音でループ再生" else if (album.size > 1) "${album.size}枚の写真を順番に表示" else "写真・イラスト",
                            style = MaterialTheme.typography.bodySmall)
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
