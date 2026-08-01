package app.quickerlink.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.quickerlink.MainActivity
import app.quickerlink.QuickerLinkApplication
import app.quickerlink.R
import app.quickerlink.connection.QuickerConnectionState
import app.quickerlink.data.PreferenceWriteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class QuickerLinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runtime by lazy { (application as QuickerLinkApplication).connectionRuntime }
    private var stateJob: Job? = null
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val result = runtime.setBackgroundConnectionEnabled(false)
            val failure = backgroundServiceStopFailure(result)
            if (failure != null) {
                startOrUpdateForeground(QuickerConnectionState.Error(failure))
                return START_NOT_STICKY
            }
            if (!runtime.shouldRetainConnection()) runtime.manager.disconnect()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!runtime.backgroundConnectionEnabled.value) {
            stopSelf()
            return START_NOT_STICKY
        }

        startOrUpdateForeground(runtime.manager.state.value)
        if (stateJob == null) {
            stateJob = scope.launch {
                runtime.manager.state.collect(::startOrUpdateForeground)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stateJob?.cancel()
        stateJob = null
        if (!runtime.isAppInForeground()) runtime.manager.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    private fun startOrUpdateForeground(state: QuickerConnectionState) {
        val notification = buildNotification(state)
        if (!foregroundStarted) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            foregroundStarted = true
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(state: QuickerConnectionState): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopService = PendingIntent.getService(
            this,
            REQUEST_STOP_SERVICE,
            Intent(this, QuickerLinkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val (title, detail) = notificationText(state)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "停止后台连接", stopService)
            .build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "后台连接",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Quicker Link 在后台保持局域网连接时显示"
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val ACTION_START = "app.quickerlink.action.START_BACKGROUND_CONNECTION"
        private const val ACTION_STOP = "app.quickerlink.action.STOP_BACKGROUND_CONNECTION"
        private const val CHANNEL_ID = "quicker_link_background_connection"
        private const val NOTIFICATION_ID = 420
        private const val REQUEST_OPEN_APP = 421
        private const val REQUEST_STOP_SERVICE = 422

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, QuickerLinkService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, QuickerLinkService::class.java))
        }
    }
}

internal fun backgroundServiceStopFailure(result: PreferenceWriteResult): String? = when (result) {
    PreferenceWriteResult.Success -> null
    is PreferenceWriteResult.Failure -> result.message
}

internal fun notificationText(state: QuickerConnectionState): Pair<String, String> = when (state) {
    is QuickerConnectionState.Ready -> "Quicker Link 已连接" to state.endpoint
    is QuickerConnectionState.Connecting -> "Quicker Link 正在连接" to state.endpoint
    QuickerConnectionState.Authenticating -> "Quicker Link 正在认证" to "等待 Quicker 响应"
    is QuickerConnectionState.Reconnecting -> "Quicker Link 正在重连" to
        "${state.delaySeconds} 秒后重试"
    is QuickerConnectionState.AuthFailed -> "Quicker Link 认证失败" to state.reason
    is QuickerConnectionState.Error -> "Quicker Link 连接异常" to state.reason
    QuickerConnectionState.Disconnected -> "后台增强连接已开启" to "打开 App 连接电脑"
}
