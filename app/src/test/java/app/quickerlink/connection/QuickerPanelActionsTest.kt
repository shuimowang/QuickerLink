package app.quickerlink.connection

import app.quickerlink.data.ActionParameterChoice
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class QuickerPanelActionsTest {
    @Test
    fun usesStrictV9PanelActionCommand() {
        assertEquals("quickerlink:list-panel-actions:v9", QuickerPanelActionsProtocol.LIST_COMMAND)
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
                [{"id":"$SECOND_ID","title":"通用动作","group":"默认","order":3,"icon":null,"parameterChoices":[]}]
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
        assertEquals(emptyList<ActionParameterChoice>(), catalog.scenes.first().actions.single().parameterChoices)
        assertEquals(64L * 1024 * 1024, catalog.capabilities.maxFileBytes)
        assertTrue(catalog.capabilities.screenClick)
        assertTrue(catalog.capabilities.clipboardWrite)
        assertTrue(catalog.capabilities.systemControl)
        assertTrue(catalog.capabilities.windowList)
        assertTrue(catalog.capabilities.windowActivate)
        assertEquals(64 * 1024, catalog.capabilities.chunkBytes)
        assertTrue(catalog.capabilities.desktopPush.text)
        assertTrue(catalog.capabilities.desktopPush.notification)
        assertTrue(catalog.capabilities.desktopPush.file)
        assertEquals(16_000, catalog.capabilities.desktopPush.maxTextChars)
        assertEquals(64L * 1024 * 1024, catalog.capabilities.desktopPush.maxFileBytes)
    }

    @Test
    fun parsesV9CatalogReturnedAsObject() {
        val data = JsonParser.parseString(successCatalog()).asJsonObject

        val catalog = QuickerPanelActionsProtocol.parse(data)

        assertEquals(2, catalog.scenes.size)
        assertEquals(FIRST_ID, catalog.actions.single().id)
    }

    @Test
    fun parsesOrderedContextMenuParameterChoices() {
        val catalog = parse(
            globalActions = """
                [{
                  "id":"$FIRST_ID",
                  "title":"录制工具",
                  "group":"常用",
                  "order":0,
                  "icon":null,
                  "parameterChoices":[
                    {"label":"设置","value":"action_settings"},
                    {"label":"录制","value":"action_ffmpeg"}
                  ]
                }]
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                ActionParameterChoice("设置", "action_settings"),
                ActionParameterChoice("录制", "action_ffmpeg"),
            ),
            catalog.actions.single().parameterChoices,
        )
    }

    @Test
    fun rejectsMissingMalformedOrUnboundedParameterChoices() {
        val base = """{"id":"$FIRST_ID","title":"动作","group":"常用","order":0,"icon":null"""
        val tooManyChoices = List(51) { index ->
            """{"label":"选项$index","value":"value_$index"}"""
        }.joinToString(",")
        val malformedActions = listOf(
            "$base}",
            "$base,\"parameterChoices\":null}",
            "$base,\"parameterChoices\":{}}",
            "$base,\"parameterChoices\":[false]}",
            "$base,\"parameterChoices\":[{\"label\":\"设置\"}]}",
            "$base,\"parameterChoices\":[{\"label\":\"设置\",\"value\":\"action_settings\",\"extra\":true}]}",
            "$base,\"parameterChoices\":[{\"label\":\"\",\"value\":\"action_settings\"}]}",
            "$base,\"parameterChoices\":[{\"label\":\"设置\",\"value\":\"\"}]}",
            "$base,\"parameterChoices\":[{\"label\":\"${"x".repeat(121)}\",\"value\":\"value\"}]}",
            "$base,\"parameterChoices\":[{\"label\":\"设置\",\"value\":\"${"x".repeat(2_049)}\"}]}",
            "$base,\"parameterChoices\":[$tooManyChoices]}",
        )

        malformedActions.forEach { action ->
            assertThrows(IllegalArgumentException::class.java) {
                parse(globalActions = "[$action]")
            }
        }
    }

    @Test
    fun acceptsEmptyCommonSceneAndUngroupedNonContiguousActions() {
        val catalog = parse(
            globalGroups = "[]",
            globalActions = """
                [
                  {"id":"$FIRST_ID","title":"动作一","group":null,"order":2,"icon":null,"parameterChoices":[]},
                  {"id":"$SECOND_ID","title":"动作二","group":null,"order":7,"icon":null,"parameterChoices":[]}
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
    fun acceptsCatalogsWith501And10000GroupsPerScene() {
        listOf(501, 10_000).forEach { groupCount ->
            val catalog = parse(
                globalGroups = groups(groupCount),
                globalActions = "[]",
            )

            assertEquals(groupCount, catalog.scenes.first().groups.size)
        }
    }

    @Test
    fun rejectsCatalogWith10001GroupsInOneScene() {
        assertThrows(IllegalArgumentException::class.java) {
            parse(
                globalGroups = groups(10_001),
                globalActions = "[]",
            )
        }
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
        listOf(8, 5).forEach { version ->
            assertThrows(UnsupportedPanelCatalogVersionException::class.java) {
                QuickerPanelActionsProtocol.parse(
                    JsonPrimitive(successCatalog().replace("\"version\":9", "\"version\":$version")),
                )
            }
        }
    }

    @Test
    fun identifiesOldCompanionUnsupportedCommandResponseAsVersionMismatch() {
        val oldCompanionResponse = """
            {
              "protocol":"quickerlink.panel-actions",
              "version":4,
              "ok":false,
              "code":"unsupported_command",
              "error":"不支持的命令"
            }
        """.trimIndent()

        assertThrows(UnsupportedPanelCatalogVersionException::class.java) {
            QuickerPanelActionsProtocol.parse(JsonPrimitive(oldCompanionResponse))
        }
    }

    @Test
    fun acceptsQuickerHttpsAndRenderedPngIcons() {
        val dataIcon = "data:image/png;base64,$ONE_PIXEL_PNG"
        val catalog = parse(
            globalActions = """
                [
                  {"id":"$FIRST_ID","title":"网络图标","group":"常用","order":0,"icon":"$QUICKER_ICON","parameterChoices":[]},
                  {"id":"$SECOND_ID","title":"字体图标","group":"常用","order":1,"icon":"$dataIcon","parameterChoices":[]}
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
                        """[{"id":"$FIRST_ID","title":"动作","group":"常用","order":0,"icon":"$icon","parameterChoices":[]}]""",
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
                    [{"id":"$FIRST_ID","title":"动作","group":"常用","order":0,"icon":null,"parameterChoices":[],"unexpected":true}]
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
    fun rejectsMissingDisabledOrExtendedCapabilities() {
        val valid = successCatalog()
        val invalidPayloads = listOf(
            valid.replace("\"capabilities\":$CAPABILITIES_JSON,", ""),
            valid.replace("\"stopAction\":true", "\"stopAction\":false"),
            valid.replace("\"screenCapture\":true", "\"screenCapture\":false"),
            valid.replace("\"screenClick\":true", "\"screenClick\":false"),
            valid.replace("\"screenClick\":true,", ""),
            valid.replace("\"clipboardRead\":true", "\"clipboardRead\":false"),
            valid.replace("\"clipboardWrite\":true", "\"clipboardWrite\":false"),
            valid.replace("\"clipboardWrite\":true,", ""),
            valid.replace("\"systemControl\":true", "\"systemControl\":false"),
            valid.replace("\"systemControl\":true,", ""),
            valid.replace("\"windowList\":true", "\"windowList\":false"),
            valid.replace("\"windowList\":true,", ""),
            valid.replace("\"windowActivate\":true", "\"windowActivate\":false"),
            valid.replace("\"windowActivate\":true,", ""),
            valid.replace("\"stopAction\":true", "\"stopAction\":true,\"extra\":true"),
            valid.replace(CAPABILITIES_JSON, "null"),
            valid.replace("\"fileTransfer\":$FILE_TRANSFER_JSON", "\"fileTransfer\":null"),
            valid.replace("\"maxBytes\":67108864", "\"maxBytes\":67108863"),
            valid.replace("\"chunkBytes\":65536", "\"chunkBytes\":32768"),
            valid.replace("\"chunkBytes\":65536", "\"chunkBytes\":65536,\"extra\":true"),
            valid.replace("\"desktopPush\":$DESKTOP_PUSH_JSON", "\"desktopPush\":null"),
            valid.replace("\"text\":true", "\"text\":false"),
            valid.replace("\"notification\":true", "\"notification\":false"),
            valid.replace("\"file\":true", "\"file\":false"),
            valid.replace("\"maxTextChars\":16000", "\"maxTextChars\":15999"),
            valid.replace("\"maxFileBytes\":67108864", "\"maxFileBytes\":67108863"),
            valid.replace("\"maxFileBytes\":67108864", "\"maxFileBytes\":67108864,\"extra\":true"),
        )

        invalidPayloads.forEach { payload ->
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
              {"id":"$FIRST_ID","title":"动作一","group":"常用","order":0,"icon":null,"parameterChoices":[]},
              {"id":"$duplicateId","title":"动作二","group":"常用","order":1,"icon":null,"parameterChoices":[]}
            ]
        """.trimIndent()
        val duplicateAcrossScenes = """
            [{"id":"$duplicateId","title":"重复动作","group":"默认","order":0,"icon":null,"parameterChoices":[]}]
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
            parse(globalActions = """[{"id":"not-a-guid","title":"动作一","group":"常用","order":0,"icon":null,"parameterChoices":[]}]""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parse(
                globalActions = """
                    [{"id":"$FIRST_ID","title":"动作一","group":"不存在","order":0,"icon":null,"parameterChoices":[]}]
                """.trimIndent(),
            )
        }
    }

    @Test
    fun rejectsDuplicateReversedAndNegativeActionOrder() {
        val invalidActions = listOf(
            """
                [
                  {"id":"$FIRST_ID","title":"动作一","group":"常用","order":3,"icon":null,"parameterChoices":[]},
                  {"id":"$SECOND_ID","title":"动作二","group":"常用","order":3,"icon":null,"parameterChoices":[]}
                ]
            """.trimIndent(),
            """
                [
                  {"id":"$FIRST_ID","title":"动作一","group":"常用","order":3,"icon":null,"parameterChoices":[]},
                  {"id":"$SECOND_ID","title":"动作二","group":"常用","order":2,"icon":null,"parameterChoices":[]}
                ]
            """.trimIndent(),
            """[{"id":"$FIRST_ID","title":"动作一","group":"常用","order":-1,"icon":null,"parameterChoices":[]}]""",
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
    fun surfacesStableCodeAndMessageFromV9ServerError() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            QuickerPanelActionsProtocol.parse(
                JsonPrimitive(
                    """
                        {
                          "protocol":"quickerlink.panel-actions",
                          "version":9,
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
    fun mapsConnectionErrorsToActionableMessages() {
        mapOf(
            "secure_websocket_required" to "请先在 Quicker 中启用安全连接 WSS 并重新配对",
            "invalid_connection_password" to "连接验证码与 Quicker 设置不一致，请重新配对",
            "authentication_required" to "请先启用 Quicker WSS 服务并重新配对",
        ).forEach { (code, expectedMessage) ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                QuickerPanelActionsProtocol.parse(
                    JsonPrimitive(
                        """{"protocol":"quickerlink.panel-actions","version":9,"ok":false,"code":"$code","error":"fallback"}""",
                    ),
                )
            }

            assertEquals("[$code] $expectedMessage", error.message)
        }
    }

    @Test
    fun rejectsMalformedOrExtendedServerErrorEnvelopes() {
        listOf(
            """{"protocol":"quickerlink.panel-actions","version":9,"ok":false,"code":"bad code","error":"失败"}""",
            """{"protocol":"quickerlink.panel-actions","version":9,"ok":false,"code":"failed","error":""}""",
            """{"protocol":"quickerlink.panel-actions","version":9,"ok":"false","code":"failed","error":"失败"}""",
            """{"protocol":"quickerlink.panel-actions","version":9,"code":"failed","error":"失败"}""",
            """{"protocol":"quickerlink.panel-actions","version":9,"ok":false,"code":7,"error":"失败"}""",
            """{"protocol":"quickerlink.panel-actions","version":9,"ok":false,"code":"failed","error":7}""",
            """{"protocol":"quickerlink.panel-actions","version":9,"ok":false,"code":"failed","error":"失败","unexpected":true}""",
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
                    globalActions = """[{"id":"$FIRST_ID","title":"动作一","order":0,"icon":null,"parameterChoices":[]}]""",
                ),
            ),
            successCatalog(
                validScenes(
                    globalActions = """[{"id":"$FIRST_ID","title":"动作一","group":7,"order":0,"icon":null,"parameterChoices":[]}]""",
                ),
            ),
            successCatalog(
                validScenes(
                    globalActions = """[{"id":"$FIRST_ID","title":"动作一","group":"常用","order":0,"parameterChoices":[]}]""",
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
          "version":9,
          "ok":true,
          "capabilities":$CAPABILITIES_JSON,
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
        [{"id":"$FIRST_ID","title":"打开项目","group":"常用","order":0,"icon":"$QUICKER_ICON","parameterChoices":[]}]
    """.trimIndent()

    private fun groups(count: Int): String = (0 until count).joinToString(",", "[", "]") { index ->
        "\"分组$index\""
    }

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
        const val FILE_TRANSFER_JSON = "{\"maxBytes\":67108864,\"chunkBytes\":65536}"
        const val DESKTOP_PUSH_JSON =
            "{\"text\":true,\"notification\":true,\"file\":true," +
                "\"maxTextChars\":16000,\"maxFileBytes\":67108864}"
        const val CAPABILITIES_JSON =
            "{\"stopAction\":true,\"screenCapture\":true,\"screenClick\":true," +
                "\"clipboardRead\":true,\"clipboardWrite\":true,\"systemControl\":true," +
                "\"windowList\":true,\"windowActivate\":true," +
                "\"fileTransfer\":$FILE_TRANSFER_JSON,\"desktopPush\":$DESKTOP_PUSH_JSON}"
        const val ONE_PIXEL_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    }
}
