package app.quickerlink.connection

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class QuickerConnectionManagerTest {
    @Test
    fun `continues reconnecting after consecutive immediate failures`() = runTest {
        val factory = FakeWebSocketFactory(failFirstConnections = 3)
        val manager = QuickerConnectionManager(
            socketFactory = factory,
            dispatcher = StandardTestDispatcher(testScheduler),
            retryDelayMillis = { 0L },
        )

        manager.connect(CONFIG)
        advanceUntilIdle()

        assertEquals(4, factory.sockets.size)
        assertTrue(manager.state.value is QuickerConnectionState.Connecting)
        manager.close()
    }

    @Test
    fun `gates business messages until authenticated and queues commands reliably`() = runTest {
        val factory = FakeWebSocketFactory()
        val manager = QuickerConnectionManager(
            socketFactory = factory,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        manager.connect(CONFIG)
        val socket = factory.latestSocket()
        assertTrue(manager.state.value is QuickerConnectionState.Connecting)

        socket.open()
        assertEquals(QuickerConnectionState.Authenticating, manager.state.value)

        socket.receive(command(serial = 10))
        socket.receive("""{"messageType":4,"replyTo":10,"isSuccess":true}""")
        val beforeAuth = runCatching {
            manager.sendCommand(operation = "copy", data = "blocked", timeoutMs = 50)
        }
        assertTrue(beforeAuth.exceptionOrNull() is IllegalStateException)

        socket.receive("""{"messageType":6,"replyTo":1,"isSuccess":true}""")
        assertTrue(manager.state.value is QuickerConnectionState.Ready)

        // A duplicate auth response must not move an already-ready connection to AuthFailed.
        socket.receive("""{"messageType":6,"replyTo":1,"isSuccess":false,"message":"late"}""")
        assertTrue(manager.state.value is QuickerConnectionState.Ready)

        val expectedSerials = (100L until 200L).toList()
        expectedSerials.forEach { socket.receive(command(serial = it)) }

        // Collection begins after the burst. Channel-backed commands must still all be present.
        val received = withTimeout(1_000) {
            manager.commands.take(expectedSerials.size).toList()
        }
        assertEquals(expectedSerials, received.map { it.message.serial })
        assertFalse(received.any { it.message.serial == 10L })
        manager.close()
    }

    @Test
    fun `dispatch sends wait false and returns without a response`() = runTest {
        val factory = FakeWebSocketFactory()
        val manager = QuickerConnectionManager(
            socketFactory = factory,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        manager.connect(CONFIG)
        val socket = factory.latestSocket()
        socket.open()
        socket.receive("""{"messageType":6,"replyTo":1,"isSuccess":true}""")

        withTimeout(100L) {
            manager.dispatchCommand(
                operation = "action",
                action = "action-id",
                data = "parameter",
            )
        }

        val request = socket.sentTexts
            .map(JsonParser::parseString)
            .map { it.asJsonObject }
            .last { it.get("messageType").asInt == QuickerProtocol.MESSAGE_COMMAND }
        assertEquals("action", request.get("operation").asString)
        assertEquals("action-id", request.get("action").asString)
        assertEquals("parameter", request.get("data").asString)
        assertFalse(request.get("wait").asBoolean)
        manager.close()
    }

    @Test
    fun `dispatch fails immediately when websocket rejects the frame`() = runTest {
        val factory = FakeWebSocketFactory()
        val manager = QuickerConnectionManager(
            socketFactory = factory,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        manager.connect(CONFIG)
        val socket = factory.latestSocket()
        socket.open()
        socket.receive("""{"messageType":6,"replyTo":1,"isSuccess":true}""")
        socket.acceptTextSends = false

        val failure = runCatching {
            manager.dispatchCommand(operation = "action", action = "action-id")
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("消息未能加入发送队列", failure?.message)
        manager.close()
    }

    @Test
    fun `commands from a disconnected generation are stale after reconnect`() = runTest {
        val factory = FakeWebSocketFactory()
        val manager = QuickerConnectionManager(
            socketFactory = factory,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        manager.connect(CONFIG)
        val firstSocket = factory.latestSocket()
        firstSocket.open()
        firstSocket.receive("""{"messageType":6,"replyTo":1,"isSuccess":true}""")
        firstSocket.receive(command(serial = 10L))
        firstSocket.receive(command(serial = 11L))
        val staleCommand = withTimeout(1_000L) { manager.commands.first() }

        firstSocket.fail(IOException("connection lost"))
        advanceUntilIdle()
        val secondSocket = factory.latestSocket()
        secondSocket.open()
        secondSocket.receive("""{"messageType":6,"replyTo":2,"isSuccess":true}""")

        assertFalse(manager.isCommandCurrent(staleCommand))
        assertFalse(manager.replyToCommand(staleCommand, true, "stale"))

        secondSocket.receive(command(serial = 20L))
        val freshCommand = withTimeout(1_000L) { manager.commands.first() }
        assertEquals(20L, freshCommand.message.serial)
        assertTrue(manager.isCommandCurrent(freshCommand))
        assertTrue(manager.replyToCommand(freshCommand, true, "ok"))
        assertEquals(
            20L,
            secondSocket.sentTexts
                .map(QuickerProtocol::parse)
                .last { it.messageType == QuickerProtocol.MESSAGE_RESPONSE }
                .replyTo,
        )
        manager.close()
    }

    @Test
    fun `late auth response after timeout cannot restore ready state`() = runTest {
        val factory = FakeWebSocketFactory()
        val manager = QuickerConnectionManager(
            socketFactory = factory,
            dispatcher = StandardTestDispatcher(testScheduler),
            authTimeoutMs = 100L,
            retryDelayMillis = { 1_000L },
        )

        manager.connect(CONFIG)
        val socket = factory.latestSocket()
        socket.open()
        assertEquals(QuickerConnectionState.Authenticating, manager.state.value)

        advanceTimeBy(100L)
        runCurrent()
        assertTrue(manager.state.value is QuickerConnectionState.Reconnecting)

        socket.receive("""{"messageType":6,"replyTo":1,"isSuccess":true}""")
        assertTrue(manager.state.value is QuickerConnectionState.Reconnecting)
        assertEquals(1, factory.sockets.size)
        manager.close()
    }

    @Test
    fun `disconnect cannot pass a send in progress and fails its pending request`() = runBlocking {
        val factory = FakeWebSocketFactory()
        val manager = QuickerConnectionManager(
            socketFactory = factory,
            dispatcher = Dispatchers.Default,
            authTimeoutMs = 60_000L,
        )
        manager.connect(CONFIG)
        val socket = factory.latestSocket()
        socket.open()
        socket.receive("""{"messageType":6,"replyTo":1,"isSuccess":true}""")

        val sendEntered = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        val disconnectStarted = CountDownLatch(1)
        socket.onTextSend = { text ->
            if (QuickerProtocol.parse(text).messageType == QuickerProtocol.MESSAGE_COMMAND) {
                sendEntered.countDown()
                assertTrue(releaseSend.await(2, TimeUnit.SECONDS))
            }
        }

        val request = async(Dispatchers.Default) {
            runCatching {
                manager.sendCommand(operation = "copy", data = "hello", timeoutMs = 5_000L)
            }
        }
        assertTrue(sendEntered.await(1, TimeUnit.SECONDS))

        val disconnect = async(Dispatchers.Default) {
            disconnectStarted.countDown()
            manager.disconnect()
        }
        assertTrue(disconnectStarted.await(1, TimeUnit.SECONDS))
        Thread.sleep(50)
        assertFalse("disconnect must wait for the lock held by send", disconnect.isCompleted)

        releaseSend.countDown()
        withTimeout(1_000) { disconnect.await() }
        val failure = withTimeout(1_000) { request.await() }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure is IllegalStateException)
        assertEquals("连接已断开", failure?.message)
        assertEquals(QuickerConnectionState.Disconnected, manager.state.value)
        manager.close()
    }

    private fun command(serial: Long): String =
        """{"messageType":2,"serial":$serial,"operation":"copy","data":"value-$serial"}"""

    private companion object {
        val CONFIG = QuickerConnectionConfig(
            ipAddress = "192.168.1.56",
            port = 668,
            password = "1234",
        )
    }
}

private class FakeWebSocketFactory(
    private val failFirstConnections: Int = 0,
) : WebSocket.Factory {
    val sockets: List<FakeWebSocket>
        get() = synchronized(createdSockets) { createdSockets.toList() }

    private val createdSockets = mutableListOf<FakeWebSocket>()

    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
        val socket = FakeWebSocket(request, listener)
        val connectionNumber = synchronized(createdSockets) {
            createdSockets += socket
            createdSockets.size
        }
        if (connectionNumber <= failFirstConnections) {
            socket.fail(IOException("failure-$connectionNumber"))
        }
        return socket
    }

    fun latestSocket(): FakeWebSocket = synchronized(createdSockets) { createdSockets.last() }
}

private class FakeWebSocket(
    private val originalRequest: Request,
    private val listener: WebSocketListener,
) : WebSocket {
    val sentTexts: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var onTextSend: ((String) -> Unit)? = null

    @Volatile
    var acceptTextSends = true

    @Volatile
    private var active = true

    override fun request(): Request = originalRequest

    override fun queueSize(): Long = 0L

    override fun send(text: String): Boolean {
        if (!active || !acceptTextSends) return false
        onTextSend?.invoke(text)
        sentTexts += text
        return true
    }

    override fun send(bytes: ByteString): Boolean = active

    override fun close(code: Int, reason: String?): Boolean {
        val wasActive = active
        active = false
        return wasActive
    }

    override fun cancel() {
        active = false
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

    fun receive(text: String) {
        listener.onMessage(this, text)
    }

    fun fail(error: Throwable) {
        active = false
        listener.onFailure(this, error, null)
    }
}
