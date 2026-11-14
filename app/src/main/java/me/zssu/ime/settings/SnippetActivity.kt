package me.zssu.ime.settings

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.*
import me.zssu.ime.ime.SnippetStore

class SnippetActivity : Activity() {
    private val store by lazy { SnippetStore(this) }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); refresh() }
    private fun refresh() {
        val items = store.entries()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(TextView(context).apply { text = "定型文\nタップで編集、長押しで並べ替え・削除。端末内に暗号化して保存します。"; textSize = 18f })
            addView(Button(context).apply { text = "追加"; isEnabled = items.size < 100; setOnClickListener { edit(null) } })
            addView(Button(context).apply {
                text = "差し込み用の名前を設定"
                setOnClickListener {
                    val field = EditText(context).apply {
                        setText(store.name()); setSingleLine()
                        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                        filters = arrayOf(android.text.InputFilter.LengthFilter(100))
                    }
                    AlertDialog.Builder(this@SnippetActivity).setTitle("差し込み用の名前").setView(field)
                        .setNegativeButton("キャンセル", null).setPositiveButton("保存") { _, _ ->
                            if (!store.saveName(field.text.toString())) Toast.makeText(context, "保存できませんでした", Toast.LENGTH_LONG).show()
                        }.show()
                }
            })
            addView(Button(context).apply {
                text = "日付の形式を設定"
                setOnClickListener {
                    val field = EditText(context).apply {
                        setSingleLine(); setText(store.dateFormat())
                        hint = "yyyy年MM月dd日"
                        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    }
                    val dialog = AlertDialog.Builder(this@SnippetActivity).setTitle("日付の形式")
                        .setMessage("例：yyyy-MM-dd、yyyy年M月d日、M/d")
                        .setView(field).setPositiveButton("保存", null).setNegativeButton("戻る", null).create()
                    dialog.setOnShowListener {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            val pattern = field.text.toString().trim()
                            if (!me.zssu.ime.ime.SnippetTemplate.validDateFormat(pattern)) field.error = "この日付形式は使えません"
                            else { store.saveDateFormat(pattern); dialog.dismiss() }
                        }
                    }
                    dialog.show()
                }
            })
            items.forEachIndexed { index, value ->
                addView(Button(context).apply {
                    text = value
                    setOnClickListener { edit(index) }
                    setOnLongClickListener {
                        val actions = buildList {
                            if (index > 0) { add("先頭に移動" to 0); add("一つ上へ" to index - 1) }
                            if (index < items.lastIndex) add("一つ下へ" to index + 1)
                            add("削除" to -1)
                        }
                        AlertDialog.Builder(this@SnippetActivity).setItems(actions.map { it.first }.toTypedArray()) { _, choice ->
                            val target = actions[choice].second
                            if (target < 0) AlertDialog.Builder(this@SnippetActivity).setMessage("この定型文を削除しますか？")
                                .setNegativeButton("キャンセル", null).setPositiveButton("削除") { _, _ ->
                                    persist(items.filterIndexed { i, _ -> i != index })
                                }.show()
                            else persist(items.toMutableList().apply { add(target, removeAt(index)) })
                        }.show()
                        true
                    }
                })
            }
            addView(Button(context).apply { text = "戻る"; setOnClickListener { finish() } })
        }
        val scroll = ScrollView(this).apply { addView(panel) }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
    }
    private fun edit(index: Int?) {
        val input = EditText(this).apply {
            minLines = 3
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(android.text.InputFilter.LengthFilter(2000))
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setText(index?.let { store.entries().getOrNull(it) }.orEmpty())
        }
        val editor = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = "差し込み項目をタップして追加できます。" })
            addView(LinearLayout(context).apply {
                for (label in listOf("名前", "日付", "選択", "カーソル")) addView(Button(context).apply {
                    text = label
                    setOnClickListener {
                        input.text.insert(input.selectionStart.coerceAtLeast(0), "{{$label}}")
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })
            addView(input)
        }
        val dialog = AlertDialog.Builder(this).setTitle("定型文（2000文字まで）").setView(editor)
            .setNegativeButton("キャンセル", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (input.text.isBlank()) { input.error = "本文を入力してください"; return@setOnClickListener }
                val next = store.entries().toMutableList()
                if (index == null) next.add(input.text.toString()) else if (index in next.indices) next[index] = input.text.toString()
                if (persist(next)) dialog.dismiss()
            }
        }
        dialog.show()
    }
    private fun persist(items: List<String>): Boolean {
        if (!store.save(items)) { Toast.makeText(this, "保存できませんでした", Toast.LENGTH_LONG).show(); return false }
        refresh(); return true
    }
}
