package app.quickerlink

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.compose.runtime.mutableStateOf
import app.quickerlink.ui.QuickerApp
import app.quickerlink.ui.theme.QuickerLinkTheme

class MainActivity : ComponentActivity() {
    private val viewModel: QuickerViewModel by viewModels()
    private val cameraPermissionGranted = mutableStateOf(false)
    private val cameraPermissionPermanentlyDenied = mutableStateOf(false)
    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) = viewModel.onAppForegrounded()

        override fun onStop(owner: LifecycleOwner) = viewModel.onAppBackgrounded()
    }

    private val localNetworkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val permanentlyDenied = !granted &&
            !ActivityCompat.shouldShowRequestPermissionRationale(this, LOCAL_NETWORK_PERMISSION)
        viewModel.onLocalNetworkPermissionResult(granted, permanentlyDenied)
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraPermissionGranted.value = granted
        cameraPermissionPermanentlyDenied.value = !granted &&
            !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        viewModel.onLocalNetworkPermissionStatus(hasLocalNetworkPermission())
        cameraPermissionGranted.value = hasCameraPermission()
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)

        setContent {
            QuickerLinkTheme {
                QuickerApp(
                    viewModel = viewModel,
                    onRequestLocalNetworkPermission = ::requestLocalNetworkPermission,
                    cameraPermissionGranted = cameraPermissionGranted.value,
                    cameraPermissionPermanentlyDenied = cameraPermissionPermanentlyDenied.value,
                    onRequestCameraPermission = ::requestCameraPermission,
                    onOpenAppSettings = ::openAppSettings,
                    onOpenExternalUrl = ::openExternalUrl,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onLocalNetworkPermissionStatus(hasLocalNetworkPermission())
        cameraPermissionGranted.value = hasCameraPermission()
        if (cameraPermissionGranted.value) cameraPermissionPermanentlyDenied.value = false
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
        super.onDestroy()
    }

    private fun hasLocalNetworkPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 37) return true
        return ContextCompat.checkSelfPermission(this, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocalNetworkPermission() {
        if (Build.VERSION.SDK_INT < 37) {
            viewModel.onLocalNetworkPermissionResult(true, permanentlyDenied = false)
            return
        }
        localNetworkPermissionLauncher.launch(LOCAL_NETWORK_PERMISSION)
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    private fun openExternalUrl(value: String) {
        val uri = value.toUri()
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path.orEmpty()
        val allowed = uri.scheme.equals("https", ignoreCase = true) && when (host) {
            "github.com" -> path.startsWith("/shuimowang/QuickerLink")
            "getquicker.net" -> path.startsWith("/User/Actions/743590-")
            else -> false
        }
        if (!allowed) {
            Toast.makeText(this, "无法打开这个链接", Toast.LENGTH_SHORT).show()
            return
        }

        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE),
            )
        }.onFailure {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
