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

    @Test
    fun `accepts a computer response while phone clipboard is unchanged`() {
        val guard = ClipboardSyncGuard()
        val readToken = guard.beginComputerRead()

        val fingerprint = guard.computerCandidate("from computer", readToken)

        assertEquals(clipboardFingerprint("from computer"), fingerprint)
    }

    @Test
    fun `rejects an old computer response after local clipboard changes`() {
        val guard = ClipboardSyncGuard()
        val readToken = guard.beginComputerRead()

        guard.markPhoneClipboardChanged()

        assertNull(guard.computerCandidate("stale computer text", readToken))
        assertNotNull(guard.phoneCandidate("new phone text"))
        val freshReadToken = guard.beginComputerRead()
        assertEquals(
            clipboardFingerprint("stale computer text"),
            guard.computerCandidate("stale computer text", freshReadToken),
        )
    }

    @Test
    fun `computer write invalidates reads that started before it`() {
        val guard = ClipboardSyncGuard()
        val readToken = guard.beginComputerRead()

        guard.markComputerApplied("computer push")

        assertNull(guard.computerCandidate("older poll response", readToken))
    }

    @Test
    fun `reset invalidates reads from the previous connection`() {
        val guard = ClipboardSyncGuard()
        val readToken = guard.beginComputerRead()

        guard.reset()

        assertNull(guard.computerCandidate("previous computer", readToken))
        assertNotNull(
            guard.computerCandidate("current computer", guard.beginComputerRead()),
        )
    }
}
