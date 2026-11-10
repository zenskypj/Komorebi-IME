package me.zssu.ime.keyboard

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.provider.Settings
import android.view.SoundEffectConstants
import android.view.View
import me.zssu.ime.R
import me.zssu.ime.theme.KeySound

internal class KeyboardSounds(private val context: Context) {
    private var pool: SoundPool? = null
    private var source = KeySound.SYSTEM
    private var sample = 0
    private var ready = false
    private var systemEnabled = false

    fun configure(enabled: Boolean, sound: KeySound) {
        systemEnabled = enabled && Settings.System.getInt(
            context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1,
        ) != 0
        if (!systemEnabled || sound == KeySound.SYSTEM) {
            release()
            return
        }
        if (source == sound && pool != null) return
        release()
        source = sound
        val id = when (sound) {
            KeySound.SYSTEM -> return
            KeySound.WOOD -> R.raw.key_sound_wood
            KeySound.GLASS -> R.raw.key_sound_glass
            KeySound.SOFT -> R.raw.key_sound_soft
        }
        runCatching {
            val player = SoundPool.Builder().setMaxStreams(1)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .build()
            pool = player
            player.setOnLoadCompleteListener { loaded, loadedId, status ->
                if (pool === loaded && loadedId == sample) ready = status == 0
            }
            sample = player.load(context, id, 1)
        }.onFailure { release() }
    }

    fun play(view: View, sound: KeySound, volume: Float) {
        if (!systemEnabled || volume <= 0f) return
        if (sound == KeySound.SYSTEM) view.playSoundEffect(SoundEffectConstants.CLICK)
        else if (ready) pool?.play(sample, volume, volume, 1, 0, 1f)
    }

    fun release() {
        pool?.release()
        pool = null
        ready = false
        sample = 0
    }
}
