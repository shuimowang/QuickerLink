package app.quickerlink.update

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

data class AppRelease(
    val versionName: String,
    val tagName: String,
    val pageUrl: String,
    val apkUrl: String,
    val checksumUrl: String,
    val apkFileName: String,
    val apkSizeBytes: Long,
    val releaseName: String?,
    val publishedAt: String?,
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
            .header("User-Agent", USER_AGENT)
            .build()

        val responseBody = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub returned HTTP ${response.code}")
            val body = response.body
            val contentLength = body.contentLength()
            if (contentLength > MAX_RESPONSE_BYTES) {
                throw IOException("GitHub release response is too large")
            }
            ByteArrayOutputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_RESPONSE_BYTES) {
                            throw IOException("GitHub release response is too large")
                        }
                        output.write(buffer, 0, count)
                    }
                }
                output.toByteArray().toString(Charsets.UTF_8)
            }
        }

        return evaluateReleaseResponse(currentVersionName, responseBody)
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        runCatching { client.cache?.close() }
    }

    private companion object {
        const val RELEASES_API_URL =
            "https://api.github.com/repos/shuimowang/QuickerLink/releases?per_page=10"
        const val MAX_RESPONSE_BYTES = 512L * 1024L

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
            parseRelease(release)
        }
        .maxByOrNull(VersionedRelease::version)
        ?: throw IOException("No usable GitHub release with verified assets was found")

    return if (latest.version > currentVersion) {
        UpdateCheckResult.Available(latest.release)
    } else {
        UpdateCheckResult.UpToDate(latest.release.versionName)
    }
}

private fun parseRelease(release: JsonObject): VersionedRelease? {
    if (release.booleanOrNull("draft") != false) return null

    val tagName = release.stringOrNull("tag_name")
        ?.takeIf { it.length <= MAX_TAG_LENGTH && it.startsWith('v') }
        ?: return null
    val versionName = tagName.removePrefix("v")
    val version = ReleaseVersion.parse(versionName) ?: return null
    val pageUrl = release.stringOrNull("html_url") ?: return null
    if (!isTrustedReleasePageUrl(pageUrl, tagName)) return null

    val apkFileName = expectedApkFileName(tagName)
    val checksumFileName = "$apkFileName.sha256"
    val assets = release["assets"]?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
    val exactAssets = assets.asSequence()
        .mapNotNull { it.takeIf { asset -> asset.isJsonObject }?.asJsonObject }
        .filter { it.stringOrNull("state") == "uploaded" }
        .groupBy { it.stringOrNull("name") }

    // Duplicate exact-name assets are ambiguous and therefore not installable.
    val apkAsset = exactAssets[apkFileName]?.singleOrNull() ?: return null
    val checksumAsset = exactAssets[checksumFileName]?.singleOrNull() ?: return null
    val apkUrl = apkAsset.stringOrNull("browser_download_url") ?: return null
    val checksumUrl = checksumAsset.stringOrNull("browser_download_url") ?: return null
    if (!isTrustedReleaseAssetUrl(apkUrl, tagName, apkFileName)) return null
    if (!isTrustedReleaseAssetUrl(checksumUrl, tagName, checksumFileName)) return null

    val apkSizeBytes = apkAsset.longOrNull("size")
        ?.takeIf { it in 1..MAX_APK_DOWNLOAD_BYTES }
        ?: return null
    val checksumSizeBytes = checksumAsset.longOrNull("size")
        ?.takeIf { it in 1..MAX_CHECKSUM_DOWNLOAD_BYTES }
        ?: return null

    return VersionedRelease(
        version = version,
        release = AppRelease(
            versionName = versionName,
            tagName = tagName,
            pageUrl = pageUrl,
            apkUrl = apkUrl,
            checksumUrl = checksumUrl,
            apkFileName = apkFileName,
            apkSizeBytes = apkSizeBytes,
            releaseName = release.stringOrNull("name")?.take(MAX_RELEASE_NAME_LENGTH),
            publishedAt = release.stringOrNull("published_at")?.take(MAX_PUBLISHED_AT_LENGTH),
        ),
    )
}

internal fun validateReleaseMetadata(release: AppRelease) {
    val version = ReleaseVersion.parse(release.versionName)
        ?: throw UpdateInstallException(UpdateFailure.InvalidRelease, "Release version is invalid")
    if (version.toString() != release.versionName || release.tagName != "v${release.versionName}") {
        throw UpdateInstallException(UpdateFailure.InvalidRelease, "Release tag does not match its version")
    }
    val expectedApkName = expectedApkFileName(release.tagName)
    if (release.apkFileName != expectedApkName) {
        throw UpdateInstallException(UpdateFailure.InvalidRelease, "Release APK name is invalid")
    }
    if (release.apkSizeBytes !in 1..MAX_APK_DOWNLOAD_BYTES) {
        throw UpdateInstallException(UpdateFailure.InvalidRelease, "Release APK size is invalid")
    }
    if (!isTrustedReleasePageUrl(release.pageUrl, release.tagName) ||
        !isTrustedReleaseAssetUrl(release.apkUrl, release.tagName, release.apkFileName) ||
        !isTrustedReleaseAssetUrl(
            release.checksumUrl,
            release.tagName,
            "${release.apkFileName}.sha256",
        )
    ) {
        throw UpdateInstallException(UpdateFailure.UntrustedUrl, "Release URL is not trusted")
    }
}

internal fun isTrustedReleasePageUrl(url: String, tagName: String): Boolean =
    isExactGitHubUrl(url, "/$REPOSITORY_OWNER/$REPOSITORY_NAME/releases/tag/$tagName")

internal fun isTrustedReleaseAssetUrl(url: String, tagName: String, fileName: String): Boolean =
    isExactGitHubUrl(
        url,
        "/$REPOSITORY_OWNER/$REPOSITORY_NAME/releases/download/$tagName/$fileName",
    )

private fun isExactGitHubUrl(url: String, expectedRawPath: String): Boolean = runCatching {
    val parsed = URI(url)
    !parsed.isOpaque &&
        parsed.scheme == "https" &&
        parsed.rawAuthority == GITHUB_HOST &&
        parsed.host == GITHUB_HOST &&
        parsed.port == -1 &&
        parsed.rawUserInfo == null &&
        parsed.rawQuery == null &&
        parsed.rawFragment == null &&
        parsed.rawPath == expectedRawPath &&
        parsed.normalize().rawPath == expectedRawPath
}.getOrDefault(false)

private fun JsonObject.stringOrNull(name: String): String? = runCatching {
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}.getOrNull()

private fun JsonObject.booleanOrNull(name: String): Boolean? = runCatching {
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
}.getOrNull()

private fun JsonObject.longOrNull(name: String): Long? = runCatching {
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
}.getOrNull()

private fun expectedApkFileName(tagName: String): String = "quicker-link-$tagName-release.apk"

private data class VersionedRelease(
    val version: ReleaseVersion,
    val release: AppRelease,
)

internal data class ReleaseVersion(
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

    override fun toString(): String = buildString {
        append(major).append('.').append(minor).append('.').append(patch)
        prerelease?.let { append('-').append(it.joinToString(".")) }
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

internal const val MAX_APK_DOWNLOAD_BYTES = 150L * 1024L * 1024L
internal const val MAX_CHECKSUM_DOWNLOAD_BYTES = 4L * 1024L
internal const val EXPECTED_PACKAGE_NAME = "app.quickerlink"
internal const val FILE_PROVIDER_AUTHORITY = "$EXPECTED_PACKAGE_NAME.fileprovider"
internal const val UPDATE_CACHE_DIRECTORY = "updates"
internal const val USER_AGENT = "QuickerLink-Android"
private const val REPOSITORY_OWNER = "shuimowang"
private const val REPOSITORY_NAME = "QuickerLink"
private const val GITHUB_HOST = "github.com"
private const val MAX_TAG_LENGTH = 80
private const val MAX_RELEASE_NAME_LENGTH = 160
private const val MAX_PUBLISHED_AT_LENGTH = 64
