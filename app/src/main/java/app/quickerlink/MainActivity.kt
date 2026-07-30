package app.quickerlink

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.quickerlink.ui.QuickerApp
import app.quickerlink.ui.theme.QuickerLinkTheme

class MainActivity : ComponentActivity() {
    private val viewModel: QuickerViewModel by viewModels()
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        viewModel.onLocalNetworkPermissionStatus(hasLocalNetworkPermission())
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)

        setContent {
            QuickerLinkTheme {
                QuickerApp(
                    viewModel = viewModel,
                    onRequestLocalNetworkPermission = ::requestLocalNetworkPermission,
                    onOpenAppSettings = ::openAppSettings,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onLocalNetworkPermissionStatus(hasLocalNetworkPermission())
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

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    private companion object {
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
