package app.quickerlink.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.random.Random

sealed interface QuickerConnectionState {
    data object Disconnected : QuickerConnectionState
    data class Connecting(val endpoint: String) : QuickerConnectionState
    data object Authenticating : QuickerConnectionState
    data class Ready(val endpoint: String) : QuickerConnectionState
    data class Reconnecting(
        val attempt: Int,
        val delaySeconds: Long,
        val reason: String,
    ) : QuickerConnectionState

    data class AuthFailed(val reason: String) : QuickerConnectionState
    data class Error(val reason: String) : QuickerConnectionState
}

enum class QuickerEventDirection {
    SYSTEM,
    OUTGOING,
    INCOMING,
}

data class QuickerConnectionEvent(
    val direction: QuickerEventDirection,
    val summary: String,
)

private data class PendingCommand(
    val response: CompletableDeferred<QuickerMessage>,
    val logResponse: Boolean,
)

class QuickerIncomingCommand internal constructor(
    val message: QuickerMessage,
    internal val owner: Any,
    internal val generation: Long,
)

class QuickerConnectionManager private constructor(
    private val socketFactory: WebSocket.Factory,
    private val ownedClient: OkHttpClient?,
    dispatcher: CoroutineDispatcher,
    private val authTimeoutMs: Long,
    private val retryDelayMillis: (attempt: Int) -> Long,
) : AutoCloseable {
    constructor(client: OkHttpClient = defaultClient()) : this(
        socketFactory = client,
        ownedClient = client,
        dispatcher = Dispatchers.IO,
        authTimeoutMs = AUTH_TIMEOUT_MS,
        retryDelayMillis = ::defaultRetryDelayMillis,
    )

    internal constructor(
        socketFactory: WebSocket.Factory,
        dispatcher: CoroutineDispatcher,
        authTimeoutMs: Long = AUTH_TIMEOUT_MS,
        retryDelayMillis: (attempt: Int) -> Long = { 0L },
    ) : this(
        socketFactory = socketFactory,
        ownedClient = null,
        dispatcher = dispatcher,
        authTimeoutMs = authTimeoutMs,
        retryDelayMillis = retryDelayMillis,
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val generation = AtomicLong(0)
    private val serial = AtomicLong(1)
    private val pending = mutableMapOf<Long, PendingCommand>()
    private val lock = Any()

    private val mutableState = MutableStateFlow<QuickerConnectionState>(QuickerConnectionState.Disconnected)
    val state: StateFlow<QuickerConnectionState> = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<QuickerConnectionEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<QuickerConnectionEvent> = mutableEvents.asSharedFlow()

    private val commandOwner = Any()
    private val incomingCommands = Channel<QuickerIncomingCommand>(Channel.UNLIMITED)
    val commands: Flow<QuickerIncomingCommand> = incomingCommands.receiveAsFlow()

    private var desiredConfig: QuickerConnectionConfig? = null
    private var socket: WebSocket? = null
    private var readyGeneration: Long? = null
    private var retryJob: Job? = null
    private var authTimeoutJob: Job? = null
    private var retryAttempt = 0

    fun connect(config: QuickerConnectionConfig) {
        val normalizedConfig = config.copy(ipAddress = QuickerEndpoint.normalizeIpv4(config.ipAddress))
        QuickerEndpoint.url(normalizedConfig)

        synchronized(lock) {
            desiredConfig = normalizedConfig
            readyGeneration = null
            discardQueuedCommandsLocked()
            retryAttempt = 0
            retryJob?.cancel()
            retryJob = null
        }
        startConnection(normalizedConfig)
    }

    fun disconnect() {
        val oldSocket: WebSocket?
        val requestsToFail: List<CompletableDeferred<QuickerMessage>>
        synchronized(lock) {
            desiredConfig = null
            generation.incrementAndGet()
            readyGeneration = null
            retryJob?.cancel()
            retryJob = null
            authTimeoutJob?.cancel()
            authTimeoutJob = null
            oldSocket = socket
            socket = null
            retryAttempt = 0
            requestsToFail = drainPendingLocked()
            discardQueuedCommandsLocked()
            mutableState.value = QuickerConnectionState.Disconnected
        }

        oldSocket?.close(1000, "User disconnected")
        failRequests(requestsToFail, "连接已断开")
        mutableEvents.tryEmit(QuickerConnectionEvent(QuickerEventDirection.SYSTEM, "已断开连接"))
    }

    suspend fun sendCommand(
        operation: String,
        data: String? = null,
        action: String? = null,
        timeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        logSummary: String? = null,
        logRequest: Boolean = true,
        logResponse: Boolean = true,
    ): QuickerMessage {
        require(operation.isNotBlank()) { "操作类型不能为空" }
        val requestSerial = nextSerial()
        val deferred = CompletableDeferred<QuickerMessage>()
        val payload = QuickerProtocol.commandRequest(
            serial = requestSerial,
            operation = operation,
            data = data,
            action = action,
            wait = true,
        )

        val sent = synchronized(lock) {
            val token = generation.get()
            val currentSocket = socket
            check(readyGeneration == token && currentSocket != null) { "尚未连接到 Quicker" }

            pending[requestSerial] = PendingCommand(deferred, logResponse)
            if (currentSocket.send(payload)) {
                true
            } else {
                pending.remove(requestSerial)
                false
            }
        }
        if (!sent) {
            throw IllegalStateException("消息未能加入发送队列")
        }

        if (logRequest) {
            mutableEvents.tryEmit(
                QuickerConnectionEvent(
                    direction = QuickerEventDirection.OUTGOING,
                    summary = compactLogText(
                        logSummary
                            ?: if (operation == "action") {
                                "执行动作：${action.orEmpty()}"
                            } else {
                                "发送操作：$operation"
                            },
                    ),
                ),
            )
        }

        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            synchronized(lock) { pending.remove(requestSerial) }
        }
    }

    fun dispatchCommand(
        operation: String,
        data: String? = null,
        action: String? = null,
        logSummary: String? = null,
    ) {
        require(operation.isNotBlank()) { "操作类型不能为空" }
        val payload = QuickerProtocol.commandRequest(
            serial = nextSerial(),
            operation = operation,
            data = data,
            action = action,
            wait = false,
        )

        val sent = synchronized(lock) {
            val token = generation.get()
            val currentSocket = socket
            check(readyGeneration == token && currentSocket != null) { "尚未连接到 Quicker" }
            currentSocket.send(payload)
        }
        if (!sent) {
            throw IllegalStateException("消息未能加入发送队列")
        }

        mutableEvents.tryEmit(
            QuickerConnectionEvent(
                direction = QuickerEventDirection.OUTGOING,
                summary = compactLogText(
                    logSummary
                        ?: if (operation == "action") {
                            "发送动作：${action.orEmpty()}"
                        } else {
                            "发送操作：$operation"
                        },
                ),
            ),
        )
    }

    fun isCommandCurrent(command: QuickerIncomingCommand): Boolean = synchronized(lock) {
        isCommandCurrentLocked(command)
    }

    fun replyToCommand(
        request: QuickerIncomingCommand,
        isSuccess: Boolean,
        message: String,
        data: String? = null,
    ): Boolean {
        val requestSerial = request.message.serial ?: return false
        val payload = QuickerProtocol.commandResponse(
            serial = nextSerial(),
            replyTo = requestSerial,
            isSuccess = isSuccess,
            message = message,
            data = data,
        )
        return synchronized(lock) {
            val currentSocket = socket
            if (!isCommandCurrentLocked(request) || currentSocket == null) {
                false
            } else {
                currentSocket.send(payload)
            }
        }
    }

    private fun startConnection(config: QuickerConnectionConfig) {
        val endpoint = QuickerEndpoint.url(config)
        val token: Long
        val previousSocket: WebSocket?
        val requestsToFail: List<CompletableDeferred<QuickerMessage>>

        synchronized(lock) {
            if (desiredConfig != config) return
            token = generation.incrementAndGet()
            previousSocket = socket
            socket = null
            readyGeneration = null
            authTimeoutJob?.cancel()
            authTimeoutJob = null
            requestsToFail = drainPendingLocked()
            discardQueuedCommandsLocked()
            mutableState.value = QuickerConnectionState.Connecting(endpoint)
        }
        previousSocket?.cancel()
        failRequests(requestsToFail, "连接已重置")

        val request = Request.Builder().url(endpoint).build()
        val newSocket = runCatching {
            socketFactory.newWebSocket(request, listener(token, config, endpoint))
        }.getOrElse { error ->
            handleDisconnect(token, error.message ?: "WebSocket 连接失败")
            return
        }
        val keepSocket = synchronized(lock) {
            val connectionState = mutableState.value
            if (
                generation.get() == token &&
                desiredConfig == config &&
                connectionState !is QuickerConnectionState.Reconnecting &&
                connectionState !is QuickerConnectionState.Disconnected &&
                connectionState !is QuickerConnectionState.AuthFailed
            ) {
                socket = newSocket
                true
            } else {
                false
            }
        }
        if (!keepSocket) newSocket.cancel()
    }

    private fun listener(
        token: Long,
        config: QuickerConnectionConfig,
        endpoint: String,
    ) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val accepted = synchronized(lock) {
                if (generation.get() == token && desiredConfig == config) {
                    readyGeneration = null
                    mutableState.value = QuickerConnectionState.Authenticating
                    true
                } else {
                    false
                }
            }
            if (!accepted) {
                webSocket.close(1000, "Superseded connection")
                return
            }

            mutableEvents.tryEmit(QuickerConnectionEvent(QuickerEventDirection.SYSTEM, "WebSocket 已连接，正在认证"))

            if (config.password.isNotEmpty()) {
                val sent = webSocket.send(QuickerProtocol.authRequest(nextSerial(), config.password))
                if (!sent) {
                    webSocket.cancel()
                    handleDisconnect(token, "认证消息发送失败")
                    return
                }
            }

            synchronized(lock) {
                authTimeoutJob?.cancel()
                authTimeoutJob = scope.launch {
                    delay(authTimeoutMs)
                    val timedOut = handleDisconnect(
                        token = token,
                        reason = "等待认证响应超时",
                        onlyIfAuthenticating = true,
                    )
                    if (timedOut) {
                        webSocket.cancel()
                    }
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(token)) return
            val message = runCatching { QuickerProtocol.parse(text) }
                .getOrElse { error ->
                    mutableEvents.tryEmit(
                        QuickerConnectionEvent(
                            QuickerEventDirection.SYSTEM,
                            "收到无法解析的消息：${error.message.orEmpty()}",
                        ),
                    )
                    return
                }

            when (message.messageType) {
                QuickerProtocol.MESSAGE_AUTH_RESPONSE -> {
                    val authenticating = synchronized(lock) {
                        isCurrentLocked(token) && mutableState.value is QuickerConnectionState.Authenticating
                    }
                    if (authenticating) {
                        handleAuthResponse(
                            token = token,
                            endpoint = endpoint,
                            webSocket = webSocket,
                            message = message,
                        )
                    } else {
                        emitIgnoredBeforeReady(message.messageType)
                    }
                }

                QuickerProtocol.MESSAGE_RESPONSE -> {
                    val (accepted, request) = synchronized(lock) {
                        if (!isReadyLocked(token)) {
                            false to null
                        } else {
                            true to message.replyTo?.let { pending.remove(it) }
                        }
                    }
                    if (!accepted) {
                        emitIgnoredBeforeReady(message.messageType)
                        return
                    }
                    request?.response?.complete(message)
                    if (request?.logResponse == true) {
                        mutableEvents.tryEmit(
                            QuickerConnectionEvent(
                                QuickerEventDirection.INCOMING,
                                responseSummary(message),
                            ),
                        )
                    }
                }

                QuickerProtocol.MESSAGE_COMMAND -> {
                    val accepted = synchronized(lock) {
                        if (!isReadyLocked(token)) {
                            false
                        } else {
                            incomingCommands.trySend(
                                QuickerIncomingCommand(
                                    message = message,
                                    owner = commandOwner,
                                    generation = token,
                                ),
                            ).isSuccess
                        }
                    }
                    if (!accepted) {
                        emitIgnoredBeforeReady(message.messageType)
                    } else {
                        mutableEvents.tryEmit(
                            QuickerConnectionEvent(
                                QuickerEventDirection.INCOMING,
                                "Quicker 发来操作：${message.operation ?: "未知"}",
                            ),
                        )
                    }
                }

                else -> mutableEvents.tryEmit(
                    QuickerConnectionEvent(
                        QuickerEventDirection.INCOMING,
                        "收到消息类型 ${message.messageType}",
                    ),
                )
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!synchronized(lock) { isReadyLocked(token) }) {
                emitIgnoredBeforeReady(null)
                return
            }
            mutableEvents.tryEmit(
                QuickerConnectionEvent(
                    QuickerEventDirection.INCOMING,
                    "收到 ${bytes.size} 字节二进制数据，当前版本未保存文件",
                ),
            )
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (isCurrent(token)) webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleDisconnect(token, reason.ifBlank { "连接已关闭（$code）" })
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val detail = t.message?.takeIf(String::isNotBlank)
                ?: response?.message?.takeIf(String::isNotBlank)
                ?: "WebSocket 连接失败"
            handleDisconnect(token, detail)
        }
    }

    private fun handleAuthResponse(
        token: Long,
        endpoint: String,
        webSocket: WebSocket,
        message: QuickerMessage,
    ) {
        val accepted = synchronized(lock) {
            if (!isCurrentLocked(token) || mutableState.value !is QuickerConnectionState.Authenticating) {
                false
            } else {
                authTimeoutJob?.cancel()
                authTimeoutJob = null

                if (message.isSuccess == true) {
                    retryAttempt = 0
                    readyGeneration = token
                    mutableState.value = QuickerConnectionState.Ready(endpoint)
                } else {
                    desiredConfig = null
                    readyGeneration = null
                    generation.compareAndSet(token, token + 1)
                    socket = null
                    discardQueuedCommandsLocked()
                    mutableState.value = QuickerConnectionState.AuthFailed(
                        message.message?.takeIf(String::isNotBlank) ?: "验证码不正确",
                    )
                }
                true
            }
        }
        if (!accepted) return

        if (message.isSuccess == true) {
            mutableEvents.tryEmit(QuickerConnectionEvent(QuickerEventDirection.SYSTEM, "认证成功"))
        } else {
            val reason = message.message?.takeIf(String::isNotBlank) ?: "验证码不正确"
            mutableEvents.tryEmit(QuickerConnectionEvent(QuickerEventDirection.SYSTEM, "认证失败：$reason"))
            webSocket.close(1000, "Authentication failed")
        }
    }

    private fun handleDisconnect(
        token: Long,
        reason: String,
        onlyIfAuthenticating: Boolean = false,
    ): Boolean {
        val requestsToFail: List<CompletableDeferred<QuickerMessage>>
        val config: QuickerConnectionConfig?
        val reconnectToken: Long
        synchronized(lock) {
            if (
                !isCurrentLocked(token) ||
                (onlyIfAuthenticating && mutableState.value !is QuickerConnectionState.Authenticating)
            ) {
                return false
            }
            reconnectToken = generation.incrementAndGet()
            readyGeneration = null
            authTimeoutJob?.cancel()
            authTimeoutJob = null
            socket = null
            requestsToFail = drainPendingLocked()
            discardQueuedCommandsLocked()
            config = desiredConfig
            if (config == null) {
                mutableState.value = QuickerConnectionState.Disconnected
            }
        }
        failRequests(requestsToFail, reason)

        if (config == null) {
            return true
        }

        scheduleReconnect(reconnectToken, config, reason)
        return true
    }

    private fun scheduleReconnect(
        token: Long,
        config: QuickerConnectionConfig,
        reason: String,
    ) {
        lateinit var scheduledJob: Job
        val delayMs: Long
        val attempt: Int
        synchronized(lock) {
            if (retryJob != null || !isCurrentLocked(token) || desiredConfig != config) return
            retryAttempt += 1
            attempt = retryAttempt
            delayMs = retryDelayMillis(attempt).coerceAtLeast(0L)

            mutableState.value = QuickerConnectionState.Reconnecting(attempt, delayMs / 1_000L, reason)

            scheduledJob = scope.launch(start = CoroutineStart.LAZY) {
                delay(delayMs)
                val shouldReconnect = synchronized(lock) {
                    if (retryJob !== scheduledJob) {
                        false
                    } else {
                        retryJob = null
                        isCurrentLocked(token) && desiredConfig == config
                    }
                }
                if (shouldReconnect) {
                    startConnection(config)
                }
            }
            retryJob = scheduledJob
        }
        mutableEvents.tryEmit(
            QuickerConnectionEvent(
                QuickerEventDirection.SYSTEM,
                "连接中断，${delayMs / 1_000L} 秒后重试：$reason",
            ),
        )
        scheduledJob.start()
    }

    private fun responseSummary(message: QuickerMessage): String {
        return if (message.isSuccess == false) {
            val detail = message.message
                ?: QuickerProtocol.displayData(message.data)
                ?: "未知错误"
            "操作失败：${compactLogText(detail)}"
        } else {
            "操作成功"
        }
    }

    private fun drainPendingLocked(): List<CompletableDeferred<QuickerMessage>> =
        pending.values.map(PendingCommand::response).also { pending.clear() }

    private fun discardQueuedCommandsLocked() {
        while (incomingCommands.tryReceive().isSuccess) {
            // Commands are scoped to the authenticated WebSocket generation that received them.
        }
    }

    private fun failRequests(
        requests: List<CompletableDeferred<QuickerMessage>>,
        reason: String,
    ) {
        val error = IllegalStateException(reason)
        requests.forEach { it.completeExceptionally(error) }
    }

    private fun nextSerial(): Long = serial.getAndUpdate { current ->
        if (current == Long.MAX_VALUE) 1 else current + 1
    }

    private fun emitIgnoredBeforeReady(messageType: Int?) {
        val description = messageType?.let { "消息类型 $it" } ?: "二进制消息"
        mutableEvents.tryEmit(
            QuickerConnectionEvent(
                QuickerEventDirection.SYSTEM,
                "认证完成前已忽略$description",
            ),
        )
    }

    private fun isCurrent(token: Long): Boolean = generation.get() == token

    private fun isCurrentLocked(token: Long): Boolean = generation.get() == token

    private fun isReadyLocked(token: Long): Boolean = isCurrentLocked(token) && readyGeneration == token

    private fun isCommandCurrentLocked(command: QuickerIncomingCommand): Boolean =
        command.owner === commandOwner && isReadyLocked(command.generation)

    override fun close() {
        disconnect()
        incomingCommands.close()
        scope.cancel()
        ownedClient?.dispatcher?.executorService?.shutdown()
        ownedClient?.connectionPool?.evictAll()
    }

    companion object {
        private const val AUTH_TIMEOUT_MS = 10_000L
        private const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L

        private fun defaultRetryDelayMillis(attempt: Int): Long {
            val baseDelaySeconds = min(30L, 1L shl min(attempt - 1, 5))
            return baseDelaySeconds * 1_000L + Random.nextLong(0L, 500L)
        }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .proxy(Proxy.NO_PROXY)
            .dns(QuickerLanDns)
            .build()
    }
}

internal fun compactLogText(value: String, maxLength: Int = 180): String {
    require(maxLength >= 4)
    val compact = value
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifEmpty { "无详细信息" }
    return if (compact.length <= maxLength) compact else compact.take(maxLength - 3) + "..."
}
