package me.zssu.ime.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ArrowBack as LeftArrow
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.NavigateNext
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import me.zssu.ime.keyboard.OperationKeyIcons
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.zssu.ime.keyboard.KeyAction
import me.zssu.ime.keyboard.KeyOutput
import me.zssu.ime.keyboard.KeySpec
import me.zssu.ime.keyboard.KeyStyle
import me.zssu.ime.keyboard.KeyboardLayout
import me.zssu.ime.keyboard.LayoutRepository
import me.zssu.ime.theme.ZinnaTheme
import me.zssu.ime.theme.MaterialYouTheme

private data class ActionChoice(val type: String, val label: String)
private val ACTION_CHOICES = listOf(
    ActionChoice("input", "文字入力 (Input)"), ActionChoice("backspace", "バックスペース"),
    ActionChoice("enter", "Enter（改行・確定）"), ActionChoice("space", "スペース"),
    ActionChoice("convert", "変換"), ActionChoice("cursor", "カーソル移動"),
    ActionChoice("undo", "元に戻す"), ActionChoice("modify", "゛小゜"),
    ActionChoice("layout", "配列切替"), ActionChoice("shift", "Shift"),
    ActionChoice("symbol", "記号直接入力"), ActionChoice("ime_picker", "IME切替"),
)

class VisualLayoutEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_LAYOUT_ID)
        setContent { ZinnaTheme { LayoutEditorScreen(id) } }
    }

    companion object {
        const val EXTRA_LAYOUT_ID = "layout_id"
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LayoutEditorScreen(initialId: String?) {
    val context = LocalContext.current
    val repository = remember { LayoutRepository(context) }
    val settings = remember { ImeSettings(context) }
    var refresh by remember { mutableIntStateOf(0) }
    var layout by remember(initialId) { mutableStateOf(initialId?.let(repository::loadLayout)) }
    var rowIndex by remember { mutableIntStateOf(-1) }
    var keyIndex by remember { mutableIntStateOf(-1) }
    var message by remember { mutableStateOf(if (layout == null) "配列を選択してください" else "キーを選択してください") }
    val ids = remember(refresh) { repository.availableLayoutIds() }
    val previewTheme = remember { repository.loadTheme(settings.activeThemeId) ?: MaterialYouTheme.create(context) }
    var showPreview by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("配列エディタ") },
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
                .padding(horizontal = 12.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // Layout selector
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ids.forEach { id ->
                    FilterChip(
                        selected = layout?.id == id,
                        onClick = {
                            layout = repository.loadLayout(id); rowIndex = -1; keyIndex = -1
                            message = "$id を読み込みました"
                        },
                        label = { Text(id, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }

            layout?.let { current ->
                Text(current.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                ThemeToggle("キーボードのプレビュー", showPreview) { showPreview = it }
                if (showPreview) ThemeKeyboardPreview(previewTheme, current)

                // Key grid
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        current.rows.forEachIndexed { r, keyRow ->
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                keyRow.keys.forEachIndexed { k, key ->
                                    FilterChip(
                                        selected = r == rowIndex && k == keyIndex,
                                        onClick = { rowIndex = r; keyIndex = k },
                                        label = {
                                            val icon = OperationKeyIcons.resource(key.center.action, key.faceLabel)
                                            if (icon != null) Icon(painterResource(icon), contentDescription = OperationKeyIcons.description(key.center.action, key.faceLabel), modifier = Modifier.size(18.dp))
                                            else Text(key.faceLabel, maxLines = 1, style = MaterialTheme.typography.labelSmall)
                                        },
                                        modifier = Modifier.width((52 * key.weight).dp),
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }

                val selected = current.rows.getOrNull(rowIndex)?.keys?.getOrNull(keyIndex)
                if (selected != null) {
                    Card(shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("選択中: ", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                val icon = OperationKeyIcons.resource(selected.center.action, selected.faceLabel)
                                if (icon != null) Icon(painterResource(icon), contentDescription = OperationKeyIcons.description(selected.center.action, selected.faceLabel), modifier = Modifier.size(18.dp))
                                else Text(selected.faceLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            }

                            key(current.id, rowIndex, keyIndex) {
                                var editAppearance by remember { mutableStateOf(false) }
                                OutlinedButton(onClick = { editAppearance = !editAppearance }) {
                                    Text(if (editAppearance) "外観の編集を閉じる" else "このキーの外観を編集")
                                }
                                if (editAppearance) KeyLookEditor(selected.appearance) { look ->
                                    layout = updateSelected(current, rowIndex, keyIndex) { it.copy(appearance = look) }
                                }
                            }

                            // Action type
                            Text("アクション種別", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            var actionExpanded by remember { mutableStateOf(false) }
                            val currentType = actionType(selected.center)
                            ExposedDropdownMenuBox(expanded = actionExpanded, onExpandedChange = { actionExpanded = it }) {
                                OutlinedTextField(
                                    value = ACTION_CHOICES.firstOrNull { it.type == currentType }?.label ?: currentType,
                                    onValueChange = {}, readOnly = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = actionExpanded) },
                                    modifier = Modifier
                                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                        .fillMaxWidth(),
                                    textStyle = MaterialTheme.typography.bodySmall,
                                )
                                ExposedDropdownMenu(expanded = actionExpanded, onDismissRequest = { actionExpanded = false }) {
                                    ACTION_CHOICES.forEach { choice ->
                                        DropdownMenuItem(text = { Text(choice.label) }, onClick = {
                                            actionExpanded = false; layout = replaceAction(current, rowIndex, keyIndex, choice.type)
                                        })
                                    }
                                }
                            }

                            // Action-specific fields
                            when (val action = selected.center.action) {
                                is KeyAction.Input -> OutlinedTextField(
                                    value = action.text, onValueChange = { text ->
                                        layout = updateSelected(current, rowIndex, keyIndex) {
                                            it.copy(center = it.center.copy(
                                                label = if (it.label != null || it.center.label != action.text) text else it.center.label,
                                                action = KeyAction.Input(text),
                                            ))
                                        }
                                    }, label = { Text("入力テキスト") }, modifier = Modifier.fillMaxWidth(), singleLine = true, textStyle = MaterialTheme.typography.bodySmall)

                                is KeyAction.InsertSymbol -> OutlinedTextField(
                                    value = action.text, onValueChange = { text ->
                                        layout = updateSelected(current, rowIndex, keyIndex) {
                                            it.copy(center = KeyOutput(label = text.ifEmpty { "記号" }, action = KeyAction.InsertSymbol(text)))
                                        }
                                    }, label = { Text("直接入力する記号") }, modifier = Modifier.fillMaxWidth(), singleLine = true, textStyle = MaterialTheme.typography.bodySmall)

                                is KeyAction.MoveCursor -> OutlinedTextField(
                                    value = action.delta.toString(), onValueChange = { text ->
                                        val delta = text.toIntOrNull() ?: return@OutlinedTextField
                                        layout = updateSelected(current, rowIndex, keyIndex) {
                                            it.copy(center = it.center.copy(action = action.copy(delta = delta)))
                                        }
                                    }, label = { Text("移動量（マイナスで左）") }, modifier = Modifier.fillMaxWidth(), singleLine = true, textStyle = MaterialTheme.typography.bodySmall)

                                is KeyAction.SwitchLayout -> OutlinedTextField(
                                    value = action.layoutId, onValueChange = { id ->
                                        layout = updateSelected(current, rowIndex, keyIndex) {
                                            it.copy(center = it.center.copy(action = KeyAction.SwitchLayout(id)))
                                        }
                                    }, label = { Text("切替先の配列ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true, textStyle = MaterialTheme.typography.bodySmall)

                                else -> {}
                            }

                            // Style
                            Text("キースタイル", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                KeyStyle.entries.forEach { style ->
                                    FilterChip(
                                        selected = selected.style == style,
                                        onClick = {
                                            layout = updateSelected(current, rowIndex, keyIndex) { it.copy(style = style) }
                                        },
                                        label = {
                                            Text(when (style) { KeyStyle.CHARACTER -> "文字"; KeyStyle.MODIFIER -> "修飾"; KeyStyle.ACTION -> "操作" },
                                                style = MaterialTheme.typography.labelSmall)
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        ),
                                    )
                                }
                            }

                            // Repeatable
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("リピート", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Switch(checked = selected.repeatable, onCheckedChange = { checked ->
                                    layout = updateSelected(current, rowIndex, keyIndex) { it.copy(repeatable = checked) }
                                })
                            }

                            // Label
                            OutlinedTextField(
                                value = selected.label.orEmpty(), onValueChange = { label ->
                                    layout = updateSelected(current, rowIndex, keyIndex) { it.copy(label = label.ifBlank { null }) }
                                }, label = { Text("表面ラベル（空欄で自動）") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall)

                            // Weight
                            Text("キー幅 ${(selected.weight * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                            Slider(value = selected.weight.coerceIn(0.5f, 4f), onValueChange = { weight ->
                                layout = updateSelected(current, rowIndex, keyIndex) { it.copy(weight = weight) }
                            }, valueRange = 0.5f..4f)

                            HorizontalDivider()

                            // Flick outputs
                            Text("フリック方向の出力（空欄で解除）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            listOf("左" to { s: KeySpec, v: KeyOutput? -> s.copy(left = v) },
                                "上" to { s, v -> s.copy(up = v) },
                                "右" to { s, v -> s.copy(right = v) },
                                "下" to { s, v -> s.copy(down = v) }).forEach { (dir, setter) ->
                                val output = when (dir) { "左" -> selected.left; "上" -> selected.up; "右" -> selected.right; else -> selected.down }
                                var flickText by remember(output) { mutableStateOf((output?.action as? KeyAction.Input)?.text.orEmpty()) }
                                OutlinedTextField(value = flickText, onValueChange = { value ->
                                    flickText = value
                                    layout = updateSelected(current, rowIndex, keyIndex) { spec ->
                                        setter(spec, if (value.isBlank()) null else KeyOutput(value, KeyAction.Input(value)))
                                    }
                                }, label = { Text("${dir}フリック") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                                    textStyle = MaterialTheme.typography.bodySmall)
                            }

                            // Long press
                            OutlinedTextField(
                                value = (selected.longPress?.action as? KeyAction.InsertSymbol)?.text.orEmpty(),
                                onValueChange = { symbol ->
                                    layout = updateSelected(current, rowIndex, keyIndex) {
                                        it.copy(longPress = symbol.takeIf(String::isNotEmpty)?.let { v -> KeyOutput(v, KeyAction.InsertSymbol(v)) })
                                    }
                                }, label = { Text("長押しで直接入力する記号") },
                                supportingText = { Text("空欄で解除") }, modifier = Modifier.fillMaxWidth(), singleLine = true, textStyle = MaterialTheme.typography.bodySmall)

                            HorizontalDivider()

                            // Move
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf("←", "↑", "↓", "→").forEach { dir ->
                                    OutlinedButton(onClick = {
                                        val m = moveKey(current, rowIndex, keyIndex, dir); layout = m.layout; rowIndex = m.row; keyIndex = m.key
                                    }, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                                        val (icon, description) = when (dir) {
                                            "←" -> Icons.Filled.LeftArrow to "左へ移動"
                                            "→" -> Icons.Filled.ArrowForward to "右へ移動"
                                            "↑" -> Icons.Filled.ArrowUpward to "上へ移動"
                                            else -> Icons.Filled.ArrowDownward to "下へ移動"
                                        }
                                        Icon(icon, contentDescription = description)
                                    }
                                }
                            }

                            // Duplicate / Insert / Delete
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedButton(onClick = {
                                    val r = duplicateKey(current, rowIndex, keyIndex); layout = r.layout; rowIndex = r.row; keyIndex = r.key
                                }, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, Modifier.size(14.dp))
                                    Spacer(Modifier.width(2.dp))
                                    Text("複製", style = MaterialTheme.typography.labelSmall)
                                }
                                OutlinedButton(onClick = {
                                    val r = insertKey(current, rowIndex, keyIndex); layout = r.layout; rowIndex = r.row; keyIndex = r.key
                                }, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                                    Icon(Icons.Outlined.Add, contentDescription = null, Modifier.size(14.dp))
                                    Spacer(Modifier.width(2.dp))
                                    Text("追加", style = MaterialTheme.typography.labelSmall)
                                }
                                OutlinedButton(onClick = {
                                    val r = deleteKey(current, rowIndex, keyIndex)
                                    if (r != null) { layout = r.layout; rowIndex = r.row; keyIndex = r.key }
                                    else message = "最後のキーは削除できません"
                                }, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                                    Icon(Icons.Outlined.Delete, contentDescription = null, Modifier.size(14.dp))
                                    Spacer(Modifier.width(2.dp))
                                    Text("削除", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }

                Button(onClick = {
                    runCatching { repository.saveLayout(current) }
                        .onSuccess {
                            settings.markCustomizationChanged()
                            settings.activeLayoutId = current.id
                            message = "${current.id} を保存して使用します"
                            refresh++
                        }.onFailure { message = it.message ?: "保存できませんでした" }
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Save, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("保存して使用")
                }
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

private fun actionType(output: KeyOutput): String = when (output.action) {
    is KeyAction.DeleteChunk -> "delete_chunk"
    is KeyAction.Input -> "input"; is KeyAction.Backspace -> "backspace"; is KeyAction.Enter -> "enter"
    is KeyAction.Space -> "space"; is KeyAction.Convert -> "convert"; is KeyAction.MoveCursor -> "cursor"
    is KeyAction.Undo -> "undo"; is KeyAction.ModifyChar -> "modify"; is KeyAction.SwitchLayout -> "layout"
    is KeyAction.Shift -> "shift"; is KeyAction.InsertSymbol -> "symbol"; is KeyAction.ShowImePicker -> "ime_picker"
}

private fun makeAction(type: String, prev: KeyAction): KeyAction = when (type) {
    "input" -> KeyAction.Input((prev as? KeyAction.Input)?.text ?: ""); "backspace" -> KeyAction.Backspace
    "enter" -> KeyAction.Enter; "space" -> KeyAction.Space; "convert" -> KeyAction.Convert
    "cursor" -> KeyAction.MoveCursor((prev as? KeyAction.MoveCursor)?.delta ?: -1); "undo" -> KeyAction.Undo
    "modify" -> KeyAction.ModifyChar; "layout" -> KeyAction.SwitchLayout((prev as? KeyAction.SwitchLayout)?.layoutId ?: "")
    "shift" -> KeyAction.Shift; "symbol" -> KeyAction.InsertSymbol((prev as? KeyAction.InsertSymbol)?.text ?: "")
    "ime_picker" -> KeyAction.ShowImePicker; else -> prev
}

private data class MoveResult(val layout: KeyboardLayout, val row: Int, val key: Int)

private fun replaceAction(layout: KeyboardLayout, row: Int, key: Int, type: String): KeyboardLayout {
    if (row !in layout.rows.indices || key !in layout.rows[row].keys.indices) return layout
    val keys = layout.rows[row].keys.toMutableList()
    val spec = keys[key]; val newAction = makeAction(type, spec.center.action)
    val newCenter = KeyOutput(if (spec.label != null) spec.label!! else when (newAction) {
        is KeyAction.Input -> newAction.text.ifEmpty { "文字" }; is KeyAction.Backspace -> "⌫"; is KeyAction.Enter -> "↵"
        is KeyAction.Space -> "空白"; is KeyAction.Convert -> "変換"; is KeyAction.MoveCursor -> if (newAction.delta < 0) "◀" else "▶"
        is KeyAction.Undo -> "↶"; is KeyAction.ModifyChar -> "゛小゜"; is KeyAction.Shift -> "⬆"
        is KeyAction.ShowImePicker -> "IME"; is KeyAction.InsertSymbol -> newAction.text.ifEmpty { "記号" }; else -> "切替"
    }, newAction)
    val clearsFlicks = type != "input"
    keys[key] = spec.copy(center = newCenter, left = if (clearsFlicks) null else spec.left, up = if (clearsFlicks) null else spec.up,
        right = if (clearsFlicks) null else spec.right, down = if (clearsFlicks) null else spec.down)
    val rows = layout.rows.toMutableList(); rows[row] = rows[row].copy(keys = keys)
    return layout.copy(rows = rows)
}

private fun moveKey(layout: KeyboardLayout, row: Int, key: Int, direction: String): MoveResult {
    if (row !in layout.rows.indices || key !in layout.rows[row].keys.indices) return MoveResult(layout, row, key)
    val rows = layout.rows.map { it.copy(keys = it.keys.toMutableList()) }.toMutableList()
    val source = rows[row].keys.toMutableList(); val value = source[key]
    return when (direction) {
        "←" -> if (key == 0) MoveResult(layout, row, key) else { source[key] = source[key - 1]; source[key - 1] = value; rows[row] = rows[row].copy(keys = source); MoveResult(layout.copy(rows = rows), row, key - 1) }
        "→" -> if (key == source.lastIndex) MoveResult(layout, row, key) else { source[key] = source[key + 1]; source[key + 1] = value; rows[row] = rows[row].copy(keys = source); MoveResult(layout.copy(rows = rows), row, key + 1) }
        "↑", "↓" -> {
            val tr = row + if (direction == "↑") -1 else 1
            if (tr !in rows.indices || source.size == 1) MoveResult(layout, row, key) else {
                source.removeAt(key); rows[row] = rows[row].copy(keys = source)
                val target = rows[tr].keys.toMutableList(); val ti = key.coerceAtMost(target.size); target.add(ti, value)
                rows[tr] = rows[tr].copy(keys = target); MoveResult(layout.copy(rows = rows), tr, ti)
            }
        }
        else -> MoveResult(layout, row, key)
    }
}

private fun duplicateKey(layout: KeyboardLayout, row: Int, key: Int): MoveResult {
    if (row !in layout.rows.indices || key !in layout.rows[row].keys.indices) return MoveResult(layout, row, key)
    val rows = layout.rows.map { it.copy(keys = it.keys.toMutableList()) }.toMutableList()
    val source = rows[row].keys.toMutableList(); source.add(key + 1, source[key])
    rows[row] = rows[row].copy(keys = source); return MoveResult(layout.copy(rows = rows), row, key + 1)
}

private fun insertKey(layout: KeyboardLayout, row: Int, key: Int): MoveResult {
    if (row !in layout.rows.indices) return MoveResult(layout, row, key)
    val idx = key.coerceIn(0, layout.rows[row].keys.size - 1)
    val rows = layout.rows.map { it.copy(keys = it.keys.toMutableList()) }.toMutableList()
    rows[row].keys.toMutableList().also {
        it.add(idx + 1, KeySpec(weight = 1f, center = KeyOutput("新規", KeyAction.Input("")), style = KeyStyle.CHARACTER))
        rows[row] = rows[row].copy(keys = it)
    }
    return MoveResult(layout.copy(rows = rows), row, idx + 1)
}

private fun deleteKey(layout: KeyboardLayout, row: Int, key: Int): MoveResult? {
    if (row !in layout.rows.indices || key !in layout.rows[row].keys.indices) return null
    val rows = layout.rows.map { it.copy(keys = it.keys.toMutableList()) }.toMutableList()
    val source = rows[row].keys.toMutableList()
    if (source.size <= 1) return null; source.removeAt(key)
    rows[row] = rows[row].copy(keys = source)
    return MoveResult(layout.copy(rows = rows), row, (key - 1).coerceAtLeast(0))
}

private fun updateSelected(layout: KeyboardLayout, row: Int, key: Int, transform: (KeySpec) -> KeySpec): KeyboardLayout {
    if (row !in layout.rows.indices || key !in layout.rows[row].keys.indices) return layout
    val rows = layout.rows.toMutableList()
    val keys = rows[row].keys.toMutableList(); keys[key] = transform(keys[key])
    rows[row] = rows[row].copy(keys = keys); return layout.copy(rows = rows)
}
