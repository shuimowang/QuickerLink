package app.quickerlink.connection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class QuickerWebSocketEndpointProbeTest {
    @Test
    fun `uses WSS endpoint without sending credentials`() = runTest {
        val factory = DiscoveryWebSocketFactory()
        val probe = QuickerWebSocketEndpointProbe(factory)
        val result = async { probe.probe(endpoint()) }
        runCurrent()

        assertEquals("wss://192-168-1-56.lan.quicker.cc:668/ws", endpoint().url)
        assertEquals("https://192-168-1-56.lan.quicker.cc:668/ws", factory.socket.request().url.toString())
        assertFalse(result.isCompleted)
        factory.socket.open()

        assertTrue(result.await())
        assertTrue(factory.socket.sentTexts.isEmpty())
        assertTrue(factory.socket.closed)
    }

    @Test
    fun `reports handshake failure`() = runTest {
        val factory = DiscoveryWebSocketFactory()
        val probe = QuickerWebSocketEndpointProbe(factory)
        val result = async { probe.probe(endpoint()) }
        runCurrent()

        assertEquals(
            "https://192-168-1-56.lan.quicker.cc:668/ws",
            factory.socket.request().url.toString(),
        )
        factory.socket.fail(IOException("refused"))

        assertFalse(result.await())
        assertTrue(factory.socket.sentTexts.isEmpty())
    }

    @Test
    fun `cancelling probe cancels websocket`() = runTest {
        val factory = DiscoveryWebSocketFactory()
        val probe = QuickerWebSocketEndpointProbe(factory)
        val result = async { probe.probe(endpoint()) }
        runCurrent()

        result.cancelAndJoin()

        assertTrue(result.isCancelled)
        assertTrue(factory.socket.cancelled)
    }

    private fun endpoint() = QuickerDiscoveryEndpoint(
        ipAddress = "192.168.1.56",
        port = 668,
    )
}

private class DiscoveryWebSocketFactory : WebSocket.Factory {
    lateinit var socket: DiscoveryFakeWebSocket
        private set

    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
        socket = DiscoveryFakeWebSocket(request, listener)
        return socket
    }
}

private class DiscoveryFakeWebSocket(
    private val originalRequest: Request,
    private val listener: WebSocketListener,
) : WebSocket {
    val sentTexts = mutableListOf<String>()
    var closed = false
        private set
    var cancelled = false
        private set

    override fun request(): Request = originalRequest

    override fun queueSize(): Long = 0L

    override fun send(text: String): Boolean {
        sentTexts += text
        return true
    }

    override fun send(bytes: ByteString): Boolean = true

    override fun close(code: Int, reason: String?): Boolean {
        closed = true
        return true
    }

    override fun cancel() {
        cancelled = true
    }

    fun open() {
        listener.onOpen(
            this,
            Response.Builder()
                .request(originalRequest)
                .protocol(Protocol.HTTP_1_1)
                .code(101)
                .message("Switching Protocols")
                .build(),
        )
    }

    fun fail(error: Throwable) {
        listener.onFailure(this, error, null)
    }

}
