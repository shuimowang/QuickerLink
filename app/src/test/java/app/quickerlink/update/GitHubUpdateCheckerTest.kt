package app.quickerlink.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUpdateCheckerTest {
    @Test
    fun `finds newer prerelease and ignores drafts and unsafe urls`() {
        val result = evaluateReleaseResponse(
            currentVersionName = "0.2.0-alpha.2",
            responseBody = """
                [
                  {"tag_name":"v9.0.0","html_url":"https://github.com/shuimowang/QuickerLink/releases/tag/v9","draft":true},
                  {"tag_name":"v8.0.0","html_url":"https://example.com/not-trusted","draft":false},
                  {"tag_name":"v0.2.0-alpha.10","html_url":"https://github.com/shuimowang/QuickerLink/releases/tag/v0.2.0-alpha.10","draft":false},
                  {"tag_name":"v0.2.0-alpha.3","html_url":"https://github.com/shuimowang/QuickerLink/releases/tag/v0.2.0-alpha.3","draft":false}
                ]
            """.trimIndent(),
        )

        assertTrue(result is UpdateCheckResult.Available)
        assertEquals("0.2.0-alpha.10", (result as UpdateCheckResult.Available).release.versionName)
    }

    @Test
    fun `stable release sorts after prerelease`() {
        val result = evaluateReleaseResponse(
            currentVersionName = "1.0.0-alpha.9",
            responseBody = """
                [{"tag_name":"v1.0.0","html_url":"https://github.com/shuimowang/QuickerLink/releases/tag/v1.0.0","draft":false}]
            """.trimIndent(),
        )

        assertEquals("1.0.0", (result as UpdateCheckResult.Available).release.versionName)
    }

    @Test
    fun `same or older release reports current`() {
        val result = evaluateReleaseResponse(
            currentVersionName = "0.2.0-alpha.2",
            responseBody = """
                [{"tag_name":"v0.2.0-alpha.1","html_url":"https://github.com/shuimowang/QuickerLink/releases/tag/v0.2.0-alpha.1","draft":false}]
            """.trimIndent(),
        )

        assertTrue(result is UpdateCheckResult.UpToDate)
        assertEquals("0.2.0-alpha.1", (result as UpdateCheckResult.UpToDate).latestVersionName)
    }
}
