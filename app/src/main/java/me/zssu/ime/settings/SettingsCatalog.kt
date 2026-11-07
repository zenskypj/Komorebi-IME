package me.zssu.ime.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GTranslate
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.ui.graphics.vector.ImageVector

internal enum class SettingsTab(val label: String) {
    HOME("ホーム"),
    INPUT("入力"),
    CONVERT("変換"),
    DATA("データ"),
    INFO("情報"),
}

internal enum class SettingsDestination {
    INPUT_GUIDE,
    INPUT_METHOD,
    LAYOUT_THEME,
    APPEARANCE,
    APP_PROFILES,
    SNIPPETS,
    ENGINE_DICT,
    PROOFREAD_MODEL,
    LEARNING,
    USER_DICTIONARY,
    MEANING_DICTIONARY,
    MEETS_BEAM,
    LEGAL,
    ABOUT,
}

internal data class SettingsEntry(
    val id: String,
    val tab: SettingsTab,
    val title: String,
    val subtitle: String,
    val keywords: List<String>,
    val icon: ImageVector,
    val destination: SettingsDestination? = null,
    val targetTab: SettingsTab? = null,
    val featured: Boolean = false,
)

internal object SettingsCatalog {
    val entries: List<SettingsEntry> = listOf(
        SettingsEntry(
            id = "input_guide",
            tab = SettingsTab.INPUT,
            title = "入力の使い方",
            subtitle = "図解・操作体験・入力の練習",
            keywords = listOf("ガイド", "練習", "使い方", "オンボーディング"),
            icon = Icons.Filled.School,
            destination = SettingsDestination.INPUT_GUIDE,
            featured = true,
        ),
        SettingsEntry(
            id = "input_method",
            tab = SettingsTab.INPUT,
            title = "入力方式",
            subtitle = "フリック・QWERTY・ガイド表示の設定",
            keywords = listOf("キーボード", "フリック", "QWERTY", "ローマ字"),
            icon = Icons.Filled.TouchApp,
            destination = SettingsDestination.INPUT_METHOD,
            featured = true,
        ),
        SettingsEntry(
            id = "layout_theme",
            tab = SettingsTab.INPUT,
            title = "配列とテーマ",
            subtitle = "プリセット選択・自作・共有",
            keywords = listOf("配列", "テーマ", "レイアウト", "スタジオ"),
            icon = Icons.Filled.Palette,
            destination = SettingsDestination.LAYOUT_THEME,
        ),
        SettingsEntry(
            id = "appearance",
            tab = SettingsTab.INPUT,
            title = "外観",
            subtitle = "背景画像・高さ・片手モード",
            keywords = listOf("見た目", "背景", "高さ", "片手", "ピュアブラック"),
            icon = Icons.Filled.Image,
            destination = SettingsDestination.APPEARANCE,
        ),
        SettingsEntry(
            id = "snippets",
            tab = SettingsTab.INPUT,
            title = "定型文",
            subtitle = "よく使う文章の登録・編集",
            keywords = listOf("スニペット", "文章", "定型", "登録"),
            icon = Icons.Filled.Description,
            destination = SettingsDestination.SNIPPETS,
        ),
        SettingsEntry(
            id = "engine_dict",
            tab = SettingsTab.CONVERT,
            title = "変換エンジン・辞書",
            subtitle = "動作状態、固有名詞・AI用語の切り替え",
            keywords = listOf("Mozc", "Karukan", "zenzai", "辞書", "エンジン", "AI"),
            icon = Icons.Filled.Psychology,
            destination = SettingsDestination.ENGINE_DICT,
            featured = true,
        ),
        SettingsEntry(
            id = "proofread",
            tab = SettingsTab.CONVERT,
            title = "AI 文章校正モデル",
            subtitle = "Qwen3.5 0.8B / Qwen3 1.7B の取得・削除",
            keywords = listOf("AI", "校正", "モデル", "Qwen", "文章"),
            icon = Icons.Filled.AutoAwesome,
            destination = SettingsDestination.PROOFREAD_MODEL,
        ),
        SettingsEntry(
            id = "learning",
            tab = SettingsTab.CONVERT,
            title = "変換学習",
            subtitle = "変換履歴・予測履歴のリセット",
            keywords = listOf("学習", "履歴", "リセット", "診断"),
            icon = Icons.Filled.School,
            destination = SettingsDestination.LEARNING,
        ),
        SettingsEntry(
            id = "user_dictionary",
            tab = SettingsTab.CONVERT,
            title = "ユーザー辞書",
            subtitle = "単語の追加・編集・インポート",
            keywords = listOf("辞書", "単語", "登録", "インポート", "Beam"),
            icon = Icons.Filled.Edit,
            destination = SettingsDestination.USER_DICTIONARY,
            featured = true,
        ),
        SettingsEntry(
            id = "meaning_dictionary",
            tab = SettingsTab.CONVERT,
            title = "意味辞書",
            subtitle = "候補長押しで意味を表示",
            keywords = listOf("意味", "辞書", "タグ", "長押し"),
            icon = Icons.Filled.GTranslate,
            destination = SettingsDestination.MEANING_DICTIONARY,
        ),
        SettingsEntry(
            id = "backup",
            tab = SettingsTab.DATA,
            title = "バックアップと移行",
            subtitle = "NFC 移行・書き出し・読み込み",
            keywords = listOf("バックアップ", "移行", "NFC", "復元", "書き出し"),
            icon = Icons.Filled.Backup,
            targetTab = SettingsTab.DATA,
            featured = true,
        ),
        SettingsEntry(
            id = "meets_beam",
            tab = SettingsTab.DATA,
            title = "Meets Beam",
            subtitle = "NFC でリンク・アプリ・テキストを共有",
            keywords = listOf("ビーム", "NFC", "共有", "Wi-Fi Direct", "ファイル"),
            icon = Icons.Filled.Devices,
            destination = SettingsDestination.MEETS_BEAM,
            featured = true,
        ),
        SettingsEntry(
            id = "app_profiles",
            tab = SettingsTab.DATA,
            title = "アプリ別設定",
            subtitle = "アプリごとの入力方式・高さ・シークレット",
            keywords = listOf("プロファイル", "アプリ別", "シークレット", "高さ"),
            icon = Icons.Filled.Devices,
            destination = SettingsDestination.APP_PROFILES,
        ),
        SettingsEntry(
            id = "legal",
            tab = SettingsTab.INFO,
            title = "オープンソースライセンス",
            subtitle = "ライセンス・原作者表示",
            keywords = listOf("ライセンス", "法的", "OSS", "原作者"),
            icon = Icons.Filled.Description,
            destination = SettingsDestination.LEGAL,
        ),
        SettingsEntry(
            id = "about",
            tab = SettingsTab.INFO,
            title = "Komorebi IME について",
            subtitle = "バージョン情報・問い合わせ",
            keywords = listOf("バージョン", "情報", "問い合わせ", "Komorebi"),
            icon = Icons.Filled.Info,
            destination = SettingsDestination.ABOUT,
        ),
    )

    fun forTab(tab: SettingsTab): List<SettingsEntry> =
        if (tab == SettingsTab.HOME) emptyList()
        else entries.filter { it.tab == tab && it.destination != null }

    fun featured(): List<SettingsEntry> = entries.filter { it.featured }

    fun search(query: String): List<SettingsEntry> {
        val needle = query.trim()
        if (needle.isEmpty()) return entries
        return entries.filter { entry ->
            entry.title.contains(needle, ignoreCase = true) ||
                entry.subtitle.contains(needle, ignoreCase = true) ||
                entry.keywords.any { it.contains(needle, ignoreCase = true) }
        }
    }
}
