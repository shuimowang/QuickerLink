package app.quickerlink.connection

import app.quickerlink.data.FeatureSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickerConnectionRuntimeTest {
    @Test
    fun `background receive defaults on while clipboard sync remains opt in`() {
        val settings = FeatureSettings()

        assertTrue(settings.backgroundConnectionEnabled)
        assertFalse(settings.clipboardSyncEnabled)
    }

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

    @Test
    fun `desktop text command prefix is removed exactly once`() {
        assertEquals("正文", normalizeIncomingDesktopText("sendText:正文"))
        assertEquals("正文", normalizeIncomingDesktopText("SENDTEXT:正文"))
        assertEquals("sendText:正文", normalizeIncomingDesktopText("sendText:sendText:正文"))
        assertEquals("普通文本", normalizeIncomingDesktopText("普通文本"))
    }

    @Test
    fun `received desktop text remains discoverable when clipboard write is unavailable`() {
        assertEquals("已复制到手机剪贴板", receivedTextNotificationBody(true))
        assertEquals("已保存到 Quicker Link，打开 App 查看", receivedTextNotificationBody(false))
    }
}

private data class RetentionCase(
    val appInForeground: Boolean,
    val backgroundConnectionEnabled: Boolean,
    val expected: Boolean,
)
