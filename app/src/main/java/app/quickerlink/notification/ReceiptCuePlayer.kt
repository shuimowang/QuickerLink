package app.quickerlink.notification

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.util.Log
import app.quickerlink.R

internal object ReceiptCuePlayer {
    private val lock = Any()

    @Volatile
    private var soundPool: SoundPool? = null

    @Volatile
    private var soundId = 0

    @Volatile
    private var soundLoaded = false

    @Volatile
    private var playWhenLoaded = false

    fun prepare(context: Context) {
        runCatching { getOrCreateSoundPool(context.applicationContext) }
            .onFailure { Log.w(TAG, "Unable to prepare receipt cue", it) }
    }

    fun play(context: Context, outcome: ReceiptCueOutcome) {
        if (!outcome.playsCue) return
        try {
            playIfAudible(context.applicationContext)
        } catch (error: Exception) {
            // A receipt cue is optional and must never stop desktop command processing.
            Log.w(TAG, "Unable to play receipt cue", error)
        }
    }

    private fun playIfAudible(context: Context) {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return
        if (
            !shouldPlayReceiptCue(
                ringerMode = audioManager.ringerMode,
                notificationVolume = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION),
                notificationStreamMuted = audioManager.isStreamMute(AudioManager.STREAM_NOTIFICATION),
            )
        ) {
            return
        }

        val pool = getOrCreateSoundPool(context)
        val loaded = synchronized(lock) {
            if (!soundLoaded) playWhenLoaded = true
            soundLoaded
        }
        if (loaded) playLoaded(pool)
    }

    private fun getOrCreateSoundPool(context: Context): SoundPool = synchronized(lock) {
        soundPool?.let { return it }

        val pool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        soundPool = pool
        soundLoaded = false
        playWhenLoaded = false
        pool.setOnLoadCompleteListener { loadedPool, sampleId, status ->
            var shouldPlay = false
            var shouldRelease = false
            synchronized(lock) {
                if (loadedPool !== soundPool || sampleId != soundId) return@setOnLoadCompleteListener
                if (status == LOAD_SUCCESS) {
                    soundLoaded = true
                    shouldPlay = playWhenLoaded
                } else {
                    Log.w(TAG, "Receipt cue failed to load: status=$status")
                    soundPool = null
                    soundId = 0
                    soundLoaded = false
                    shouldRelease = true
                }
                playWhenLoaded = false
            }
            if (shouldRelease) runCatching(loadedPool::release)
            if (shouldPlay) playLoaded(loadedPool)
        }
        soundId = pool.load(context, R.raw.receipt_cue, LOAD_PRIORITY)
        if (soundId == 0) {
            resetFailedPool(pool)
            error("Receipt cue could not be queued for loading")
        }
        pool
    }

    private fun playLoaded(pool: SoundPool) {
        val streamId = pool.play(
            soundId,
            CUE_VOLUME,
            CUE_VOLUME,
            PLAY_PRIORITY,
            NO_LOOP,
            NORMAL_RATE,
        )
        if (streamId == 0) Log.w(TAG, "Receipt cue playback was rejected")
    }

    private fun resetFailedPool(pool: SoundPool) {
        if (soundPool === pool) {
            soundPool = null
            soundId = 0
            soundLoaded = false
            playWhenLoaded = false
        }
        runCatching(pool::release)
    }

    private const val TAG = "ReceiptCuePlayer"
    private const val CUE_VOLUME = 0.65f
    private const val LOAD_PRIORITY = 1
    private const val LOAD_SUCCESS = 0
    private const val PLAY_PRIORITY = 1
    private const val NO_LOOP = 0
    private const val NORMAL_RATE = 1f
}

internal enum class ReceiptCueOutcome(internal val playsCue: Boolean) {
    TEXT_ACCEPTED(true),
    NOTIFICATION_ACCEPTED(true),
    FILE_OFFER_ACCEPTED(true),
    REJECTED(false),
    DUPLICATE(false),
    FAILED(false),
}

internal fun shouldPlayReceiptCue(
    ringerMode: Int,
    notificationVolume: Int,
    notificationStreamMuted: Boolean,
): Boolean =
    ringerMode == AudioManager.RINGER_MODE_NORMAL &&
        notificationVolume > 0 &&
        !notificationStreamMuted
