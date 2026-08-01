package app.quickerlink.notification

import android.media.AudioManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptCuePlayerTest {
    @Test
    fun `only accepted receipt types are eligible for a cue`() {
        assertTrue(ReceiptCueOutcome.TEXT_ACCEPTED.playsCue)
        assertTrue(ReceiptCueOutcome.NOTIFICATION_ACCEPTED.playsCue)
        assertTrue(ReceiptCueOutcome.FILE_OFFER_ACCEPTED.playsCue)
        assertFalse(ReceiptCueOutcome.REJECTED.playsCue)
        assertFalse(ReceiptCueOutcome.DUPLICATE.playsCue)
        assertFalse(ReceiptCueOutcome.FAILED.playsCue)
    }

    @Test
    fun `plays only on an audible unmuted notification stream`() {
        assertTrue(
            shouldPlayReceiptCue(
                AudioManager.RINGER_MODE_NORMAL,
                notificationVolume = 1,
                notificationStreamMuted = false,
            ),
        )
        assertFalse(
            shouldPlayReceiptCue(
                AudioManager.RINGER_MODE_SILENT,
                notificationVolume = 1,
                notificationStreamMuted = false,
            ),
        )
        assertFalse(
            shouldPlayReceiptCue(
                AudioManager.RINGER_MODE_NORMAL,
                notificationVolume = 0,
                notificationStreamMuted = false,
            ),
        )
        assertFalse(
            shouldPlayReceiptCue(
                AudioManager.RINGER_MODE_NORMAL,
                notificationVolume = 1,
                notificationStreamMuted = true,
            ),
        )
    }
}
