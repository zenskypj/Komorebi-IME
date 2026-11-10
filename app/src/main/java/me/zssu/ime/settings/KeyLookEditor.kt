package me.zssu.ime.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zssu.ime.theme.KeyAppearance
import me.zssu.ime.theme.KeyFrame
import me.zssu.ime.theme.LocalArt
import me.zssu.ime.theme.ThemeArt

@Composable
internal fun KeyLookEditor(value: KeyAppearance?, update: (KeyAppearance?) -> Unit) {
    val look = value ?: KeyAppearance()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestLook by rememberUpdatedState(look)
    val latestUpdate by rememberUpdatedState(update)
    var message by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { LocalArt.importKey(context, uri) } }
                .onSuccess { latestUpdate(latestLook.copy(imageId = it)) }
                .onFailure { message = it.message ?: "画像を読み込めませんでした" }
        }
    }
    Text("このキーの外観", style = MaterialTheme.typography.titleMedium)
    ChoiceChips(mapOf<KeyFrame?, String>(null to "テーマに従う") + frameNames, look.frame) {
        update(look.copy(frame = it))
    }
    Text("キーの画像")
    TexturePicker(if (look.imageId != null) null else look.texture, includeInherit = true) {
        update(look.copy(texture = it, imageId = null))
    }
    val local = remember(look.imageId) { look.imageId?.let { ThemeArt.loadLocal(context, it)?.asImageBitmap() } }
    if (local != null) {
        Image(local, "選択したキー画像", modifier = Modifier.size(72.dp), contentScale = ContentScale.Crop)
    }
    OutlinedButton(onClick = { picker.launch(arrayOf("image/*")) }) {
        Text(if (look.imageId == null) "端末から画像を選ぶ" else "端末の画像を変更")
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    KeyColorField("キー面の色", look.fillColor) { update(look.copy(fillColor = it)) }
    KeyColorField("文字の色", look.labelColor) { update(look.copy(labelColor = it)) }
    ThemeToggle("キー文字を表示", look.showLabel) { update(look.copy(showLabel = it)) }
    ChoiceChips(mapOf<Boolean?, String>(null to "太さを継承", true to "太字", false to "標準の太さ"), look.labelBold) {
        update(look.copy(labelBold = it))
    }
    ChoiceChips(mapOf<Boolean?, String>(null to "ヒントを継承", true to "ヒント表示", false to "ヒント非表示"),
        look.showLongPressHint) { update(look.copy(showLongPressHint = it)) }
    ThemeToggle("文字サイズを個別に指定", look.labelSizeSp != null) {
        update(look.copy(labelSizeSp = if (it) 17f else null))
    }
    look.labelSizeSp?.let { size ->
        ThemeSlider("文字サイズ", size, 10f..32f, "sp") { update(look.copy(labelSizeSp = it)) }
    }
    TextButton(onClick = { update(null) }) { Text("このキーをテーマの設定に戻す") }
}

@Composable
private fun KeyColorField(label: String, initial: Int?, onChange: (Int?) -> Unit) {
    var text by remember { mutableStateOf(initial?.let { "#%08X".format(it) }.orEmpty()) }
    LaunchedEffect(initial) {
        if (initial != parseThemeColor(text)) text = initial?.let { "#%08X".format(it) }.orEmpty()
    }
    OutlinedTextField(text, {
        text = it.take(9)
        if (text.isBlank()) onChange(null) else parseThemeColor(text)?.let(onChange)
    }, label = { Text(label) }, supportingText = { Text("#RRGGBB / #AARRGGBB。空欄でテーマに従う") },
        singleLine = true, modifier = Modifier.fillMaxWidth())
}
