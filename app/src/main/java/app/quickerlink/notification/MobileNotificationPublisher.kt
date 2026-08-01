package app.quickerlink.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.quickerlink.MainActivity
import app.quickerlink.R
import java.util.concurrent.atomic.AtomicInteger

object MobileNotificationPublisher {
    private val nextId = AtomicInteger(1_000)

    fun publish(context: Context, title: String, body: String): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return runCatching {
            createChannel(context)
            val notificationManager = NotificationManagerCompat.from(context)
            if (!notificationManager.areNotificationsEnabled()) return@runCatching false
            if (
                context.getSystemService(NotificationManager::class.java)
                    .getNotificationChannel(CHANNEL_ID)
                    ?.importance == NotificationManager.IMPORTANCE_NONE
            ) {
                return@runCatching false
            }
            val openApp = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setSilent(true)
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .build()
            notificationManager.notify(nextId.getAndIncrement(), notification)
            true
        }.getOrDefault(false)
    }

    private fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val legacyChannel = manager.getNotificationChannel(LEGACY_CHANNEL_ID)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "电脑通知",
                    legacyChannel?.importance ?: NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Quicker 从电脑发送到手机的通知"
                    setSound(null, null)
                    enableVibration(false)
                    legacyChannel?.let {
                        lockscreenVisibility = it.lockscreenVisibility
                        setShowBadge(it.canShowBadge())
                    }
                },
            )
        }
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
    }

    private const val CHANNEL_ID = "quicker_link_desktop_messages_silent_v2"
    private const val LEGACY_CHANNEL_ID = "quicker_link_desktop_messages"
}
