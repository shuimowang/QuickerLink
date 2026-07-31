package app.quickerlink.connection

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

internal object StrictJsonParser {
    fun parse(payload: String): JsonElement {
        try {
            JsonReader(StringReader(payload)).use { reader ->
                reader.strictness = Strictness.STRICT
                scanValue(reader, depth = 0)
                require(reader.peek() == JsonToken.END_DOCUMENT) { "JSON 包含多余内容" }
            }
        } catch (error: Exception) {
            if (error is IllegalArgumentException && error.message?.startsWith("JSON ") == true) {
                throw error
            }
            throw IllegalArgumentException("JSON 格式无效", error)
        }
        return JsonParser.parseString(payload)
    }

    private fun scanValue(reader: JsonReader, depth: Int) {
        require(depth <= MAX_DEPTH) { "JSON 嵌套过深" }
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                val names = mutableSetOf<String>()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(names.add(name)) { "JSON 包含重复字段：$name" }
                    scanValue(reader, depth + 1)
                }
                reader.endObject()
            }

            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                while (reader.hasNext()) scanValue(reader, depth + 1)
                reader.endArray()
            }

            JsonToken.STRING,
            JsonToken.NUMBER,
            -> reader.nextString()

            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> reader.nextNull()
            else -> throw IllegalArgumentException("JSON 值格式无效")
        }
    }

    private const val MAX_DEPTH = 32
}
