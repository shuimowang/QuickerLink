package app.quickerlink.connection

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

data class QuickerTransferDescriptor(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val sha256: String,
    val chunkSize: Int,
)

sealed interface QuickerToolboxResult {
    data class Clipboard(val text: String) : QuickerToolboxResult
    data class Transfer(val descriptor: QuickerTransferDescriptor) : QuickerToolboxResult
    data class DownloadChunk(
        val transferId: String,
        val offset: Long,
        val bytes: ByteArray,
        val eof: Boolean,
    ) : QuickerToolboxResult

    data class UploadStarted(
        val transferId: String,
        val nextOffset: Long,
        val chunkSize: Int,
    ) : QuickerToolboxResult

    data class UploadAdvanced(
        val transferId: String,
        val nextOffset: Long,
    ) : QuickerToolboxResult

    data class UploadSaved(
        val savedName: String,
        val location: String,
    ) : QuickerToolboxResult

    data object Completed : QuickerToolboxResult
}

internal class UnsupportedToolboxVersionException :
    IllegalArgumentException("工具箱协议版本不受支持")

internal class QuickerToolboxRemoteException(
    val code: String,
    message: String,
) : IllegalStateException(message)

object QuickerToolboxProtocol {
    const val VERSION = 6
    const val MAX_FILE_BYTES = 8L * 1024 * 1024
    const val CHUNK_BYTES = 64 * 1024
    const val MAX_CLIPBOARD_CHARS = 16_000

    const val OP_CLIPBOARD_READ = "clipboard.read"
    const val OP_SCREEN_CAPTURE = "screen.capture"
    const val OP_DOWNLOAD_PICK = "download.pick"
    const val OP_DOWNLOAD_CHUNK = "download.chunk"
    const val OP_DOWNLOAD_FINISH = "download.finish"
    const val OP_UPLOAD_BEGIN = "upload.begin"
    const val OP_UPLOAD_CHUNK = "upload.chunk"
    const val OP_UPLOAD_FINISH = "upload.finish"
    const val OP_TRANSFER_CANCEL = "transfer.cancel"

    private const val COMMAND_PREFIX = "quickerlink:toolbox:v6:"
    private const val PROTOCOL = "quickerlink.toolbox"
    private const val LEGACY_CATALOG_PROTOCOL = "quickerlink.panel-actions"
    private const val MAX_PAYLOAD_LENGTH = 100_000
    private const val MAX_FILE_NAME_LENGTH = 120
    private const val MAX_MIME_LENGTH = 127
    private const val MAX_ERROR_CODE_LENGTH = 64
    private const val MAX_LOCATION_LENGTH = 160
    private val commonSuccessFields = setOf("protocol", "version", "ok", "op")
    private val errorFields = commonSuccessFields + "code"
    private val errorCodePattern = Regex("[a-z][a-z0-9_]{0,63}")
    private val mimePattern = Regex("[!-~]{1,127}")
    private val errorMessages = mapOf(
        "invalid_request" to "传输请求格式无效",
        "unsupported_operation" to "Quicker Link 动作不支持这项功能",
        "clipboard_read_failed" to "无法读取电脑剪贴板",
        "clipboard_too_large" to "电脑剪贴板文本过大",
        "screen_capture_failed" to "无法截取电脑屏幕",
        "selection_cancelled" to "已取消选择文件",
        "ui_unavailable" to "Quicker 暂时无法打开文件选择窗口",
        "file_not_found" to "所选文件已不存在",
        "file_too_large" to "文件超过 8 MiB 上限",
        "file_read_failed" to "读取电脑文件失败",
        "invalid_transfer_id" to "传输标识无效",
        "transfer_not_found" to "传输已失效，请重新开始",
        "invalid_offset" to "文件分块位置不一致",
        "invalid_chunk" to "文件分块格式无效",
        "checksum_mismatch" to "文件校验失败",
        "transfer_incomplete" to "文件尚未传输完整",
        "storage_unavailable" to "电脑存储暂不可用",
        "save_failed" to "电脑保存文件失败",
        "response_too_large" to "Quicker Link 响应过大",
        "internal_error" to "Quicker Link 动作执行失败",
    )

    fun clipboardReadCommand(): String = command(OP_CLIPBOARD_READ)

    fun screenCaptureCommand(): String = command(OP_SCREEN_CAPTURE)

    fun downloadPickCommand(): String = command(OP_DOWNLOAD_PICK)

    fun downloadChunkCommand(transferId: String, offset: Long): String = command(
        OP_DOWNLOAD_CHUNK,
        "transferId" to canonicalUuid(transferId),
        "offset" to offset.requireRange(0, MAX_FILE_BYTES, "文件分块位置无效"),
    )

    fun downloadFinishCommand(transferId: String): String = command(
        OP_DOWNLOAD_FINISH,
        "transferId" to canonicalUuid(transferId),
    )

    fun uploadBeginCommand(
        name: String,
        mime: String,
        size: Long,
        sha256: String,
    ): String = command(
        OP_UPLOAD_BEGIN,
        "name" to validateFileName(name),
        "mime" to validateMime(mime),
        "size" to size.requireRange(0, MAX_FILE_BYTES, "文件大小无效"),
        "sha256" to validateSha256(sha256),
    )

    fun uploadChunkCommand(
        transferId: String,
        offset: Long,
        bytes: ByteArray,
    ): String {
        require(bytes.isNotEmpty() && bytes.size <= CHUNK_BYTES) { "文件分块大小无效" }
        return command(
            OP_UPLOAD_CHUNK,
            "transferId" to canonicalUuid(transferId),
            "offset" to offset.requireRange(0, MAX_FILE_BYTES, "文件分块位置无效"),
            "data" to Base64.getEncoder().encodeToString(bytes),
            "sha256" to sha256(bytes),
        )
    }

    fun uploadFinishCommand(transferId: String): String = command(
        OP_UPLOAD_FINISH,
        "transferId" to canonicalUuid(transferId),
    )

    fun cancelCommand(transferId: String): String = command(
        OP_TRANSFER_CANCEL,
        "transferId" to canonicalUuid(transferId),
    )

    fun parse(data: JsonElement?, expectedOperation: String): QuickerToolboxResult {
        require(expectedOperation in supportedOperations) { "工具箱操作无效" }
        val root = decodeRoot(data)
        val protocol = root.requiredString("protocol", "工具箱响应缺少协议标识")
        require(protocol == PROTOCOL || protocol == LEGACY_CATALOG_PROTOCOL) {
            "工具箱响应协议标识无效"
        }
        val version = root.requiredInt("version", "工具箱响应版本格式无效")
        if (version != VERSION) throw UnsupportedToolboxVersionException()
        require(protocol == PROTOCOL) { "工具箱响应协议标识无效" }
        val operation = root.requiredString("op", "工具箱响应缺少操作类型")
        require(operation == expectedOperation) { "工具箱响应操作不匹配" }

        if (!root.requiredBoolean("ok", "工具箱响应状态格式无效")) {
            root.requireFields(errorFields, "工具箱错误响应格式无效")
            val code = root.requiredString("code", "工具箱错误响应缺少代码")
            require(
                code.length <= MAX_ERROR_CODE_LENGTH &&
                    errorCodePattern.matches(code) &&
                    code in errorMessages,
            ) { "工具箱错误代码无效" }
            throw QuickerToolboxRemoteException(code, requireNotNull(errorMessages[code]))
        }

        return when (operation) {
            OP_CLIPBOARD_READ -> parseClipboard(root)
            OP_SCREEN_CAPTURE,
            OP_DOWNLOAD_PICK,
            -> parseTransfer(root)
            OP_DOWNLOAD_CHUNK -> parseDownloadChunk(root)
            OP_DOWNLOAD_FINISH,
            OP_TRANSFER_CANCEL,
            -> {
                root.requireFields(commonSuccessFields, "工具箱完成响应格式无效")
                QuickerToolboxResult.Completed
            }
            OP_UPLOAD_BEGIN -> parseUploadStarted(root)
            OP_UPLOAD_CHUNK -> parseUploadAdvanced(root)
            OP_UPLOAD_FINISH -> parseUploadSaved(root)
            else -> error("Unreachable operation")
        }
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { value -> "%02x".format(value) }

    private fun parseClipboard(root: JsonObject): QuickerToolboxResult.Clipboard {
        root.requireFields(commonSuccessFields + "text", "剪贴板响应格式无效")
        val text = root.requiredString("text", "剪贴板文本格式无效", allowEmpty = true)
        require(
            text.length <= MAX_CLIPBOARD_CHARS &&
                text.toByteArray(StandardCharsets.UTF_8).size <= 48 * 1024,
        ) { "剪贴板文本过大" }
        return QuickerToolboxResult.Clipboard(text)
    }

    private fun parseTransfer(root: JsonObject): QuickerToolboxResult.Transfer {
        root.requireFields(commonSuccessFields + "transfer", "文件描述响应格式无效")
        val transfer = root.requiredObject("transfer", "文件描述格式无效")
        transfer.requireFields(
            setOf("id", "name", "mime", "size", "sha256", "chunkSize"),
            "文件描述字段无效",
        )
        return QuickerToolboxResult.Transfer(
            QuickerTransferDescriptor(
                id = canonicalUuid(transfer.requiredString("id", "文件传输标识无效")),
                name = validateFileName(transfer.requiredString("name", "文件名格式无效")),
                mime = validateMime(transfer.requiredString("mime", "文件类型格式无效")),
                size = transfer.requiredLong("size", "文件大小格式无效")
                    .requireRange(0, MAX_FILE_BYTES, "文件大小无效"),
                sha256 = validateSha256(transfer.requiredString("sha256", "文件校验值格式无效")),
                chunkSize = transfer.requiredInt("chunkSize", "文件分块大小格式无效")
                    .also { require(it == CHUNK_BYTES) { "文件分块大小不受支持" } },
            ),
        )
    }

    private fun parseDownloadChunk(root: JsonObject): QuickerToolboxResult.DownloadChunk {
        root.requireFields(
            commonSuccessFields + setOf("transferId", "offset", "data", "sha256", "eof"),
            "文件分块响应格式无效",
        )
        val encoded = root.requiredString("data", "文件分块数据格式无效", allowEmpty = true)
        require(encoded.length <= 87_384 && encoded.length % 4 == 0 && encoded.none(Char::isWhitespace)) {
            "文件分块数据格式无效"
        }
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }
            .getOrElse { throw IllegalArgumentException("文件分块不是有效 Base64") }
        require(Base64.getEncoder().encodeToString(bytes) == encoded) { "文件分块 Base64 不是规范格式" }
        require(bytes.size <= CHUNK_BYTES) { "文件分块过大" }
        val expectedHash = validateSha256(root.requiredString("sha256", "文件分块校验值无效"))
        require(MessageDigest.isEqual(expectedHash.toByteArray(), sha256(bytes).toByteArray())) {
            "文件分块校验失败"
        }
        return QuickerToolboxResult.DownloadChunk(
            transferId = canonicalUuid(root.requiredString("transferId", "文件传输标识无效")),
            offset = root.requiredLong("offset", "文件分块位置格式无效")
                .requireRange(0, MAX_FILE_BYTES, "文件分块位置无效"),
            bytes = bytes,
            eof = root.requiredBoolean("eof", "文件结束标识格式无效"),
        )
    }

    private fun parseUploadStarted(root: JsonObject): QuickerToolboxResult.UploadStarted {
        root.requireFields(
            commonSuccessFields + setOf("transferId", "nextOffset", "chunkSize"),
            "文件上传响应格式无效",
        )
        return QuickerToolboxResult.UploadStarted(
            transferId = canonicalUuid(root.requiredString("transferId", "文件传输标识无效")),
            nextOffset = root.requiredLong("nextOffset", "文件上传位置格式无效")
                .requireRange(0, MAX_FILE_BYTES, "文件上传位置无效"),
            chunkSize = root.requiredInt("chunkSize", "文件分块大小格式无效")
                .also { require(it == CHUNK_BYTES) { "文件分块大小不受支持" } },
        )
    }

    private fun parseUploadAdvanced(root: JsonObject): QuickerToolboxResult.UploadAdvanced {
        root.requireFields(
            commonSuccessFields + setOf("transferId", "nextOffset"),
            "文件上传进度响应格式无效",
        )
        return QuickerToolboxResult.UploadAdvanced(
            transferId = canonicalUuid(root.requiredString("transferId", "文件传输标识无效")),
            nextOffset = root.requiredLong("nextOffset", "文件上传位置格式无效")
                .requireRange(0, MAX_FILE_BYTES, "文件上传位置无效"),
        )
    }

    private fun parseUploadSaved(root: JsonObject): QuickerToolboxResult.UploadSaved {
        root.requireFields(
            commonSuccessFields + setOf("savedName", "location"),
            "文件保存响应格式无效",
        )
        val location = root.requiredString("location", "文件保存位置格式无效")
        require(location.length <= MAX_LOCATION_LENGTH && location.none(Char::isISOControl)) {
            "文件保存位置格式无效"
        }
        return QuickerToolboxResult.UploadSaved(
            savedName = validateFileName(root.requiredString("savedName", "已保存文件名格式无效")),
            location = location,
        )
    }

    private fun command(operation: String, vararg values: Pair<String, Any>): String {
        require(operation in supportedOperations) { "工具箱操作无效" }
        val root = JsonObject().apply {
            addProperty("op", operation)
            values.forEach { (name, value) ->
                when (value) {
                    is String -> addProperty(name, value)
                    is Int -> addProperty(name, value)
                    is Long -> addProperty(name, value)
                    else -> error("Unsupported toolbox value")
                }
            }
        }
        val command = COMMAND_PREFIX + root.toString()
        require(command.length <= MAX_PAYLOAD_LENGTH) { "工具箱请求过大" }
        return command
    }

    private fun decodeRoot(data: JsonElement?): JsonObject {
        require(data != null && !data.isJsonNull) { "Quicker 未返回工具箱结果" }
        val decoded = if (data.isJsonPrimitive && data.asJsonPrimitive.isString) {
            val payload = data.asString
            require(payload.length in 1..MAX_PAYLOAD_LENGTH) { "工具箱响应长度无效" }
            runCatching { StrictJsonParser.parse(payload) }
                .getOrElse { throw IllegalArgumentException("工具箱响应不是有效 JSON") }
        } else {
            val payload = data.toString()
            require(payload.length in 1..MAX_PAYLOAD_LENGTH) { "工具箱响应长度无效" }
            data
        }
        require(decoded.isJsonObject) { "工具箱响应不是 JSON 对象" }
        return decoded.asJsonObject
    }

    private fun validateFileName(value: String): String {
        require(value.isNotBlank() && value.length <= MAX_FILE_NAME_LENGTH) { "文件名格式无效" }
        require(value.none(Char::isISOControl) && '/' !in value && '\\' !in value) { "文件名格式无效" }
        require(value == value.trim() && !value.endsWith('.')) { "文件名格式无效" }
        return value
    }

    private fun validateMime(value: String): String {
        require(value.length <= MAX_MIME_LENGTH && mimePattern.matches(value)) { "文件类型格式无效" }
        require('"' !in value && '\\' !in value && value == value.lowercase()) { "文件类型格式无效" }
        return value
    }

    private fun validateSha256(value: String): String {
        require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
            "SHA-256 格式无效"
        }
        return value
    }

    private fun canonicalUuid(value: String): String {
        val parsed = runCatching { UUID.fromString(value) }
            .getOrElse { throw IllegalArgumentException("文件传输标识无效") }
        val canonical = parsed.toString()
        require(value == canonical) { "文件传输标识无效" }
        return canonical
    }

    private fun Long.requireRange(minimum: Long, maximum: Long, message: String): Long {
        require(this in minimum..maximum) { message }
        return this
    }

    private fun JsonObject.requireFields(expected: Set<String>, message: String) {
        require(keySet() == expected) { message }
    }

    private fun JsonObject.requiredString(
        name: String,
        message: String,
        allowEmpty: Boolean = false,
    ): String {
        val element = get(name)?.takeUnless(JsonElement::isJsonNull)
        require(element != null && element.isJsonPrimitive && element.asJsonPrimitive.isString) { message }
        return element.asString.also { require(allowEmpty || it.isNotEmpty()) { message } }
    }

    private fun JsonObject.requiredBoolean(name: String, message: String): Boolean {
        val element = get(name)?.takeUnless(JsonElement::isJsonNull)
        require(element != null && element.isJsonPrimitive && element.asJsonPrimitive.isBoolean) { message }
        return element.asBoolean
    }

    private fun JsonObject.requiredInt(name: String, message: String): Int {
        val element = get(name)?.takeUnless(JsonElement::isJsonNull)
        require(element != null && element.isJsonPrimitive && element.asJsonPrimitive.isNumber) { message }
        val raw = element.asString
        require(integerPattern.matches(raw)) { message }
        return raw.toIntOrNull() ?: throw IllegalArgumentException(message)
    }

    private fun JsonObject.requiredLong(name: String, message: String): Long {
        val element = get(name)?.takeUnless(JsonElement::isJsonNull)
        require(element != null && element.isJsonPrimitive && element.asJsonPrimitive.isNumber) { message }
        val raw = element.asString
        require(integerPattern.matches(raw)) { message }
        return raw.toLongOrNull() ?: throw IllegalArgumentException(message)
    }

    private fun JsonObject.requiredObject(name: String, message: String): JsonObject {
        val element = get(name)?.takeUnless(JsonElement::isJsonNull)
        require(element != null && element.isJsonObject) { message }
        return element.asJsonObject
    }

    private val supportedOperations = setOf(
        OP_CLIPBOARD_READ,
        OP_SCREEN_CAPTURE,
        OP_DOWNLOAD_PICK,
        OP_DOWNLOAD_CHUNK,
        OP_DOWNLOAD_FINISH,
        OP_UPLOAD_BEGIN,
        OP_UPLOAD_CHUNK,
        OP_UPLOAD_FINISH,
        OP_TRANSFER_CANCEL,
    )
    private val integerPattern = Regex("0|-?[1-9][0-9]*")
}
