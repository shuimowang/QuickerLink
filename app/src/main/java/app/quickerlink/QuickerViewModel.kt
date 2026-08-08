package app.quickerlink

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.quickerlink.connection.QuickerConnectionConfig
import app.quickerlink.connection.QuickerConnectionBinding
import app.quickerlink.connection.QuickerConnectionRuntimeEvent
import app.quickerlink.connection.QuickerConnectionState
import app.quickerlink.connection.QuickerDesktopWindow
import app.quickerlink.connection.QuickerActionControlProtocol
import app.quickerlink.connection.AndroidIpv4SubnetProvider
import app.quickerlink.connection.QuickerEndpoint
import app.quickerlink.connection.QuickerEventDirection
import app.quickerlink.connection.QuickerDiscoveryRequest
import app.quickerlink.connection.QuickerLanDiscovery
import app.quickerlink.connection.QuickerMessage
import app.quickerlink.connection.QuickerPairingCode
import app.quickerlink.connection.QuickerPanelActionCatalog
import app.quickerlink.connection.QuickerPanelActionsProtocol
import app.quickerlink.connection.QuickerLinkCapabilities
import app.quickerlink.connection.QuickerSystemCommand
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
import app.quickerlink.data.DEFAULT_BACKGROUND_CONNECTION_ENABLED
import app.quickerlink.data.DEFAULT_RECEIPT_CUE_ENABLED
import app.quickerlink.data.PreferenceWriteResult
import app.quickerlink.data.QuickerPreferences
import app.quickerlink.data.SavedAction
import app.quickerlink.data.StoredConnection
import app.quickerlink.service.QuickerLinkService
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
import kotlinx.coroutines.delay
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
    SCREEN_CLICK,
    RECEIVE_FILE,
    SEND_FILE,
    SAVE_SCREEN,
    SYSTEM_CONTROL,
    WINDOWS,
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

internal data class ToolboxConnection(
    val connection: QuickerConnectionBinding,
    val actionId: String,
)

data class ScreenPreviewState(
    val path: String,
    val name: String,
    val capturedAt: String,
    val captureId: String?,
    val bitmap: Bitmap? = null,
    val width: Int = 0,
    val height: Int = 0,
    val savedLocation: String? = null,
)

private data class DecodedScreenBitmap(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
)

private const val MAX_DECODED_SCREEN_DIMENSION = 1_280
private const val MAX_DECODED_SCREEN_PIXELS = 1_280L * 1_280L

internal data class QueuedScreenTap(
    val captureId: String,
    val x: Int,
    val y: Int,
    val connection: ToolboxConnection,
    val monitorSession: Long,
)

internal class LatestScreenTapQueue {
    private var pending: QueuedScreenTap? = null
    val hasPending: Boolean
        get() = pending != null

    fun offer(tap: QueuedScreenTap) {
        QuickerToolboxProtocol.screenClickCommand(tap.captureId, tap.x, tap.y)
        pending = tap
    }

    fun take(): QueuedScreenTap? = pending.also { pending = null }

    fun clear() {
        pending = null
    }
}

internal data class QueuedWindowActivation(
    val token: String,
    val title: String,
    val connection: ToolboxConnection,
    val monitorSession: Long,
)

internal class LatestWindowActivationQueue {
    private var pending: QueuedWindowActivation? = null
    val hasPending: Boolean
        get() = pending != null

    fun offer(activation: QueuedWindowActivation) {
        QuickerToolboxProtocol.windowsActivateCommand(activation.token)
        pending = activation
    }

    fun take(): QueuedWindowActivation? = pending.also { pending = null }

    fun clear() {
        pending = null
    }
}

internal class PendingMonitorCapture {
    private var monitorSession: Long? = null
    val isPending: Boolean
        get() = monitorSession != null

    fun request(session: Long) {
        monitorSession = session
    }

    fun take(session: Long): Boolean {
        if (monitorSession != session) return false
        monitorSession = null
        return true
    }

    fun clear(session: Long?) {
        if (monitorSession == session) monitorSession = null
    }

    fun clear() {
        monitorSession = null
    }
}

data class IncomingFileOfferState(
    val descriptor: QuickerTransferDescriptor,
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
    val backgroundConnectionEnabled: Boolean = DEFAULT_BACKGROUND_CONNECTION_ENABLED,
    val backgroundConnectionError: String? = null,
    val receiptCueEnabled: Boolean = DEFAULT_RECEIPT_CUE_ENABLED,
    val receiptCueError: String? = null,
    val savedActions: List<SavedAction> = emptyList(),
    val catalogActionId: String = QuickerPanelActionsProtocol.COMPANION_SHARED_ACTION_ID,
    val syncingPanelActions: Boolean = false,
    val companionActionPromptVisible: Boolean = false,
    val linkCapabilities: QuickerLinkCapabilities? = null,
    val runningActionIds: Set<String> = emptySet(),
    val toolboxText: String = "",
    val toolboxStatus: ToolboxStatus = ToolboxStatus.Idle,
    val screenPreview: ScreenPreviewState? = null,
    val desktopWindows: List<QuickerDesktopWindow> = emptyList(),
    val desktopWindowsLoaded: Boolean = false,
    val desktopWindowsError: String? = null,
    val windowActivationQueued: Boolean = false,
    val incomingFileOffer: IncomingFileOfferState? = null,
    val logs: List<EventLog> = emptyList(),
    val appVersionName: String = BuildConfig.VERSION_NAME,
    val updateState: AppUpdateState = AppUpdateState.Idle,
)

sealed interface UiNotice {
    data class Success(val message: String) : UiNotice
    data class Error(val message: String) : UiNotice
    data class ActionSent(val message: String) : UiNotice
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
private const val BACKGROUND_NOTIFICATION_PERMISSION_MESSAGE =
    "后台连接仍会保持，电脑通知暂不显示"

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

internal fun StoredConnection.toReconnectConfigOrNull(): QuickerConnectionConfig? {
    if (ipAddress.isBlank() || connectionPasswordValidationError(password) != null) return null
    return QuickerConnectionConfig(ipAddress, port, password)
}

internal fun connectionPasswordValidationError(password: String): String? = when {
    password.length > 256 || password.any(Char::isISOControl) -> "连接验证码格式无效"
    else -> null
}

internal data class QuickerLinkTarget(
    val ipAddress: String,
    val port: Int,
    val serviceActionId: String,
)

private fun QuickerConnectionConfig.toLinkTarget(serviceActionId: String) = QuickerLinkTarget(
    ipAddress = ipAddress,
    port = port,
    serviceActionId = serviceActionId,
)

internal fun shouldKeepLinkCapabilities(
    verifiedTarget: QuickerLinkTarget?,
    requestedTarget: QuickerLinkTarget,
): Boolean = verifiedTarget == requestedTarget

internal fun shouldSyncPanelActionsAfterReady(
    explicitlyRequested: Boolean,
    capabilities: QuickerLinkCapabilities?,
): Boolean = explicitlyRequested || capabilities == null

internal fun shouldDispatchMonitorFollowUp(
    monitorActive: Boolean,
    connectionState: QuickerConnectionState,
): Boolean = monitorActive && connectionState is QuickerConnectionState.Ready

internal fun shouldPublishMonitorResult(
    connectionCurrent: Boolean,
    connectionState: QuickerConnectionState,
    requestedMonitorSession: Long?,
    activeMonitorSession: Long?,
    monitorActive: Boolean,
): Boolean = connectionCurrent &&
    connectionState is QuickerConnectionState.Ready &&
    (
        requestedMonitorSession == null ||
            monitorActive && requestedMonitorSession == activeMonitorSession
        )

internal fun isCurrentDisplayedScreenFrame(
    displayedCaptureId: String?,
    requestedCaptureId: String,
    width: Int,
    height: Int,
    decoded: Boolean,
): Boolean = decoded &&
    width > 0 &&
    height > 0 &&
    displayedCaptureId == requestedCaptureId

internal fun shouldRefreshDesktopWindows(
    loaded: Boolean,
    lastRefreshedAtMillis: Long,
    nowMillis: Long,
    refreshIntervalMillis: Long,
): Boolean {
    require(refreshIntervalMillis > 0L)
    if (!loaded) return true
    val elapsed = nowMillis - lastRefreshedAtMillis
    return elapsed < 0L || elapsed >= refreshIntervalMillis
}

internal fun invalidateScreenMonitorSession(state: QuickerUiState): QuickerUiState = state.copy(
    screenPreview = state.screenPreview?.copy(captureId = null),
    desktopWindows = emptyList(),
    desktopWindowsLoaded = false,
    desktopWindowsError = null,
    windowActivationQueued = false,
)

class QuickerViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences: QuickerPreferences = AppPreferences(application)
    private val connectionRuntime = (application as QuickerLinkApplication).connectionRuntime
    private val connectionManager = connectionRuntime.manager
    private val updateChecker = GitHubUpdateChecker()
    private val updateDownloader = AppUpdateDownloader(application)
    private val subnetProvider = AndroidIpv4SubnetProvider(application)
    private val lanDiscovery = QuickerLanDiscovery(QuickerWebSocketEndpointProbe())
    private val transferStore = AndroidTransferStore(application)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val runningActionsLock = Any()

    private var knownGoodConnection = preferences.loadConnection()
    private val connectionSession = ConnectionSession(knownGoodConnection.toReconnectConfigOrNull())
    private var activeLinkTarget = knownGoodConnection.toReconnectConfigOrNull()?.toLinkTarget(
        knownGoodConnection.serviceActionId ?: QuickerPanelActionsProtocol.COMPANION_SHARED_ACTION_ID,
    )
    private var verifiedCapabilitiesTarget: QuickerLinkTarget? = null
    private var appInForeground = false
    private var discoveryJob: Job? = null
    private var syncPanelActionsAfterConnect = false
    private var toolboxJob: Job? = null
    private var activeTransferId: String? = null
    private var activeTransferConnection: ToolboxConnection? = null
    private var suppressRemoteTransferCancel = false
    private var pendingUploadConfirmation: PendingUploadConfirmation? = null
    private var screenConnection: ToolboxConnection? = null
    private var screenMonitorActive = false
    private var screenMonitorSessionSerial = 0L
    private var activeScreenMonitorSession: Long? = null
    private val pendingMonitorCapture = PendingMonitorCapture()
    private val pendingScreenTaps = LatestScreenTapQueue()
    private val pendingWindowActivations = LatestWindowActivationQueue()
    private var desktopWindowsConnection: ToolboxConnection? = null
    private var desktopWindowsRefreshedAtMillis = 0L
    private var toolboxCancellationRequested = false
    private val mutableUiState = MutableStateFlow(
        QuickerUiState(
            ipAddress = knownGoodConnection.ipAddress,
            port = knownGoodConnection.port.toString(),
            password = knownGoodConnection.password,
            rememberPassword = knownGoodConnection.rememberPassword,
            backgroundConnectionEnabled = connectionRuntime.backgroundConnectionEnabled.value,
            receiptCueEnabled = connectionRuntime.receiptCueEnabled.value,
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
        connectionRuntime.setCompanionActionId(mutableUiState.value.catalogActionId)
        viewModelScope.launch {
            connectionManager.state.collect { connectionState ->
                handleConnectionState(connectionState)
            }
        }
        viewModelScope.launch {
            connectionManager.events.collect { event -> appendLog(event.direction, event.summary) }
        }
        viewModelScope.launch {
            connectionRuntime.events.collect(::handleRuntimeEvent)
        }
        viewModelScope.launch {
            connectionRuntime.incomingFileOffer.collect { offer ->
                mutableUiState.update {
                    it.copy(incomingFileOffer = offer?.descriptor?.let(::IncomingFileOfferState))
                }
            }
        }
        viewModelScope.launch {
            connectionRuntime.receivedText.collect { received ->
                if (received != null) {
                    mutableUiState.update { it.copy(toolboxText = received.text) }
                }
            }
        }
        viewModelScope.launch {
            connectionRuntime.backgroundConnectionEnabled.collect { enabled ->
                mutableUiState.update { it.copy(backgroundConnectionEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            connectionRuntime.receiptCueEnabled.collect { enabled ->
                mutableUiState.update { it.copy(receiptCueEnabled = enabled) }
            }
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
        if (
            connectionSession.onBackground(connectionManager.state.value) &&
            !connectionRuntime.shouldRetainConnection()
        ) {
            connectionManager.disconnect()
        }
    }

    fun setBackgroundConnectionEnabled(enabled: Boolean) {
        when (val result = connectionRuntime.setBackgroundConnectionEnabled(enabled)) {
            PreferenceWriteResult.Success -> {
                if (enabled) {
                    runCatching { QuickerLinkService.start(getApplication()) }
                        .onFailure { error ->
                            connectionRuntime.setBackgroundConnectionEnabled(false)
                            mutableUiState.update {
                                it.copy(
                                    backgroundConnectionError = boundedUiErrorMessage(
                                        error.message,
                                        "无法启动后台连接服务",
                                    ),
                                )
                            }
                            mutableNotices.tryEmit(UiNotice.Error("无法启动后台连接服务"))
                        }
                        .onSuccess {
                            mutableUiState.update { it.copy(backgroundConnectionError = null) }
                            mutableNotices.tryEmit(UiNotice.Success("后台接收与连接已开启"))
                        }
                } else {
                    QuickerLinkService.stop(getApplication())
                    mutableUiState.update { it.copy(backgroundConnectionError = null) }
                    mutableNotices.tryEmit(UiNotice.Success("后台接收与连接已关闭"))
                }
            }

            is PreferenceWriteResult.Failure -> {
                mutableUiState.update { it.copy(backgroundConnectionError = result.message) }
                mutableNotices.tryEmit(UiNotice.Error(result.message))
            }
        }
    }

    fun setReceiptCueEnabled(enabled: Boolean) {
        when (val result = connectionRuntime.setReceiptCueEnabled(enabled)) {
            PreferenceWriteResult.Success -> {
                mutableUiState.update { it.copy(receiptCueError = null) }
                mutableNotices.tryEmit(
                    UiNotice.Success(if (enabled) "接收提示音已开启" else "接收提示音已关闭"),
                )
            }

            is PreferenceWriteResult.Failure -> {
                mutableUiState.update { it.copy(receiptCueError = result.message) }
                mutableNotices.tryEmit(UiNotice.Error(result.message))
            }
        }
    }

    fun onNotificationPermissionStatus(granted: Boolean) {
        if (!connectionRuntime.backgroundConnectionEnabled.value) {
            if (granted) {
                mutableUiState.update { it.copy(backgroundConnectionError = null) }
            }
            return
        }
        runCatching { QuickerLinkService.start(getApplication()) }
            .onFailure { error ->
                connectionRuntime.setBackgroundConnectionEnabled(false)
                mutableUiState.update {
                    it.copy(
                        backgroundConnectionError = boundedUiErrorMessage(
                            error.message,
                            "无法启动后台连接服务",
                        ),
                    )
                }
            }
            .onSuccess {
                mutableUiState.update { it.copy(backgroundConnectionError = null) }
            }
    }

    fun reportBackgroundConnectionPermissionDenied() {
        mutableUiState.update { it.copy(backgroundConnectionError = null) }
        mutableNotices.tryEmit(UiNotice.Error(BACKGROUND_NOTIFICATION_PERMISSION_MESSAGE))
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
        connectionPasswordValidationError(state.password)?.let { error ->
            mutableUiState.update { it.copy(connectionError = error) }
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
        connectionRuntime.clearIncomingFileOffer()
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
        val syncTarget = activeLinkTarget
        if (state.connectionState !is QuickerConnectionState.Ready) {
            mutableNotices.tryEmit(UiNotice.Error("请先连接 Quicker"))
            return
        }
        if (state.syncingPanelActions) return

        mutableUiState.update {
            it.copy(
                syncingPanelActions = true,
                linkCapabilities = null,
            )
        }
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
                if (syncTarget != activeLinkTarget) {
                    throw IllegalStateException("连接已切换，请重新同步")
                }
                verifiedCapabilitiesTarget = syncTarget
                mutableUiState.update { current ->
                    val updated = mergePanelActions(current.savedActions, catalog)
                    preferences.saveActions(updated)
                    current.copy(
                        savedActions = updated,
                        companionActionPromptVisible = false,
                        linkCapabilities = catalog.capabilities,
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
                mutableNotices.emit(UiNotice.ActionSent("“${action.label}”已发送"))
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
        val actionIdentity = action.quickerActionId ?: action.actionTarget
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
                    data = QuickerActionControlProtocol.stopCommand(actionIdentity),
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
                QuickerActionControlProtocol.parseStopResponse(response.data, action.quickerActionId)
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
        if (mutableUiState.value.linkCapabilities?.clipboardWrite != true) {
            mutableNotices.tryEmit(UiNotice.Error("请先同步最新版 Quicker Link 动作能力"))
            return
        }
        launchToolboxTask(
            task = ToolboxTask.CLIPBOARD,
            title = "发送文本",
            detail = "正在写入电脑剪贴板",
        ) {
            requestToolbox(
                command = QuickerToolboxProtocol.clipboardWriteCommand(text),
                expectedOperation = QuickerToolboxProtocol.OP_CLIPBOARD_WRITE,
                logSummary = "发送文本到电脑剪贴板",
            )
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
        captureComputerScreen(silentIfBusy = false)
    }

    fun openComputerScreenMonitor() {
        screenMonitorActive = true
        screenMonitorSessionSerial += 1L
        activeScreenMonitorSession = screenMonitorSessionSerial
        if (toolboxJob?.isActive == true) {
            pendingMonitorCapture.request(screenMonitorSessionSerial)
        } else {
            captureComputerScreen(silentIfBusy = true)
        }
    }

    fun closeComputerScreenMonitor() {
        screenMonitorActive = false
        activeScreenMonitorSession = null
        screenConnection = null
        desktopWindowsConnection = null
        desktopWindowsRefreshedAtMillis = 0L
        pendingMonitorCapture.clear()
        pendingScreenTaps.clear()
        pendingWindowActivations.clear()
        mutableUiState.update(::invalidateScreenMonitorSession)
    }

    fun captureComputerScreenInMonitor() {
        if (!screenMonitorActive) return
        if (toolboxJob?.isActive == true) {
            pendingMonitorCapture.request(requireNotNull(activeScreenMonitorSession))
        } else {
            captureComputerScreen(silentIfBusy = true)
        }
    }

    private fun captureComputerScreen(silentIfBusy: Boolean) {
        val monitorSession = activeScreenMonitorSession
        launchToolboxTask(
            task = ToolboxTask.SCREEN,
            title = "获取当前屏幕",
            detail = "正在请求电脑生成快照",
            silentIfBusy = silentIfBusy,
        ) {
            val connection = currentToolboxConnection()
            requestAndDownloadScreen(
                logSummary = "获取电脑当前屏幕：Quicker Link",
                connection = connection,
                task = ToolboxTask.SCREEN,
                monitorSession = monitorSession,
            )
            if (canDispatchMonitorFollowUp(connection, monitorSession) && !hasPendingMonitorInteraction()) {
                refreshDesktopWindowsIfStale(connection, monitorSession)
            }
        }
    }

    fun clickComputerScreen(captureId: String, x: Int, y: Int) {
        val state = mutableUiState.value
        if (!screenMonitorActive) return
        if (state.linkCapabilities?.screenClick != true) {
            mutableNotices.tryEmit(UiNotice.Error("请先同步最新版 Quicker Link 动作能力"))
            return
        }
        val displayedFrame = state.screenPreview
        if (
            !isCurrentDisplayedScreenFrame(
                displayedCaptureId = displayedFrame?.captureId,
                requestedCaptureId = captureId,
                width = displayedFrame?.width ?: 0,
                height = displayedFrame?.height ?: 0,
                decoded = displayedFrame?.bitmap != null,
            )
        ) {
            mutableNotices.tryEmit(UiNotice.Error("屏幕画面已失效，请刷新后重试"))
            return
        }
        val connection = screenConnection
        if (connection == null) {
            mutableNotices.tryEmit(UiNotice.Error("屏幕画面已失效，请刷新后重试"))
            return
        }
        if (!connectionManager.isCurrentReadyConnection(connection.connection)) {
            mutableUiState.update { current ->
                current.copy(screenPreview = current.screenPreview?.copy(captureId = null))
            }
            screenConnection = null
            mutableNotices.tryEmit(UiNotice.Error("屏幕画面已失效，请刷新后重试"))
            return
        }
        val monitorSession = activeScreenMonitorSession
        if (monitorSession == null) return
        val tap = QueuedScreenTap(captureId, x, y, connection, monitorSession)
        if (toolboxJob?.isActive == true) {
            val workingTask = (state.toolboxStatus as? ToolboxStatus.Working)?.task
            when {
                pendingWindowActivations.hasPending -> Unit
                workingTask == ToolboxTask.SCREEN -> pendingScreenTaps.offer(tap)
                workingTask == ToolboxTask.SCREEN_CLICK -> {
                    mutableNotices.tryEmit(UiNotice.Error("正在处理上一次点击，请等待画面刷新"))
                }
                else -> mutableNotices.tryEmit(UiNotice.Error("已有传输任务正在进行"))
            }
            return
        }
        dispatchComputerScreenClick(tap, queued = false)
    }

    private fun dispatchComputerScreenClick(
        tap: QueuedScreenTap,
        queued: Boolean,
    ) {
        val connection = tap.connection
        screenConnection = null
        mutableUiState.update { current ->
            current.copy(screenPreview = current.screenPreview?.copy(captureId = null))
        }
        launchToolboxTask(
            task = ToolboxTask.SCREEN_CLICK,
            title = "点击电脑屏幕",
            detail = "正在发送点击并刷新画面",
            silentIfBusy = true,
        ) {
            try {
                requestToolbox(
                    command = QuickerToolboxProtocol.screenClickCommand(tap.captureId, tap.x, tap.y),
                    expectedOperation = QuickerToolboxProtocol.OP_SCREEN_CLICK,
                    logSummary = "点击电脑屏幕：Quicker Link",
                    actionId = connection.actionId,
                    expectedConnection = connection.connection,
                )
            } catch (error: QuickerToolboxRemoteException) {
                if (queued && error.code == "screen_target_expired") {
                    if (
                        canDispatchMonitorFollowUp(connection, tap.monitorSession) &&
                        !pendingWindowActivations.hasPending
                    ) {
                        mutableNotices.emit(UiNotice.Error("画面已更新，请重新点击"))
                        requestAndDownloadScreen(
                            logSummary = "点击画面失效后刷新屏幕：Quicker Link",
                            connection = connection,
                            task = ToolboxTask.SCREEN_CLICK,
                            monitorSession = tap.monitorSession,
                        )
                    } else if (!pendingWindowActivations.hasPending) {
                        mutableUiState.update { it.copy(toolboxStatus = ToolboxStatus.Idle) }
                    }
                    return@launchToolboxTask
                }
                throw error
            }
            appendLog(QuickerEventDirection.INCOMING, "电脑已接收屏幕点击")
            try {
                delay(SCREEN_CLICK_SETTLE_MS)
                if (
                    !canDispatchMonitorFollowUp(connection, tap.monitorSession) ||
                    pendingWindowActivations.hasPending
                ) {
                    if (!pendingWindowActivations.hasPending) {
                        completeConfirmedMonitorInteraction(
                            task = ToolboxTask.SCREEN_CLICK,
                            title = "已点击电脑屏幕",
                            detail = "电脑已接收点击",
                        )
                    }
                    return@launchToolboxTask
                }
                requestAndDownloadScreen(
                    logSummary = "点击后刷新电脑屏幕：Quicker Link",
                    connection = connection,
                    task = ToolboxTask.SCREEN_CLICK,
                    monitorSession = tap.monitorSession,
                )
                if (
                    canDispatchMonitorFollowUp(connection, tap.monitorSession) &&
                    !hasPendingMonitorInteraction()
                ) {
                    refreshDesktopWindowsIfStale(connection, tap.monitorSession)
                }
            } catch (error: Exception) {
                handleConfirmedMonitorFollowUpFailure(
                    task = ToolboxTask.SCREEN_CLICK,
                    title = "已点击电脑屏幕",
                    detail = "画面暂未更新，可稍后刷新",
                    notice = "已点击电脑屏幕，但画面暂未更新",
                    error = error,
                )
            }
        }
    }

    private fun dispatchPendingScreenTapIfPossible() {
        if (!screenMonitorActive) {
            pendingScreenTaps.clear()
            return
        }
        val tap = pendingScreenTaps.take() ?: return
        val state = mutableUiState.value
        if (
            state.connectionState !is QuickerConnectionState.Ready ||
            state.linkCapabilities?.screenClick != true ||
            activeScreenMonitorSession != tap.monitorSession ||
            !connectionManager.isCurrentReadyConnection(tap.connection.connection)
        ) {
            activeScreenMonitorSession?.let(pendingMonitorCapture::request)
            return
        }
        dispatchComputerScreenClick(tap, queued = true)
    }

    private fun dispatchPendingMonitorCaptureIfPossible() {
        if (!pendingMonitorCapture.isPending) return
        if (!screenMonitorActive || activeScreenMonitorSession == null) {
            pendingMonitorCapture.clear()
            return
        }
        if (
            toolboxJob?.isActive == true ||
            mutableUiState.value.connectionState !is QuickerConnectionState.Ready
        ) {
            return
        }
        val monitorSession = requireNotNull(activeScreenMonitorSession)
        if (pendingMonitorCapture.take(monitorSession)) {
            captureComputerScreen(silentIfBusy = true)
        }
    }

    private fun hasPendingMonitorInteraction(): Boolean =
        pendingWindowActivations.hasPending || pendingScreenTaps.hasPending

    private fun canDispatchMonitorFollowUp(
        connection: ToolboxConnection,
        monitorSession: Long?,
    ): Boolean =
        shouldDispatchMonitorFollowUp(
            monitorActive = screenMonitorActive,
            connectionState = mutableUiState.value.connectionState,
        ) &&
            monitorSession != null &&
            monitorSession == activeScreenMonitorSession &&
            connectionManager.isCurrentReadyConnection(connection.connection)

    private fun completeConfirmedMonitorInteraction(
        task: ToolboxTask,
        title: String,
        detail: String,
    ) {
        mutableUiState.update {
            it.copy(toolboxStatus = ToolboxStatus.Success(task, title, detail))
        }
    }

    private suspend fun handleConfirmedMonitorFollowUpFailure(
        task: ToolboxTask,
        title: String,
        detail: String,
        notice: String,
        error: Exception,
    ) {
        if (error is CancellationException && !currentCoroutineContext().isActive) throw error
        completeConfirmedMonitorInteraction(task, title, detail)
        appendLog(
            QuickerEventDirection.SYSTEM,
            "$title，后续刷新失败：${compactLogText(boundedUiErrorMessage(error.message, "刷新失败"))}",
        )
        mutableNotices.emit(UiNotice.Success(notice))
    }

    fun refreshComputerWindows() {
        if (mutableUiState.value.linkCapabilities?.windowList != true) {
            mutableNotices.tryEmit(UiNotice.Error("请先同步最新版 Quicker Link 动作能力"))
            return
        }
        val monitorSession = activeScreenMonitorSession
        launchToolboxTask(
            task = ToolboxTask.WINDOWS,
            title = "刷新电脑窗口",
            detail = "正在读取可切换窗口",
        ) {
            val connection = currentToolboxConnection()
            val windows = requestDesktopWindows(connection, "读取电脑窗口：Quicker Link")
            publishDesktopWindows(windows, "已刷新电脑窗口", connection, monitorSession)
        }
    }

    fun activateComputerWindow(token: String) {
        val state = mutableUiState.value
        if (!screenMonitorActive) return
        if (state.linkCapabilities?.windowActivate != true) {
            mutableNotices.tryEmit(UiNotice.Error("请先同步最新版 Quicker Link 动作能力"))
            return
        }
        val window = state.desktopWindows.singleOrNull { it.token == token }
        if (window == null) {
            mutableNotices.tryEmit(UiNotice.Error("窗口列表已失效，请刷新后重试"))
            return
        }
        val connection = desktopWindowsConnection
        if (connection == null) {
            mutableNotices.tryEmit(UiNotice.Error("窗口列表已失效，请刷新后重试"))
            return
        }
        if (!connectionManager.isCurrentReadyConnection(connection.connection)) {
            desktopWindowsConnection = null
            mutableNotices.tryEmit(UiNotice.Error("窗口列表已失效，请刷新后重试"))
            return
        }
        val monitorSession = activeScreenMonitorSession ?: return
        val activation = QueuedWindowActivation(token, window.title, connection, monitorSession)
        if (toolboxJob?.isActive == true) {
            val workingTask = (state.toolboxStatus as? ToolboxStatus.Working)?.task
            if (workingTask == ToolboxTask.SCREEN || workingTask == ToolboxTask.SCREEN_CLICK) {
                pendingScreenTaps.clear()
                pendingWindowActivations.offer(activation)
                mutableUiState.update { it.copy(windowActivationQueued = true) }
            } else {
                mutableNotices.tryEmit(UiNotice.Error("已有传输任务正在进行"))
            }
            return
        }
        pendingScreenTaps.clear()
        dispatchComputerWindowActivation(activation)
    }

    private fun dispatchComputerWindowActivation(activation: QueuedWindowActivation) {
        val connection = activation.connection
        screenConnection = null
        mutableUiState.update { current ->
            current.copy(screenPreview = current.screenPreview?.copy(captureId = null))
        }
        launchToolboxTask(
            task = ToolboxTask.WINDOWS,
            title = "切换电脑窗口",
            detail = activation.title,
            silentIfBusy = true,
        ) {
            requestToolbox(
                command = QuickerToolboxProtocol.windowsActivateCommand(activation.token),
                expectedOperation = QuickerToolboxProtocol.OP_WINDOWS_ACTIVATE,
                logSummary = "切换电脑窗口：${activation.title}",
                actionId = connection.actionId,
                expectedConnection = connection.connection,
            )
            mutableUiState.update { current ->
                current.copy(
                    desktopWindows = current.desktopWindows.map { item ->
                        item.copy(active = item.token == activation.token)
                    },
                    desktopWindowsError = null,
                )
            }
            appendLog(QuickerEventDirection.INCOMING, "已切换电脑窗口：${activation.title}")
            try {
                delay(WINDOW_ACTIVATION_SETTLE_MS)
                if (!canDispatchMonitorFollowUp(connection, activation.monitorSession)) {
                    completeConfirmedMonitorInteraction(
                        task = ToolboxTask.WINDOWS,
                        title = "已切换到 ${activation.title}",
                        detail = "电脑窗口已切换",
                    )
                    return@launchToolboxTask
                }
                requestAndDownloadScreen(
                    logSummary = "切换窗口后刷新屏幕：Quicker Link",
                    connection = connection,
                    task = ToolboxTask.WINDOWS,
                    monitorSession = activation.monitorSession,
                )
                if (!canDispatchMonitorFollowUp(connection, activation.monitorSession)) {
                    completeConfirmedMonitorInteraction(
                        task = ToolboxTask.WINDOWS,
                        title = "已切换到 ${activation.title}",
                        detail = "电脑窗口和画面已更新",
                    )
                    return@launchToolboxTask
                }
                mutableUiState.update {
                    it.copy(
                        toolboxStatus = ToolboxStatus.Working(
                            ToolboxTask.WINDOWS,
                            "刷新电脑窗口",
                            "正在确认当前窗口",
                        ),
                    )
                }
                val windows = requestDesktopWindows(connection, "刷新电脑窗口：Quicker Link")
                publishDesktopWindows(
                    windows = windows,
                    successTitle = "已切换到 ${activation.title}",
                    connection = connection,
                    monitorSession = activation.monitorSession,
                )
            } catch (error: Exception) {
                handleConfirmedMonitorFollowUpFailure(
                    task = ToolboxTask.WINDOWS,
                    title = "已切换到 ${activation.title}",
                    detail = "画面或窗口列表暂未更新，可稍后刷新",
                    notice = "已切换到 ${activation.title}，但画面暂未更新",
                    error = error,
                )
            }
        }
    }

    private fun dispatchPendingWindowActivationIfPossible(): Boolean {
        if (!screenMonitorActive) {
            pendingWindowActivations.clear()
            mutableUiState.update { it.copy(windowActivationQueued = false) }
            return false
        }
        val activation = pendingWindowActivations.take() ?: return false
        mutableUiState.update { it.copy(windowActivationQueued = false) }
        val state = mutableUiState.value
        if (
            state.connectionState !is QuickerConnectionState.Ready ||
            state.linkCapabilities?.windowActivate != true ||
            activeScreenMonitorSession != activation.monitorSession ||
            !connectionManager.isCurrentReadyConnection(activation.connection.connection)
        ) {
            return false
        }
        dispatchComputerWindowActivation(activation)
        return true
    }

    fun receiveFileFromComputer() {
        launchToolboxTask(
            task = ToolboxTask.RECEIVE_FILE,
            title = "从电脑接收文件",
            detail = "请在电脑上选择一个不超过 64 MiB 的文件",
            canCancel = true,
        ) {
            val connection = currentToolboxConnection()
            val result = requestToolbox(
                command = QuickerToolboxProtocol.downloadPickCommand(),
                expectedOperation = QuickerToolboxProtocol.OP_DOWNLOAD_PICK,
                logSummary = "从电脑选择文件：Quicker Link",
                timeoutMs = FILE_PICK_TIMEOUT_MS,
                actionId = connection.actionId,
                expectedConnection = connection.connection,
            ) as QuickerToolboxResult.Transfer
            downloadFromComputer(
                descriptor = result.descriptor,
                task = ToolboxTask.RECEIVE_FILE,
                keepAsScreenPreview = false,
                connection = connection,
            )
        }
    }

    fun acceptIncomingFileOffer() {
        val offer = mutableUiState.value.incomingFileOffer ?: return
        if (mutableUiState.value.connectionState !is QuickerConnectionState.Ready) {
            mutableNotices.tryEmit(UiNotice.Error("连接中断，恢复连接后再接收文件"))
            return
        }
        if (mutableUiState.value.toolboxStatus is ToolboxStatus.Working) {
            mutableNotices.tryEmit(UiNotice.Error("请等待当前传输完成"))
            return
        }
        val incoming = connectionRuntime.clearIncomingFileOffer(offer.descriptor.id) ?: run {
            mutableNotices.tryEmit(UiNotice.Error("文件邀请已失效"))
            return
        }
        val descriptor = incoming.descriptor
        val connection = ToolboxConnection(incoming.connection, incoming.actionId)
        mutableUiState.update { it.copy(incomingFileOffer = null) }
        launchToolboxTask(
            task = ToolboxTask.RECEIVE_FILE,
            title = "接收 ${descriptor.name}",
            detail = "正在从电脑下载文件",
            canCancel = true,
        ) {
            downloadFromComputer(
                descriptor = descriptor,
                task = ToolboxTask.RECEIVE_FILE,
                keepAsScreenPreview = false,
                connection = connection,
            )
        }
    }

    fun rejectIncomingFileOffer() {
        val offer = mutableUiState.value.incomingFileOffer ?: return
        val incoming = connectionRuntime.clearIncomingFileOffer(offer.descriptor.id) ?: return
        val descriptor = incoming.descriptor
        mutableUiState.update { it.copy(incomingFileOffer = null) }
        viewModelScope.launch {
            runCatching {
                requestToolbox(
                    command = QuickerToolboxProtocol.cancelCommand(descriptor.id),
                    expectedOperation = QuickerToolboxProtocol.OP_TRANSFER_CANCEL,
                    logSummary = "拒绝电脑文件：Quicker Link",
                    actionId = incoming.actionId,
                    expectedConnection = incoming.connection,
                )
            }
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
            activeTransferConnection = ToolboxConnection(pending.connection, pending.actionId)
            suppressRemoteTransferCancel = true
            val saved = finishUploadWithRecovery(pending)
            pendingUploadConfirmation = null
            activeTransferId = null
            activeTransferConnection = null
            suppressRemoteTransferCancel = false
            publishUploadSuccess(saved)
        }
    }

    fun runSystemCommand(command: QuickerSystemCommand) {
        if (mutableUiState.value.linkCapabilities?.systemControl != true) {
            mutableNotices.tryEmit(UiNotice.Error("请先同步最新版 Quicker Link 动作能力"))
            return
        }
        val label = systemCommandLabel(command)
        launchToolboxTask(
            task = ToolboxTask.SYSTEM_CONTROL,
            title = label,
            detail = "正在向电脑发送命令",
        ) {
            requestToolbox(
                command = QuickerToolboxProtocol.systemCommand(command),
                expectedOperation = QuickerToolboxProtocol.OP_SYSTEM_COMMAND,
                logSummary = "$label：Quicker Link",
            )
            mutableUiState.update {
                it.copy(
                    toolboxStatus = ToolboxStatus.Success(
                        ToolboxTask.SYSTEM_CONTROL,
                        "命令已发送",
                        label,
                    ),
                )
            }
            appendLog(QuickerEventDirection.INCOMING, "电脑已接收命令：$label")
        }
    }

    fun pasteText(text: String) {
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
                connectionManager.sendCommand(operation = "paste", data = text)
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

    fun sendQuickInput(text: String, appendEnter: Boolean) {
        if (text.isEmpty()) {
            mutableNotices.tryEmit(UiNotice.Error("请输入内容"))
            return
        }
        if (text.toByteArray(Charsets.UTF_8).size > MAX_TOOLBOX_TEXT_BYTES) {
            mutableNotices.tryEmit(UiNotice.Error("文本过大，请缩短后重试"))
            return
        }
        if (mutableUiState.value.connectionState !is QuickerConnectionState.Ready) {
            mutableNotices.tryEmit(UiNotice.Error("请先连接 Quicker"))
            return
        }

        viewModelScope.launch {
            try {
                val pasteResponse = connectionManager.sendCommand(
                    operation = "paste",
                    data = text,
                    logSummary = "发送快捷输入文本",
                    logResponse = false,
                )
                if (pasteResponse.isSuccess == false) {
                    throw IllegalStateException(webSocketCommandFailureMessage(pasteResponse, "文本输入失败"))
                }
                if (appendEnter) {
                    val enterResponse = connectionManager.sendCommand(
                        operation = "sendkeys",
                        data = "{ENTER}",
                        logSummary = "发送回车键",
                        logResponse = false,
                    )
                    if (enterResponse.isSuccess == false) {
                        throw IllegalStateException(webSocketCommandFailureMessage(enterResponse, "回车键发送失败"))
                    }
                }
                mutableNotices.emit(
                    UiNotice.Success(if (appendEnter) "文本与回车已发送" else "文本已发送"),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                mutableNotices.emit(UiNotice.Error(boundedUiErrorMessage(error.message, "快捷输入失败")))
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
        silentIfBusy: Boolean = false,
        block: suspend () -> Unit,
    ) {
        if (requiresConnection && mutableUiState.value.connectionState !is QuickerConnectionState.Ready) {
            mutableNotices.tryEmit(UiNotice.Error("请先连接 Quicker"))
            return
        }
        if (toolboxJob?.isActive == true) {
            if (!silentIfBusy) {
                mutableNotices.tryEmit(UiNotice.Error("已有传输任务正在进行"))
            }
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
                activeTransferConnection = null
                suppressRemoteTransferCancel = false
                toolboxCancellationRequested = false
                toolboxJob = null
                if (!dispatchPendingWindowActivationIfPossible()) {
                    dispatchPendingScreenTapIfPossible()
                }
                dispatchPendingMonitorCaptureIfPossible()
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
        if (task == ToolboxTask.WINDOWS) {
            desktopWindowsRefreshedAtMillis = 0L
        }
        mutableUiState.update {
            it.copy(
                desktopWindowsLoaded = if (task == ToolboxTask.WINDOWS) true else it.desktopWindowsLoaded,
                desktopWindowsError = if (task == ToolboxTask.WINDOWS) message else it.desktopWindowsError,
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

    private fun currentToolboxConnection(): ToolboxConnection {
        val connection = connectionManager.currentReadyConnectionBinding()
            ?: throw IllegalStateException("尚未连接到 Quicker")
        return ToolboxConnection(
            connection = connection,
            actionId = mutableUiState.value.catalogActionId,
        )
    }

    private suspend fun uploadToComputer(upload: PreparedUpload) {
        pendingUploadConfirmation = null
        val connection = connectionManager.currentReadyConnectionBinding()
            ?: throw IllegalStateException("尚未连接到 Quicker")
        val actionId = mutableUiState.value.catalogActionId
        val toolboxConnection = ToolboxConnection(connection, actionId)
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
        activeTransferConnection = toolboxConnection

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
        activeTransferConnection = null
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
        connection: ToolboxConnection,
        captureId: String? = null,
        screenMonitorSession: Long? = null,
    ) {
        if (keepAsScreenPreview) {
            require(descriptor.mime == "image/jpeg") { "电脑返回的屏幕快照类型无效" }
            require(captureId != null) { "电脑未返回屏幕标识" }
        }
        activeTransferId = descriptor.id
        activeTransferConnection = connection
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
                        actionId = connection.actionId,
                        expectedConnection = connection.connection,
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
                finishRemoteDownloadBestEffort(descriptor.id, connection)
                activeTransferId = null
                activeTransferConnection = null
                publishScreenPreview(
                    preview = preview,
                    captureId = requireNotNull(captureId),
                    connection = connection,
                    monitorSession = screenMonitorSession,
                )
            } else {
                val saved = withContext(Dispatchers.IO) {
                    transferStore.saveToDownloads(part, descriptor.name, descriptor.mime)
                }
                committed = true
                withContext(Dispatchers.IO) { transferStore.delete(part) }
                finishRemoteDownloadBestEffort(descriptor.id, connection)
                activeTransferId = null
                activeTransferConnection = null
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

    private suspend fun requestAndDownloadScreen(
        logSummary: String,
        connection: ToolboxConnection,
        task: ToolboxTask,
        monitorSession: Long?,
    ) {
        pendingMonitorCapture.clear(monitorSession)
        val logRequest = mutableUiState.value.screenPreview == null
        val result = requestToolbox(
            command = QuickerToolboxProtocol.screenCaptureCommand(),
            expectedOperation = QuickerToolboxProtocol.OP_SCREEN_CAPTURE,
            logSummary = logSummary.takeIf { logRequest },
            logRequest = logRequest,
            timeoutMs = SCREEN_CAPTURE_TIMEOUT_MS,
            actionId = connection.actionId,
            expectedConnection = connection.connection,
        ) as QuickerToolboxResult.ScreenCapture
        downloadFromComputer(
            descriptor = result.descriptor,
            task = task,
            keepAsScreenPreview = true,
            connection = connection,
            captureId = result.captureId,
            screenMonitorSession = monitorSession,
        )
    }

    private suspend fun requestDesktopWindows(
        connection: ToolboxConnection,
        logSummary: String,
    ): List<QuickerDesktopWindow> {
        val result = requestToolbox(
            command = QuickerToolboxProtocol.windowsListCommand(),
            expectedOperation = QuickerToolboxProtocol.OP_WINDOWS_LIST,
            logSummary = logSummary,
            actionId = connection.actionId,
            expectedConnection = connection.connection,
        ) as QuickerToolboxResult.Windows
        return result.items
    }

    private suspend fun refreshDesktopWindowsIfStale(
        connection: ToolboxConnection,
        monitorSession: Long?,
    ) {
        val state = mutableUiState.value
        if (state.linkCapabilities?.windowList != true) return
        if (!canPublishMonitorResult(connection, monitorSession)) return
        val now = SystemClock.elapsedRealtime()
        if (
            !shouldRefreshDesktopWindows(
                loaded = state.desktopWindowsLoaded,
                lastRefreshedAtMillis = desktopWindowsRefreshedAtMillis,
                nowMillis = now,
                refreshIntervalMillis = WINDOW_LIST_REFRESH_INTERVAL_MS,
            )
        ) {
            return
        }
        mutableUiState.update {
            it.copy(
                toolboxStatus = ToolboxStatus.Working(
                    ToolboxTask.WINDOWS,
                    "读取电脑窗口",
                    "正在更新可切换窗口",
                ),
            )
        }
        try {
            val windows = requestDesktopWindows(connection, "读取电脑窗口：Quicker Link")
            publishDesktopWindows(
                windows = windows,
                successTitle = "已获取电脑当前屏幕",
                connection = connection,
                monitorSession = monitorSession,
            )
        } catch (error: Exception) {
            if (error is CancellationException && !currentCoroutineContext().isActive) throw error
            if (!canPublishMonitorResult(connection, monitorSession)) {
                clearWorkingToolboxStatus()
                return
            }
            desktopWindowsRefreshedAtMillis = now
            val message = boundedUiErrorMessage(error.message, "读取窗口失败")
            mutableUiState.update {
                it.copy(
                    desktopWindowsLoaded = true,
                    desktopWindowsError = message,
                    toolboxStatus = ToolboxStatus.Success(
                        ToolboxTask.SCREEN,
                        "已获取电脑当前屏幕",
                        "窗口列表暂不可用",
                    ),
                )
            }
            appendLog(QuickerEventDirection.SYSTEM, "读取电脑窗口失败：${compactLogText(message)}")
        }
    }

    private fun publishDesktopWindows(
        windows: List<QuickerDesktopWindow>,
        successTitle: String,
        connection: ToolboxConnection,
        monitorSession: Long?,
    ): Boolean {
        if (!canPublishMonitorResult(connection, monitorSession)) {
            clearWorkingToolboxStatus()
            return false
        }
        desktopWindowsRefreshedAtMillis = SystemClock.elapsedRealtime()
        desktopWindowsConnection = connection
        mutableUiState.update {
            it.copy(
                desktopWindows = windows,
                desktopWindowsLoaded = true,
                desktopWindowsError = null,
                toolboxStatus = ToolboxStatus.Success(
                    ToolboxTask.WINDOWS,
                    successTitle,
                    if (windows.isEmpty()) "没有可切换窗口" else "${windows.size} 个窗口",
                ),
            )
        }
        return true
    }

    private fun canPublishMonitorResult(
        connection: ToolboxConnection,
        monitorSession: Long?,
    ): Boolean = shouldPublishMonitorResult(
        connectionCurrent = connectionManager.isCurrentReadyConnection(connection.connection),
        connectionState = mutableUiState.value.connectionState,
        requestedMonitorSession = monitorSession,
        activeMonitorSession = activeScreenMonitorSession,
        monitorActive = screenMonitorActive,
    )

    private fun clearWorkingToolboxStatus() {
        mutableUiState.update { state ->
            if (state.toolboxStatus is ToolboxStatus.Working) {
                state.copy(toolboxStatus = ToolboxStatus.Idle)
            } else {
                state
            }
        }
    }

    private suspend fun publishScreenPreview(
        preview: ScreenPreview,
        captureId: String,
        connection: ToolboxConnection,
        monitorSession: Long?,
    ) {
        val publishAllowed = canPublishMonitorResult(connection, monitorSession)
        if (!publishAllowed) {
            withContext(Dispatchers.IO) { transferStore.delete(preview.file) }
            clearWorkingToolboxStatus()
            return
        }
        val decoded = try {
            withContext(Dispatchers.IO) { decodeScreenBitmap(preview.file) }
        } catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { transferStore.delete(preview.file) }
            throw error
        }
        if (decoded == null) {
            withContext(NonCancellable + Dispatchers.IO) { transferStore.delete(preview.file) }
            throw IllegalArgumentException("电脑返回的屏幕快照无法解码")
        }
        if (!canPublishMonitorResult(connection, monitorSession)) {
            withContext(NonCancellable + Dispatchers.IO) {
                decoded.bitmap.recycle()
                transferStore.delete(preview.file)
            }
            clearWorkingToolboxStatus()
            return
        }
        pendingMonitorCapture.clear(monitorSession)
        val previousPath = mutableUiState.value.screenPreview?.path
        val capturedAt = LocalTime.now().format(timeFormatter)
        mutableUiState.update {
            it.copy(
                screenPreview = ScreenPreviewState(
                    path = preview.file.absolutePath,
                    name = preview.name,
                    capturedAt = capturedAt,
                    captureId = captureId,
                    bitmap = decoded.bitmap,
                    width = decoded.width,
                    height = decoded.height,
                ),
                toolboxStatus = ToolboxStatus.Success(
                    ToolboxTask.SCREEN,
                    "已获取电脑当前屏幕",
                    "${preview.name} · $capturedAt",
                ),
            )
        }
        screenConnection = connection
        if (previousPath != null && previousPath != preview.file.absolutePath) {
            withContext(Dispatchers.IO) { transferStore.delete(File(previousPath)) }
        }
        if (previousPath == null) {
            appendLog(QuickerEventDirection.INCOMING, "已获取电脑当前屏幕")
        }
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

    private suspend fun finishRemoteDownloadBestEffort(
        transferId: String,
        connection: ToolboxConnection,
    ) {
        val cleaned = withContext(NonCancellable) {
            runCatching {
                requestToolbox(
                    command = QuickerToolboxProtocol.downloadFinishCommand(transferId),
                    expectedOperation = QuickerToolboxProtocol.OP_DOWNLOAD_FINISH,
                    logRequest = false,
                    timeoutMs = REMOTE_CLEANUP_TIMEOUT_MS,
                    actionId = connection.actionId,
                    expectedConnection = connection.connection,
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
        val connection = activeTransferConnection ?: return
        withContext(NonCancellable) {
            runCatching {
                requestToolbox(
                    command = QuickerToolboxProtocol.cancelCommand(transferId),
                    expectedOperation = QuickerToolboxProtocol.OP_TRANSFER_CANCEL,
                    logRequest = false,
                    timeoutMs = REMOTE_CLEANUP_TIMEOUT_MS,
                    actionId = connection.actionId,
                    expectedConnection = connection.connection,
                )
            }
        }
        activeTransferId = null
        activeTransferConnection = null
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
                screenPreview = if (connectionState is QuickerConnectionState.Ready) {
                    state.screenPreview
                } else {
                    state.screenPreview?.copy(captureId = null)
                },
                desktopWindows = if (connectionState is QuickerConnectionState.Ready) {
                    state.desktopWindows
                } else {
                    emptyList()
                },
                desktopWindowsLoaded = connectionState is QuickerConnectionState.Ready &&
                    state.desktopWindowsLoaded,
                desktopWindowsError = if (connectionState is QuickerConnectionState.Ready) {
                    state.desktopWindowsError
                } else {
                    null
                },
                windowActivationQueued = connectionState is QuickerConnectionState.Ready &&
                    state.windowActivationQueued,
                connectionError = when {
                    errorMessage != null -> errorMessage
                    connectionState is QuickerConnectionState.Connecting ||
                        connectionState is QuickerConnectionState.Authenticating ||
                        connectionState is QuickerConnectionState.Ready -> null
                    else -> state.connectionError
                },
            )
        }
        if (connectionState !is QuickerConnectionState.Ready) {
            screenMonitorActive = false
            activeScreenMonitorSession = null
            pendingMonitorCapture.clear()
            screenConnection = null
            pendingScreenTaps.clear()
            pendingWindowActivations.clear()
            desktopWindowsConnection = null
            desktopWindowsRefreshedAtMillis = 0L
        }

        when (connectionState) {
            is QuickerConnectionState.Ready -> {
                persistAuthenticatedConnection()
                val shouldSync = shouldSyncPanelActionsAfterReady(
                    explicitlyRequested = syncPanelActionsAfterConnect,
                    capabilities = mutableUiState.value.linkCapabilities,
                )
                syncPanelActionsAfterConnect = false
                if (shouldSync) {
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
        val passwordError = connectionPasswordValidationError(config.password)
        if (passwordError != null) {
            mutableUiState.update { it.copy(connectionError = passwordError) }
            return
        }
        val requestedTarget = config.toLinkTarget(serviceActionId)
        activeLinkTarget = requestedTarget
        connectionRuntime.setCompanionActionId(serviceActionId)
        connectionRuntime.clearIncomingFileOffer()
        val keepCapabilities = shouldKeepLinkCapabilities(
            verifiedTarget = verifiedCapabilitiesTarget,
            requestedTarget = requestedTarget,
        )
        mutableUiState.update {
            it.copy(
                linkCapabilities = it.linkCapabilities.takeIf { keepCapabilities },
                screenPreview = it.screenPreview?.copy(captureId = null),
                desktopWindows = emptyList(),
                desktopWindowsLoaded = false,
                desktopWindowsError = null,
                windowActivationQueued = false,
            )
        }
        screenConnection = null
        pendingScreenTaps.clear()
        pendingWindowActivations.clear()
        desktopWindowsConnection = null
        desktopWindowsRefreshedAtMillis = 0L
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

    private fun handleRuntimeEvent(event: QuickerConnectionRuntimeEvent) {
        when (event) {
            is QuickerConnectionRuntimeEvent.TextReceived -> {
                appendLog(
                    QuickerEventDirection.INCOMING,
                    "收到${event.source}文本（${event.text.length} 字）",
                )
                mutableNotices.tryEmit(UiNotice.Success("已复制${event.source}发来的文本"))
            }

            is QuickerConnectionRuntimeEvent.NotificationReceived -> {
                appendLog(
                    QuickerEventDirection.INCOMING,
                    "收到电脑通知（${event.bodyLength} 字）",
                )
                mutableNotices.tryEmit(
                    if (event.published) {
                        UiNotice.Success("电脑通知已送达")
                    } else {
                        UiNotice.Error("已收到电脑通知，但系统通知权限尚未开启")
                    },
                )
            }

            is QuickerConnectionRuntimeEvent.FileOffered -> {
                appendLog(
                    QuickerEventDirection.INCOMING,
                    "电脑发来文件：${compactLogText(event.name, 80)}",
                )
                mutableNotices.tryEmit(UiNotice.Success("电脑发来文件“${event.name}”"))
            }

            is QuickerConnectionRuntimeEvent.CommandRejected -> {
                appendLog(QuickerEventDirection.SYSTEM, event.summary)
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

        val target = activeLinkTarget
            ?.takeIf { it.ipAddress == config.ipAddress && it.port == config.port }
            ?: config.toLinkTarget(
                knownGoodConnection.serviceActionId ?: state.catalogActionId,
            )
        activeLinkTarget = target
        if (!shouldKeepLinkCapabilities(verifiedCapabilitiesTarget, target)) {
            mutableUiState.update { it.copy(linkCapabilities = null) }
        }

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
        screenMonitorActive = false
        pendingScreenTaps.clear()
        pendingWindowActivations.clear()
        discoveryJob?.cancel()
        toolboxJob?.cancel()
        updateChecker.close()
        updateDownloader.close()
        if (!connectionRuntime.shouldRetainConnection()) {
            connectionManager.disconnect()
        }
    }

    private companion object {
        const val MAX_LOG_COUNT = 100
        const val MAX_TOOLBOX_TEXT_BYTES = 48 * 1024
        const val TOOLBOX_COMMAND_TIMEOUT_MS = 30_000L
        const val SCREEN_CAPTURE_TIMEOUT_MS = 60_000L
        const val FILE_PICK_TIMEOUT_MS = 5 * 60_000L
        const val FILE_RECONNECT_TIMEOUT_MS = 20_000L
        const val REMOTE_CLEANUP_TIMEOUT_MS = 5_000L
        const val WINDOW_LIST_REFRESH_INTERVAL_MS = 8_000L
        const val SCREEN_CLICK_SETTLE_MS = 120L
        const val WINDOW_ACTIVATION_SETTLE_MS = 120L
    }
}

private fun decodeScreenBitmap(file: File): DecodedScreenBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (
        bounds.outWidth !in 1..MAX_DECODED_SCREEN_DIMENSION ||
        bounds.outHeight !in 1..MAX_DECODED_SCREEN_DIMENSION ||
        bounds.outWidth.toLong() * bounds.outHeight > MAX_DECODED_SCREEN_PIXELS
    ) {
        return null
    }
    val bitmap = BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
    ) ?: return null
    if (bitmap.width != bounds.outWidth || bitmap.height != bounds.outHeight) {
        bitmap.recycle()
        return null
    }
    return DecodedScreenBitmap(bitmap, bitmap.width, bitmap.height)
}

internal fun systemCommandLabel(command: QuickerSystemCommand): String = when (command) {
    QuickerSystemCommand.SHUTDOWN -> "关闭电脑"
    QuickerSystemCommand.SLEEP -> "电脑睡眠"
    QuickerSystemCommand.RESTART_QUICKER -> "重启 Quicker"
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
