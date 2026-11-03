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

            Card(shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("変換学習について", style = MaterialTheme.typography.titleMedium)
                    Text("候補を長押しすると意味を確認でき、削除可能な予測候補では学習削除も選べます。", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Update, null, Modifier.size(18.dp))
                        Text("変換学習をすべてリセット")
                    }
                    result?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }

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
