package app.quickerlink.notification

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

internal object ReceiptCuePlayer {
    fun play(context: Context, outcome: ReceiptCueOutcome) {
        if (!outcome.playsCue) return
        try {
            playIfAudible(context)
        } catch (_: Exception) {
            // A receipt cue is optional and must never stop desktop command processing.
        }
    }

    private fun playIfAudible(context: Context) {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return
        val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return
        if (
            !shouldPlayReceiptCue(
                ringerMode = audioManager.ringerMode,
                notificationVolume = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION),
                interruptionFilter = notificationManager.currentInterruptionFilter,
            )
        ) {
            return
        }

        val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, CUE_VOLUME_PERCENT)
        try {
            if (!generator.startTone(ToneGenerator.TONE_PROP_ACK, CUE_DURATION_MS)) {
                generator.releaseSafely()
                return
            }
            val releaseScheduled = Handler(Looper.getMainLooper()).postDelayed(
                generator::releaseSafely,
                RELEASE_DELAY_MS,
            )
            if (!releaseScheduled) generator.releaseSafely()
        } catch (error: Exception) {
            generator.releaseSafely()
            throw error
        }
    }

    private const val CUE_VOLUME_PERCENT = 16
    private const val CUE_DURATION_MS = 70
    private const val RELEASE_DELAY_MS = 140L
}

internal enum class ReceiptCueOutcome(internal val playsCue: Boolean) {
    TEXT_ACCEPTED(true),
    NOTIFICATION_ACCEPTED(true),
    FILE_OFFER_ACCEPTED(true),
    REJECTED(false),
    DUPLICATE(false),
    FAILED(false),
}

private fun ToneGenerator.releaseSafely() {
    try {
        release()
    } catch (_: Exception) {
        // Releasing a failed optional tone must not affect message delivery.
    }
}

internal fun shouldPlayReceiptCue(
    ringerMode: Int,
    notificationVolume: Int,
    interruptionFilter: Int,
): Boolean =
    ringerMode == AudioManager.RINGER_MODE_NORMAL &&
        notificationVolume > 0 &&
        interruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
