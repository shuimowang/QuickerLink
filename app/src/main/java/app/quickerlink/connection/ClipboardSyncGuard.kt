package app.quickerlink.connection

import java.security.MessageDigest

internal class ClipboardSyncGuard {
    private var synchronizedFingerprint: String? = null
    private var lastComputerFingerprint: String? = null
    private var phoneClipboardEpoch = 0L

    @Synchronized
    fun markPhoneClipboardChanged() {
        advancePhoneClipboardEpoch()
    }

    @Synchronized
    fun beginComputerRead(): ComputerClipboardReadToken =
        ComputerClipboardReadToken(phoneClipboardEpoch)

    @Synchronized
    fun phoneCandidate(text: String): String? {
        val fingerprint = clipboardFingerprint(text)
        return fingerprint.takeUnless { it == synchronizedFingerprint }
    }

    @Synchronized
    fun markPhoneSent(fingerprint: String) {
        synchronizedFingerprint = fingerprint
        lastComputerFingerprint = fingerprint
    }

    @Synchronized
    fun computerCandidate(text: String): String? {
        return computerCandidateLocked(text)
    }

    @Synchronized
    fun computerCandidate(text: String, readToken: ComputerClipboardReadToken): String? {
        if (readToken.phoneClipboardEpoch != phoneClipboardEpoch) return null
        return computerCandidateLocked(text)
    }

    private fun computerCandidateLocked(text: String): String? {
        val fingerprint = clipboardFingerprint(text)
        if (fingerprint == lastComputerFingerprint) return null
        lastComputerFingerprint = fingerprint
        if (fingerprint == synchronizedFingerprint) return null
        synchronizedFingerprint = fingerprint
        return fingerprint
    }

    @Synchronized
    fun markComputerApplied(text: String): String {
        advancePhoneClipboardEpoch()
        val fingerprint = clipboardFingerprint(text)
        synchronizedFingerprint = fingerprint
        lastComputerFingerprint = fingerprint
        return fingerprint
    }

    @Synchronized
    fun markComputerApplyFailed(fingerprint: String) {
        if (synchronizedFingerprint == fingerprint) synchronizedFingerprint = null
        if (lastComputerFingerprint == fingerprint) lastComputerFingerprint = null
    }

    @Synchronized
    fun reset() {
        advancePhoneClipboardEpoch()
        synchronizedFingerprint = null
        lastComputerFingerprint = null
    }

    private fun advancePhoneClipboardEpoch() {
        phoneClipboardEpoch += 1L
    }
}

internal class ComputerClipboardReadToken internal constructor(
    internal val phoneClipboardEpoch: Long,
)

internal fun clipboardFingerprint(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { value -> "%02x".format(value) }
