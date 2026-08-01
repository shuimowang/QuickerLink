package app.quickerlink.connection

import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class QuickerActionControlTest {
    @Test
    fun `builds strict v8 stop command for ids and names`() {
        assertEquals(
            "quickerlink:stop-action:v8:QUFBQUFBQUEtQUFBQS00QUFBLThBQUEtQUFBQUFBQUFBQUFB",
            QuickerActionControlProtocol.stopCommand(ACTION_ID.uppercase()),
        )
        assertEquals(
            "quickerlink:stop-action:v8:5oiR55qE5Yqo5L2c",
            QuickerActionControlProtocol.stopCommand("我的动作"),
        )
    }

    @Test
    fun `parses compact success returned as string or object`() {
        val payload = """{"protocol":"quickerlink.stop-action","version":8,"ok":true,"actionId":"$ACTION_ID"}"""

        assertEquals(
            ACTION_ID,
            QuickerActionControlProtocol.parseStopResponse(JsonPrimitive(payload), ACTION_ID).actionId,
        )
        assertEquals(
            ACTION_ID,
            QuickerActionControlProtocol.parseStopResponse(JsonParser.parseString(payload), ACTION_ID).actionId,
        )
    }

    @Test
    fun `maps current stop failures to concise user messages`() {
        val expectedMessages = mapOf(
            "invalid_action_id" to "目标动作 ID 无效",
            "invalid_service_action_id" to "Quicker Link 服务状态无效",
            "self_stop_forbidden" to "不能终止 Quicker Link 自身",
            "service_unavailable" to "Quicker 动作服务暂不可用",
            "action_not_found" to "目标动作不存在，请刷新面板后重试",
            "action_ambiguous" to "存在多个同名动作，请在手动动作中填写动作 GUID",
            "stop_failed" to "Quicker 未能终止该动作",
            "secure_websocket_required" to "请先在 Quicker 中启用安全连接 WSS 并重新配对",
            "invalid_connection_password" to "连接验证码与 Quicker 设置不一致，请重新配对",
            "authentication_required" to "请先启用 Quicker WSS 服务并重新配对",
        )

        expectedMessages.forEach { (code, expectedMessage) ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                QuickerActionControlProtocol.parseStopResponse(
                    JsonPrimitive(
                        """{"protocol":"quickerlink.stop-action","version":8,"ok":false,"code":"$code"}""",
                    ),
                    ACTION_ID,
                )
            }
            assertEquals(expectedMessage, error.message)
        }
    }

    @Test
    fun `identifies old action control protocol as version mismatch`() {
        listOf(
            """{"protocol":"quickerlink.stop-action","version":5,"ok":true}""",
            """{"protocol":"quickerlink.panel-actions","version":5,"ok":false,"code":"unsupported_command","error":"不支持"}""",
        ).forEach { payload ->
            assertThrows(UnsupportedActionControlVersionException::class.java) {
                QuickerActionControlProtocol.parseStopResponse(JsonPrimitive(payload), ACTION_ID)
            }
        }
    }

    @Test
    fun `malformed protocols are not reported as version mismatches`() {
        listOf(
            """{"protocol":"unexpected","version":7,"ok":true}""",
            """{"protocol":"unexpected","version":5,"ok":true}""",
            """{"version":5,"ok":true}""",
            """{"protocol":7,"version":5,"ok":true}""",
            """{"protocol":"quickerlink.stop-action","version":"5","ok":true}""",
            """{"protocol":"quickerlink.panel-actions","version":8,"ok":false,"code":"unsupported_command","error":"不支持"}""",
        ).forEach { payload ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                QuickerActionControlProtocol.parseStopResponse(JsonPrimitive(payload), ACTION_ID)
            }
            assertFalse(error is UnsupportedActionControlVersionException)
        }
    }

    @Test
    fun `rejects malformed extended or unknown stop responses`() {
        listOf(
            """{"protocol":"quickerlink.stop-action","version":8,"ok":true,"actionId":"$ACTION_ID","extra":1}""",
            """{"protocol":"quickerlink.stop-action","version":8,"ok":false,"code":"unknown"}""",
            """{"protocol":"quickerlink.stop-action","version":8,"ok":"true","actionId":"$ACTION_ID"}""",
            """{"protocol":"quickerlink.stop-action","version":8}""",
            """[]""",
        ).forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                QuickerActionControlProtocol.parseStopResponse(JsonPrimitive(payload), ACTION_ID)
            }
        }
    }

    @Test
    fun `rejects invalid request identities and oversized responses`() {
        assertThrows(IllegalArgumentException::class.java) {
            QuickerActionControlProtocol.stopCommand("   ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuickerActionControlProtocol.stopCommand("x".repeat(241))
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuickerActionControlProtocol.stopCommand("动".repeat(129))
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuickerActionControlProtocol.parseStopResponse(JsonPrimitive("x".repeat(1_025)), ACTION_ID)
        }
    }

    private companion object {
        const val ACTION_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    }
}
