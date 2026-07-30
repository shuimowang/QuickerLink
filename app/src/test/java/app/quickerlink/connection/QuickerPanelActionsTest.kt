package app.quickerlink.connection

import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class QuickerPanelActionsTest {
    @Test
    fun usesStrictV2PanelActionCommand() {
        assertEquals("quickerlink:list-panel-actions:v2", QuickerPanelActionsProtocol.LIST_COMMAND)
    }

    @Test
    fun parsesExactlyGlobalThenCommonScenesFromJsonString() {
        val catalog = parse(
            commonGroups = """["默认"]""",
            commonActions = """
                [{"id":"$SECOND_ID","title":"通用动作","group":"默认","order":3}]
            """.trimIndent(),
        )

        assertEquals(
            listOf(QuickerPanelActionsProtocol.GLOBAL_SCENE, QuickerPanelActionsProtocol.COMMON_SCENE),
            catalog.scenes.map { it.scene },
        )
        assertEquals(listOf(FIRST_ID, SECOND_ID), catalog.actions.map { it.id })
        assertEquals(listOf("常用"), catalog.scenes.first().groups)
        assertEquals("打开项目", catalog.scenes.first().actions.single().title)
        assertEquals("常用", catalog.scenes.first().actions.single().group)
        assertEquals(0, catalog.scenes.first().actions.single().order)
    }

    @Test
    fun parsesV2CatalogReturnedAsObject() {
        val data = JsonParser.parseString(successCatalog()).asJsonObject

        val catalog = QuickerPanelActionsProtocol.parse(data)

        assertEquals(2, catalog.scenes.size)
        assertEquals(FIRST_ID, catalog.actions.single().id)
    }

    @Test
    fun acceptsEmptyCommonSceneAndUngroupedNonContiguousActions() {
        val catalog = parse(
            globalGroups = "[]",
            globalActions = """
                [
                  {"id":"$FIRST_ID","title":"动作一","group":null,"order":2},
                  {"id":"$SECOND_ID","title":"动作二","group":null,"order":7}
                ]
            """.trimIndent(),
            commonGroups = "[]",
            commonActions = "[]",
        )

        assertEquals(listOf(2, 7), catalog.actions.map(QuickerPanelAction::order))
        assertNull(catalog.actions.first().group)
        assertEquals(emptyList<QuickerPanelAction>(), catalog.scenes.last().actions)
    }

    @Test
    fun rejectsOldV1Catalog() {
        val legacy = """
            {
              "protocol":"quickerlink.global-actions",
              "version":1,
              "ok":true,
              "scene":"_global",
              "groups":[],
              "actions":[]
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            QuickerPanelActionsProtocol.parse(JsonPrimitive(legacy))
        }
    }

    @Test
    fun rejectsMissingReversedDuplicateUnknownAndExtraScenes() {
        val global = scene("_global")
        val common = scene("common")
        val unknown = scene("chrome.exe")
        val invalidScenes = listOf(
            "[$global]",
            "[$common,$global]",
            "[$global,$global]",
            "[$global,$unknown]",
            "[$global,$common,$unknown]",
        )

        invalidScenes.forEach { scenes ->
            assertThrows(IllegalArgumentException::class.java) {
                QuickerPanelActionsProtocol.parse(JsonPrimitive(successCatalog(scenes)))
            }
        }
    }

    @Test
    fun rejectsUnrecognizedResponseSceneAndActionFields() {
        val extraRoot = successCatalog(extraRoot = ""","unexpected":true""")
        val extraScene = successCatalog(
            scenes = "[" + scene("_global", extra = ""","unexpected":true""") + "," + scene("common") + "]",
        )
        val extraAction = successCatalog(
            scenes = validScenes(
                globalActions = """
                    [{"id":"$FIRST_ID","title":"动作","group":"常用","order":0,"unexpected":true}]
                """.trimIndent(),
            ),
        )

        listOf(extraRoot, extraScene, extraAction).forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                QuickerPanelActionsProtocol.parse(JsonPrimitive(payload))
            }
        }
    }

    @Test
    fun rejectsDuplicateActionIdsWithinOrAcrossScenes() {
        val duplicateId = FIRST_ID.uppercase()
        val duplicateWithinScene = """
            [
              {"id":"$FIRST_ID","title":"动作一","group":"常用","order":0},
              {"id":"$duplicateId","title":"动作二","group":"常用","order":1}
            ]
        """.trimIndent()
        val duplicateAcrossScenes = """
            [{"id":"$duplicateId","title":"重复动作","group":"默认","order":0}]
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            parse(globalActions = duplicateWithinScene)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parse(commonGroups = """["默认"]""", commonActions = duplicateAcrossScenes)
        }
    }

    @Test
    fun rejectsInvalidActionIdsAndUnknownGroups() {
        assertThrows(IllegalArgumentException::class.java) {
            parse(globalActions = """[{"id":"not-a-guid","title":"动作一","group":"常用","order":0}]""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parse(
                globalActions = """
                    [{"id":"$FIRST_ID","title":"动作一","group":"不存在","order":0}]
                """.trimIndent(),
            )
        }
    }

    @Test
    fun rejectsDuplicateReversedAndNegativeActionOrder() {
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
            """[{"id":"$FIRST_ID","title":"动作一","group":"常用","order":-1}]""",
        )

        invalidActions.forEach { actions ->
            assertThrows(IllegalArgumentException::class.java) {
                parse(globalActions = actions)
            }
        }
    }

    @Test
    fun rejectsOversizedPayloads() {
        assertThrows(IllegalArgumentException::class.java) {
            QuickerPanelActionsProtocol.parse(JsonPrimitive("x".repeat(262_145)))
        }
    }

    @Test
    fun surfacesStableCodeAndMessageFromV2ServerError() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            QuickerPanelActionsProtocol.parse(
                JsonPrimitive(
                    """
                        {
                          "protocol":"quickerlink.panel-actions",
                          "version":2,
                          "ok":false,
                          "code":"catalog_read_failed",
                          "error":"读取 Quicker 动作目录失败。"
                        }
                    """.trimIndent(),
                ),
            )
        }

        assertEquals("[catalog_read_failed] 读取 Quicker 动作目录失败。", error.message)
    }

    @Test
    fun rejectsMalformedOrExtendedServerErrorEnvelopes() {
        listOf(
            """{"protocol":"quickerlink.panel-actions","version":2,"ok":false,"code":"bad code","error":"失败"}""",
            """{"protocol":"quickerlink.panel-actions","version":2,"ok":false,"code":"failed","error":""}""",
            """{"protocol":"quickerlink.panel-actions","version":2,"ok":"false","code":"failed","error":"失败"}""",
            """{"protocol":"quickerlink.panel-actions","version":2,"code":"failed","error":"失败"}""",
            """{"protocol":"quickerlink.panel-actions","version":2,"ok":false,"code":7,"error":"失败"}""",
            """{"protocol":"quickerlink.panel-actions","version":2,"ok":false,"code":"failed","error":7}""",
            """{"protocol":"quickerlink.panel-actions","version":2,"ok":false,"code":"failed","error":"失败","unexpected":true}""",
        ).forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                QuickerPanelActionsProtocol.parse(JsonPrimitive(payload))
            }
        }
    }

    @Test
    fun rejectsMalformedGroupsActionsAndNullableGroupFields() {
        val malformedPayloads = listOf(
            successCatalog(validScenes(globalGroups = "{}")),
            successCatalog(validScenes(globalGroups = "null")),
            successCatalog(validScenes(globalActions = "{}")),
            successCatalog(validScenes(globalActions = JsonPrimitive("not-an-array").toString())),
            successCatalog(validScenes(globalActions = "[false]")),
            successCatalog(
                validScenes(
                    globalActions = """[{"id":"$FIRST_ID","title":"动作一","order":0}]""",
                ),
            ),
            successCatalog(
                validScenes(
                    globalActions = """[{"id":"$FIRST_ID","title":"动作一","group":7,"order":0}]""",
                ),
            ),
        )

        malformedPayloads.forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                QuickerPanelActionsProtocol.parse(JsonPrimitive(payload))
            }
        }
    }

    private fun parse(
        globalGroups: String = """["常用"]""",
        globalActions: String = defaultActions(),
        commonGroups: String = "[]",
        commonActions: String = "[]",
    ): QuickerPanelActionCatalog = QuickerPanelActionsProtocol.parse(
        JsonPrimitive(
            successCatalog(
                validScenes(globalGroups, globalActions, commonGroups, commonActions),
            ),
        ),
    )

    private fun successCatalog(
        scenes: String = validScenes(),
        extraRoot: String = "",
    ): String = """
        {
          "protocol":"quickerlink.panel-actions",
          "version":2,
          "ok":true,
          "scenes":$scenes$extraRoot
        }
    """.trimIndent()

    private fun validScenes(
        globalGroups: String = """["常用"]""",
        globalActions: String = defaultActions(),
        commonGroups: String = "[]",
        commonActions: String = "[]",
    ): String = """
        [
          ${scene("_global", globalGroups, globalActions)},
          ${scene("common", commonGroups, commonActions)}
        ]
    """.trimIndent()

    private fun scene(
        name: String,
        groups: String = "[]",
        actions: String = "[]",
        extra: String = "",
    ): String = """
        {
          "scene":"$name",
          "groups":$groups,
          "actions":$actions$extra
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
