package app.quickerlink.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quickerlink.EventLog
import app.quickerlink.QuickerUiState
import app.quickerlink.QuickerDiscoveryState
import app.quickerlink.QuickerViewModel
import app.quickerlink.UiNotice
import app.quickerlink.connection.QuickerConnectionState
import app.quickerlink.connection.QuickerEndpoint
import app.quickerlink.connection.QuickerEventDirection
import app.quickerlink.data.SavedAction
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class MainDestination(val label: String, val icon: ImageVector) {
    ACTIONS("动作", Icons.Outlined.Bolt),
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
    onOpenAppSettings: () -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var destination by rememberSaveable { mutableStateOf(MainDestination.ACTIONS) }
    var editedAction by remember { mutableStateOf<SavedAction?>(null) }
    var showActionEditor by remember { mutableStateOf(false) }
    var showPairingScanner by rememberSaveable { mutableStateOf(false) }
    var pairingScannerRequested by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(cameraPermissionGranted, pairingScannerRequested) {
        if (cameraPermissionGranted && pairingScannerRequested) {
            pairingScannerRequested = false
            showPairingScanner = true
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.notices.collect { notice ->
            snackbarHostState.showSnackbar(
                when (notice) {
                    is UiNotice.Error -> notice.message
                    is UiNotice.Success -> notice.message
                },
            )
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when (destination) {
            MainDestination.ACTIONS -> ActionsScreen(
                state = state,
                contentPadding = innerPadding,
                onOpenConnection = { destination = MainDestination.CONNECTION },
                onSyncPanelActions = viewModel::syncPanelActions,
                onRun = viewModel::runAction,
                onEdit = { action ->
                    editedAction = action
                    showActionEditor = true
                },
                onDelete = viewModel::deleteAction,
            )

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
                onOpenAppSettings = onOpenAppSettings,
                onSendText = viewModel::sendText,
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
    onEdit: (SavedAction) -> Unit,
    onDelete: (SavedAction) -> Unit,
) {
    var pendingConfirmation by remember { mutableStateOf<SavedAction?>(null) }
    var pendingDelete by remember { mutableStateOf<SavedAction?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val actionSections = remember(state.savedActions, searchQuery) {
        buildActionListSections(state.savedActions, searchQuery)
    }
    val visibleActionCount = actionSections.sumOf { it.actions.size }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columnCount = when {
            maxWidth < 420.dp -> 4
            maxWidth < 600.dp -> 5
            else -> 6
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columnCount),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp,
                top = contentPadding.calculateTopPadding() + 8.dp,
                end = 12.dp,
                bottom = contentPadding.calculateBottomPadding() + 88.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ConnectionSummary(
                    state = state.connectionState,
                    syncingPanelActions = state.syncingPanelActions,
                    actionCount = state.savedActions.count { it.quickerActionId != null },
                    onOpenConnection = onOpenConnection,
                    onSyncPanelActions = onSyncPanelActions,
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
                        totalCount = state.savedActions.size,
                        visibleCount = visibleActionCount,
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                    )
                }

                if (actionSections.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptySearchResults(onClear = { searchQuery = "" })
                    }
                } else {
                    actionSections.forEach { section ->
                        item(
                            key = "section:${section.key}",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            ActionGroupHeader(section.title, section.actions.size)
                        }
                        gridItems(section.actions, key = SavedAction::id) { action ->
                            SavedActionTile(
                                action = action,
                                enabled = state.connectionState is QuickerConnectionState.Ready,
                                running = action.id in state.runningActionIds,
                                onRun = {
                                    if (action.confirmBeforeRun) pendingConfirmation = action else onRun(action)
                                },
                                onEdit = { onEdit(action) },
                                onDelete = { pendingDelete = action },
                            )
                        }
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
    syncingPanelActions: Boolean,
    actionCount: Int,
    onOpenConnection: () -> Unit,
    onSyncPanelActions: () -> Unit,
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
        if (actionCount > 0 && state is QuickerConnectionState.Ready) {
            TextButton(
                onClick = onSyncPanelActions,
                modifier = Modifier.fillMaxWidth(),
                enabled = !syncingPanelActions,
            ) {
                if (syncingPanelActions) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("刷新面板动作 ($actionCount)")
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
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("动作列表", modifier = Modifier.weight(1f))
            Text(
                if (query.isBlank()) "$totalCount 个" else "$visibleCount / $totalCount 个",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("搜索动作") },
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
private fun ActionGroupHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        Text("没有匹配的动作", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onClear) { Text("清除搜索") }
    }
}

@Composable
private fun SavedActionTile(
    action: SavedAction,
    enabled: Boolean,
    running: Boolean,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val labelHeight = (34f * fontScale).dp
    val tileHeight = (134f + 34f * (fontScale - 1f)).dp
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
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
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
    onOpenAppSettings: () -> Unit,
    onSendText: (String, String) -> Unit,
    onClearLogs: () -> Unit,
) {
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var textOperation by rememberSaveable { mutableStateOf("paste") }
    var textToSend by rememberSaveable { mutableStateOf("") }
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

        if (!state.localNetworkPermissionGranted) {
            item {
                PermissionRow(
                    permanentlyDenied = state.localNetworkPermissionPermanentlyDenied,
                    onRequestPermission = onRequestPermission,
                    onOpenAppSettings = onOpenAppSettings,
                )
            }
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
                    label = { Text("连接验证码") },
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
            Spacer(Modifier.height(16.dp))
            SectionTitle("发送文本")
        }

        item {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("paste" to "粘贴", "copy" to "复制").forEachIndexed { index, (operation, label) ->
                    SegmentedButton(
                        selected = textOperation == operation,
                        onClick = { textOperation = operation },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        icon = {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        label = { Text(label) },
                    )
                }
            }
        }

        item {
            OutlinedTextField(
                value = textToSend,
                onValueChange = { textToSend = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("文本内容") },
                minLines = 3,
                maxLines = 6,
            )
        }

        item {
            Button(
                onClick = { onSendText(textOperation, textToSend) },
                modifier = Modifier.fillMaxWidth(),
                enabled = connected && textToSend.isNotEmpty(),
            ) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (textOperation == "paste") "粘贴到电脑" else "复制到电脑剪贴板")
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
        Text(log.text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.weight(1f))
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
