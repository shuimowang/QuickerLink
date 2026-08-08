package app.quickerlink

import app.quickerlink.connection.QuickerPanelScene
import app.quickerlink.connection.QuickerConnectionConfig
import app.quickerlink.connection.QuickerConnectionBinding
import app.quickerlink.connection.QuickerConnectionState
import app.quickerlink.connection.QuickerDesktopWindow
import app.quickerlink.connection.QuickerMessage
import app.quickerlink.connection.QuickerLinkCapabilities
import app.quickerlink.connection.QuickerPanelAction
import app.quickerlink.connection.QuickerPanelActionCatalog
import app.quickerlink.connection.QuickerPanelActionsProtocol
import app.quickerlink.connection.QuickerProtocol
import app.quickerlink.connection.QuickerSystemCommand
import app.quickerlink.connection.UnsupportedPanelCatalogVersionException
import app.quickerlink.data.ActionParameterChoice
import app.quickerlink.data.SavedAction
import app.quickerlink.data.StoredConnection
import app.quickerlink.update.UpdateFailure
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class QuickerViewModelStateTest {
    private val config = QuickerConnectionConfig(
        ipAddress = "192.168.1.56",
        port = 668,
        password = "123456",
    )
    private val storedConnection = StoredConnection(
        ipAddress = config.ipAddress,
        port = config.port,
        rememberPassword = true,
        password = config.password,
        requiresPassword = true,
    )

    @Test
    fun screenTapQueueCoalescesToLatestAndClearsOnMonitorClose() {
        val queue = LatestScreenTapQueue()
        val first = QueuedScreenTap(FIRST_CAPTURE_ID, 100, 200, firstToolboxConnection, monitorSession = 1L)
        val latest = QueuedScreenTap(SECOND_CAPTURE_ID, 300, 400, secondToolboxConnection, monitorSession = 2L)
        queue.offer(first)
        queue.offer(latest)

        assertTrue(queue.hasPending)
        val taken = requireNotNull(queue.take())
        assertEquals(SECOND_CAPTURE_ID, taken.captureId)
        assertEquals(300, taken.x)
        assertEquals(400, taken.y)
        assertEquals(2L, taken.monitorSession)
        assertSame(secondToolboxConnection, taken.connection)
        assertFalse(queue.hasPending)
        assertNull(queue.take())

        queue.offer(first.copy(x = 500, y = 600))
        queue.clear()
        assertNull(queue.take())
    }

    @Test
    fun screenTapQueueRejectsCoordinatesOutsideProtocolRange() {
        val queue = LatestScreenTapQueue()
        assertThrows(IllegalArgumentException::class.java) {
            queue.offer(QueuedScreenTap(FIRST_CAPTURE_ID, -1, 0, firstToolboxConnection, monitorSession = 1L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            queue.offer(
                QueuedScreenTap(FIRST_CAPTURE_ID, 0, 1_000_001, firstToolboxConnection, monitorSession = 1L),
            )
        }
    }

    @Test
    fun windowActivationQueueCoalescesToLatestBindingAndClears() {
        val queue = LatestWindowActivationQueue()
        queue.offer(QueuedWindowActivation(FIRST_WINDOW_TOKEN, "第一个窗口", firstToolboxConnection, 1L))
        queue.offer(QueuedWindowActivation(SECOND_WINDOW_TOKEN, "第二个窗口", secondToolboxConnection, 2L))

        assertTrue(queue.hasPending)
        val taken = requireNotNull(queue.take())
        assertEquals(SECOND_WINDOW_TOKEN, taken.token)
        assertEquals("第二个窗口", taken.title)
        assertSame(secondToolboxConnection, taken.connection)
        assertEquals(2L, taken.monitorSession)
        assertFalse(queue.hasPending)

        queue.offer(QueuedWindowActivation(FIRST_WINDOW_TOKEN, "第一个窗口", firstToolboxConnection, 1L))
        queue.clear()
        assertNull(queue.take())
    }

    @Test
    fun monitorFollowUpsRequireVisibleMonitorAndReadyConnection() {
        val ready = QuickerConnectionState.Ready("wss://192.168.1.56:668/QuickWebSocket")

        assertTrue(shouldDispatchMonitorFollowUp(monitorActive = true, connectionState = ready))
        assertFalse(shouldDispatchMonitorFollowUp(monitorActive = false, connectionState = ready))
        assertFalse(
            shouldDispatchMonitorFollowUp(
                monitorActive = true,
                connectionState = QuickerConnectionState.Reconnecting(1, 2, "网络切换"),
            ),
        )
    }

    @Test
    fun pendingMonitorCaptureCoalescesBusyRequestsAndIsClearedByClose() {
        val pending = PendingMonitorCapture()

        pending.request(1L)
        pending.request(1L)
        assertTrue(pending.isPending)
        assertTrue(pending.take(1L))
        assertFalse(pending.take(1L))

        pending.request(2L)
        pending.clear(1L)
        assertTrue(pending.isPending)
        assertTrue(pending.take(2L))

        pending.request(3L)
        pending.clear()
        assertFalse(pending.isPending)
    }

    @Test
    fun screenPreviewRequiresCurrentConnectionAndMonitorSession() {
        val ready = QuickerConnectionState.Ready("wss://192.168.1.56:668/QuickWebSocket")

        assertTrue(
            shouldPublishMonitorResult(
                connectionCurrent = true,
                connectionState = ready,
                requestedMonitorSession = 3L,
                activeMonitorSession = 3L,
                monitorActive = true,
            ),
        )
        assertFalse(
            shouldPublishMonitorResult(
                connectionCurrent = true,
                connectionState = ready,
                requestedMonitorSession = 2L,
                activeMonitorSession = 3L,
                monitorActive = true,
            ),
        )
        assertFalse(
            shouldPublishMonitorResult(
                connectionCurrent = false,
                connectionState = ready,
                requestedMonitorSession = 3L,
                activeMonitorSession = 3L,
                monitorActive = true,
            ),
        )
        assertTrue(
            shouldPublishMonitorResult(
                connectionCurrent = true,
                connectionState = ready,
                requestedMonitorSession = null,
                activeMonitorSession = null,
                monitorActive = false,
            ),
        )
    }

    @Test
    fun closingMonitorInvalidatesPreviewAndWindowInteractions() {
        val state = QuickerUiState(
            screenPreview = ScreenPreviewState(
                path = "screen.jpg",
                name = "screen.jpg",
                capturedAt = "12:00:00",
                captureId = FIRST_CAPTURE_ID,
            ),
            desktopWindows = listOf(
                QuickerDesktopWindow(FIRST_WINDOW_TOKEN, "窗口", "process", icon = null, active = true),
            ),
            desktopWindowsLoaded = true,
            windowActivationQueued = true,
        )

        val closed = invalidateScreenMonitorSession(state)

        assertNull(closed.screenPreview?.captureId)
        assertTrue(closed.desktopWindows.isEmpty())
        assertFalse(closed.desktopWindowsLoaded)
        assertFalse(closed.windowActivationQueued)
    }

    @Test
    fun screenClicksRequireTheCaptureIdAndDimensionsOfTheDecodedDisplayedFrame() {
        assertTrue(
            isCurrentDisplayedScreenFrame(
                displayedCaptureId = FIRST_CAPTURE_ID,
                requestedCaptureId = FIRST_CAPTURE_ID,
                width = 1280,
                height = 720,
                decoded = true,
            ),
        )
        assertFalse(
            isCurrentDisplayedScreenFrame(
                displayedCaptureId = SECOND_CAPTURE_ID,
                requestedCaptureId = FIRST_CAPTURE_ID,
                width = 1280,
                height = 720,
                decoded = true,
            ),
        )
        assertFalse(
            isCurrentDisplayedScreenFrame(
                displayedCaptureId = FIRST_CAPTURE_ID,
                requestedCaptureId = FIRST_CAPTURE_ID,
                width = 1280,
                height = 720,
                decoded = false,
            ),
        )
        assertFalse(
            isCurrentDisplayedScreenFrame(
                displayedCaptureId = FIRST_CAPTURE_ID,
                requestedCaptureId = FIRST_CAPTURE_ID,
                width = 0,
                height = 720,
                decoded = true,
            ),
        )
    }

    @Test
    fun desktopWindowListRefreshesOnlyAfterItsCacheExpires() {
        val interval = 8_000L

        assertTrue(shouldRefreshDesktopWindows(false, 10_000L, 10_001L, interval))
        assertFalse(shouldRefreshDesktopWindows(true, 10_000L, 17_999L, interval))
        assertTrue(shouldRefreshDesktopWindows(true, 10_000L, 18_000L, interval))
        assertTrue(shouldRefreshDesktopWindows(true, 10_000L, 9_999L, interval))
        assertThrows(IllegalArgumentException::class.java) {
            shouldRefreshDesktopWindows(true, 10_000L, 10_001L, 0L)
        }
    }

    @Test
    fun userConnectionsAllowAnEmptyVerificationCodeButRejectUnsafeValues() {
        assertNull(connectionPasswordValidationError(""))
        assertNull(connectionPasswordValidationError("   "))
        assertEquals("连接验证码格式无效", connectionPasswordValidationError("line\nbreak"))
        assertEquals("连接验证码格式无效", connectionPasswordValidationError("x".repeat(257)))
        assertNull(connectionPasswordValidationError("123456"))
    }

    @Test
    fun passwordlessSavedConnectionCanAutoReconnect() {
        val legacy = storedConnection.copy(
            password = "",
            requiresPassword = false,
        )

        assertEquals(config.copy(password = ""), legacy.toReconnectConfigOrNull())
        assertEquals(config, storedConnection.toReconnectConfigOrNull())
    }

    @Test
    fun savedConnectionRequiresPermissionAndDisconnectedState() {
        val session = ConnectionSession(config)

        assertNull(session.connectionForForeground(false, QuickerConnectionState.Disconnected))
        assertNull(session.connectionForForeground(true, QuickerConnectionState.Authenticating))
        assertEquals(config, session.connectionForForeground(true, QuickerConnectionState.Disconnected))
    }

    @Test
    fun activeConnectionDisconnectsInBackgroundAndResumesInForeground() {
        val session = ConnectionSession(null)
        session.beginUserConnection(config, storedConnection)

        assertTrue(session.onBackground(QuickerConnectionState.Ready("endpoint")))
        assertEquals(config, session.connectionForForeground(true, QuickerConnectionState.Disconnected))
    }

    @Test
    fun savedConnectionSurvivesBackgroundWhilePermissionIsUnavailable() {
        val session = ConnectionSession(config)

        assertFalse(session.onBackground(QuickerConnectionState.Disconnected))
        assertEquals(config, session.connectionForForeground(true, QuickerConnectionState.Disconnected))
    }

    @Test
    fun manualDisconnectPreventsForegroundReconnect() {
        val session = ConnectionSession(null)
        session.beginUserConnection(config, storedConnection)

        session.onUserDisconnect()

        assertNull(session.connectionForForeground(true, QuickerConnectionState.Disconnected))
        assertNull(session.takeAuthenticatedConnection())
    }

    @Test
    fun clearingSavedCredentialsStopsIdleAutoConnect() {
        val session = ConnectionSession(config)

        session.replaceSavedConnection(null)

        assertNull(session.connectionForForeground(true, QuickerConnectionState.Disconnected))
    }

    @Test
    fun authenticationFailureDropsPendingPersistenceAndReconnect() {
        val session = ConnectionSession(null)
        session.beginUserConnection(config, storedConnection)

        session.onAuthenticationFailed()

        assertNull(session.takeAuthenticatedConnection())
        assertNull(session.connectionForForeground(true, QuickerConnectionState.Disconnected))
    }

    @Test
    fun authenticatedConnectionIsPersistedOnlyOnce() {
        val session = ConnectionSession(null)
        session.beginUserConnection(config, storedConnection)

        assertSame(storedConnection, session.takeAuthenticatedConnection())
        assertNull(session.takeAuthenticatedConnection())
    }

    @Test
    fun duplicateActionIsRejectedWhileOtherActionsCanRun() {
        val initial = QuickerUiState()
        val firstStarted = initial.startRunningAction("first")

        assertEquals(setOf("first"), firstStarted?.runningActionIds)
        assertNull(firstStarted?.startRunningAction("first"))

        val secondStarted = firstStarted?.startRunningAction("second")
        assertEquals(setOf("first", "second"), secondStarted?.runningActionIds)
        assertFalse("first" in requireNotNull(secondStarted).finishRunningAction("first").runningActionIds)
        assertTrue("second" in secondStarted.finishRunningAction("first").runningActionIds)
    }

    @Test
    fun updateFailuresUseActionableMessagesWithoutExposingInternalErrors() {
        assertEquals("下载失败，请检查网络后重试", updateFailureMessage(UpdateFailure.Network))
        assertEquals("安装包校验失败，已停止更新", updateFailureMessage(UpdateFailure.ChecksumMismatch))
        assertEquals("安装包身份验证失败，已停止更新", updateFailureMessage(UpdateFailure.SignatureMismatch))
        assertEquals("发布文件不符合安全要求，已停止更新", updateFailureMessage(UpdateFailure.UntrustedUrl))
    }

    @Test
    fun panelSyncPromptsOnlyWhenCompanionActionNeedsInstallationOrUpdate() {
        val missing = classifyPanelSyncFailure(CompanionActionUnavailableException("not found"))
        val outdated = classifyPanelSyncFailure(UnsupportedPanelCatalogVersionException())
        val network = classifyPanelSyncFailure(IOException("等待响应超时"))

        assertTrue(missing.showCompanionActionPrompt)
        assertEquals("未找到可用的 Quicker Link 动作，请先安装或更新", missing.message)
        assertTrue(outdated.showCompanionActionPrompt)
        assertEquals("Quicker Link 动作版本过旧，请更新后重试", outdated.message)
        assertFalse(network.showCompanionActionPrompt)
        assertEquals("等待响应超时", network.message)
    }

    @Test
    fun webSocketFailureMessagesAreBoundedAndNeverSerializeResponseData() {
        val response = QuickerMessage(
            messageType = QuickerProtocol.MESSAGE_RESPONSE,
            isSuccess = false,
            data = JsonParser.parseString(
                """{"catalog":"${"private-action-data".repeat(200)}"}""",
            ),
            raw = "response",
        )

        assertEquals(
            "Quicker 拒绝终止动作",
            webSocketCommandFailureMessage(response, "Quicker 拒绝终止动作"),
        )

        val bounded = webSocketCommandFailureMessage(
            response.copy(message = "  第一行\r\n第二行 ${"x".repeat(500)}  "),
            "终止动作失败",
        )
        assertEquals(180, bounded.length)
        assertFalse(bounded.contains('\r'))
        assertFalse(bounded.contains('\n'))
        assertTrue(bounded.startsWith("第一行 第二行 "))
        assertFalse(bounded.contains("private-action-data"))
    }

    @Test
    fun transferProgressIsStableForEmptyAndPartialFiles() {
        assertEquals(100, transferPercent(0, 0))
        assertEquals(0, transferPercent(0, 64 * 1024 * 1024L))
        assertEquals(50, transferPercent(32 * 1024 * 1024L, 64 * 1024 * 1024L))
        assertEquals(100, transferPercent(64 * 1024 * 1024L, 64 * 1024 * 1024L))
        assertEquals("0 B", formatTransferBytes(0))
        assertEquals("1.0 KiB", formatTransferBytes(1024))
        assertEquals("64.0 MiB", formatTransferBytes(64 * 1024 * 1024L))
    }

    @Test
    fun systemControlLabelsAreExplicitAndStable() {
        assertEquals("关闭电脑", systemCommandLabel(QuickerSystemCommand.SHUTDOWN))
        assertEquals("电脑睡眠", systemCommandLabel(QuickerSystemCommand.SLEEP))
        assertEquals("重启 Quicker", systemCommandLabel(QuickerSystemCommand.RESTART_QUICKER))
    }

    @Test
    fun linkCapabilitiesRefreshOncePerConnectedTarget() {
        val target = QuickerLinkTarget(
            ipAddress = "192.168.1.56",
            port = 668,
            serviceActionId = QuickerPanelActionsProtocol.COMPANION_SHARED_ACTION_ID,
        )
        val capabilities = QuickerLinkCapabilities()

        assertTrue(shouldSyncPanelActionsAfterReady(explicitlyRequested = false, capabilities = null))
        assertFalse(shouldSyncPanelActionsAfterReady(explicitlyRequested = false, capabilities = capabilities))
        assertTrue(shouldSyncPanelActionsAfterReady(explicitlyRequested = true, capabilities = capabilities))
        assertTrue(shouldKeepLinkCapabilities(verifiedTarget = target, requestedTarget = target))
        assertFalse(
            shouldKeepLinkCapabilities(
                verifiedTarget = target,
                requestedTarget = target.copy(ipAddress = "192.168.1.57"),
            ),
        )
    }

    @Test
    fun panelSyncNetworkErrorsAreBoundedBeforeReachingUiState() {
        val failure = classifyPanelSyncFailure(
            IOException("  网络失败\r\n${"detail".repeat(100)}  "),
        )

        assertFalse(failure.showCompanionActionPrompt)
        assertEquals(180, failure.message.length)
        assertFalse(failure.message.contains('\r'))
        assertFalse(failure.message.contains('\n'))
        assertTrue(failure.message.startsWith("网络失败 "))
    }

    @Test
    fun panelActionSyncPreservesSceneAndPanelOrder() {
        val catalog = panelCatalog(
            globalGroups = listOf("常用"),
            globalActions = listOf(
                QuickerPanelAction(FIRST_ACTION_ID, "动作一", "常用", 2, ICON_URL),
                QuickerPanelAction(SECOND_ACTION_ID, "动作二", null, 7),
            ),
            commonGroups = listOf("默认"),
            commonActions = listOf(
                QuickerPanelAction(THIRD_ACTION_ID, "通用动作", "默认", 1),
            ),
        )

        val merged = mergePanelActions(emptyList(), catalog)

        assertEquals(
            listOf(FIRST_ACTION_ID, SECOND_ACTION_ID, THIRD_ACTION_ID),
            merged.map(SavedAction::quickerActionId),
        )
        assertEquals(listOf("常用", null, "默认"), merged.map(SavedAction::sourceGroup))
        assertEquals(listOf(ICON_URL, null, null), merged.map(SavedAction::icon))
        assertEquals(
            listOf(
                QuickerPanelActionsProtocol.GLOBAL_SCENE,
                QuickerPanelActionsProtocol.GLOBAL_SCENE,
                QuickerPanelActionsProtocol.COMMON_SCENE,
            ),
            merged.map(SavedAction::sourceScene),
        )
    }

    @Test
    fun fullPanelSyncUpdatesMappingsRemovesAllMissingSyncedItemsAndKeepsManualActions() {
        val oldChoices = listOf(ActionParameterChoice("旧选项", "old_value"))
        val refreshedChoices = listOf(
            ActionParameterChoice("设置", "action_settings"),
            ActionParameterChoice("录制", "action_ffmpeg"),
        )
        val manual = SavedAction(
            id = "manual",
            label = "手工动作",
            actionTarget = "按名称运行",
            parameter = "手工参数",
            confirmBeforeRun = true,
        )
        val existing = listOf(
            SavedAction(
                id = "synced-first",
                label = "旧名称",
                actionTarget = FIRST_ACTION_ID,
                parameter = "保留参数",
                parameterChoices = oldChoices,
                confirmBeforeRun = true,
                quickerActionId = FIRST_ACTION_ID.uppercase(),
                sourceGroup = "旧分组",
                sourceScene = null,
                icon = "https://files.getquicker.net/_icons/OLD.png",
            ),
            SavedAction(
                id = "synced-removed-common",
                label = "已删除通用",
                actionTarget = SECOND_ACTION_ID,
                quickerActionId = SECOND_ACTION_ID,
                sourceScene = QuickerPanelActionsProtocol.COMMON_SCENE,
            ),
            SavedAction(
                id = "synced-removed-without-scene",
                label = "旧数据",
                actionTarget = THIRD_ACTION_ID,
                quickerActionId = THIRD_ACTION_ID,
                sourceScene = null,
            ),
            manual,
        )
        val catalog = panelCatalog(
            globalGroups = listOf("新分组"),
            globalActions = listOf(
                QuickerPanelAction(
                    FIRST_ACTION_ID,
                    "新名称",
                    "新分组",
                    4,
                    ICON_URL,
                    refreshedChoices,
                ),
            ),
        )

        val merged = mergePanelActions(existing, catalog)

        assertEquals(listOf("synced-first", "manual"), merged.map(SavedAction::id))
        assertEquals("新名称", merged.first().label)
        assertEquals("新分组", merged.first().sourceGroup)
        assertEquals("保留参数", merged.first().parameter)
        assertEquals(refreshedChoices, merged.first().parameterChoices)
        assertTrue(merged.first().confirmBeforeRun)
        assertEquals(QuickerPanelActionsProtocol.GLOBAL_SCENE, merged.first().sourceScene)
        assertEquals(ICON_URL, merged.first().icon)
        assertEquals(manual, merged.last())
    }

    @Test
    fun synchronizedActionEditChangesOnlyLocalRunSettings() {
        val choices = listOf(ActionParameterChoice("设置", "action_settings"))
        val existing = SavedAction(
            id = "synced",
            label = "电脑名称",
            actionTarget = FIRST_ACTION_ID,
            parameterChoices = choices,
            quickerActionId = FIRST_ACTION_ID,
            sourceScene = QuickerPanelActionsProtocol.GLOBAL_SCENE,
            icon = ICON_URL,
        )
        val edited = existing.copy(
            label = "伪造名称",
            actionTarget = SECOND_ACTION_ID,
            parameter = "action_settings",
            parameterChoices = listOf(ActionParameterChoice("伪造", "forged")),
            confirmBeforeRun = true,
            sourceScene = QuickerPanelActionsProtocol.COMMON_SCENE,
            icon = null,
        )

        val saved = applyActionSave(listOf(existing), edited).single()

        assertEquals(existing.copy(parameter = "action_settings", confirmBeforeRun = true), saved)
        assertEquals(choices, saved.parameterChoices)
    }

    @Test
    fun savingStaleSynchronizedEditorCannotRestoreActionRemovedByRefresh() {
        val stale = SavedAction(
            id = "synced",
            label = "已移除动作",
            actionTarget = FIRST_ACTION_ID,
            parameter = "payload",
            quickerActionId = FIRST_ACTION_ID,
        )

        assertEquals(emptyList<SavedAction>(), applyActionSave(emptyList(), stale))
    }

    @Test
    fun deletingUsesCurrentStoredActionClassification() {
        val synchronized = SavedAction(
            id = "shared-id",
            label = "同步动作",
            actionTarget = FIRST_ACTION_ID,
            quickerActionId = FIRST_ACTION_ID,
        )
        val staleManualSnapshot = synchronized.copy(quickerActionId = null)

        assertNull(removeManualAction(listOf(synchronized), staleManualSnapshot.id))

        val manual = staleManualSnapshot.copy(label = "手工动作")
        assertEquals(emptyList<SavedAction>(), removeManualAction(listOf(manual), manual.id))
        val current = listOf(manual)
        assertSame(current, removeManualAction(current, "missing"))
    }

    @Test
    fun movingActionFromGlobalToCommonPreservesLocalSettings() {
        val existing = SavedAction(
            id = "stable-local-id",
            label = "旧名称",
            actionTarget = FIRST_ACTION_ID,
            parameter = "payload",
            confirmBeforeRun = true,
            quickerActionId = FIRST_ACTION_ID,
            sourceScene = QuickerPanelActionsProtocol.GLOBAL_SCENE,
        )
        val catalog = panelCatalog(
            commonGroups = listOf("默认"),
            commonActions = listOf(QuickerPanelAction(FIRST_ACTION_ID, "通用名称", "默认", 0)),
        )

        val merged = mergePanelActions(listOf(existing), catalog)

        assertEquals(1, merged.size)
        assertEquals("stable-local-id", merged.single().id)
        assertEquals("payload", merged.single().parameter)
        assertTrue(merged.single().confirmBeforeRun)
        assertEquals(QuickerPanelActionsProtocol.COMMON_SCENE, merged.single().sourceScene)
    }

    @Test
    fun panelActionSyncDeduplicatesByUuidWithGlobalPriorityEvenWhenCatalogIsReversed() {
        val catalog = QuickerPanelActionCatalog(
            scenes = listOf(
                QuickerPanelScene(
                    scene = QuickerPanelActionsProtocol.COMMON_SCENE,
                    groups = emptyList(),
                    actions = listOf(QuickerPanelAction(FIRST_ACTION_ID, "通用名称", null, 0)),
                ),
                QuickerPanelScene(
                    scene = QuickerPanelActionsProtocol.GLOBAL_SCENE,
                    groups = emptyList(),
                    actions = listOf(QuickerPanelAction(FIRST_ACTION_ID.uppercase(), "全局名称", null, 0)),
                ),
            ),
        )

        val merged = mergePanelActions(emptyList(), catalog)

        assertEquals(1, merged.size)
        assertEquals("全局名称", merged.single().label)
        assertEquals(QuickerPanelActionsProtocol.GLOBAL_SCENE, merged.single().sourceScene)
    }

    @Test
    fun panelActionSyncKeepsMatchingManualGuidAsLocalData() {
        val manualGuid = SavedAction(
            id = "manual-guid",
            label = "自定义名称",
            actionTarget = FIRST_ACTION_ID.uppercase(),
            parameter = "payload",
            confirmBeforeRun = true,
        )
        val catalog = panelCatalog(
            commonActions = listOf(QuickerPanelAction(FIRST_ACTION_ID, "Quicker 名称", null, 0)),
        )

        val merged = mergePanelActions(listOf(manualGuid), catalog)

        assertEquals(2, merged.size)
        assertEquals("quicker:$FIRST_ACTION_ID", merged.first().id)
        assertEquals(FIRST_ACTION_ID, merged.first().quickerActionId)
        assertEquals(QuickerPanelActionsProtocol.COMMON_SCENE, merged.first().sourceScene)
        assertEquals(manualGuid, merged.last())
    }

    private fun panelCatalog(
        globalGroups: List<String> = emptyList(),
        globalActions: List<QuickerPanelAction> = emptyList(),
        commonGroups: List<String> = emptyList(),
        commonActions: List<QuickerPanelAction> = emptyList(),
    ): QuickerPanelActionCatalog = QuickerPanelActionCatalog(
        scenes = listOf(
            QuickerPanelScene(
                scene = QuickerPanelActionsProtocol.GLOBAL_SCENE,
                groups = globalGroups,
                actions = globalActions,
            ),
            QuickerPanelScene(
                scene = QuickerPanelActionsProtocol.COMMON_SCENE,
                groups = commonGroups,
                actions = commonActions,
            ),
        ),
    )

    private companion object {
        const val FIRST_CAPTURE_ID = "44444444-4444-4444-8444-444444444444"
        const val SECOND_CAPTURE_ID = "55555555-5555-4555-8555-555555555555"
        const val FIRST_WINDOW_TOKEN = "66666666-6666-4666-8666-666666666666"
        const val SECOND_WINDOW_TOKEN = "77777777-7777-4777-8777-777777777777"
        const val FIRST_ACTION_ID = "11111111-1111-4111-8111-111111111111"
        const val SECOND_ACTION_ID = "22222222-2222-4222-8222-222222222222"
        const val THIRD_ACTION_ID = "33333333-3333-4333-8333-333333333333"
        const val ICON_URL = "https://files.getquicker.net/_icons/NEW.png"
    }

    private val firstToolboxConnection = ToolboxConnection(
        connection = QuickerConnectionBinding(config, generation = 1L),
        actionId = FIRST_ACTION_ID,
    )
    private val secondToolboxConnection = ToolboxConnection(
        connection = QuickerConnectionBinding(config.copy(ipAddress = "192.168.1.57"), generation = 2L),
        actionId = SECOND_ACTION_ID,
    )
}
