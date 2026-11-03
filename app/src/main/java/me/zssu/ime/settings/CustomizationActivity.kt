package me.zssu.ime.settings

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.zssu.ime.keyboard.LayoutRepository
import me.zssu.ime.theme.MaterialYouTheme
import me.zssu.ime.theme.ZinnaTheme

class CustomizationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ZinnaTheme { CustomizationScreen() } }
    }
}

private enum class ResourceKind { LAYOUT, THEME }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CustomizationScreen() {
    val context = LocalContext.current
    val repository = remember { LayoutRepository(context) }
    val settings = remember { ImeSettings(context) }
    var kind by remember { mutableStateOf(ResourceKind.LAYOUT) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var exportText by remember { mutableStateOf<String?>(null) }
    var jsonExpanded by remember { mutableStateOf(false) }

    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("ファイルを読み込めません")
        }
        text.onSuccess {
            editor = it
            repository.importJson(it).onSuccess { imported ->
                kind = if (imported is LayoutRepository.ImportedResource.Layout) ResourceKind.LAYOUT else ResourceKind.THEME
                selectedId = imported.id
                message = "${imported.id} を取り込みました"
                refresh++
            }.onFailure { error -> message = "取り込み失敗: ${error.message}" }
        }.onFailure { message = "読み込み失敗: ${it.message}" }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = exportText
        if (uri != null && text != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
                    ?: error("保存先を開けません")
            }.onSuccess { message = "書き出しました" }
                .onFailure { message = "書き出し失敗: ${it.message}" }
        }
        exportText = null
    }

    val ids = remember(kind, refresh) {
        if (kind == ResourceKind.LAYOUT) repository.availableLayoutIds()
        else repository.availableThemeIds()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("配列・テーマスタジオ") },
                navigationIcon = {
                    IconButton(onClick = { (context as? ComponentActivity)?.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // Mode selector
            Text("編集する対象", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = kind == ResourceKind.LAYOUT,
                    onClick = { kind = ResourceKind.LAYOUT; selectedId = null; editor = "" },
                    label = { Text("配列") },
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
                FilterChip(
                    selected = kind == ResourceKind.THEME,
                    onClick = { kind = ResourceKind.THEME; selectedId = null; editor = "" },
                    label = { Text("テーマ") },
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                )
            }

            // Preset list
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("プリセット / 作成済み", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ids.forEach { id ->
                            val active = if (kind == ResourceKind.LAYOUT) settings.activeLayoutId == id
                                else settings.activeThemeId == id
                            FilterChip(
                                selected = selectedId == id,
                                onClick = {
                                    selectedId = id
                                    editor = if (kind == ResourceKind.LAYOUT) repository.exportLayout(id).orEmpty()
                                        else repository.exportTheme(id).orEmpty()
                                    message = if (id == MaterialYouTheme.ID) "Material You は動的生成のため編集不可" else "$id を読み込みました"
                                },
                                label = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (active) Icon(Icons.Filled.Check, contentDescription = "使用中", modifier = Modifier.size(16.dp))
                                        Text(id, style = MaterialTheme.typography.labelSmall)
                                    }
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            )
                        }
                    }
                }
            }

            // Actions
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        selectedId?.let {
                            if (kind == ResourceKind.LAYOUT) settings.activeLayoutId = it
                            else settings.activeThemeId = it
                            message = "$it を使用します"
                            refresh++
                        }
                    },
                    enabled = selectedId != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("使用する")
                }
                OutlinedButton(onClick = { importFile.launch(arrayOf("application/json", "text/*")) },
                    modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Download, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("取込")
                }
            }

            // Editor launchers
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { context.startActivity(Intent(context, VisualLayoutEditorActivity::class.java)) },
                    enabled = kind == ResourceKind.LAYOUT && selectedId != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("配列エディタ")
                }
                OutlinedButton(
                    onClick = {
                        val intent = Intent(context, ThemeEditorActivity::class.java).apply {
                            putExtra(ThemeEditorActivity.EXTRA_THEME_ID, selectedId)
                        }
                        context.startActivity(intent)
                    },
                    enabled = kind == ResourceKind.THEME && selectedId != null && selectedId != MaterialYouTheme.ID,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("テーマ編集")
                }
            }

            HorizontalDivider()

            // JSON editor toggle
            FilledTonalButton(
                onClick = { jsonExpanded = !jsonExpanded },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Code, contentDescription = null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (jsonExpanded) "JSONエディタを閉じる" else "JSONエディタを開く")
            }

            AnimatedVisibility(visible = jsonExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = editor,
                        onValueChange = { editor = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                        label = { Text("id を変更すると複製として保存") },
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = {
                                repository.importJson(editor).onSuccess {
                                    kind = if (it is LayoutRepository.ImportedResource.Layout) ResourceKind.LAYOUT else ResourceKind.THEME
                                    selectedId = it.id
                                    message = "${it.id} を保存しました"
                                    refresh++
                                }.onFailure { message = "保存失敗: ${it.message}" }
                            },
                            enabled = editor.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Save, contentDescription = null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("検証保存")
                        }
                        OutlinedButton(
                            onClick = {
                                val id = selectedId ?: return@OutlinedButton
                                exportText = if (kind == ResourceKind.LAYOUT) repository.exportLayout(id)
                                    else repository.exportTheme(id)
                                if (exportText == null) message = "動的テーマは書き出せません"
                                else exportFile.launch("$id.json")
                            },
                            enabled = selectedId != null,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Upload, contentDescription = null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("書出")
                        }
                    }
                }
            }

            // Delete
            OutlinedButton(
                onClick = {
                    val id = selectedId ?: return@OutlinedButton
                    val dir = if (kind == ResourceKind.LAYOUT) LayoutRepository.LAYOUTS_DIR else LayoutRepository.THEMES_DIR
                    if (repository.deleteOverride(dir, id)) {
                        if (kind == ResourceKind.LAYOUT && settings.activeLayoutId == id) settings.activeLayoutId = null
                        if (kind == ResourceKind.THEME && settings.activeThemeId == id) settings.activeThemeId = ImeSettings.DEFAULT_THEME_ID
                        selectedId = null; editor = ""
                        message = "ユーザー版を削除しました"
                        refresh++
                    } else message = "同梱プリセットは削除できません"
                },
                enabled = selectedId?.let {
                    repository.hasUserOverride(
                        if (kind == ResourceKind.LAYOUT) LayoutRepository.LAYOUTS_DIR else LayoutRepository.THEMES_DIR, it
                    )
                } == true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("ユーザー版を削除")
            }

            message?.let {
                Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Text(it, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    }
}
