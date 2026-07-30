package app.quickerlink.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.Proxy
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

data class QuickerDiscoveryLimits(
    val maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
    val maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
    val probeTimeoutMillis: Long = DEFAULT_PROBE_TIMEOUT_MILLIS,
    val overallTimeoutMillis: Long = DEFAULT_OVERALL_TIMEOUT_MILLIS,
) {
    init {
        require(maxCandidates in 1..Ipv4Subnet.MAX_GENERATED_CANDIDATES) {
            "最大候选地址数量必须在 1 到 ${Ipv4Subnet.MAX_GENERATED_CANDIDATES} 之间"
        }
        require(maxConcurrency in 1..HARD_MAX_CONCURRENCY) {
            "最大并发探测数必须在 1 到 $HARD_MAX_CONCURRENCY 之间"
        }
        require(probeTimeoutMillis in 1L..HARD_MAX_PROBE_TIMEOUT_MILLIS) {
            "单个端点探测超时必须在 1 到 $HARD_MAX_PROBE_TIMEOUT_MILLIS 毫秒之间"
        }
        require(overallTimeoutMillis in 1L..HARD_MAX_OVERALL_TIMEOUT_MILLIS) {
            "整体探测超时必须在 1 到 $HARD_MAX_OVERALL_TIMEOUT_MILLIS 毫秒之间"
        }
    }

    companion object {
        const val DEFAULT_MAX_CANDIDATES = 254
        const val DEFAULT_MAX_CONCURRENCY = 32
        const val DEFAULT_PROBE_TIMEOUT_MILLIS = 1_000L
        const val DEFAULT_OVERALL_TIMEOUT_MILLIS = 10_000L
        const val HARD_MAX_CONCURRENCY = 64
        const val HARD_MAX_PROBE_TIMEOUT_MILLIS = 5_000L
        const val HARD_MAX_OVERALL_TIMEOUT_MILLIS = 30_000L
    }
}

data class QuickerDiscoveryRequest(
    val subnet: Ipv4Subnet,
    val port: Int,
    val limits: QuickerDiscoveryLimits = QuickerDiscoveryLimits(),
) {
    init {
        require(port in 1..65535) { "端口必须在 1 到 65535 之间" }
    }
}

data class QuickerDiscoveryEndpoint(
    val ipAddress: String,
    val port: Int,
) {
    init {
        QuickerEndpoint.normalizeIpv4(ipAddress)
        require(port in 1..65535) { "端口必须在 1 到 65535 之间" }
    }

    val url: String
        get() = QuickerEndpoint.url(
            QuickerConnectionConfig(
                ipAddress = ipAddress,
                port = port,
                password = "",
            ),
        )
}

data class QuickerDiscoveryResult(
    val endpoints: List<QuickerDiscoveryEndpoint>,
    val candidateCount: Int,
    val attemptedCount: Int,
    val completedCount: Int,
    val timedOut: Boolean,
)

fun interface QuickerEndpointProbe {
    suspend fun probe(endpoint: QuickerDiscoveryEndpoint): Boolean
}

class QuickerLanDiscovery(
    private val endpointProbe: QuickerEndpointProbe,
) {
    suspend fun discover(request: QuickerDiscoveryRequest): QuickerDiscoveryResult {
        val candidates = request.subnet.hostCandidates(request.limits.maxCandidates)
        val nextCandidateIndex = AtomicInteger(0)
        val attemptedCount = AtomicInteger(0)
        val completedCount = AtomicInteger(0)
        val matches = Collections.synchronizedList(mutableListOf<IndexedValue<QuickerDiscoveryEndpoint>>())

        val completedWithinDeadline = withTimeoutOrNull(request.limits.overallTimeoutMillis) {
            coroutineScope {
                val workers = List(minOf(request.limits.maxConcurrency, candidates.size)) {
                    launch {
                        while (true) {
                            val index = nextCandidateIndex.getAndIncrement()
                            if (index >= candidates.size) break
                            attemptedCount.incrementAndGet()
                            val endpoint = QuickerDiscoveryEndpoint(
                                ipAddress = candidates[index],
                                port = request.port,
                            )
                            val matched = probeCandidate(
                                endpoint = endpoint,
                                timeoutMillis = request.limits.probeTimeoutMillis,
                            )
                            completedCount.incrementAndGet()
                            if (matched) matches += IndexedValue(index, endpoint)
                        }
                    }
                }
                workers.joinAll()
            }
            true
        } ?: false

        return QuickerDiscoveryResult(
            endpoints = matches.sortedBy(IndexedValue<QuickerDiscoveryEndpoint>::index).map { it.value },
            candidateCount = candidates.size,
            attemptedCount = attemptedCount.get(),
            completedCount = completedCount.get(),
            timedOut = !completedWithinDeadline,
        )
    }

    private suspend fun probeCandidate(
        endpoint: QuickerDiscoveryEndpoint,
        timeoutMillis: Long,
    ): Boolean = try {
        withTimeoutOrNull(timeoutMillis) { endpointProbe.probe(endpoint) } == true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        false
    }
}

class QuickerWebSocketEndpointProbe internal constructor(
    private val socketFactory: WebSocket.Factory,
) : QuickerEndpointProbe {
    constructor() : this(sharedClient)

    override suspend fun probe(endpoint: QuickerDiscoveryEndpoint): Boolean =
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            val socketReference = AtomicReference<WebSocket?>()

            fun complete(result: Boolean) {
                if (completed.compareAndSet(false, true)) continuation.resume(result)
            }

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    complete(true)
                    webSocket.close(1000, "Discovery complete")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    complete(false)
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    complete(false)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    complete(false)
                }
            }

            continuation.invokeOnCancellation {
                completed.compareAndSet(false, true)
                socketReference.get()?.cancel()
            }
            val socket = runCatching {
                socketFactory.newWebSocket(
                    Request.Builder().url(endpoint.url).build(),
                    listener,
                )
            }.getOrElse {
                complete(false)
                return@suspendCancellableCoroutine
            }
            socketReference.set(socket)
            if (continuation.isCancelled) socket.cancel()
        }

    companion object {
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(QuickerDiscoveryLimits.HARD_MAX_PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .proxy(Proxy.NO_PROXY)
                .dns(QuickerLanDns)
                .build()
        }
    }
}
