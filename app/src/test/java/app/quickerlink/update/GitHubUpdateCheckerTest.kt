package app.quickerlink.update

import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUpdateCheckerTest {
    @Test
    fun `finds newest complete prerelease and exposes exact assets`() {
        val result = evaluateUpdateManifest(
            currentVersionName = "0.2.0-alpha.2",
            responseBody = manifest(
                "[${validRelease("9.0.0", draft = true)}," +
                    "${validRelease("0.2.0-alpha.3")},${validRelease("0.2.0-alpha.10")} ]",
            ),
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
        val result = evaluateUpdateManifest(
            currentVersionName = "1.0.0-alpha.9",
            responseBody = manifest("[${validRelease("1.0.0-alpha.10")},${validRelease("1.0.0")} ]"),
        )

        assertEquals("1.0.0", (result as UpdateCheckResult.Available).release.versionName)
    }

    @Test
    fun `same or older release reports latest usable version`() {
        val result = evaluateUpdateManifest(
            currentVersionName = "0.2.0-alpha.2",
            responseBody = manifest("[${validRelease("0.2.0-alpha.1")} ]"),
        )

        assertTrue(result is UpdateCheckResult.UpToDate)
        assertEquals("0.2.0-alpha.1", (result as UpdateCheckResult.UpToDate).latestVersionName)
    }

    @Test
    fun `successful Pages index does not call REST API`() {
        val counts = RequestCounts()
        val client = updateClient(
            counts = counts,
            pages = { request ->
                response(request, manifest("[${validRelease("0.6.0")}]"))
            },
            api = { throw IOException("REST API must not be called") },
        )

        val result = GitHubUpdateChecker(client).use { checker ->
            checker.check("0.5.0")
        }

        assertEquals("0.6.0", (result as UpdateCheckResult.Available).release.versionName)
        assertEquals(1, counts.pages)
        assertEquals(0, counts.api)
    }

    @Test
    fun `stale but valid Pages index does not call REST API`() {
        val counts = RequestCounts()
        val client = updateClient(
            counts = counts,
            pages = { request ->
                response(request, manifest("[${validRelease("0.4.0")}]"))
            },
            api = { throw IOException("REST API must not be called") },
        )

        val result = GitHubUpdateChecker(client).use { checker ->
            checker.check("0.5.0")
        }

        assertEquals("0.4.0", (result as UpdateCheckResult.UpToDate).latestVersionName)
        assertEquals(1, counts.pages)
        assertEquals(0, counts.api)
    }

    @Test
    fun `Pages failures fall back to REST API exactly once`() {
        val validIndex = manifest("[${validRelease("0.6.0")}]")
        val failures = listOf<Pair<String, (Request) -> Response>>(
            "network failure" to { throw IOException("offline") },
            "HTTP failure" to { request -> response(request, "{}", code = 503) },
            "wrong content type" to { request ->
                response(request, validIndex, contentType = "text/html")
            },
            "oversized declared response" to { request ->
                response(request, "x".repeat(64 * 1024 + 1))
            },
            "oversized streamed response" to { request ->
                streamedResponse(request, "x".repeat(64 * 1024 + 1).toByteArray())
            },
            "invalid UTF-8" to { request ->
                streamedResponse(request, byteArrayOf(0xc3.toByte(), 0x28))
            },
            "malformed JSON" to { request -> response(request, "{") },
            "wrong index identity" to { request ->
                response(
                    request,
                    manifest("[${validRelease("0.6.0")} ]", repository = "other/QuickerLink"),
                )
            },
            "untrusted final URL" to { request ->
                response(
                    request = request,
                    body = validIndex,
                    finalUrl = "https://evil.example/QuickerLink/update-v1.json",
                )
            },
        )

        failures.forEach { (name, pagesFailure) ->
            val counts = RequestCounts()
            val client = updateClient(
                counts = counts,
                pages = pagesFailure,
                api = { request -> response(request, "[${validRelease("0.7.0")}]") },
            )

            val result = GitHubUpdateChecker(client).use { checker ->
                checker.check("0.5.0")
            }

            assertEquals(name, "0.7.0", (result as UpdateCheckResult.Available).release.versionName)
            assertEquals("$name Pages calls", 1, counts.pages)
            assertEquals("$name REST API calls", 1, counts.api)
        }
    }

    @Test
    fun `REST API array response remains supported`() {
        val result = evaluateReleaseResponse(
            currentVersionName = "0.5.0",
            responseBody = "[${validRelease("0.5.1")},${validRelease("0.6.0")}]",
        )

        assertEquals("0.6.0", (result as UpdateCheckResult.Available).release.versionName)
        assertThrows(IOException::class.java) {
            evaluateReleaseResponse("0.5.0", manifest("[${validRelease("0.6.0")}]"))
        }
    }

    @Test
    fun `invalid current version does not call either source`() {
        val counts = RequestCounts()
        val client = updateClient(
            counts = counts,
            pages = { throw IOException("must not be called") },
            api = { throw IOException("must not be called") },
        )

        assertThrows(IOException::class.java) {
            GitHubUpdateChecker(client).use { checker -> checker.check("not-a-version") }
        }
        assertEquals(0, counts.pages)
        assertEquals(0, counts.api)
    }

    @Test
    fun `release without both exact assets is unusable`() {
        assertThrows(IOException::class.java) {
            evaluateUpdateManifest(
                currentVersionName = "0.1.0",
                responseBody = manifest("[${validRelease("0.2.0", includeChecksum = false)}]"),
            )
        }
        assertThrows(IOException::class.java) {
            evaluateUpdateManifest(
                currentVersionName = "0.1.0",
                responseBody = manifest("[${validRelease("0.2.0", apkNameSuffix = "-copy")}]"),
            )
        }
    }

    @Test
    fun `asset sizes must use integer JSON literals`() {
        listOf("1.5", "1e2", "1E2").forEach { invalidSize ->
            val release = validRelease("0.2.0")
                .replaceFirst("\"size\":1234", "\"size\":$invalidSize")

            assertThrows("size $invalidSize", IOException::class.java) {
                evaluateUpdateManifest("0.1.0", manifest("[$release]"))
            }
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
            evaluateUpdateManifest("0.1.0", manifest("[$withDuplicate]"))
        }
    }

    @Test
    fun `unsafe newer release is ignored`() {
        val unsafe = validRelease(
            version = "9.0.0",
            apkUrlOverride = "https://github.com.evil.example/shuimowang/QuickerLink/releases/download/" +
                "v9.0.0/quicker-link-v9.0.0-release.apk",
        )
        val result = evaluateUpdateManifest(
            currentVersionName = "0.1.0",
            responseBody = manifest("[$unsafe,${validRelease("0.2.0")} ]"),
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

    @Test
    fun `update index requires exact schema repository and fields`() {
        val releases = "[${validRelease("0.2.0")}]"

        assertThrows(IOException::class.java) {
            evaluateUpdateManifest("0.1.0", manifest(releases, schemaVersion = 2))
        }
        assertThrows(IOException::class.java) {
            evaluateUpdateManifest(
                "0.1.0",
                manifest(releases).replace("\"schema_version\":1", "\"schema_version\":1e0"),
            )
        }
        assertThrows(IOException::class.java) {
            evaluateUpdateManifest("0.1.0", manifest(releases, repository = "evil/QuickerLink"))
        }
        assertThrows(IOException::class.java) {
            evaluateUpdateManifest(
                "0.1.0",
                manifest(releases).replace("\"releases\":", "\"unexpected\":true,\"releases\":"),
            )
        }
        assertThrows(IOException::class.java) {
            evaluateUpdateManifest(
                "0.1.0",
                manifest(releases).replace("\"repository\":", "\"repository\":\"duplicate\",\"repository\":"),
            )
        }
    }

    @Test
    fun `update index rejects empty excessive and duplicate releases`() {
        assertThrows(IOException::class.java) {
            evaluateUpdateManifest("0.1.0", manifest("[]"))
        }
        val excessive = (1..11).joinToString(",") { index -> validRelease("0.$index.0") }
        assertThrows(IOException::class.java) {
            evaluateUpdateManifest("0.1.0", manifest("[$excessive]"))
        }
        val duplicate = validRelease("0.2.0")
        assertThrows(IOException::class.java) {
            evaluateUpdateManifest("0.1.0", manifest("[$duplicate,$duplicate]"))
        }
    }

    @Test
    fun `update index URL must be exact`() {
        val valid = "https://shuimowang.github.io/QuickerLink/update-v1.json"

        assertTrue(isTrustedUpdateIndexUrl(valid))
        assertFalse(isTrustedUpdateIndexUrl("http://shuimowang.github.io/QuickerLink/update-v1.json"))
        assertFalse(isTrustedUpdateIndexUrl("https://shuimowang.github.io.evil.example/QuickerLink/update-v1.json"))
        assertFalse(isTrustedUpdateIndexUrl("https://user@shuimowang.github.io/QuickerLink/update-v1.json"))
        assertFalse(isTrustedUpdateIndexUrl("$valid?cache=off"))
        assertFalse(isTrustedUpdateIndexUrl("$valid#latest"))
        assertFalse(isTrustedUpdateIndexUrl("https://shuimowang.github.io/QuickerLink/../update-v1.json"))
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
            "prerelease":true,
            "assets":[$assets]
        }""".trimIndent()
    }

    private fun manifest(
        releases: String,
        schemaVersion: Int = 1,
        repository: String = "shuimowang/QuickerLink",
    ): String = """{
        "schema_version":$schemaVersion,
        "repository":"$repository",
        "releases":$releases
    }""".trimIndent()

    private fun assetJson(name: String, url: String, size: Long): String =
        """{"name":"$name","state":"uploaded","size":$size,"browser_download_url":"$url"}"""

    private fun updateClient(
        counts: RequestCounts,
        pages: (Request) -> Response,
        api: (Request) -> Response,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            when (request.url.host) {
                "shuimowang.github.io" -> {
                    assertEquals(
                        "https://shuimowang.github.io/QuickerLink/update-v1.json",
                        request.url.toString(),
                    )
                    counts.pages += 1
                    pages(request)
                }

                "api.github.com" -> {
                    assertEquals(
                        "https://api.github.com/repos/shuimowang/QuickerLink/releases?per_page=10",
                        request.url.toString(),
                    )
                    counts.api += 1
                    api(request)
                }

                else -> throw IOException("Unexpected update host: ${request.url.host}")
            }
        }
        .build()

    private fun response(
        request: Request,
        body: String,
        code: Int = 200,
        contentType: String = "application/json",
        finalUrl: String? = null,
    ): Response = Response.Builder()
        .request(finalUrl?.let { request.newBuilder().url(it).build() } ?: request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code in 200..299) "OK" else "Failure")
        .body(body.toResponseBody(contentType.toMediaType()))
        .build()

    private fun streamedResponse(request: Request, bytes: ByteArray): Response {
        val source = Buffer().write(bytes)
        val body = object : ResponseBody() {
            override fun contentType() = "application/json".toMediaType()

            override fun contentLength(): Long = -1L

            override fun source(): BufferedSource = source
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()
    }

    private data class RequestCounts(
        var pages: Int = 0,
        var api: Int = 0,
    )
}
