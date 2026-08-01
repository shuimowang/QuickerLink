package app.quickerlink.connection

import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class QuickerToolboxProtocolTest {
    @Test
    fun `builds strict v8 commands and canonical chunk data`() {
        assertEquals(
            "quickerlink:toolbox:v8:{\"op\":\"clipboard.read\"}",
            QuickerToolboxProtocol.clipboardReadCommand(),
        )
        assertEquals(
            "quickerlink:toolbox:v8:{\"op\":\"clipboard.write\",\"text\":\"手机文本\"}",
            QuickerToolboxProtocol.clipboardWriteCommand("手机文本"),
        )

        val begin = commandJson(
            QuickerToolboxProtocol.uploadBeginCommand(
                name = "测试.txt",
                mime = "text/plain",
                size = 3,
                sha256 = QuickerToolboxProtocol.sha256("abc".toByteArray()),
            ),
        )
        assertEquals(QuickerToolboxProtocol.OP_UPLOAD_BEGIN, begin["op"].asString)
        assertEquals("测试.txt", begin["name"].asString)
        assertEquals(3L, begin["size"].asLong)

        val bytes = "abc".toByteArray()
        val chunk = commandJson(QuickerToolboxProtocol.uploadChunkCommand(TRANSFER_ID, 0, bytes))
        assertEquals(Base64.getEncoder().encodeToString(bytes), chunk["data"].asString)
        assertEquals(QuickerToolboxProtocol.sha256(bytes), chunk["sha256"].asString)
        assertEquals(TRANSFER_ID, chunk["transferId"].asString)

        val click = commandJson(
            QuickerToolboxProtocol.screenClickCommand(TRANSFER_ID, 0, 1_000_000),
        )
        assertEquals("screen.click", click["op"].asString)
        assertEquals(setOf("op", "captureId", "x", "y"), click.keySet())
        assertEquals(0, click["x"].asInt)
        assertEquals(1_000_000, click["y"].asInt)

        val system = commandJson(
            QuickerToolboxProtocol.systemCommand(QuickerSystemCommand.RESTART_QUICKER),
        )
        assertEquals("system.command", system["op"].asString)
        assertEquals(setOf("op", "command"), system.keySet())
        assertEquals("restart-quicker", system["command"].asString)
    }

    @Test
    fun `rejects invalid clipboard write text`() {
        listOf(
            "",
            "   ",
            "x".repeat(16_001),
            "\u0000",
        ).forEach { text ->
            assertThrows(IllegalArgumentException::class.java) {
                QuickerToolboxProtocol.clipboardWriteCommand(text)
            }
        }
    }

    @Test
    fun `parses clipboard and transfer descriptors from strings or objects`() {
        val clipboard = QuickerToolboxProtocol.parse(
            JsonPrimitive(success("clipboard.read", ",\"text\":\"电脑文本\"")),
            QuickerToolboxProtocol.OP_CLIPBOARD_READ,
        ) as QuickerToolboxResult.Clipboard
        assertEquals("电脑文本", clipboard.text)

        val descriptorJson = success(
            "screen.capture",
            """, "transfer":{"id":"$TRANSFER_ID","name":"screen.jpg","mime":"image/jpeg","size":5,"sha256":"${QuickerToolboxProtocol.sha256("hello".toByteArray())}","chunkSize":65536},"captureId":"$TRANSFER_ID"""",
        )
        val transfer = QuickerToolboxProtocol.parse(
            JsonParser.parseString(descriptorJson),
            QuickerToolboxProtocol.OP_SCREEN_CAPTURE,
        ) as QuickerToolboxResult.ScreenCapture

        assertEquals(TRANSFER_ID, transfer.descriptor.id)
        assertEquals("screen.jpg", transfer.descriptor.name)
        assertEquals(5L, transfer.descriptor.size)
        assertEquals(QuickerToolboxProtocol.CHUNK_BYTES, transfer.descriptor.chunkSize)
        assertEquals(TRANSFER_ID, transfer.captureId)
    }

    @Test
    fun `verifies download chunk base64 hash offset and eof`() {
        val bytes = "hello".toByteArray()
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val hash = QuickerToolboxProtocol.sha256(bytes)
        val result = QuickerToolboxProtocol.parse(
            JsonPrimitive(
                success(
                    "download.chunk",
                    """, "transferId":"$TRANSFER_ID","offset":0,"data":"$encoded","sha256":"$hash","eof":true""",
                ),
            ),
            QuickerToolboxProtocol.OP_DOWNLOAD_CHUNK,
        ) as QuickerToolboxResult.DownloadChunk

        assertArrayEquals(bytes, result.bytes)
        assertEquals(0L, result.offset)
        assertTrue(result.eof)

        listOf(
            success(
                "download.chunk",
                """, "transferId":"$TRANSFER_ID","offset":0,"data":"$encoded","sha256":"${"0".repeat(64)}","eof":true""",
            ),
            success(
                "download.chunk",
                """, "transferId":"$TRANSFER_ID","offset":0,"data":"aGVsbG8","sha256":"$hash","eof":true""",
            ),
        ).forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                QuickerToolboxProtocol.parse(JsonPrimitive(payload), QuickerToolboxProtocol.OP_DOWNLOAD_CHUNK)
            }
        }
    }

    @Test
    fun `parses upload progress completion and terminal responses`() {
        val started = QuickerToolboxProtocol.parse(
            JsonPrimitive(
                success(
                    "upload.begin",
                    """, "transferId":"$TRANSFER_ID","nextOffset":0,"chunkSize":65536""",
                ),
            ),
            QuickerToolboxProtocol.OP_UPLOAD_BEGIN,
        ) as QuickerToolboxResult.UploadStarted
        assertEquals(0L, started.nextOffset)

        val advanced = QuickerToolboxProtocol.parse(
            JsonPrimitive(
                success(
                    "upload.chunk",
                    """, "transferId":"$TRANSFER_ID","nextOffset":65536""",
                ),
            ),
            QuickerToolboxProtocol.OP_UPLOAD_CHUNK,
        ) as QuickerToolboxResult.UploadAdvanced
        assertEquals(65_536L, advanced.nextOffset)

        val saved = QuickerToolboxProtocol.parse(
            JsonPrimitive(
                success(
                    "upload.finish",
                    ",\"savedName\":\"文档.txt\",\"location\":\"电脑下载 / Quicker Link\"",
                ),
            ),
            QuickerToolboxProtocol.OP_UPLOAD_FINISH,
        ) as QuickerToolboxResult.UploadSaved
        assertEquals("文档.txt", saved.savedName)

        assertEquals(
            QuickerToolboxResult.Completed,
            QuickerToolboxProtocol.parse(
                JsonPrimitive(success("clipboard.write")),
                QuickerToolboxProtocol.OP_CLIPBOARD_WRITE,
            ),
        )
        assertEquals(
            QuickerToolboxResult.Completed,
            QuickerToolboxProtocol.parse(
                JsonPrimitive(success("download.finish")),
                QuickerToolboxProtocol.OP_DOWNLOAD_FINISH,
            ),
        )
        assertEquals(
            QuickerToolboxResult.Completed,
            QuickerToolboxProtocol.parse(
                JsonPrimitive(success("screen.click")),
                QuickerToolboxProtocol.OP_SCREEN_CLICK,
            ),
        )
        assertEquals(
            QuickerToolboxResult.Completed,
            QuickerToolboxProtocol.parse(
                JsonPrimitive(success("system.command")),
                QuickerToolboxProtocol.OP_SYSTEM_COMMAND,
            ),
        )
    }

    @Test
    fun `maps known remote errors and rejects unknown or extended errors`() {
        val error = assertThrows(QuickerToolboxRemoteException::class.java) {
            QuickerToolboxProtocol.parse(
                JsonPrimitive(error("selection_cancelled")),
                QuickerToolboxProtocol.OP_DOWNLOAD_PICK,
            )
        }
        assertEquals("selection_cancelled", error.code)
        assertEquals("已取消选择文件", error.message)

        val capacityError = assertThrows(QuickerToolboxRemoteException::class.java) {
            QuickerToolboxProtocol.parse(
                JsonPrimitive(error("transfer_limit_reached", "upload.begin")),
                QuickerToolboxProtocol.OP_UPLOAD_BEGIN,
            )
        }
        assertEquals("transfer_limit_reached", capacityError.code)
        assertEquals("电脑端暂存传输数量已达上限", capacityError.message)

        val authenticationError = assertThrows(QuickerToolboxRemoteException::class.java) {
            QuickerToolboxProtocol.parse(
                JsonPrimitive(error("authentication_required")),
                QuickerToolboxProtocol.OP_DOWNLOAD_PICK,
            )
        }
        assertEquals("authentication_required", authenticationError.code)
        assertEquals("请先启用 Quicker WSS 服务并重新配对", authenticationError.message)

        mapOf(
            "secure_websocket_required" to "请先在 Quicker 中启用安全连接 WSS 并重新配对",
            "invalid_connection_password" to "连接验证码与 Quicker 设置不一致，请重新配对",
        ).forEach { (code, message) ->
            val connectionError = assertThrows(QuickerToolboxRemoteException::class.java) {
                QuickerToolboxProtocol.parse(
                    JsonPrimitive(error(code)),
                    QuickerToolboxProtocol.OP_DOWNLOAD_PICK,
                )
            }
            assertEquals(code, connectionError.code)
            assertEquals(message, connectionError.message)
        }

        mapOf(
            "screen_target_expired" to "屏幕画面已失效，请刷新后重试",
            "screen_click_failed" to "电脑未能完成屏幕点击",
            "clipboard_write_failed" to "无法写入电脑剪贴板",
            "system_command_failed" to "电脑未能执行系统命令",
        ).forEach { (code, message) ->
            val operation = when {
                code.startsWith("screen_") -> "screen.click"
                code.startsWith("clipboard_") -> "clipboard.write"
                else -> "system.command"
            }
            val remote = assertThrows(QuickerToolboxRemoteException::class.java) {
                QuickerToolboxProtocol.parse(
                    JsonPrimitive(error(code, operation)),
                    when (operation) {
                        "screen.click" -> QuickerToolboxProtocol.OP_SCREEN_CLICK
                        "clipboard.write" -> QuickerToolboxProtocol.OP_CLIPBOARD_WRITE
                        else -> QuickerToolboxProtocol.OP_SYSTEM_COMMAND
                    },
                )
            }
            assertEquals(message, remote.message)
        }

        listOf(
            error("unknown_error"),
            error("selection_cancelled").dropLast(1) + ",\"extra\":true}",
        ).forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                QuickerToolboxProtocol.parse(JsonPrimitive(payload), QuickerToolboxProtocol.OP_DOWNLOAD_PICK)
            }
        }
    }

    @Test
    fun `separates old versions from malformed protocols`() {
        listOf(
            """{"protocol":"quickerlink.toolbox","version":6,"ok":true,"op":"clipboard.read","text":""}""",
            """{"protocol":"quickerlink.panel-actions","version":5,"ok":false,"code":"unsupported_command","error":"不支持"}""",
        ).forEach { payload ->
            assertThrows(UnsupportedToolboxVersionException::class.java) {
                QuickerToolboxProtocol.parse(JsonPrimitive(payload), QuickerToolboxProtocol.OP_CLIPBOARD_READ)
            }
        }

        listOf(
            """{"protocol":"unexpected","version":5,"ok":true,"op":"clipboard.read","text":""}""",
            """{"protocol":"quickerlink.panel-actions","version":8,"ok":true,"op":"clipboard.read","text":""}""",
            """{"version":5,"ok":true,"op":"clipboard.read","text":""}""",
            """{"protocol":"quickerlink.toolbox","version":"5","ok":true,"op":"clipboard.read","text":""}""",
        ).forEach { payload ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                QuickerToolboxProtocol.parse(JsonPrimitive(payload), QuickerToolboxProtocol.OP_CLIPBOARD_READ)
            }
            assertFalse(failure is UnsupportedToolboxVersionException)
        }
    }

    @Test
    fun `rejects mismatched operations extra fields and oversized payloads`() {
        listOf(
            success("clipboard.read", """, "text":"ok","extra":true"""),
            success("clipboard.read", """, "text":"ok""").replace("clipboard.read", "screen.capture"),
            success(
                "screen.capture",
                """, "transfer":{"id":"INVALID","name":"screen.jpg","mime":"image/jpeg","size":1,"sha256":"${"0".repeat(64)}","chunkSize":65536}""",
            ),
        ).forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                QuickerToolboxProtocol.parse(JsonPrimitive(payload), QuickerToolboxProtocol.OP_CLIPBOARD_READ)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuickerToolboxProtocol.parse(
                JsonPrimitive("x".repeat(100_001)),
                QuickerToolboxProtocol.OP_CLIPBOARD_READ,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuickerToolboxProtocol.parse(
                JsonPrimitive(success("system.command", ",\"extra\":true")),
                QuickerToolboxProtocol.OP_SYSTEM_COMMAND,
            )
        }
    }

    @Test
    fun `requires canonical capture id and exact screen capture fields`() {
        val transfer =
            """, "transfer":{"id":"$TRANSFER_ID","name":"screen.jpg","mime":"image/jpeg","size":1,"sha256":"${"0".repeat(64)}","chunkSize":65536}"""
        listOf(
            success("screen.capture", transfer),
            success("screen.capture", "$transfer,\"captureId\":\"${TRANSFER_ID.uppercase()}\""),
            success("screen.capture", "$transfer,\"captureId\":\"$TRANSFER_ID\",\"extra\":true"),
        ).forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                QuickerToolboxProtocol.parse(JsonPrimitive(payload), QuickerToolboxProtocol.OP_SCREEN_CAPTURE)
            }
        }
    }

    @Test
    fun `rejects invalid normalized screen click coordinates and identifiers`() {
        listOf(
            Triple(TRANSFER_ID, -1, 0),
            Triple(TRANSFER_ID, 0, 1_000_001),
            Triple("not-a-uuid", 0, 0),
        ).forEach { (captureId, x, y) ->
            assertThrows(IllegalArgumentException::class.java) {
                QuickerToolboxProtocol.screenClickCommand(captureId, x, y)
            }
        }
        assertEquals(
            listOf("shutdown", "sleep", "restart-quicker"),
            QuickerSystemCommand.entries.map(QuickerSystemCommand::wireValue),
        )
    }

    @Test
    fun `rejects duplicate response fields before parsing values`() {
        val payload =
            """{"protocol":"quickerlink.toolbox","version":8,"ok":false,"ok":true,"op":"clipboard.read","text":"secret"}"""
        assertThrows(IllegalArgumentException::class.java) {
            QuickerToolboxProtocol.parse(JsonPrimitive(payload), QuickerToolboxProtocol.OP_CLIPBOARD_READ)
        }
    }

    @Test
    fun `rejects invalid upload metadata and chunks`() {
        val hash = QuickerToolboxProtocol.sha256(ByteArray(0))
        assertThrows(IllegalArgumentException::class.java) {
            QuickerToolboxProtocol.uploadBeginCommand("../secret.txt", "text/plain", 0, hash)
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuickerToolboxProtocol.uploadBeginCommand("file.txt", "Text/Plain", 0, hash)
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuickerToolboxProtocol.uploadChunkCommand(TRANSFER_ID, 0, ByteArray(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuickerToolboxProtocol.uploadChunkCommand(
                TRANSFER_ID,
                0,
                ByteArray(QuickerToolboxProtocol.CHUNK_BYTES + 1),
            )
        }
        QuickerToolboxProtocol.uploadBeginCommand(
            "file.txt",
            "text/plain",
            QuickerToolboxProtocol.MAX_FILE_BYTES,
            hash,
        )
        assertThrows(IllegalArgumentException::class.java) {
            QuickerToolboxProtocol.uploadBeginCommand(
                "file.txt",
                "text/plain",
                QuickerToolboxProtocol.MAX_FILE_BYTES + 1,
                hash,
            )
        }
    }

    private fun commandJson(command: String) = JsonParser.parseString(
        command.removePrefix("quickerlink:toolbox:v8:"),
    ).asJsonObject

    private fun success(operation: String, extra: String = ""): String =
        """{"protocol":"quickerlink.toolbox","version":8,"ok":true,"op":"$operation"$extra}"""

    private fun error(code: String, operation: String = "download.pick"): String =
        """{"protocol":"quickerlink.toolbox","version":8,"ok":false,"op":"$operation","code":"$code"}"""

    private companion object {
        const val TRANSFER_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    }
}
