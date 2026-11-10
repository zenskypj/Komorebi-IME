package me.zssu.ime.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.Button
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import me.zssu.ime.theme.ZinnaTheme
import me.zssu.ime.update.UpdateManager
import me.zssu.ime.update.ReleaseVersion

class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ZinnaTheme { AboutScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutScreen() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val prefs = remember { UpdateManager.preferences(context) }
    var revision by remember { mutableIntStateOf(0) }
    var checking by remember { mutableStateOf(false) }
    var checkMessage by remember { mutableStateOf<String?>(null) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        revision++
        if (granted) UpdateManager.checkAsync(context, notify = true) { revision++ }
    }
    val installed = remember(revision) { UpdateManager.installedVersion(context) }
    val latest = remember(revision) { UpdateManager.latest(context) }
    val stable = remember(revision) { UpdateManager.stable(context) }
    val hasUpdate = remember(revision) { UpdateManager.updateAvailable(context) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Komorebi IME について") },
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
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("アプリの更新", style = MaterialTheme.typography.titleMedium)
                    Text("現在のバージョン: $installed", style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("更新を自動確認", modifier = Modifier.weight(1f))
                        Switch(checked = UpdateManager.enabled(context), onCheckedChange = { UpdateManager.setEnabled(context, it); revision++ })
                    }
                    Text("GitHub の公開情報をおおむね1日1回確認します。時刻は端末の設定により前後します。入力内容は送信しません。", style = MaterialTheme.typography.bodySmall)
                    if (UpdateManager.enabled(context) && !UpdateManager.scheduled(context)) {
                        Text("自動確認を設定できませんでした。オフにして再度オンにするか、手動で確認してください。", style = MaterialTheme.typography.bodySmall)
                    }
                    val checkedAt = prefs.getLong("checked_at", 0L)
                    if (checkedAt > 0) Text("最終確認: " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(checkedAt)), style = MaterialTheme.typography.bodySmall)
                    if (latest != null) {
                        val label = if (hasUpdate) {
                            if (ReleaseVersion.parse(latest)?.isStable == true) "正式版 $latest が利用できます" else "更新 $latest が利用できます"
                        } else "確認した最新バージョン: $latest"
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                        if (hasUpdate) OutlinedButton(onClick = { uriHandler.openUri(UpdateManager.releaseUrl(latest)) }) { Text("リリースを見る") }
                    }
                    if (stable != null && stable != latest && UpdateManager.isNewer(context, stable)) {
                        Text("正式版 $stable も利用できます", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { uriHandler.openUri(UpdateManager.releaseUrl(stable)) }) { Text("正式版を見る") }
                    }
                    (checkMessage ?: prefs.getString("error", null))?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Button(enabled = !checking, onClick = {
                        checking = true; checkMessage = null
                        UpdateManager.checkAsync(context, notify = false) { error ->
                            checking = false; checkMessage = error ?: "確認しました"; revision++
                        }
                    }) { Text(if (checking) "確認中…" else "今すぐ確認") }
                    if (android.os.Build.VERSION.SDK_INT >= 33 &&
                        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        OutlinedButton(onClick = { notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }) { Text("更新通知を許可") }
                        Text("通知を許可しなくても、この画面で更新を確認できます。", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                        OutlinedButton(onClick = {
                            val intent = if (android.os.Build.VERSION.SDK_INT >= 26) {
                                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                            } else {
                                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}"))
                            }
                            runCatching { context.startActivity(intent) }
                        }) { Text("通知の設定を開く") }
                    }
                }
            }
            Card(shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Komorebi IME", style = MaterialTheme.typography.titleMedium)
                    Text("ZenSky Project が soichi11208/Zinna-IME をフォークし、変更・配布している版です。", style = MaterialTheme.typography.bodyMedium)
                    Text("ライセンス: Apache License 2.0\nパッケージ: me.zssu.ime\n連絡先: support@zslink.xyz",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { uriHandler.openUri("https://github.com/soichi11208/Zinna-IME") }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Share, null, androidx.compose.ui.Modifier.size(16.dp))
                            Text("フォーク元")
                        }
                        OutlinedButton(onClick = { uriHandler.openUri("mailto:support@zslink.xyz") }, modifier = Modifier.weight(1f)) {
                            Text("問い合わせ")
                        }
                    }
                }
            }

            Card(shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("原作者および Mozc・同梱辞書の権利は各権利者に帰属します。完全な表示は「オープンソースライセンス」から確認できます。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
