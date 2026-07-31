package app.quickerlink

import app.quickerlink.connection.QuickerPanelScene
import app.quickerlink.connection.QuickerConnectionConfig
import app.quickerlink.connection.QuickerConnectionState
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
        const val FIRST_ACTION_ID = "11111111-1111-4111-8111-111111111111"
        const val SECOND_ACTION_ID = "22222222-2222-4222-8222-222222222222"
        const val THIRD_ACTION_ID = "33333333-3333-4333-8333-333333333333"
        const val ICON_URL = "https://files.getquicker.net/_icons/NEW.png"
    }
}
