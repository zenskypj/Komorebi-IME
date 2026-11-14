package me.zssu.ime.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doOnTextChanged
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.digitalink.recognition.*
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.zssu.ime.theme.ZinnaTheme
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class TextTool(val label: String) {
    TRANSLATE("翻訳"), INK("手書き入力"), OCR("画像から文字を読み取る"), CLIP_SEARCH("履歴を検索"), SNIPPET_SEARCH("定型文を検索"), SNIPPET_NAME("名前を入力")
}

/** In-memory handoff; editor identity and content are checked by the IME on return. */
internal object TextToolRequest {
    data class Request(val tool: TextTool, val source: String?, val anchor: VoiceEditorAnchor,
        val id: String = UUID.randomUUID().toString(), var result: String? = null, var snippetName: String? = null,
        val entries: List<String> = emptyList(), val expiresAt: Long = android.os.SystemClock.elapsedRealtime() + 10 * 60_000L)
    var pending: Request? = null
}

class TextToolActivity : ComponentActivity() {
    private var request: TextToolRequest.Request? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val active = TextToolRequest.pending?.takeIf { it.id == intent.getStringExtra("request") && android.os.SystemClock.elapsedRealtime() < it.expiresAt }
        if (active == null) { finish(); return }
        request = active
        setContent { ZinnaTheme { when (active.tool) { TextTool.CLIP_SEARCH, TextTool.SNIPPET_SEARCH, TextTool.SNIPPET_NAME -> Search(active); else -> Screen(active) } } }
    }

    override fun onDestroy() {
        if (isFinishing && TextToolRequest.pending === request && request?.result == null) TextToolRequest.pending = null
        super.onDestroy()
    }

    @Composable
    private fun Search(active: TextToolRequest.Request) {
        var query by remember { mutableStateOf("") }
        var template by remember { mutableStateOf(active.entries.firstOrNull().takeIf { active.tool == TextTool.SNIPPET_NAME }) }
        Surface {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
                .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(active.tool.label, style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { finish() }) { Text("閉じる") }
                }
                val selectedTemplate = template
                if (selectedTemplate != null) {
                    SelectionPreview(selectedTemplate)
                    var name by remember(selectedTemplate) { mutableStateOf(SnippetStore(this@TextToolActivity).name()) }
                    AndroidView(factory = { ctx ->
                        android.widget.EditText(ctx).apply {
                            setText(name); hint = "名前"; setSingleLine()
                            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                            filters = arrayOf(android.text.InputFilter.LengthFilter(100))
                            doOnTextChanged { value, _, _, _ -> name = value.toString() }
                        }
                    }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        if (TextToolRequest.pending === active) {
                            active.snippetName = name
                            active.result = selectedTemplate
                            finish()
                        }
                    }) { Text("この名前で入力") }
                    return@Column
                }
                // The native field marks the search as private even for a different active IME.
                AndroidView(factory = { ctx ->
                    android.widget.EditText(ctx).apply {
                        hint = "検索する文字"; setSingleLine()
                        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                        filters = arrayOf(android.text.InputFilter.LengthFilter(120))
                        doOnTextChanged { text, _, _, _ -> query = text.toString() }
                    }
                }, modifier = Modifier.fillMaxWidth())
                val results = active.entries.filter { it.contains(query.trim(), ignoreCase = true) }
                if (results.isEmpty()) Text("見つかりませんでした")
                results.forEach { entry ->
                    OutlinedButton(onClick = {
                        if (TextToolRequest.pending === active) {
                            if (active.tool == TextTool.SNIPPET_SEARCH && "{{名前}}" in entry) template = entry
                            else { active.result = entry; finish() }
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(entry, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    @Composable
    private fun Screen(active: TextToolRequest.Request) {
        val scope = rememberCoroutineScope()
        val labels = remember {
            TranslateLanguage.getAllLanguages().map { tag ->
                tag to java.util.Locale.forLanguageTag(tag).getDisplayLanguage(java.util.Locale.JAPANESE)
            }.sortedBy { it.second }
        }
        var from by remember { mutableStateOf(if (active.source.orEmpty().any { it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9fff' }) "ja" else "en") }
        var to by remember { mutableStateOf(if (from == "ja") "en" else "ja") }
        var items by remember { mutableStateOf(emptyList<String>()) }
        var selected by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var models by remember { mutableStateOf(emptyList<TranslateRemoteModel>()) }
        var managingModels by remember { mutableStateOf(false) }
        var canDraw by remember { mutableStateOf(false) }
        val scratch = remember { InkPad(this).apply { onChanged = { canDraw = hasInk; items = emptyList(); selected = "" } } }
        fun perform(work: suspend () -> List<String>) {
            if (busy) return
            busy = true; items = emptyList(); selected = ""; status = "処理しています…"
            scope.launch {
                try {
                    items = work().filter { it.isNotBlank() }.distinct()
                    selected = items.firstOrNull().orEmpty()
                    status = if (items.isEmpty()) "文字が見つかりませんでした" else ""
                } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                catch (_: Exception) { status = "処理できませんでした。モデルの取得時は通信と空き容量を確認して、もう一度お試しください。" }
                finally { busy = false }
            }
        }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) perform {
                TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()).use { recognizer ->
                    val bitmap = withContext(Dispatchers.IO) {
                        val bytes = me.zssu.ime.theme.LocalArt.photo(this@TextToolActivity, uri, 3072)
                        checkNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                    }
                    try { listOf(recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitValue().text.take(16_000)) }
                    finally { if (kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive != false) bitmap.recycle() }
                }
            }
        }
        Surface {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
                .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(active.tool.label, style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = { finish() }) { Text("閉じる") }
                }
                Text("本文や画像は端末内で処理します。Google ML Kit はモデル取得と利用診断に通信することがあります。")
                when (active.tool) {
                    TextTool.TRANSLATE -> {
                        Text("選んだ文章", style = MaterialTheme.typography.titleMedium)
                        SelectionPreview(active.source.orEmpty())
                        LanguagePicker("翻訳元", from, labels, !busy) { from = it; selected = ""; items = emptyList() }
                        LanguagePicker("翻訳先", to, labels, !busy) { to = it; selected = ""; items = emptyList() }
                        Text("初回は言語モデルを Wi-Fi で取得します。1言語およそ30 MB。取得後はオフラインで翻訳できます。")
                        TextButton(enabled = !busy, onClick = {
                            scope.launch {
                                try {
                                    models = RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
                                        .awaitValue().sortedBy { it.language }
                                    managingModels = true
                                } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                                catch (_: Exception) { status = "取得済みの言語を確認できませんでした" }
                            }
                        }) { Text("翻訳モデルを管理") }
                        Button(enabled = !busy && from != to, onClick = {
                            val options = TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build()
                            perform {
                                Translation.getClient(options).use { translator ->
                                    status = "言語モデルを確認しています…"
                                    translator.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build()).awaitValue()
                                    status = "翻訳しています…"
                                    listOf(translator.translate(active.source.orEmpty()).awaitValue())
                                }
                            }
                        }) { Text("Google 翻訳で翻訳する") }
                    }
                    TextTool.INK -> {
                        Text("指またはペンで文字を書いてください。初回は日本語モデルを Wi-Fi で取得します。")
                        AndroidView(factory = { scratch }, modifier = Modifier.fillMaxWidth().height(185.dp),
                            update = { it.isEnabled = !busy })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { scratch.undo() }, enabled = canDraw && !busy) { Text("一画戻す") }
                            OutlinedButton(onClick = { scratch.clear() }, enabled = canDraw && !busy) { Text("消去") }
                            Button(enabled = canDraw && !busy, onClick = {
                                val ink = scratch.ink()
                                perform {
                                    recognizeJapaneseInk(ink) { status = it }
                                }
                            }) { Text("読み取る") }
                        }
                    }
                    TextTool.OCR -> Button(enabled = !busy, onClick = { picker.launch("image/*") }) { Text("画像を選ぶ") }
                    TextTool.CLIP_SEARCH, TextTool.SNIPPET_SEARCH, TextTool.SNIPPET_NAME -> Unit
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (status.isNotEmpty()) Text(status, modifier = Modifier.fillMaxWidth())
                if (items.isNotEmpty()) {
                    Text(if (active.tool == TextTool.TRANSLATE) "Google 翻訳による自動翻訳" else "読み取り結果",
                        style = MaterialTheme.typography.titleMedium)
                    if (active.tool == TextTool.TRANSLATE) {
                        Surface(color = Color.White) {
                            Image(painterResource(me.zssu.ime.R.drawable.google_translation_attribution),
                                contentDescription = "Powered by Google Translate", modifier = Modifier.padding(8.dp).height(24.dp))
                        }
                        Text("Google は翻訳について、正確性・信頼性などの保証を行いません。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (items.size > 1) items.forEach { item ->
                        FilterChip(selected = selected == item, onClick = { selected = item }, label = { Text(item) })
                    } else if (active.tool == TextTool.OCR) {
                        AndroidView(factory = { ctx ->
                            android.widget.EditText(ctx).apply {
                                setText(selected)
                                imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                                filters = arrayOf(android.text.InputFilter.LengthFilter(16_000))
                                doOnTextChanged { text, _, _, _ -> selected = text.toString() }
                            }
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp))
                    } else SelectionPreview(selected)
                    Button(enabled = selected.isNotBlank(), onClick = {
                        if (TextToolRequest.pending === active) { active.result = selected; finish() }
                    }) { Text(if (active.source != null) "選択した文章を置き換える" else "入力する") }
                    TextButton(onClick = {
                        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(active.tool.label, selected))
                        status = "コピーしました"
                    }) { Text("コピー") }
                }
            }
        }
        if (managingModels) AlertDialog(onDismissRequest = { managingModels = false },
            title = { Text("取得済みの翻訳モデル") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    if (models.isEmpty()) Text("取得済みのモデルはありません")
                    models.forEach { model ->
                        val title = labels.firstOrNull { it.first == model.language }?.second ?: model.language
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    RemoteModelManager.getInstance().deleteDownloadedModel(model).awaitValue()
                                    models = models.filterNot { it == model }
                                } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                                catch (_: Exception) { status = "モデルを削除できませんでした"; managingModels = false }
                            }
                        }) { Text("$title を削除") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { managingModels = false }) { Text("閉じる") } })
    }

    @Composable
    private fun SelectionPreview(text: String) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
            Text(text, Modifier.fillMaxWidth().padding(12.dp))
        }
    }

    @Composable
    private fun LanguagePicker(label: String, tag: String, languages: List<Pair<String, String>>,
        enabled: Boolean, onSelect: (String) -> Unit) {
        var expanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
                Text("$label：${languages.firstOrNull { it.first == tag }?.second ?: tag}")
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 300.dp)) {
                languages.forEach { (code, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(code); expanded = false })
                }
            }
        }
    }
}

private suspend fun <T> Task<T>.awaitValue(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        when {
            task.isCanceled -> continuation.cancel()
            task.isSuccessful -> continuation.resume(task.result)
            else -> continuation.resumeWithException(task.exception ?: IllegalStateException("ML Kit task failed"))
        }
    }
}

internal class InkPad(context: android.content.Context) : View(context) {
    private data class Point(val x: Float, val y: Float, val time: Long)
    private val strokes = mutableListOf<MutableList<Point>>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        strokeWidth = 3f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }
    private var drawing = false
    val hasInk get() = strokes.isNotEmpty()
    var onChanged: (() -> Unit)? = null
    init { contentDescription = "手書き入力欄"; setBackgroundColor(android.graphics.Color.WHITE) }
    fun clear() { drawing = false; strokes.clear(); changed() }
    fun undo() { drawing = false; if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex); changed() }
    fun ink(): Ink = Ink.builder().apply {
        for (stroke in strokes) addStroke(Ink.Stroke.builder().apply {
            stroke.forEach { addPoint(Ink.Point.create(it.x, it.y, it.time)) }
        }.build())
    }.build()
    private fun changed() { invalidate(); onChanged?.invoke() }
    override fun onDraw(canvas: Canvas) {
        for (stroke in strokes) {
            val first = stroke.firstOrNull() ?: continue
            if (stroke.size == 1) canvas.drawPoint(first.x, first.y, paint)
            for (i in 1 until stroke.size) {
                val a = stroke[i - 1]; val b = stroke[i]
                canvas.drawLine(a.x, a.y, b.x, b.y, paint)
            }
        }
    }
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (strokes.size >= 256) return false
                parent.requestDisallowInterceptTouchEvent(true)
                drawing = true; strokes.add(mutableListOf(Point(event.x, event.y, event.eventTime))); changed()
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> if (drawing) {
                val stroke = strokes.last()
                if (stroke.size < 2048) stroke.add(Point(event.x, event.y, event.eventTime))
                if (event.actionMasked == MotionEvent.ACTION_UP) { drawing = false; parent.requestDisallowInterceptTouchEvent(false) }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> if (drawing) { undo(); parent.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }
}

internal suspend fun recognizeJapaneseInk(ink: Ink, status: (String) -> Unit): List<String> {
    val id = checkNotNull(DigitalInkRecognitionModelIdentifier.fromLanguageTag("ja"))
    val model = DigitalInkRecognitionModel.builder(id).build()
    status("日本語の手書きモデルを確認しています…")
    RemoteModelManager.getInstance().download(model, DownloadConditions.Builder().requireWifi().build()).awaitValue()
    status("手書き文字を調べています…")
    return DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build()).use { recognizer ->
        recognizer.recognize(ink).awaitValue().candidates.map { it.text }.filter { it.isNotBlank() }.distinct().take(12)
    }
}
