package me.zssu.ime.settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import me.zssu.ime.keyboard.LayoutRepository
import me.zssu.ime.mozc.ProfileBackup
import kotlin.system.exitProcess
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
@Composable
internal fun BackupCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pending?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var restorePending by remember { mutableStateOf(ProfileBackup.hasStagedRestore(context)) }

    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BACKUP_MIME)
    ) { uri ->
        val phrase = passphrase.toCharArray()
        passphrase = ""
        if (uri == null) { phrase.fill('\u0000'); return@rememberLauncherForActivityResult }
        scope.launch {
            val archive = withContext(Dispatchers.IO) { ProfileBackup.export(context, phrase, BACKUP_EXTRAS) }
            message = if (archive == null) {
                "書き出せませんでした。キーボードを一度使ってから試してください"
            } else {
                withContext(Dispatchers.IO) { runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(archive) }
                        ?: error("no stream")
                    "書き出しました"
                }.getOrElse { "保存できませんでした: ${it.message}" } }
            }
            phrase.fill('\u0000')
        }
    }

    val load = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val phrase = passphrase.toCharArray()
        passphrase = ""
        if (uri == null) { phrase.fill('\u0000'); return@rememberLauncherForActivityResult }
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16384)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(out.size().toLong() + count <= 128L * 1024 * 1024) { "バックアップは128MB以下にしてください" }
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                }
            }.getOrNull() }
            message = if (bytes == null) {
                "ファイルを読めませんでした"
            } else {
                when (val result = withContext(Dispatchers.IO) { ProfileBackup.stageRestore(context, bytes, phrase) }) {
                    ProfileBackup.Result.Ok -> {
                        restorePending = true
                        "読み込みました。反映するにはアプリを終了して開き直してください"
                    }
                    ProfileBackup.Result.NotABackup -> "このファイルはバックアップではありません"
                    ProfileBackup.Result.WrongPassphrase ->
                        "パスフレーズが違うか、ファイルが壊れています"
                    is ProfileBackup.Result.TooNew ->
                        "新しいバージョンのアプリで作られたバックアップです (形式 ${result.format})"
                    is ProfileBackup.Result.Failed -> result.message
                }
            }
            phrase.fill('\u0000')
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("バックアップ", style = MaterialTheme.typography.titleMedium)
            Text(
                "ユーザー辞書・変換の学習内容・キーボードの設定を1つのファイルにまとめます。" +
                    "打った内容そのものを含むので、パスフレーズで暗号化します。忘れると復元できません",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { pending = Pending.EXPORT },
                    modifier = Modifier.weight(1f),
                ) { Text("書き出す") }
                OutlinedButton(
                    onClick = { pending = Pending.IMPORT },
                    modifier = Modifier.weight(1f),
                ) { Text("読み込む") }
            }
            if (restorePending) {
                Text(
                    "復元待ちです。学習内容はキーボードが動いている間ずっと書き戻されるので、" +
                        "一度終了させないと上書きされてしまいます",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        // Ends this process, keyboard included. Android starts the input method
                        // again the next time a text field is focused, and that start is what
                        // unpacks the restore.
                        onClick = { exitProcess(0) },
                        modifier = Modifier.weight(1f),
                    ) { Text("終了して反映") }
                    OutlinedButton(
                        onClick = {
                            ProfileBackup.discardStagedRestore(context)
                            restorePending = false
                            message = "復元を取り消しました"
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("取り消す") }
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }

    pending?.let { what ->
        // Twice for an export, because a typo in a passphrase nobody has typed before is only
        // discovered when the backup is needed — by which time it is too late to matter.
        var confirmation by remember(what) { mutableStateOf("") }
        val exporting = what == Pending.EXPORT
        val ready = passphrase.length >= MIN_PASSPHRASE &&
            (!exporting || confirmation == passphrase)

        AlertDialog(
            onDismissRequest = { pending = null; passphrase = "" },
            title = { Text(if (exporting) "バックアップを書き出す" else "バックアップを読み込む") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("パスフレーズ") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (exporting) {
                        OutlinedTextField(
                            value = confirmation,
                            onValueChange = { confirmation = it },
                            label = { Text("もう一度") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "$MIN_PASSPHRASE 文字以上。このパスフレーズなしでは誰も — 私たちも — " +
                                "中身を読めません",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = ready,
                    onClick = {
                        pending = null
                        message = null
                        if (exporting) save.launch(ProfileBackup.suggestedFileName())
                        else load.launch(arrayOf("*/*"))
                    },
                ) { Text("続ける") }
            },
            dismissButton = {
                OutlinedButton(onClick = { pending = null; passphrase = "" }) { Text("やめる") }
            },
        )
    }
}

private enum class Pending { EXPORT, IMPORT }

private const val BACKUP_MIME = "application/octet-stream"

/**
 * Everything outside mozc's own profile that the user chose rather than typed: the settings, the
 * background image they picked, and any layout or theme they dropped in by hand.
 */
private val BACKUP_EXTRAS = ProfileBackup.Extras(
    preferences = listOf(ImeSettings.NAME, "emoji_palette"),
    files = listOf("app_profiles.json"),
    encryptedFiles = listOf("priority_candidates.enc"),
    directories = listOf(
        ImeSettings.BACKGROUND_DIR,
        LayoutRepository.LAYOUTS_DIR,
        LayoutRepository.THEMES_DIR,
        "meaning_dictionaries",
    ),
)

/** Short enough not to be a chore, long enough that PBKDF2 has something to work with. */
private const val MIN_PASSPHRASE = 8
