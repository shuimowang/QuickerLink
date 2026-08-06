package app.quickerlink.ui

import app.quickerlink.ToolboxTask
import app.quickerlink.connection.QuickerToolboxProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTapMapperTest {
    @Test
    fun `maps landscape image inside portrait container`() {
        assertEquals(
            NormalizedScreenTap(500_000, 500_000),
            mapScreenTap(1_000f, 2_000f, 1_920, 1_080, 500f, 1_000f),
        )
        assertNull(mapScreenTap(1_000f, 2_000f, 1_920, 1_080, 500f, 200f))
    }

    @Test
    fun `maps portrait image inside landscape container`() {
        assertEquals(
            NormalizedScreenTap(500_000, 500_000),
            mapScreenTap(2_000f, 1_000f, 1_080, 1_920, 1_000f, 500f),
        )
        assertNull(mapScreenTap(2_000f, 1_000f, 1_080, 1_920, 200f, 500f))
    }

    @Test
    fun `keeps fitted image boundaries inclusive`() {
        val maximum = QuickerToolboxProtocol.NORMALIZED_COORDINATE_MAX
        assertEquals(
            NormalizedScreenTap(0, 0),
            mapScreenTap(1_600f, 900f, 1_600, 900, 0f, 0f),
        )
        assertEquals(
            NormalizedScreenTap(maximum, maximum),
            mapScreenTap(1_600f, 900f, 1_600, 900, 1_600f, 900f),
        )
    }

    @Test
    fun `rejects invalid geometry and non finite taps`() {
        assertNull(mapScreenTap(0f, 900f, 1_600, 900, 0f, 0f))
        assertNull(mapScreenTap(1_600f, 900f, 0, 900, 0f, 0f))
        assertNull(mapScreenTap(1_600f, 900f, 1_600, 900, Float.NaN, 0f))
    }

    @Test
    fun `keeps taps active only while a new screen frame is in flight`() {
        listOf(null, ToolboxTask.SCREEN).forEach { task ->
            assertTrue(
                canAcceptScreenTap(
                    screenClickSupported = true,
                    connected = true,
                    captureAvailable = true,
                    dimensionsAvailable = true,
                    controlsLocked = task != null,
                    workingTask = task,
                    windowActivationQueued = false,
                ),
            )
        }
        listOf(ToolboxTask.SCREEN_CLICK, ToolboxTask.WINDOWS).forEach { task ->
            assertFalse(
                canAcceptScreenTap(true, true, true, true, true, task, false),
            )
        }
    }

    @Test
    fun `requires a connected valid preview before accepting taps`() {
        assertFalse(canAcceptScreenTap(false, true, true, true, false, null, false))
        assertFalse(canAcceptScreenTap(true, false, true, true, false, null, false))
        assertFalse(canAcceptScreenTap(true, true, false, true, false, null, false))
        assertFalse(canAcceptScreenTap(true, true, true, false, false, null, false))
        assertFalse(canAcceptScreenTap(true, true, true, true, false, null, true))
    }

    @Test
    fun `keeps window activation available during screen work only`() {
        listOf(null, ToolboxTask.SCREEN, ToolboxTask.SCREEN_CLICK).forEach { task ->
            assertTrue(
                canActivateDesktopWindow(
                    windowActivateSupported = true,
                    connected = true,
                    controlsLocked = task != null,
                    workingTask = task,
                ),
            )
        }
        assertFalse(canActivateDesktopWindow(true, true, true, ToolboxTask.WINDOWS))
        assertFalse(canActivateDesktopWindow(false, true, false, null))
        assertFalse(canActivateDesktopWindow(true, false, false, null))
    }
}
