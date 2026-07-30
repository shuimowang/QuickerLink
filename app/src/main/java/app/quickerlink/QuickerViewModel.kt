package app.quickerlink

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.quickerlink.connection.QuickerConnectionConfig
import app.quickerlink.connection.QuickerConnectionManager
import app.quickerlink.connection.QuickerConnectionState
import app.quickerlink.connection.AndroidIpv4SubnetProvider
import app.quickerlink.connection.QuickerEndpoint
import app.quickerlink.connection.QuickerEventDirection
import app.quickerlink.connection.QuickerIncomingCommand
import app.quickerlink.connection.QuickerDiscoveryRequest
import app.quickerlink.connection.QuickerLanDiscovery
import app.quickerlink.connection.QuickerPairingCode
import app.quickerlink.connection.QuickerProtocol
import app.quickerlink.connection.QuickerWebSocketEndpointProbe
import app.quickerlink.data.AppPreferences
import app.quickerlink.data.PreferenceWriteResult
import app.quickerlink.data.QuickerPreferences
import app.quickerlink.data.SavedAction
import app.quickerlink.data.StoredConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class EventLog(
    val time: String,
    val direction: QuickerEventDirection,
    val text: String,
)

sealed interface QuickerDiscoveryState {
    data object Idle : QuickerDiscoveryState
    data class Scanning(val subnet: String) : QuickerDiscoveryState
    data class Failed(val reason: String) : QuickerDiscoveryState
}

data class QuickerUiState(
    val ipAddress: String = "",
    val port: String = "668",
    val password: String = "",
    val rememberPassword: Boolean = false,
    val connectionState: QuickerConnectionState = QuickerConnectionState.Disconnected,
    val discoveryState: QuickerDiscoveryState = QuickerDiscoveryState.Idle,
    val connectionError: String? = null,
    val localNetworkPermissionGranted: Boolean = true,
    val localNetworkPermissionPermanentlyDenied: Boolean = false,
    val savedActions: List<SavedAction> = emptyList(),
    val runningActionIds: Set<String> = emptySet(),
    val logs: List<EventLog> = emptyList(),
)

sealed interface UiNotice {
    data class Success(val message: String) : UiNotice
    data class Error(val message: String) : UiNotice
}

internal class ConnectionSession(initialConfig: QuickerConnectionConfig?) {
    private var reconnectConfig = initialConfig
    private var reconnectOnForeground = initialConfig != null
    private var pendingPersistence: StoredConnection? = null

    fun beginUserConnection(config: QuickerConnectionConfig, connectionToPersist: StoredConnection) {
        reconnectConfig = config
        reconnectOnForeground = true
        pendingPersistence = connectionToPersist
    }

    fun connectionStartRejected() {
        reconnectConfig = null
        reconnectOnForeground = false
        pendingPersistence = null
    }

    fun replaceSavedConnection(config: QuickerConnectionConfig?) {
        reconnectConfig = config
        reconnectOnForeground = config != null
    }

    fun onBackground(state: QuickerConnectionState): Boolean {
        val active = state is QuickerConnectionState.Connecting ||
            state is QuickerConnectionState.Authenticating ||
            state is QuickerConnectionState.Reconnecting ||
            state is QuickerConnectionState.Ready
        if (active) reconnectOnForeground = reconnectConfig != null
        return active
    }

    fun connectionForForeground(
        hasPermission: Boolean,
        state: QuickerConnectionState,
    ): QuickerConnectionConfig? = reconnectConfig.takeIf {
        reconnectOnForeground && hasPermission && state is QuickerConnectionState.Disconnected
    }

    fun takeAuthenticatedConnection(): StoredConnection? {
        reconnectOnForeground = reconnectConfig != null
        return pendingPersistence.also { pendingPersistence = null }
    }

    fun onAuthenticationFailed() {
        reconnectConfig = null
        reconnectOnForeground = false
        pendingPersistence = null
    }

    fun onUserDisconnect() {
        reconnectConfig = null
        reconnectOnForeground = false
        pendingPersistence = null
    }
}

internal fun QuickerUiState.startRunningAction(actionId: String): QuickerUiState? =
    if (actionId in runningActionIds) null else copy(runningActionIds = runningActionIds + actionId)

internal fun QuickerUiState.finishRunningAction(actionId: String): QuickerUiState =
    copy(runningActionIds = runningActionIds - actionId)

private fun StoredConnection.toReconnectConfigOrNull(): QuickerConnectionConfig? {
    if (ipAddress.isBlank() || (requiresPassword && password.isEmpty())) return null
    return QuickerConnectionConfig(ipAddress, port, password)
}

class QuickerViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences: QuickerPreferences = AppPreferences(application)
    private val connectionManager = QuickerConnectionManager()
    private val subnetProvider = AndroidIpv4SubnetProvider(application)
    private val lanDiscovery = QuickerLanDiscovery(QuickerWebSocketEndpointProbe())
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val runningActionsLock = Any()

    private var knownGoodConnection = preferences.loadConnection()
    private val connectionSession = ConnectionSession(knownGoodConnection.toReconnectConfigOrNull())
    private var appInForeground = false
    private var discoveryJob: Job? = null
    private val mutableUiState = MutableStateFlow(
        QuickerUiState(
            ipAddress = knownGoodConnection.ipAddress,
            port = knownGoodConnection.port.toString(),
            password = knownGoodConnection.password,
            rememberPassword = knownGoodConnection.rememberPassword,
            savedActions = preferences.loadActions(),
        ),
    )
    val uiState: StateFlow<QuickerUiState> = mutableUiState.asStateFlow()

    private val mutableNotices = MutableSharedFlow<UiNotice>(extraBufferCapacity = 8)
    val notices: SharedFlow<UiNotice> = mutableNotices.asSharedFlow()

    init {
        viewModelScope.launch {
            connectionManager.state.collect { connectionState ->
                handleConnectionState(connectionState)
            }
        }
        viewModelScope.launch {
            connectionManager.events.collect { event -> appendLog(event.direction, event.summary) }
        }
        viewModelScope.launch {
            connectionManager.commands.collect { command -> handleIncomingCommand(command) }
        }
    }

    fun updateIpAddress(value: String) = updateConnectionFields {
        copy(
            ipAddress = value,
            connectionError = null,
            discoveryState = QuickerDiscoveryState.Idle,
        )
    }

    fun updatePort(value: String) = updateConnectionFields {
        copy(port = value.filter(Char::isDigit).take(5), connectionError = null)
    }

    fun updatePassword(value: String) = updateConnectionFields { copy(password = value, connectionError = null) }

    fun updateRememberPassword(value: Boolean) {
        if (value) {
            updateConnectionFields { copy(rememberPassword = true, connectionError = null) }
            return
        }

        when (val result = preferences.clearRememberedPassword()) {
            PreferenceWriteResult.Success -> {
                knownGoodConnection = knownGoodConnection.copy(rememberPassword = false, password = "")
                connectionSession.replaceSavedConnection(knownGoodConnection.toReconnectConfigOrNull())
                updateConnectionFields { copy(rememberPassword = false, connectionError = null) }
            }

            is PreferenceWriteResult.Failure -> {
                mutableUiState.update { it.copy(connectionError = result.message) }
                mutableNotices.tryEmit(UiNotice.Error(result.message))
            }
        }
    }

    fun onLocalNetworkPermissionStatus(granted: Boolean) {
        mutableUiState.update {
            it.copy(
                localNetworkPermissionGranted = granted,
                localNetworkPermissionPermanentlyDenied = if (granted) false else {
                    it.localNetworkPermissionPermanentlyDenied
                },
            )
        }
        handlePermissionStateChange(granted)
    }

    fun onLocalNetworkPermissionResult(granted: Boolean, permanentlyDenied: Boolean) {
        mutableUiState.update {
            it.copy(
                localNetworkPermissionGranted = granted,
                localNetworkPermissionPermanentlyDenied = !granted && permanentlyDenied,
            )
        }
        handlePermissionStateChange(granted)
    }

    fun onAppForegrounded() {
        appInForeground = true
        resumeConnectionIfEligible()
    }

    fun onAppBackgrounded() {
        appInForeground = false
        cancelDiscovery()
        if (connectionSession.onBackground(connectionManager.state.value)) {
            connectionManager.disconnect()
        }
    }

    fun connect() {
        if (!appInForeground) return
        val state = mutableUiState.value
        if (!state.localNetworkPermissionGranted) {
            mutableNotices.tryEmit(UiNotice.Error("需要局域网访问权限"))
            return
        }

        val port = state.port.toIntOrNull()
        if (port == null) {
            mutableUiState.update { it.copy(connectionError = "请输入有效端口") }
            return
        }

        val config = QuickerConnectionConfig(
            ipAddress = state.ipAddress.trim(),
            port = port,
            password = state.password,
        )
        startConnection(config, state.rememberPassword)
    }

    fun discoverAndConnect() {
        if (!appInForeground) return
        val state = mutableUiState.value
        if (!state.localNetworkPermissionGranted) {
            mutableNotices.tryEmit(UiNotice.Error("需要局域网访问权限"))
            return
        }

        val port = state.port.toIntOrNull()
        if (port == null || port !in 1..65535) {
            mutableUiState.update { it.copy(connectionError = "请输入有效端口") }
            return
        }
        val subnet = runCatching { subnetProvider.currentSubnet() }.getOrNull()
        if (subnet == null) {
            val message = "未找到可用的局域网，请连接 Wi-Fi 后重试"
            mutableUiState.update { it.copy(discoveryState = QuickerDiscoveryState.Failed(message)) }
            return
        }

        discoveryJob?.cancel()
        mutableUiState.update {
            it.copy(
                discoveryState = QuickerDiscoveryState.Scanning(subnet.toString()),
                connectionError = null,
            )
        }
        discoveryJob = viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    lanDiscovery.discover(
                        QuickerDiscoveryRequest(
                            subnet = subnet,
                            port = port,
                        ),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                val message = "查找失败，请扫描配对码或检查高级设置"
                mutableUiState.update { it.copy(discoveryState = QuickerDiscoveryState.Failed(message)) }
                appendLog(QuickerEventDirection.SYSTEM, message)
                return@launch
            }
            if (!isActive) return@launch

            if (result.timedOut) {
                val message = "查找超时，请扫描配对码或检查 WSS 端口"
                mutableUiState.update { it.copy(discoveryState = QuickerDiscoveryState.Failed(message)) }
                appendLog(QuickerEventDirection.SYSTEM, message)
                return@launch
            }
            if (result.endpoints.isEmpty()) {
                val message = "未找到 Quicker，请扫描配对码或检查 WSS 端口"
                mutableUiState.update { it.copy(discoveryState = QuickerDiscoveryState.Failed(message)) }
                appendLog(QuickerEventDirection.SYSTEM, message)
                return@launch
            }
            if (result.endpoints.size > 1) {
                val message = "发现多台候选电脑，请扫描配对码或在高级设置中填写 IPv4"
                mutableUiState.update { it.copy(discoveryState = QuickerDiscoveryState.Failed(message)) }
                appendLog(QuickerEventDirection.SYSTEM, message)
                return@launch
            }

            val endpoint = result.endpoints.single()
            mutableUiState.update {
                it.copy(
                    ipAddress = endpoint.ipAddress,
                    discoveryState = QuickerDiscoveryState.Idle,
                )
            }
            appendLog(QuickerEventDirection.SYSTEM, "已发现 Quicker WSS：${endpoint.ipAddress}")
            startConnection(
                config = QuickerConnectionConfig(endpoint.ipAddress, endpoint.port, state.password),
                rememberPassword = state.rememberPassword,
            )
        }
    }

    fun connectFromPairingCode(payload: String) {
        if (!appInForeground) return
        if (!mutableUiState.value.localNetworkPermissionGranted) {
            mutableNotices.tryEmit(UiNotice.Error("需要局域网访问权限"))
            return
        }
        val pairing = runCatching { QuickerPairingCode.parse(payload) }
            .getOrElse { error ->
                mutableNotices.tryEmit(UiNotice.Error(error.message ?: "无法识别配对码"))
                return
            }
        val state = mutableUiState.value
        mutableUiState.update {
            it.copy(
                ipAddress = pairing.ipAddress,
                port = pairing.port.toString(),
                password = pairing.password,
                discoveryState = QuickerDiscoveryState.Idle,
                connectionError = null,
            )
        }
        startConnection(
            config = QuickerConnectionConfig(pairing.ipAddress, pairing.port, pairing.password),
            rememberPassword = state.rememberPassword,
        )
    }

    fun disconnect() {
        if (discoveryJob?.isActive == true) {
            cancelDiscovery()
            return
        }
        connectionSession.onUserDisconnect()
        mutableUiState.update { it.copy(connectionError = null) }
        connectionManager.disconnect()
    }

    fun saveAction(action: SavedAction) {
        require(action.label.isNotBlank()) { "显示名称不能为空" }
        require(action.actionTarget.isNotBlank()) { "动作名称或 ID 不能为空" }

        mutableUiState.update { state ->
            val existingIndex = state.savedActions.indexOfFirst { it.id == action.id }
            val updated = if (existingIndex >= 0) {
                state.savedActions.toMutableList().apply { set(existingIndex, action) }
            } else {
                state.savedActions + action
            }
            preferences.saveActions(updated)
            state.copy(savedActions = updated)
        }
    }

    fun deleteAction(action: SavedAction) {
        mutableUiState.update { state ->
            val updated = state.savedActions.filterNot { it.id == action.id }
            preferences.saveActions(updated)
            state.copy(savedActions = updated)
        }
    }

    fun runAction(action: SavedAction) {
        if (mutableUiState.value.connectionState !is QuickerConnectionState.Ready) {
            mutableNotices.tryEmit(UiNotice.Error("请先连接 Quicker"))
            return
        }
        if (!reserveAction(action.id)) {
            mutableNotices.tryEmit(UiNotice.Error("“${action.label}”正在执行"))
            return
        }

        viewModelScope.launch {
            try {
                runCatching {
                    connectionManager.sendCommand(
                        operation = "action",
                        action = action.actionTarget,
                        data = action.parameter.takeIf(String::isNotEmpty),
                        wait = true,
                    )
                }.onSuccess { response ->
                    if (response.isSuccess == false) {
                        mutableNotices.emit(
                            UiNotice.Error(
                                response.message ?: QuickerProtocol.displayData(response.data) ?: "动作执行失败",
                            ),
                        )
                    } else {
                        val result = QuickerProtocol.displayData(response.data)?.takeIf(String::isNotBlank)
                        mutableNotices.emit(UiNotice.Success(result ?: "“${action.label}”已执行"))
                    }
                }.onFailure { error ->
                    mutableNotices.emit(UiNotice.Error(error.message ?: "动作执行失败"))
                }
            } finally {
                mutableUiState.update { it.finishRunningAction(action.id) }
            }
        }
    }

    fun sendText(operation: String, text: String) {
        if (text.isEmpty()) {
            mutableNotices.tryEmit(UiNotice.Error("请输入内容"))
            return
        }
        if (mutableUiState.value.connectionState !is QuickerConnectionState.Ready) {
            mutableNotices.tryEmit(UiNotice.Error("请先连接 Quicker"))
            return
        }

        viewModelScope.launch {
            runCatching {
                connectionManager.sendCommand(operation = operation, data = text, wait = true)
            }.onSuccess { response ->
                if (response.isSuccess == false) {
                    mutableNotices.emit(UiNotice.Error(response.message ?: "发送失败"))
                } else {
                    mutableNotices.emit(UiNotice.Success("已发送到电脑"))
                }
            }.onFailure { error ->
                mutableNotices.emit(UiNotice.Error(error.message ?: "发送失败"))
            }
        }
    }

    fun clearLogs() = mutableUiState.update { it.copy(logs = emptyList()) }

    private suspend fun handleConnectionState(connectionState: QuickerConnectionState) {
        val errorMessage = when (connectionState) {
            is QuickerConnectionState.AuthFailed -> connectionState.reason
            is QuickerConnectionState.Error -> connectionState.reason
            is QuickerConnectionState.Reconnecting -> "连接中断，正在重试：${connectionState.reason}"
            else -> null
        }
        mutableUiState.update { state ->
            state.copy(
                connectionState = connectionState,
                connectionError = when {
                    errorMessage != null -> errorMessage
                    connectionState is QuickerConnectionState.Connecting ||
                        connectionState is QuickerConnectionState.Authenticating ||
                        connectionState is QuickerConnectionState.Ready -> null
                    else -> state.connectionError
                },
            )
        }

        when (connectionState) {
            is QuickerConnectionState.Ready -> persistAuthenticatedConnection()
            is QuickerConnectionState.AuthFailed,
            is QuickerConnectionState.Error,
            -> connectionSession.onAuthenticationFailed()

            else -> Unit
        }
    }

    private suspend fun persistAuthenticatedConnection() {
        val connection = connectionSession.takeAuthenticatedConnection() ?: return
        when (val result = withContext(Dispatchers.IO) { preferences.saveConnection(connection) }) {
            PreferenceWriteResult.Success -> knownGoodConnection = connection
            is PreferenceWriteResult.Failure -> {
                val message = "已连接，但${result.message}；原有连接设置未更改"
                mutableUiState.update { it.copy(connectionError = message) }
                mutableNotices.emit(UiNotice.Error(message))
            }
        }
    }

    private fun startConnection(config: QuickerConnectionConfig, rememberPassword: Boolean) {
        val connectionToPersist = StoredConnection(
            ipAddress = config.ipAddress,
            port = config.port,
            rememberPassword = rememberPassword,
            password = config.password,
            requiresPassword = config.password.isNotEmpty(),
        )
        runCatching {
            QuickerEndpoint.url(config)
            connectionSession.beginUserConnection(config, connectionToPersist)
            connectionManager.connect(config)
        }.onFailure { error ->
            connectionSession.connectionStartRejected()
            mutableUiState.update { it.copy(connectionError = error.message ?: "连接设置不正确") }
        }
    }

    private fun cancelDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        mutableUiState.update { state ->
            if (state.discoveryState is QuickerDiscoveryState.Scanning) {
                state.copy(discoveryState = QuickerDiscoveryState.Idle)
            } else {
                state
            }
        }
    }

    private fun handleIncomingCommand(incomingCommand: QuickerIncomingCommand) {
        if (!appInForeground || !connectionManager.isCommandCurrent(incomingCommand)) return
        val command = incomingCommand.message
        if (command.operation == "copy") {
            val text = QuickerProtocol.displayData(command.data).orEmpty()
            val clipboard = getApplication<Application>()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Quicker", text))
            connectionManager.replyToCommand(incomingCommand, true, "ok")
            mutableNotices.tryEmit(UiNotice.Success("已复制 Quicker 发来的文本"))
        }
    }

    private fun resumeConnectionIfEligible() {
        if (!appInForeground) return
        val state = mutableUiState.value
        val config = connectionSession.connectionForForeground(
            hasPermission = state.localNetworkPermissionGranted,
            state = connectionManager.state.value,
        ) ?: return

        runCatching { connectionManager.connect(config) }
            .onFailure { error ->
                connectionSession.connectionStartRejected()
                mutableUiState.update { it.copy(connectionError = error.message ?: "无法恢复连接") }
            }
    }

    private fun handlePermissionStateChange(granted: Boolean) {
        if (granted) {
            resumeConnectionIfEligible()
        } else if (connectionSession.onBackground(connectionManager.state.value)) {
            connectionManager.disconnect()
        }
    }

    private fun reserveAction(actionId: String): Boolean = synchronized(runningActionsLock) {
        val started = mutableUiState.value.startRunningAction(actionId) ?: return@synchronized false
        mutableUiState.value = started
        true
    }

    private fun appendLog(direction: QuickerEventDirection, text: String) {
        val log = EventLog(LocalTime.now().format(timeFormatter), direction, text)
        mutableUiState.update { state ->
            state.copy(logs = (listOf(log) + state.logs).take(MAX_LOG_COUNT))
        }
    }

    private inline fun updateConnectionFields(transform: QuickerUiState.() -> QuickerUiState) {
        mutableUiState.update { current -> current.transform() }
    }

    override fun onCleared() {
        discoveryJob?.cancel()
        connectionManager.close()
    }

    private companion object {
        const val MAX_LOG_COUNT = 100
    }
}
