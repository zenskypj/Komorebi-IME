package me.zssu.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.util.TypedValue
import androidx.core.content.ContextCompat
import me.zssu.ime.R
import me.zssu.ime.theme.KeyboardTheme

/**
 * Popup overlay shown above the keyboard when a user long-presses a candidate.
 *
 * Renders the term with its meaning-dictionary definitions as a card that floats
 * over the input area rather than replacing the candidate strip.
 */
@SuppressLint("ViewConstructor")
class MeaningPopupView(context: Context) : FrameLayout(context) {

    var onDismiss: (() -> Unit)? = null
    private var primaryTextColor = Color.WHITE
    private var secondaryTextColor = Color.LTGRAY
    private var dividerColor = Color.GRAY
    private var dangerTextColor = Color.rgb(255, 128, 128)

    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val radius = (12 * resources.displayMetrics.density)
        background = GradientDrawable().apply {
            setColor(Color.argb(0xF0, 0x1E, 0x1E, 0x1E))
            cornerRadius = radius
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
    }

    init {
        visibility = GONE
        isClickable = true
        setBackgroundColor(Color.argb(0x70, 0, 0, 0))
        setOnClickListener { hide() }
        // A tap on the card itself must not fall through to the keyboard or dismiss the popup.
        card.isClickable = true
    }

    /**
     * Updates colours to match the current keyboard theme.
     */
    fun applyTheme(theme: KeyboardTheme) {
        val isDark = Color.red(theme.backgroundColor) < 128 &&
            Color.green(theme.backgroundColor) < 128 &&
            Color.blue(theme.backgroundColor) < 128
        primaryTextColor = if (isDark) Color.WHITE else Color.rgb(28, 28, 28)
        secondaryTextColor =
            if (isDark) Color.rgb(195, 195, 195) else Color.rgb(88, 88, 88)
        dividerColor =
            if (isDark) Color.argb(0x55, 255, 255, 255) else Color.argb(0x40, 0, 0, 0)
        dangerTextColor = if (isDark) Color.rgb(255, 145, 145) else Color.rgb(180, 28, 28)
        (card.background as? GradientDrawable)?.setColor(
            if (isDark) Color.argb(0xF0, 0x1E, 0x1E, 0x1E)
            else Color.argb(0xF0, 0xFF, 0xFF, 0xFF)
        )
    }

    /**
     * Shows a term with its meaning-dictionary definitions as a popup.
     */
    fun show(
        term: String,
        reading: String?,
        definitions: List<String>,
        canDelete: Boolean,
        onDelete: (() -> Unit)?,
        canPrioritize: Boolean,
        isPrioritized: Boolean,
        onTogglePriority: (() -> Unit)?,
    ) {
        card.removeAllViews()
        val pad = (12 * resources.displayMetrics.density).toInt()

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(ImageButton(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_material_arrow_back))
            imageTintList = ColorStateList.valueOf(primaryTextColor)
            contentDescription = "閉じる"
            background = null
            val p = (10 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setOnClickListener { hide() }
        })

        header.addView(TextView(context).apply {
            text = term
            setTextColor(primaryTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            setPadding(pad, 0, pad, 0)
            isSingleLine = true
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        card.addView(header)

        if (!reading.isNullOrEmpty()) {
            card.addView(TextView(context).apply {
                text = "［$reading］"
                setTextColor(secondaryTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(pad * 2, 0, pad, 0)
            })
        }

        val separator = View(context).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * resources.displayMetrics.density).toInt(),
            ).apply { setMargins(0, pad / 2, 0, pad / 2) }
        }
        card.addView(separator)

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            isFillViewport = true
        }
        val meaningsList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val displayedDefinitions =
            definitions.ifEmpty { listOf("意味辞書には登録されていません") }
        displayedDefinitions.forEachIndexed { index, def ->
            meaningsList.addView(TextView(context).apply {
                text = if (definitions.isEmpty()) def else "${index + 1}. $def"
                setTextColor(primaryTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setPadding(pad, pad / 3, pad, pad / 3)
            })
        }

        scroll.addView(meaningsList)
        card.addView(scroll)

        if (canPrioritize) {
            card.addView(TextView(context).apply {
                text = if (isPrioritized) "最優先を解除" else "最優先にする"
                val star = ContextCompat.getDrawable(context, R.drawable.ic_ime_star)?.mutate()
                star?.setTint(primaryTextColor)
                star?.setBounds(0, 0, (20 * resources.displayMetrics.density).toInt(), (20 * resources.displayMetrics.density).toInt())
                setCompoundDrawablesRelative(star, null, null, null)
                compoundDrawablePadding = (6 * resources.displayMetrics.density).toInt()
                setTextColor(primaryTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setPadding(pad, pad, pad, pad / 2)
                gravity = Gravity.CENTER
                setOnClickListener {
                    onTogglePriority?.invoke()
                    hide()
                }
            })
        }

        if (canDelete) {
            card.addView(TextView(context).apply {
                text = "学習削除"
                setTextColor(dangerTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setPadding(pad, pad, pad, pad / 2)
                gravity = Gravity.CENTER
                setOnClickListener {
                    onDelete?.invoke()
                    hide()
                }
            })
        }

        val margin = (10 * resources.displayMetrics.density).toInt()
        val cardParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.CENTER
            setMargins(margin, margin, margin, margin)
        }

        if (card.parent != null) (card.parent as android.view.ViewGroup).removeView(card)
        removeAllViews()
        addView(card, cardParams)
        visibility = VISIBLE
    }

    fun hide(notify: Boolean = true) {
        if (visibility != VISIBLE) return
        visibility = GONE
        if (notify) onDismiss?.invoke()
    }
}
