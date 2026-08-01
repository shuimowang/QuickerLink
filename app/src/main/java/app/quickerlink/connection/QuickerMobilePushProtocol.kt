package app.quickerlink.connection

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.UUID

sealed interface QuickerMobilePush {
    data class Text(val text: String) : QuickerMobilePush
    data class Notification(val title: String, val body: String) : QuickerMobilePush
    data class FileOffer(val descriptor: QuickerTransferDescriptor) : QuickerMobilePush
}

object QuickerMobilePushProtocol {
    const val OPERATION = "quickerlink.push"

    private const val PROTOCOL = "quickerlink.mobile-push"
    private const val VERSION = 8
    private const val MAX_TEXT_CHARS = 16_000
    private const val MAX_TEXT_BYTES = 48 * 1024
    private const val MAX_TITLE_CHARS = 80
    private const val MAX_BODY_CHARS = 1_000
    private const val MAX_FILE_NAME_CHARS = 120
    private const val MAX_MIME_CHARS = 127
    private val commonFields = setOf("protocol", "version", "op")
    private val transferFields = setOf("id", "name", "mime", "size", "sha256", "chunkSize")
    private val sha256Pattern = Regex("[0-9a-f]{64}")
    private val mimePattern = Regex("[!-~]{1,$MAX_MIME_CHARS}")

    fun parse(data: JsonElement?): QuickerMobilePush {
        require(data != null && data.isJsonObject) { "电脑推送格式无效" }
        val root = data.asJsonObject
        require(root.string("protocol") == PROTOCOL) { "电脑推送协议无效" }
        require(root.int("version") == VERSION) { "电脑推送版本不受支持" }
        return when (val operation = root.string("op")) {
            "text.enqueue" -> {
                root.requireFields(commonFields + "text")
                QuickerMobilePush.Text(validateText(root.string("text"), MAX_TEXT_CHARS, MAX_TEXT_BYTES))
            }

            "notify.enqueue" -> {
                root.requireFields(commonFields + setOf("title", "body"))
                QuickerMobilePush.Notification(
                    title = validateText(root.string("title"), MAX_TITLE_CHARS, MAX_TEXT_BYTES),
                    body = validateText(root.string("body"), MAX_BODY_CHARS, MAX_TEXT_BYTES),
                )
            }

            "file.enqueue" -> {
                root.requireFields(commonFields + "transfer")
                QuickerMobilePush.FileOffer(parseTransfer(root.obj("transfer")))
            }

            else -> throw IllegalArgumentException("不支持的电脑推送操作：$operation")
        }
    }

    private fun parseTransfer(root: JsonObject): QuickerTransferDescriptor {
        root.requireFields(transferFields)
        val id = canonicalUuid(root.string("id"))
        val name = root.string("name")
        require(
            name.length in 1..MAX_FILE_NAME_CHARS &&
                name != "." && name != ".." &&
                name.none { it.isISOControl() || it == '/' || it == '\\' || it == ':' },
        ) { "电脑推送文件名无效" }
        val mime = root.string("mime")
        require(mimePattern.matches(mime)) { "电脑推送文件类型无效" }
        val size = root.long("size")
        require(size in 0..QuickerToolboxProtocol.MAX_FILE_BYTES) { "电脑推送文件大小无效" }
        val sha256 = root.string("sha256")
        require(sha256Pattern.matches(sha256)) { "电脑推送文件校验值无效" }
        val chunkSize = root.int("chunkSize")
        require(chunkSize == QuickerToolboxProtocol.CHUNK_BYTES) { "电脑推送文件分块无效" }
        return QuickerTransferDescriptor(id, name, mime, size, sha256, chunkSize)
    }

    private fun validateText(value: String, maxChars: Int, maxBytes: Int): String {
        require(
            value.isNotBlank() && value.length <= maxChars &&
                value.toByteArray(Charsets.UTF_8).size <= maxBytes &&
                value.none { it.isISOControl() && it != '\r' && it != '\n' && it != '\t' },
        ) { "电脑推送文本无效" }
        return value
    }

    private fun canonicalUuid(value: String): String {
        val parsed = runCatching { UUID.fromString(value) }
            .getOrElse { throw IllegalArgumentException("电脑推送文件标识无效") }
        val canonical = parsed.toString()
        require(value == canonical) { "电脑推送文件标识无效" }
        return canonical
    }

    private fun JsonObject.requireFields(expected: Set<String>) {
        require(keySet() == expected) { "电脑推送字段无效" }
    }

    private fun JsonObject.string(name: String): String {
        val value = get(name)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            "电脑推送中的 $name 格式无效"
        }
        return value.asString
    }

    private fun JsonObject.int(name: String): Int {
        val value = get(name)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
            "电脑推送中的 $name 格式无效"
        }
        return value.asString.toIntOrNull()
            ?: throw IllegalArgumentException("电脑推送中的 $name 格式无效")
    }

    private fun JsonObject.long(name: String): Long {
        val value = get(name)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
            "电脑推送中的 $name 格式无效"
        }
        return value.asString.toLongOrNull()
            ?: throw IllegalArgumentException("电脑推送中的 $name 格式无效")
    }

    private fun JsonObject.obj(name: String): JsonObject {
        val value = get(name)
        require(value != null && value.isJsonObject) { "电脑推送中的 $name 格式无效" }
        return value.asJsonObject
    }
}
