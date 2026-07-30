package app.quickerlink.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address

fun interface Ipv4SubnetProvider {
    fun currentSubnet(): Ipv4Subnet?
}

class AndroidIpv4SubnetProvider(context: Context) : Ipv4SubnetProvider {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Suppress("DEPRECATION")
    override fun currentSubnet(): Ipv4Subnet? {
        val activeNetwork = connectivityManager.activeNetwork
        val candidates = sequence {
            if (activeNetwork != null) yield(activeNetwork)
            connectivityManager.allNetworks.forEach { network ->
                if (network != activeNetwork) yield(network)
            }
        }
        return candidates
            .filter(::isLanNetwork)
            .firstNotNullOfOrNull(::privateIpv4Subnet)
    }

    private fun privateIpv4Subnet(network: Network): Ipv4Subnet? {
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return null

        return linkProperties.linkAddresses.asSequence()
            .firstNotNullOfOrNull { linkAddress ->
                val address = linkAddress.address
                if (
                    address is Inet4Address &&
                    !address.isAnyLocalAddress &&
                    !address.isLoopbackAddress &&
                    !address.isLinkLocalAddress &&
                    !address.isMulticastAddress &&
                    address.isSiteLocalAddress
                ) {
                    address.hostAddress?.let { Ipv4Subnet.from(it, linkAddress.prefixLength) }
                } else {
                    null
                }
            }
    }

    private fun isLanNetwork(network: Network): Boolean {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
