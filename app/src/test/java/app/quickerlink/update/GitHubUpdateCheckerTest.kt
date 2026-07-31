package app.quickerlink.update

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUpdateCheckerTest {
    @Test
    fun `finds newest complete prerelease and exposes exact assets`() {
        val result = evaluateReleaseResponse(
            currentVersionName = "0.2.0-alpha.2",
            responseBody = "[${validRelease("9.0.0", draft = true)}," +
                "${validRelease("0.2.0-alpha.3")},${validRelease("0.2.0-alpha.10")} ]",
        )

        assertTrue(result is UpdateCheckResult.Available)
        val release = (result as UpdateCheckResult.Available).release
        assertEquals("0.2.0-alpha.10", release.versionName)
        assertEquals("v0.2.0-alpha.10", release.tagName)
        assertEquals("quicker-link-v0.2.0-alpha.10-release.apk", release.apkFileName)
        assertEquals(1234L, release.apkSizeBytes)
        assertEquals(
            "https://github.com/shuimowang/QuickerLink/releases/download/" +
                "v0.2.0-alpha.10/quicker-link-v0.2.0-alpha.10-release.apk.sha256",
            release.checksumUrl,
        )
    }

    @Test
    fun `stable release sorts after prerelease`() {
        val result = evaluateReleaseResponse(
            currentVersionName = "1.0.0-alpha.9",
            responseBody = "[${validRelease("1.0.0-alpha.10")},${validRelease("1.0.0")} ]",
        )

        assertEquals("1.0.0", (result as UpdateCheckResult.Available).release.versionName)
    }

    @Test
    fun `same or older release reports latest usable version`() {
        val result = evaluateReleaseResponse(
            currentVersionName = "0.2.0-alpha.2",
            responseBody = "[${validRelease("0.2.0-alpha.1")} ]",
        )

        assertTrue(result is UpdateCheckResult.UpToDate)
        assertEquals("0.2.0-alpha.1", (result as UpdateCheckResult.UpToDate).latestVersionName)
    }

    @Test
    fun `release without both exact assets is unusable`() {
        assertThrows(IOException::class.java) {
            evaluateReleaseResponse(
                currentVersionName = "0.1.0",
                responseBody = "[${validRelease("0.2.0", includeChecksum = false)}]",
            )
        }
        assertThrows(IOException::class.java) {
            evaluateReleaseResponse(
                currentVersionName = "0.1.0",
                responseBody = "[${validRelease("0.2.0", apkNameSuffix = "-copy")}]",
            )
        }
    }

    @Test
    fun `duplicate exact assets are rejected`() {
        val base = validRelease("0.2.0")
        val duplicateAsset = assetJson(
            name = "quicker-link-v0.2.0-release.apk",
            url = "https://github.com/shuimowang/QuickerLink/releases/download/" +
                "v0.2.0/quicker-link-v0.2.0-release.apk",
            size = 1234,
        )
        val withDuplicate = base.replace("\"assets\":[", "\"assets\":[$duplicateAsset,")

        assertThrows(IOException::class.java) {
            evaluateReleaseResponse("0.1.0", "[$withDuplicate]")
        }
    }

    @Test
    fun `unsafe newer release is ignored`() {
        val unsafe = validRelease(
            version = "9.0.0",
            apkUrlOverride = "https://github.com.evil.example/shuimowang/QuickerLink/releases/download/" +
                "v9.0.0/quicker-link-v9.0.0-release.apk",
        )
        val result = evaluateReleaseResponse(
            currentVersionName = "0.1.0",
            responseBody = "[$unsafe,${validRelease("0.2.0")} ]",
        )

        assertEquals("0.2.0", (result as UpdateCheckResult.Available).release.versionName)
    }

    @Test
    fun `trusted URL checks reject userinfo query fragment and traversal`() {
        val tag = "v0.2.0"
        val file = "quicker-link-v0.2.0-release.apk"
        val valid = "https://github.com/shuimowang/QuickerLink/releases/download/$tag/$file"

        assertTrue(isTrustedReleaseAssetUrl(valid, tag, file))
        assertFalse(isTrustedReleaseAssetUrl("https://github.com@evil.example/$file", tag, file))
        assertFalse(isTrustedReleaseAssetUrl("$valid?download=https://evil.example", tag, file))
        assertFalse(isTrustedReleaseAssetUrl("$valid#fragment", tag, file))
        assertFalse(
            isTrustedReleaseAssetUrl(
                "https://github.com/shuimowang/QuickerLink/releases/download/$tag/../$file",
                tag,
                file,
            ),
        )
        assertFalse(
            isTrustedReleaseAssetUrl(
                "https://github.com/shuimowang/QuickerLink/releases/download/$tag%2f../$file",
                tag,
                file,
            ),
        )
    }

    private fun validRelease(
        version: String,
        draft: Boolean = false,
        includeChecksum: Boolean = true,
        apkNameSuffix: String = "",
        apkUrlOverride: String? = null,
    ): String {
        val tag = "v$version"
        val expectedApkName = "quicker-link-$tag-release.apk"
        val actualApkName = "$expectedApkName$apkNameSuffix"
        val root = "https://github.com/shuimowang/QuickerLink/releases/download/$tag"
        val assets = buildList {
            add(assetJson(actualApkName, apkUrlOverride ?: "$root/$actualApkName", 1234))
            if (includeChecksum) {
                add(assetJson("$expectedApkName.sha256", "$root/$expectedApkName.sha256", 100))
            }
        }.joinToString(",")
        return """{
            "tag_name":"$tag",
            "name":"Quicker Link $tag",
            "html_url":"https://github.com/shuimowang/QuickerLink/releases/tag/$tag",
            "published_at":"2026-07-31T00:00:00Z",
            "draft":$draft,
            "assets":[$assets]
        }""".trimIndent()
    }

    private fun assetJson(name: String, url: String, size: Long): String =
        """{"name":"$name","state":"uploaded","size":$size,"browser_download_url":"$url"}"""
}
