package app.quickerlink.update

import com.google.gson.JsonParser
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

data class AppRelease(
    val versionName: String,
    val pageUrl: String,
)

sealed interface UpdateCheckResult {
    data class Available(val release: AppRelease) : UpdateCheckResult
    data class UpToDate(val latestVersionName: String) : UpdateCheckResult
}

class GitHubUpdateChecker(
    private val client: OkHttpClient = defaultClient(),
) : AutoCloseable {
    fun check(currentVersionName: String): UpdateCheckResult {
        val request = Request.Builder()
            .url(RELEASES_API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "QuickerLink-Android")
            .build()

        val responseBody = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub returned HTTP ${response.code}")
            response.body.string().also { body ->
                if (body.length > MAX_RESPONSE_CHARACTERS) {
                    throw IOException("GitHub release response is too large")
                }
            }
        }

        return evaluateReleaseResponse(currentVersionName, responseBody)
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        runCatching { client.cache?.close() }
    }

    private companion object {
        const val RELEASES_API_URL =
            "https://api.github.com/repos/shuimowang/QuickerLink/releases?per_page=10"
        const val MAX_RESPONSE_CHARACTERS = 512 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(12, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}

internal fun evaluateReleaseResponse(
    currentVersionName: String,
    responseBody: String,
): UpdateCheckResult {
    val currentVersion = ReleaseVersion.parse(currentVersionName)
        ?: throw IOException("Current app version is invalid")
    val root = runCatching { JsonParser.parseString(responseBody) }
        .getOrElse { throw IOException("GitHub release response is invalid", it) }
    if (!root.isJsonArray) throw IOException("GitHub release response is not an array")

    val latest = root.asJsonArray.asSequence()
        .mapNotNull { element ->
            val release = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            if (release["draft"]?.takeIf { it.isJsonPrimitive }?.asBoolean == true) return@mapNotNull null
            val tag = release["tag_name"]?.takeIf { it.isJsonPrimitive }?.asString
                ?.take(MAX_TAG_LENGTH)
                ?: return@mapNotNull null
            val versionName = tag.removePrefix("v")
            val version = ReleaseVersion.parse(versionName) ?: return@mapNotNull null
            val pageUrl = release["html_url"]?.takeIf { it.isJsonPrimitive }?.asString
                ?: return@mapNotNull null
            if (!isTrustedReleaseUrl(pageUrl)) return@mapNotNull null
            VersionedRelease(version, AppRelease(versionName, pageUrl))
        }
        .maxByOrNull(VersionedRelease::version)
        ?: throw IOException("No usable GitHub release was found")

    return if (latest.version > currentVersion) {
        UpdateCheckResult.Available(latest.release)
    } else {
        UpdateCheckResult.UpToDate(latest.release.versionName)
    }
}

private fun isTrustedReleaseUrl(url: String): Boolean = runCatching {
    val parsed = java.net.URI(url)
    parsed.scheme.equals("https", ignoreCase = true) &&
        parsed.host.equals("github.com", ignoreCase = true) &&
        parsed.path.startsWith("/shuimowang/QuickerLink/releases/")
}.getOrDefault(false)

private data class VersionedRelease(
    val version: ReleaseVersion,
    val release: AppRelease,
)

private data class ReleaseVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: List<String>?,
) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        if (prerelease == null && other.prerelease == null) return 0
        if (prerelease == null) return 1
        if (other.prerelease == null) return -1

        for (index in 0 until maxOf(prerelease.size, other.prerelease.size)) {
            val left = prerelease.getOrNull(index) ?: return -1
            val right = other.prerelease.getOrNull(index) ?: return 1
            comparePrereleasePart(left, right).takeIf { it != 0 }?.let { return it }
        }
        return 0
    }

    companion object {
        private val pattern = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z.-]+))?$")

        fun parse(value: String): ReleaseVersion? {
            val match = pattern.matchEntire(value) ?: return null
            val prerelease = match.groupValues[4]
                .takeIf(String::isNotEmpty)
                ?.split('.')
                ?.takeIf { parts -> parts.all { it.isNotEmpty() } }
                ?: if (match.groupValues[4].isEmpty()) null else return null
            return ReleaseVersion(
                major = match.groupValues[1].toIntOrNull() ?: return null,
                minor = match.groupValues[2].toIntOrNull() ?: return null,
                patch = match.groupValues[3].toIntOrNull() ?: return null,
                prerelease = prerelease,
            )
        }
    }
}

private fun comparePrereleasePart(left: String, right: String): Int {
    val leftNumber = left.toIntOrNull()
    val rightNumber = right.toIntOrNull()
    return when {
        leftNumber != null && rightNumber != null -> compareValues(leftNumber, rightNumber)
        leftNumber != null -> -1
        rightNumber != null -> 1
        else -> left.compareTo(right)
    }
}

private const val MAX_TAG_LENGTH = 80
