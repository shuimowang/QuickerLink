package app.quickerlink.connection

import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class QuickerPanelActionsTest {
    @Test
    fun usesStrictV3PanelActionCommand() {
        assertEquals("quickerlink:list-panel-actions:v3", QuickerPanelActionsProtocol.LIST_COMMAND)
        assertEquals(
            "b02b2732-f087-4e45-416d-08deee3e76ba",
            QuickerPanelActionsProtocol.COMPANION_SHARED_ACTION_ID,
        )
    }

    @Test
    fun parsesExactlyGlobalThenCommonScenesFromJsonString() {
        val catalog = parse(
            commonGroups = """["默认"]""",
            commonActions = """
                [{"id":"$SECOND_ID","title":"通用动作","group":"默认","order":3,"icon":null}]
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
        assertEquals(QUICKER_ICON, catalog.scenes.first().actions.single().icon)
    }

    @Test
    fun parsesV3CatalogReturnedAsObject() {
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
                  {"id":"$FIRST_ID","title":"动作一","group":null,"order":2,"icon":null},
                  {"id":"$SECOND_ID","title":"动作二","group":null,"order":7,"icon":null}
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
    fun rejectsOldCatalogVersions() {
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
        assertThrows(IllegalArgumentException::class.java) {
            QuickerPanelActionsProtocol.parse(JsonPrimitive(successCatalog().replace("\"version\":3", "\"version\":2")))
        }
    }

    @Test
    fun acceptsQuickerHttpsAndRenderedPngIcons() {
        val dataIcon = "data:image/png;base64,$ONE_PIXEL_PNG"
        val catalog = parse(
            globalActions = """
                [
                  {"id":"$FIRST_ID","title":"网络图标","group":"常用","order":0,"icon":"$QUICKER_ICON"},
                  {"id":"$SECOND_ID","title":"字体图标","group":"常用","order":1,"icon":"$dataIcon"}
                ]
            """.trimIndent(),
        )

        assertEquals(listOf(QUICKER_ICON, dataIcon), catalog.actions.map(QuickerPanelAction::icon))
    }

    @Test
    fun rejectsUntrustedOrMalformedIcons() {
        val invalidIcons = listOf(
            "http://files.getquicker.net/_icons/A.png",
            "HTTPS://files.getquicker.net/_icons/A.png",
            "https://FILES.getquicker.net/_icons/A.png",
            "https://files.getquicker.net:443/_icons/A.png",
            "https://example.com/_icons/A.png",
            "https://user@files.getquicker.net/_icons/A.png",
            "https://files.getquicker.net/_icons/A.png?tracking=1",
            "https://files.getquicker.net/_icons/../private/A.png",
            "https://files.getquicker.net/_icons/%2e%2e/private.png",
            "https://files.getquicker.net/_icons/folder/A.png",
            "data:image/png;base64,not-base64",
            "data:image/png;base64,SGVsbG8=",
            "data:image/png;base64,${pngWithDimensions(width = 4096, height = 1)}",
            "fa:Solid_Search",
        )

        invalidIcons.forEach { icon ->
            assertThrows(IllegalArgumentException::class.java) {
                parse(
                    globalActions =
                        """[{"id":"$FIRST_ID","title":"动作","group":"常用","order":0,"icon":"$icon"}]""",
                )
            }
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
                    [{"id":"$FIRST_ID","title":"动作","group":"常用","order":0,"icon":null,"unexpected":true}]
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
              {"id":"$FIRST_ID","title":"动作一","group":"常用","order":0,"icon":null},
              {"id":"$duplicateId","title":"动作二","group":"常用","order":1,"icon":null}
            ]
        """.trimIndent()
        val duplicateAcrossScenes = """
            [{"id":"$duplicateId","title":"重复动作","group":"默认","order":0,"icon":null}]
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
            parse(globalActions = """[{"id":"not-a-guid","title":"动作一","group":"常用","order":0,"icon":null}]""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parse(
                globalActions = """
                    [{"id":"$FIRST_ID","title":"动作一","group":"不存在","order":0,"icon":null}]
                """.trimIndent(),
            )
        }
    }

    @Test
    fun rejectsDuplicateReversedAndNegativeActionOrder() {
        val invalidActions = listOf(
            """
                [
                  {"id":"$FIRST_ID","title":"动作一","group":"常用","order":3,"icon":null},
                  {"id":"$SECOND_ID","title":"动作二","group":"常用","order":3,"icon":null}
                ]
            """.trimIndent(),
            """
                [
                  {"id":"$FIRST_ID","title":"动作一","group":"常用","order":3,"icon":null},
                  {"id":"$SECOND_ID","title":"动作二","group":"常用","order":2,"icon":null}
                ]
            """.trimIndent(),
            """[{"id":"$FIRST_ID","title":"动作一","group":"常用","order":-1,"icon":null}]""",
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
    fun surfacesStableCodeAndMessageFromV3ServerError() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            QuickerPanelActionsProtocol.parse(
                JsonPrimitive(
                    """
                        {
                          "protocol":"quickerlink.panel-actions",
                          "version":3,
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
            """{"protocol":"quickerlink.panel-actions","version":3,"ok":false,"code":"bad code","error":"失败"}""",
            """{"protocol":"quickerlink.panel-actions","version":3,"ok":false,"code":"failed","error":""}""",
            """{"protocol":"quickerlink.panel-actions","version":3,"ok":"false","code":"failed","error":"失败"}""",
            """{"protocol":"quickerlink.panel-actions","version":3,"code":"failed","error":"失败"}""",
            """{"protocol":"quickerlink.panel-actions","version":3,"ok":false,"code":7,"error":"失败"}""",
            """{"protocol":"quickerlink.panel-actions","version":3,"ok":false,"code":"failed","error":7}""",
            """{"protocol":"quickerlink.panel-actions","version":3,"ok":false,"code":"failed","error":"失败","unexpected":true}""",
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
                    globalActions = """[{"id":"$FIRST_ID","title":"动作一","order":0,"icon":null}]""",
                ),
            ),
            successCatalog(
                validScenes(
                    globalActions = """[{"id":"$FIRST_ID","title":"动作一","group":7,"order":0,"icon":null}]""",
                ),
            ),
            successCatalog(
                validScenes(
                    globalActions = """[{"id":"$FIRST_ID","title":"动作一","group":"常用","order":0}]""",
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
          "version":3,
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
        [{"id":"$FIRST_ID","title":"打开项目","group":"常用","order":0,"icon":"$QUICKER_ICON"}]
    """.trimIndent()

    private fun pngWithDimensions(width: Int, height: Int): String {
        val bytes = Base64.getDecoder().decode(ONE_PIXEL_PNG)
        writeUInt32(bytes, 16, width)
        writeUInt32(bytes, 20, height)
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun writeUInt32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private companion object {
        const val FIRST_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val SECOND_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val QUICKER_ICON = "https://files.getquicker.net/_icons/ABC123.png"
        const val ONE_PIXEL_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    }
}
