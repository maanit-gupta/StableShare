package com.maanit.stableshare.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.maanit.stableshare.MainActivity
import com.maanit.stableshare.R
import com.maanit.stableshare.engine.CoordinatorStatus

/**
 * The coordinator's ongoing, low-importance notification (channel "transfers"). Without the
 * POST_NOTIFICATIONS grant it is simply not shown; the work runs either way.
 */
class TransferNotifications(private val context: Context) {

    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "Transfers", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress of running uploads and downloads"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(status: CoordinatorStatus): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = when (status.active) {
            0 -> "Preparing transfers"
            1 -> "1 transfer running"
            else -> "${status.active} transfers running"
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_transfer)
            .setContentTitle(title)
            .setContentText(if (status.totalBytes > 0) "${status.percent}% · ${mb(status.bytes)} / ${mb(status.totalBytes)} MB" else null)
            .setProgress(100, status.percent, status.totalBytes <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    fun foregroundInfo(status: CoordinatorStatus): ForegroundInfo {
        val notification = build(status)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun mb(bytes: Long) = bytes / (1024 * 1024)

    companion object {
        const val CHANNEL_ID = "transfers"
        const val NOTIFICATION_ID = 1001
    }
}
