package app.quickerlink

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.quickerlink.connection.QuickerConnectionConfig
import app.quickerlink.connection.QuickerConnectionBinding
import app.quickerlink.connection.QuickerConnectionManager
import app.quickerlink.connection.QuickerConnectionState
import app.quickerlink.connection.QuickerActionControlProtocol
import app.quickerlink.connection.AndroidIpv4SubnetProvider
import app.quickerlink.connection.QuickerEndpoint
import app.quickerlink.connection.QuickerEventDirection
import app.quickerlink.connection.QuickerIncomingCommand
import app.quickerlink.connection.QuickerDiscoveryRequest
import app.quickerlink.connection.QuickerLanDiscovery
import app.quickerlink.connection.QuickerMessage
import app.quickerlink.connection.QuickerPairingCode
import app.quickerlink.connection.QuickerProtocol
import app.quickerlink.connection.QuickerPanelActionCatalog
import app.quickerlink.connection.QuickerPanelActionsProtocol
import app.quickerlink.connection.QuickerToolboxProtocol
import app.quickerlink.connection.QuickerToolboxRemoteException
import app.quickerlink.connection.QuickerToolboxResult
import app.quickerlink.connection.QuickerTransferDescriptor
import app.quickerlink.connection.UnsupportedPanelCatalogVersionException
import app.quickerlink.connection.UnsupportedActionControlVersionException
import app.quickerlink.connection.UnsupportedToolboxVersionException
import app.quickerlink.connection.compactLogText
import app.quickerlink.connection.QuickerWebSocketEndpointProbe
import app.quickerlink.data.AppPreferences
import app.quickerlink.data.PreferenceWriteResult
import app.quickerlink.data.QuickerPreferences
import app.quickerlink.data.SavedAction
import app.quickerlink.data.StoredConnection
import app.quickerlink.transfer.AndroidTransferStore
import app.quickerlink.transfer.PreparedUpload
import app.quickerlink.transfer.ScreenPreview
import app.quickerlink.update.AppRelease
import app.quickerlink.update.AppUpdateDownloader
import app.quickerlink.update.GitHubUpdateChecker
import app.quickerlink.update.InstallReady
import app.quickerlink.update.UpdateCheckResult
import app.quickerlink.update.UpdateFailure
import app.quickerlink.update.UpdateInstallException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
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

enum class ToolboxTask {
    CLIPBOARD,
    SCREEN,
    RECEIVE_FILE,
    SEND_FILE,
    SAVE_SCREEN,
}

sealed interface ToolboxStatus {
    data object Idle : ToolboxStatus

    data class Working(
        val task: ToolboxTask,
        val title: String,
        val detail: String,
        val percent: Int? = null,
        val canCancel: Boolean = false,
    ) : ToolboxStatus

    data class Success(
        val task: ToolboxTask,
        val title: String,
        val detail: String,
    ) : ToolboxStatus

    data class Failed(
        val task: ToolboxTask,
        val title: String,
        val message: String,
        val canRetry: Boolean = false,
    ) : ToolboxStatus
}

private data class PendingUploadConfirmation(
    val transferId: String,
    val fileName: String,
    val connection: QuickerConnectionBinding,
    val actionId: String,
)

data class ScreenPreviewState(
    val path: String,
    val name: String,
    val capturedAt: String,
    val savedLocation: String? = null,
)

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
    val toolboxText: String = "",
    val toolboxStatus: ToolboxStatus = ToolboxStatus.Idle,
    val screenPreview: ScreenPreviewState? = null,
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

internal fun actionLogIdentity(action: SavedAction): String {
    val label = compactLogText(action.label, 80)
    val target = compactLogText(action.actionTarget, 80)
    return if (label == target) label else "$label（$target）"
}

internal fun boundedUiErrorMessage(message: String?, fallback: String): String {
    require(fallback.isNotBlank())
    return compactLogText(
        message?.takeIf(String::isNotBlank) ?: fallback,
        MAX_UI_ERROR_MESSAGE_LENGTH,
    )
}

internal fun webSocketCommandFailureMessage(
    response: QuickerMessage,
    fallback: String,
): String = boundedUiErrorMessage(response.message, fallback)

internal fun transferPercent(transferred: Long, total: Long): Int {
    require(transferred >= 0 && total >= 0 && transferred <= total)
    return if (total == 0L) 100 else ((transferred * 100L) / total).toInt().coerceIn(0, 100)
}

internal fun formatTransferBytes(bytes: Long): String {
    require(bytes >= 0)
    return when {
        bytes >= 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024.0)
        else -> "$bytes B"
    }
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
        message = boundedUiErrorMessage(error.message, "同步动作失败"),
        showCompanionActionPrompt = false,
    )
}

private const val MAX_UI_ERROR_MESSAGE_LENGTH = 180

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
    private val transferStore = AndroidTransferStore(application)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val runningActionsLock = Any()

    private var knownGoodConnection = preferences.loadConnection()
    private val connectionSession = ConnectionSession(knownGoodConnection.toReconnectConfigOrNull())
    private var appInForeground = false
    private var discoveryJob: Job? = null
    private var syncPanelActionsAfterConnect = false
    private var toolboxJob: Job? = null
    private var activeTransferId: String? = null
    private var suppressRemoteTransferCancel = false
    private var pendingUploadConfirmation: PendingUploadConfirmation? = null
    private var toolboxCancellationRequested = false
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
                    logSummary = "同步面板动作：Quicker Link（${state.catalogActionId}）",
                    logResponse = false,
                )
                if (response.isSuccess == false) {
                    throw CompanionActionUnavailableException(
                        webSocketCommandFailureMessage(
                            response,
                            "Quicker 拒绝读取动作目录",
                        ),
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
                appendLog(
                    QuickerEventDirection.INCOMING,
                    "已同步 ${catalog.actions.size} 个全局与通用动作",
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                val failure = classifyPanelSyncFailure(error)
                mutableUiState.update {
                    it.copy(companionActionPromptVisible = failure.showCompanionActionPrompt)
                }
                appendLog(
                    QuickerEventDirection.SYSTEM,
                    "同步面板动作失败：${compactLogText(failure.message)}",
                )
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
                    logSummary = "发送动作：${actionLogIdentity(action)}",
                )
                mutableNotices.emit(UiNotice.Success("“${action.label}”已发送"))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                mutableNotices.emit(
                    UiNotice.Error(boundedUiErrorMessage(error.message, "动作发送失败")),
                )
            } finally {
                mutableUiState.update { it.finishRunningAction(action.id) }
            }
        }
    }

    fun stopAction(action: SavedAction) {
        val quickerActionId = action.quickerActionId
        if (quickerActionId == null) {
            mutableNotices.tryEmit(UiNotice.Error("只有从 Quicker 同步的动作支持终止"))
            return
        }
        if (mutableUiState.value.connectionState !is QuickerConnectionState.Ready) {
            mutableNotices.tryEmit(UiNotice.Error("请先连接 Quicker"))
            return
        }
        if (!reserveAction(action.id)) {
            mutableNotices.tryEmit(UiNotice.Error("“${action.label}”正在处理"))
            return
        }

        val companionActionId = mutableUiState.value.catalogActionId
        val identity = actionLogIdentity(action)
        viewModelScope.launch {
            try {
                val response = connectionManager.sendCommand(
                    operation = "action",
                    action = companionActionId,
                    data = QuickerActionControlProtocol.stopCommand(quickerActionId),
                    logSummary = "终止动作：$identity",
                    logResponse = false,
                )
                if (response.isSuccess == false) {
                    throw IllegalStateException(
                        webSocketCommandFailureMessage(
                            response,
                            "Quicker 拒绝终止动作",
                        ),
                    )
                }
                QuickerActionControlProtocol.parseStopResponse(response.data, quickerActionId)
                appendLog(QuickerEventDirection.INCOMING, "已终止动作：$identity")
                mutableNotices.emit(UiNotice.Success("已终止“${action.label}”"))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                val outdated = error is UnsupportedActionControlVersionException
                val message = if (outdated) {
                    "Quicker Link 动作版本过旧，请更新后重试"
                } else {
                    boundedUiErrorMessage(error.message, "终止动作失败")
                }
                if (outdated) {
                    mutableUiState.update { it.copy(companionActionPromptVisible = true) }
                }
                appendLog(
                    QuickerEventDirection.SYSTEM,
                    "终止动作失败：$identity · ${compactLogText(message)}",
                )
                mutableNotices.emit(UiNotice.Error(message))
            } finally {
                mutableUiState.update { it.finishRunningAction(action.id) }
            }
        }
    }

    fun updateToolboxText(value: String) {
        mutableUiState.update { it.copy(toolboxText = value.take(QuickerToolboxProtocol.MAX_CLIPBOARD_CHARS)) }
    }

    fun sendToolboxText() {
        val text = mutableUiState.value.toolboxText
        if (text.isEmpty()) {
            mutableNotices.tryEmit(UiNotice.Error("请输入要发送的文本"))
            return
        }
        if (text.toByteArray(Charsets.UTF_8).size > MAX_TOOLBOX_TEXT_BYTES) {
            mutableNotices.tryEmit(UiNotice.Error("文本过大，请缩短后重试"))
            return
        }
        launchToolboxTask(
            task = ToolboxTask.CLIPBOARD,
            title = "发送文本",
            detail = "正在写入电脑剪贴板",
        ) {
            val response = connectionManager.sendCommand(
                operation = "copy",
                data = text,
                logSummary = "发送文本到电脑剪贴板",
                logResponse = false,
            )
            if (response.isSuccess != true) {
                throw IllegalStateException(webSocketCommandFailureMessage(response, "电脑拒绝接收文本"))
            }
            mutableUiState.update {
                it.copy(
                    toolboxStatus = ToolboxStatus.Success(
                        ToolboxTask.CLIPBOARD,
                        "文本已发送",
                        "已写入电脑剪贴板",
                    ),
                )
            }
            appendLog(QuickerEventDirection.INCOMING, "文本已写入电脑剪贴板")
            mutableNotices.emit(UiNotice.Success("已发送到电脑剪贴板"))
        }
    }

    fun readComputerClipboard() {
        launchToolboxTask(
            task = ToolboxTask.CLIPBOARD,
            title = "读取剪贴板",
            detail = "正在读取电脑上的文本",
        ) {
            val result = requestToolbox(
                command = QuickerToolboxProtocol.clipboardReadCommand(),
                expectedOperation = QuickerToolboxProtocol.OP_CLIPBOARD_READ,
                logSummary = "读取电脑剪贴板：Quicker Link",
            ) as QuickerToolboxResult.Clipboard
            mutableUiState.update {
                it.copy(
                    toolboxText = result.text,
                    toolboxStatus = ToolboxStatus.Success(
                        ToolboxTask.CLIPBOARD,
                        "已读取电脑剪贴板",
                        if (result.text.isEmpty()) "电脑剪贴板中没有文本" else "文本已放入编辑框",
                    ),
                )
            }
            appendLog(QuickerEventDirection.INCOMING, "已读取电脑剪贴板文本")
        }
    }

    fun captureComputerScreen() {
        launchToolboxTask(
            task = ToolboxTask.SCREEN,
            title = "获取当前屏幕",
            detail = "正在请求电脑生成快照",
        ) {
            val result = requestToolbox(
                command = QuickerToolboxProtocol.screenCaptureCommand(),
                expectedOperation = QuickerToolboxProtocol.OP_SCREEN_CAPTURE,
                logSummary = "获取电脑当前屏幕：Quicker Link",
                timeoutMs = SCREEN_CAPTURE_TIMEOUT_MS,
            ) as QuickerToolboxResult.Transfer
            downloadFromComputer(result.descriptor, ToolboxTask.SCREEN, keepAsScreenPreview = true)
        }
    }

    fun receiveFileFromComputer() {
        launchToolboxTask(
            task = ToolboxTask.RECEIVE_FILE,
            title = "从电脑接收文件",
            detail = "请在电脑上选择一个不超过 8 MiB 的文件",
            canCancel = true,
        ) {
            val result = requestToolbox(
                command = QuickerToolboxProtocol.downloadPickCommand(),
                expectedOperation = QuickerToolboxProtocol.OP_DOWNLOAD_PICK,
                logSummary = "从电脑选择文件：Quicker Link",
                timeoutMs = FILE_PICK_TIMEOUT_MS,
            ) as QuickerToolboxResult.Transfer
            downloadFromComputer(result.descriptor, ToolboxTask.RECEIVE_FILE, keepAsScreenPreview = false)
        }
    }

    fun sendFileToComputer(uri: Uri) {
        launchToolboxTask(
            task = ToolboxTask.SEND_FILE,
            title = "发送文件到电脑",
            detail = "正在读取并校验所选文件",
            canCancel = true,
            requiresConnection = false,
        ) {
            var prepared: PreparedUpload? = null
            try {
                prepared = runInterruptible(Dispatchers.IO) { transferStore.prepareUpload(uri) }
                awaitReadyConnectionForTransfer(prepared.name)
                uploadToComputer(prepared)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    transferStore.delete(prepared?.file)
                }
            }
        }
    }

    fun cancelToolboxTransfer() {
        val status = mutableUiState.value.toolboxStatus as? ToolboxStatus.Working ?: return
        if (!status.canCancel) return
        val job = toolboxJob?.takeIf(Job::isActive) ?: return
        toolboxCancellationRequested = true
        job.cancel(CancellationException("用户取消传输"))
    }

    fun saveScreenToDownloads() {
        val preview = mutableUiState.value.screenPreview
        if (preview == null) {
            mutableNotices.tryEmit(UiNotice.Error("请先获取电脑屏幕"))
            return
        }
        launchToolboxTask(
            task = ToolboxTask.SAVE_SCREEN,
            title = "保存屏幕快照",
            detail = "正在写入下载目录",
            requiresConnection = false,
        ) {
            val saved = withContext(Dispatchers.IO) {
                transferStore.saveToDownloads(File(preview.path), preview.name, "image/jpeg")
            }
            mutableUiState.update { state ->
                state.copy(
                    screenPreview = state.screenPreview?.takeIf { it.path == preview.path }
                        ?.copy(savedLocation = saved.location)
                        ?: state.screenPreview,
                    toolboxStatus = ToolboxStatus.Success(
                        ToolboxTask.SAVE_SCREEN,
                        "屏幕快照已保存",
                        "${saved.location} / ${saved.name}",
                    ),
                )
            }
            mutableNotices.emit(UiNotice.Success("屏幕快照已保存到下载目录"))
        }
    }

    fun clearToolboxStatus() {
        mutableUiState.update { state ->
            if (state.toolboxStatus is ToolboxStatus.Working) {
                state
            } else {
                if ((state.toolboxStatus as? ToolboxStatus.Failed)?.canRetry == true) {
                    pendingUploadConfirmation = null
                }
                state.copy(toolboxStatus = ToolboxStatus.Idle)
            }
        }
    }

    fun retryUploadConfirmation() {
        val pending = pendingUploadConfirmation ?: return
        launchToolboxTask(
            task = ToolboxTask.SEND_FILE,
            title = "确认电脑端文件",
            detail = "正在重新确认 ${pending.fileName}",
        ) {
            activeTransferId = pending.transferId
            suppressRemoteTransferCancel = true
            val saved = finishUploadWithRecovery(pending)
            pendingUploadConfirmation = null
            activeTransferId = null
            suppressRemoteTransferCancel = false
            publishUploadSuccess(saved)
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
                    mutableNotices.emit(
                        UiNotice.Error(webSocketCommandFailureMessage(response, "发送失败")),
                    )
                } else {
                    mutableNotices.emit(UiNotice.Success("已发送到电脑"))
                }
            }.onFailure { error ->
                mutableNotices.emit(
                    UiNotice.Error(boundedUiErrorMessage(error.message, "发送失败")),
                )
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

    private fun launchToolboxTask(
        task: ToolboxTask,
        title: String,
        detail: String,
        canCancel: Boolean = false,
        requiresConnection: Boolean = true,
        block: suspend () -> Unit,
    ) {
        if (requiresConnection && mutableUiState.value.connectionState !is QuickerConnectionState.Ready) {
            mutableNotices.tryEmit(UiNotice.Error("请先连接 Quicker"))
            return
        }
        if (toolboxJob?.isActive == true) {
            mutableNotices.tryEmit(UiNotice.Error("已有传输任务正在进行"))
            return
        }

        toolboxCancellationRequested = false
        suppressRemoteTransferCancel = false
        mutableUiState.update {
            it.copy(toolboxStatus = ToolboxStatus.Working(task, title, detail, canCancel = canCancel))
        }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (cancellation: CancellationException) {
                if (toolboxCancellationRequested) {
                    val waitingForDesktopPicker = task == ToolboxTask.RECEIVE_FILE && activeTransferId == null
                    cancelActiveRemoteTransferBestEffort()
                    mutableUiState.update { it.copy(toolboxStatus = ToolboxStatus.Idle) }
                    val message = if (waitingForDesktopPicker) {
                        "手机已停止等待，请在电脑上关闭文件选择窗口"
                    } else {
                        "已取消文件传输"
                    }
                    appendLog(QuickerEventDirection.SYSTEM, message)
                    mutableNotices.emit(UiNotice.Success(message))
                } else if (currentCoroutineContext().isActive) {
                    handleToolboxFailure(task, title, cancellation)
                } else {
                    throw cancellation
                }
            } catch (error: Exception) {
                handleToolboxFailure(task, title, error)
            } finally {
                activeTransferId = null
                suppressRemoteTransferCancel = false
                toolboxCancellationRequested = false
                toolboxJob = null
            }
        }
        toolboxJob = job
        job.start()
    }

    private suspend fun handleToolboxFailure(task: ToolboxTask, title: String, error: Exception) {
        val definiteRemoteFailure = error is QuickerToolboxRemoteException
        if (definiteRemoteFailure) pendingUploadConfirmation = null
        val confirmationUnknown = task == ToolboxTask.SEND_FILE &&
            pendingUploadConfirmation != null &&
            suppressRemoteTransferCancel &&
            !definiteRemoteFailure
        if (!confirmationUnknown) cancelActiveRemoteTransferBestEffort()
        if (error is QuickerToolboxRemoteException && error.code == "selection_cancelled") {
            mutableUiState.update { it.copy(toolboxStatus = ToolboxStatus.Idle) }
            mutableNotices.emit(UiNotice.Success("已取消选择文件"))
            return
        }

        val requiresActionUpdate = error is UnsupportedToolboxVersionException ||
            error is CompanionActionUnavailableException ||
            error is QuickerToolboxRemoteException && error.code == "unsupported_operation"
        val message = when {
            requiresActionUpdate -> "Quicker Link 动作版本过旧或不可用，请安装最新版后重试"
            confirmationUnknown -> "电脑端保存结果尚未确认。请重新确认；不要直接重复发送"
            error is TimeoutCancellationException -> "等待 Quicker 响应超时，请检查连接后重试"
            else -> boundedUiErrorMessage(error.message, "$title 失败")
        }
        if (requiresActionUpdate) {
            mutableUiState.update { it.copy(companionActionPromptVisible = true) }
        }
        mutableUiState.update {
            it.copy(
                toolboxStatus = ToolboxStatus.Failed(
                    task,
                    if (confirmationUnknown) "保存结果待确认" else "$title 失败",
                    message,
                    canRetry = confirmationUnknown,
                ),
            )
        }
        appendLog(
            QuickerEventDirection.SYSTEM,
            "$title 失败：${compactLogText(message)}",
        )
        mutableNotices.emit(UiNotice.Error(message))
    }

    private suspend fun awaitReadyConnectionForTransfer(fileName: String) {
        if (connectionManager.state.value is QuickerConnectionState.Ready) return
        mutableUiState.update {
            it.copy(
                toolboxStatus = ToolboxStatus.Working(
                    task = ToolboxTask.SEND_FILE,
                    title = "发送文件到电脑",
                    detail = "$fileName · 正在恢复局域网连接",
                    canCancel = true,
                ),
            )
        }
        withTimeout(FILE_RECONNECT_TIMEOUT_MS) {
            connectionManager.state.first { it is QuickerConnectionState.Ready }
        }
    }

    private suspend fun requestToolbox(
        command: String,
        expectedOperation: String,
        logSummary: String? = null,
        logRequest: Boolean = true,
        timeoutMs: Long = TOOLBOX_COMMAND_TIMEOUT_MS,
        actionId: String = mutableUiState.value.catalogActionId,
        expectedConnection: QuickerConnectionBinding? = null,
    ): QuickerToolboxResult {
        val response = connectionManager.sendCommand(
            operation = "action",
            action = actionId,
            data = command,
            timeoutMs = timeoutMs,
            logSummary = logSummary,
            logRequest = logRequest,
            logResponse = false,
            expectedConnection = expectedConnection,
        )
        if (response.isSuccess == false) {
            throw CompanionActionUnavailableException(
                webSocketCommandFailureMessage(response, "Quicker Link 动作不可用"),
            )
        }
        require(response.isSuccess == true) { "Quicker 返回了无效的工具箱状态" }
        return QuickerToolboxProtocol.parse(response.data, expectedOperation)
    }

    private suspend fun uploadToComputer(upload: PreparedUpload) {
        pendingUploadConfirmation = null
        val connection = connectionManager.currentReadyConnectionBinding()
            ?: throw IllegalStateException("尚未连接到 Quicker")
        val actionId = mutableUiState.value.catalogActionId
        updateTransferProgress(
            ToolboxTask.SEND_FILE,
            "发送文件到电脑",
            upload.name,
            transferred = 0,
            total = upload.size,
        )
        val started = requestToolbox(
            command = QuickerToolboxProtocol.uploadBeginCommand(
                upload.name,
                upload.mime,
                upload.size,
                upload.sha256,
            ),
            expectedOperation = QuickerToolboxProtocol.OP_UPLOAD_BEGIN,
            logSummary = "发送文件到电脑：${upload.name}",
            actionId = actionId,
            expectedConnection = connection,
        ) as QuickerToolboxResult.UploadStarted
        require(started.nextOffset == 0L && started.chunkSize == QuickerToolboxProtocol.CHUNK_BYTES) {
            "电脑返回了无效的上传起点"
        }
        activeTransferId = started.transferId

        var offset = 0L
        FileInputStream(upload.file).use { input ->
            while (offset < upload.size) {
                currentCoroutineContext().ensureActive()
                val count = minOf(QuickerToolboxProtocol.CHUNK_BYTES.toLong(), upload.size - offset).toInt()
                val bytes = input.readExactChunk(count)
                val advanced = requestToolbox(
                    command = QuickerToolboxProtocol.uploadChunkCommand(started.transferId, offset, bytes),
                    expectedOperation = QuickerToolboxProtocol.OP_UPLOAD_CHUNK,
                    logRequest = false,
                    actionId = actionId,
                    expectedConnection = connection,
                ) as QuickerToolboxResult.UploadAdvanced
                val expectedOffset = offset + bytes.size
                require(advanced.transferId == started.transferId && advanced.nextOffset == expectedOffset) {
                    "电脑返回了不一致的上传进度"
                }
                offset = expectedOffset
                updateTransferProgress(
                    ToolboxTask.SEND_FILE,
                    "发送文件到电脑",
                    upload.name,
                    transferred = offset,
                    total = upload.size,
                )
            }
            require(input.read() == -1) { "发送期间文件大小发生变化" }
        }

        val pending = PendingUploadConfirmation(
            transferId = started.transferId,
            fileName = upload.name,
            connection = connection,
            actionId = actionId,
        )
        pendingUploadConfirmation = pending
        suppressRemoteTransferCancel = true
        mutableUiState.update {
            it.copy(
                toolboxStatus = ToolboxStatus.Working(
                    task = ToolboxTask.SEND_FILE,
                    title = "发送文件到电脑",
                    detail = "${upload.name} · 正在确认保存",
                    percent = 100,
                    canCancel = false,
                ),
            )
        }
        val saved = finishUploadWithRecovery(pending)
        pendingUploadConfirmation = null
        activeTransferId = null
        suppressRemoteTransferCancel = false
        publishUploadSuccess(saved)
    }

    private suspend fun publishUploadSuccess(saved: QuickerToolboxResult.UploadSaved) {
        mutableUiState.update {
            it.copy(
                toolboxStatus = ToolboxStatus.Success(
                    ToolboxTask.SEND_FILE,
                    "文件已发送到电脑",
                    "${saved.location} / ${saved.savedName}",
                ),
            )
        }
        appendLog(QuickerEventDirection.INCOMING, "文件已发送到电脑：${saved.savedName}")
        mutableNotices.emit(UiNotice.Success("文件已发送到电脑"))
    }

    private suspend fun finishUploadWithRecovery(
        pending: PendingUploadConfirmation,
    ): QuickerToolboxResult.UploadSaved {
        suspend fun finish(): QuickerToolboxResult.UploadSaved = requestToolbox(
            command = QuickerToolboxProtocol.uploadFinishCommand(pending.transferId),
            expectedOperation = QuickerToolboxProtocol.OP_UPLOAD_FINISH,
            logRequest = false,
            actionId = pending.actionId,
            expectedConnection = pending.connection,
        ) as QuickerToolboxResult.UploadSaved

        return try {
            finish()
        } catch (error: Exception) {
            if (
                error is QuickerToolboxRemoteException ||
                error !is TimeoutCancellationException && error !is IllegalStateException
            ) {
                throw error
            }
            currentCoroutineContext().ensureActive()
            mutableUiState.update {
                it.copy(
                    toolboxStatus = ToolboxStatus.Working(
                        task = ToolboxTask.SEND_FILE,
                        title = "发送文件到电脑",
                        detail = "正在确认电脑端保存结果",
                        canCancel = false,
                    ),
                )
            }
            if (connectionManager.state.value !is QuickerConnectionState.Ready) {
                withTimeout(FILE_RECONNECT_TIMEOUT_MS) {
                    connectionManager.state.first { it is QuickerConnectionState.Ready }
                }
            }
            finish()
        }
    }

    private suspend fun downloadFromComputer(
        descriptor: QuickerTransferDescriptor,
        task: ToolboxTask,
        keepAsScreenPreview: Boolean,
    ) {
        if (keepAsScreenPreview) {
            require(descriptor.mime == "image/jpeg") { "电脑返回的屏幕快照类型无效" }
        }
        activeTransferId = descriptor.id
        val part = withContext(Dispatchers.IO) { transferStore.createIncomingPart() }
        var committed = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var offset = 0L
            FileOutputStream(part).use { output ->
                while (offset < descriptor.size) {
                    currentCoroutineContext().ensureActive()
                    val chunk = requestToolbox(
                        command = QuickerToolboxProtocol.downloadChunkCommand(descriptor.id, offset),
                        expectedOperation = QuickerToolboxProtocol.OP_DOWNLOAD_CHUNK,
                        logRequest = false,
                    ) as QuickerToolboxResult.DownloadChunk
                    val expectedCount = minOf(
                        QuickerToolboxProtocol.CHUNK_BYTES.toLong(),
                        descriptor.size - offset,
                    ).toInt()
                    require(
                        chunk.transferId == descriptor.id &&
                            chunk.offset == offset &&
                            chunk.bytes.size == expectedCount,
                    ) { "电脑返回了不一致的文件分块" }
                    val nextOffset = offset + chunk.bytes.size
                    require(chunk.eof == (nextOffset == descriptor.size)) { "电脑返回的文件结束标识无效" }
                    output.write(chunk.bytes)
                    digest.update(chunk.bytes)
                    offset = nextOffset
                    updateTransferProgress(
                        task,
                        if (keepAsScreenPreview) "获取当前屏幕" else "从电脑接收文件",
                        descriptor.name,
                        transferred = offset,
                        total = descriptor.size,
                    )
                }
                output.fd.sync()
            }
            val actualHash = digest.digest().toLowerHex()
            require(
                MessageDigest.isEqual(
                    actualHash.toByteArray(Charsets.US_ASCII),
                    descriptor.sha256.toByteArray(Charsets.US_ASCII),
                ),
            ) { "完整文件校验失败" }

            mutableUiState.update {
                it.copy(
                    toolboxStatus = ToolboxStatus.Working(
                        task = task,
                        title = if (keepAsScreenPreview) "获取当前屏幕" else "从电脑接收文件",
                        detail = if (keepAsScreenPreview) "正在生成屏幕预览" else "正在保存 ${descriptor.name}",
                        percent = 100,
                        canCancel = false,
                    ),
                )
            }

            if (keepAsScreenPreview) {
                val preview = withContext(Dispatchers.IO) {
                    transferStore.finalizeScreen(part, descriptor.name, descriptor.mime)
                }
                committed = true
                finishRemoteDownloadBestEffort(descriptor.id)
                activeTransferId = null
                publishScreenPreview(preview)
            } else {
                val saved = withContext(Dispatchers.IO) {
                    transferStore.saveToDownloads(part, descriptor.name, descriptor.mime)
                }
                committed = true
                withContext(Dispatchers.IO) { transferStore.delete(part) }
                finishRemoteDownloadBestEffort(descriptor.id)
                activeTransferId = null
                mutableUiState.update {
                    it.copy(
                        toolboxStatus = ToolboxStatus.Success(
                            ToolboxTask.RECEIVE_FILE,
                            "文件已保存到手机",
                            "${saved.location} / ${saved.name}",
                        ),
                    )
                }
                appendLog(QuickerEventDirection.INCOMING, "已从电脑接收文件：${saved.name}")
                mutableNotices.emit(UiNotice.Success("文件已保存到下载 / Quicker Link"))
            }
        } finally {
            if (!committed) {
                withContext(NonCancellable + Dispatchers.IO) { transferStore.delete(part) }
            }
        }
    }

    private suspend fun publishScreenPreview(preview: ScreenPreview) {
        val previousPath = mutableUiState.value.screenPreview?.path
        val capturedAt = LocalTime.now().format(timeFormatter)
        mutableUiState.update {
            it.copy(
                screenPreview = ScreenPreviewState(
                    path = preview.file.absolutePath,
                    name = preview.name,
                    capturedAt = capturedAt,
                ),
                toolboxStatus = ToolboxStatus.Success(
                    ToolboxTask.SCREEN,
                    "已获取电脑当前屏幕",
                    "${preview.name} · $capturedAt",
                ),
            )
        }
        if (previousPath != null && previousPath != preview.file.absolutePath) {
            withContext(Dispatchers.IO) { transferStore.delete(File(previousPath)) }
        }
        appendLog(QuickerEventDirection.INCOMING, "已获取电脑当前屏幕")
    }

    private fun updateTransferProgress(
        task: ToolboxTask,
        title: String,
        name: String,
        transferred: Long,
        total: Long,
    ) {
        mutableUiState.update {
            it.copy(
                toolboxStatus = ToolboxStatus.Working(
                    task = task,
                    title = title,
                    detail = "$name · ${formatTransferBytes(transferred)} / ${formatTransferBytes(total)}",
                    percent = transferPercent(transferred, total),
                    canCancel = true,
                ),
            )
        }
    }

    private suspend fun finishRemoteDownloadBestEffort(transferId: String) {
        val cleaned = withContext(NonCancellable) {
            runCatching {
                requestToolbox(
                    command = QuickerToolboxProtocol.downloadFinishCommand(transferId),
                    expectedOperation = QuickerToolboxProtocol.OP_DOWNLOAD_FINISH,
                    logRequest = false,
                    timeoutMs = REMOTE_CLEANUP_TIMEOUT_MS,
                )
            }.isSuccess
        }
        if (!cleaned) {
            appendLog(QuickerEventDirection.SYSTEM, "电脑端传输临时文件将在稍后自动清理")
        }
    }

    private suspend fun cancelActiveRemoteTransferBestEffort() {
        if (suppressRemoteTransferCancel) return
        val transferId = activeTransferId ?: return
        withContext(NonCancellable) {
            runCatching {
                requestToolbox(
                    command = QuickerToolboxProtocol.cancelCommand(transferId),
                    expectedOperation = QuickerToolboxProtocol.OP_TRANSFER_CANCEL,
                    logRequest = false,
                    timeoutMs = REMOTE_CLEANUP_TIMEOUT_MS,
                )
            }
        }
        activeTransferId = null
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
            if (
                text.length > QuickerToolboxProtocol.MAX_CLIPBOARD_CHARS ||
                text.toByteArray(Charsets.UTF_8).size > MAX_TOOLBOX_TEXT_BYTES
            ) {
                connectionManager.replyToCommand(incomingCommand, false, "text_too_large")
                appendLog(QuickerEventDirection.SYSTEM, "Quicker 发来的文本过大，已拒绝")
                return
            }
            val clipboard = getApplication<Application>()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("Quicker", text)) }
                .onSuccess {
                    connectionManager.replyToCommand(incomingCommand, true, "ok")
                    mutableUiState.update { it.copy(toolboxText = text) }
                    mutableNotices.tryEmit(UiNotice.Success("已复制 Quicker 发来的文本"))
                }
                .onFailure {
                    connectionManager.replyToCommand(incomingCommand, false, "clipboard_unavailable")
                }
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
        val log = EventLog(
            LocalTime.now().format(timeFormatter),
            direction,
            compactLogText(text),
        )
        mutableUiState.update { state ->
            state.copy(logs = (listOf(log) + state.logs).take(MAX_LOG_COUNT))
        }
    }

    private inline fun updateConnectionFields(transform: QuickerUiState.() -> QuickerUiState) {
        mutableUiState.update { current -> current.transform() }
    }

    override fun onCleared() {
        discoveryJob?.cancel()
        toolboxJob?.cancel()
        connectionManager.close()
        updateChecker.close()
        updateDownloader.close()
    }

    private companion object {
        const val MAX_LOG_COUNT = 100
        const val MAX_TOOLBOX_TEXT_BYTES = 48 * 1024
        const val TOOLBOX_COMMAND_TIMEOUT_MS = 30_000L
        const val SCREEN_CAPTURE_TIMEOUT_MS = 60_000L
        const val FILE_PICK_TIMEOUT_MS = 5 * 60_000L
        const val FILE_RECONNECT_TIMEOUT_MS = 20_000L
        const val REMOTE_CLEANUP_TIMEOUT_MS = 5_000L
    }
}

private fun FileInputStream.readExactChunk(count: Int): ByteArray {
    require(count in 1..QuickerToolboxProtocol.CHUNK_BYTES)
    val result = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val read = read(result, offset, count - offset)
        check(read > 0) { "读取暂存文件失败" }
        offset += read
    }
    return result
}

private fun ByteArray.toLowerHex(): String = joinToString(separator = "") { value ->
    "%02x".format(value)
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
