package app.quickerlink.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quickerlink.EventLog
import app.quickerlink.QuickerUiState
import app.quickerlink.QuickerDiscoveryState
import app.quickerlink.QuickerViewModel
import app.quickerlink.ToolboxStatus
import app.quickerlink.UiNotice
import app.quickerlink.formatTransferBytes
import app.quickerlink.connection.QuickerConnectionState
import app.quickerlink.connection.QuickerEndpoint
import app.quickerlink.connection.QuickerEventDirection
import app.quickerlink.connection.QuickerSystemCommand
import app.quickerlink.data.SavedAction
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class MainDestination(val label: String, val icon: ImageVector) {
    ACTIONS("动作", Icons.Outlined.Bolt),
    TRANSFER("传输", Icons.Outlined.SwapHoriz),
    CONNECTION("连接", Icons.Outlined.Settings),
    ABOUT("关于", Icons.Outlined.Info),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickerApp(
    viewModel: QuickerViewModel,
    onRequestLocalNetworkPermission: () -> Unit,
    cameraPermissionGranted: Boolean,
    cameraPermissionPermanentlyDenied: Boolean,
    onRequestCameraPermission: () -> Unit,
    notificationPermissionGranted: Boolean,
    notificationPermissionPermanentlyDenied: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onChooseFile: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val actionsStateHolder = rememberSaveableStateHolder()
    var destination by rememberSaveable { mutableStateOf(MainDestination.ACTIONS) }
    var editedAction by remember { mutableStateOf<SavedAction?>(null) }
    var showActionEditor by remember { mutableStateOf(false) }
    var showPairingScanner by rememberSaveable { mutableStateOf(false) }
    var pairingScannerRequested by rememberSaveable { mutableStateOf(false) }
    var showQuickInput by rememberSaveable { mutableStateOf(false) }
    var quickInputText by rememberSaveable { mutableStateOf("") }
    var quickInputAppendEnter by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(cameraPermissionGranted, pairingScannerRequested) {
        if (cameraPermissionGranted && pairingScannerRequested) {
            pairingScannerRequested = false
            showPairingScanner = true
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.notices.collect { notice ->
            when (notice) {
                is UiNotice.Error -> snackbarHostState.showSnackbar(notice.message)
                is UiNotice.Success -> snackbarHostState.showSnackbar(notice.message)
                is UiNotice.ActionSent -> {
                    val result = snackbarHostState.showSnackbar(
                        message = notice.message,
                        actionLabel = QUICK_INPUT_SNACKBAR_ACTION,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        quickInputText = ""
                        quickInputAppendEnter = true
                        showQuickInput = true
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quicker Link", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    if (destination == MainDestination.ACTIONS) {
                        IconButton(
                            onClick = {
                                editedAction = null
                                showActionEditor = true
                            },
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = "添加动作")
                        }
                    }
                    ConnectionStatusIcon(state.connectionState)
                },
            )
        },
        bottomBar = {
            NavigationBar {
                MainDestination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                if (data.visuals.actionLabel == QUICK_INPUT_SNACKBAR_ACTION) {
                    Snackbar(
                        action = {
                            IconButton(onClick = data::performAction) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.Send,
                                    contentDescription = "继续发送文本",
                                )
                            }
                        },
                    ) {
                        Text(data.visuals.message)
                    }
                } else {
                    Snackbar { Text(data.visuals.message) }
                }
            }
        },
    ) { innerPadding ->
        when (destination) {
            MainDestination.ACTIONS -> actionsStateHolder.SaveableStateProvider(ACTIONS_SCREEN_STATE_KEY) {
                ActionsScreen(
                    state = state,
                    contentPadding = innerPadding,
                    onOpenConnection = { destination = MainDestination.CONNECTION },
                    onSyncPanelActions = viewModel::syncPanelActions,
                    onRun = viewModel::runAction,
                    onStop = viewModel::stopAction,
                    onEdit = { action ->
                        editedAction = action
                        showActionEditor = true
                    },
                    onDelete = viewModel::deleteAction,
                )
            }

            MainDestination.TRANSFER -> actionsStateHolder.SaveableStateProvider(TRANSFER_SCREEN_STATE_KEY) {
                TransferScreen(
                    state = state,
                    contentPadding = innerPadding,
                    onOpenConnection = { destination = MainDestination.CONNECTION },
                    onTextChanged = viewModel::updateToolboxText,
                    onReadClipboard = viewModel::readComputerClipboard,
                    onSendText = viewModel::sendToolboxText,
                    onPasteText = viewModel::pasteText,
                    onCaptureScreen = viewModel::captureComputerScreen,
                    onScreenClick = viewModel::clickComputerScreen,
                    onSaveScreen = viewModel::saveScreenToDownloads,
                    onChooseFile = onChooseFile,
                    onReceiveFile = viewModel::receiveFileFromComputer,
                    onSystemCommand = viewModel::runSystemCommand,
                    onCancel = viewModel::cancelToolboxTransfer,
                    onRetry = viewModel::retryUploadConfirmation,
                    onClearStatus = viewModel::clearToolboxStatus,
                )
            }

            MainDestination.CONNECTION -> ConnectionScreen(
                state = state,
                contentPadding = innerPadding,
                onIpChanged = viewModel::updateIpAddress,
                onPortChanged = viewModel::updatePort,
                onPasswordChanged = viewModel::updatePassword,
                onRememberPasswordChanged = viewModel::updateRememberPassword,
                onConnect = viewModel::connect,
                onDiscover = viewModel::discoverAndConnect,
                onScanPairingCode = {
                    when {
                        cameraPermissionGranted -> showPairingScanner = true
                        cameraPermissionPermanentlyDenied -> onOpenAppSettings()
                        else -> {
                            pairingScannerRequested = true
                            onRequestCameraPermission()
                        }
                    }
                },
                onDisconnect = viewModel::disconnect,
                onRequestPermission = onRequestLocalNetworkPermission,
                notificationPermissionGranted = notificationPermissionGranted,
                notificationPermissionPermanentlyDenied = notificationPermissionPermanentlyDenied,
                onRequestNotificationPermission = onRequestNotificationPermission,
                onBackgroundConnectionChanged = { enabled ->
                    when {
                        !enabled -> viewModel.setBackgroundConnectionEnabled(false)
                        notificationPermissionGranted -> viewModel.setBackgroundConnectionEnabled(true)
                        notificationPermissionPermanentlyDenied -> {
                            viewModel.setBackgroundConnectionEnabled(true)
                            viewModel.reportBackgroundConnectionPermissionDenied()
                            onOpenAppSettings()
                        }
                        else -> {
                            viewModel.setBackgroundConnectionEnabled(true)
                            onRequestNotificationPermission()
                        }
                    }
                },
                onClipboardSyncChanged = viewModel::setClipboardSyncEnabled,
                onOpenAppSettings = onOpenAppSettings,
                onClearLogs = viewModel::clearLogs,
            )

            MainDestination.ABOUT -> AboutScreen(
                state = state,
                contentPadding = innerPadding,
                onCheckForUpdates = viewModel::checkForUpdates,
                onDownloadAndInstall = viewModel::downloadAndInstallUpdate,
                onInstallUpdate = viewModel::requestUpdateInstallation,
                onOpenExternalUrl = onOpenExternalUrl,
            )
        }
    }

    if (showActionEditor) {
        ActionEditorDialog(
            action = editedAction,
            onDismiss = { showActionEditor = false },
            onSave = { action ->
                viewModel.saveAction(action)
                showActionEditor = false
            },
        )
    }

    if (showQuickInput) {
        QuickInputDialog(
            text = quickInputText,
            appendEnter = quickInputAppendEnter,
            onTextChanged = { quickInputText = it.take(16_000) },
            onAppendEnterChanged = { quickInputAppendEnter = it },
            onDismiss = { showQuickInput = false },
            onSend = {
                viewModel.sendQuickInput(quickInputText, quickInputAppendEnter)
                showQuickInput = false
            },
        )
    }

    state.incomingFileOffer?.let { offer ->
        AlertDialog(
            onDismissRequest = viewModel::rejectIncomingFileOffer,
            icon = { Icon(Icons.Outlined.Download, contentDescription = null) },
            title = { Text("电脑发来文件") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(offer.descriptor.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        formatTransferBytes(offer.descriptor.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = viewModel::acceptIncomingFileOffer,
                    enabled = state.toolboxStatus !is ToolboxStatus.Working,
                ) {
                    Icon(Icons.Outlined.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("接收")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::rejectIncomingFileOffer) { Text("拒绝") }
            },
        )
    }

    if (showPairingScanner) {
        PairingQrScannerDialog(
            onResult = { payload ->
                showPairingScanner = false
                viewModel.connectFromPairingCode(payload)
            },
            onDismiss = { showPairingScanner = false },
        )
    }

    if (state.companionActionPromptVisible) {
        AlertDialog(
            onDismissRequest = viewModel::dismissCompanionActionPrompt,
            icon = { Icon(Icons.Outlined.Link, contentDescription = null) },
            title = { Text("请安装或更新 Quicker Link 动作") },
            text = {
                Text("刷新面板动作需要先在电脑上安装或更新配套的 Quicker Link 动作。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissCompanionActionPrompt()
                        onOpenExternalUrl(ProductLinks.COMPANION_ACTION)
                    },
                ) {
                    Text("打开动作网页")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissCompanionActionPrompt) {
                    Text("稍后")
                }
            },
        )
    }
}

@Composable
private fun QuickInputDialog(
    text: String,
    appendEnter: Boolean,
    onTextChanged: (String) -> Unit,
    onAppendEnterChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSend: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null) },
        title = { Text("继续输入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("发送到电脑当前窗口") },
                    minLines = 3,
                    maxLines = 7,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = appendEnter,
                            role = Role.Switch,
                            onValueChange = onAppendEnterChanged,
                        )
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("发送后按回车", modifier = Modifier.weight(1f))
                    Switch(checked = appendEnter, onCheckedChange = null)
                }
            }
        },
        confirmButton = {
            Button(onClick = onSend, enabled = text.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("发送")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ConnectionStatusIcon(state: QuickerConnectionState) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = connectionStateDescription(state) },
        contentAlignment = Alignment.Center,
    ) {
        ConnectionStateGraphic(state = state, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ConnectionStateGraphic(
    state: QuickerConnectionState,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is QuickerConnectionState.Connecting,
        QuickerConnectionState.Authenticating,
        is QuickerConnectionState.Reconnecting,
        -> CircularProgressIndicator(
            modifier = modifier,
            color = MaterialTheme.colorScheme.secondary,
            strokeWidth = 2.5.dp,
        )

        is QuickerConnectionState.Ready -> Icon(
            Icons.Outlined.Wifi,
            contentDescription = null,
            modifier = modifier,
            tint = MaterialTheme.colorScheme.primary,
        )

        is QuickerConnectionState.AuthFailed,
        is QuickerConnectionState.Error,
        -> Icon(
            Icons.Outlined.ErrorOutline,
            contentDescription = null,
            modifier = modifier,
            tint = MaterialTheme.colorScheme.error,
        )

        QuickerConnectionState.Disconnected -> Icon(
            Icons.Outlined.WifiOff,
            contentDescription = null,
            modifier = modifier,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun connectionStateDescription(state: QuickerConnectionState): String = when (state) {
    is QuickerConnectionState.Ready -> "已连接"
    is QuickerConnectionState.Connecting -> "正在连接"
    QuickerConnectionState.Authenticating -> "正在认证"
    is QuickerConnectionState.Reconnecting -> "正在重连"
    is QuickerConnectionState.AuthFailed -> "认证失败"
    is QuickerConnectionState.Error -> "连接异常"
    QuickerConnectionState.Disconnected -> "未连接"
}

@Composable
private fun ActionsScreen(
    state: QuickerUiState,
    contentPadding: PaddingValues,
    onOpenConnection: () -> Unit,
    onSyncPanelActions: () -> Unit,
    onRun: (SavedAction) -> Unit,
    onStop: (SavedAction) -> Unit,
    onEdit: (SavedAction) -> Unit,
    onDelete: (SavedAction) -> Unit,
) {
    var pendingConfirmation by remember { mutableStateOf<SavedAction?>(null) }
    var pendingDelete by remember { mutableStateOf<SavedAction?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedSectionKey by rememberSaveable { mutableStateOf<String?>(null) }
    val gridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()
    val actionSections = remember(state.savedActions) {
        buildActionListSections(state.savedActions, query = "")
    }
    val selectedSectionIndex = resolveActionSectionIndex(actionSections, selectedSectionKey)
    val selectedSection = actionSections.getOrNull(selectedSectionIndex)
    val visibleActions = remember(state.savedActions, selectedSection?.key, searchQuery) {
        visibleActionsForSection(state.savedActions, selectedSection?.key, searchQuery)
    }

    LaunchedEffect(actionSections.map(ActionListSection::key), selectedSection?.key) {
        if (selectedSectionKey != selectedSection?.key) {
            selectedSectionKey = selectedSection?.key
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columnCount = when {
            maxWidth < 420.dp -> 4
            maxWidth < 600.dp -> 5
            else -> 6
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columnCount),
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = contentPadding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding(),
                ),
            state = gridState,
            contentPadding = PaddingValues(
                start = 12.dp,
                top = 8.dp,
                end = 12.dp,
                bottom = 88.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ConnectionSummary(
                    state = state.connectionState,
                    onOpenConnection = onOpenConnection,
                )
            }

            if (state.savedActions.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyActions(
                        connected = state.connectionState is QuickerConnectionState.Ready,
                        syncing = state.syncingPanelActions,
                        onOpenConnection = onOpenConnection,
                        onSyncPanelActions = onSyncPanelActions,
                    )
                }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ActionLibraryHeader(
                        totalCount = selectedSection?.actions?.size ?: 0,
                        visibleCount = visibleActions.size,
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        connected = state.connectionState is QuickerConnectionState.Ready,
                        syncing = state.syncingPanelActions,
                        onSync = onSyncPanelActions,
                    )
                }

                stickyHeader(key = "action-section-navigation") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 2.dp,
                    ) {
                        ActionSectionNavigation(
                            sections = actionSections,
                            selectedIndex = selectedSectionIndex,
                            onSelected = { index ->
                                selectedSectionKey = actionSections[index].key
                                coroutineScope.launch {
                                    gridState.animateScrollToItem(ACTION_NAVIGATION_GRID_INDEX)
                                }
                            },
                        )
                    }
                }

                if (visibleActions.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptySearchResults(onClear = { searchQuery = "" })
                    }
                } else {
                    gridItems(visibleActions, key = SavedAction::id) { action ->
                        SavedActionTile(
                            action = action,
                            enabled = state.connectionState is QuickerConnectionState.Ready,
                            running = action.id in state.runningActionIds,
                            onRun = {
                                if (action.confirmBeforeRun) pendingConfirmation = action else onRun(action)
                            },
                            onStop = { onStop(action) },
                            onEdit = { onEdit(action) },
                            onDelete = { pendingDelete = action },
                        )
                    }
                }
            }
        }
    }

    pendingConfirmation?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingConfirmation = null },
            icon = { Icon(Icons.Outlined.Bolt, contentDescription = null) },
            title = { Text("执行“${action.label}”？") },
            text = { Text(action.actionTarget) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingConfirmation = null
                        onRun(action)
                    },
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("执行")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirmation = null }) { Text("取消") }
            },
        )
    }

    pendingDelete?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text("删除“${action.label}”？") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDelete = null
                        onDelete(action)
                    },
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ConnectionSummary(
    state: QuickerConnectionState,
    onOpenConnection: () -> Unit,
) {
    val (title, detail, color) = when (state) {
        is QuickerConnectionState.Ready -> Triple("已连接", state.endpoint, MaterialTheme.colorScheme.primary)
        is QuickerConnectionState.Connecting -> Triple("正在连接", state.endpoint, MaterialTheme.colorScheme.secondary)
        QuickerConnectionState.Authenticating -> Triple("正在认证", "等待 Quicker 响应", MaterialTheme.colorScheme.secondary)
        is QuickerConnectionState.Reconnecting -> Triple(
            "正在重连",
            "${state.delaySeconds} 秒后重试",
            MaterialTheme.colorScheme.secondary,
        )

        is QuickerConnectionState.AuthFailed -> Triple("认证失败", state.reason, MaterialTheme.colorScheme.error)
        is QuickerConnectionState.Error -> Triple("连接异常", state.reason, MaterialTheme.colorScheme.error)
        QuickerConnectionState.Disconnected -> Triple("未连接", "", MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConnectionStateGraphic(
                state = state,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = color)
                if (detail.isNotBlank()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(onClick = onOpenConnection) {
                Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("设置")
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun EmptyActions(
    connected: Boolean,
    syncing: Boolean,
    onOpenConnection: () -> Unit,
    onSyncPanelActions: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Outlined.Bolt,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("还没有动作快捷项", style = MaterialTheme.typography.titleMedium)
            Text(
                if (connected) "从电脑同步全局与通用动作即可开始" else "先连接电脑，再同步全局与通用动作",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = if (connected) onSyncPanelActions else onOpenConnection,
                enabled = !syncing,
            ) {
                if (syncing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        if (connected) Icons.Outlined.Sync else Icons.Outlined.Link,
                        contentDescription = null,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(if (connected) "获取面板动作" else "连接 Quicker")
            }
        }
    }
}

@Composable
private fun ActionLibraryHeader(
    totalCount: Int,
    visibleCount: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    connected: Boolean,
    syncing: Boolean,
    onSync: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("动作列表", modifier = Modifier.weight(1f))
            Text(
                if (query.isBlank()) "$totalCount 个" else "$visibleCount / $totalCount 个",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onSync, enabled = connected && !syncing) {
                if (syncing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Sync, contentDescription = "刷新面板动作")
                }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("搜索当前分组") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Outlined.Close, contentDescription = "清除搜索")
                    }
                }
            } else {
                null
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
    }
}

@Composable
private fun ActionSectionNavigation(
    sections: List<ActionListSection>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    SecondaryScrollableTabRow(
        selectedTabIndex = selectedIndex,
        modifier = Modifier.fillMaxWidth(),
        edgePadding = 0.dp,
        containerColor = MaterialTheme.colorScheme.surface,
        divider = {},
    ) {
        sections.forEachIndexed { index, section ->
            Tab(
                selected = index == selectedIndex,
                onClick = { onSelected(index) },
                text = {
                    Text(
                        "${section.title}  ${section.actions.size}",
                        modifier = Modifier.widthIn(max = 180.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
private fun EmptySearchResults(onClear: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.Search,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Text("当前分组没有匹配的动作", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onClear) { Text("清除搜索") }
    }
}

@Composable
private fun SavedActionTile(
    action: SavedAction,
    enabled: Boolean,
    running: Boolean,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val labelHeight = (34f * fontScale).dp
    val tileHeight = (134f + 34f * (fontScale - 1f)).dp
    val labelStyle = MaterialTheme.typography.labelMedium
    val compactSingleToken = action.label.length >= 8 &&
        action.label.none(Char::isWhitespace) &&
        action.label.all { it.isLetterOrDigit() || it in "._-" }
    var labelFontSize by remember(action.label, fontScale) { mutableStateOf(labelStyle.fontSize) }
    val details = buildList {
        add(if (action.quickerActionId != null) "${action.syncedSceneLabel()}动作" else action.actionTarget)
        if (action.parameter.isNotEmpty()) add("有参数")
        if (action.confirmBeforeRun) add("执行前确认")
    }.joinToString(" · ")

    Card(
        onClick = onRun,
        enabled = !running,
        modifier = Modifier
            .height(tileHeight)
            .semantics {
                contentDescription = "${action.label}，$details"
                stateDescription = when {
                    running -> "正在发送"
                    enabled -> "可以执行"
                    else -> "Quicker 未连接"
                }
            },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 5.dp, top = 8.dp, end = 5.dp, bottom = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.size(44.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (running) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                } else {
                    SavedActionArtwork(action = action, enabled = enabled)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                action.label,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(labelHeight),
                style = labelStyle.copy(fontSize = labelFontSize),
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = if (compactSingleToken) 1 else 2,
                softWrap = !compactSingleToken,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { result ->
                    if (compactSingleToken && result.hasVisualOverflow && labelFontSize.value > 9f) {
                        labelFontSize = (labelFontSize.value - 0.5f).coerceAtLeast(9f).sp
                    }
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = "${action.label}的更多选项",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        if (action.quickerActionId != null) {
                            DropdownMenuItem(
                                text = { Text("运行设置") },
                                leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onEdit()
                                },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("编辑") },
                                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onEdit()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("删除") },
                                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onDelete()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("终止动作") },
                            leadingIcon = { Icon(Icons.Outlined.StopCircle, contentDescription = null) },
                            enabled = enabled && !running,
                            onClick = {
                                menuExpanded = false
                                onStop()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedActionArtwork(action: SavedAction, enabled: Boolean) {
    val icon = action.icon
    val encodedPng = icon
        ?.takeIf { it.startsWith(PNG_ICON_PREFIX) }
        ?.removePrefix(PNG_ICON_PREFIX)
    val decodedBitmap by produceState<Bitmap?>(initialValue = null, key1 = encodedPng) {
        value = encodedPng?.let { encoded ->
            withContext(Dispatchers.Default) { decodeActionIcon(encoded) }
        }
    }
    var networkLoaded by remember(icon) { mutableStateOf(false) }
    val tint = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            decodedBitmap != null -> Image(
                bitmap = requireNotNull(decodedBitmap).asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                contentScale = ContentScale.Fit,
            )

            icon?.startsWith(QUICKER_ICON_URL_PREFIX) == true -> {
                if (!networkLoaded) {
                    ActionIconPlaceholder(action.label, tint)
                }
                AsyncImage(
                    model = icon,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    contentScale = ContentScale.Fit,
                    onLoading = { networkLoaded = false },
                    onSuccess = { networkLoaded = true },
                    onError = { networkLoaded = false },
                )
            }

            else -> ActionIconPlaceholder(action.label, tint)
        }
    }
}

@Composable
private fun ActionIconPlaceholder(label: String, tint: androidx.compose.ui.graphics.Color) {
    Surface(
        modifier = Modifier.size(40.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label.trim().take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = tint,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun decodeActionIcon(encoded: String): Bitmap? {
    val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return null
    if (bytes.size !in 33..16_384) return null

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (
        bounds.outWidth !in 1..512 ||
        bounds.outHeight !in 1..512 ||
        bounds.outWidth.toLong() * bounds.outHeight > 262_144L
    ) {
        return null
    }

    var sampleSize = 1
    while (bounds.outWidth / sampleSize > 128 || bounds.outHeight / sampleSize > 128) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

private const val PNG_ICON_PREFIX = "data:image/png;base64,"
private const val QUICKER_ICON_URL_PREFIX = "https://files.getquicker.net/"
private const val QUICK_INPUT_SNACKBAR_ACTION = "quick-input"
private const val SCREEN_CLICK_REFRESH_INTERVAL_MS = 1_200L
private const val ACTION_NAVIGATION_GRID_INDEX = 2
private const val ACTIONS_SCREEN_STATE_KEY = "actions-screen"
private const val TRANSFER_SCREEN_STATE_KEY = "transfer-screen"

@Composable
private fun TransferScreen(
    state: QuickerUiState,
    contentPadding: PaddingValues,
    onOpenConnection: () -> Unit,
    onTextChanged: (String) -> Unit,
    onReadClipboard: () -> Unit,
    onSendText: () -> Unit,
    onPasteText: (String) -> Unit,
    onCaptureScreen: () -> Unit,
    onScreenClick: (String, Int, Int) -> Unit,
    onSaveScreen: () -> Unit,
    onChooseFile: () -> Unit,
    onReceiveFile: () -> Unit,
    onSystemCommand: (QuickerSystemCommand) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onClearStatus: () -> Unit,
) {
    val connected = state.connectionState is QuickerConnectionState.Ready
    val controlsLocked = state.toolboxStatus is ToolboxStatus.Working ||
        (state.toolboxStatus as? ToolboxStatus.Failed)?.canRetry == true
    var showScreenPreview by rememberSaveable { mutableStateOf(false) }
    var screenClickMode by rememberSaveable { mutableStateOf(false) }
    var pendingSystemCommand by remember { mutableStateOf<QuickerSystemCommand?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
            ),
        contentPadding = PaddingValues(start = 20.dp, top = 14.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConnectionStateGraphic(state.connectionState, Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (connected) "局域网已连接" else "尚未连接电脑",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        (state.connectionState as? QuickerConnectionState.Ready)?.endpoint
                            ?: "需要连接 Quicker",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!connected) {
                    TextButton(onClick = onOpenConnection) {
                        Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("连接")
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
        }

        if (state.toolboxStatus !is ToolboxStatus.Idle) {
            item {
                ToolboxStatusBanner(
                    status = state.toolboxStatus,
                    onCancel = onCancel,
                    onRetry = onRetry,
                    retryEnabled = connected,
                    onClear = onClearStatus,
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "当前屏幕",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    IconButton(
                        onClick = onCaptureScreen,
                        enabled = connected && !controlsLocked,
                    ) {
                        Icon(Icons.Outlined.Sync, contentDescription = "刷新电脑屏幕")
                    }
                    IconButton(
                        onClick = onSaveScreen,
                        enabled = state.screenPreview != null && !controlsLocked,
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = "保存屏幕快照")
                    }
                }

                val preview = state.screenPreview
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clickable(
                            enabled = preview != null || connected && !controlsLocked,
                            onClick = {
                                if (preview == null) onCaptureScreen() else showScreenPreview = true
                            },
                        ),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    if (preview == null) {
                        Box(contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Outlined.Visibility,
                                    contentDescription = null,
                                    modifier = Modifier.size(34.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "点击获取电脑当前屏幕",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        AsyncImage(
                            model = File(preview.path),
                            contentDescription = "电脑当前屏幕，${preview.capturedAt}",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(top = 18.dp))
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "文本",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedTextField(
                    value = state.toolboxText,
                    onValueChange = onTextChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("文本内容") },
                    minLines = 3,
                    maxLines = 7,
                    enabled = !controlsLocked,
                    trailingIcon = if (state.toolboxText.isNotEmpty()) {
                        {
                            IconButton(
                                onClick = { onTextChanged("") },
                                enabled = !controlsLocked,
                            ) {
                                Icon(Icons.Outlined.Clear, contentDescription = "清空文本")
                            }
                        }
                    } else {
                        null
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onReadClipboard,
                        enabled = connected && !controlsLocked,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("读取电脑")
                    }
                    Button(
                        onClick = onSendText,
                        enabled = connected && !controlsLocked && state.toolboxText.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("复制到电脑")
                    }
                }
                OutlinedButton(
                    onClick = { onPasteText(state.toolboxText) },
                    enabled = connected && !controlsLocked && state.toolboxText.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("粘贴到电脑当前窗口")
                }
            }
            HorizontalDivider(modifier = Modifier.padding(top = 18.dp))
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "文件",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "单个文件不超过 64 MiB",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FilledTonalButton(
                        onClick = onChooseFile,
                        enabled = connected && !controlsLocked,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.UploadFile, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("发送文件")
                    }
                    FilledTonalButton(
                        onClick = onReceiveFile,
                        enabled = connected && !controlsLocked,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("接收文件")
                    }
                }
            }
        }

        if (state.linkCapabilities?.systemControl == true) {
            item {
                HorizontalDivider(modifier = Modifier.padding(top = 2.dp))
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "电脑控制",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SystemCommandButton(
                            label = "睡眠",
                            icon = Icons.Outlined.Bedtime,
                            enabled = connected && !controlsLocked,
                            modifier = Modifier.weight(1f),
                            onClick = { pendingSystemCommand = QuickerSystemCommand.SLEEP },
                        )
                        SystemCommandButton(
                            label = "关机",
                            icon = Icons.Outlined.PowerSettingsNew,
                            enabled = connected && !controlsLocked,
                            modifier = Modifier.weight(1f),
                            onClick = { pendingSystemCommand = QuickerSystemCommand.SHUTDOWN },
                        )
                        SystemCommandButton(
                            label = "重启\nQuicker",
                            icon = Icons.Outlined.RestartAlt,
                            enabled = connected && !controlsLocked,
                            modifier = Modifier.weight(1f),
                            onClick = { pendingSystemCommand = QuickerSystemCommand.RESTART_QUICKER },
                        )
                    }
                }
            }
        }
    }

    if (showScreenPreview) {
        state.screenPreview?.let { preview ->
            val imageDimensions by produceState<Pair<Int, Int>?>(
                initialValue = null,
                key1 = preview.path,
            ) {
                value = withContext(Dispatchers.IO) {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(preview.path, options)
                    if (options.outWidth > 0 && options.outHeight > 0) {
                        options.outWidth to options.outHeight
                    } else {
                        null
                    }
                }
            }
            val captureId = preview.captureId
            val clickAvailable = state.linkCapabilities?.screenClick == true &&
                captureId != null && connected && !controlsLocked && imageDimensions != null
            val automaticRefreshEnabled = showScreenPreview &&
                screenClickMode &&
                connected &&
                !controlsLocked &&
                state.toolboxStatus !is ToolboxStatus.Failed
            LaunchedEffect(
                automaticRefreshEnabled,
                preview.captureId,
            ) {
                if (automaticRefreshEnabled) {
                    delay(SCREEN_CLICK_REFRESH_INTERVAL_MS)
                    onCaptureScreen()
                }
            }
            val screenTapModifier = if (screenClickMode && clickAvailable) {
                Modifier.pointerInput(preview.path, captureId, imageDimensions) {
                    detectTapGestures { offset ->
                        val dimensions = imageDimensions ?: return@detectTapGestures
                        val point = mapScreenTap(
                            containerWidth = size.width.toFloat(),
                            containerHeight = size.height.toFloat(),
                            imageWidth = dimensions.first,
                            imageHeight = dimensions.second,
                            tapX = offset.x,
                            tapY = offset.y,
                        ) ?: return@detectTapGestures
                        onScreenClick(requireNotNull(captureId), point.x, point.y)
                    }
                }
            } else {
                Modifier
            }
            Dialog(
                onDismissRequest = {
                    screenClickMode = false
                    showScreenPreview = false
                },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = File(preview.path),
                        contentDescription = "电脑当前屏幕",
                        modifier = Modifier
                            .fillMaxSize()
                            .then(screenTapModifier),
                        contentScale = ContentScale.Fit,
                    )
                    if (state.linkCapabilities?.screenClick == true) {
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(18.dp)
                                .widthIn(max = 184.dp),
                        ) {
                            SegmentedButton(
                                selected = !screenClickMode,
                                onClick = { screenClickMode = false },
                                shape = SegmentedButtonDefaults.itemShape(0, 2),
                                icon = {
                                    Icon(
                                        Icons.Outlined.Visibility,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                label = { Text("查看") },
                            )
                            SegmentedButton(
                                selected = screenClickMode,
                                onClick = { screenClickMode = true },
                                enabled = clickAvailable,
                                shape = SegmentedButtonDefaults.itemShape(1, 2),
                                icon = {
                                    Icon(
                                        Icons.Outlined.TouchApp,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                label = { Text("点击") },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(18.dp),
                    ) {
                        IconButton(
                            onClick = onCaptureScreen,
                            enabled = connected && !controlsLocked,
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.58f), CircleShape),
                        ) {
                            Icon(Icons.Outlined.Sync, contentDescription = "刷新屏幕", tint = Color.White)
                        }
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                screenClickMode = false
                                showScreenPreview = false
                            },
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.58f), CircleShape),
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = "关闭预览", tint = Color.White)
                        }
                    }
                }
            }
        }
    }

    pendingSystemCommand?.let { command ->
        AlertDialog(
            onDismissRequest = { pendingSystemCommand = null },
            icon = { Icon(systemCommandIcon(command), contentDescription = null) },
            title = { Text("确认${systemCommandTitle(command)}？") },
            text = { Text(systemCommandConfirmation(command)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingSystemCommand = null
                        onSystemCommand(command)
                    },
                    enabled = connected && !controlsLocked,
                ) {
                    Text("确认执行", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSystemCommand = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun SystemCommandButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(78.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(21.dp))
            Spacer(Modifier.height(5.dp))
            Text(
                label,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
            )
        }
    }
}

private fun systemCommandTitle(command: QuickerSystemCommand): String = when (command) {
    QuickerSystemCommand.SHUTDOWN -> "关闭电脑"
    QuickerSystemCommand.SLEEP -> "让电脑睡眠"
    QuickerSystemCommand.RESTART_QUICKER -> "重启 Quicker"
}

private fun systemCommandConfirmation(command: QuickerSystemCommand): String = when (command) {
    QuickerSystemCommand.SHUTDOWN -> "电脑将立即关机，未保存的内容可能丢失。"
    QuickerSystemCommand.SLEEP -> "电脑将进入睡眠状态，当前连接会暂时断开。"
    QuickerSystemCommand.RESTART_QUICKER -> "Quicker 将重新启动，当前连接会短暂断开。"
}

private fun systemCommandIcon(command: QuickerSystemCommand): ImageVector = when (command) {
    QuickerSystemCommand.SHUTDOWN -> Icons.Outlined.PowerSettingsNew
    QuickerSystemCommand.SLEEP -> Icons.Outlined.Bedtime
    QuickerSystemCommand.RESTART_QUICKER -> Icons.Outlined.RestartAlt
}

@Composable
private fun ToolboxStatusBanner(
    status: ToolboxStatus,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    retryEnabled: Boolean,
    onClear: () -> Unit,
) {
    val containerColor = when (status) {
        is ToolboxStatus.Failed -> MaterialTheme.colorScheme.errorContainer
        is ToolboxStatus.Success -> MaterialTheme.colorScheme.primaryContainer
        is ToolboxStatus.Working -> MaterialTheme.colorScheme.secondaryContainer
        ToolboxStatus.Idle -> Color.Transparent
    }
    val title = when (status) {
        is ToolboxStatus.Failed -> status.title
        is ToolboxStatus.Success -> status.title
        is ToolboxStatus.Working -> status.title
        ToolboxStatus.Idle -> ""
    }
    val detail = when (status) {
        is ToolboxStatus.Failed -> status.message
        is ToolboxStatus.Success -> status.detail
        is ToolboxStatus.Working -> status.detail
        ToolboxStatus.Idle -> ""
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, top = 11.dp, end = 8.dp, bottom = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when (status) {
                    is ToolboxStatus.Working -> if (status.canCancel) {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Outlined.Close, contentDescription = "取消传输")
                        }
                    }
                    is ToolboxStatus.Failed,
                    is ToolboxStatus.Success,
                    -> {
                        if (status is ToolboxStatus.Failed && status.canRetry) {
                            IconButton(onClick = onRetry, enabled = retryEnabled) {
                                Icon(Icons.Outlined.Sync, contentDescription = "重新确认保存结果")
                            }
                        }
                        IconButton(onClick = onClear) {
                            Icon(Icons.Outlined.Close, contentDescription = "关闭状态")
                        }
                    }
                    ToolboxStatus.Idle -> Unit
                }
            }
            if (status is ToolboxStatus.Working) {
                Spacer(Modifier.height(8.dp))
                if (status.percent == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { status.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionScreen(
    state: QuickerUiState,
    contentPadding: PaddingValues,
    onIpChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onRememberPasswordChanged: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onDiscover: () -> Unit,
    onScanPairingCode: () -> Unit,
    onDisconnect: () -> Unit,
    onRequestPermission: () -> Unit,
    notificationPermissionGranted: Boolean,
    notificationPermissionPermanentlyDenied: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onBackgroundConnectionChanged: (Boolean) -> Unit,
    onClipboardSyncChanged: (Boolean) -> Unit,
    onOpenAppSettings: () -> Unit,
    onClearLogs: () -> Unit,
) {
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var logsExpanded by rememberSaveable { mutableStateOf(false) }
    val connected = state.connectionState is QuickerConnectionState.Ready
    val discovering = state.discoveryState is QuickerDiscoveryState.Scanning
    val busy = state.connectionState is QuickerConnectionState.Connecting ||
        state.connectionState is QuickerConnectionState.Authenticating ||
        state.connectionState is QuickerConnectionState.Reconnecting ||
        discovering
    val hasKnownEndpoint = QuickerEndpoint.isPrivateIpv4(state.ipAddress)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionTitle("连接 Quicker") }

        item {
            ConnectionStatePanel(
                connectionState = state.connectionState,
                discoveryState = state.discoveryState,
            )
        }

        if ((connected || busy) && state.password.isEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Text(
                        "当前未使用连接验证码。仅建议在可信局域网使用，并优先在 Quicker 中设置验证码。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (!state.localNetworkPermissionGranted) {
            item {
                PermissionRow(
                    permanentlyDenied = state.localNetworkPermissionPermanentlyDenied,
                    onRequestPermission = onRequestPermission,
                    onOpenAppSettings = onOpenAppSettings,
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "增强功能",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BackgroundConnectionRow(
                    checked = state.backgroundConnectionEnabled,
                    notificationPermissionGranted = notificationPermissionGranted,
                    notificationPermissionPermanentlyDenied = notificationPermissionPermanentlyDenied,
                    onCheckedChange = onBackgroundConnectionChanged,
                    onRequestNotificationPermission = if (notificationPermissionPermanentlyDenied) {
                        onOpenAppSettings
                    } else {
                        onRequestNotificationPermission
                    },
                )
                HorizontalDivider()
                ClipboardSyncRow(
                    checked = state.clipboardSyncEnabled,
                    onCheckedChange = onClipboardSyncChanged,
                )
            }
        }

        state.backgroundConnectionError?.let { error ->
            item { ConnectionErrorRow(error) }
        }

        state.clipboardSyncError?.let { error ->
            item { ConnectionErrorRow(error) }
        }

        state.connectionError
            ?.takeUnless {
                state.connectionState is QuickerConnectionState.AuthFailed ||
                    state.connectionState is QuickerConnectionState.Error
            }
            ?.let { error ->
                item { ConnectionErrorRow(error) }
            }

        (state.discoveryState as? QuickerDiscoveryState.Failed)?.let { failure ->
            item { ConnectionErrorRow(failure.reason) }
        }

        if (connected || busy) {
            item {
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.LinkOff, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (discovering) "取消查找" else if (busy) "取消连接" else "断开连接")
                }
            }
        } else {
            if (!hasKnownEndpoint) {
                item {
                    Button(
                        onClick = onScanPairingCode,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.localNetworkPermissionGranted,
                    ) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("扫描配对码")
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = state.password,
                    onValueChange = onPasswordChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("连接验证码（推荐）") },
                    supportingText = if (state.password.isEmpty()) {
                        {
                            Text(
                                "可以留空，但同一局域网内的设备将无需验证码即可连接",
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Password, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (passwordVisible) "隐藏验证码" else "显示验证码",
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }

            item {
                RememberConnectionRow(
                    checked = state.rememberPassword,
                    onCheckedChange = onRememberPasswordChanged,
                )
            }

            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (hasKnownEndpoint) {
                        Button(
                            onClick = onConnect,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.localNetworkPermissionGranted,
                        ) {
                            Icon(Icons.Outlined.Link, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("连接 Quicker")
                        }
                    } else {
                        OutlinedButton(
                            onClick = onDiscover,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.localNetworkPermissionGranted,
                        ) {
                            Icon(Icons.Outlined.Search, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("自动查找并连接")
                        }
                    }
                    if (hasKnownEndpoint) {
                        OutlinedButton(
                            onClick = onScanPairingCode,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.localNetworkPermissionGranted,
                        ) {
                            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("扫描新的配对码")
                        }
                    }
                    if (hasKnownEndpoint) {
                        TextButton(
                            onClick = onDiscover,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.localNetworkPermissionGranted,
                        ) {
                            Icon(Icons.Outlined.Search, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("查找其他电脑")
                        }
                    }
                }
            }
        }

        if (!connected && !busy) {
            item {
                TextButton(
                    onClick = { advancedExpanded = !advancedExpanded },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("高级设置", modifier = Modifier.weight(1f))
                    Icon(
                        if (advancedExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (advancedExpanded) "收起高级设置" else "展开高级设置",
                    )
                }
            }

            if (advancedExpanded) {
                item {
                    EndpointFields(
                        ipAddress = state.ipAddress,
                        port = state.port,
                        onIpChanged = onIpChanged,
                        onPortChanged = onPortChanged,
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { logsExpanded = !logsExpanded },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "连接记录${if (state.logs.isEmpty()) "" else " (${state.logs.size})"}",
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (logsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (logsExpanded) "收起连接记录" else "展开连接记录",
                )
            }
        }

        if (logsExpanded) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "最近记录",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(onClick = onClearLogs, enabled = state.logs.isNotEmpty()) {
                        Icon(Icons.Outlined.ClearAll, contentDescription = "清空记录")
                    }
                }
            }

            if (state.logs.isEmpty()) {
                item {
                    Text(
                        "暂无记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            } else {
                items(state.logs.take(30)) { log -> EventLogRow(log) }
            }
        }
    }
}

@Composable
private fun BackgroundConnectionRow(
    checked: Boolean,
    notificationPermissionGranted: Boolean,
    notificationPermissionPermanentlyDenied: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onRequestNotificationPermission: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "后台接收与连接",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (checked) {
                        "切换应用或锁屏后仍接收电脑消息和文件"
                    } else {
                        "已关闭；App 退到后台后会断开连接"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = null)
        }
        if (checked && !notificationPermissionGranted) {
            Text(
                "后台连接已保持；通知权限仅影响电脑通知显示",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = onRequestNotificationPermission,
                modifier = Modifier.align(Alignment.End),
            ) {
                Icon(Icons.Outlined.Notifications, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (notificationPermissionPermanentlyDenied) "打开通知设置" else "允许电脑通知")
            }
        }
    }
}

@Composable
private fun ClipboardSyncRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "前台剪贴板同步",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (checked) {
                    "App 可见时双向同步短文本"
                } else {
                    "Android 10+ 不允许普通 App 在后台读取剪贴板"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ConnectionStatePanel(
    connectionState: QuickerConnectionState,
    discoveryState: QuickerDiscoveryState,
) {
    val discovering = discoveryState as? QuickerDiscoveryState.Scanning
    val (title, detail) = if (discovering != null) {
        "正在查找 Quicker" to discovering.subnet
    } else {
        when (connectionState) {
            QuickerConnectionState.Disconnected -> "尚未连接" to "等待选择一台电脑"
            is QuickerConnectionState.Connecting -> "正在连接" to connectionState.endpoint
            QuickerConnectionState.Authenticating -> "正在认证" to "等待 Quicker 确认连接"
            is QuickerConnectionState.Ready -> "已连接" to connectionState.endpoint
            is QuickerConnectionState.Reconnecting -> {
                "正在重新连接" to "第 ${connectionState.attempt} 次尝试，${connectionState.delaySeconds} 秒后重试"
            }
            is QuickerConnectionState.AuthFailed -> "认证失败" to connectionState.reason
            is QuickerConnectionState.Error -> "连接异常" to connectionState.reason
        }
    }
    val (containerColor, contentColor) = when {
        discovering != null -> MaterialTheme.colorScheme.secondaryContainer to
            MaterialTheme.colorScheme.onSecondaryContainer
        connectionState is QuickerConnectionState.Ready -> MaterialTheme.colorScheme.primaryContainer to
            MaterialTheme.colorScheme.onPrimaryContainer
        connectionState is QuickerConnectionState.AuthFailed || connectionState is QuickerConnectionState.Error -> {
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        }
        else -> MaterialTheme.colorScheme.surfaceContainerLow to MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (discovering != null) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
            } else {
                ConnectionStateGraphic(connectionState, modifier = Modifier.size(24.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RememberConnectionRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text("加密保存并自动连接", modifier = Modifier.weight(1f))
    }
}

@Composable
private fun EndpointFields(
    ipAddress: String,
    port: String,
    onIpChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
) {
    @Composable
    fun IpField(modifier: Modifier) {
        OutlinedTextField(
            value = ipAddress,
            onValueChange = onIpChanged,
            modifier = modifier,
            label = { Text("电脑 IPv4") },
            placeholder = { Text("192.168.1.56") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Next,
            ),
        )
    }

    @Composable
    fun PortField(modifier: Modifier) {
        OutlinedTextField(
            value = port,
            onValueChange = onPortChanged,
            modifier = modifier,
            label = { Text("WSS 端口") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth < 360.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                IpField(Modifier.fillMaxWidth())
                PortField(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IpField(Modifier.weight(1f))
                PortField(Modifier.width(116.dp))
            }
        }
    }
}

@Composable
private fun PermissionRow(
    permanentlyDenied: Boolean,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        BoxWithConstraints(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            val message = if (permanentlyDenied) {
                "局域网权限已被永久拒绝"
            } else {
                "需要局域网权限才能连接电脑"
            }
            val action = if (permanentlyDenied) "打开设置" else "授权"
            val onClick = if (permanentlyDenied) onOpenAppSettings else onRequestPermission

            if (maxWidth < 340.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text(message, modifier = Modifier.weight(1f))
                    }
                    TextButton(onClick = onClick, modifier = Modifier.align(Alignment.End)) {
                        Text(action)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(message, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClick) { Text(action) }
                }
            }
        }
    }
}

@Composable
private fun ConnectionErrorRow(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun EventLogRow(log: EventLog) {
    val color = when (log.direction) {
        QuickerEventDirection.SYSTEM -> MaterialTheme.colorScheme.onSurfaceVariant
        QuickerEventDirection.OUTGOING -> MaterialTheme.colorScheme.primary
        QuickerEventDirection.INCOMING -> MaterialTheme.colorScheme.tertiary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            log.time,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(62.dp),
        )
        Text(
            log.text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            modifier = Modifier.weight(1f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionEditorDialog(
    action: SavedAction?,
    onDismiss: () -> Unit,
    onSave: (SavedAction) -> Unit,
) {
    var label by rememberSaveable(action?.id) { mutableStateOf(action?.label.orEmpty()) }
    var target by rememberSaveable(action?.id) { mutableStateOf(action?.actionTarget.orEmpty()) }
    var parameter by rememberSaveable(action?.id) { mutableStateOf(action?.parameter.orEmpty()) }
    var parameterMenuExpanded by remember(action?.id) { mutableStateOf(false) }
    var confirm by rememberSaveable(action?.id) { mutableStateOf(action?.confirmBeforeRun ?: false) }
    val synced = action?.quickerActionId != null
    val valid = synced || (label.isNotBlank() && target.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (synced) Icons.Outlined.Settings else Icons.Outlined.Bolt,
                contentDescription = null,
            )
        },
        title = {
            Text(
                when {
                    action == null -> "添加动作"
                    synced -> "运行设置"
                    else -> "编辑动作"
                },
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (synced) {
                    Text(
                        text = action?.label.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                } else {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("显示名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = target,
                        onValueChange = { target = it },
                        label = { Text("Quicker 动作名称或 ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (action?.parameterChoices.isNullOrEmpty()) {
                    OutlinedTextField(
                        value = parameter,
                        onValueChange = { parameter = it },
                        label = { Text("动作参数") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    ExposedDropdownMenuBox(
                        expanded = parameterMenuExpanded,
                        onExpandedChange = { parameterMenuExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = parameter,
                            onValueChange = { parameter = it },
                            label = { Text("动作参数") },
                            minLines = 2,
                            maxLines = 4,
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = parameterMenuExpanded)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = true),
                        )
                        ExposedDropdownMenu(
                            expanded = parameterMenuExpanded,
                            onDismissRequest = { parameterMenuExpanded = false },
                        ) {
                            action.parameterChoices.forEach { choice ->
                                DropdownMenuItem(
                                    text = {
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                text = choice.label,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (choice.value != choice.label) {
                                                Text(
                                                    text = choice.value,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        parameter = choice.value
                                        parameterMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = confirm,
                            role = Role.Switch,
                            onValueChange = { confirm = it },
                        )
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("执行前确认", modifier = Modifier.weight(1f))
                    Switch(checked = confirm, onCheckedChange = null)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    onSave(
                        SavedAction(
                            id = action?.id ?: SavedAction(label = "", actionTarget = "").id,
                            label = if (synced) requireNotNull(action).label else label.trim(),
                            actionTarget = if (synced) requireNotNull(action).actionTarget else target.trim(),
                            parameter = parameter,
                            parameterChoices = action?.parameterChoices.orEmpty(),
                            confirmBeforeRun = confirm,
                            quickerActionId = action?.quickerActionId,
                            sourceGroup = action?.sourceGroup,
                            sourceScene = action?.sourceScene,
                            icon = action?.icon,
                        ),
                    )
                },
            ) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
