package app.quickerlink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
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
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quickerlink.EventLog
import app.quickerlink.QuickerUiState
import app.quickerlink.QuickerDiscoveryState
import app.quickerlink.QuickerViewModel
import app.quickerlink.UiNotice
import app.quickerlink.connection.QuickerConnectionState
import app.quickerlink.connection.QuickerEventDirection
import app.quickerlink.data.SavedAction

private enum class MainDestination(val label: String, val icon: ImageVector) {
    ACTIONS("动作", Icons.Outlined.Bolt),
    CONNECTION("连接", Icons.Outlined.Settings),
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
        floatingActionButton = {
            if (destination == MainDestination.ACTIONS) {
                ExtendedFloatingActionButton(
                    onClick = {
                        editedAction = null
                        showActionEditor = true
                    },
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text("添加动作") },
                )
            }
        },
    ) { innerPadding ->
        when (destination) {
            MainDestination.ACTIONS -> ActionsScreen(
                state = state,
                contentPadding = innerPadding,
                onOpenConnection = { destination = MainDestination.CONNECTION },
                onSyncGlobalActions = viewModel::syncGlobalActions,
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
}

@Composable
private fun ConnectionStatusIcon(state: QuickerConnectionState) {
    val (icon, tint, description) = when (state) {
        is QuickerConnectionState.Ready -> Triple(
            Icons.Outlined.Wifi,
            MaterialTheme.colorScheme.primary,
            "已连接",
        )

        is QuickerConnectionState.Connecting,
        QuickerConnectionState.Authenticating,
        is QuickerConnectionState.Reconnecting,
        -> Triple(Icons.Outlined.Sync, MaterialTheme.colorScheme.secondary, "连接中")

        is QuickerConnectionState.AuthFailed,
        is QuickerConnectionState.Error,
        -> Triple(Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error, "连接异常")

        QuickerConnectionState.Disconnected -> Triple(
            Icons.Outlined.WifiOff,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "未连接",
        )
    }
    Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun ActionsScreen(
    state: QuickerUiState,
    contentPadding: PaddingValues,
    onOpenConnection: () -> Unit,
    onSyncGlobalActions: () -> Unit,
    onRun: (SavedAction) -> Unit,
    onEdit: (SavedAction) -> Unit,
    onDelete: (SavedAction) -> Unit,
) {
    var pendingConfirmation by remember { mutableStateOf<SavedAction?>(null) }
    var pendingDelete by remember { mutableStateOf<SavedAction?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 88.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ConnectionSummary(
                state = state.connectionState,
                syncingGlobalActions = state.syncingGlobalActions,
                onOpenConnection = onOpenConnection,
                onSyncGlobalActions = onSyncGlobalActions,
            )
        }

        if (state.savedActions.isEmpty()) {
            item {
                EmptyActions()
            }
        } else {
            items(state.savedActions, key = SavedAction::id) { action ->
                SavedActionItem(
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
    syncingGlobalActions: Boolean,
    onOpenConnection: () -> Unit,
    onSyncGlobalActions: () -> Unit,
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
            Icon(
                if (state is QuickerConnectionState.Ready) Icons.Outlined.CheckCircle else Icons.Outlined.LinkOff,
                contentDescription = null,
                tint = color,
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
        TextButton(
            onClick = onSyncGlobalActions,
            modifier = Modifier.fillMaxWidth(),
            enabled = state is QuickerConnectionState.Ready && !syncingGlobalActions,
        ) {
            if (syncingGlobalActions) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text("同步全局动作")
        }
    }
    HorizontalDivider()
}

@Composable
private fun EmptyActions() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.Bolt,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text("还没有已保存动作", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun SavedActionItem(
    action: SavedAction,
    enabled: Boolean,
    running: Boolean,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        onClick = onRun,
        enabled = enabled && !running,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (running) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                } else {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = "执行", tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    action.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (action.quickerActionId != null) {
                        "全局 · ${action.sourceGroup?.takeIf(String::isNotBlank) ?: "未分组"}"
                    } else {
                        action.actionTarget
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
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
    val connected = state.connectionState is QuickerConnectionState.Ready
    val discovering = state.discoveryState is QuickerDiscoveryState.Scanning
    val busy = state.connectionState is QuickerConnectionState.Connecting ||
        state.connectionState is QuickerConnectionState.Authenticating ||
        state.connectionState is QuickerConnectionState.Reconnecting ||
        discovering

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
        item { SectionTitle("连接设置") }

        if (!state.localNetworkPermissionGranted) {
            item {
                PermissionRow(
                    permanentlyDenied = state.localNetworkPermissionPermanentlyDenied,
                    onRequestPermission = onRequestPermission,
                    onOpenAppSettings = onOpenAppSettings,
                )
            }
        }

        state.connectionError?.let { error ->
            item { ConnectionErrorRow(error) }
        }

        when (val discovery = state.discoveryState) {
            is QuickerDiscoveryState.Scanning -> item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("正在查找 Quicker", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            is QuickerDiscoveryState.Failed -> item { ConnectionErrorRow(discovery.reason) }
            QuickerDiscoveryState.Idle -> Unit
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
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                enabled = !busy && !connected,
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = state.rememberPassword,
                    onCheckedChange = onRememberPasswordChanged,
                    enabled = !busy && !connected,
                )
                Text("在此设备上加密保存验证码", modifier = Modifier.weight(1f))
            }
        }

        item {
            if (connected || busy) {
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.LinkOff, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (discovering) "取消查找" else if (busy) "取消连接" else "断开连接")
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = if (state.ipAddress.isBlank()) onDiscover else onConnect,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.localNetworkPermissionGranted,
                    ) {
                        Icon(
                            if (state.ipAddress.isBlank()) Icons.Outlined.Search else Icons.Outlined.Link,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.ipAddress.isBlank()) "自动查找并连接" else "连接 Quicker")
                    }
                    if (state.ipAddress.isNotBlank()) {
                        OutlinedButton(
                            onClick = onDiscover,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.localNetworkPermissionGranted,
                        ) {
                            Icon(Icons.Outlined.Search, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("查找其他电脑")
                        }
                    }
                    OutlinedButton(
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
        }

        item {
            TextButton(
                onClick = { advancedExpanded = !advancedExpanded },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && !connected,
            ) {
                Text("高级设置", modifier = Modifier.weight(1f))
                Icon(
                    if (advancedExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (advancedExpanded) "收起" else "展开",
                )
            }
        }

        if (advancedExpanded) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = state.ipAddress,
                        onValueChange = onIpChanged,
                        modifier = Modifier.weight(1f),
                        label = { Text("电脑 IPv4") },
                        placeholder = { Text("192.168.1.56") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Next,
                        ),
                        enabled = !busy && !connected,
                    )
                    OutlinedTextField(
                        value = state.port,
                        onValueChange = onPortChanged,
                        modifier = Modifier.width(104.dp),
                        label = { Text("WSS 端口") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                        ),
                        enabled = !busy && !connected,
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
                trailingIcon = {
                    IconButton(
                        onClick = { onSendText(textOperation, textToSend) },
                        enabled = connected && textToSend.isNotEmpty(),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "发送")
                    }
                },
            )
        }

        item {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("连接记录", modifier = Modifier.weight(1f))
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
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                if (permanentlyDenied) "局域网权限已被永久拒绝" else "需要局域网权限才能连接电脑",
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = if (permanentlyDenied) onOpenAppSettings else onRequestPermission) {
                Text(if (permanentlyDenied) "打开设置" else "授权")
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

@Composable
private fun ActionEditorDialog(
    action: SavedAction?,
    onDismiss: () -> Unit,
    onSave: (SavedAction) -> Unit,
) {
    var label by rememberSaveable(action?.id) { mutableStateOf(action?.label.orEmpty()) }
    var target by rememberSaveable(action?.id) { mutableStateOf(action?.actionTarget.orEmpty()) }
    var parameter by rememberSaveable(action?.id) { mutableStateOf(action?.parameter.orEmpty()) }
    var confirm by rememberSaveable(action?.id) { mutableStateOf(action?.confirmBeforeRun ?: false) }
    val valid = label.isNotBlank() && target.isNotBlank()
    val synced = action?.quickerActionId != null

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Bolt, contentDescription = null) },
        title = { Text(if (action == null) "添加动作" else "编辑动作") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("显示名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !synced,
                )
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text("Quicker 动作名称或 ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !synced,
                )
                OutlinedTextField(
                    value = parameter,
                    onValueChange = { parameter = it },
                    label = { Text("动作参数") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("执行前确认", modifier = Modifier.weight(1f))
                    Switch(checked = confirm, onCheckedChange = { confirm = it })
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
                            label = label.trim(),
                            actionTarget = target.trim(),
                            parameter = parameter,
                            confirmBeforeRun = confirm,
                            quickerActionId = action?.quickerActionId,
                            sourceGroup = action?.sourceGroup,
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
