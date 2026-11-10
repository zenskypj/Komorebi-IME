package me.zssu.ime.settings

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import me.zssu.ime.ime.ProofreadModelActivity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsHomeScreen() {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val searchFocus = remember { FocusRequester() }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var backupOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var focusSearch by remember { mutableStateOf(false) }
    var needsSetup by remember { mutableStateOf(needsKeyboardSetup(context)) }
    val homeList = rememberLazyListState()
    val searchList = rememberLazyListState()

    fun hideKeyboard() {
        keyboard?.hide()
        focus.clearFocus()
    }

    fun navigateBack() {
        if (backupOpen) {
            backupOpen = false
        } else {
            hideKeyboard()
            searchOpen = false
            query = ""
            focusSearch = false
        }
    }

    val openEntry: (SettingsEntry) -> Unit = { entry ->
        hideKeyboard()
        if (entry.destination == SettingsDestination.BACKUP) backupOpen = true
        else openDestination(context, entry.destination)
    }

    DisposableEffect(context, activity) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                needsSetup = needsKeyboardSetup(context)
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) needsSetup = needsKeyboardSetup(context)
        }
        resolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD), false, observer)
        resolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_INPUT_METHODS), false, observer)
        activity?.lifecycle?.addObserver(lifecycleObserver)
        onDispose {
            resolver.unregisterContentObserver(observer)
            activity?.lifecycle?.removeObserver(lifecycleObserver)
        }
    }

    LaunchedEffect(searchOpen, backupOpen, focusSearch) {
        if (searchOpen && !backupOpen && focusSearch) {
            searchFocus.requestFocus()
            keyboard?.show()
            focusSearch = false
        }
    }
    LaunchedEffect(query) { searchList.scrollToItem(0) }
    BackHandler(enabled = backupOpen || searchOpen, onBack = ::navigateBack)

    Scaffold(
        topBar = {
            if (searchOpen && !backupOpen) {
                Surface {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                            .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = ::navigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る")
                        }
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.weight(1f).focusRequester(searchFocus),
                            label = { Text("設定を検索") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { hideKeyboard() }),
                            trailingIcon = if (query.isNotEmpty()) {
                                {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(Icons.Filled.Close, "検索語を消す")
                                    }
                                }
                            } else null,
                        )
                    }
                }
            } else {
                TopAppBar(
                    title = { Text(if (backupOpen) "バックアップと移行" else "設定") },
                    navigationIcon = {
                        if (backupOpen) {
                            IconButton(onClick = ::navigateBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る")
                            }
                        }
                    },
                    actions = {
                        if (!backupOpen) {
                            IconButton(onClick = { searchOpen = true; focusSearch = true }) {
                                Icon(Icons.Filled.Search, "設定を検索")
                            }
                        }
                    },
                )
            }
        },
    ) { insets ->
        Box(
            modifier = Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            val listModifier = Modifier.widthIn(max = 640.dp).fillMaxSize()
            when {
                backupOpen -> LazyColumn(listModifier, contentPadding = PaddingValues(16.dp)) {
                    item { BackupCard() }
                }
                searchOpen -> {
                    val results = remember(query) {
                        SettingsCatalog.search(query).groupBy { it.group }
                    }
                    LazyColumn(listModifier, state = searchList, contentPadding = PaddingValues(bottom = 24.dp)) {
                        if (results.isEmpty()) {
                            item {
                                Text(
                                    "該当する設定がありません",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(24.dp),
                                )
                            }
                        } else {
                            SettingsGroup.entries.forEach { group ->
                                results[group]?.let { settingsSection(group.label, it, openEntry) }
                            }
                        }
                    }
                }
                else -> LazyColumn(listModifier, state = homeList, contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (needsSetup) settingsSection("キーボードの初期設定", SettingsCatalog.keyboardSetup, openEntry)
                    SettingsGroup.entries.forEach { group ->
                        val entries = SettingsCatalog.entries.filter { it.group == group }
                        val rows = if (!needsSetup && group == SettingsGroup.INPUT) {
                            entries + SettingsCatalog.keyboardSetup
                        } else entries
                        settingsSection(group.label, rows, openEntry)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.settingsSection(
    title: String,
    entries: List<SettingsEntry>,
    onEntry: (SettingsEntry) -> Unit,
) {
    item {
        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, top = 14.dp, end = 20.dp, bottom = 4.dp)
                .semantics { heading() },
        )
    }
    items(entries, key = { it.id }) { entry -> SettingLink(entry) { onEntry(entry) } }
}

private fun openDestination(context: Context, destination: SettingsDestination) {
    val intent = when (destination) {
        SettingsDestination.ENABLE_KEYBOARD -> Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
        SettingsDestination.PICK_KEYBOARD -> {
            context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            return
        }
        SettingsDestination.BACKUP -> return // Kept in the settings activity to preserve the list position.
        SettingsDestination.INPUT_GUIDE -> Intent(context, InputGuideActivity::class.java)
        SettingsDestination.INPUT_METHOD -> Intent(context, InputSettingsActivity::class.java)
        SettingsDestination.DRESS_UP -> Intent(context, ThemeEditorActivity::class.java)
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
    context.startActivity(intent)
}

private fun needsKeyboardSetup(context: Context): Boolean {
    val imm = context.getSystemService(InputMethodManager::class.java) ?: return true
    val selected = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    return imm.enabledInputMethodList.none { it.packageName == context.packageName && it.id == selected }
}
