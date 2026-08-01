package app.quickerlink.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class QuickerPairingCodeTest {
    @Test
    fun `round trips a WSS pairing configuration`() {
        val config = QuickerPairingConfig(
            "192.168.1.56",
            668,
            "a+b & c",
            "7db7596b-3b46-4afc-ab07-c96309d30aa8",
        )

        assertEquals(config, QuickerPairingCode.parse(QuickerPairingCode.encode(config)))
    }

    @Test
    fun `round trips a passwordless private LAN pairing code`() {
        val config = QuickerPairingConfig("10.0.0.8", 1668, "")

        assertEquals(config, QuickerPairingCode.parse(QuickerPairingCode.encode(config)))
    }

    @Test
    fun `accepts legacy pairing codes without a service action id`() {
        assertEquals(
            null,
            QuickerPairingCode.parse(
                "quickerlink://pair?v=1&ip=192.168.1.2&port=668&code=x",
            ).serviceActionId,
        )
    }

    @Test
    fun `preserves a literal plus in a query value`() {
        assertEquals(
            "a+b",
            QuickerPairingCode.parse(
                "quickerlink://pair?v=1&ip=192.168.1.2&port=668&code=a+b",
            ).password,
        )
    }

    @Test
    fun `rejects cloud push and non private connection codes`() {
        val cloudPushError = assertThrows(IllegalArgumentException::class.java) {
            QuickerPairingCode.parse(
                "https://tools.getquicker.cn/static/pushtool.html?email=placeholder&code=placeholder",
            )
        }
        assertEquals("云推送二维码不能用于局域网 WSS 配对", cloudPushError.message)
        assertThrows(IllegalArgumentException::class.java) {
            QuickerPairingCode.parse("quickerlink://pair?v=1&ip=8.8.8.8&port=668&code=x")
        }
    }

    @Test
    fun `rejects unknown versions duplicate fields and control characters`() {
        assertThrows(IllegalArgumentException::class.java) {
            QuickerPairingCode.parse("quickerlink://pair?v=2&ip=192.168.1.2&port=668&code=x")
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuickerPairingCode.parse(
                "quickerlink://pair?v=1&ip=192.168.1.2&ip=192.168.1.3&port=668&code=x",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuickerPairingCode.parse("quickerlink://pair?v=1&ip=192.168.1.2&port=668&code=x%0Ay")
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuickerPairingCode.parse("quickerlink://pair?v=1&ip=192.168.1.2&port=668&code=%C2%85")
        }
    }

    @Test
    fun `rejects missing fields paths and authority ports`() {
        assertThrows(IllegalArgumentException::class.java) {
            QuickerPairingCode.parse("quickerlink://pair?v=1&ip=192.168.1.2&port=668")
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuickerPairingCode.parse("quickerlink://pair/extra?v=1&ip=192.168.1.2&port=668&code=x")
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuickerPairingCode.parse("quickerlink://pair:668?v=1&ip=192.168.1.2&port=668&code=x")
        }
    }

    @Test
    fun `rejects malformed UTF-8 noncanonical ports and oversized payloads`() {
        assertThrows(IllegalArgumentException::class.java) {
            QuickerPairingCode.parse("quickerlink://pair?v=1&ip=192.168.1.2&port=668&code=%ff")
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuickerPairingCode.parse("quickerlink://pair?v=1&ip=192.168.1.2&port=%2B668&code=x")
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuickerPairingCode.parse("x".repeat(4_097))
        }
    }

    @Test
    fun `rejects unsafe encode inputs`() {
        assertThrows(IllegalArgumentException::class.java) {
            QuickerPairingCode.encode(QuickerPairingConfig("192.168.1.2", 668, "x".repeat(257)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuickerPairingCode.encode(QuickerPairingConfig("8.8.8.8", 668, "x"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuickerPairingCode.encode(QuickerPairingConfig("192.168.1.2", 668, "x\ny"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuickerPairingCode.encode(QuickerPairingConfig("192.168.1.2", 668, "x", "not-an-id"))
        }
    }
}
