package app.quickerlink.service

import app.quickerlink.connection.QuickerConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class QuickerLinkServiceTest {
    @Test
    fun `disconnected notification prompts user to open the app`() {
        assertEquals(
            "后台增强连接已开启" to "打开 App 连接电脑",
            notificationText(QuickerConnectionState.Disconnected),
        )
    }

    @Test
    fun `ready notification displays the connected endpoint`() {
        assertEquals(
            "Quicker Link 已连接" to "wss://192.168.1.56:668/ws",
            notificationText(QuickerConnectionState.Ready("wss://192.168.1.56:668/ws")),
        )
    }

    @Test
    fun `reconnecting notification displays retry delay`() {
        assertEquals(
            "Quicker Link 正在重连" to "5 秒后重试",
            notificationText(
                QuickerConnectionState.Reconnecting(
                    attempt = 3,
                    delaySeconds = 5,
                    reason = "连接中断",
                ),
            ),
        )
    }

    @Test
    fun `error notification displays failure reason`() {
        assertEquals(
            "Quicker Link 连接异常" to "证书校验失败",
            notificationText(QuickerConnectionState.Error("证书校验失败")),
        )
    }
}
