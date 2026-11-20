package com.ntt.crosstest.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import com.ntt.crosstest.R

/**
 * Plays the short "coin drop" reward sound.
 *
 * - Uses [SoundPool] with a single stream so consecutive rewards never overlap.
 * - Routed through [AudioAttributes.USAGE_ASSISTANCE_SONIFICATION] (UI feedback,
 *   not media), which is muted by the system in silent mode as well.
 * - Throttles playback so a burst of rewards (for example several reports
 *   approved at once) only produces one sound.
 */
object CoinSoundPlayer {

    private const val THROTTLE_MS = 250L

    @Volatile
    private var soundPool: SoundPool? = null

    @Volatile
    private var soundId = 0

    @Volatile
    private var isLoaded = false

    @Volatile
    private var lastPlayedAt = 0L

    /** Warm the SoundPool before the first reward so the first play isn't dropped. */
    fun preload(context: Context) {
        synchronized(this) {
            ensurePool(context.applicationContext)
        }
    }

    fun play(context: Context) {
        val appContext = context.applicationContext
        if (isSilentMode(appContext)) return

        synchronized(this) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastPlayedAt < THROTTLE_MS) return
            val pool = ensurePool(appContext) ?: return
            val id = soundId
            if (!isLoaded || id == 0) return
            lastPlayedAt = now
            pool.play(id, VOLUME, VOLUME, 1, 0, 1f)
        }
    }

    private fun ensurePool(context: Context): SoundPool? {
        soundPool?.let { return it }
        return try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val pool = SoundPool.Builder()
                .setMaxStreams(1)
                .setAudioAttributes(attributes)
                .build()
            pool.setOnLoadCompleteListener { _, _, status ->
                if (status == 0) isLoaded = true
            }
            soundId = pool.load(context, R.raw.coin_drop, 1)
            soundPool = pool
            pool
        } catch (_: Exception) {
            null
        }
    }

    private fun isSilentMode(context: Context): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return false
        return audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT
    }

    private const val VOLUME = 0.8f
}
