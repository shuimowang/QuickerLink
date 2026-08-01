package app.quickerlink.connection

import java.security.MessageDigest

internal class ClipboardSyncGuard {
    private var synchronizedFingerprint: String? = null
    private var lastComputerFingerprint: String? = null

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
        val fingerprint = clipboardFingerprint(text)
        if (fingerprint == lastComputerFingerprint) return null
        lastComputerFingerprint = fingerprint
        if (fingerprint == synchronizedFingerprint) return null
        synchronizedFingerprint = fingerprint
        return fingerprint
    }

    @Synchronized
    fun markComputerApplied(text: String): String {
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
        synchronizedFingerprint = null
        lastComputerFingerprint = null
    }
}

internal fun clipboardFingerprint(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { value -> "%02x".format(value) }
