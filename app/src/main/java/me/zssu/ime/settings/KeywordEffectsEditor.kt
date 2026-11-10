package me.zssu.ime.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zssu.ime.theme.KeyTexture
import me.zssu.ime.theme.KeywordEffect
import me.zssu.ime.theme.LocalArt

@Composable
internal fun KeywordEffectsEditor(effects: List<KeywordEffect>, update: (List<KeywordEffect>) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var keyword by remember { mutableStateOf("") }
    var texture by remember { mutableStateOf(KeyTexture.PLUM) }
    var imageId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { LocalArt.importKey(context, uri) } }
                .onSuccess { imageId = it; error = null }
                .onFailure { error = it.message ?: "画像を読み込めませんでした" }
        }
    }
    Text("入力エフェクト", style = MaterialTheme.typography.titleMedium)
    Text("登録した語を含む変換結果を確定すると、選んだ画像がキーボードに短く表示されます。最大8件。")
    effects.forEachIndexed { index, effect ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(effect.keyword, style = MaterialTheme.typography.titleSmall)
                Text(if (effect.imageId != null) "端末の画像" else effect.texture.label)
                TextButton(onClick = { update(effects.filterIndexed { i, _ -> i != index }) }) { Text("削除") }
            }
        }
    }
    if (effects.size >= 8) return
    OutlinedTextField(keyword, { keyword = it.take(32) }, modifier = Modifier.fillMaxWidth(),
        label = { Text("キーワード") }, singleLine = true)
    ArtPicker(KeyTexture.entries.filter { it.asset != null }, texture, { it.label }, { it.asset }) {
        texture = it
        imageId = null
    }
    OutlinedButton(onClick = { picker.launch(arrayOf("image/*")) }) {
        Text(if (imageId != null) "端末の画像を選択済み" else "端末の画像を選ぶ")
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(onClick = {
        update(effects + KeywordEffect(keyword.trim(), texture, imageId))
        keyword = ""
        imageId = null
    }, enabled = keyword.isNotBlank() && effects.none { it.keyword == keyword.trim() }) { Text("追加") }
}
