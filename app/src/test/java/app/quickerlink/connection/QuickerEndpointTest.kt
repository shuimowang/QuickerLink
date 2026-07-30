package app.quickerlink.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.UnknownHostException

class QuickerEndpointTest {
    @Test
    fun `builds secure Quicker LAN url`() {
        val url = QuickerEndpoint.url(
            QuickerConnectionConfig("192.168.1.56", 668, secure = true, password = "1234"),
        )

        assertEquals("wss://192-168-1-56.lan.quicker.cc:668/ws", url)
    }

    @Test
    fun `builds cleartext url and normalizes octets`() {
        val url = QuickerEndpoint.url(
            QuickerConnectionConfig(" 192.168.001.056 ", 668, secure = false, password = ""),
        )

        assertEquals("ws://192.168.1.56:668/ws", url)
    }

    @Test
    fun `rejects invalid address and port`() {
        assertThrows(IllegalArgumentException::class.java) {
            QuickerEndpoint.url(QuickerConnectionConfig("192.168.1.999", 668, true, ""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuickerEndpoint.url(QuickerConnectionConfig("192.168.1.2", 0, true, ""))
        }
    }

    @Test
    fun `resolves encoded Quicker hostname locally`() {
        val address = QuickerLanDns.lookup("192-168-1-56.lan.quicker.cc").single()

        assertEquals("192.168.1.56", address.hostAddress)
    }

    @Test
    fun `rejects malformed encoded Quicker hostname`() {
        assertThrows(UnknownHostException::class.java) {
            QuickerLanDns.lookup("192-168-1.lan.quicker.cc")
        }
    }
}
