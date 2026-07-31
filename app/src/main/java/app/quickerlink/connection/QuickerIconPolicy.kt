package app.quickerlink.connection

import java.net.URI

internal object QuickerIconPolicy {
    private const val HOST = "files.getquicker.net"
    private val pathPattern = Regex("^/_(icons|system)/[A-Za-z0-9][A-Za-z0-9._-]{0,200}$")

    fun normalizeUrl(value: String): String? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (
            uri.scheme != "https" ||
            uri.rawAuthority != HOST ||
            uri.host != HOST ||
            uri.port != -1 ||
            uri.rawUserInfo != null ||
            uri.rawQuery != null ||
            uri.rawFragment != null ||
            !pathPattern.matches(uri.rawPath.orEmpty())
        ) {
            return null
        }
        return "https://$HOST${uri.rawPath}"
    }
}
