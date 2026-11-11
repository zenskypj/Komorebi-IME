package me.zssu.ime.settings

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.zssu.ime.theme.ZinnaTheme
import kotlin.math.abs

class InputGuideActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialPage = intent.getIntExtra(EXTRA_PAGE, 0).coerceIn(0, 3)
        setContent { ZinnaTheme { InputGuideScreen(initialPage) { finish() } } }
    }
    companion object {
        const val EXTRA_PAGE = "guide_page"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputGuideScreen(initialPage: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    var page by remember { mutableIntStateOf(initialPage) }
    val pages = listOf("入力", "変換・文節", "カーソル", "ツール")
    val scroll = rememberScrollState()
    LaunchedEffect(page) { scroll.scrollTo(0) }
    Scaffold(topBar = {
        CenterAlignedTopAppBar(title = { Text("入力の使い方") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "設定に戻る") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pages.forEachIndexed { index, title ->
                    FilterChip(selected = page == index, onClick = { page = index }, label = { Text("${index + 1}  $title") })
                }
            }
            Column(Modifier.weight(1f).verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("${page + 1} / 4", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                when (page) {
                    0 -> {
                        Text("指を滑らせて、一文字。", style = MaterialTheme.typography.headlineSmall)
                        Text("フリックでは中央をタップして「あ」、触れたまま上下左右に動かすと「い・う・え・お」を入力できます。")
                        FlickLesson()
                        GuideCard("自分の入力方法を選ぶ") {
                            Text("フリック、QWERTY、かなと英字の組み合わせを選べます。大きい画面では片手モードも試してみてください。")
                            FilledTonalButton(onClick = { context.startActivity(Intent(context, InputSettingsActivity::class.java)) }) { Text("入力方式の設定") }
                            OutlinedButton(onClick = { context.startActivity(Intent(context, AppearanceActivity::class.java)) }) { Text("高さ・片手モードの設定") }
                        }
                        GuidePractice("あいうえお", "実際のキーボードで「あいうえお」を入力してみましょう。")
                    }
                    1 -> {
                        Text("変換と区切りの直し方", style = MaterialTheme.typography.headlineSmall)
                        Text("区切りとは、文章を変換するときの言葉のまとまりです。")
                        GuideCard("まずは変換") {
                            Text("1　「きょうはいいてんきです」と入力します。")
                            Text("2　「変換」を1回押します。QWERTYではスペースを押します。")
                            Text("3　表示が合っていれば、確定キーで文章全体を決められます。")
                            Text("最初に表示される言葉は、まだ確定していません。", style = MaterialTheme.typography.bodySmall)
                        }
                        GuideCard("区切りが違うとき") {
                            Text("例：「きょう｜はいいてんきです」を「きょうは｜いいてんきです」に直す")
                            Text("1　変換候補の右端にある「∨」を押します。")
                            Text("2　一覧の上にある「文節を調整」を押します。")
                            Text("3　直したいまとまりを選び、「広げる」または「縮める」を押します。")
                            Text("4　区切りが合ったら、候補を選びます。")
                            Text("「前の文節」「次の文節」で、直す場所を移せます。", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        GuideCard("候補を選ぶ") {
                            Text("1回目の後に文節や候補を操作せず「変換」をもう1回押すと、候補を選ぶ段階になります。このとき候補はまだ次に進みません。3回目から次の候補に進み、ページの先も続けて選べます。")
                            Text("候補をタップして選べます。長押しすると、意味や登録などの操作を開けます。")
                            Text("前のまとまりを確定すると、続きの候補が自動で表示されます。", style = MaterialTheme.typography.bodySmall)
                        }
                        GuidePractice("きょうはいいてんきです",
                            "下の練習欄に自分で「きょうはいいてんきです」と入力し、「変換」を押してから「∨」→「文節を調整」を試してください。",
                            allowExample = false)
                    }
                    2 -> {
                        Text("消して打ち直す前に、移動。", style = MaterialTheme.typography.headlineSmall)
                        GuideCard("スペースを長押し → 左右へ") {
                            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp)) {
                                Text("←     長押ししてから滑らせる     →", Modifier.fillMaxWidth().padding(20.dp), style = MaterialTheme.typography.titleMedium)
                            }
                            Text("キーの表示が「カーソル」に変わったら、指を左右へ。読みの途中を直せます。変換中は文節単位、確定後は入力先の文字単位で移動します。QWERTYでは左右にフリックしたまま長押しすると連続移動します。中心付近で止まり、離すほど速くなりますが上限があります。指を離すと停止します。")
                            Text("短い左右フリックとは別の操作です。変換中の短いフリックは文節の境界を変更します。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        GuideCard("範囲選択やコピーも、キーボードから") {
                            Text("メニュー →「カーソル・範囲選択」で、単語の前後・行頭末尾・選択開始／終了・コピー・貼り付けを操作できます。")
                        }
                        GuideCard("削除キーを左・上へフリック") {
                            Text("左へ少し動かすと左の単語、さらに遠くへ動かすと行頭まで削除します。上へ少し動かすと右の単語、さらに遠くへ動かすと行末まで削除します。")
                            Text("表示された操作名を確認してから指を離してください。改行は消しません。未確定入力中はその中が対象で、変換中は読みに戻して削除します。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        GuidePractice("今日はいい天気です。", "「例文を入れる」で練習用の文章を用意し、スペース長押しでカーソルを移動してください。")
                    }
                    else -> {
                        Text("いつもの5つを、すぐ手元に。", style = MaterialTheme.typography.headlineSmall)
                        ToolLesson()
                        GuideCard("ほかのツールを開く・並べ替える") {
                            Text("候補欄左のメニューボタンから一覧を開き、上下にスクロールすると全ツールへ移動できます。定型文や文体補正もここにあります。")
                            Text("「編集」でタイルを長押しして並べ替え。先頭の表示対象5件が上部に並びます。「標準順に戻す」で初期の並びへ戻せます。")
                            FilledTonalButton(onClick = { context.startActivity(Intent(context, SnippetActivity::class.java)) }) { Text("定型文を登録する") }
                            OutlinedButton(onClick = { context.startActivity(Intent(context, me.zssu.ime.ime.ProofreadModelActivity::class.java)) }) { Text("AI校正モデルを管理") }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    OutlinedButton(onClick = { page-- }, enabled = page > 0) { Text("前へ") }
                    if (page < 3) Button(onClick = { page++ }) { Text("次へ：${pages[page + 1]}") }
                    else Button(onClick = onBack) { Text("設定に戻る") }
                }
            }
        }
    }
}

@Composable
private fun GuideCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun FlickLesson() {
    var result by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf("あ") }
    GuideCard("この図で試す") {
        Text("中央をタップ、または触れて上下左右へ滑らせて離してください。")
        Box(Modifier.align(Alignment.CenterHorizontally).size(180.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClickLabel = "あを試す") { result = (result + "あ").takeLast(20) }
            .pointerInput(Unit) {
                var dx = 0f; var dy = 0f
                detectDragGestures(
                    onDragStart = { dx = 0f; dy = 0f; preview = "あ" },
                    onDragEnd = { result = (result + preview).takeLast(20); preview = "あ" },
                    onDragCancel = { preview = "あ" },
                ) { change, amount ->
                    change.consume(); dx += amount.x; dy += amount.y
                    preview = if (maxOf(abs(dx), abs(dy)) < 20.dp.toPx()) "あ"
                        else if (abs(dx) > abs(dy)) { if (dx < 0) "い" else "え" }
                        else if (dy < 0) "う" else "お"
                }
            }) {
            Text("う ↑", Modifier.align(Alignment.TopCenter).padding(16.dp))
            Text("← い", Modifier.align(Alignment.CenterStart).padding(12.dp))
            Text(preview, Modifier.align(Alignment.Center), style = MaterialTheme.typography.headlineLarge)
            Text("え →", Modifier.align(Alignment.CenterEnd).padding(12.dp))
            Text("お ↓", Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
        Text(result.ifEmpty { "入力した文字がここに出ます" }, style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { result = "" }) { Text("図の入力を消す") }
    }
}

@Composable
private fun GuidePractice(example: String, instruction: String, allowExample: Boolean = true) {
    var value by remember { mutableStateOf("") }
    GuideCard("実際のキーボードで練習") {
        Text(instruction)
        OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("練習用の入力欄") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (allowExample) TextButton(onClick = { value = example }) { Text("例文を入れる") }
            TextButton(onClick = { value = "" }) { Text("消去") }
        }
        Text("練習内容はガイドに保存しません。入力時には現在のキーボードの学習設定が適用されます。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToolLesson() {
    val labels = listOf("クリップボード", "絵文字", "片手モード", "AI校正", "設定")
    val details = listOf(
        "クリップボード：最近コピーした文章を選んで貼り付けます。機密モードでは履歴を収集しません。",
        "絵文字：最近使った項目・顔文字・お気に入りを選びます。長押しでお気に入りを変更できます。",
        "片手モード：全幅 → 左寄せ → 右寄せと切り替わります。",
        "AI文章校正：選択した文章やカーソル直前の文章を端末内で校正。モデル取得後に使え、差分を確認してから適用します。",
        "設定：入力方式、配列、外観、辞書などを変更します。",
    )
    var selected by remember { mutableIntStateOf(0) }
    GuideCard("標準ツールバーの見本") {
        Text("横にスクロールし、項目をタップすると使い方を確認できます。")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEachIndexed { index, label -> FilterChip(selected = selected == index, onClick = { selected = index }, label = { Text(label) }) }
        }
        Text(details[selected])
        Text("追加ツールの「キーボードの高さ」では、入力を続けたまま高さを調整し、保存・取消・標準へ戻す操作ができます。アプリ別設定があるときは、そのアプリの高さを変更します。")
    }
}
