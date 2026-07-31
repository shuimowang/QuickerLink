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
    fun `builds strict v6 commands and canonical chunk data`() {
        assertEquals(
            "quickerlink:toolbox:v6:{\"op\":\"clipboard.read\"}",
            QuickerToolboxProtocol.clipboardReadCommand(),
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
            """, "transfer":{"id":"$TRANSFER_ID","name":"screen.jpg","mime":"image/jpeg","size":5,"sha256":"${QuickerToolboxProtocol.sha256("hello".toByteArray())}","chunkSize":65536}""",
        )
        val transfer = QuickerToolboxProtocol.parse(
            JsonParser.parseString(descriptorJson),
            QuickerToolboxProtocol.OP_SCREEN_CAPTURE,
        ) as QuickerToolboxResult.Transfer

        assertEquals(TRANSFER_ID, transfer.descriptor.id)
        assertEquals("screen.jpg", transfer.descriptor.name)
        assertEquals(5L, transfer.descriptor.size)
        assertEquals(QuickerToolboxProtocol.CHUNK_BYTES, transfer.descriptor.chunkSize)
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
                JsonPrimitive(success("download.finish")),
                QuickerToolboxProtocol.OP_DOWNLOAD_FINISH,
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
            """{"protocol":"quickerlink.toolbox","version":5,"ok":true,"op":"clipboard.read","text":""}""",
            """{"protocol":"quickerlink.panel-actions","version":5,"ok":false,"code":"unsupported_command","error":"不支持"}""",
        ).forEach { payload ->
            assertThrows(UnsupportedToolboxVersionException::class.java) {
                QuickerToolboxProtocol.parse(JsonPrimitive(payload), QuickerToolboxProtocol.OP_CLIPBOARD_READ)
            }
        }

        listOf(
            """{"protocol":"unexpected","version":5,"ok":true,"op":"clipboard.read","text":""}""",
            """{"protocol":"quickerlink.panel-actions","version":6,"ok":true,"op":"clipboard.read","text":""}""",
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
    }

    @Test
    fun `rejects duplicate response fields before parsing values`() {
        val payload =
            """{"protocol":"quickerlink.toolbox","version":6,"ok":false,"ok":true,"op":"clipboard.read","text":"secret"}"""
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
    }

    private fun commandJson(command: String) = JsonParser.parseString(
        command.removePrefix("quickerlink:toolbox:v6:"),
    ).asJsonObject

    private fun success(operation: String, extra: String = ""): String =
        """{"protocol":"quickerlink.toolbox","version":6,"ok":true,"op":"$operation"$extra}"""

    private fun error(code: String, operation: String = "download.pick"): String =
        """{"protocol":"quickerlink.toolbox","version":6,"ok":false,"op":"$operation","code":"$code"}"""

    private companion object {
        const val TRANSFER_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    }
}
