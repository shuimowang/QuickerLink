package app.quickerlink.connection

import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class QuickerGlobalActionsTest {
    @Test
    fun `parses catalog returned as a JSON string`() {
        val catalog = QuickerGlobalActionsProtocol.parse(JsonPrimitive(successCatalog()))

        assertEquals(listOf("常用"), catalog.groups)
        assertEquals(1, catalog.actions.size)
        assertEquals(FIRST_ID, catalog.actions.single().id)
        assertEquals("打开项目", catalog.actions.single().title)
        assertEquals("常用", catalog.actions.single().group)
        assertEquals(0, catalog.actions.single().order)
    }

    @Test
    fun `parses catalog returned as an object`() {
        val data = JsonParser.parseString(successCatalog()).asJsonObject

        val catalog = QuickerGlobalActionsProtocol.parse(data)

        assertEquals(listOf("常用"), catalog.groups)
        assertEquals(FIRST_ID, catalog.actions.single().id)
    }

    @Test
    fun `accepts ungrouped actions empty groups and non contiguous original order`() {
        val catalog = parse(
            groups = "[]",
            actions = """
                [
                  {"id":"$FIRST_ID","title":"动作一","group":null,"order":2},
                  {"id":"$SECOND_ID","title":"动作二","group":null,"order":7}
                ]
            """.trimIndent(),
        )

        assertEquals(emptyList<String>(), catalog.groups)
        assertEquals(listOf(2, 7), catalog.actions.map(QuickerGlobalAction::order))
        assertNull(catalog.actions.first().group)
    }

    @Test
    fun `rejects duplicate and invalid action ids`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse(
                actions = """
                    [
                      {"id":"$FIRST_ID","title":"动作一","group":"常用","order":0},
                      {"id":"${FIRST_ID.uppercase()}","title":"动作二","group":"常用","order":1}
                    ]
                """.trimIndent(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            parse(
                actions = """
                    [{"id":"not-a-guid","title":"动作一","group":"常用","order":0}]
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `rejects actions that reference an unknown group`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse(
                actions = """
                    [{"id":"$FIRST_ID","title":"动作一","group":"不存在","order":0}]
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `rejects duplicate reversed and negative original order`() {
        val invalidActions = listOf(
            """
                [
                  {"id":"$FIRST_ID","title":"动作一","group":"常用","order":3},
                  {"id":"$SECOND_ID","title":"动作二","group":"常用","order":3}
                ]
            """.trimIndent(),
            """
                [
                  {"id":"$FIRST_ID","title":"动作一","group":"常用","order":3},
                  {"id":"$SECOND_ID","title":"动作二","group":"常用","order":2}
                ]
            """.trimIndent(),
            """
                [{"id":"$FIRST_ID","title":"动作一","group":"常用","order":-1}]
            """.trimIndent(),
        )

        invalidActions.forEach { actions ->
            assertThrows(IllegalArgumentException::class.java) { parse(actions = actions) }
        }
    }

    @Test
    fun `rejects oversized payloads`() {
        assertThrows(IllegalArgumentException::class.java) {
            QuickerGlobalActionsProtocol.parse(JsonPrimitive("x".repeat(262_145)))
        }
    }

    @Test
    fun `surfaces stable code and message from server error envelope`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            QuickerGlobalActionsProtocol.parse(
                JsonPrimitive(
                    """
                        {
                          "protocol":"quickerlink.global-actions",
                          "version":1,
                          "ok":false,
                          "code":"CATALOG_READ_FAILED",
                          "error":"读取 Quicker 全局动作失败。"
                        }
                    """.trimIndent(),
                ),
            )
        }

        assertEquals("[CATALOG_READ_FAILED] 读取 Quicker 全局动作失败。", error.message)
    }

    @Test
    fun `rejects malformed server error envelopes`() {
        listOf(
            """{"protocol":"quickerlink.global-actions","version":1,"ok":false,"code":"bad code","error":"失败"}""",
            """{"protocol":"quickerlink.global-actions","version":1,"ok":false,"code":"FAILED","error":""}""",
            """{"protocol":"quickerlink.global-actions","version":1,"ok":"false","code":"FAILED","error":"失败"}""",
            """{"protocol":"quickerlink.global-actions","version":1,"code":"FAILED","error":"失败"}""",
            """{"protocol":"quickerlink.global-actions","version":1,"ok":false,"code":7,"error":"失败"}""",
            """{"protocol":"quickerlink.global-actions","version":1,"ok":false,"code":"FAILED","error":7}""",
        ).forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                QuickerGlobalActionsProtocol.parse(JsonPrimitive(payload))
            }
        }
    }

    @Test
    fun `rejects malformed groups and actions types with controlled errors`() {
        val malformedPayloads = listOf(
            successCatalog(groups = "{}"),
            successCatalog(groups = "null"),
            successCatalog(actions = "{}"),
            successCatalog(actions = "\"not-an-array\""),
            successCatalog(actions = "[false]"),
        )

        malformedPayloads.forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                QuickerGlobalActionsProtocol.parse(JsonPrimitive(payload))
            }
        }
    }

    @Test
    fun `requires nullable group field to be null or a string`() {
        listOf(
            """[{"id":"$FIRST_ID","title":"动作一","order":0}]""",
            """[{"id":"$FIRST_ID","title":"动作一","group":7,"order":0}]""",
        ).forEach { actions ->
            assertThrows(IllegalArgumentException::class.java) { parse(actions = actions) }
        }
    }

    private fun parse(
        groups: String = "[\"常用\"]",
        actions: String = defaultActions(),
    ): QuickerGlobalActionCatalog = QuickerGlobalActionsProtocol.parse(
        JsonPrimitive(successCatalog(groups, actions)),
    )

    private fun successCatalog(
        groups: String = "[\"常用\"]",
        actions: String = defaultActions(),
    ): String = """
        {
          "protocol":"quickerlink.global-actions",
          "version":1,
          "ok":true,
          "scene":"_global",
          "groups":$groups,
          "actions":$actions
        }
    """.trimIndent()

    private fun defaultActions(): String = """
        [{"id":"$FIRST_ID","title":"打开项目","group":"常用","order":0}]
    """.trimIndent()

    private companion object {
        const val FIRST_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val SECOND_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    }
}
