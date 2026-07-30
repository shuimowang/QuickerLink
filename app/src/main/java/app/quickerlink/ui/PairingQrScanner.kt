package app.quickerlink.ui

import android.annotation.SuppressLint
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.quickerlink.connection.QuickerPairingCode
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

@Composable
internal fun PairingQrScannerDialog(
    onResult: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var bindingError by remember { mutableStateOf<String?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var scannerGeneration by remember { mutableIntStateOf(0) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(modifier = Modifier.fillMaxSize()) {
                key(scannerGeneration) {
                    CameraPreview(
                        modifier = Modifier.fillMaxSize(),
                        lifecycleOwner = lifecycleOwner,
                        onResult = onResult,
                        onInvalidCode = { scanError = it },
                        onBindingError = {
                            scanError = null
                            bindingError = it
                        },
                    )
                }
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                ) {
                    if (bindingError == null) {
                        val frameSize = minOf(280.dp, maxWidth * 0.72f, maxHeight * 0.45f)
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(frameSize)
                                .border(3.dp, Color.White, RoundedCornerShape(8.dp)),
                        )
                    }
                    Text(
                        text = "扫描配对码",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 20.dp, top = 16.dp),
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 4.dp, end = 8.dp),
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = Color.White)
                    }
                    bindingError?.let { message ->
                        CameraErrorPanel(
                            message = message,
                            onRetry = {
                                bindingError = null
                                scanError = null
                                scannerGeneration += 1
                            },
                            onDismiss = onDismiss,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    if (bindingError == null) {
                        scanError?.let { message ->
                            InvalidCodeNotice(
                                message = message,
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InvalidCodeNotice(
    message: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun CameraErrorPanel(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("无法使用相机", style = MaterialTheme.typography.titleMedium)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text("关闭") }
                Button(onClick = onRetry) { Text("重试") }
            }
        }
    }
}

@SuppressLint("RestrictedApi")
@Composable
private fun CameraPreview(
    modifier: Modifier,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onResult: (String) -> Unit,
    onInvalidCode: (String) -> Unit,
    onBindingError: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    val currentOnInvalidCode by rememberUpdatedState(onInvalidCode)
    val currentOnBindingError by rememberUpdatedState(onBindingError)
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
    DisposableEffect(lifecycleOwner, previewView) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val active = AtomicBoolean(true)
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var analysis: ImageAnalysis? = null
        providerFuture.addListener(
            {
                if (!active.get()) return@addListener
                runCatching {
                    val cameraProvider = providerFuture.get()
                    if (!active.get()) return@runCatching
                    provider = cameraProvider
                    preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { useCase ->
                            useCase.setAnalyzer(
                                analysisExecutor,
                                QrCodeAnalyzer(
                                    onResult = { payload ->
                                        mainExecutor.execute {
                                            if (active.get()) currentOnResult(payload)
                                        }
                                    },
                                    onInvalidCode = { message ->
                                        mainExecutor.execute {
                                            if (active.get()) currentOnInvalidCode(message)
                                        }
                                    },
                                    onError = { message ->
                                        mainExecutor.execute {
                                            if (active.get()) {
                                                releaseCameraUseCases(provider, preview, analysis)
                                                currentOnBindingError(message)
                                            }
                                        }
                                    },
                                ),
                            )
                        }
                    if (!active.get()) {
                        releaseCameraUseCases(provider, preview, analysis)
                        return@runCatching
                    }
                    val selector = selectCamera(cameraProvider)
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        selector,
                        requireNotNull(preview),
                        requireNotNull(analysis),
                    )
                }.onFailure {
                    releaseCameraUseCases(provider, preview, analysis)
                    if (active.get()) currentOnBindingError(cameraErrorMessage(it))
                }
            },
            mainExecutor,
        )

        onDispose {
            active.set(false)
            releaseCameraUseCases(provider, preview, analysis)
            analysisExecutor.shutdownNow()
        }
    }
}

private fun selectCamera(provider: ProcessCameraProvider): CameraSelector = when {
    provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
    provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
    else -> throw NoCameraAvailableException()
}

private fun releaseCameraUseCases(
    provider: ProcessCameraProvider?,
    preview: Preview?,
    analysis: ImageAnalysis?,
) {
    analysis?.clearAnalyzer()
    preview?.setSurfaceProvider(null)
    if (provider == null) return
    val useCases = listOfNotNull<UseCase>(preview, analysis)
    if (useCases.isNotEmpty()) runCatching { provider.unbind(*useCases.toTypedArray()) }
}

private fun cameraErrorMessage(error: Throwable): String {
    val causes = generateSequence(error) { it.cause }.toList()
    return when {
        causes.any { it is NoCameraAvailableException } -> "此设备没有可用的相机。"
        causes.any { it is SecurityException } -> "相机权限不可用，请在系统设置中允许后重试。"
        else -> "无法打开相机，请确认没有其他应用占用相机后重试。"
    }
}

private class NoCameraAvailableException : IllegalStateException()

private class QrCodeAnalyzer(
    private val onResult: (String) -> Unit,
    private val onInvalidCode: (String) -> Unit,
    private val onError: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val reader = QRCodeReader()
    private val completed = AtomicBoolean(false)
    private val hints = mapOf(DecodeHintType.CHARACTER_SET to "UTF-8")
    private var lastInvalidMessage: String? = null
    private var lastInvalidNoticeAtNanos = 0L

    override fun analyze(image: ImageProxy) {
        if (completed.get()) {
            image.close()
            return
        }
        try {
            val luminance = compactLuminance(image)
            val source = PlanarYUVLuminanceSource(
                luminance,
                image.width,
                image.height,
                0,
                0,
                image.width,
                image.height,
                false,
            )
            val result = reader.decode(BinaryBitmap(HybridBinarizer(source)), hints)
            val payload = result.text
            val validationError = pairingValidationError(payload)
            if (validationError == null) {
                if (completed.compareAndSet(false, true)) onResult(payload)
            } else {
                notifyInvalidCode(validationError)
            }
        } catch (_: ReaderException) {
            Unit
        } catch (_: RuntimeException) {
            if (completed.compareAndSet(false, true)) onError("无法读取相机画面，请重试。")
        } finally {
            reader.reset()
            image.close()
        }
    }

    private fun notifyInvalidCode(message: String) {
        val now = System.nanoTime()
        if (message != lastInvalidMessage || now - lastInvalidNoticeAtNanos >= INVALID_NOTICE_INTERVAL_NANOS) {
            lastInvalidMessage = message
            lastInvalidNoticeAtNanos = now
            onInvalidCode(message)
        }
    }

    private fun compactLuminance(image: ImageProxy): ByteArray {
        val plane = image.planes.first()
        val width = image.width
        val height = image.height
        require(width > 0 && height > 0) { "Invalid image dimensions" }
        val output = ByteArray(Math.multiplyExact(width, height))
        val buffer = plane.buffer.duplicate()
        val baseOffset = buffer.position()
        require(plane.rowStride > 0 && plane.pixelStride > 0) { "Invalid image plane strides" }
        val row = ByteArray(plane.rowStride)
        for (y in 0 until height) {
            val rowStart = baseOffset.toLong() + y.toLong() * plane.rowStride
            if (rowStart >= buffer.limit()) break
            buffer.position(rowStart.toInt())
            val rowLength = min(plane.rowStride, buffer.remaining())
            buffer.get(row, 0, rowLength)
            for (x in 0 until width) {
                val sourceIndex = x * plane.pixelStride
                if (sourceIndex < rowLength) output[y * width + x] = row[sourceIndex]
            }
        }
        return output
    }

    private companion object {
        const val INVALID_NOTICE_INTERVAL_NANOS = 2_000_000_000L
    }
}

private fun pairingValidationError(payload: String): String? = runCatching {
    QuickerPairingCode.parse(payload)
}.fold(
    onSuccess = { null },
    onFailure = { it.message ?: "这不是 Quicker Link 配对码" },
)
