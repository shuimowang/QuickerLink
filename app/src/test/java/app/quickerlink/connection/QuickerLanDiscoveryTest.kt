package app.quickerlink.connection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class QuickerLanDiscoveryTest {
    @Test
    fun `enforces candidate and concurrency limits while preserving candidate order`() = runTest {
        val active = AtomicInteger(0)
        val peakActive = AtomicInteger(0)
        val calls = AtomicInteger(0)
        val discovery = QuickerLanDiscovery { endpoint ->
            calls.incrementAndGet()
            val currentActive = active.incrementAndGet()
            peakActive.updateAndGet { maxOf(it, currentActive) }
            try {
                val octet = endpoint.ipAddress.substringAfterLast('.').toInt()
                delay(if (octet % 2 == 0) 20L else 100L)
                octet in setOf(9, 12)
            } finally {
                active.decrementAndGet()
            }
        }

        val result = discovery.discover(
            request(
                maxCandidates = 8,
                maxConcurrency = 3,
                probeTimeoutMillis = 500L,
                overallTimeoutMillis = 2_000L,
            ),
        )

        assertEquals(8, result.candidateCount)
        assertEquals(8, result.attemptedCount)
        assertEquals(8, result.completedCount)
        assertEquals(8, calls.get())
        assertTrue(peakActive.get() <= 3)
        assertEquals(listOf("192.168.1.9", "192.168.1.12"), result.endpoints.map { it.ipAddress })
        assertFalse(result.timedOut)
    }

    @Test
    fun `per endpoint timeout cancels probes and continues scanning`() = runTest {
        val cancelled = AtomicInteger(0)
        val discovery = QuickerLanDiscovery {
            try {
                delay(1_000L)
                true
            } finally {
                cancelled.incrementAndGet()
            }
        }

        val result = discovery.discover(
            request(
                maxCandidates = 4,
                maxConcurrency = 2,
                probeTimeoutMillis = 100L,
                overallTimeoutMillis = 1_000L,
            ),
        )

        assertTrue(result.endpoints.isEmpty())
        assertEquals(4, result.attemptedCount)
        assertEquals(4, result.completedCount)
        assertEquals(4, cancelled.get())
        assertFalse(result.timedOut)
    }

    @Test
    fun `overall timeout returns partial metadata and cancels active probes`() = runTest {
        val cancelled = AtomicInteger(0)
        val discovery = QuickerLanDiscovery {
            try {
                delay(5_000L)
                true
            } finally {
                cancelled.incrementAndGet()
            }
        }

        val result = discovery.discover(
            request(
                maxCandidates = 10,
                maxConcurrency = 2,
                probeTimeoutMillis = 5_000L,
                overallTimeoutMillis = 150L,
            ),
        )

        assertTrue(result.timedOut)
        assertEquals(10, result.candidateCount)
        assertEquals(2, result.attemptedCount)
        assertEquals(0, result.completedCount)
        assertEquals(2, cancelled.get())
    }

    @Test
    fun `caller cancellation propagates and stops all workers`() = runTest {
        val started = AtomicInteger(0)
        val cancelled = AtomicInteger(0)
        val discovery = QuickerLanDiscovery {
            started.incrementAndGet()
            try {
                awaitCancellation()
            } finally {
                cancelled.incrementAndGet()
            }
        }

        val scan = async {
            discovery.discover(
                request(
                    maxCandidates = 20,
                    maxConcurrency = 4,
                    probeTimeoutMillis = 5_000L,
                    overallTimeoutMillis = 30_000L,
                ),
            )
        }
        runCurrent()
        assertEquals(4, started.get())

        scan.cancelAndJoin()

        assertTrue(scan.isCancelled)
        assertEquals(started.get(), cancelled.get())
    }

    @Test
    fun `isolates probe failures and validates hard limits`() = runTest {
        val discovery = QuickerLanDiscovery { endpoint ->
            if (endpoint.ipAddress.endsWith(".9")) error("unreachable")
            endpoint.ipAddress.endsWith(".11")
        }

        val result = discovery.discover(request(maxCandidates = 2, maxConcurrency = 2))

        assertEquals(listOf("192.168.1.11"), result.endpoints.map { it.ipAddress })
        assertEquals(2, result.completedCount)
        assertThrows(IllegalArgumentException::class.java) {
            QuickerDiscoveryLimits(maxCandidates = Ipv4Subnet.MAX_GENERATED_CANDIDATES + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuickerDiscoveryLimits(maxConcurrency = QuickerDiscoveryLimits.HARD_MAX_CONCURRENCY + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuickerDiscoveryLimits(
                probeTimeoutMillis = QuickerDiscoveryLimits.HARD_MAX_PROBE_TIMEOUT_MILLIS + 1L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuickerDiscoveryLimits(
                overallTimeoutMillis = QuickerDiscoveryLimits.HARD_MAX_OVERALL_TIMEOUT_MILLIS + 1L,
            )
        }
    }

    @Test
    fun `discovery checks every bounded candidate so ambiguous endpoints are visible`() = runTest {
        val calls = AtomicInteger(0)
        val discovery = QuickerLanDiscovery {
            calls.incrementAndGet()
            true
        }
        val request = request(maxCandidates = 8, maxConcurrency = 1)

        val result = discovery.discover(request)

        assertEquals(8, calls.get())
        assertEquals(8, result.endpoints.size)
        assertFalse(result.timedOut)
    }

    @Test
    fun `empty slash 32 subnet completes without waiting for the overall timeout`() = runTest {
        val calls = AtomicInteger(0)
        val discovery = QuickerLanDiscovery {
            calls.incrementAndGet()
            true
        }

        val result = discovery.discover(
            QuickerDiscoveryRequest(
                subnet = Ipv4Subnet.from("10.0.0.10", 32),
                port = 668,
                limits = QuickerDiscoveryLimits(overallTimeoutMillis = 30_000L),
            ),
        )

        assertEquals(0, calls.get())
        assertEquals(0, result.candidateCount)
        assertFalse(result.timedOut)
    }

    private fun request(
        maxCandidates: Int,
        maxConcurrency: Int,
        probeTimeoutMillis: Long = 1_000L,
        overallTimeoutMillis: Long = 10_000L,
    ) = QuickerDiscoveryRequest(
        subnet = Ipv4Subnet.from("192.168.1.10", 24),
        port = 668,
        limits = QuickerDiscoveryLimits(
            maxCandidates = maxCandidates,
            maxConcurrency = maxConcurrency,
            probeTimeoutMillis = probeTimeoutMillis,
            overallTimeoutMillis = overallTimeoutMillis,
        ),
    )
}
