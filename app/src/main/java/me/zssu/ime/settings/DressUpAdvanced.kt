package me.zssu.ime.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zssu.ime.keyboard.LayoutRepository
import java.io.ByteArrayOutputStream

@Composable
internal fun DressUpAdvanced(
    repo: LayoutRepository,
    theme: Boolean,
    selected: String?,
    revision: Int,
    onImport: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf<String?>(null) }
    val source = remember(selected, theme, revision, expanded) {
        if (!expanded) null else selected?.let { if (theme) repo.exportTheme(it) else repo.exportLayout(it) }
    }
    var json by remember(source) { mutableStateOf(source.orEmpty()) }
    val dir = if (theme) LayoutRepository.THEMES_DIR else LayoutRepository.LAYOUTS_DIR

    fun import(text: String) {
        repo.importJson(text, expectTheme = theme).onSuccess {
            error = null
            onImport(it.id)
        }.onFailure { error = it.message ?: "取り込めませんでした" }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri) ?: error("ファイルを開けません")
                    input.use {
                        val bytes = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val size = it.read(buffer)
                            if (size < 0) break
                            require(bytes.size() + size <= 1024 * 1024) { "JSONは1 MiB以下にしてください" }
                            bytes.write(buffer, 0, size)
                        }
                        bytes.toString(Charsets.UTF_8.name())
                    }
                }
            }.onSuccess(::import).onFailure { error = it.message ?: "読み込めませんでした" }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = exporting
        exporting = null
        if (uri != null && text != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri) ?: error("ファイルを開けません")
                    output.bufferedWriter().use { it.write(text) }
                }
            }.onSuccess { error = null }.onFailure { error = it.message ?: "書き出せませんでした" }
        }
    }

    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "詳細を閉じる" else "詳細・JSON入出力")
    }
    if (expanded) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pick.launch(arrayOf("application/json", "text/*")) }) { Text("取り込み") }
            OutlinedButton(onClick = {
                exporting = source
                export.launch("${selected ?: "komorebi"}.json")
            }, enabled = source != null) { Text("書き出し") }
        }
        OutlinedTextField(json, { json = it }, label = { Text("JSONを編集") },
            minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { import(json) }, enabled = json.isNotBlank() && json != source) { Text("JSONを保存") }
            if (selected != null && repo.hasUserOverride(dir, selected)) {
                TextButton(onClick = { confirmDelete = true }) { Text("保存済みの設定を削除") }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (confirmDelete && selected != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("保存済みの設定を削除しますか？") },
            text = { Text("同じIDの同梱プリセットがある場合は元の内容に戻ります。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    if (repo.deleteOverride(dir, selected)) onRemove(selected) else error = "削除できませんでした"
                }) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}
