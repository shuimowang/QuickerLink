package app.quickerlink.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickerConnectionRuntimeTest {
    @Test
    fun `retains connection while app is foreground or background connection is enabled`() {
        val cases = listOf(
            RetentionCase(appInForeground = false, backgroundConnectionEnabled = false, expected = false),
            RetentionCase(appInForeground = true, backgroundConnectionEnabled = false, expected = true),
            RetentionCase(appInForeground = false, backgroundConnectionEnabled = true, expected = true),
            RetentionCase(appInForeground = true, backgroundConnectionEnabled = true, expected = true),
        )

        cases.forEach { case ->
            assertEquals(
                "foreground=${case.appInForeground}, background=${case.backgroundConnectionEnabled}",
                case.expected,
                shouldRetainConnection(
                    appInForeground = case.appInForeground,
                    backgroundConnectionEnabled = case.backgroundConnectionEnabled,
                ),
            )
        }
    }
}

private data class RetentionCase(
    val appInForeground: Boolean,
    val backgroundConnectionEnabled: Boolean,
    val expected: Boolean,
)
