package app.quickerlink.connection

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.UUID

data class QuickerStoppedAction(
    val actionId: String,
)

internal class UnsupportedActionControlVersionException :
    IllegalArgumentException("动作控制协议版本不受支持")

object QuickerActionControlProtocol {
    private const val STOP_COMMAND_PREFIX = "quickerlink:stop-action:v6:"
    private const val PROTOCOL = "quickerlink.stop-action"
    private const val LEGACY_CATALOG_PROTOCOL = "quickerlink.panel-actions"
    private const val VERSION = 6
    private const val MAX_PAYLOAD_LENGTH = 1_024
    private const val MAX_ERROR_CODE_LENGTH = 64
    private val successFields = setOf("protocol", "version", "ok")
    private val errorFields = setOf("protocol", "version", "ok", "code")
    private val errorCodePattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val errorMessages = mapOf(
        "invalid_action_id" to "目标动作 ID 无效",
        "invalid_service_action_id" to "Quicker Link 服务状态无效",
        "self_stop_forbidden" to "不能终止 Quicker Link 自身",
        "service_unavailable" to "Quicker 动作服务暂不可用",
        "action_not_found" to "目标动作不存在，请刷新面板后重试",
        "stop_failed" to "Quicker 未能终止该动作",
    )

    fun stopCommand(actionId: String): String = STOP_COMMAND_PREFIX + canonicalUuid(actionId)

    fun parseStopResponse(
        data: JsonElement?,
        expectedActionId: String,
    ): QuickerStoppedAction {
        val expectedId = canonicalUuid(expectedActionId)
        val root = decodeRoot(data)
        val protocol = root.optionalString("protocol")
        require(protocol == PROTOCOL || protocol == LEGACY_CATALOG_PROTOCOL) {
            "终止动作协议无效"
        }
        val version = root.optionalInt("version")
            ?: throw IllegalArgumentException("终止动作协议版本无效")
        if (version != VERSION) {
            throw UnsupportedActionControlVersionException()
        }
        require(protocol == PROTOCOL) { "终止动作协议无效" }
        if (!root.boolean("ok")) {
            root.requireFields(errorFields, "终止动作错误响应格式无效")
            val code = root.string("code")
            require(
                code.length <= MAX_ERROR_CODE_LENGTH &&
                    errorCodePattern.matches(code) &&
                    code in errorMessages,
            ) {
                "终止动作错误代码无效"
            }
            throw IllegalArgumentException(requireNotNull(errorMessages[code]))
        }

        root.requireFields(successFields, "终止动作响应格式无效")
        return QuickerStoppedAction(expectedId)
    }

    private fun decodeRoot(data: JsonElement?): JsonObject {
        require(data != null && !data.isJsonNull) { "Quicker 未返回终止结果" }
        val root = if (data.isJsonPrimitive && data.asJsonPrimitive.isString) {
            val payload = data.asString
            require(payload.length in 1..MAX_PAYLOAD_LENGTH) { "终止动作响应长度无效" }
            runCatching { StrictJsonParser.parse(payload) }
                .getOrElse { throw IllegalArgumentException("终止动作响应不是有效 JSON") }
        } else {
            val payload = data.toString()
            require(payload.length in 1..MAX_PAYLOAD_LENGTH) { "终止动作响应长度无效" }
            data
        }
        require(root.isJsonObject) { "终止动作响应不是 JSON 对象" }
        return root.asJsonObject
    }

    private fun canonicalUuid(value: String): String {
        val parsed = runCatching { UUID.fromString(value) }
            .getOrElse { throw IllegalArgumentException("动作 ID 无效") }
        val canonical = parsed.toString()
        require(value.equals(canonical, ignoreCase = true) && value.length == canonical.length) {
            "动作 ID 无效"
        }
        return canonical
    }

    private fun JsonObject.requireFields(expected: Set<String>, message: String) {
        require(keySet() == expected) { message }
    }

    private fun JsonObject.string(name: String): String = optionalString(name)
        ?: throw IllegalArgumentException("终止动作响应缺少 $name")

    private fun JsonObject.optionalString(name: String): String? = get(name)
        ?.takeUnless(JsonElement::isJsonNull)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString

    private fun JsonObject.optionalInt(name: String): Int? = get(name)
        ?.takeUnless(JsonElement::isJsonNull)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.asString
        ?.toIntOrNull()

    private fun JsonObject.boolean(name: String): Boolean {
        val value = get(name)?.takeUnless(JsonElement::isJsonNull)
            ?: throw IllegalArgumentException("终止动作响应缺少 $name")
        require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) {
            "终止动作响应中的 $name 格式无效"
        }
        return value.asBoolean
    }
}
