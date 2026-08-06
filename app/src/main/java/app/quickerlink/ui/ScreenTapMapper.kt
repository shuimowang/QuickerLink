package app.quickerlink.ui

import app.quickerlink.ToolboxTask
import app.quickerlink.connection.QuickerToolboxProtocol
import kotlin.math.roundToInt

internal data class NormalizedScreenTap(
    val x: Int,
    val y: Int,
)

internal fun canAcceptScreenTap(
    screenClickSupported: Boolean,
    connected: Boolean,
    captureAvailable: Boolean,
    dimensionsAvailable: Boolean,
    controlsLocked: Boolean,
    workingTask: ToolboxTask?,
    windowActivationQueued: Boolean,
): Boolean {
    val captureInFlight = workingTask == ToolboxTask.SCREEN
    return screenClickSupported &&
        connected &&
        captureAvailable &&
        dimensionsAvailable &&
        !windowActivationQueued &&
        (!controlsLocked || captureInFlight)
}

internal fun canActivateDesktopWindow(
    windowActivateSupported: Boolean,
    connected: Boolean,
    controlsLocked: Boolean,
    workingTask: ToolboxTask?,
): Boolean {
    val screenInteractionInFlight = workingTask == ToolboxTask.SCREEN ||
        workingTask == ToolboxTask.SCREEN_CLICK
    return windowActivateSupported &&
        connected &&
        (!controlsLocked || screenInteractionInFlight)
}

internal fun mapScreenTap(
    containerWidth: Float,
    containerHeight: Float,
    imageWidth: Int,
    imageHeight: Int,
    tapX: Float,
    tapY: Float,
): NormalizedScreenTap? {
    if (
        !containerWidth.isFinite() || !containerHeight.isFinite() ||
        !tapX.isFinite() || !tapY.isFinite() ||
        containerWidth <= 0f || containerHeight <= 0f ||
        imageWidth <= 0 || imageHeight <= 0
    ) {
        return null
    }

    val scale = minOf(containerWidth / imageWidth, containerHeight / imageHeight)
    val renderedWidth = imageWidth * scale
    val renderedHeight = imageHeight * scale
    val left = (containerWidth - renderedWidth) / 2f
    val top = (containerHeight - renderedHeight) / 2f
    val right = left + renderedWidth
    val bottom = top + renderedHeight
    if (tapX < left || tapX > right || tapY < top || tapY > bottom) return null

    val maximum = QuickerToolboxProtocol.NORMALIZED_COORDINATE_MAX
    return NormalizedScreenTap(
        x = (((tapX - left) / renderedWidth) * maximum).roundToInt().coerceIn(0, maximum),
        y = (((tapY - top) / renderedHeight) * maximum).roundToInt().coerceIn(0, maximum),
    )
}
