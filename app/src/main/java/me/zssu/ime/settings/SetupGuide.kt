package me.zssu.ime.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun SetupGuide(enabled: Boolean, selected: Boolean) {
    val context = LocalContext.current
    val done = context.getSharedPreferences(ImeSettings.NAME, Context.MODE_PRIVATE)
        .getBoolean("flick_onboarding_done", false)
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("はじめての設定", style = MaterialTheme.typography.titleMedium)
            Text("終わったところから続けられます。入力方法はあとから変更できます。", style = MaterialTheme.typography.bodySmall)
            Text("${if (enabled) "✓" else "1."} キーボードを有効にする")
            if (!enabled) OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }) { Text("Androidの設定を開く") }
            Text("${if (selected) "✓" else "2."} Komorebi IMEを選ぶ")
            if (enabled && !selected) OutlinedButton(onClick = {
                context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            }) { Text("キーボードを選ぶ") }
            Text("${if (done) "✓" else "3."} 入力方法を選んで試す")
            if (selected) {
                Button(onClick = { context.startActivity(Intent(context, InputGuideActivity::class.java)) }) {
                    Text("入力を練習する")
                }
                Text("練習欄を開くと、最初に入力方式を選べます。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
