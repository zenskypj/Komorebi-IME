package me.zssu.ime.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.zssu.ime.mozc.SystemDictionaryManager
import java.util.Locale

@Composable
internal fun SystemDictionaryControls() {
    val context = LocalContext.current
    val manager = remember { SystemDictionaryManager.get(context) }
    var state by remember { mutableStateOf(manager.currentState()) }
    var notice by remember { mutableStateOf<SystemDictionaryManager.Pack?>(null) }
    DisposableEffect(manager) {
        val observer = manager.observe { state = it }
        onDispose { observer.close() }
    }
    fun size(bytes: Long) = String.format(Locale.JAPAN, "%.1f MiB", bytes / 1048576.0)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("追加システム辞書", style = MaterialTheme.typography.titleMedium)
            Text("追加語も文中の変換に使う、Mozcデータ全体の切り替えです。一度に1種類を使用します。取得はボタンを押したときだけ行います。", style = MaterialTheme.typography.bodyMedium)
            Text("入力中は切り替えを待ちます。個人辞書・学習履歴は引き継ぎます。", style = MaterialTheme.typography.bodySmall)
            Text(state.message.ifEmpty { "標準辞書を使用します" }, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { manager.select("") }, enabled = state.requested.isNotEmpty()) {
                Text(if (state.applied == "" && state.requested.isEmpty()) "標準辞書を使用中" else "標準辞書に戻す")
            }
            manager.packs.forEach { pack ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(pack.name, style = MaterialTheme.typography.titleSmall)
                        Text("${pack.version} · ${pack.license}", style = MaterialTheme.typography.bodySmall)
                        Text("取得 ${size(pack.compressedBytes)} / 保存 ${size(pack.dataBytes)}", style = MaterialTheme.typography.bodySmall)
                        Text("導入時の追加空き容量 ${size(pack.compressedBytes + pack.dataBytes + 32L * 1024 * 1024)}", style = MaterialTheme.typography.bodySmall)
                        val installed = manager.installed(pack)
                        val compatible = manager.compatible(pack)
                        Text(when {
                            !compatible -> "このアプリのMozcとは互換性がありません"
                            state.applied == pack.id && state.requested == pack.id -> "使用中"
                            state.requested == pack.id -> "切り替え待ち"
                            state.applied == pack.id -> "標準辞書への切り替え待ち"
                            installed -> "導入済み"
                            else -> "未導入"
                        }, style = MaterialTheme.typography.bodySmall)
                        if (state.downloading == pack.id) {
                            LinearProgressIndicator(progress = { (state.received.toFloat() / pack.compressedBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Text("${size(state.received)} / ${size(pack.compressedBytes)}", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (installed) {
                                Button(onClick = { manager.select(pack.id) }, enabled = compatible && state.requested != pack.id) { Text("使用する") }
                                OutlinedButton(onClick = { manager.delete(pack) }, enabled = state.downloading == null) { Text("削除") }
                            } else {
                                Button(onClick = { manager.download(pack) }, enabled = compatible && state.downloading == null) { Text("取得する") }
                            }
                        }
                        TextButton(onClick = { notice = pack }) { Text("出典・ライセンス・収録方針") }
                    }
                }
            }
            Text("入力内容・個人辞書・学習履歴を取得先へ送信しません。ダウンロード先はGitHubです。", style = MaterialTheme.typography.bodySmall)
        }
    }
    notice?.let { pack ->
        val text = remember(pack.id) { manager.notice(pack) }
        AlertDialog(onDismissRequest = { notice = null }, title = { Text(pack.name) },
            text = { Text(text, modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { notice = null }) { Text("閉じる") } })
    }
}
