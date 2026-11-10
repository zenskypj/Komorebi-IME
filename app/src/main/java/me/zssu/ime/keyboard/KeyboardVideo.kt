package me.zssu.ime.keyboard

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import java.io.File
import kotlin.math.max

/** Plays only while the keyboard is visible. A failed decode exposes the still backdrop. */
internal class KeyboardVideo(context: Context, private val file: File) :
    TextureView(context), TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var active = true
    private var videoWidth = 0
    private var videoHeight = 0
    private val handler = Handler(Looper.getMainLooper())
    private var prepared = false
    private var seeking = false
    private var startFraction = 0f
    private var endFraction = 1f
    private var playbackRange: VideoPlaybackRange? = null
    private val checkRange = object : Runnable {
        override fun run() {
            val current = player ?: return
            val range = playbackRange ?: return
            if (!active || !prepared || seeking) return
            runCatching {
                if (!current.isPlaying) return
                val remaining = range.endMs - current.currentPosition
                if (remaining <= 0) seekToStart(current, range)
                else handler.postDelayed(this, remaining.toLong().coerceIn(16L, 500L))
            }.onFailure { release() }
        }
    }
    var horizontalPosition = 0.5f
    var verticalPosition = 0.5f

    init {
        surfaceTextureListener = this
        isOpaque = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setActive(value: Boolean) {
        active = value
        if (value) surfaceTexture?.let(::start) else release()
    }

    fun setRange(start: Float, end: Float) {
        if (start == startFraction && end == endFraction) return
        startFraction = start
        endFraction = end
        if (prepared) player?.let { runCatching { applyRange(it) }.onFailure { release() } }
    }

    private fun applyRange(current: MediaPlayer) {
        handler.removeCallbacks(checkRange)
        val duration = current.duration.coerceAtLeast(1)
        val range = VideoPlaybackRange.resolve(startFraction, endFraction, duration)
        playbackRange = range
        current.isLooping = range.isFull(duration)
        if (current.isLooping && !seeking) current.start()
        else seekToStart(current, range)
    }

    private fun seekToStart(current: MediaPlayer, range: VideoPlaybackRange) {
        handler.removeCallbacks(checkRange)
        if (current.isPlaying) current.pause()
        seeking = true
        if (android.os.Build.VERSION.SDK_INT >= 26) current.seekTo(range.startMs.toLong(), MediaPlayer.SEEK_CLOSEST)
        else {
            @Suppress("DEPRECATION")
            current.seekTo(range.startMs)
        }
    }

    private fun start(texture: SurfaceTexture) {
        if (!active || player != null || !isAttachedToWindow || windowVisibility != VISIBLE) return
        val next = runCatching { MediaPlayer() }.getOrNull() ?: return
        player = next
        runCatching {
            Surface(texture).useSurface { next.setSurface(it) }
            next.setDataSource(file.absolutePath)
            next.setVolume(0f, 0f)
            next.setOnVideoSizeChangedListener { _, w, h ->
                videoWidth = w
                videoHeight = h
                recrop()
            }
            next.setOnPreparedListener {
                if (player === it && active) {
                    prepared = true
                    runCatching { applyRange(it) }.onFailure { release() }
                }
            }
            next.setOnSeekCompleteListener {
                if (player === it && active) {
                    seeking = false
                    runCatching {
                        it.start()
                        handler.removeCallbacks(checkRange)
                        if (!it.isLooping) handler.post(checkRange)
                    }.onFailure { release() }
                }
            }
            next.setOnCompletionListener {
                if (player === it && active) runCatching {
                    if (!it.isLooping) playbackRange?.let { range -> seekToStart(it, range) }
                }.onFailure { release() }
            }
            next.setOnErrorListener { failed, _, _ ->
                if (player === failed) release()
                true
            }
            next.prepareAsync()
        }.onFailure { release() }
    }

    fun recrop() {
        if (width <= 0 || height <= 0 || videoWidth <= 0 || videoHeight <= 0) return
        val scale = max(width.toFloat() / videoWidth, height.toFloat() / videoHeight)
        val sw = videoWidth * scale
        val sh = videoHeight * scale
        setTransform(Matrix().apply {
            setScale(sw / width, sh / height)
            postTranslate((width - sw) * horizontalPosition, (height - sh) * verticalPosition)
        })
    }

    fun release() {
        handler.removeCallbacks(checkRange)
        prepared = false
        seeking = false
        playbackRange = null
        player?.release()
        player = null
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) surfaceTexture?.let(::start) else release()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = start(surface)
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = recrop()
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        release()
        return true
    }

    private inline fun Surface.useSurface(block: (Surface) -> Unit) {
        try { block(this) } finally { release() }
    }
}
