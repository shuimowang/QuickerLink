package app.quickerlink.connection

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickerProtocolTest {
    @Test
    fun `encodes auth request with lower camel case fields`() {
        val json = JsonParser.parseString(QuickerProtocol.authRequest(7, "secret")).asJsonObject

        assertEquals(5, json["messageType"].asInt)
        assertEquals(7L, json["serial"].asLong)
        assertEquals("secret", json["data"].asString)
    }

    @Test
    fun `encodes action command`() {
        val json = JsonParser.parseString(
            QuickerProtocol.commandRequest(
                serial = 12,
                operation = "action",
                data = "payload",
                action = "Demo",
                wait = true,
            ),
        ).asJsonObject

        assertEquals(2, json["messageType"].asInt)
        assertEquals("action", json["operation"].asString)
        assertEquals("Demo", json["action"].asString)
        assertTrue(json["wait"].asBoolean)
    }

    @Test
    fun `parses lower camel case response`() {
        val message = QuickerProtocol.parse(
            """{"messageType":4,"replyTo":12,"isSuccess":true,"data":"ok"}""",
        )

        assertEquals(4, message.messageType)
        assertEquals(12L, message.replyTo)
        assertTrue(message.isSuccess == true)
        assertEquals("ok", QuickerProtocol.displayData(message.data))
    }

    @Test
    fun `parses legacy pascal case response`() {
        val message = QuickerProtocol.parse(
            """{"MessageType":4,"ReplyTo":3,"IsSuccess":false,"Message":"failed"}""",
        )

        assertEquals(4, message.messageType)
        assertEquals(3L, message.replyTo)
        assertFalse(message.isSuccess == true)
        assertEquals("failed", message.message)
        assertNull(message.data)
    }

    @Test
    fun `preserves structured response data`() {
        val message = QuickerProtocol.parse(
            """{"messageType":4,"replyTo":4,"isSuccess":true,"data":{"value":7}}""",
        )

        assertEquals(7, message.data?.asJsonObject?.get("value")?.asInt)
        assertEquals("{\"value\":7}", QuickerProtocol.displayData(message.data))
    }

    @Test
    fun `rejects duplicate fields including case variants`() {
        listOf(
            """{"messageType":4,"messageType":6}""",
            """{"messageType":4,"MessageType":6}""",
            """{"messageType":4,"data":{"value":1,"value":2}}""",
        ).forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                QuickerProtocol.parse(payload)
            }
        }
    }
}
