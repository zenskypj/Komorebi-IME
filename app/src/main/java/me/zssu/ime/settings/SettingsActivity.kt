package me.zssu.ime.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GTranslate
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.zssu.ime.theme.ZinnaTheme

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ZinnaTheme { SettingsScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen() {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Komorebi IME", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
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
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Brand header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp)
                    .background(
                        Brush.linearGradient(listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.tertiaryContainer,
                        )),
                        RoundedCornerShape(20.dp),
                    )
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "言葉をそっと照らす、日本語入力",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(4.dp))

            // Quick enable
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Keyboard, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("キーボードを有効化")
                }
                FilledTonalButton(
                    onClick = {
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showInputMethodPicker()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.GridView, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("キーボードを選択")
                }
            }

            Spacer(Modifier.height(8.dp))

            BackupCard()

            // Settings list
            SettingsListItem(
                icon = Icons.Filled.Psychology,
                title = "変換エンジン・辞書",
                subtitle = "動作状態、固有名詞・AI用語の切り替え",
                onClick = { context.startActivity(Intent(context, EngineDictActivity::class.java)) },
            )
            SettingsListItem(
                icon = Icons.Filled.TouchApp,
                title = "入力方式",
                subtitle = "フリック・QWERTY・ガイド表示の設定",
                onClick = { context.startActivity(Intent(context, InputSettingsActivity::class.java)) },
            )
            SettingsListItem(
                icon = Icons.Filled.Palette,
                title = "配列とテーマ",
                subtitle = "プリセット選択・自作・共有",
                onClick = { context.startActivity(Intent(context, CustomizationActivity::class.java)) },
            )
            SettingsListItem(
                icon = Icons.Filled.Image,
                title = "外観",
                subtitle = "背景画像・ピュアブラック・高さ・片手モード",
                onClick = { context.startActivity(Intent(context, AppearanceActivity::class.java)) },
            )
            SettingsListItem(
                icon = Icons.Filled.School,
                title = "変換学習",
                subtitle = "変換履歴・予測履歴のリセット",
                onClick = { context.startActivity(Intent(context, LearningActivity::class.java)) },
            )
            SettingsListItem(
                icon = Icons.Filled.Devices,
                title = "アプリ別設定",
                subtitle = "アプリごとの入力方式・高さ・シークレット",
                onClick = { context.startActivity(Intent(context, AppProfileActivity::class.java)) },
            )
            SettingsListItem(
                icon = Icons.Filled.Edit,
                title = "ユーザー辞書",
                subtitle = "単語の追加・編集・インポート",
                onClick = { context.startActivity(Intent(context, UserDictionaryActivity::class.java)) },
            )
            SettingsListItem(
                icon = Icons.Filled.GTranslate,
                title = "意味辞書",
                subtitle = "候補長押しで意味を表示",
                onClick = { context.startActivity(Intent(context, MeaningDictionaryActivity::class.java)) },
            )

            Spacer(Modifier.height(12.dp))

            // Footer
            SettingsListItem(
                icon = Icons.Filled.Description,
                title = "オープンソースライセンス",
                subtitle = "ライセンス・原作者表示",
                onClick = { context.startActivity(Intent(context, LegalActivity::class.java)) },
            )
            SettingsListItem(
                icon = Icons.Filled.Info,
                title = "Komorebi IME について",
                subtitle = "バージョン情報・問い合わせ",
                onClick = { context.startActivity(Intent(context, AboutActivity::class.java)) },
            )
        }
    }
}

@Composable
fun SettingsListItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
