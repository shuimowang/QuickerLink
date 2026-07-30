package app.quickerlink.connection

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

data class QuickerConnectionConfig(
    val ipAddress: String,
    val port: Int,
    val secure: Boolean,
    val password: String,
)

object QuickerEndpoint {
    private const val QUICKER_LAN_SUFFIX = ".lan.quicker.cc"

    fun url(config: QuickerConnectionConfig): String {
        val normalizedIp = normalizeIpv4(config.ipAddress)
        require(config.port in 1..65535) { "端口必须在 1 到 65535 之间" }

        return if (config.secure) {
            "wss://${normalizedIp.replace('.', '-')}$QUICKER_LAN_SUFFIX:${config.port}/ws"
        } else {
            "ws://$normalizedIp:${config.port}/ws"
        }
    }

    fun normalizeIpv4(value: String): String {
        val parts = value.trim().split('.')
        require(parts.size == 4) { "请输入有效的 IPv4 地址" }

        val octets = parts.map { part ->
            require(part.isNotEmpty() && part.all(Char::isDigit)) { "请输入有效的 IPv4 地址" }
            part.toIntOrNull()?.also { octet ->
                require(octet in 0..255) { "请输入有效的 IPv4 地址" }
            } ?: throw IllegalArgumentException("请输入有效的 IPv4 地址")
        }

        require(octets.any { it != 0 }) { "请输入电脑的局域网 IPv4 地址" }
        return octets.joinToString(".")
    }
}

object QuickerLanDns : Dns {
    private const val SUFFIX = ".lan.quicker.cc"

    override fun lookup(hostname: String): List<InetAddress> {
        val normalized = hostname.lowercase()
        if (!normalized.endsWith(SUFFIX)) {
            return Dns.SYSTEM.lookup(hostname)
        }

        val encodedIp = normalized.removeSuffix(SUFFIX)
        val parts = encodedIp.split('-')
        if (parts.size != 4) {
            throw UnknownHostException("Invalid Quicker LAN host: $hostname")
        }

        val bytes = ByteArray(4)
        parts.forEachIndexed { index, part ->
            val octet = part.toIntOrNull()
                ?.takeIf { it in 0..255 }
                ?: throw UnknownHostException("Invalid Quicker LAN host: $hostname")
            bytes[index] = octet.toByte()
        }

        return listOf(InetAddress.getByAddress(hostname, bytes))
    }
}
