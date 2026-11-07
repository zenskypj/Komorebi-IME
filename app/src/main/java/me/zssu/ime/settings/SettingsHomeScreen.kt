package me.zssu.ime.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import me.zssu.ime.ime.ProofreadModelActivity

@Composable
internal fun SettingsHomeScreen() {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val focusManager = LocalFocusManager.current
    var selectedTab by rememberSaveable { mutableStateOf(SettingsTab.HOME) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var glassEnabled by rememberSaveable { mutableStateOf(true) }
    var imeEnabled by remember { mutableStateOf(isImeEnabled(context)) }
    var imeSelected by remember { mutableStateOf(isImeSelected(context)) }
    val listState = rememberLazyListState()

    fun closeSearch() {
        searchOpen = false
        query = ""
        focusManager.clearFocus()
    }

    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                imeEnabled = isImeEnabled(context)
                imeSelected = isImeSelected(context)
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    LaunchedEffect(selectedTab, searchOpen) {
        runCatching { listState.scrollToItem(0) }
    }

    BackHandler(enabled = searchOpen || selectedTab != SettingsTab.HOME) {
        if (searchOpen) closeSearch() else selectedTab = SettingsTab.HOME
    }

    CompositionLocalProvider(LocalGlassEffects provides glassEnabled) {
        Box(Modifier.fillMaxSize()) {
            AmbientBackground()
            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    GlassTopBar(
                        searchOpen = searchOpen,
                        query = query,
                        onQueryChange = { query = it },
                        onOpenSearch = { searchOpen = true },
                        onCloseSearch = { closeSearch() },
                        glassEnabled = glassEnabled,
                        onToggleGlass = { glassEnabled = !glassEnabled },
                    )
                },
                bottomBar = {
                    SettingsBottomBars(
                        selectedTab = selectedTab,
                        searchOpen = searchOpen,
                        onSelect = { tab ->
                            selectedTab = tab
                            if (searchOpen) closeSearch()
                        },
                        onKeyboard = {
                            context.startActivity(
                                Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                        onInputMethod = {
                            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                            imm.showInputMethodPicker()
                        },
                        onSearch = {
                            selectedTab = SettingsTab.HOME
                            searchOpen = true
                        },
                        onBackup = {
                            selectedTab = SettingsTab.DATA
                            if (searchOpen) closeSearch()
                        },
                    )
                },
            ) { innerPadding ->
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (searchOpen) {
                        val results = SettingsCatalog.search(query)
                        item {
                            GlassSectionHeader(
                                title = if (query.isBlank()) "すべての設定" else "検索結果",
                                eyebrow = if (query.isBlank()) "SEARCH" else "「$query」",
                                count = results.size,
                            )
                        }
                        if (results.isEmpty()) {
                            item { EmptySearch(query) }
                        } else {
                            items(results, key = { it.id }) { entry ->
                                GlassSettingRow(
                                    entry = entry,
                                    onClick = { openEntry(context, entry, ::closeSearch, { selectedTab = it }) },
                                    category = entry.tab.label,
                                )
                            }
                        }
                    } else {
                        when (selectedTab) {
                            SettingsTab.HOME -> homeContent(
                                imeEnabled = imeEnabled,
                                imeSelected = imeSelected,
                                onSelectTab = { selectedTab = it },
                                onEntry = {
                                    openEntry(context, it, ::closeSearch, { tab -> selectedTab = tab })
                                },
                            )
                            SettingsTab.INPUT,
                            SettingsTab.CONVERT,
                            SettingsTab.DATA,
                            SettingsTab.INFO -> tabContent(
                                tab = selectedTab,
                                onEntry = {
                                    openEntry(context, it, ::closeSearch, { tab -> selectedTab = tab })
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.homeContent(
    imeEnabled: Boolean,
    imeSelected: Boolean,
    onSelectTab: (SettingsTab) -> Unit,
    onEntry: (SettingsEntry) -> Unit,
) {
    item { HomeHero(imeEnabled, imeSelected) }
    item {
        GlassSectionHeader(
            title = "よく使う",
            eyebrow = "QUICK ACCESS",
            count = SettingsCatalog.featured().size,
        )
    }
    items(SettingsCatalog.featured(), key = { it.id }) { entry ->
        GlassSettingRow(entry = entry, onClick = { onEntry(entry) })
    }
    item {
        GlassSectionHeader(
            title = "カテゴリから探す",
            eyebrow = "EXPLORE",
        )
    }
    item {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsTab.entries.filter { it != SettingsTab.HOME }.forEach { tab ->
                GlassCategoryChip(tab = tab, onClick = { onSelectTab(tab) })
            }
        }
    }
    item {
        Text(
            "上部の検索で、すべての設定を横断できます。",
            style = MaterialTheme.typography.bodySmall,
            color = LocalGlassPalette.current.textTertiary,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.tabContent(
    tab: SettingsTab,
    onEntry: (SettingsEntry) -> Unit,
) {
    item { TabIntro(tab) }
    if (tab == SettingsTab.DATA) {
        item { BackupCard() }
    }
    val entries = SettingsCatalog.forTab(tab)
    items(entries, key = { it.id }) { entry ->
        GlassSettingRow(entry = entry, onClick = { onEntry(entry) })
    }
    if (tab == SettingsTab.INFO) {
        item { VersionFooter() }
    }
}

@Composable
private fun HomeHero(imeEnabled: Boolean, imeSelected: Boolean) {
    val palette = LocalGlassPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("設定", style = MaterialTheme.typography.displaySmall, color = palette.textPrimary)
        Text(
            "入力・変換・データを、ひとつの場所に。",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusPill(
                text = if (imeEnabled) "キーボード 有効" else "キーボード 未有効",
                active = imeEnabled,
                warn = !imeEnabled,
            )
            StatusPill(
                text = if (imeSelected) "選択中" else "未選択",
                active = imeSelected,
                warn = !imeSelected,
            )
            StatusPill("端末内で処理", active = true)
        }
    }
}

@Composable
private fun TabIntro(tab: SettingsTab) {
    val palette = LocalGlassPalette.current
    val subtitle = when (tab) {
        SettingsTab.HOME -> ""
        SettingsTab.INPUT -> "キーボード・配列・外観・定型文"
        SettingsTab.CONVERT -> "エンジン・辞書・学習・AI"
        SettingsTab.DATA -> "バックアップ・移行・共有"
        SettingsTab.INFO -> "ライセンス・バージョン"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            tab.label,
            style = MaterialTheme.typography.displaySmall,
            color = palette.textPrimary,
        )
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = palette.textSecondary)
    }
}

@Composable
private fun EmptySearch(query: String) {
    val palette = LocalGlassPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("該当する設定がありません", style = MaterialTheme.typography.titleMedium, color = palette.textPrimary)
        Text(
            if (query.isBlank()) "検索語を入力してください。" else "「$query」に一致する設定はありません。",
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary,
        )
    }
}

@Composable
private fun VersionFooter() {
    val context = LocalContext.current
    val version = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    Text(
        "Komorebi IME $version",
        style = MaterialTheme.typography.labelSmall,
        color = LocalGlassPalette.current.textTertiary,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsBottomBars(
    selectedTab: SettingsTab,
    searchOpen: Boolean,
    onSelect: (SettingsTab) -> Unit,
    onKeyboard: () -> Unit,
    onInputMethod: () -> Unit,
    onSearch: () -> Unit,
    onBackup: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedVisibility(
            visible = selectedTab == SettingsTab.HOME && !searchOpen,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
        ) {
            GlassQuickBar(
                onKeyboard = onKeyboard,
                onInputMethod = onInputMethod,
                onSearch = onSearch,
                onBackup = onBackup,
            )
        }
        GlassBottomDock(selectedTab = selectedTab, onSelect = onSelect)
    }
}

private fun openEntry(
    context: Context,
    entry: SettingsEntry,
    closeSearch: () -> Unit,
    selectTab: (SettingsTab) -> Unit,
) {
    entry.targetTab?.let {
        selectTab(it)
        closeSearch()
        return
    }
    val destination = entry.destination ?: return
    val intent = when (destination) {
        SettingsDestination.INPUT_GUIDE -> Intent(context, InputGuideActivity::class.java)
        SettingsDestination.INPUT_METHOD -> Intent(context, InputSettingsActivity::class.java)
        SettingsDestination.LAYOUT_THEME -> Intent(context, CustomizationActivity::class.java)
        SettingsDestination.APPEARANCE -> Intent(context, AppearanceActivity::class.java)
        SettingsDestination.APP_PROFILES -> Intent(context, AppProfileActivity::class.java)
        SettingsDestination.SNIPPETS -> Intent(context, SnippetActivity::class.java)
        SettingsDestination.ENGINE_DICT -> Intent(context, EngineDictActivity::class.java)
        SettingsDestination.PROOFREAD_MODEL -> Intent(context, ProofreadModelActivity::class.java)
        SettingsDestination.LEARNING -> Intent(context, LearningActivity::class.java)
        SettingsDestination.USER_DICTIONARY -> Intent(context, UserDictionaryActivity::class.java)
        SettingsDestination.MEANING_DICTIONARY -> Intent(context, MeaningDictionaryActivity::class.java)
        SettingsDestination.MEETS_BEAM -> Intent(context, MeetsBeamActivity::class.java)
        SettingsDestination.LEGAL -> Intent(context, LegalActivity::class.java)
        SettingsDestination.ABOUT -> Intent(context, AboutActivity::class.java)
    }
    closeSearch()
    context.startActivity(intent)
}

private fun isImeEnabled(context: Context): Boolean {
    val imm = context.getSystemService(InputMethodManager::class.java) ?: return false
    return imm.enabledInputMethodList.any { it.packageName == context.packageName }
}

private fun isImeSelected(context: Context): Boolean {
    val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    return current?.startsWith(context.packageName) == true
}
