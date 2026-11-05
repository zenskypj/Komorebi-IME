package me.zssu.ime.keyboard

import android.content.Context
import androidx.core.content.ContextCompat
import android.graphics.drawable.Drawable
import me.zssu.ime.R
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import me.zssu.ime.theme.KeyboardTheme
import kotlin.math.max
import kotlin.math.min

/**
 * Renders a [KeyboardLayout] and turns touches into [KeyOutput]s.
 *
 * Flick resolution is deliberately handled here rather than delegated to mozc's toggle table: we
 * know the pointer geometry, so we can commit the moment the finger crosses the threshold and give
 * the user a live guide of where each direction leads. mozc then receives an already-resolved kana.
 *
 * Multi-touch is supported to the extent that matters for typing speed — a second finger landing
 * while the first is still down commits the first key immediately, which is what fast thumb typists
 * expect from Simeji and Gboard.
 */
class FlickKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    fun interface OnKeyOutputListener {
        fun onKeyOutput(output: KeyOutput, key: KeySpec, direction: FlickDirection)
    }

    var listener: OnKeyOutputListener? = null
    var onTrackpadMove: ((Int) -> Unit)? = null
    var onRepeatStopped: (() -> Unit)? = null
    var isRepeatingOutput: Boolean = false
        private set

    /** Relabels a space/convert key to match what pressing it will do right now. */
    var isConversionAvailable: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var theme: KeyboardTheme = KeyboardTheme.Default
        set(value) {
            field = value
            applyTheme()
            invalidate()
        }

    /** Whether the next letter, or every letter, is capitalised. See [KeyAction.Shift]. */
    private enum class ShiftState { OFF, ONCE, LOCKED }

    private var shift = ShiftState.OFF

    var layout: KeyboardLayout? = null
        set(value) {
            field = value
            // A plane switch starts fresh: a shift armed on the symbol page means nothing on the
            // one being switched to, and leaving it latched is the kind of state users cannot see
            // the cause of.
            shift = ShiftState.OFF
            // Recompute here rather than waiting for onSizeChanged: every plane has the same row
            // count, so swapping kana → ascii leaves the view exactly the same size and
            // onSizeChanged never fires. Relying on it left the new layout with no placed keys at
            // all — a blank keyboard that ignores touches.
            activeTouches.clear()
            cancelTimers()
            refreshPlacement()
            requestLayout()
            invalidate()
        }

    /**
     * Distance in dp the finger must travel before a flick registers. Exposed because thumb size
     * and screen density vary enough that a single value annoys somebody; the settings screen maps
     * a slider onto it.
     */
    var flickThresholdDp: Float = DEFAULT_FLICK_THRESHOLD_DP

    /**
     * Pixels above this view that the flick guide may draw into — in practice the height of the
     * candidate strip sitting directly above. Requires the parent to have `clipChildren = false`,
     * otherwise the guide is still cut at this view's top edge.
     */
    var guideOverflowTop: Float = 0f

    /** What to draw while a finger rests on a flick key. See [FlickGuideStyle]. */
    var guideStyle: FlickGuideStyle = FlickGuideStyle.DIRECTIONS
        set(value) {
            field = value
            invalidate()
        }

    /** Reused so the preview costs no allocation on a path that redraws as the finger moves. */
    private val previewRect = RectF()

    private data class PlacedKey(val spec: KeySpec, val bounds: RectF)

    /** State for one finger currently on the keyboard. */
    private class Touch(
        val key: PlacedKey,
        val downX: Float,
        val downY: Float,
        val path: FlickResolver.PathTracker = FlickResolver.PathTracker(downX, downY),
        var direction: FlickDirection = FlickDirection.CENTER,
        /** Set for repeatable keys, which emit on press instead of on release. */
        var firedOnPress: Boolean = false,
        /** Set once a hold-to-repeat has fired, so release does not add one extra centre hit. */
        var repeated: Boolean = false,
        /** Set once [KeySpec.longPress] fires, so release does not also emit the centre output. */
        var longPressed: Boolean = false,
        var trackpad: Boolean = false,
        var trackpadMotion: TrackpadMotion? = null,
        var spaceHoldMotion: SpaceHoldMotion? = null,
        var deleteGesture: Boolean = false,
        var deleteLine: Boolean = false,
    )

    private var placedKeys: List<PlacedKey> = emptyList()
    private val activeTouches = mutableMapOf<Int, Touch>()

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boundaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val modifierPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val longPressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        alpha = 180
    }
    private val guideBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val guideLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val guideHitAreaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val handler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null
    private var longPressRunnable: Runnable? = null

    private val flickThresholdPx: Float
        get() = flickThresholdDp * resources.displayMetrics.density

    init {
        isFocusable = false
        isClickable = true
        applyTheme()
    }

    private fun applyTheme() {
        keyPaint.color = theme.keyColor
        keyPressedPaint.color = theme.keyPressedColor
        boundaryPaint.color = theme.labelColor
        boundaryPaint.alpha = 95
        boundaryPaint.strokeWidth = resources.displayMetrics.density
        modifierPaint.color = theme.modifierKeyColor
        labelPaint.color = theme.labelColor
        longPressPaint.color = theme.labelColor
        guideBackgroundPaint.color = theme.flickGuideColor
        guideLabelPaint.color = theme.flickGuideLabelColor
        guideHitAreaPaint.color = theme.flickGuideSelectedLabelColor
        // The parent paints the panel — a colour, or the user's background image. Painting it here
        // too would cover that image with an opaque rectangle.
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rowCount = layout?.rows?.size ?: 4
        val height = (theme.keyHeightDp * resources.displayMetrics.density * rowCount).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        labelPaint.textSize = spToPx(theme.labelSizeSp)
        longPressPaint.textSize = spToPx(theme.labelSizeSp * 0.55f)
        guideLabelPaint.textSize = spToPx(theme.labelSizeSp)
        refreshPlacement()
    }

    /** Applies Android 14+'s non-linear accessibility font scaling, with legacy compatibility. */
    private fun spToPx(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics,
    )

    private fun refreshPlacement() {
        placedKeys = if (width > 0 && height > 0) {
            computePlacement(width.toFloat(), height.toFloat())
        } else {
            emptyList()
        }
    }

    private fun computePlacement(width: Float, height: Float): List<PlacedKey> {
        val current = layout ?: return emptyList()
        val totalRowWeight = current.rows.sumOf { it.weight.toDouble() }.toFloat()
        val gap = theme.keyGapDp * resources.displayMetrics.density
        val placed = mutableListOf<PlacedKey>()
        var y = 0f
        for (row in current.rows) {
            val rowHeight = height * (row.weight / totalRowWeight)
            val rowWeight = row.totalWeight
            var x = width * (row.padStart / rowWeight)
            for (key in row.keys) {
                val keyWidth = width * (key.weight / rowWeight)
                placed += PlacedKey(
                    key,
                    RectF(x + gap, y + gap, x + keyWidth - gap, y + rowHeight - gap),
                )
                x += keyWidth
            }
            y += rowHeight
        }
        return placed
    }

    private val operationIcons = mutableMapOf<Int, Drawable>()
    private val guideTextBounds = android.graphics.Rect()
    private fun drawOutput(canvas: Canvas, action: KeyAction, label: String, x: Float, y: Float, paint: Paint) {
        val id = OperationKeyIcons.resource(action, label)
        val icon = id?.let { operationIcons.getOrPut(it) { requireNotNull(ContextCompat.getDrawable(context, it)).mutate() } }
        if (icon == null) {
            val baseline = if (paint === guideLabelPaint && label.isNotBlank()) {
                paint.getTextBounds(label, 0, label.length, guideTextBounds)
                if (!guideTextBounds.isEmpty) y - (guideTextBounds.top + guideTextBounds.bottom) / 2f
                else y - (paint.descent() + paint.ascent()) / 2f
            } else y - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(label, x, baseline, paint)
            return
        }
        val size = paint.textSize.toInt().coerceAtLeast(1)
        icon.setTint(paint.color)
        icon.alpha = paint.alpha
        icon.setBounds((x-size/2f).toInt(), (y-size/2f).toInt(), (x+size/2f).toInt(), (y+size/2f).toInt())
        icon.draw(canvas)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = theme.keyCornerRadiusDp * resources.displayMetrics.density
        for (placed in placedKeys) {
            // With flat keys only the pressed one gets a shape: the resting keys are their labels
            // and nothing else, so the panel (or the user's background image) shows through.
            val paint = when {
                activeTouches.values.any { it.key === placed } -> keyPressedPaint
                theme.flatKeys -> null
                placed.spec.style == KeyStyle.CHARACTER -> keyPaint
                else -> modifierPaint
            }
            if (paint != null) canvas.drawRoundRect(placed.bounds, radius, radius, paint)
            canvas.drawRoundRect(placed.bounds, radius, radius, boundaryPaint)
            if (activeTouches.values.any { it.key === placed && it.trackpad }) drawCursorLabel(canvas, placed.bounds)
            else drawOutput(canvas, placed.spec.center.action, faceLabel(placed.spec), placed.bounds.centerX(), placed.bounds.centerY(), labelPaint)
            placed.spec.longPress?.let { output ->
                val inset = 5f * resources.displayMetrics.density
                if (OperationKeyIcons.resource(output.action, output.label) != null) {
                    val size = longPressPaint.textSize
                    drawOutput(canvas, output.action, output.label, placed.bounds.right - inset - size / 2f,
                        placed.bounds.top + inset / 2f + size / 2f, longPressPaint)
                } else {
                    canvas.drawText(
                        output.label,
                        placed.bounds.right - inset,
                        placed.bounds.top - longPressPaint.ascent() + inset / 2f,
                        longPressPaint,
                    )
                }
            }
        }

        // Guides go on top of every key so a guide near the edge is never clipped by a neighbour.
        for (touch in activeTouches.values) {
            if (touch.deleteGesture) {
                if (touch.direction != FlickDirection.CENTER) drawFlickPreview(canvas, touch, radius)
                continue
            }
            if (touch.trackpad || !touch.key.spec.hasFlicks) continue
            when (guideStyle) {
                FlickGuideStyle.PREVIEW -> drawFlickPreview(canvas, touch, radius)
                FlickGuideStyle.DIRECTIONS -> drawFlickGuide(canvas, touch, radius)
            }
        }
    }

    /**
     * A single bubble above the key showing what letting go now would type.
     *
     * The alternative — the full cross — puts a cell over each of the four neighbours, which is
     * exactly where the eye is trying to look. This shows only the answer, and follows the finger
     * as the direction changes.
     */
    private fun drawFlickPreview(canvas: Canvas, touch: Touch, radius: Float) {
        val output = touchOutput(touch) ?: return
        val bounds = touch.key.bounds
        previewRect.set(bounds)
        if (touch.deleteGesture) {
            val width = min(this.width.toFloat(), max(bounds.width(),
                max(144 * resources.displayMetrics.density, guideLabelPaint.measureText(output.label) + 24 * resources.displayMetrics.density)))
            previewRect.left = bounds.centerX() - width / 2
            previewRect.right = bounds.centerX() + width / 2
        }
        previewRect.offset(0f, -bounds.height())
        clampIntoView(previewRect)

        guideBackgroundPaint.alpha = 255
        canvas.drawRoundRect(previewRect, radius, radius, guideBackgroundPaint)
        guideLabelPaint.color = theme.flickGuideSelectedLabelColor
        drawOutput(canvas, output.action, output.label, previewRect.centerX(), previewRect.centerY(), guideLabelPaint)
    }

    private fun drawFlickGuide(canvas: Canvas, touch: Touch, radius: Float) {
        val cells = guideCells(touch)
        if (cells.isEmpty()) return

        // The up-cell of a top-row key sits above this view entirely, and the side cells of the
        // outer columns sit beyond its edges. Nudging the whole cross back inside the drawable area
        // keeps every option readable; without it the top row's up-flick — う, く, す … — is simply
        // invisible, which is the one guide a user most needs to see.
        val shift = fitShift(cells.values)

        for (rect in cells.values) rect.offset(shift.x, shift.y)

        for ((direction, rect) in cells) {
            if (touch.key.spec.output(direction) == null) continue
            val selected = direction == touch.direction
            guideBackgroundPaint.alpha = if (selected) 255 else 200
            canvas.drawRoundRect(rect, radius, radius, guideBackgroundPaint)
        }

        drawCharacterHitAreas(canvas, cells, touch.direction, radius)

        for ((direction, rect) in cells) {
            val output = touch.key.spec.output(direction) ?: continue
            val selected = direction == touch.direction
            guideLabelPaint.color =
                if (selected) theme.flickGuideSelectedLabelColor else theme.flickGuideLabelColor
            drawOutput(canvas, output.action, output.label, rect.centerX(), rect.centerY(), guideLabelPaint)
        }
        guideBackgroundPaint.alpha = 255
    }

    /** Draws the selectable area around each actual character instead of abstract geometry. */
    private fun drawCharacterHitAreas(
        canvas: Canvas,
        cells: Map<FlickDirection, RectF>,
        selectedDirection: FlickDirection,
        radius: Float,
    ) {
        val density = resources.displayMetrics.density
        for ((direction, rect) in cells) {
            guideHitAreaPaint.alpha = if (direction == selectedDirection) 230 else 120
            guideHitAreaPaint.strokeWidth =
                if (direction == selectedDirection) 2f * density else density
            canvas.drawRoundRect(rect, radius, radius, guideHitAreaPaint)
        }
    }

    private fun guideCells(touch: Touch): Map<FlickDirection, RectF> {
        val bounds = touch.key.bounds
        val w = bounds.width()
        val h = bounds.height()
        val cells = LinkedHashMap<FlickDirection, RectF>()
        for (direction in FlickDirection.entries) {
            if (touch.key.spec.output(direction) == null) continue
            val gap = theme.keyGapDp * resources.displayMetrics.density
            val offset = GuideGeometry.offset(direction, w, h, gap)
            cells[direction] = RectF(bounds).apply { offset(offset.x, offset.y) }
        }
        return cells
    }

    /**
     * How far the guide has to move to sit inside the drawable area.
     *
     * The area extends [guideOverflowTop] above this view because the parent turns off child
     * clipping, letting the guide spill over the candidate strip rather than being cut off by it.
     */
    private fun fitShift(cells: Collection<RectF>): PointF {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (rect in cells) {
            left = min(left, rect.left)
            top = min(top, rect.top)
            right = max(right, rect.right)
            bottom = max(bottom, rect.bottom)
        }
        val dx = when {
            left < 0f -> -left
            right > width -> width - right
            else -> 0f
        }
        val dy = when {
            top < -guideOverflowTop -> -guideOverflowTop - top
            bottom > height -> height - bottom
            else -> 0f
        }
        return PointF(dx, dy)
    }

    /** Moves [rect] the shortest distance that puts it inside the drawable area. */
    private fun clampIntoView(rect: RectF) {
        val dx = when {
            rect.left < 0f -> -rect.left
            rect.right > width -> width - rect.right
            else -> 0f
        }
        val dy = when {
            rect.top < -guideOverflowTop -> -guideOverflowTop - rect.top
            rect.bottom > height -> height - rect.bottom
            else -> 0f
        }
        rect.offset(dx, dy)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                onPointerDown(event.getPointerId(index), event.getX(index), event.getY(index))
            }

            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    for (historyIndex in 0 until event.historySize) {
                        activeTouches[event.getPointerId(index)]?.path?.record(
                            event.getHistoricalX(index, historyIndex),
                            event.getHistoricalY(index, historyIndex),
                        )
                    }
                    onPointerMove(event.getPointerId(index), event.getX(index), event.getY(index))
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                onPointerMove(event.getPointerId(index), event.getX(index), event.getY(index))
                onPointerUp(event.getPointerId(index))
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelTimers()
                activeTouches.clear()
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun onPointerDown(pointerId: Int, x: Float, y: Float) {
        val key = placedKeys.firstOrNull { it.bounds.contains(x, y) } ?: return
        // A new finger means the previous key is settled; commit it rather than letting a stray
        // move event on the old pointer rewrite it.
        activeTouches.values.forEach {
            if (!it.deleteGesture || it.direction == FlickDirection.CENTER) commit(it)
        }
        activeTouches.clear()
        cancelTimers()

        val touch = Touch(key, x, y)
        touch.deleteGesture = key.spec.center.action is KeyAction.Backspace && !key.spec.hasFlicks &&
            layout?.inputStyle?.name?.contains("FLICK") == true && layout?.id?.startsWith("qwerty") != true
        activeTouches[pointerId] = touch
        if (theme.hapticFeedback) performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)

        if (touch.deleteGesture) {
            // A flick must not first delete a character on DOWN. Tap resolves on release;
            // an unmoving hold retains continuous ordinary Backspace.
            if (key.spec.repeatable) scheduleRepeat(touch)
        } else if (key.spec.center.action is KeyAction.Space || key.spec.center.action is KeyAction.Convert) {
            val hold = Runnable {
                val qwerty = layout?.inputStyle == InputStyle.QWERTY_HIRAGANA || layout?.inputStyle == InputStyle.QWERTY_HALFWIDTH_ASCII
                if (touch.direction == FlickDirection.CENTER || (qwerty && touch.direction in listOf(FlickDirection.LEFT, FlickDirection.RIGHT))) {
                    touch.trackpad = true
                    touch.longPressed = true
                    touch.trackpadMotion = TrackpadMotion(18 * resources.displayMetrics.density, touch.downX + touch.path.dx)
                    cancelRepeat()
                    if (qwerty) {
                        touch.spaceHoldMotion = SpaceHoldMotion(touch.downX, resources.displayMetrics.density)
                        if (touch.direction == FlickDirection.LEFT || touch.direction == FlickDirection.RIGHT) onTrackpadMove?.invoke(if (touch.direction == FlickDirection.LEFT) -1 else 1)
                        scheduleSpaceHold(touch)
                    }
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    announceForAccessibility("カーソル移動。左右に滑らせて移動")
                    invalidate()
                }
            }
            longPressRunnable = hold
            handler.postDelayed(hold, LONG_PRESS_DELAY_MS)
        } else if (key.spec.repeatable) {
            if (key.spec.hasFlicks) {
                // A repeatable key that also flicks — the space bar — cannot fire on press, or a
                // sideways swipe would insert a stray space before the cursor move. Resolve on
                // release like a character key; holding still repeats the centre action below.
                scheduleRepeat(touch)
            } else {
                // Plain repeatable keys fire on press, not on release. Backspace has to delete the
                // moment it is touched, and firing again on release would delete one extra
                // character after every hold.
                touch.firedOnPress = true
                emit(touch)
                scheduleRepeat(touch)
            }
        } else if (key.spec.longPress != null) {
            scheduleLongPress(touch)
        }
        invalidate()
    }

    private fun onPointerMove(pointerId: Int, x: Float, y: Float) {
        val touch = activeTouches[pointerId] ?: return
        touch.path.record(x, y)
        if (touch.deleteGesture) {
            val dx = touch.path.dx
            val dy = touch.path.dy
            val distance = max(kotlin.math.abs(dx), kotlin.math.abs(dy))
            if (touch.repeated) {
                if (distance >= flickThresholdPx) cancelRepeat()
                return
            }
            val direction = when {
                distance < flickThresholdPx -> FlickDirection.CENTER
                touch.direction == FlickDirection.LEFT && dx < 0 && kotlin.math.abs(dx) * 1.25f >= kotlin.math.abs(dy) -> FlickDirection.LEFT
                touch.direction == FlickDirection.UP && dy < 0 && kotlin.math.abs(dy) * 1.25f >= kotlin.math.abs(dx) -> FlickDirection.UP
                kotlin.math.abs(dx) >= kotlin.math.abs(dy) && dx < 0 -> FlickDirection.LEFT
                kotlin.math.abs(dy) > kotlin.math.abs(dx) && dy < 0 -> FlickDirection.UP
                else -> FlickDirection.CENTER
            }
            val boundary = max(flickThresholdPx * 2.5f, 64 * resources.displayMetrics.density)
            val line = direction != FlickDirection.CENTER && distance >=
                boundary * (if (touch.deleteLine && touch.direction == direction) 0.8f else 1f)
            if (direction != touch.direction || line != touch.deleteLine) {
                touch.direction = direction
                touch.deleteLine = line
                cancelRepeat()
                if (direction == FlickDirection.CENTER && touch.key.spec.repeatable) scheduleRepeat(touch)
                if (direction != FlickDirection.CENTER && theme.hapticFeedback) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                }
                invalidate()
            }
            return
        }
        if (touch.trackpad) {
            if (touch.spaceHoldMotion != null) return
            val count = touch.trackpadMotion?.move(x) ?: 0
            repeat(kotlin.math.abs(count)) { onTrackpadMove?.invoke(if (count < 0) -1 else 1) }
            return
        }
        val direction = resolveDirection(touch)
        if (direction != touch.direction) {
            touch.direction = direction
            cancelRepeat()
            invalidate()
            if (touch.key.spec.repeatable && touch.key.spec.center.action !is KeyAction.Space && touch.key.spec.center.action !is KeyAction.Convert && direction != FlickDirection.CENTER && !touch.longPressed) {
                // Swiped to a flick direction on a repeatable key: keep repeating in that direction.
                scheduleRepeat(touch, direction, skipInitialDelay = true)
            }
        }
    }

    private fun onPointerUp(pointerId: Int) {
        val touch = activeTouches.remove(pointerId) ?: return
        cancelTimers()
        commit(touch)
        invalidate()
    }

    private fun resolveDirection(touch: Touch): FlickDirection =
        FlickResolver.resolve(
            key = touch.key.spec,
            dx = touch.path.dx,
            dy = touch.path.dy,
            thresholdPx = flickThresholdPx,
        )

    private fun commit(touch: Touch) {
        if (touch.firedOnPress || touch.longPressed) return
        // A flickable repeatable key that already repeated on hold must not add one more on release.
        if (touch.repeated) return
        emit(touch)
    }

    private fun emit(touch: Touch) {
        val output = touchOutput(touch) ?: return
        emitOutput(output, touch.key.spec, touch.direction)
    }

    private fun touchOutput(touch: Touch): KeyOutput? {
        if (!touch.deleteGesture || touch.direction == FlickDirection.CENTER) return touch.key.spec.output(touch.direction)
        val forward = touch.direction == FlickDirection.UP
        val label = if (touch.deleteLine) { if (forward) "行末まで" else "行頭まで" }
            else if (forward) "右の単語" else "左の単語"
        return KeyOutput(label, KeyAction.DeleteChunk(forward, touch.deleteLine))
    }

    private fun emitOutput(output: KeyOutput, key: KeySpec, direction: FlickDirection) {
        if (output.action is KeyAction.Shift) {
            // Tap cycles off → once → locked, the way every phone keyboard does it. Consumed here:
            // the service has nothing to do with it and never sees the key.
            shift = when (shift) {
                ShiftState.OFF -> ShiftState.ONCE
                ShiftState.ONCE -> ShiftState.LOCKED
                ShiftState.LOCKED -> ShiftState.OFF
            }
            invalidate()
            return
        }
        listener?.onKeyOutput(shiftApplied(output), key, direction)
        if (shift == ShiftState.ONCE && output.action is KeyAction.Input) {
            shift = ShiftState.OFF
            invalidate()
        }
    }

    /**
     * What to draw on a key face, which under shift is not what the layout says.
     *
     * The shift key itself reports the state rather than a fixed glyph — without that, a latched
     * shift is invisible and the next capital looks like a bug.
     */
    private fun faceLabel(spec: KeySpec): String {
        if (activeTouches.values.any { it.key.spec === spec && it.trackpad }) return "↔ カーソル"
        if (isConversionAvailable &&
            (spec.center.action is KeyAction.Space || spec.center.action is KeyAction.Convert)
        ) {
            return CONVERT_LABEL
        }
        if (spec.center.action is KeyAction.Shift) {
            return when (shift) {
                ShiftState.OFF -> spec.faceLabel
                ShiftState.ONCE -> SHIFT_ONCE_LABEL
                ShiftState.LOCKED -> SHIFT_LOCKED_LABEL
            }
        }
        return if (shift == ShiftState.OFF) spec.faceLabel else spec.faceLabel.uppercase()
    }

    /** The output as typed under the current shift state. */
    private fun shiftApplied(output: KeyOutput): KeyOutput {
        if (shift == ShiftState.OFF) return output
        val action = output.action
        if (action !is KeyAction.Input) return output
        val upper = action.text.uppercase()
        if (upper == action.text) return output
        return output.copy(label = upper, action = KeyAction.Input(upper))
    }

    private fun scheduleRepeat(touch: Touch, direction: FlickDirection = FlickDirection.CENTER, skipInitialDelay: Boolean = false) {
        val key = touch.key.spec
        val output = key.output(direction) ?: return
        val initialDelay = if (skipInitialDelay) REPEAT_INTERVAL_MS else REPEAT_DELAY_MS
        val runnable = object : Runnable {
            override fun run() {
                val firstOutput = !touch.repeated && !touch.firedOnPress
                touch.repeated = true
                if (theme.hapticFeedback) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                }
                // Keys resolved on release (including flick Backspace) have not sent a base
                // press yet. Keep that first accepted press even if the hold ends while busy.
                isRepeatingOutput = !firstOutput
                try { emitOutput(output, key, direction) }
                finally { isRepeatingOutput = false }
                handler.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        repeatRunnable = runnable
        handler.postDelayed(runnable, initialDelay)
    }

    private fun scheduleLongPress(touch: Touch) {
        val output = touch.key.spec.longPress ?: return
        val runnable = Runnable {
            if (touch.direction != FlickDirection.CENTER) return@Runnable
            touch.longPressed = true
            if (theme.hapticFeedback) {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }
            emitOutput(output, touch.key.spec, FlickDirection.CENTER)
            invalidate()
        }
        longPressRunnable = runnable
        handler.postDelayed(runnable, LONG_PRESS_DELAY_MS)
    }

    private fun scheduleSpaceHold(touch: Touch) {
        val runnable = object : Runnable {
            override fun run() {
                if (!isAttachedToWindow || !activeTouches.containsValue(touch) || !touch.trackpad) return
                val step = touch.spaceHoldMotion?.tick(touch.downX + touch.path.dx, android.os.SystemClock.uptimeMillis()) ?: 0
                if (step != 0) {
                    isRepeatingOutput = true
                    try { onTrackpadMove?.invoke(step) }
                    finally { isRepeatingOutput = false }
                }
                if (isAttachedToWindow && activeTouches.containsValue(touch)) handler.postDelayed(this, 32L)
            }
        }
        repeatRunnable = runnable
        handler.post(runnable)
    }

    private val cursorModePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun drawCursorLabel(canvas: Canvas, bounds: RectF) {
        cursorModePaint.set(labelPaint)
        val width = (bounds.width() - 8f * resources.displayMetrics.density).coerceAtLeast(1f)
        val text = "カーソル"
        val split = cursorModePaint.measureText(text) > width / 0.75f
        val lines = if (split) listOf("カー", "ソル") else listOf(text)
        val widest = lines.maxOf { cursorModePaint.measureText(it) }
        val fontHeight = cursorModePaint.descent() - cursorModePaint.ascent()
        val scale = minOf(1f, width / widest.coerceAtLeast(1f), (bounds.height() - 8f * resources.displayMetrics.density).coerceAtLeast(1f) / (fontHeight * lines.size))
        cursorModePaint.textSize *= scale
        val height = cursorModePaint.descent() - cursorModePaint.ascent()
        val top = bounds.centerY() - height * lines.size / 2f
        lines.forEachIndexed { index, line -> canvas.drawText(line, bounds.centerX(), top + index * height - cursorModePaint.ascent(), cursorModePaint) }
    }

    private fun cancelRepeat() {
        repeatRunnable?.let { handler.removeCallbacks(it) }
        repeatRunnable = null
        onRepeatStopped?.invoke()
    }

    private fun cancelLongPressTimer() {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun cancelTimers() {
        cancelRepeat()
        cancelLongPressTimer()
    }

    fun cancelGestures() {
        cancelTimers()
        activeTouches.clear()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        cancelGestures()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val DEFAULT_FLICK_THRESHOLD_DP = 24f
        private const val CONVERT_LABEL = "変換"

        /** Filled arrow while shift is armed for one letter, underlined arrow while it is locked. */
        private const val SHIFT_ONCE_LABEL = "⬆"
        private const val SHIFT_LOCKED_LABEL = "⇪"
        /** How long the key must be held before it starts repeating. */
        private const val REPEAT_DELAY_MS = 400L
        private const val LONG_PRESS_DELAY_MS = 450L

        /**
         * Gap between repeats. Explicit rather than ViewConfiguration.getKeyRepeatDelay(), which
         * despite the name is the initial delay and reads far too short to use as an interval.
         *
         * Slightly slower than a hardware key repeat on purpose: every repeat on a live composition
         * costs a full mozc prediction on this thread, and typing correction made those noticeably
         * more expensive.
         */
        private const val REPEAT_INTERVAL_MS = 70L
    }
}
