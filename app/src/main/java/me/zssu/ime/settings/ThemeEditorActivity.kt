package me.zssu.ime.settings

import android.os.Bundle
import android.content.Intent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import kotlinx.serialization.json.Json
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import me.zssu.ime.keyboard.FlickKeyboardView
import me.zssu.ime.keyboard.KeyboardLayout
import me.zssu.ime.keyboard.KeyboardPanelView
import me.zssu.ime.keyboard.LayoutRepository
import me.zssu.ime.theme.*

class ThemeEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ZinnaTheme { ThemeEditorScreen() } }
    }
}

internal val frameNames = mapOf(
    KeyFrame.FILLED to "塗りつぶし", KeyFrame.OUTLINE to "個別の枠",
    KeyFrame.DIVIDERS to "仕切り線", KeyFrame.NONE to "枠なし",
    KeyFrame.DASHED to "破線の枠", KeyFrame.GRADIENT to "グラデーション",
)

private enum class EditorPage(val label: String) {
    BACKGROUND("背景"), GALLERY("テーマ"), SHAPE("ボタン"), TEXT("文字"), COLORS("配色"), EFFECTS("装飾"), LAYOUT("配列"),

}

private data class ColorProperty(
    val title: String, val get: (KeyboardTheme) -> Int, val set: (KeyboardTheme, Int) -> KeyboardTheme,
)

private val colors = listOf(
    ColorProperty("背景", { it.backgroundColor }, { t, v -> t.copy(backgroundColor = v) }),
    ColorProperty("文字キー", { it.keyColor }, { t, v -> t.copy(keyColor = v) }),
    ColorProperty("操作キー", { it.modifierKeyColor }, { t, v -> t.copy(modifierKeyColor = v) }),
    ColorProperty("枠・仕切り線", { it.lineColor }, { t, v -> t.copy(lineColor = v) }),
    ColorProperty("押したキー", { it.keyPressedColor }, { t, v -> t.copy(keyPressedColor = v) }),
    ColorProperty("キー文字", { it.labelColor }, { t, v -> t.copy(labelColor = v) }),
    ColorProperty("フリックガイド背景", { it.flickGuideColor }, { t, v -> t.copy(flickGuideColor = v) }),
    ColorProperty("フリックガイド文字", { it.flickGuideLabelColor }, { t, v -> t.copy(flickGuideLabelColor = v) }),
    ColorProperty("フリックガイド選択色", { it.flickGuideSelectedLabelColor }, { t, v -> t.copy(flickGuideSelectedLabelColor = v) }),
    ColorProperty("候補の背景", { it.candidateBackgroundColor }, { t, v -> t.copy(candidateBackgroundColor = v, useCandidateBackground = true) }),
    ColorProperty("候補の文字", { it.candidateTextColor }, { t, v -> t.copy(candidateTextColor = v) }),
    ColorProperty("ツールバー背景", { it.toolbarBackgroundColor }, { t, v -> t.copy(toolbarBackgroundColor = v) }),
    ColorProperty("ツールバーアイコン", { it.toolbarIconColor }, { t, v -> t.copy(toolbarIconColor = v) }),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ThemeEditorScreen() {
    val context = LocalContext.current
    val repo = remember { LayoutRepository(context) }
    val settings = remember { ImeSettings(context) }
    var revision by remember { mutableIntStateOf(0) }
    val themes = remember(revision) { repo.availableThemeIds().associateWith { id ->
        if (id == MaterialYouTheme.ID) MaterialYouTheme.create(context) else repo.loadTheme(id)
    }.mapNotNull { (id, theme) -> theme?.let { id to it } }.toMap() }
    val bundles = remember { ThemeMediaBundles(context) }
    var saveMedia by rememberSaveable { mutableStateOf(false) }
    var replaceCurrent by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val schedule = remember { ThemeSchedule(context) }
    var scheduleMode by remember { mutableStateOf(schedule.mode) }
    var scheduleId by remember { mutableStateOf(schedule.themeId) }
    var scheduleStart by remember { mutableIntStateOf(schedule.start) }
    var scheduleEnd by remember { mutableIntStateOf(schedule.end) }
    val startingId = settings.activeThemeId
    var baseId by rememberSaveable { mutableStateOf(startingId) }
    val base = themes[baseId] ?: KeyboardTheme.Default
    var theme by rememberSaveable(stateSaver = Saver<KeyboardTheme, String>(
        save = { Json.encodeToString(KeyboardTheme.serializer(), it) },
        restore = { Json.decodeFromString(KeyboardTheme.serializer(), it) },
    )) { mutableStateOf(base) }
    var name by rememberSaveable { mutableStateOf("") }
    var appliedId by rememberSaveable { mutableStateOf(settings.activeThemeId) }
    var page by rememberSaveable { mutableStateOf(EditorPage.BACKGROUND) }
    var qwerty by rememberSaveable { mutableStateOf(
        settings.activeLayoutId?.let(repo::loadLayout)?.let(::isQwerty)
            ?: (settings.keyboardStyle == me.zssu.ime.keyboard.KeyboardStyle.QWERTY)
    ) }
    var previewVisible by rememberSaveable { mutableStateOf(false) }
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    LaunchedEffect(imeInsets, density) {
        snapshotFlow { imeInsets.getBottom(density) > 0 }.collect { previewVisible = it }
    }
    var inputView by remember { mutableStateOf<EditText?>(null) }
    val imm = remember { context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager }
    DisposableEffect(Unit) {
        onDispose { DressUpLivePreview.clear() }
    }
    var previewThemeBackground by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val background = rememberDressUpBackground(settings) { color, customMedia ->
        theme = DressUpColors.apply(theme, color, customMedia)
    }
    val colorScope = rememberCoroutineScope()
    var imported by remember { mutableStateOf<ThemePackage.Draft?>(null) }
    val cleanup = remember { arrayOfNulls<ThemePackage.Draft>(1) }
    DisposableEffect(Unit) { onDispose { cleanup[0]?.close() } }
    fun discardImport() { imported?.close(); imported = null; cleanup[0] = null }
    var sharing by remember { mutableStateOf<Triple<KeyboardTheme, java.io.File?, Boolean>?>(null) }
    val importTheme = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) colorScope.launch {
            try {
                val draft = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri) ?: error("ファイルを開けません")
                    input.use { ThemePackage.read(context, it) }.also {
                        try { repo.validate(it.theme) } catch (e: Exception) { it.close(); throw e }
                    }
                }
                discardImport()
                cleanup[0] = draft; imported = draft
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (e: Exception) { message = e.message ?: "テーマを読み込めませんでした" }
        }
    }
    val exportTheme = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val export = sharing
        sharing = null
        if (uri != null && export != null) colorScope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri) ?: error("保存先を開けません")
                    output.use { ThemePackage.write(it, export.first, export.second, export.third) }
                }
                message = "テーマを書き出しました"
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (e: Exception) { message = e.message ?: "書き出せませんでした" }
        }
    }
    fun matchImageColors() {
        val file = background.media.photo ?: background.media.album.firstOrNull() ?: background.media.video
        val wallpaper = theme.wallpaper
        colorScope.launch {
            val color = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (file != null && !previewThemeBackground) DressUpColors.fromFile(file)
                else wallpaper?.let { DressUpColors.fromAsset(context, it.asset) }
            }
            if (color != null) theme = DressUpColors.apply(theme, color,
                customMedia = file != null && !previewThemeBackground && settings.backgroundPresetId == null)
            else message = "色を合わせる画像を選んでください"
        }
    }
    val bundledMedia = remember(baseId, revision) { bundles.load(baseId) }
    val previewMedia = if (saveMedia) background.media else bundledMedia ?: background.media
    val edited = theme != base || name.isNotBlank() || saveMedia
    val canReplace = repo.hasUserOverride(LayoutRepository.THEMES_DIR, baseId)
    var appliedLayoutId by rememberSaveable { mutableStateOf(settings.activeLayoutId) }
    var selectedLayoutId by rememberSaveable { mutableStateOf(settings.activeLayoutId) }
    val layouts = remember(revision) { repo.availableLayoutIds().mapNotNull { id ->
        repo.loadLayout(id)?.let { id to it }
    }.toMap() }
    val layout = selectedLayoutId?.let(layouts::get)?.takeIf { isQwerty(it) == qwerty }
        ?: layouts[if (qwerty) "qwerty_ascii" else LayoutRepository.DEFAULT_LAYOUT_ID]
    val openLayoutEditor = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        revision++
        val active = settings.activeLayoutId
        if (active != appliedLayoutId) {
            appliedLayoutId = active
            selectedLayoutId = active
        }
    }

    val reduceMotion = remember(revision) { settings.reducedKeyboardMotion }
    SideEffect {
        val previewTheme = (if (settings.pureBlack) theme.asPureBlack() else theme).let {
            if (reduceMotion) it.copy(wallpaperMotion = me.zssu.ime.theme.WallpaperMotion.NONE,
                tapEffect = me.zssu.ime.theme.TapEffect.NONE, keywordEffects = emptyList()) else it
        }
        DressUpLivePreview.update(DressUpLivePreview.Draft(
            imported?.theme ?: previewTheme, layout,
            if (imported != null) imported?.media else previewMedia.takeUnless { previewThemeBackground },
        ))
    }

    fun selectLayout(id: String?) {
        selectedLayoutId = id
        id?.let(layouts::get)?.let { qwerty = isQwerty(it) }
    }

    fun apply() {
        if (saving) return
        val selected = when {
            !edited && baseId in themes -> baseId
            replaceCurrent && canReplace -> baseId
            else -> {
                val used = repo.availableThemeIds().toSet()
                generateSequence(1) { it + 1 }.map { if (it == 1) "my_theme" else "my_theme_$it" }.first { it !in used }
            }
        }
        val draft = theme.copy(id = selected, label = name.trim().ifEmpty {
            if (selected == baseId) base.label else "${base.label} のアレンジ"
        })
        val snapshot = if (saveMedia) background.media else bundledMedia?.takeIf { edited && selected != baseId }
        saving = true
        colorScope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if (edited) {
                        snapshot?.let { bundles.save(selected, it) }
                        repo.saveTheme(draft)
                        settings.markCustomizationChanged()
                    }
                    settings.activeThemeId = selected
                    if (appliedLayoutId != selectedLayoutId) settings.activeLayoutId = selectedLayoutId
                }
                (context as? ComponentActivity)?.finish()
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (e: Exception) { message = e.message ?: "保存できませんでした" }
            finally { saving = false }
        }
    }

    imported?.let { draft ->
        AlertDialog(onDismissRequest = ::discardImport,
            title = { Text("テーマの取り込み") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(draft.theme.label.take(80))
                    Text("展開後 %.1f MB。新しいテーマとして追加します。".format(draft.size / 1048576.0))
                    val thumb = remember(draft) {
                        (draft.media?.photo ?: draft.media?.album?.firstOrNull())?.let { file ->
                            android.graphics.BitmapFactory.decodeFile(file.absolutePath,
                                android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 })
                        }
                    }
                    if (thumb != null) Image(thumb.asImageBitmap(), "取り込む背景",
                        Modifier.fillMaxWidth().height(110.dp), contentScale = ContentScale.Crop)
                    Text("文字色の見本：あいうえお", color = androidx.compose.ui.graphics.Color(draft.theme.labelColor),
                        modifier = Modifier.background(androidx.compose.ui.graphics.Color(draft.theme.keyColor)).padding(8.dp))
                }
            },
            confirmButton = { TextButton(enabled = !saving, onClick = {
                saving = true
                val used = repo.availableThemeIds().toSet()
                val id = generateSequence(1) { it + 1 }.map { "imported_theme_$it" }.first { it !in used }
                colorScope.launch {
                    try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            draft.media?.let { bundles.save(id, it) }
                            repo.saveTheme(draft.theme.copy(id = id, label = draft.theme.label.take(80)))
                            settings.markCustomizationChanged()
                        }
                        baseId = id; theme = draft.theme.copy(id = id, label = draft.theme.label.take(80))
                        saveMedia = false; replaceCurrent = false; name = ""; revision++
                        discardImport()
                    } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                    catch (e: Exception) { discardImport(); message = e.message ?: "保存できませんでした" }
                    finally { saving = false }
                }
            }) { Text("新しく追加") } },
            dismissButton = { TextButton(enabled = !saving, onClick = ::discardImport) { Text("キャンセル") } })
    }

    Scaffold(topBar = {
        CenterAlignedTopAppBar(title = { Text("編集") }, navigationIcon = {
            IconButton(onClick = { (context as? ComponentActivity)?.finish() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る")
            }
        }, actions = {
            TextButton(onClick = ::apply, enabled = !background.busy && !saving, modifier = Modifier.padding(end = 8.dp)) { Text("完了") }
        })
    }) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding()) {
            val sideBySide = maxWidth >= 600.dp
            val preview: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier.padding(horizontal = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (!previewVisible) Text("実際のキーボードで試す", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                        else Spacer(Modifier.weight(1f))
                        TextButton(onClick = { qwerty = false }, enabled = qwerty) { Text("フリック") }
                        TextButton(onClick = { qwerty = true }, enabled = !qwerty) { Text("QWERTY") }
                        TextButton(onClick = {
                            previewVisible = !previewVisible
                            inputView?.let {
                                if (previewVisible) {
                                    it.requestFocus()
                                    it.post { imm.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT) }
                                } else {
                                    imm.hideSoftInputFromWindow(it.windowToken, 0)
                                    it.clearFocus()
                                }
                            }
                        }) { Text(if (previewVisible) "閉じる" else "開く") }
                    }
                    AndroidView(
                        factory = { ctx ->
                            EditText(ctx).apply {
                                hint = "ここで入力を試す"
                                setSingleLine(true)
                                privateImeOptions = DressUpLivePreview.EDITOR_MARKER
                                imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                                inputView = this
                                setOnFocusChangeListener { _, focused -> if (focused) previewVisible = true }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!previewVisible) Text("Komorebi IMEを選択して試してください", style = MaterialTheme.typography.labelSmall)
                }
            }
            val controls: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.SpaceEvenly) {
                        EditorPage.entries.forEach { option ->
                            val icon = when (option) {
                                EditorPage.BACKGROUND -> Icons.Filled.Image
                                EditorPage.GALLERY -> Icons.Filled.Palette
                                EditorPage.SHAPE -> Icons.Filled.Keyboard
                                EditorPage.TEXT -> Icons.Filled.TextFields
                                EditorPage.COLORS -> Icons.Filled.FormatColorFill
                                EditorPage.EFFECTS -> Icons.Filled.AutoAwesome
                                EditorPage.LAYOUT -> Icons.Filled.GridView
                            }
                            Column(
                                Modifier.widthIn(min = 68.dp).clickable { page = option; message = null }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(icon, contentDescription = null,
                                    tint = if (page == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                Text(option.label, style = MaterialTheme.typography.labelMedium,
                                    color = if (page == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                Spacer(Modifier.height(4.dp))
                                HorizontalDivider(Modifier.width(20.dp), thickness = 2.dp,
                                    color = if (page == option) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
                            }
                        }
                    }
                    message?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
                    HorizontalDivider()
                    key(page) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (edited) {
                                OutlinedButton(onClick = { theme = base; name = "" }) {
                                    Text("このテーマの変更を元に戻す")
                                }
                                Text("写真や動画は「背景」で選択時に反映されます。", style = MaterialTheme.typography.bodySmall)
                            }
                            when (page) {
                                EditorPage.GALLERY -> {
                                    Text("好きなテーマから始める", style = MaterialTheme.typography.titleLarge)
                                    Text("選んだテーマを試してから「適用」。キーや配色を変えると、別のテーマとして保存できます。",
                                        style = MaterialTheme.typography.bodyMedium)
                                    val illustrated = themes.filterValues { it.wallpaper != null }
                                    if (illustrated.isNotEmpty()) {
                                        ArtPicker(illustrated.keys.toList(), baseId, { themes.getValue(it).label },
                                            { themes.getValue(it).wallpaper?.asset }) {
                                            baseId = it; theme = themes.getValue(it); name = ""; message = null; saveMedia = false; replaceCurrent = false
                                        }
                                    }
                                    Text("色のテーマ", style = MaterialTheme.typography.titleMedium)
                                    ChoiceChips(themes.filterValues { it.wallpaper == null }.mapValues { it.value.label }, baseId) {
                                        baseId = it; theme = themes.getValue(it); name = ""; message = null; saveMedia = false; replaceCurrent = false
                                    }
                                    if (bundledMedia != null) Text("このテーマには保存した背景があります。")
                                    if (background.media.hasImage) {
                                        ThemeToggle("今の写真・動画もテーマに保存", saveMedia) { saveMedia = it }
                                    }
                                    if (edited && canReplace) ThemeToggle("このテーマを更新する", replaceCurrent) { replaceCurrent = it }
                                    if (previewMedia.hasImage) {
                                        ThemeToggle("テーマの背景だけ試す", previewThemeBackground) { previewThemeBackground = it }
                                        Text("テーマに保存した背景がある場合は、その背景を表示します。",
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (edited) OutlinedTextField(name, { name = it.take(80) }, label = { Text("新しいテーマ名（任意）") },
                                        singleLine = true, modifier = Modifier.fillMaxWidth())
                                    OutlinedButton(onClick = { importTheme.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                                        Text("テーマ一式を読み込む")
                                    }
                                    OutlinedButton(onClick = {
                                        sharing = Triple(theme, null, false); exportTheme.launch("komorebi-theme.zip")
                                    }) { Text("配色・キー設定だけを書き出す") }
                                    if (bundledMedia != null) OutlinedButton(onClick = {
                                        sharing = Triple(theme, bundles.folder(baseId), true); exportTheme.launch("komorebi-theme-background.zip")
                                    }) { Text("保存済みの背景も書き出す") }
                                    Text("テーマを自動で切り替える", style = MaterialTheme.typography.titleMedium)
                                    ChoiceChips(ThemeSchedule.Mode.entries.associateWith { it.title }, scheduleMode) {
                                        scheduleMode = it; schedule.mode = it
                                    }
                                    if (scheduleMode != ThemeSchedule.Mode.OFF) {
                                        ThemeDropdown("切り替え先", mapOf<String?, String>(null to "指定なし") +
                                            themes.map { (id, item) -> (id as String?) to item.label }.toMap(), scheduleId) {
                                            scheduleId = it; schedule.themeId = it
                                        }
                                        if (scheduleMode == ThemeSchedule.Mode.HOURS) {
                                            val hours = (0..23).associateWith { "%02d:00".format(it) }
                                            ThemeDropdown("開始", hours, scheduleStart) { scheduleStart = it; schedule.start = it }
                                            ThemeDropdown("終了", hours, scheduleEnd) { scheduleEnd = it; schedule.end = it }
                                            if (scheduleStart == scheduleEnd) Text("開始と終了が同じ場合は切り替えません。")
                                        }
                                        Text("アプリ別の指定を優先します。切り替えはキーボードを開くときに反映します。",
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    DressUpAdvanced(repo, true, baseId, revision, onImport = { id ->
                                        settings.markCustomizationChanged()
                                        revision++
                                        repo.loadTheme(id)?.let { baseId = id; theme = it; name = ""; saveMedia = false; replaceCurrent = false }
                                    }, onRemove = { id ->
                                        bundles.delete(id)
                                        settings.markCustomizationChanged()
                                        if (appliedId == id && repo.loadTheme(id) == null) {
                                            settings.activeThemeId = ImeSettings.DEFAULT_THEME_ID
                                            appliedId = ImeSettings.DEFAULT_THEME_ID
                                        }
                                        baseId = if (repo.loadTheme(id) != null) id else ImeSettings.DEFAULT_THEME_ID
                                        theme = repo.loadTheme(baseId) ?: MaterialYouTheme.create(context)
                                        name = ""
                                        revision++
                                    })
                                }
                                EditorPage.LAYOUT -> {
                                    Text("配列プリセット", style = MaterialTheme.typography.titleLarge)
                                    Text("キーの並びを試してから「適用」。自動を選ぶと入力方式に合わせて切り替わります。",
                                        style = MaterialTheme.typography.bodyMedium)
                                    val primary = layouts.filterKeys { !it.contains("symbol") }
                                    val choices = mapOf<String?, String>(null to "入力方式に合わせる") +
                                        primary.mapValues { (id, layout) -> layoutPresetName(id, layout) }
                                    ChoiceChips(choices, selectedLayoutId) { selectLayout(it) }
                                    val symbols = layouts.filterKeys { it.contains("symbol") }
                                    if (symbols.isNotEmpty()) {
                                        Text("記号の配列", style = MaterialTheme.typography.titleMedium)
                                        val symbolChoices: Map<String?, String> = symbols.entries.associate { (id, layout) ->
                                            id to layoutPresetName(id, layout)
                                        }
                                        ChoiceChips(symbolChoices, selectedLayoutId) { selectLayout(it) }
                                    }
                                    OutlinedButton(onClick = {
                                        openLayoutEditor.launch(Intent(context, VisualLayoutEditorActivity::class.java)
                                            .putExtra(VisualLayoutEditorActivity.EXTRA_LAYOUT_ID, layout?.id))
                                    }) { Text("キーの並びや動作を編集") }
                                    DressUpAdvanced(repo, false, selectedLayoutId, revision, onImport = { id ->
                                        settings.markCustomizationChanged()
                                        revision++
                                        selectedLayoutId = id
                                        repo.loadLayout(id)?.let { qwerty = isQwerty(it) }
                                    }, onRemove = { id ->
                                        bundles.delete(id)
                                        settings.markCustomizationChanged()
                                        if (repo.loadLayout(id) == null) {
                                            if (appliedLayoutId == id) {
                                                settings.activeLayoutId = null
                                                appliedLayoutId = null
                                            }
                                            selectLayout(null)
                                        }
                                        revision++
                                    })
                                }
                                EditorPage.BACKGROUND -> {
                                    LaunchedEffect(Unit) { previewThemeBackground = false }
                                    background.controls()
                                    Text("テーマの背景イラスト", style = MaterialTheme.typography.titleMedium)
                                    ArtPicker(listOf(null) + ThemeWallpaper.entries, theme.wallpaper,
                                        { it?.label ?: "なし" }, { it?.asset }, { selected ->
                                            theme = theme.copy(wallpaper = selected)
                                            if (selected != null && !background.media.hasImage) {
                                                colorScope.launch {
                                                    val color = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                        DressUpColors.fromAsset(context, selected.asset)
                                                    }
                                                    if (color != null && theme.wallpaper == selected) theme = DressUpColors.apply(theme, color)
                                                }
                                            }
                                        })
                                    if (theme.wallpaper != null) ThemeSlider("イラストの濃さ", theme.wallpaperOpacity * 100f, 0f..100f, "%") {
                                        theme = theme.copy(wallpaperOpacity = it / 100f)
                                    }
                                    Text("背景の演出", style = MaterialTheme.typography.titleMedium)
                                    ChoiceChips(mapOf(WallpaperMotion.NONE to "なし", WallpaperMotion.LIGHTS to "光の粒",
                                        WallpaperMotion.SNOW to "雪"), theme.wallpaperMotion) { theme = theme.copy(wallpaperMotion = it) }
                                }
                                EditorPage.SHAPE -> {
                                    Text("キーの区切り", style = MaterialTheme.typography.titleMedium)
                                    OutlinedButton(onClick = {
                                        theme = theme.copy(keyFrame = KeyFrame.FILLED, keyOpacity = 1f, labelShadow = true)
                                    }) { Text("キーを見やすくする") }
                                    ChoiceChips(frameNames, theme.frameFor(null)) { theme = theme.copy(keyFrame = it) }
                                    ThemeSlider("線の太さ", theme.lineWidthDp, 0.5f..4f, "dp") { theme = theme.copy(lineWidthDp = it) }
                                    ChoiceChips(mapOf(0f to "四角", 8f to "角丸", 32f to "大きな丸み"), theme.keyCornerRadiusDp) {
                                        theme = theme.copy(keyCornerRadiusDp = it)
                                    }
                                    ThemeSlider("角の丸み", theme.keyCornerRadiusDp, 0f..32f, "dp") { theme = theme.copy(keyCornerRadiusDp = it) }
                                    ThemeSlider("キーの間隔", theme.keyGapDp, 0f..16f, "dp") { theme = theme.copy(keyGapDp = it) }
                                    ThemeSlider("キーの高さ", theme.keyHeightDp, 32f..96f, "dp") { theme = theme.copy(keyHeightDp = it) }
                                    ThemeSlider("キー面の濃さ", theme.keyOpacity * 100f, 0f..100f, "%") { theme = theme.copy(keyOpacity = it / 100f) }
                                    Text("文字キーの画像", style = MaterialTheme.typography.titleMedium)
                                    TexturePicker(theme.keyTexture) { theme = theme.copy(keyTexture = it ?: KeyTexture.NONE) }
                                    Text("操作キーの画像", style = MaterialTheme.typography.titleMedium)
                                    TexturePicker(theme.modifierTexture) { theme = theme.copy(modifierTexture = it ?: KeyTexture.NONE) }
                                    TextButton(onClick = {
                                        openLayoutEditor.launch(Intent(context, VisualLayoutEditorActivity::class.java)
                                            .putExtra(VisualLayoutEditorActivity.EXTRA_LAYOUT_ID, layout?.id))
                                    }) { Text("キーを一つずつ編集") }
                                }
                                EditorPage.COLORS -> {
                                    Text("まとめて調整", style = MaterialTheme.typography.titleMedium)
                                    OutlinedButton(onClick = { matchImageColors() }, modifier = Modifier.fillMaxWidth()) {
                                        Text("背景画像に色を合わせる")
                                    }
                                    val photo = background.media.hasImage && !previewThemeBackground && settings.backgroundPresetId == null
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { theme = DressUpColors.highContrast(theme, true, photo) },
                                            modifier = Modifier.weight(1f)) { Text("黒文字でくっきり") }
                                        OutlinedButton(onClick = { theme = DressUpColors.highContrast(theme, false, photo) },
                                            modifier = Modifier.weight(1f)) { Text("白文字でくっきり") }
                                    }
                                    ThemeToggle("動く演出を減らす", settings.reducedKeyboardMotion) {
                                        settings.reducedKeyboardMotion = it; revision++
                                    }
                                    Text("文字・キーとツールのアイコンの色", style = MaterialTheme.typography.bodySmall)
                                    var unifiedHex by remember { mutableStateOf("#%06X".format(theme.labelColor and 0xFFFFFF)) }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { unifiedHex = "#000000"; theme = DressUpColors.unifiedText(theme, android.graphics.Color.BLACK) }, modifier = Modifier.weight(1f)) { Text("まとめて黒") }
                                        OutlinedButton(onClick = { unifiedHex = "#FFFFFF"; theme = DressUpColors.unifiedText(theme, android.graphics.Color.WHITE) }, modifier = Modifier.weight(1f)) { Text("まとめて白") }
                                    }
                                    OutlinedTextField(unifiedHex, { value ->
                                        unifiedHex = value.take(9)
                                        parseThemeColor(unifiedHex)?.let { theme = DressUpColors.unifiedText(theme, it) }
                                    }, label = { Text("文字とアイコンの色をまとめて指定") }, singleLine = true,
                                        supportingText = { Text("#RRGGBB または #AARRGGBB") },
                                        isError = parseThemeColor(unifiedHex) == null, modifier = Modifier.fillMaxWidth())
                                    Text("細かく調整", style = MaterialTheme.typography.titleMedium)
                                    ThemeToggle("候補に背景色を付ける", theme.useCandidateBackground) { theme = theme.copy(useCandidateBackground = it) }
                                    var prop by remember { mutableStateOf(colors.first()) }
                                    ChoiceChips(colors.associateWith { it.title }, prop) { prop = it }
                                    key(prop) {
                                        var hex by remember(prop.get(theme)) { mutableStateOf("#%08X".format(prop.get(theme))) }
                                        val parsed = parseThemeColor(hex)
                                        OutlinedTextField(hex, {
                                            hex = it.take(9)
                                            parseThemeColor(hex)?.let { color -> theme = prop.set(theme, color) }
                                        }, label = { Text(prop.title) }, singleLine = true, isError = parsed == null,
                                            supportingText = { Text("#RRGGBB または #AARRGGBB") }, modifier = Modifier.fillMaxWidth())
                                        if (parsed != null) {
                                            val hsv = FloatArray(3)
                                            android.graphics.Color.colorToHSV(parsed, hsv)
                                            ThemeSlider("色相", hsv[0], 0f..360f, "°") {
                                                hsv[0] = it
                                                hex = "#%08X".format(android.graphics.Color.HSVToColor(android.graphics.Color.alpha(parsed), hsv))
                                                theme = prop.set(theme, requireNotNull(parseThemeColor(hex)))
                                            }
                                            ThemeSlider("鮮やかさ", hsv[1] * 100, 0f..100f, "%") {
                                                hsv[1] = it / 100
                                                val color = android.graphics.Color.HSVToColor(android.graphics.Color.alpha(parsed), hsv)
                                                hex = "#%08X".format(color); theme = prop.set(theme, color)
                                            }
                                            ThemeSlider("明るさ", hsv[2] * 100, 0f..100f, "%") {
                                                hsv[2] = it / 100
                                                val color = android.graphics.Color.HSVToColor(android.graphics.Color.alpha(parsed), hsv)
                                                hex = "#%08X".format(color); theme = prop.set(theme, color)
                                            }
                                            ThemeSlider("色の不透明度", android.graphics.Color.alpha(parsed) * 100f / 255, 0f..100f, "%") {
                                                val color = (parsed and 0xffffff) or ((it * 255 / 100).toInt() shl 24)
                                                hex = "#%08X".format(color); theme = prop.set(theme, color)
                                            }
                                        }
                                    }
                                }
                                EditorPage.EFFECTS -> KeywordEffectsEditor(theme.keywordEffects) {
                                    theme = theme.copy(keywordEffects = it)
                                }
                                EditorPage.TEXT -> {
                                    Text("キートップの書体", style = MaterialTheme.typography.titleMedium)
                                    ChoiceChips(mapOf(KeyFont.SYSTEM to "標準", KeyFont.ROUNDED to "丸ゴシック系",
                                        KeyFont.SERIF to "明朝系", KeyFont.MONOSPACE to "等幅"), theme.labelFont) { theme = theme.copy(labelFont = it) }
                                    ThemeSlider("キー文字の大きさ", theme.labelSizeSp, 10f..32f, "sp") { theme = theme.copy(labelSizeSp = it) }
                                    ThemeToggle("太字", theme.labelBold) { theme = theme.copy(labelBold = it) }
                                    ThemeToggle("文字の影", theme.labelShadow) { theme = theme.copy(labelShadow = it) }
                                    ThemeToggle("長押しのヒント", theme.showLongPressHints) { theme = theme.copy(showLongPressHints = it) }
                                    ThemeToggle("フリックガイド", theme.showFlickGuides) { theme = theme.copy(showFlickGuides = it) }
                                    if (theme.showFlickGuides) {
                                        ChoiceChips(mapOf(ThemeFlickGuide.INHERIT to "入力設定に従う", ThemeFlickGuide.PREVIEW to "選択中の文字",
                                            ThemeFlickGuide.DIRECTIONS to "全方向"), theme.flickGuideMode) { theme = theme.copy(flickGuideMode = it) }
                                    }
                                    ThemeToggle("キー音", theme.keyClickSound) { theme = theme.copy(keyClickSound = it) }
                                    if (theme.keyClickSound) {
                                        ChoiceChips(mapOf(KeySound.SYSTEM to "端末標準", KeySound.WOOD to "木", KeySound.GLASS to "ガラス",
                                            KeySound.SOFT to "ソフト"), theme.keySound) { theme = theme.copy(keySound = it) }
                                        if (theme.keySound != KeySound.SYSTEM) ThemeSlider("キー音の音量", theme.keySoundVolume * 100, 0f..100f, "%") {
                                            theme = theme.copy(keySoundVolume = it / 100)
                                        }
                                    }
                                    Text("タップの演出", style = MaterialTheme.typography.titleMedium)
                                    ChoiceChips(mapOf(TapEffect.NONE to "なし", TapEffect.RING to "波紋",
                                        TapEffect.SPARKLE to "きらめき"), theme.tapEffect) { theme = theme.copy(tapEffect = it) }
                                    Text("押したときの強調", style = MaterialTheme.typography.titleMedium)
                                    ChoiceChips(mapOf(PressFeedback.FILL to "面の色", PressFeedback.OUTLINE to "枠線",
                                        PressFeedback.NONE to "なし"), theme.pressFeedback) { theme = theme.copy(pressFeedback = it) }
                                }
                            }
                        }
                    }
                }
            }
            if (sideBySide) {
                Row(Modifier.fillMaxSize()) {
                    preview(Modifier.weight(1f))
                    controls(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    controls(Modifier.weight(1f).fillMaxWidth())
                    preview(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private fun isQwerty(layout: KeyboardLayout): Boolean = layout.id.startsWith("qwerty_") ||
    layout.inputStyle == me.zssu.ime.keyboard.InputStyle.QWERTY_HIRAGANA ||
    layout.inputStyle == me.zssu.ime.keyboard.InputStyle.QWERTY_HALFWIDTH_ASCII

private fun layoutPresetName(id: String, layout: KeyboardLayout): String = when (id) {
    "flick_kana" -> "かなフリック"
    "flick_ascii" -> "英字フリック"
    "qwerty_kana" -> "かなQWERTY"
    "qwerty_ascii" -> "英字QWERTY"
    "flick_symbol" -> "フリック記号 1"
    "flick_symbol2" -> "フリック記号 2"
    "qwerty_symbol" -> "QWERTY記号 1"
    "qwerty_symbol2" -> "QWERTY記号 2"
    else -> layout.label
}

@Composable
internal fun ThemeKeyboardPreview(
    theme: KeyboardTheme,
    layout: KeyboardLayout,
    modifier: Modifier = Modifier.fillMaxWidth(),
    media: DressUpMedia? = null,
    maxHeight: androidx.compose.ui.unit.Dp = 215.dp,
) {
    val keyHeight = (theme.keyHeightDp * 0.62f)
        .coerceAtMost(((maxHeight.value - 28f) / layout.rows.size.coerceAtLeast(1)).coerceAtLeast(10f))
    key(media?.revision) {
    AndroidView(
        factory = { context ->
            KeyboardPanelView(context).apply {
                val strip = LinearLayout(context).apply { tag = "preview-strip" }
                for (sample in listOf("候補", "変換", "設定")) {
                    strip.addView(android.widget.TextView(context).apply {
                        text = sample
                        gravity = android.view.Gravity.CENTER
                        textSize = 13f
                    }, LinearLayout.LayoutParams(0, (28 * resources.displayMetrics.density).toInt(), 1f))
                }
                addView(strip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                val keyboard = FlickKeyboardView(context).apply { tag = "preview-keys" }
                val sample = strip.getChildAt(0) as android.widget.TextView
                keyboard.listener = FlickKeyboardView.OnKeyOutputListener { output, _, _ ->
                    sample.text = output.label.take(16)
                }
                addView(keyboard, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
            }
        },
        modifier = modifier,
        update = { panel ->
            panel.setBackgroundColor(theme.backgroundColor)
            panel.setWallpaper(theme.wallpaper, theme.wallpaperOpacity, theme.wallpaperMotion)
            if (panel.tag != media) {
                media?.render(panel)
                panel.tag = media
            }
            val strip = panel.findViewWithTag<LinearLayout>("preview-strip")
            for (i in 0 until strip.childCount) {
                (strip.getChildAt(i) as android.widget.TextView).apply {
                    val isTool = i == strip.childCount - 1
                    val toolbarTint = theme.toolbarIconColor.takeUnless { it == android.graphics.Color.TRANSPARENT }
                    setTextColor(if (isTool) toolbarTint ?: theme.candidateTextColor else theme.candidateTextColor)
                    setBackgroundColor(if (isTool) theme.toolbarBackgroundColor else
                        if (theme.useCandidateBackground) theme.candidateBackgroundColor else android.graphics.Color.TRANSPARENT)
                }
            }
            panel.findViewWithTag<FlickKeyboardView>("preview-keys").apply {
                this.theme = theme.copy(keyHeightDp = keyHeight)
                if (this.layout != layout) this.layout = layout
            }
        },
    )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> ChoiceChips(options: Map<T, String>, value: T, onChange: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (option, name) ->
            FilterChip(value == option, { onChange(option) }, label = { Text(name) })
        }
    }
}

@Composable
internal fun <T> ArtPicker(options: List<T>, value: T, label: (T) -> String, asset: (T) -> String?, select: (T) -> Unit) {
    val context = LocalContext.current
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { item ->
            val name = asset(item)
            val bitmap = remember(name) { name?.let { ThemeArt.load(context, it)?.asImageBitmap() } }
            OutlinedCard(onClick = { select(item) }, modifier = Modifier.width(100.dp)) {
                bitmap?.let {
                    Image(it, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(56.dp))
                }
                Text((if (item == value) "✓ " else "") + label(item), Modifier.padding(8.dp),
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
internal fun TexturePicker(value: KeyTexture?, includeInherit: Boolean = false, select: (KeyTexture?) -> Unit) {
    ArtPicker((if (includeInherit) listOf(null) else emptyList()) + KeyTexture.entries, value,
        { it?.label ?: "テーマに従う" }, { it?.asset }, select)
}

@Composable
internal fun ThemeSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String, onChange: (Float) -> Unit) {
    Text("$label  ${"%.1f".format(value).removeSuffix(".0")}$unit", style = MaterialTheme.typography.bodyMedium)
    Slider(value.coerceIn(range), onChange, valueRange = range)
}

@Composable
internal fun ThemeToggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, onChange)
    }
}

internal fun parseThemeColor(raw: String): Int? = raw.takeIf {
    it.startsWith("#") && (it.length == 7 || it.length == 9)
}?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() }

@Composable
internal fun <T> ThemeDropdown(label: String, options: Map<T, String>, selected: T, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("$label：${options[selected] ?: "指定なし"}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 280.dp)) {
            options.forEach { (id, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; onSelect(id) })
            }
        }
    }
}
