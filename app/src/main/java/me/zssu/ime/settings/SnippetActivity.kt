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
            addView(TextView(context).apply { text = "定型文\nタップで編集、長押しで削除。端末内に暗号化して保存します。"; textSize = 18f })
            addView(Button(context).apply { text = "追加"; isEnabled = items.size < 100; setOnClickListener { edit(null) } })
            items.forEachIndexed { index, value ->
                addView(Button(context).apply {
                    text = value
                    setOnClickListener { edit(index) }
                    setOnLongClickListener {
                        AlertDialog.Builder(this@SnippetActivity).setMessage("この定型文を削除しますか？")
                            .setNegativeButton("キャンセル", null).setPositiveButton("削除") { _, _ ->
                                persist(store.entries().filterIndexed { i, _ -> i != index })
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
        val dialog = AlertDialog.Builder(this).setTitle("定型文（2000文字まで）").setView(input)
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
