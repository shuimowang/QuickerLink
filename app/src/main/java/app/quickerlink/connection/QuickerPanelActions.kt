package app.quickerlink.connection

import app.quickerlink.data.ActionParameterChoice
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.Base64
import java.util.UUID

data class QuickerPanelAction(
    val id: String,
    val title: String,
    val group: String?,
    val order: Int,
    val icon: String? = null,
    val parameterChoices: List<ActionParameterChoice> = emptyList(),
)

data class QuickerPanelScene(
    val scene: String,
    val groups: List<String>,
    val actions: List<QuickerPanelAction>,
)

data class QuickerPanelActionCatalog(
    val scenes: List<QuickerPanelScene>,
    val capabilities: QuickerLinkCapabilities = QuickerLinkCapabilities(),
) {
    val actions: List<QuickerPanelAction>
        get() = scenes.flatMap(QuickerPanelScene::actions)
}

data class QuickerLinkCapabilities(
    val stopAction: Boolean = true,
    val screenCapture: Boolean = true,
    val clipboardRead: Boolean = true,
    val maxFileBytes: Long = QuickerToolboxProtocol.MAX_FILE_BYTES,
    val chunkBytes: Int = QuickerToolboxProtocol.CHUNK_BYTES,
)

internal class UnsupportedPanelCatalogVersionException :
    IllegalArgumentException("动作目录版本不受支持")

object QuickerPanelActionsProtocol {
    const val COMPANION_SHARED_ACTION_ID = "b02b2732-f087-4e45-416d-08deee3e76ba"
    const val LIST_COMMAND = "quickerlink:list-panel-actions:v6"
    const val GLOBAL_SCENE = "_global"
    const val COMMON_SCENE = "common"

    private const val PROTOCOL = "quickerlink.panel-actions"
    private const val VERSION = 6
    private const val MAX_PAYLOAD_LENGTH = 262_144
    private const val MAX_GROUPS_PER_SCENE = 500
    private const val MAX_ACTIONS = 500
    private const val MAX_GROUP_LENGTH = 80
    private const val MAX_TITLE_LENGTH = 160
    private const val MAX_ICON_LENGTH = 22_000
    private const val MAX_ICON_BYTES = 16_384
    private const val MAX_PARAMETER_CHOICES_PER_ACTION = 50
    private const val MAX_PARAMETER_CHOICE_LABEL_LENGTH = 120
    private const val MAX_PARAMETER_CHOICE_VALUE_LENGTH = 2_048
    private const val MAX_ERROR_LENGTH = 200
    private const val MAX_ERROR_CODE_LENGTH = 64
    private val expectedScenes = listOf(GLOBAL_SCENE, COMMON_SCENE)
    private val successFields = setOf("protocol", "version", "ok", "capabilities", "scenes")
    private val errorFields = setOf("protocol", "version", "ok", "code", "error")
    private val capabilityFields = setOf("stopAction", "screenCapture", "clipboardRead", "fileTransfer")
    private val fileTransferCapabilityFields = setOf("maxBytes", "chunkBytes")
    private val sceneFields = setOf("scene", "groups", "actions")
    private val actionFields = setOf("id", "title", "group", "order", "icon", "parameterChoices")
    private val parameterChoiceFields = setOf("label", "value")
    private val errorCodePattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    fun parse(data: JsonElement?): QuickerPanelActionCatalog {
        val root = decodeRoot(data)
        require(root.string("protocol") == PROTOCOL) { "动作目录协议无效" }
        if (root.int("version") != VERSION) {
            throw UnsupportedPanelCatalogVersionException()
        }
        if (!root.boolean("ok")) {
            root.requireFields(errorFields, "动作目录错误响应格式无效")
            val code = root.string("code")
            require(code.length <= MAX_ERROR_CODE_LENGTH && errorCodePattern.matches(code)) {
                "动作目录错误代码无效"
            }
            val error = validateText(root.string("error"), MAX_ERROR_LENGTH, "动作目录错误消息")
            throw IllegalArgumentException("[$code] $error")
        }

        root.requireFields(successFields, "动作目录响应格式无效")
        val capabilities = root.obj("capabilities", "动作目录缺少能力声明", "动作能力格式无效")
        capabilities.requireFields(capabilityFields, "动作能力字段无效")
        require(capabilities.boolean("stopAction")) { "当前 Quicker Link 动作不支持终止动作" }
        require(capabilities.boolean("screenCapture")) { "当前 Quicker Link 动作不支持屏幕快照" }
        require(capabilities.boolean("clipboardRead")) { "当前 Quicker Link 动作不支持读取剪贴板" }
        val fileTransfer = capabilities.obj("fileTransfer", "动作目录缺少文件传输能力", "文件传输能力格式无效")
        fileTransfer.requireFields(fileTransferCapabilityFields, "文件传输能力字段无效")
        val maxFileBytes = fileTransfer.long("maxBytes")
        val chunkBytes = fileTransfer.int("chunkBytes")
        require(maxFileBytes == QuickerToolboxProtocol.MAX_FILE_BYTES) { "文件传输大小上限不受支持" }
        require(chunkBytes == QuickerToolboxProtocol.CHUNK_BYTES) { "文件分块大小不受支持" }
        val scenesJson = root.array("scenes", "动作目录缺少场景", "动作场景格式无效")
        require(scenesJson.size() == expectedScenes.size) { "动作场景数量无效" }

        val seenIds = hashSetOf<String>()
        val scenes = scenesJson.mapIndexed { index, element ->
            require(element.isJsonObject) { "动作场景格式无效" }
            parseScene(element.asJsonObject, expectedScenes[index], seenIds)
        }
        require(scenes.sumOf { it.actions.size } <= MAX_ACTIONS) { "动作数量过多" }
        return QuickerPanelActionCatalog(
            scenes = scenes,
            capabilities = QuickerLinkCapabilities(
                maxFileBytes = maxFileBytes,
                chunkBytes = chunkBytes,
            ),
        )
    }

    private fun parseScene(
        root: JsonObject,
        expectedScene: String,
        seenIds: MutableSet<String>,
    ): QuickerPanelScene {
        root.requireFields(sceneFields, "动作场景字段无效")
        val scene = root.string("scene")
        require(scene == expectedScene) { "动作目录包含不受支持的场景" }

        val groupsJson = root.array("groups", "动作目录缺少分组", "动作分组格式无效")
        require(groupsJson.size() <= MAX_GROUPS_PER_SCENE) { "动作分组数量无效" }
        val groups = groupsJson.map { element ->
            require(element.isJsonPrimitive && element.asJsonPrimitive.isString) { "动作分组格式无效" }
            validateText(element.asString, MAX_GROUP_LENGTH, "动作分组")
        }
        require(groups.distinct().size == groups.size) { "动作目录包含重复分组" }

        val actionsJson = root.array("actions", "动作目录缺少动作", "动作条目格式无效")
        require(actionsJson.size() <= MAX_ACTIONS) { "动作数量过多" }
        val seenOrders = hashSetOf<Int>()
        var previousOrder = -1
        val actions = actionsJson.map { element ->
            require(element.isJsonObject) { "动作条目格式无效" }
            val item = element.asJsonObject
            item.requireFields(actionFields, "动作条目字段无效")
            val id = canonicalUuid(item.string("id"))
            require(seenIds.add(id)) { "动作目录包含重复动作" }
            val title = validateText(item.string("title"), MAX_TITLE_LENGTH, "动作名称")
            val group = item.nullableString("group")
                ?.let { validateText(it, MAX_GROUP_LENGTH, "动作分组") }
            require(group == null || group in groups) { "动作引用了未知分组" }
            val order = item.int("order")
            require(order >= 0) { "动作顺序无效" }
            require(seenOrders.add(order)) { "动作目录包含重复顺序" }
            require(order > previousOrder) { "动作顺序无效" }
            previousOrder = order
            val icon = validateIcon(item.nullableString("icon"))
            val parameterChoices = parseParameterChoices(item)
            QuickerPanelAction(
                id = id,
                title = title,
                group = group,
                order = order,
                icon = icon,
                parameterChoices = parameterChoices,
            )
        }
        return QuickerPanelScene(scene = scene, groups = groups, actions = actions)
    }

    private fun parseParameterChoices(item: JsonObject): List<ActionParameterChoice> {
        val choices = item.array(
            "parameterChoices",
            "动作目录缺少快捷参数",
            "快捷参数格式无效",
        )
        require(choices.size() <= MAX_PARAMETER_CHOICES_PER_ACTION) { "快捷参数数量过多" }
        return choices.map { element ->
            require(element.isJsonObject) { "快捷参数格式无效" }
            val choice = element.asJsonObject
            choice.requireFields(parameterChoiceFields, "快捷参数字段无效")
            ActionParameterChoice(
                label = validateText(
                    choice.string("label"),
                    MAX_PARAMETER_CHOICE_LABEL_LENGTH,
                    "快捷参数名称",
                ),
                value = validateText(
                    choice.string("value"),
                    MAX_PARAMETER_CHOICE_VALUE_LENGTH,
                    "快捷参数值",
                ),
            )
        }
    }

    private fun decodeRoot(data: JsonElement?): JsonObject {
        require(data != null && !data.isJsonNull) { "Quicker 未返回动作目录" }
        val root = if (data.isJsonPrimitive && data.asJsonPrimitive.isString) {
            val payload = data.asString
            require(payload.length in 1..MAX_PAYLOAD_LENGTH) { "动作目录长度无效" }
            runCatching { StrictJsonParser.parse(payload) }
                .getOrElse { throw IllegalArgumentException("动作目录不是有效 JSON") }
        } else {
            val payload = data.toString()
            require(payload.length in 1..MAX_PAYLOAD_LENGTH) { "动作目录长度无效" }
            data
        }
        require(root.isJsonObject) { "动作目录不是 JSON 对象" }
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

    private fun validateText(value: String, maxLength: Int, field: String): String {
        require(value.isNotBlank() && value.length <= maxLength && value.none(Char::isISOControl)) {
            "$field 无效"
        }
        return value
    }

    private fun validateIcon(value: String?): String? {
        if (value == null) return null
        require(value.length in 1..MAX_ICON_LENGTH && value.none(Char::isISOControl)) {
            "动作图标无效"
        }
        if (value.startsWith(PNG_DATA_PREFIX)) {
            val bytes = runCatching { Base64.getDecoder().decode(value.removePrefix(PNG_DATA_PREFIX)) }
                .getOrElse { throw IllegalArgumentException("动作图标无效") }
            require(bytes.size in MIN_PNG_BYTES..MAX_ICON_BYTES && bytes.startsWith(PNG_SIGNATURE)) {
                "动作图标无效"
            }
            validatePngHeader(bytes)
            return value
        }

        return QuickerIconPolicy.normalizeUrl(value)
            ?: throw IllegalArgumentException("动作图标地址无效")
    }

    private fun validatePngHeader(bytes: ByteArray) {
        require(bytes.readUInt32(8) == PNG_IHDR_DATA_LENGTH && bytes.matchesAt(PNG_IHDR, 12)) {
            "动作图标无效"
        }
        val width = bytes.readUInt32(16)
        val height = bytes.readUInt32(20)
        require(
            width in 1..MAX_ICON_DIMENSION &&
                height in 1..MAX_ICON_DIMENSION &&
                width * height <= MAX_ICON_PIXELS,
        ) { "动作图标尺寸无效" }
    }

    private fun JsonObject.requireFields(expected: Set<String>, message: String) {
        require(keySet() == expected) { message }
    }

    private fun JsonObject.string(name: String): String = optionalString(name)
        ?: throw IllegalArgumentException("动作目录缺少 $name")

    private fun JsonObject.optionalString(name: String): String? = get(name)
        ?.takeUnless(JsonElement::isJsonNull)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString

    private fun JsonObject.nullableString(name: String): String? {
        val value = get(name) ?: throw IllegalArgumentException("动作目录缺少 $name")
        if (value.isJsonNull) return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            "动作目录中的 $name 格式无效"
        }
        return value.asString
    }

    private fun JsonObject.boolean(name: String): Boolean {
        val value = get(name)?.takeUnless(JsonElement::isJsonNull)
            ?: throw IllegalArgumentException("动作目录缺少 $name")
        require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) {
            "动作目录中的 $name 格式无效"
        }
        return value.asBoolean
    }

    private fun JsonObject.int(name: String): Int {
        val value = get(name)?.takeUnless(JsonElement::isJsonNull)
            ?: throw IllegalArgumentException("动作目录缺少 $name")
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
            "动作目录中的 $name 格式无效"
        }
        return value.asString.toIntOrNull()
            ?: throw IllegalArgumentException("动作目录中的 $name 格式无效")
    }

    private fun JsonObject.long(name: String): Long {
        val value = get(name)?.takeUnless(JsonElement::isJsonNull)
            ?: throw IllegalArgumentException("动作目录缺少 $name")
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
            "动作目录中的 $name 格式无效"
        }
        return value.asString.toLongOrNull()
            ?: throw IllegalArgumentException("动作目录中的 $name 格式无效")
    }

    private fun JsonObject.array(
        name: String,
        missingMessage: String,
        invalidMessage: String,
    ) = get(name)
        ?.takeUnless(JsonElement::isJsonNull)
        ?.takeIf(JsonElement::isJsonArray)
        ?.asJsonArray
        ?: if (has(name)) {
            throw IllegalArgumentException(invalidMessage)
        } else {
            throw IllegalArgumentException(missingMessage)
        }

    private fun JsonObject.obj(
        name: String,
        missingMessage: String,
        invalidMessage: String,
    ): JsonObject = get(name)
        ?.takeUnless(JsonElement::isJsonNull)
        ?.takeIf(JsonElement::isJsonObject)
        ?.asJsonObject
        ?: if (has(name)) {
            throw IllegalArgumentException(invalidMessage)
        } else {
            throw IllegalArgumentException(missingMessage)
        }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

    private fun ByteArray.matchesAt(value: ByteArray, offset: Int): Boolean =
        size >= offset + value.size && value.indices.all { index -> this[offset + index] == value[index] }

    private fun ByteArray.readUInt32(offset: Int): Long =
        ((this[offset].toLong() and 0xff) shl 24) or
            ((this[offset + 1].toLong() and 0xff) shl 16) or
            ((this[offset + 2].toLong() and 0xff) shl 8) or
            (this[offset + 3].toLong() and 0xff)

    private const val PNG_DATA_PREFIX = "data:image/png;base64,"
    private const val MIN_PNG_BYTES = 33
    private const val PNG_IHDR_DATA_LENGTH = 13L
    private const val MAX_ICON_DIMENSION = 512L
    private const val MAX_ICON_PIXELS = 262_144L
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4e,
        0x47,
        0x0d,
        0x0a,
        0x1a,
        0x0a,
    )
    private val PNG_IHDR = byteArrayOf(0x49, 0x48, 0x44, 0x52)
}
