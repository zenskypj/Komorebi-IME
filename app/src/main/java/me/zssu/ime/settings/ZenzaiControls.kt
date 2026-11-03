package me.zssu.ime.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.zssu.ime.ime.ZenzPlusModel
import me.zssu.ime.ime.ZenzaiNative
import me.zssu.ime.ime.ZenzaiEngine

@Composable
internal fun ZenzaiControls() {
    val context = LocalContext.current
    val settings = remember { ImeSettings(context) }
    var enabled by remember { mutableStateOf(settings.zenzaiPlusEnabled) }
    var plusState by remember { mutableStateOf(ZenzPlusModel.state(context)) }
    val prefs = remember { context.getSharedPreferences(ImeSettings.NAME, 0) }
    var updates by remember { mutableLongStateOf(prefs.getLong("zenzai_ttt_updates", 0)) }
    var trained by remember { mutableIntStateOf(prefs.getInt("zenzai_dictionary_trained", 0)) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            updates = prefs.getLong("zenzai_ttt_updates", 0)
            trained = prefs.getInt("zenzai_dictionary_trained", 0)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    DisposableEffect(context) {
        val subscription = ZenzPlusModel.observe(context) { plusState = it }
        onDispose { subscription.close() }
    }
    val available = ZenzaiEngine.isBundled(context)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("zenzai-plus（TTT と辞書学習）", style = MaterialTheme.typography.titleMedium)
            when (val current = plusState) {
                ZenzPlusModel.State.Missing -> {
                    Text("モデルを Hugging Face から取得します（約 183 MiB、CC BY-SA 4.0）。")
                    Button(onClick = { ZenzPlusModel.install(context) }, enabled = ZenzaiNative.available) {
                        Text(if (ZenzPlusModel.storedBytes(context) > 0) "zenzai-plus を再開" else "zenzai-plus をダウンロード")
                    }
                    if (!ZenzaiNative.available) Text("この端末では学習ランタイムを利用できません。")
                }
                is ZenzPlusModel.State.Downloading -> {
                    val progress = (current.bytes.toFloat() / 192132704f).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("zenzai-plus: ${(progress * 100).toInt()}%")
                    OutlinedButton(onClick = { ZenzPlusModel.cancel() }) { Text("中断") }
                }
                is ZenzPlusModel.State.Error -> {
                    Text(current.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { ZenzPlusModel.install(context) }, enabled = ZenzaiNative.available) { Text("再試行") }
                }
                ZenzPlusModel.State.Ready -> {
                    Text("zenzai-plus のモデルを準備しました。")
                    OutlinedButton(onClick = { ZenzPlusModel.delete(context) }, enabled = !enabled) { Text("zenzai-plus のモデルを削除") }
                }
            }

            Text("zenzai-plus 変換を使用")
            Switch(checked = enabled && available, enabled = available, onCheckedChange = {
                enabled = it; settings.zenzaiPlusEnabled = it
            })
            if (enabled && available) {
                Text("文節の区切りと候補選択には Mozc を使用します。")
                var autoLearning by remember { mutableStateOf(prefs.getBoolean("zenzai_auto_learning_enabled", true)) }
                Text("入力から自動学習（確定した読み・表記と直前の文脈）")
                Switch(checked = autoLearning, onCheckedChange = {
                    autoLearning = it
                    prefs.edit().putBoolean("zenzai_auto_learning_enabled", it).apply()
                })
                Text("学習は端末内で行います。オフにすると未処理の入力は学習しません。進行中の学習は完了する場合があります。", style = MaterialTheme.typography.bodySmall)
                run {
                    OutlinedButton(onClick = {
                        prefs.edit().putLong("zenzai_train_dictionary", prefs.getLong("zenzai_train_dictionary", 0) + 1).apply()
                    }) { Text("ユーザー辞書を学習") }
                    Text("TTT更新: $updates / 学習済み辞書語: $trained")
                }
            }
        }
    }
}
