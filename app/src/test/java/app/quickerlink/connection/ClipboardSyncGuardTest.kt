package app.quickerlink.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardSyncGuardTest {
    @Test
    fun `suppresses phone and computer echoes without storing text`() {
        val guard = ClipboardSyncGuard()
        val phoneFingerprint = guard.phoneCandidate("from phone")
        assertNotNull(phoneFingerprint)

        guard.markPhoneSent(requireNotNull(phoneFingerprint))

        assertNull(guard.phoneCandidate("from phone"))
        assertNull(guard.computerCandidate("from phone"))
        val computerFingerprint = guard.computerCandidate("from computer")
        assertNotNull(computerFingerprint)
        assertEquals(clipboardFingerprint("from computer"), computerFingerprint)
        assertNull(guard.phoneCandidate("from computer"))
    }

    @Test
    fun `retries a computer value after applying it fails`() {
        val guard = ClipboardSyncGuard()
        val fingerprint = guard.computerCandidate("retry")
        assertNotNull(fingerprint)

        guard.markComputerApplyFailed(requireNotNull(fingerprint))

        assertNotNull(guard.computerCandidate("retry"))
    }

    @Test
    fun `reset permits an unchanged value on a new connection`() {
        val guard = ClipboardSyncGuard()
        guard.markComputerApplied("same")
        assertNull(guard.computerCandidate("same"))

        guard.reset()

        assertNotNull(guard.computerCandidate("same"))
    }
}
