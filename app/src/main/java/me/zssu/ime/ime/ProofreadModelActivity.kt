package me.zssu.ime.ime

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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.zssu.ime.theme.ZinnaTheme

/**
 * Full-screen manager for the on-device proofreading models.
 *
 * Kept separate from the review window on purpose: a settings screen has room for the size,
 * licence and progress of each model, while the review window only needs to say whether the
 * selected model is ready.
 */
class ProofreadModelActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ZinnaTheme { ProofreadModelScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProofreadModelScreen() {
    val context = LocalContext.current
    val specs = ProofreadModelStore.specs
    var selectedId by remember { mutableStateOf(ProofreadModelStore.selectedId(context)) }
    val states = remember { mutableStateMapOf<String, ProofreadModelStore.State>() }
    DisposableEffect(context) {
        val subscription = ProofreadModelStore.observe(context) { id, state -> states[id] = state }
        onDispose { subscription.close() }
    }
    val runtimeAvailable = ProofreadNative.available
    val recommendedIndex = remember {
        val memory = android.app.ActivityManager.MemoryInfo().also {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it)
        }
        ProofreadRecommendation.select(memory.availMem, context.noBackupFilesDir.usableSpace)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("AI 文章校正モデル") },
                navigationIcon = {
                    IconButton(onClick = { (context as? ComponentActivity)?.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AutoAwesome, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("端末内のAI文章校正", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        "選択した文章、またはカーソル直前の600文字を、ダウンロードしたモデルで校正します。" +
                            "校正対象の文章と結果は端末内で処理し、ネットワークには送信しません。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "これは変換に使う zenzai-plus のモデルとは別物です。モデルはアプリのバックアップ対象外の領域に保存し、削除できます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!runtimeAvailable) {
                        Text(
                            "この端末では校正ランタイムを利用できません（arm64-v8a 専用）。モデルのダウンロードはできますが校正は実行できません。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            specs.forEachIndexed { index, spec ->
                val state = states[spec.id] ?: ProofreadModelStore.state(context, spec)
                Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = spec.id == selectedId,
                                onClick = {
                                    selectedId = spec.id
                                    ProofreadModelStore.select(context, spec)
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    spec.label + if (index == recommendedIndex) "（おすすめ）" else "",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    if (state is ProofreadModelStore.State.Ready) "取得済み" else "未取得",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (state is ProofreadModelStore.State.Ready) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        when (state) {
                            ProofreadModelStore.State.Missing -> {
                                Button(
                                    onClick = {
                                        ProofreadModelStore.select(context, spec)
                                        selectedId = spec.id
                                        ProofreadModelStore.install(context, spec)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(if (ProofreadModelStore.storedBytes(context, spec) > 0) "ダウンロードを再開" else "ダウンロード")
                                }
                            }
                            is ProofreadModelStore.State.Downloading -> {
                                val fraction = if (state.total > 0) state.bytes.toFloat() / state.total else 0f
                                LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                                    OutlinedButton(onClick = { ProofreadModelStore.cancel() }) { Text("中止") }
                                }
                            }
                            ProofreadModelStore.State.Ready -> {
                                Text("このモデルで校正します。", style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(
                                    onClick = { ProofreadModelStore.delete(context, spec) },
                                    enabled = spec.id != selectedId,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(if (spec.id == selectedId) "選択中のモデルは削除できません" else "モデルを削除")
                                }
                            }
                            is ProofreadModelStore.State.Error -> {
                                Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                Button(
                                    onClick = {
                                        ProofreadModelStore.select(context, spec)
                                        selectedId = spec.id
                                        ProofreadModelStore.install(context, spec)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("再試行")
                                }
                            }
                        }
                        Text(
                            "${spec.repository} · ${spec.license} · Unsloth による GGUF 量子化",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "Qwen3.5 / Qwen3 は Qwen（Alibaba Cloud）、Gemma 4 は Google DeepMind による Apache-2.0 のモデルで、GGUF 量子化は Unsloth によるものです。実行部は llama.cpp（MIT）を使用します。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
