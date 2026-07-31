package app.quickerlink.connection

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject

data class QuickerMessage(
    val messageType: Int,
    val serial: Long? = null,
    val replyTo: Long? = null,
    val operation: String? = null,
    val action: String? = null,
    val isSuccess: Boolean? = null,
    val message: String? = null,
    val data: JsonElement? = null,
    val extData: JsonElement? = null,
    val raw: String,
)

object QuickerProtocol {
    const val MESSAGE_COMMAND = 2
    const val MESSAGE_RESPONSE = 4
    const val MESSAGE_AUTH_REQUEST = 5
    const val MESSAGE_AUTH_RESPONSE = 6

    private val gson = Gson()

    fun authRequest(serial: Long, password: String): String = encode(
        JsonObject().apply {
            addProperty("messageType", MESSAGE_AUTH_REQUEST)
            addProperty("serial", serial)
            addProperty("data", password)
        },
    )

    fun commandRequest(
        serial: Long,
        operation: String,
        data: String? = null,
        action: String? = null,
        wait: Boolean = true,
    ): String = encode(
        JsonObject().apply {
            addProperty("messageType", MESSAGE_COMMAND)
            addProperty("serial", serial)
            addProperty("operation", operation)
            data?.let { addProperty("data", it) }
            action?.takeIf(String::isNotBlank)?.let { addProperty("action", it) }
            addProperty("wait", wait)
        },
    )

    fun commandResponse(
        serial: Long,
        replyTo: Long,
        isSuccess: Boolean,
        message: String,
        data: String? = null,
    ): String = encode(
        JsonObject().apply {
            addProperty("messageType", MESSAGE_RESPONSE)
            addProperty("serial", serial)
            addProperty("replyTo", replyTo)
            addProperty("isSuccess", isSuccess)
            addProperty("message", message)
            data?.let { addProperty("data", it) }
        },
    )

    fun parse(text: String): QuickerMessage {
        val root = StrictJsonParser.parse(text)
        require(root.isJsonObject) { "消息不是 JSON 对象" }
        val json = root.asJsonObject
        val messageType = json.requiredInt("messageType")

        return QuickerMessage(
            messageType = messageType,
            serial = json.optionalLong("serial"),
            replyTo = json.optionalLong("replyTo"),
            operation = json.optionalString("operation"),
            action = json.optionalString("action"),
            isSuccess = json.optionalBoolean("isSuccess"),
            message = json.optionalString("message"),
            data = json.find("data")?.unlessNull(),
            extData = json.find("extData")?.unlessNull(),
            raw = text,
        )
    }

    fun displayData(element: JsonElement?): String? = when {
        element == null || element.isJsonNull -> null
        element.isJsonPrimitive && element.asJsonPrimitive.isString -> element.asString
        else -> gson.toJson(element)
    }

    private fun encode(json: JsonObject): String = gson.toJson(json)

    private fun JsonObject.find(name: String): JsonElement? {
        val matches = entrySet().filter { (key, _) -> key.equals(name, ignoreCase = true) }
        require(matches.size <= 1) { "消息包含重复字段 $name" }
        return matches.firstOrNull()?.value
    }

    private fun JsonElement.unlessNull(): JsonElement? = takeUnless { it is JsonNull || it.isJsonNull }

    private fun JsonObject.requiredInt(name: String): Int {
        val value = find(name) ?: throw IllegalArgumentException("消息缺少 $name")
        return value.canonicalInteger(name).toIntOrNull()
            ?: throw IllegalArgumentException("消息中的 $name 超出整数范围")
    }

    private fun JsonObject.optionalLong(name: String): Long? = find(name)?.let { value ->
        value.canonicalInteger(name).toLongOrNull()
            ?: throw IllegalArgumentException("消息中的 $name 超出整数范围")
    }

    private fun JsonObject.optionalString(name: String): String? = find(name)?.let { value ->
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            "消息中的 $name 必须是字符串"
        }
        value.asString
    }

    private fun JsonObject.optionalBoolean(name: String): Boolean? = find(name)?.let { value ->
        require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) {
            "消息中的 $name 必须是布尔值"
        }
        value.asBoolean
    }

    private fun JsonElement.canonicalInteger(name: String): String {
        require(isJsonPrimitive && asJsonPrimitive.isNumber) {
            "消息中的 $name 必须是整数"
        }
        val literal = asString
        require(CANONICAL_INTEGER.matches(literal)) {
            "消息中的 $name 必须是规范整数"
        }
        return literal
    }

    private val CANONICAL_INTEGER = Regex("(?:0|-?[1-9][0-9]*)")
}
