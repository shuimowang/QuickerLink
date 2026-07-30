package app.quickerlink.connection

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction

data class QuickerPairingConfig(
    val ipAddress: String,
    val port: Int,
    val password: String,
)

object QuickerPairingCode {
    private const val SCHEME = "quickerlink"
    private const val HOST = "pair"
    private const val VERSION = "1"
    private const val CLOUD_PUSH_HOST = "tools.getquicker.cn"
    private const val CLOUD_PUSH_PATH = "/static/pushtool.html"
    private const val MAX_PAYLOAD_LENGTH = 4_096
    private const val MAX_PASSWORD_LENGTH = 256
    private val allowedKeys = setOf("v", "ip", "port", "code")

    fun parse(payload: String): QuickerPairingConfig {
        val trimmed = payload.trim()
        require(trimmed.length in 1..MAX_PAYLOAD_LENGTH) { "配对码长度无效" }
        val uri = runCatching { URI(trimmed) }
            .getOrElse { throw IllegalArgumentException("配对码格式无效") }
        if (
            (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) &&
            uri.host?.equals(CLOUD_PUSH_HOST, ignoreCase = true) == true &&
            uri.path.equals(CLOUD_PUSH_PATH, ignoreCase = true)
        ) {
            throw IllegalArgumentException("云推送二维码不能用于局域网 WSS 配对")
        }
        require(
            !uri.isOpaque &&
                uri.scheme.equals(SCHEME, ignoreCase = true) &&
                uri.host?.equals(HOST, ignoreCase = true) == true,
        ) {
            "这不是 Quicker Link 配对码"
        }
        require(
            uri.userInfo == null &&
                uri.port == -1 &&
                uri.rawPath.isNullOrEmpty() &&
                uri.fragment == null,
        ) {
            "配对码包含不支持的内容"
        }

        val parameters = parseQuery(uri.rawQuery.orEmpty())
        require(parameters.keys.all(allowedKeys::contains)) { "配对码包含未知字段" }
        require(parameters.keys.containsAll(allowedKeys)) { "配对码缺少必要字段" }
        require(parameters["v"] == VERSION) { "配对码版本不受支持" }

        val ipAddress = QuickerEndpoint.normalizeIpv4(parameters["ip"].orEmpty())
        require(QuickerEndpoint.isPrivateIpv4(ipAddress)) { "配对码中的地址不是局域网 IPv4" }
        val portText = parameters["port"].orEmpty()
        require(portText.isNotEmpty() && portText.all { it in '0'..'9' }) { "配对码中的端口无效" }
        val port = portText.toIntOrNull()
        require(port != null && port in 1..65535) { "配对码中的端口无效" }
        val password = parameters["code"].orEmpty()
        require(password.length <= MAX_PASSWORD_LENGTH && password.none(Char::isISOControl)) {
            "配对码中的验证码无效"
        }
        return QuickerPairingConfig(ipAddress, port, password)
    }

    fun encode(config: QuickerPairingConfig): String {
        val ipAddress = QuickerEndpoint.normalizeIpv4(config.ipAddress)
        require(QuickerEndpoint.isPrivateIpv4(ipAddress)) { "配对地址必须是局域网 IPv4" }
        require(config.port in 1..65535) { "端口必须在 1 到 65535 之间" }
        require(config.password.length <= MAX_PASSWORD_LENGTH && config.password.none(Char::isISOControl)) {
            "验证码无效"
        }
        return "$SCHEME://$HOST?v=$VERSION&ip=${encodeValue(ipAddress)}&port=${config.port}" +
            "&code=${encodeValue(config.password)}"
    }

    private fun parseQuery(rawQuery: String): Map<String, String> {
        require(rawQuery.isNotBlank()) { "配对码缺少连接参数" }
        val result = linkedMapOf<String, String>()
        rawQuery.split('&').forEach { pair ->
            val separator = pair.indexOf('=')
            require(separator > 0) { "配对码参数格式无效" }
            val key = decodeValue(pair.substring(0, separator))
            val value = decodeValue(pair.substring(separator + 1))
            require(result.put(key, value) == null) { "配对码包含重复字段" }
        }
        return result
    }

    private fun decodeValue(value: String): String {
        try {
            val bytes = ByteArrayOutputStream(value.length)
            var index = 0
            while (index < value.length) {
                val character = value[index]
                if (character == '%') {
                    require(index + 2 < value.length)
                    val high = Character.digit(value[index + 1], 16)
                    val low = Character.digit(value[index + 2], 16)
                    require(high >= 0 && low >= 0)
                    bytes.write((high shl 4) or low)
                    index += 3
                } else {
                    require(character.code in 0x21..0x7e)
                    bytes.write(character.code)
                    index += 1
                }
            }
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("配对码参数编码无效")
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("配对码参数编码无效")
        }
    }

    private fun encodeValue(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
