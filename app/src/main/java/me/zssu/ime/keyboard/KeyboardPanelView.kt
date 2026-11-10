package me.zssu.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Matrix
import android.graphics.Paint
import android.widget.LinearLayout
import java.io.File
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import me.zssu.ime.theme.ThemeArt
import me.zssu.ime.theme.BackgroundFilter
import me.zssu.ime.theme.ThemeWallpaper
import me.zssu.ime.theme.WallpaperMotion
import kotlin.math.max

/**
 * The keyboard's backdrop: candidate strip and keys sit on top of it.
 *
 * Everything above draws transparently, so this one view owns the panel's appearance. That is what
 * lets a background image run continuously behind the strip and the keys instead of stopping at a
 * seam between them.
 */
@SuppressLint("ViewConstructor")
class KeyboardPanelView(context: Context) : LinearLayout(context) {

    private var bitmap: Bitmap? = null
    private var wallpaper: Bitmap? = null
    private var wallpaperAlpha = 255
    private var wallpaperMotion = WallpaperMotion.NONE
    private val motionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val motionStart = SystemClock.uptimeMillis()
    private var imageAlpha = 255
    private var pendingLoad: Future<*>? = null
    private var sourceGeneration = 0L
    private var sourcePath: String? = null
    private var sourceLength: Long = -1
    private var sourceModified: Long = -1
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private var video: KeyboardVideo? = null
    private var videoFile: File? = null
    private var album: List<File> = emptyList()
    private var albumIndex = 0
    private var slideSeconds = 20
    private var mediaActive = true
    private var horizontalPosition = 0.5f
    private var verticalPosition = 0.5f
    private var blurDp = 0f
    private var blurred: Bitmap? = null
    private var blurredSource: Bitmap? = null
    private var blurredAmount = 0f
    private val timer = Handler(Looper.getMainLooper())
    private val nextSlide = object : Runnable {
        override fun run() {
            if (!mediaActive || album.size < 2 || !isAttachedToWindow || windowVisibility != VISIBLE) return
            albumIndex = (albumIndex + 1) % album.size
            setBackgroundImage(album[albumIndex], imageAlpha / 255f)
            timer.postDelayed(this, slideSeconds * 1000L)
        }
    }

    init {
        orientation = VERTICAL
        // The flick guide draws outside the keyboard view's bounds, up over the candidate strip.
        clipChildren = false
        clipToPadding = false
        setWillNotDraw(false)
    }

    /**
     * Sets the background image, or clears it with null.
     *
     * @param opacity 0f–1f. A photo at full strength swallows the key labels, so this is how the
     *   user trades legibility against the image.
     */
    fun setBackgroundImage(file: File?, opacity: Float) {
        val nextPath = file?.absolutePath
        val nextLength = file?.length() ?: -1
        val nextModified = file?.lastModified() ?: -1
        if (
            nextPath != sourcePath ||
            nextLength != sourceLength ||
            nextModified != sourceModified
        ) {
            sourcePath = nextPath
            sourceLength = nextLength
            sourceModified = nextModified
            pendingLoad?.cancel(false)
            val generation = ++sourceGeneration
            if (file == null) {
                pendingLoad = null
                bitmap = null
                blurred = null
                blurredSource = null
            } else {
                val target = resources.displayMetrics.widthPixels.coerceIn(640, 1600)
                pendingLoad = imageWorker.submit {
                    val decoded = runCatching { decodeScaled(file, target) }.getOrNull()
                    timer.post {
                        if (generation == sourceGeneration) {
                            pendingLoad = null
                            bitmap = decoded
                            blurred = null
                            blurredSource = null
                            invalidate()
                        }
                    }
                }
            }
        }
        imageAlpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
        invalidate()
    }

    fun setMedia(
        photo: File?,
        album: List<File>,
        video: File?,
        opacity: Float,
        brightness: Float,
        blur: Float,
        horizontal: Float,
        vertical: Float,
        slideSeconds: Int,
        videoStart: Float = 0f,
        videoEnd: Float = 1f,
        filter: BackgroundFilter = BackgroundFilter.NATURAL,
    ) {
        if (this.album != album) albumIndex = 0
        this.album = album
        this.slideSeconds = slideSeconds.coerceIn(5, 120)
        val hasMedia = photo != null || album.isNotEmpty() || video != null
        horizontalPosition = if (hasMedia) horizontal.coerceIn(0f, 1f) else 0.5f
        verticalPosition = if (hasMedia) vertical.coerceIn(0f, 1f) else 0.5f
        blurDp = if (hasMedia) blur.coerceIn(0f, 20f) else 0f
        val scale = brightness.coerceIn(0.25f, 1.5f)
        bitmapPaint.colorFilter = if (!hasMedia || (scale == 1f && filter == BackgroundFilter.NATURAL)) null else ColorMatrixColorFilter(
            ColorMatrix().apply {
                when (filter) {
                    BackgroundFilter.NATURAL -> setScale(scale, scale, scale, 1f)
                    BackgroundFilter.WARM -> setScale(scale * 1.09f, scale, scale * 0.87f, 1f)
                    BackgroundFilter.COOL -> setScale(scale * 0.86f, scale, scale * 1.11f, 1f)
                    BackgroundFilter.MONO -> {
                        setSaturation(0f)
                        postConcat(ColorMatrix().apply { setScale(scale, scale, scale, 1f) })
                    }
                }
            },
        )
        setBackgroundImage(album.getOrNull(albumIndex) ?: photo, opacity)
        if (video != videoFile) {
            this.video?.let { it.release(); removeView(it) }
            this.video = video?.let { file ->
                KeyboardVideo(context, file).also { addView(it, 0, LayoutParams(0, 0)) }
            }
            videoFile = video
        }
        this.video?.apply {
            alpha = opacity.coerceIn(0f, 1f)
            horizontalPosition = this@KeyboardPanelView.horizontalPosition
            verticalPosition = this@KeyboardPanelView.verticalPosition
            recrop()
            setRange(videoStart, videoEnd)
            setLayerPaint(bitmapPaint.colorFilter?.let { filterValue -> Paint().apply { colorFilter = filterValue } })
            if (Build.VERSION.SDK_INT >= 31) {
                val px = blurDp * resources.displayMetrics.density
                setRenderEffect(if (px > 0f) RenderEffect.createBlurEffect(px, px, Shader.TileMode.CLAMP) else null)
            }
        }
        setMediaActive(mediaActive)
    }

    fun setMediaActive(active: Boolean) {
        mediaActive = active
        video?.setActive(active)
        invalidate()
        timer.removeCallbacks(nextSlide)
        if (active && album.size > 1 && isAttachedToWindow && windowVisibility == VISIBLE) timer.postDelayed(nextSlide, slideSeconds * 1000L)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setMediaActive(mediaActive)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) setMediaActive(mediaActive) else timer.removeCallbacks(nextSlide)
    }

    override fun onDetachedFromWindow() {
        timer.removeCallbacks(nextSlide)
        video?.release()
        super.onDetachedFromWindow()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // The zero-size first child does not affect LinearLayout's keyboard measurement.
        video?.let {
            it.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
            it.layout(0, 0, width, height)
        }
    }

    fun setWallpaper(art: ThemeWallpaper?, opacity: Float, motion: WallpaperMotion = WallpaperMotion.NONE) {
        wallpaperMotion = motion
        wallpaper = art?.let { ThemeArt.load(context, it.asset) }
        wallpaperAlpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val image = (if (blurDp > 0f) blurredBackdrop() else null) ?: bitmap ?: wallpaper
        if (image != null) {
            bitmapPaint.alpha = if (bitmap != null) imageAlpha else wallpaperAlpha
            val scale = max(width.toFloat() / image.width, height.toFloat() / image.height)
            matrix.setScale(scale, scale)
            matrix.postTranslate(
                (width - image.width * scale) * horizontalPosition,
                (height - image.height * scale) * verticalPosition,
            )
            canvas.drawBitmap(image, matrix, bitmapPaint)
        }
        if (video == null) drawWallpaperMotion(canvas)
    }

    private fun drawWallpaperMotion(canvas: Canvas) {
        if (wallpaperMotion == WallpaperMotion.NONE || !mediaActive || !isShown) return
        if (Build.VERSION.SDK_INT >= 26 && !android.animation.ValueAnimator.areAnimatorsEnabled()) return
        val density = resources.displayMetrics.density
        val elapsed = (SystemClock.uptimeMillis() - motionStart) % 600_000L / 1000f
        val snow = wallpaperMotion == WallpaperMotion.SNOW
        motionPaint.color = if (snow) android.graphics.Color.WHITE else 0xfffbe7a1.toInt()
        for (i in 0 until 12) {
            val phase = (elapsed * (0.045f + i % 4 * 0.013f) + i * 0.618f) % 1f
            val drift = kotlin.math.sin(phase * 6.28f + i) * 0.035f
            val x = (((i * 0.371f) % 1f) + drift) * width
            val y = (if (snow) phase else 1f - phase) * height
            motionPaint.alpha = (if (snow) 110 else 85) + i % 3 * 24
            canvas.drawCircle(x, y, (1f + i % 3 * 0.7f) * density, motionPaint)
        }
        postInvalidateDelayed(50)
    }

    private fun blurredBackdrop(): Bitmap? {
        val source = bitmap ?: wallpaper ?: return null
        if (source !== blurredSource || blurDp != blurredAmount) {
            val factor = 1f + blurDp * 1.7f
            blurred = Bitmap.createScaledBitmap(source, (source.width / factor).toInt().coerceAtLeast(1),
                (source.height / factor).toInt().coerceAtLeast(1), true)
            blurredSource = source
            blurredAmount = blurDp
        }
        return blurred
    }

    // Deliberately no recycle on detach: InputMethodService detaches and re-attaches the same input
    // view every time the keyboard hides and shows, so freeing the bitmap there would blank the
    // background for the rest of the session. It is released with the view.

    /** Deterministic release for service destruction; the view will not be re-attached. */
    fun releaseBitmap() {
        sourceGeneration++
        pendingLoad?.cancel(false)
        pendingLoad = null
        bitmap = null
        blurred = null
        blurredSource = null
        video?.release()
        timer.removeCallbacks(nextSlide)
        invalidate()
    }

    /**
     * Decodes at roughly panel resolution rather than the camera's.
     *
     * The IME shares a process with nothing that can absorb a 12-megapixel bitmap, and the panel is
     * a few hundred pixels tall — decoding full size would be tens of MB for no visible gain.
     */
    private fun decodeScaled(file: File, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val longEdge = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longEdge / sample > target * 2) sample *= 2

        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }
    private companion object {
        val imageWorker = ScheduledThreadPoolExecutor(1) { work ->
            Thread(work, "keyboard-backdrop").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
            setKeepAliveTime(15, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
        }
    }
}
