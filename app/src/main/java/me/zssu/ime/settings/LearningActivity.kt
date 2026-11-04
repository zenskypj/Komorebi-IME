package me.zssu.ime.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.zssu.ime.mozc.MozcEngine
import me.zssu.ime.theme.ZinnaTheme

class LearningActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ZinnaTheme { LearningScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LearningScreen() {
    val context = LocalContext.current
    val diagnostic = remember { context.getSharedPreferences("input_performance", 0) }
    var performanceEnabled by remember { mutableStateOf(diagnostic.getBoolean("enabled", false)) }
    var confirmTtt by remember { mutableStateOf(false) }
    val priority = remember { me.zssu.ime.ime.PriorityCandidateRepository(context) }
    var pinned by remember { mutableStateOf(priority.entries()) }
    var confirmReset by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val settings = remember { ImeSettings(context) }
    var clipboardEnabled by remember { mutableStateOf(settings.clipboardHistoryEnabled) }
    var retention by remember { mutableFloatStateOf(settings.clipboardRetentionMinutes.toFloat()) }
    val statsPrefs = remember { context.getSharedPreferences("input_statistics", 0) }
    var statsEnabled by remember { mutableStateOf(statsPrefs.getBoolean("enabled", false)) }
    var statsCount by remember { mutableStateOf(statsPrefs.getLong("converted_codepoints", 0L)) }
    var toolCounts by remember { mutableStateOf(statsPrefs.all.filterKeys { it.startsWith("tool_") }.mapValues { (it.value as? Long) ?: 0L }) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("変換学習") },
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
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("入力統計", style = MaterialTheme.typography.titleMedium)
                    Text("有効にすると、変換確定した文字数とツールの使用回数を端末内に記録します。入力内容と機密欄は記録しません。")
                    Switch(checked = statsEnabled, onCheckedChange = { statsEnabled = it; statsPrefs.edit().putBoolean("enabled", it).apply() })
                    Text("変換確定: $statsCount 文字")
                    val labels = mapOf("CLIPBOARD" to "クリップボード", "EMOJI" to "絵文字", "ONE_HAND_CYCLE" to "片手モード", "SETTINGS" to "設定", "AI_STYLE" to "文体変換")
                    labels.forEach { (key, label) -> Text("$label: ${toolCounts["tool_$key"] ?: 0L} 回") }
                    OutlinedButton(onClick = {
                        statsCount = 0; toolCounts = emptyMap()
                        val editor = statsPrefs.edit().remove("converted_codepoints")
                        labels.keys.forEach { editor.remove("tool_$it") }
                        editor.apply()
                    }) { Text("統計をリセット") }
                }
            }

            Card(shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("クリップボード履歴", style = MaterialTheme.typography.titleMedium)
                    Text("履歴はアプリを終了すると消えます。機密欄では記録・表示しません。")
                    Switch(checked = clipboardEnabled, onCheckedChange = { clipboardEnabled = it; settings.clipboardHistoryEnabled = it })
                    Text("保持時間: ${retention.toInt()} 分")
                    Slider(value = retention, onValueChange = { retention = it },
                        onValueChangeFinished = { settings.clipboardRetentionMinutes = retention.toInt() },
                        valueRange = 1f..1440f, enabled = clipboardEnabled)
                }
            }

            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("性能診断", style = MaterialTheme.typography.titleMedium)
                    Text("入力処理と AI の所要時間だけを記録します。入力本文は保存しません。")
                    Switch(checked = performanceEnabled, onCheckedChange = { performanceEnabled = it; diagnostic.edit().putBoolean("enabled", it).apply() })
                    for ((key, label) in listOf("key" to "キー処理", "ai" to "AI 推論")) {
                        val count = diagnostic.getLong(key + "_count", 0)
                        val mean = diagnostic.getLong(key + "_total_us", 0) / count.coerceAtLeast(1) / 1000.0
                        Text("$label: $count 回 / 平均 $mean ms / 最大 ${diagnostic.getLong(key + "_max_us", 0) / 1000.0} ms")
                    }
                    OutlinedButton(onClick = { diagnostic.edit().clear().putBoolean("enabled", performanceEnabled).apply() }) { Text("診断記録を消去") }
                }
            }
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("優先する表記", style = MaterialTheme.typography.titleMedium)
                    Text("候補の長押しから表記を登録できます。")
                    for (entry in pinned) OutlinedButton(onClick = { priority.unpin(entry.reading, entry.value); pinned = priority.entries() }) {
                        Text("${entry.reading} → ${entry.value}：優先解除")
                    }
                    OutlinedButton(onClick = { confirmTtt = true }) { Text("zenzai-plus の TTT 学習をリセット") }
                }
            }
            Card(shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("変換学習について", style = MaterialTheme.typography.titleMedium)
                    Text("候補を長押しすると意味を確認でき、削除可能な予測候補では学習削除も選べます。AI学習が有効なときは、確定した読みと表記を文脈あり・なしで学び、確認できる文節の語も別の文章で使えるよう学習します。読みの文字種を変えただけの結果はAI再学習から除きます。", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Update, null, Modifier.size(18.dp))
                        Text("変換学習をすべてリセット")
                    }
                    result?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }

    if (confirmTtt) AlertDialog(
        onDismissRequest = { confirmTtt = false },
        title = { Text("TTT 学習をリセットしますか？") },
        text = { Text("zenzai-plus の学習結果を初期化します。モデルとユーザー辞書は残ります。") },
        confirmButton = { TextButton(onClick = {
            val prefs = context.getSharedPreferences(ImeSettings.NAME, 0)
            prefs.edit().putLong("zenzai_reset_learning", System.currentTimeMillis()).apply()
            confirmTtt = false
        }) { Text("リセット") } },
        dismissButton = { TextButton(onClick = { confirmTtt = false }) { Text("キャンセル") } },
    )
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("変換学習をリセットしますか？") },
            text = { Text("ユーザー辞書は残り、変換履歴と予測履歴だけが削除されます。") },
            confirmButton = {
                TextButton(onClick = {
                    val ok = MozcEngine.get(context)?.clearLearning() == true
                    result = if (ok) "リセットしました" else "リセットに失敗しました"
                    confirmReset = false
                }) { Text("リセット") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("キャンセル") } },
        )
    }
}
