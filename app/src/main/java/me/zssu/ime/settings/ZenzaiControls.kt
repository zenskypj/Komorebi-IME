package me.zssu.ime.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.zssu.ime.ime.ZenzPlusModel
import me.zssu.ime.ime.ZenzaiNative
import me.zssu.ime.ime.Zenz32Model
import me.zssu.ime.ime.Zenz32Native
import me.zssu.ime.ime.ZenzaiEngine

@Composable
internal fun ZenzaiControls() {
    val context = LocalContext.current
    val settings = remember { ImeSettings(context) }
    var enabled by remember { mutableStateOf(settings.zenzaiPlusEnabled) }
    var alongside by remember { mutableStateOf(settings.zenzaiMozcEnabled) }
    var state by remember { mutableStateOf(Zenz32Model.state(context)) }
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
        val subscription = Zenz32Model.observe(context) { state = it }
        onDispose { subscription.close() }
    }
    DisposableEffect(context) {
        val subscription = ZenzPlusModel.observe(context) { plusState = it }
        onDispose { subscription.close() }
    }
    val downloaded = state is Zenz32Model.State.Ready
    val plusReady = plusState is ZenzPlusModel.State.Ready
    val available = ZenzaiEngine.isBundled(context)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("zenz（実験的）", style = MaterialTheme.typography.titleMedium)
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
                    Text("zenzai-plus を優先します。変更後は変換をオフ・オンして切り替えてください。")
                    OutlinedButton(onClick = { ZenzPlusModel.delete(context) }, enabled = !enabled) { Text("zenzai-plus のモデルを削除") }
                }
            }

            when (val current = state) {
                Zenz32Model.State.Missing -> {
                    Text(if (Zenz32Native.available) "zenz v3.2 モデルを Hugging Face から取得します。入力内容は送信されません。" else "この端末では zenz v3.2 ランタイムを利用できません。")
                    Button(onClick = { Zenz32Model.install(context) }, enabled = Zenz32Native.available) { Text(if (Zenz32Model.storedBytes(context) > 0) "ダウンロードを再開" else "モデルをダウンロード（約 70 MiB）") }
                    if (Zenz32Model.storedBytes(context) > 0) {
                        Text("保存済み: ${Zenz32Model.storedBytes(context) / (1024 * 1024)} MiB")
                        OutlinedButton(onClick = { Zenz32Model.delete(context) }) { Text("途中のデータを削除") }
                    }
                }
                is Zenz32Model.State.Downloading -> {
                    val progress = (current.bytes.toFloat() / 73871936f).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("ダウンロード中 ${(progress * 100).toInt()}%")
                    OutlinedButton(onClick = { Zenz32Model.cancel() }) { Text("中断") }
                }
                is Zenz32Model.State.Error -> {
                    Text(current.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { Zenz32Model.install(context) }, enabled = Zenz32Native.available) { Text("再試行") }
                }
                Zenz32Model.State.Ready -> {
                    Text("zenz v3.2: ${Zenz32Model.storedBytes(context) / (1024 * 1024)} MiB。v3.2 自体は学習に対応していません。")
                    OutlinedButton(onClick = { Zenz32Model.delete(context) }, enabled = !enabled) { Text("モデルを削除") }
                    if (enabled) Text("削除するには先に zenz 変換をオフにしてください。", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("zenz 変換を使用")
            Switch(checked = enabled && available, enabled = available, onCheckedChange = {
                enabled = it; settings.zenzaiPlusEnabled = it
            })
            if (enabled && available) {
                Text("Mozc候補を併用")
                Switch(checked = alongside, onCheckedChange = { alongside = it; settings.zenzaiMozcEnabled = it })
                if (plusReady || !downloaded) {
                    OutlinedButton(onClick = {
                        prefs.edit().putLong("zenzai_train_dictionary", prefs.getLong("zenzai_train_dictionary", 0) + 1).apply()
                    }) { Text("ユーザー辞書を学習") }
                    Text("TTT更新: $updates / 学習済み辞書語: $trained")
                }
            }
        }
    }
}
