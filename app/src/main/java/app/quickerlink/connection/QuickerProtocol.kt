package app.quickerlink.connection

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

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
        val root = JsonParser.parseString(text)
        require(root.isJsonObject) { "消息不是 JSON 对象" }
        val json = root.asJsonObject
        val messageType = json.find("messageType")?.asIntOrNull()
            ?: throw IllegalArgumentException("消息缺少 messageType")

        return QuickerMessage(
            messageType = messageType,
            serial = json.find("serial")?.asLongOrNull(),
            replyTo = json.find("replyTo")?.asLongOrNull(),
            operation = json.find("operation")?.asStringOrNull(),
            action = json.find("action")?.asStringOrNull(),
            isSuccess = json.find("isSuccess")?.asBooleanOrNull(),
            message = json.find("message")?.asStringOrNull(),
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

    private fun JsonObject.find(name: String): JsonElement? = entrySet()
        .firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
        ?.value

    private fun JsonElement.unlessNull(): JsonElement? = takeUnless { it is JsonNull || it.isJsonNull }

    private fun JsonElement.asStringOrNull(): String? = runCatching { asString }.getOrNull()

    private fun JsonElement.asIntOrNull(): Int? = runCatching { asInt }.getOrNull()

    private fun JsonElement.asLongOrNull(): Long? = runCatching { asLong }.getOrNull()

    private fun JsonElement.asBooleanOrNull(): Boolean? = runCatching { asBoolean }.getOrNull()
}
