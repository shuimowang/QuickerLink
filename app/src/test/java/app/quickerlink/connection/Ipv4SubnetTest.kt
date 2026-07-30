package app.quickerlink.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Ipv4SubnetTest {
    @Test
    fun `generates nearby slash 24 hosts without local network or broadcast addresses`() {
        val subnet = Ipv4Subnet.from("192.168.1.100", 24)

        assertEquals("192.168.1.0", subnet.networkAddress)
        assertEquals("192.168.1.255", subnet.broadcastAddress)
        assertEquals(
            listOf("192.168.1.99", "192.168.1.101", "192.168.1.98", "192.168.1.102"),
            subnet.hostCandidates(4),
        )

        val allPeers = subnet.hostCandidates(300)
        assertEquals(253, allPeers.size)
        assertFalse("192.168.1.0" in allPeers)
        assertFalse("192.168.1.100" in allPeers)
        assertFalse("192.168.1.255" in allPeers)
    }

    @Test
    fun `treats slash 31 as point to point and slash 32 as having no peers`() {
        assertEquals(
            listOf("10.0.0.11"),
            Ipv4Subnet.from("10.0.0.10", 31).hostCandidates(10),
        )
        assertEquals(
            listOf("10.0.0.10"),
            Ipv4Subnet.from("10.0.0.11", 31).hostCandidates(10),
        )
        assertTrue(Ipv4Subnet.from("10.0.0.10", 32).hostCandidates(10).isEmpty())
    }

    @Test
    fun `large subnet generation stops at the requested hard limit`() {
        val subnet = Ipv4Subnet.from("10.20.30.40", 8)

        assertEquals(Ipv4Subnet.MAX_GENERATED_CANDIDATES, subnet.hostCandidates(1_024).size)
        assertThrows(IllegalArgumentException::class.java) {
            subnet.hostCandidates(Ipv4Subnet.MAX_GENERATED_CANDIDATES + 1)
        }
    }

    @Test
    fun `rejects invalid prefix lengths`() {
        assertThrows(IllegalArgumentException::class.java) { Ipv4Subnet.from("192.168.1.10", -1) }
        assertThrows(IllegalArgumentException::class.java) { Ipv4Subnet.from("192.168.1.10", 33) }
    }
}
