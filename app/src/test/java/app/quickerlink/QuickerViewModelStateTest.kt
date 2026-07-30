package app.quickerlink

import app.quickerlink.connection.QuickerConnectionConfig
import app.quickerlink.connection.QuickerConnectionState
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
}
