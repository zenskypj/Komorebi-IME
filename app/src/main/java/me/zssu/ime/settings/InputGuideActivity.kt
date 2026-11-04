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
        setContent { ZinnaTheme { InputGuideScreen { finish() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputGuideScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var page by remember { mutableIntStateOf(0) }
    val pages = listOf("入力", "変換・文節", "カーソル", "ツール")
    val scroll = rememberScrollState()
    LaunchedEffect(page) { scroll.scrollTo(0) }
    Scaffold(topBar = {
        CenterAlignedTopAppBar(title = { Text("入力の使い方") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "設定に戻る") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
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
                        Text("入力中の範囲も、変換後の文節も整える。", style = MaterialTheme.typography.headlineSmall)
                        GuideCard("変換キーの順番") {
                            Text("① 読みを入力 → ② 変換を1回押して先頭候補 → ③ 表示中の候補を変換キーで巡回。左右スワイプで別の候補へ進み、右端の下向きボタンで一覧を開きます。候補の1行／2行と文字サイズは外観設定を反映します。候補のタップか確定キーで決定します。")
                            Text("入力中の左右キーで選んだ読みの先頭側の区切りは、変換時に引き継ぎます。変換1回目の直後は左右キーで前／次の文節へ移動し、もう一度変換して候補選択に入ると左右キーで文節を縮める／広げる操作になります。前の文節を確定して読みが残ったときは、次の文節の候補が自動で開きます。2文節目以降に変換キーを押し直す必要はありません。")
                            Text("候補一覧の「文節を調整」で文節操作を開けます。候補の意味や優先登録は、候補を長押しすると開けます。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        SegmentLesson()
                        GuidePractice("きょうはいいてんきです", "読みを入力して変換し、左右キーで文節移動。もう一度変換を押してから、左右キーで区切りの変更を試してください。文節操作ボタンは候補一覧の「文節を調整」から開けます。")
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
private fun SegmentLesson() {
    var parts by remember { mutableStateOf(listOf("きょうは", "いい", "てんきです")) }
    var selected by remember { mutableIntStateOf(0) }
    GuideCard("区切りを試す（操作イメージ）") {
        Text("文節をタップして対象を選びます。移動は対象を変更し、縮小・拡大は選んだ文節の右端を変更します。")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            parts.forEachIndexed { i, text -> FilterChip(selected = selected == i, onClick = { selected = i }, label = { Text(text) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { selected-- }, enabled = selected > 0, modifier = Modifier.weight(1f)) { Text("前の文節") }
            OutlinedButton(onClick = { selected++ }, enabled = selected < parts.lastIndex, modifier = Modifier.weight(1f)) { Text("次の文節") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                parts = parts.toMutableList().also { p -> p[selected + 1] = p[selected].takeLast(1) + p[selected + 1]; p[selected] = p[selected].dropLast(1) }
            }, enabled = selected < parts.lastIndex && parts[selected].length > 1, modifier = Modifier.weight(1f)) { Text("縮める") }
            FilledTonalButton(onClick = {
                parts = parts.toMutableList().also { p -> p[selected] += p[selected + 1].take(1); p[selected + 1] = p[selected + 1].drop(1) }
            }, enabled = selected < parts.lastIndex && parts[selected + 1].length > 1, modifier = Modifier.weight(1f)) { Text("広げる") }
        }
        TextButton(onClick = { parts = listOf("きょうは", "いい", "てんきです"); selected = 0 }) { Text("最初の区切りに戻す") }
    }
}

@Composable
private fun GuidePractice(example: String, instruction: String) {
    var value by remember { mutableStateOf("") }
    GuideCard("実際のキーボードで練習") {
        Text(instruction)
        OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("練習用の入力欄") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { value = example }) { Text("例文を入れる") }
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
