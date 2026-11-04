package me.zssu.ime.settings

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.zssu.ime.mozc.MozcEngine
import me.zssu.ime.karukan.KarukanPlatform
import me.zssu.ime.theme.ZinnaTheme

class EngineDictActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ZinnaTheme { EngineDictScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EngineDictScreen() {
    val context = LocalContext.current
    val settings = remember { ImeSettings(context) }
    val modelManager = remember { KarukanModelManager.get(context) }
    var useDict by remember { mutableStateOf(settings.useProperNounDictionary) }
    var useAiDict by remember { mutableStateOf(settings.useAiTechDictionary) }
    var engine by remember { mutableStateOf(settings.conversionEngine) }
    var modelState by remember { mutableStateOf(modelManager.currentState()) }
    DisposableEffect(modelManager) {
        val observation = modelManager.observe { modelState = it }
        onDispose { observation.close() }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("変換エンジン・辞書") },
                navigationIcon = {
                    IconButton(onClick = { (context as? ComponentActivity)?.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ZenzaiControls()
            SystemDictionaryControls()
            // Engine selector and optional on-device model
            Card(shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Psychology, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("変換エンジン", style = MaterialTheme.typography.titleMedium)
                    }
                    EngineChoice(
                        title = "自動（おすすめ）",
                        subtitle = "AI候補がないときは導入済みKarukanを使用。未導入ならMozc",
                        selected = engine == ImeSettings.ConversionEngine.AUTO,
                        onClick = {
                            engine = ImeSettings.ConversionEngine.AUTO
                            settings.conversionEngine = engine
                        },
                    )
                    EngineChoice(
                        title = "Mozc",
                        subtitle = "Karukanを使用せず、端末内の辞書と学習履歴で変換",
                        selected = engine == ImeSettings.ConversionEngine.MOZC,
                        onClick = {
                            engine = ImeSettings.ConversionEngine.MOZC
                            settings.conversionEngine = engine
                        },
                    )
                    EngineChoice(
                        title = "Karukan ニューラル変換（実験的）",
                        subtitle = "AI有効時もKarukan候補を生成。変換キーでもKarukanを使用",
                        selected = engine == ImeSettings.ConversionEngine.KARUKAN,
                        enabled = modelState is KarukanModelManager.State.Installed &&
                            KarukanPlatform.isSupported,
                        onClick = {
                            engine = ImeSettings.ConversionEngine.KARUKAN
                            settings.conversionEngine = engine
                        },
                    )
                    KarukanModelControls(
                        state = modelState,
                        supported = KarukanPlatform.isSupported,
                        onInstall = modelManager::install,
                        onCancel = modelManager::cancel,
                        onDelete = {
                            modelManager.delete()
                            engine = ImeSettings.ConversionEngine.MOZC
                            settings.conversionEngine = engine
                        },
                    )
                    if (!KarukanPlatform.isSupported) {
                        Text(
                            "このCPUでは現在Karukanを利用できません。Mozcは引き続き利用できます。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(describeEngine(context, engine, modelState),
                        style = MaterialTheme.typography.bodyMedium)
                    Text(describeDictionaries(context), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Dictionary toggles
            Card(shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("変換辞書の切り替え", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    SwitchRow("有名固有名詞", "同梱の企業名・サービス名・人名・地名。追加取得は不要", useDict) {
                        useDict = it; settings.useProperNounDictionary = it
                        Thread {
                            try { MozcEngine.get(context)?.setBundledDictionaryActive(context, "proper-nouns.txt", it) } catch (_: Exception) {}
                        }.apply { isDaemon = true }.start()
                    }
                    HorizontalDivider()
                    SwitchRow("AI・テクノロジー", "AI企業・技術用語・スタートアップ名", useAiDict) {
                        useAiDict = it; settings.useAiTechDictionary = it
                        Thread {
                            try { MozcEngine.get(context)?.setBundledDictionaryActive(context, "ai-tech-nouns.txt", it) } catch (_: Exception) {}
                        }.apply { isDaemon = true }.start()
                    }
                    HorizontalDivider()
                    SwitchRow("カタカナ語→英語", "読みが完全一致したときだけ候補に表示。誤入力補正には使いません", checked = true, enabled = false) {}
                }
            }
        }
    }
}

@Composable
private fun EngineChoice(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun KarukanModelControls(
    state: KarukanModelManager.State,
    supported: Boolean,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    when (state) {
        KarukanModelManager.State.NotInstalled -> {
            Button(
                onClick = onInstall,
                enabled = supported,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("軽量モデルをダウンロード（約32 MB）")
            }
            Text(
                "Hugging Faceから一度だけ取得します。入力内容は送信されません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is KarukanModelManager.State.Downloading -> {
            val progress = if (state.totalBytes > 0) {
                state.downloadedBytes.toFloat() / state.totalBytes
            } else 0f
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth())
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onCancel) { Text("中止") }
            }
        }
        KarukanModelManager.State.Installed -> {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("モデル導入済み", color = MaterialTheme.colorScheme.primary)
                OutlinedButton(onClick = onDelete) { Text("モデルを削除") }
            }
        }
        is KarukanModelManager.State.Failed -> {
            Text(state.message, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = onInstall,
                enabled = supported,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("ダウンロードを再開")
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

private fun describeDictionaries(context: Context): String {
    val engine = MozcEngine.get(context) ?: return "—"
    val installed = engine.installedDictionaries(context)
    if (installed.isEmpty()) return "読み込み中…"
    return installed.joinToString(", ") { "${it.name} ${"%,d".format(it.entryCount)}語" }
}

private fun describeEngine(
    context: Context,
    selected: ImeSettings.ConversionEngine,
    modelState: KarukanModelManager.State,
): String {
    val engine = MozcEngine.get(context) ?: return "読み込み失敗"
    return when {
        selected == ImeSettings.ConversionEngine.KARUKAN &&
            modelState is KarukanModelManager.State.Installed ->
            "Karukan変換 / 端末内推論 / Mozcかな入力"
        selected == ImeSettings.ConversionEngine.AUTO && modelState is KarukanModelManager.State.Installed ->
            "自動 / AI候補がないときはKarukan / Mozcかな入力"
        else -> "Mozc / オフライン動作中 / 辞書 v${engine.dataVersion.ifEmpty { "unknown" }}"
    }
}
