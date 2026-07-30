package app.quickerlink

import app.quickerlink.connection.QuickerConnectionConfig
import app.quickerlink.connection.QuickerConnectionState
import app.quickerlink.connection.QuickerGlobalAction
import app.quickerlink.connection.QuickerGlobalActionCatalog
import app.quickerlink.data.SavedAction
import app.quickerlink.data.StoredConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun globalActionSyncPreservesCatalogOrderAndUngroupedState() {
        val catalog = QuickerGlobalActionCatalog(
            groups = listOf("常用"),
            actions = listOf(
                QuickerGlobalAction(FIRST_ACTION_ID, "动作一", "常用", 2),
                QuickerGlobalAction(SECOND_ACTION_ID, "动作二", null, 7),
            ),
        )

        val merged = mergeGlobalActions(emptyList(), catalog)

        assertEquals(listOf(FIRST_ACTION_ID, SECOND_ACTION_ID), merged.map(SavedAction::quickerActionId))
        assertEquals(listOf("常用", null), merged.map(SavedAction::sourceGroup))
        assertEquals(listOf("动作一", "动作二"), merged.map(SavedAction::label))
    }

    @Test
    fun globalActionSyncUpdatesRenamesRemovesMissingMappingsAndKeepsManualActions() {
        val existing = listOf(
            SavedAction(
                id = "synced-first",
                label = "旧名称",
                actionTarget = FIRST_ACTION_ID,
                parameter = "保留参数",
                confirmBeforeRun = true,
                quickerActionId = FIRST_ACTION_ID,
                sourceGroup = "旧分组",
            ),
            SavedAction(
                id = "synced-removed",
                label = "已删除",
                actionTarget = SECOND_ACTION_ID,
                quickerActionId = SECOND_ACTION_ID,
            ),
            SavedAction(id = "manual", label = "手工动作", actionTarget = "按名称运行"),
        )
        val catalog = QuickerGlobalActionCatalog(
            groups = listOf("新分组"),
            actions = listOf(QuickerGlobalAction(FIRST_ACTION_ID, "新名称", "新分组", 4)),
        )

        val merged = mergeGlobalActions(existing, catalog)

        assertEquals(listOf("synced-first", "manual"), merged.map(SavedAction::id))
        assertEquals("新名称", merged.first().label)
        assertEquals("新分组", merged.first().sourceGroup)
        assertEquals("保留参数", merged.first().parameter)
        assertTrue(merged.first().confirmBeforeRun)
    }

    @Test
    fun globalActionSyncConvertsMatchingManualGuidWithoutDuplication() {
        val manualGuid = SavedAction(
            id = "manual-guid",
            label = "自定义名称",
            actionTarget = FIRST_ACTION_ID.uppercase(),
            parameter = "payload",
            confirmBeforeRun = true,
        )
        val catalog = QuickerGlobalActionCatalog(
            groups = emptyList(),
            actions = listOf(QuickerGlobalAction(FIRST_ACTION_ID, "Quicker 名称", null, 0)),
        )

        val merged = mergeGlobalActions(listOf(manualGuid), catalog)

        assertEquals(1, merged.size)
        assertEquals("manual-guid", merged.single().id)
        assertEquals(FIRST_ACTION_ID, merged.single().quickerActionId)
        assertEquals("Quicker 名称", merged.single().label)
        assertEquals("payload", merged.single().parameter)
        assertTrue(merged.single().confirmBeforeRun)
    }

    private companion object {
        const val FIRST_ACTION_ID = "11111111-1111-4111-8111-111111111111"
        const val SECOND_ACTION_ID = "22222222-2222-4222-8222-222222222222"
    }
}
