package me.zssu.ime.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.zssu.ime.keyboard.KeyboardStyle
import me.zssu.ime.theme.ZinnaTheme

class AppProfileActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ZinnaTheme { Scaffold { AppProfilesScreen(Modifier.padding(it)) } } }
    }
}

@Composable
private fun AppProfilesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { AppProfileStore(context) }
    val globalSettings = remember { ImeSettings(context) }
    var refresh by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<AppProfileStore.Profile?>(null) }
    var message by remember { mutableStateOf("") }
    val profiles = remember(refresh) { store.profiles() }
    val unconfigured = remember(refresh) {
        store.seenPackages().filter { packageName ->
            profiles.none { it.packageName == packageName } && packageName != context.packageName
        }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("アプリ別入力プロファイル", style = MaterialTheme.typography.headlineSmall)
        Text(
            "アプリを開いたときに入力方式、片手モード、高さ、シークレットモードを自動適用します。" +
                "インストール済みアプリの一覧権限は使わず、IMEを実際に使ったアプリだけを記録します。",
        )
        Button(
            onClick = {
                editing = defaultProfile("", globalSettings)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("パッケージ名を指定して追加") }

        profiles.forEach { profile ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(profile.label.ifBlank { profile.packageName })
                    if (profile.label.isNotBlank()) {
                        Text(profile.packageName, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "${profile.resolvedKeyboardStyle.displayName()} / " +
                            "${profile.resolvedOneHandMode.displayName()} / " +
                            "高さ ${(profile.keyHeightScale * 100).toInt()}% / " +
                            if (profile.incognito) "シークレット" else "通常学習",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { editing = profile },
                            modifier = Modifier.weight(1f),
                        ) { Text("編集") }
                        OutlinedButton(
                            onClick = {
                                store.delete(profile.packageName)
                                refresh++
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("削除") }
                    }
                }
            }
        }

        if (unconfigured.isNotEmpty()) {
            Text("最近IMEを使ったアプリ", style = MaterialTheme.typography.titleMedium)
            unconfigured.forEach { packageName ->
                OutlinedButton(
                    onClick = { editing = defaultProfile(packageName, globalSettings) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(packageName) }
            }
        }
        if (profiles.isEmpty() && unconfigured.isEmpty()) {
            Text("まだ対象アプリは記録されていません。")
        }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
    }

    editing?.let { initial ->
        ProfileEditorDialog(
            initial = initial,
            onDismiss = { editing = null },
            onSave = { profile ->
                runCatching { store.save(profile) }
                    .onSuccess {
                        message = "${profile.packageName} の設定を保存しました"
                        refresh++
                        editing = null
                    }
                    .onFailure { message = it.message.orEmpty() }
            },
        )
    }
}

@Composable
private fun ProfileEditorDialog(
    initial: AppProfileStore.Profile,
    onDismiss: () -> Unit,
    onSave: (AppProfileStore.Profile) -> Unit,
) {
    var packageName by remember(initial) { mutableStateOf(initial.packageName) }
    var label by remember(initial) { mutableStateOf(initial.label) }
    var style by remember(initial) { mutableStateOf(initial.resolvedKeyboardStyle) }
    var oneHand by remember(initial) { mutableStateOf(initial.resolvedOneHandMode) }
    var height by remember(initial) { mutableFloatStateOf(initial.keyHeightScale) }
    var correction by remember(initial) { mutableStateOf(initial.typingCorrection) }
    var neuralEnabled by remember(initial) { mutableStateOf(initial.neuralEnabled) }
    var incognito by remember(initial) { mutableStateOf(initial.incognito) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("入力プロファイル") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = packageName,
                    onValueChange = { packageName = it.trim() },
                    enabled = initial.packageName.isBlank(),
                    label = { Text("パッケージ名（例: com.example.app）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("表示名（任意）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("入力方式")
                KeyboardStyle.entries.forEach { option ->
                    ChoiceButton(
                        option.displayName(),
                        style == option,
                        Modifier.fillMaxWidth(),
                    ) { style = option }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("AI 変換を許可", Modifier.weight(1f))
                    Switch(checked = neuralEnabled, onCheckedChange = { neuralEnabled = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("入力ミスを補正", Modifier.weight(1f))
                    Switch(checked = correction, onCheckedChange = { correction = it })
                }
                Text("片手モード")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ImeSettings.OneHandMode.entries.forEach { option ->
                        ChoiceButton(
                            option.displayName(),
                            oneHand == option,
                            Modifier.weight(1f),
                        ) { oneHand = option }
                    }
                }
                Text("キーボードの高さ ${(height * 100).toInt()}%")
                Slider(
                    value = height,
                    onValueChange = { height = it },
                    valueRange = ImeSettings.MIN_KEY_HEIGHT_SCALE..ImeSettings.MAX_KEY_HEIGHT_SCALE,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("シークレットモード")
                        Text("変換を学習しません", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = incognito, onCheckedChange = { incognito = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        AppProfileStore.Profile(
                            packageName = packageName,
                            label = label,
                            keyboardStyle = style.name,
                            oneHandMode = oneHand.name,
                            keyHeightScale = height,
                            incognito = incognito,
                            neuralEnabled = neuralEnabled,
                            typingCorrection = correction,
                        )
                    )
                },
                enabled = AppProfileStore.validPackageName(packageName),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
private fun ChoiceButton(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    if (selected) Button(onClick, modifier) { Text(label) }
    else OutlinedButton(onClick, modifier) { Text(label) }
}

private fun KeyboardStyle.displayName(): String = when (this) {
    KeyboardStyle.FLICK -> "フリック"
    KeyboardStyle.QWERTY -> "QWERTY"
    KeyboardStyle.MIXED -> "かなフリック + 英字QWERTY"
    KeyboardStyle.KANA_QWERTY -> "かなQWERTY + 英字フリック"
}

private fun ImeSettings.OneHandMode.displayName(): String = when (this) {
    ImeSettings.OneHandMode.OFF -> "全幅"
    ImeSettings.OneHandMode.LEFT -> "左"
    ImeSettings.OneHandMode.RIGHT -> "右"
}

private fun defaultProfile(
    packageName: String,
    settings: ImeSettings,
) = AppProfileStore.Profile(
    packageName = packageName,
    keyboardStyle = settings.keyboardStyle.name,
    oneHandMode = settings.oneHandMode.name,
    keyHeightScale = settings.keyHeightScale,
)
