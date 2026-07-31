package app.quickerlink

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
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
import app.quickerlink.connection.QuickerPanelActionCatalog
import app.quickerlink.connection.QuickerPanelActionsProtocol
import app.quickerlink.connection.UnsupportedPanelCatalogVersionException
import app.quickerlink.connection.QuickerWebSocketEndpointProbe
import app.quickerlink.data.AppPreferences
import app.quickerlink.data.PreferenceWriteResult
import app.quickerlink.data.QuickerPreferences
import app.quickerlink.data.SavedAction
import app.quickerlink.data.StoredConnection
import app.quickerlink.update.AppRelease
import app.quickerlink.update.AppUpdateDownloader
import app.quickerlink.update.GitHubUpdateChecker
import app.quickerlink.update.InstallReady
import app.quickerlink.update.UpdateCheckResult
import app.quickerlink.update.UpdateFailure
import app.quickerlink.update.UpdateInstallException
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
    val catalogActionId: String = QuickerPanelActionsProtocol.COMPANION_SHARED_ACTION_ID,
    val syncingPanelActions: Boolean = false,
    val companionActionPromptVisible: Boolean = false,
    val runningActionIds: Set<String> = emptySet(),
    val logs: List<EventLog> = emptyList(),
    val appVersionName: String = BuildConfig.VERSION_NAME,
    val updateState: AppUpdateState = AppUpdateState.Idle,
)

sealed interface UiNotice {
    data class Success(val message: String) : UiNotice
    data class Error(val message: String) : UiNotice
}

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data object UpToDate : AppUpdateState
    data class Available(val release: AppRelease) : AppUpdateState
    data class Downloading(
        val release: AppRelease,
        val percent: Int,
    ) : AppUpdateState
    data class Verifying(val release: AppRelease) : AppUpdateState
    data class ReadyToInstall(val install: InstallReady) : AppUpdateState
    data class Failed(
        val message: String,
        val release: AppRelease? = null,
    ) : AppUpdateState
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

internal fun applyActionEdit(existing: SavedAction?, edited: SavedAction): SavedAction =
    if (existing?.quickerActionId != null) {
        existing.copy(
            parameter = edited.parameter,
            confirmBeforeRun = edited.confirmBeforeRun,
        )
    } else {
        edited
    }

internal fun applyActionSave(
    existing: List<SavedAction>,
    edited: SavedAction,
): List<SavedAction> {
    val existingIndex = existing.indexOfFirst { it.id == edited.id }
    if (existingIndex < 0) {
        return if (edited.quickerActionId == null) existing + edited else existing
    }
    val saved = applyActionEdit(existing[existingIndex], edited)
    return existing.toMutableList().apply { set(existingIndex, saved) }
}

internal fun removeManualAction(
    existing: List<SavedAction>,
    actionId: String,
): List<SavedAction>? {
    val stored = existing.firstOrNull { it.id == actionId } ?: return existing
    if (stored.quickerActionId != null) return null
    return existing.filterNot { it.id == actionId }
}

internal class CompanionActionUnavailableException(message: String) : IllegalStateException(message)

internal data class PanelSyncFailure(
    val message: String,
    val showCompanionActionPrompt: Boolean,
)

internal fun classifyPanelSyncFailure(error: Exception): PanelSyncFailure = when (error) {
    is UnsupportedPanelCatalogVersionException -> PanelSyncFailure(
        message = "Quicker Link 动作版本过旧，请更新后重试",
        showCompanionActionPrompt = true,
    )
    is CompanionActionUnavailableException -> PanelSyncFailure(
        message = "未找到可用的 Quicker Link 动作，请先安装或更新",
        showCompanionActionPrompt = true,
    )
    else -> PanelSyncFailure(
        message = error.message ?: "同步动作失败",
        showCompanionActionPrompt = false,
    )
}

internal fun mergePanelActions(
    existing: List<SavedAction>,
    catalog: QuickerPanelActionCatalog,
): List<SavedAction> {
    val scenesByName = catalog.scenes.groupBy { it.scene }
    require(
        scenesByName.keys == setOf(
            QuickerPanelActionsProtocol.GLOBAL_SCENE,
            QuickerPanelActionsProtocol.COMMON_SCENE,
        ) && scenesByName.values.all { it.size == 1 },
    ) { "动作目录场景不完整" }
    val orderedScenes = listOf(
        scenesByName.getValue(QuickerPanelActionsProtocol.GLOBAL_SCENE).single(),
        scenesByName.getValue(QuickerPanelActionsProtocol.COMMON_SCENE).single(),
    )

    val syncedByActionId = buildMap {
        existing.forEach { action ->
            action.quickerActionId?.lowercase()?.let { actionId ->
                putIfAbsent(actionId, action)
            }
        }
    }
    val seenRemoteActionIds = hashSetOf<String>()
    val synced = orderedScenes.flatMap { scene ->
        scene.actions.mapNotNull { remote ->
            val actionId = remote.id.lowercase()
            if (!seenRemoteActionIds.add(actionId)) return@mapNotNull null

            val previous = syncedByActionId[actionId]
            SavedAction(
                id = previous?.id ?: "quicker:${remote.id}",
                label = remote.title,
                actionTarget = remote.id,
                parameter = previous?.parameter.orEmpty(),
                parameterChoices = remote.parameterChoices,
                confirmBeforeRun = previous?.confirmBeforeRun ?: false,
                quickerActionId = remote.id,
                sourceGroup = remote.group,
                sourceScene = scene.scene,
                icon = remote.icon,
            )
        }
    }
    val manual = existing.filter { it.quickerActionId == null }
    return synced + manual
}

private fun StoredConnection.toReconnectConfigOrNull(): QuickerConnectionConfig? {
    if (ipAddress.isBlank() || (requiresPassword && password.isEmpty())) return null
    return QuickerConnectionConfig(ipAddress, port, password)
}

class QuickerViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences: QuickerPreferences = AppPreferences(application)
    private val connectionManager = QuickerConnectionManager()
    private val updateChecker = GitHubUpdateChecker()
    private val updateDownloader = AppUpdateDownloader(application)
    private val subnetProvider = AndroidIpv4SubnetProvider(application)
    private val lanDiscovery = QuickerLanDiscovery(QuickerWebSocketEndpointProbe())
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val runningActionsLock = Any()

    private var knownGoodConnection = preferences.loadConnection()
    private val connectionSession = ConnectionSession(knownGoodConnection.toReconnectConfigOrNull())
    private var appInForeground = false
    private var discoveryJob: Job? = null
    private var syncPanelActionsAfterConnect = false
    private val mutableUiState = MutableStateFlow(
        QuickerUiState(
            ipAddress = knownGoodConnection.ipAddress,
            port = knownGoodConnection.port.toString(),
            password = knownGoodConnection.password,
            rememberPassword = knownGoodConnection.rememberPassword,
            savedActions = preferences.loadActions(),
            catalogActionId = knownGoodConnection.serviceActionId
                ?: QuickerPanelActionsProtocol.COMPANION_SHARED_ACTION_ID,
        ),
    )
    val uiState: StateFlow<QuickerUiState> = mutableUiState.asStateFlow()

    private val mutableNotices = MutableSharedFlow<UiNotice>(extraBufferCapacity = 8)
    val notices: SharedFlow<UiNotice> = mutableNotices.asSharedFlow()

    private val mutableInstallRequests = MutableSharedFlow<Uri>(extraBufferCapacity = 1)
    val installRequests: SharedFlow<Uri> = mutableInstallRequests.asSharedFlow()

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
                catalogActionId = pairing.serviceActionId
                    ?: QuickerPanelActionsProtocol.COMPANION_SHARED_ACTION_ID,
                discoveryState = QuickerDiscoveryState.Idle,
                connectionError = null,
            )
        }
        syncPanelActionsAfterConnect = true
        startConnection(
            config = QuickerConnectionConfig(pairing.ipAddress, pairing.port, pairing.password),
            rememberPassword = state.rememberPassword,
            serviceActionId = pairing.serviceActionId
                ?: QuickerPanelActionsProtocol.COMPANION_SHARED_ACTION_ID,
        )
    }

    fun disconnect() {
        if (discoveryJob?.isActive == true) {
            cancelDiscovery()
            return
        }
        connectionSession.onUserDisconnect()
        syncPanelActionsAfterConnect = false
        mutableUiState.update { it.copy(connectionError = null) }
        connectionManager.disconnect()
    }

    fun saveAction(action: SavedAction) {
        require(action.label.isNotBlank()) { "显示名称不能为空" }
        require(action.actionTarget.isNotBlank()) { "动作名称或 ID 不能为空" }

        mutableUiState.update { state ->
            val updated = applyActionSave(state.savedActions, action)
            if (updated == state.savedActions) return@update state
            preferences.saveActions(updated)
            state.copy(savedActions = updated)
        }
    }

    fun deleteAction(action: SavedAction) {
        var synchronizedAction = false
        mutableUiState.update { state ->
            val updated = removeManualAction(state.savedActions, action.id)
            if (updated == null) {
                synchronizedAction = true
                return@update state
            }
            synchronizedAction = false
            if (updated == state.savedActions) return@update state
            preferences.saveActions(updated)
            state.copy(savedActions = updated)
        }
        if (synchronizedAction) {
            mutableNotices.tryEmit(UiNotice.Error("同步动作由 Quicker 管理，不能单独删除"))
        }
    }

    fun syncPanelActions() {
        val state = mutableUiState.value
        if (state.connectionState !is QuickerConnectionState.Ready) {
            mutableNotices.tryEmit(UiNotice.Error("请先连接 Quicker"))
            return
        }
        if (state.syncingPanelActions) return

        mutableUiState.update { it.copy(syncingPanelActions = true) }
        viewModelScope.launch {
            try {
                val response = connectionManager.sendCommand(
                    operation = "action",
                    action = state.catalogActionId,
                    data = QuickerPanelActionsProtocol.LIST_COMMAND,
                )
                if (response.isSuccess == false) {
                    throw CompanionActionUnavailableException(
                        response.message
                            ?: QuickerProtocol.displayData(response.data)
                            ?: "Quicker 拒绝读取动作目录",
                    )
                }
                val catalog = QuickerPanelActionsProtocol.parse(response.data)
                mutableUiState.update { current ->
                    val updated = mergePanelActions(current.savedActions, catalog)
                    preferences.saveActions(updated)
                    current.copy(
                        savedActions = updated,
                        companionActionPromptVisible = false,
                    )
                }
                mutableNotices.emit(
                    UiNotice.Success("已同步 ${catalog.actions.size} 个全局与通用动作"),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                val failure = classifyPanelSyncFailure(error)
                mutableUiState.update {
                    it.copy(companionActionPromptVisible = failure.showCompanionActionPrompt)
                }
                mutableNotices.emit(UiNotice.Error(failure.message))
            } finally {
                mutableUiState.update { it.copy(syncingPanelActions = false) }
            }
        }
    }

    fun runAction(action: SavedAction) {
        if (mutableUiState.value.connectionState !is QuickerConnectionState.Ready) {
            mutableNotices.tryEmit(UiNotice.Error("请先连接 Quicker"))
            return
        }
        if (!reserveAction(action.id)) {
            mutableNotices.tryEmit(UiNotice.Error("“${action.label}”正在发送"))
            return
        }

        viewModelScope.launch {
            try {
                connectionManager.dispatchCommand(
                    operation = "action",
                    action = action.actionTarget,
                    data = action.parameter.takeIf(String::isNotEmpty),
                )
                mutableNotices.emit(UiNotice.Success("“${action.label}”已发送"))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                mutableNotices.emit(UiNotice.Error(error.message ?: "动作发送失败"))
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
                connectionManager.sendCommand(operation = operation, data = text)
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

    fun dismissCompanionActionPrompt() = mutableUiState.update {
        it.copy(companionActionPromptVisible = false)
    }

    fun checkForUpdates() {
        when (mutableUiState.value.updateState) {
            AppUpdateState.Checking,
            is AppUpdateState.Downloading,
            is AppUpdateState.Verifying,
            -> return

            else -> Unit
        }
        mutableUiState.update { it.copy(updateState = AppUpdateState.Checking) }

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    updateChecker.check(BuildConfig.VERSION_NAME)
                }
                mutableUiState.update { state ->
                    state.copy(
                        updateState = when (result) {
                            is UpdateCheckResult.Available -> AppUpdateState.Available(result.release)
                            is UpdateCheckResult.UpToDate -> AppUpdateState.UpToDate
                        },
                    )
                }
                if (result is UpdateCheckResult.UpToDate) {
                    mutableNotices.emit(UiNotice.Success("当前已是最新版本"))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                val message = "暂时无法连接 GitHub，请稍后重试"
                mutableUiState.update { it.copy(updateState = AppUpdateState.Failed(message)) }
                mutableNotices.emit(UiNotice.Error(message))
            }
        }
    }

    fun downloadAndInstallUpdate() {
        val release = when (val updateState = mutableUiState.value.updateState) {
            is AppUpdateState.Available -> updateState.release
            is AppUpdateState.Failed -> updateState.release
            else -> null
        } ?: return

        mutableUiState.update {
            it.copy(updateState = AppUpdateState.Downloading(release, percent = 0))
        }
        viewModelScope.launch {
            try {
                val install = withContext(Dispatchers.IO) {
                    updateDownloader.downloadAndVerify(release) { progress ->
                        val percent = (progress.fraction * 100f).toInt().coerceIn(0, 100)
                        mutableUiState.update { state ->
                            state.copy(
                                updateState = if (percent >= 100) {
                                    AppUpdateState.Verifying(release)
                                } else {
                                    AppUpdateState.Downloading(release, percent)
                                },
                            )
                        }
                    }
                }
                mutableUiState.update {
                    it.copy(updateState = AppUpdateState.ReadyToInstall(install))
                }
                mutableNotices.emit(UiNotice.Success("安装包校验通过，正在打开系统安装器"))
                mutableInstallRequests.emit(install.contentUri)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: UpdateInstallException) {
                val message = updateFailureMessage(error.failure)
                mutableUiState.update {
                    it.copy(updateState = AppUpdateState.Failed(message, release))
                }
                mutableNotices.emit(UiNotice.Error(message))
            } catch (_: Exception) {
                val message = "更新失败，请稍后重试"
                mutableUiState.update {
                    it.copy(updateState = AppUpdateState.Failed(message, release))
                }
                mutableNotices.emit(UiNotice.Error(message))
            }
        }
    }

    fun requestUpdateInstallation() {
        val install = (mutableUiState.value.updateState as? AppUpdateState.ReadyToInstall)?.install ?: return
        mutableInstallRequests.tryEmit(install.contentUri)
    }

    fun reportInstallerError(message: String) {
        mutableNotices.tryEmit(UiNotice.Error(message))
    }

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
            is QuickerConnectionState.Ready -> {
                persistAuthenticatedConnection()
                if (syncPanelActionsAfterConnect) {
                    syncPanelActionsAfterConnect = false
                    syncPanelActions()
                }
            }
            is QuickerConnectionState.AuthFailed,
            is QuickerConnectionState.Error,
            -> {
                syncPanelActionsAfterConnect = false
                connectionSession.onAuthenticationFailed()
            }

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

    private fun startConnection(
        config: QuickerConnectionConfig,
        rememberPassword: Boolean,
        serviceActionId: String = mutableUiState.value.catalogActionId,
    ) {
        val connectionToPersist = StoredConnection(
            ipAddress = config.ipAddress,
            port = config.port,
            rememberPassword = rememberPassword,
            password = config.password,
            requiresPassword = config.password.isNotEmpty(),
            serviceActionId = serviceActionId,
        )
        runCatching {
            QuickerEndpoint.url(config)
            connectionSession.beginUserConnection(config, connectionToPersist)
            connectionManager.connect(config)
        }.onFailure { error ->
            syncPanelActionsAfterConnect = false
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
        updateChecker.close()
        updateDownloader.close()
    }

    private companion object {
        const val MAX_LOG_COUNT = 100
    }
}

internal fun updateFailureMessage(failure: UpdateFailure): String = when (failure) {
    UpdateFailure.Network -> "下载失败，请检查网络后重试"
    UpdateFailure.DownloadTooLarge -> "安装包大小异常，已停止更新"
    UpdateFailure.InvalidChecksum,
    UpdateFailure.ChecksumMismatch,
    -> "安装包校验失败，已停止更新"

    UpdateFailure.WrongPackage,
    UpdateFailure.VersionMismatch,
    UpdateFailure.SignatureMismatch,
    UpdateFailure.InvalidApk,
    -> "安装包身份验证失败，已停止更新"

    UpdateFailure.Storage -> "无法保存安装包，请清理存储空间后重试"
    UpdateFailure.InvalidRelease,
    UpdateFailure.UntrustedUrl,
    UpdateFailure.ContentUri,
    -> "发布文件不符合安全要求，已停止更新"
}
