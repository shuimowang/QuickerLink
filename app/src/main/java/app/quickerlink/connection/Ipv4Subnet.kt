package app.quickerlink.connection

class Ipv4Subnet private constructor(
    val localAddress: String,
    val prefixLength: Int,
    private val localAddressValue: Long,
    private val networkAddressValue: Long,
    private val broadcastAddressValue: Long,
) {
    val networkAddress: String = formatIpv4(networkAddressValue)
    val broadcastAddress: String = formatIpv4(broadcastAddressValue)

    /**
     * Returns nearby usable hosts without ever walking the complete subnet.
     * The local address is excluded. For /31 both addresses are treated as hosts;
     * /32 has no peer candidates.
     */
    fun hostCandidates(maxCandidates: Int): List<String> {
        require(maxCandidates in 0..MAX_GENERATED_CANDIDATES) {
            "候选地址数量必须在 0 到 $MAX_GENERATED_CANDIDATES 之间"
        }
        if (maxCandidates == 0) return emptyList()

        val firstHost = if (prefixLength <= 30) networkAddressValue + 1L else networkAddressValue
        val lastHost = if (prefixLength <= 30) broadcastAddressValue - 1L else broadcastAddressValue
        val result = ArrayList<String>(maxCandidates)
        var lower = localAddressValue - 1L
        var upper = localAddressValue + 1L

        while (result.size < maxCandidates && (lower >= firstHost || upper <= lastHost)) {
            if (lower >= firstHost && result.size < maxCandidates) {
                result += formatIpv4(lower)
            }
            lower -= 1L

            if (upper <= lastHost && result.size < maxCandidates) {
                result += formatIpv4(upper)
            }
            upper += 1L
        }
        return result
    }

    override fun toString(): String = "$localAddress/$prefixLength"

    companion object {
        const val MAX_GENERATED_CANDIDATES = 1_024
        private const val IPV4_MASK = 0xffff_ffffL

        fun from(localAddress: String, prefixLength: Int): Ipv4Subnet {
            require(prefixLength in 0..32) { "IPv4 子网前缀必须在 0 到 32 之间" }
            val normalizedAddress = QuickerEndpoint.normalizeIpv4(localAddress)
            val addressValue = parseIpv4(normalizedAddress)
            val hostBits = 32 - prefixLength
            val hostMask = if (hostBits == 0) 0L else (1L shl hostBits) - 1L
            val networkValue = addressValue and (IPV4_MASK xor hostMask)
            val broadcastValue = networkValue or hostMask

            return Ipv4Subnet(
                localAddress = normalizedAddress,
                prefixLength = prefixLength,
                localAddressValue = addressValue,
                networkAddressValue = networkValue,
                broadcastAddressValue = broadcastValue,
            )
        }

        private fun parseIpv4(address: String): Long = address
            .split('.')
            .fold(0L) { value, octet -> (value shl 8) or octet.toLong() }

        private fun formatIpv4(value: Long): String = buildString {
            append((value shr 24) and 0xffL)
            append('.')
            append((value shr 16) and 0xffL)
            append('.')
            append((value shr 8) and 0xffL)
            append('.')
            append(value and 0xffL)
        }
    }
}
