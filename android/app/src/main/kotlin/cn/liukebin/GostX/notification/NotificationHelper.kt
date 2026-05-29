package cn.liukebin.GostX.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import cn.liukebin.GostX.MainActivity
import cn.liukebin.GostX.R
import cn.liukebin.GostX.service.GostVpnService

const val CHANNEL_ID = "gostx_vpn"
const val NOTIFICATION_ID = 1

object NotificationHelper {

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GostX VPN",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "GostX VPN service status"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun buildRunningNotification(context: Context, addr: String): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, GostVpnService::class.java).apply { action = GostVpnService.ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(if (addr.isNotEmpty()) context.getString(R.string.listen_addr, addr) else context.getString(R.string.notification_connecting))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openIntent)
            .addAction(0, context.getString(R.string.notification_stop), stopIntent)
            .setOngoing(true)
            .build()
    }
}
