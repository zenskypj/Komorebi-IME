package me.zssu.ime.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.zssu.ime.keyboard.FlickGuideStyle
import me.zssu.ime.keyboard.KeyboardStyle
import me.zssu.ime.theme.ZinnaTheme

class InputSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ZinnaTheme { InputSettingsScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun InputSettingsScreen() {
    val context = LocalContext.current
    val settings = remember { ImeSettings(context) }
    var style by remember { mutableStateOf(settings.keyboardStyle) }
    var flickInputMode by remember { mutableStateOf(settings.flickInputMode) }
    var guide by remember { mutableStateOf(settings.flickGuideStyle) }
    var hapticFeedback by remember { mutableStateOf(settings.hapticFeedbackEnabled) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("入力方式") },
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
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("キーボード配列", style = MaterialTheme.typography.titleMedium)
                    Text("かなと英字それぞれの配列。キーボード上のキーで面を切り替えたときも、この選択に従います", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip(style == KeyboardStyle.FLICK, "フリック") { style = KeyboardStyle.FLICK; settings.keyboardStyle = KeyboardStyle.FLICK }
                        Chip(style == KeyboardStyle.QWERTY, "QWERTY") { style = KeyboardStyle.QWERTY; settings.keyboardStyle = KeyboardStyle.QWERTY }
                        Chip(style == KeyboardStyle.MIXED, "英字だけQWERTY") { style = KeyboardStyle.MIXED; settings.keyboardStyle = KeyboardStyle.MIXED }
                        Chip(style == KeyboardStyle.KANA_QWERTY, "かなだけQWERTY") { style = KeyboardStyle.KANA_QWERTY; settings.keyboardStyle = KeyboardStyle.KANA_QWERTY }
                    }
                }
            }

            Card(shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("フリック連打", style = MaterialTheme.typography.titleMedium)
                    Text("ケータイ打ち併用では、同じキーを続けて押すと文字が循環します。約0.65秒待つか、右キーを押すと同じ行の文字を続けて入力できます", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip(flickInputMode == ImeSettings.FlickInputMode.FLICK_ONLY, "フリックのみ") {
                            flickInputMode = ImeSettings.FlickInputMode.FLICK_ONLY; settings.flickInputMode = ImeSettings.FlickInputMode.FLICK_ONLY
                        }
                        Chip(flickInputMode == ImeSettings.FlickInputMode.FLICK_AND_TOGGLE, "ケータイ併用") {
                            flickInputMode = ImeSettings.FlickInputMode.FLICK_AND_TOGGLE; settings.flickInputMode = ImeSettings.FlickInputMode.FLICK_AND_TOGGLE
                        }
                    }
                }
            }

            Card(shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("フリックガイド表示", style = MaterialTheme.typography.titleMedium)
                    Text("キーを押している間に何を出すか。フリック配列のときだけ効きます", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip(guide == FlickGuideStyle.PREVIEW, "選択文字のみ") {
                            guide = FlickGuideStyle.PREVIEW; settings.flickGuideStyle = FlickGuideStyle.PREVIEW
                        }
                        Chip(guide == FlickGuideStyle.DIRECTIONS, "4方向表示") {
                            guide = FlickGuideStyle.DIRECTIONS; settings.flickGuideStyle = FlickGuideStyle.DIRECTIONS
                        }
                    }
                }
            }

            Card(shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("キー操作のバイブ", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "キー、候補、ツールボタンを押したときの触覚フィードバック",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip(hapticFeedback, "オン") {
                            hapticFeedback = true
                            settings.hapticFeedbackEnabled = true
                        }
                        Chip(!hapticFeedback, "オフ") {
                            hapticFeedback = false
                            settings.hapticFeedbackEnabled = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}
