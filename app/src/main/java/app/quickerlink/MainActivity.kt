package app.quickerlink

import android.Manifest
import android.content.ClipData
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.mutableStateOf
import app.quickerlink.ui.QuickerApp
import app.quickerlink.ui.theme.QuickerLinkTheme
import app.quickerlink.update.FILE_PROVIDER_AUTHORITY
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: QuickerViewModel by viewModels()
    private val cameraPermissionGranted = mutableStateOf(false)
    private val cameraPermissionPermanentlyDenied = mutableStateOf(false)
    private val notificationPermissionGranted = mutableStateOf(false)
    private val notificationPermissionPermanentlyDenied = mutableStateOf(false)
    private var pendingInstallUri: Uri? = null
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

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationPermissionGranted.value = granted
        notificationPermissionPermanentlyDenied.value =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted &&
            !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)
        if (granted) {
            notificationPermissionPermanentlyDenied.value = false
            viewModel.onNotificationPermissionStatus(true)
        } else {
            viewModel.reportBackgroundConnectionPermissionDenied()
        }
    }

    private val unknownAppsSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val uri = pendingInstallUri ?: return@registerForActivityResult
        if (canInstallPackages()) {
            launchPackageInstaller(uri)
        } else {
            pendingInstallUri = null
            viewModel.reportInstallerError("需要允许 Quicker Link 安装未知应用后才能继续")
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(viewModel::sendFileToComputer)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        viewModel.onLocalNetworkPermissionStatus(hasLocalNetworkPermission())
        cameraPermissionGranted.value = hasCameraPermission()
        notificationPermissionGranted.value = hasNotificationPermission()
        viewModel.onNotificationPermissionStatus(notificationPermissionGranted.value)
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.installRequests.collect(::beginUpdateInstallation)
            }
        }

        setContent {
            QuickerLinkTheme {
                QuickerApp(
                    viewModel = viewModel,
                    onRequestLocalNetworkPermission = ::requestLocalNetworkPermission,
                    cameraPermissionGranted = cameraPermissionGranted.value,
                    cameraPermissionPermanentlyDenied = cameraPermissionPermanentlyDenied.value,
                    onRequestCameraPermission = ::requestCameraPermission,
                    notificationPermissionGranted = notificationPermissionGranted.value,
                    notificationPermissionPermanentlyDenied = notificationPermissionPermanentlyDenied.value,
                    onRequestNotificationPermission = ::requestNotificationPermission,
                    onOpenAppSettings = ::openAppSettings,
                    onOpenExternalUrl = ::openExternalUrl,
                    onChooseFile = ::chooseFile,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onLocalNetworkPermissionStatus(hasLocalNetworkPermission())
        cameraPermissionGranted.value = hasCameraPermission()
        if (cameraPermissionGranted.value) cameraPermissionPermanentlyDenied.value = false
        notificationPermissionGranted.value = hasNotificationPermission()
        if (notificationPermissionGranted.value) {
            notificationPermissionPermanentlyDenied.value = false
        }
        viewModel.onNotificationPermissionStatus(notificationPermissionGranted.value)
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

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionGranted.value = true
            viewModel.onNotificationPermissionStatus(true)
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun chooseFile() {
        filePickerLauncher.launch(arrayOf("*/*"))
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
        val allowed = uri.scheme.equals("https", ignoreCase = true) &&
            uri.userInfo == null &&
            uri.port == -1 &&
            uri.fragment == null &&
            when (host) {
                "github.com" -> path.startsWith("/shuimowang/QuickerLink")
                "getquicker.net" -> path.startsWith("/User/Actions/743590-") ||
                    (
                        path.equals("/Sharedaction", ignoreCase = true) &&
                            uri.queryParameterNames == setOf("code") &&
                            uri.getQueryParameter("code") == COMPANION_ACTION_CODE
                        )
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

    private fun beginUpdateInstallation(uri: Uri) {
        if (uri.scheme != "content" || uri.authority != FILE_PROVIDER_AUTHORITY) {
            viewModel.reportInstallerError("安装包地址无效，已停止安装")
            return
        }
        if (canInstallPackages()) {
            launchPackageInstaller(uri)
            return
        }

        pendingInstallUri = uri
        runCatching {
            unknownAppsSettingsLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.fromParts("package", packageName, null),
                ),
            )
        }.onFailure {
            pendingInstallUri = null
            viewModel.reportInstallerError("无法打开“安装未知应用”设置")
        }
    }

    private fun canInstallPackages(): Boolean = packageManager.canRequestPackageInstalls()

    private fun launchPackageInstaller(uri: Uri) {
        pendingInstallUri = null
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            clipData = ClipData.newRawUri("Quicker Link update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure { viewModel.reportInstallerError("无法打开系统安装器") }
    }

    private companion object {
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        const val COMPANION_ACTION_CODE = "b02b2732-f087-4e45-416d-08deee3e76ba"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
