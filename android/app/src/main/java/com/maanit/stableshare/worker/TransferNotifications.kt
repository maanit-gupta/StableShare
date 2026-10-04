package com.maanit.stableshare.worker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import com.maanit.stableshare.MainActivity
import com.maanit.stableshare.R
import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.domain.TransferType
import com.maanit.stableshare.engine.CoordinatorStatus
import com.maanit.stableshare.ui.AppIntents
import com.maanit.stableshare.ui.components.MAX_TRIES
import com.maanit.stableshare.ui.model.ErrorCopy
import com.maanit.stableshare.ui.model.Format

/**
 * Notifications (UI-SPEC §5.12): the coordinator's ongoing notification on "transfers" (low
 * importance) and per-transfer results on "results" (default importance). All use the plane
 * icon and the mint accent. Without POST_NOTIFICATIONS nothing is shown; transfers run anyway.
 */
class TransferNotifications(private val context: Context) {

    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_transfers), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
        manager.createNotificationChannel(
            NotificationChannel(RESULTS_CHANNEL_ID, context.getString(R.string.channel_results), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /**
     * "Moving {n} file(s)" / "{percent}% overall, {speed}", with a determinate overall bar. Tap:
     * Transfers. Action "Pause transfers" pauses everything queued or running (both hosts).
     */
    fun build(status: CoordinatorStatus): Notification {
        val n = maxOf(status.active, status.pending)
        val speed = Format.speed(status.bytesPerSecond)
        val text = if (speed != null) {
            context.getString(R.string.notif_overall_speed, status.percent, speed)
        } else {
            context.getString(R.string.notif_overall, status.percent)
        }
        val open = Intent(context, MainActivity::class.java)
            .putExtra(AppIntents.EXTRA_OPEN_TRANSFERS, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return base(CHANNEL_ID)
            .setContentTitle(context.resources.getQuantityString(R.plurals.notif_moving, n, n))
            .setContentText(text)
            .setProgress(100, status.percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(PendingIntent.getActivity(context, REQUEST_ONGOING, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, context.getString(R.string.notif_pause_transfers), pauseIntent())
            .build()
    }

    private fun pauseIntent(): PendingIntent {
        val intent = Intent(context, PauseTransfersReceiver::class.java).setAction(PauseTransfersReceiver.ACTION)
        return PendingIntent.getBroadcast(context, REQUEST_PAUSE, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun foregroundInfo(status: CoordinatorStatus): ForegroundInfo {
        val notification = build(status)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    /** "Upload complete" / "Download complete" — "{name} was verified." Tap: the transfer's Detail. */
    fun completed(row: TransferEntity): Notification = result(
        row,
        title = if (row.type == TransferType.UPLOAD) R.string.notif_upload_complete else R.string.notif_download_complete,
        text = context.getString(R.string.notif_verified, row.fileName),
    )

    /** "Upload failed" / "Download failed" — "{name}: {short reason}. Tap to see options." */
    fun failed(row: TransferEntity): Notification = result(
        row,
        title = if (row.type == TransferType.UPLOAD) R.string.notif_upload_failed else R.string.notif_download_failed,
        text = context.getString(R.string.notif_failed_text, row.fileName, ErrorCopy.short(row.errorCode, MAX_TRIES).resolve(context.resources)),
    )

    private fun result(row: TransferEntity, title: Int, text: String): Notification {
        val open = Intent(context, MainActivity::class.java)
            .putExtra(AppIntents.EXTRA_TRANSFER_ID, row.id)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return base(RESULTS_CHANNEL_ID)
            .setContentTitle(context.getString(title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, resultId(row.id), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
    }

    /** Posts [notification] for transfer [id] (one per transfer), if the user allows notifications. */
    fun postResult(id: String, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(resultId(id), notification)
    }

    private fun base(channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_stat_plane)
        .setColor(ContextCompat.getColor(context, R.color.brand_mint))

    companion object {
        const val CHANNEL_ID = "transfers"
        const val RESULTS_CHANNEL_ID = "results"
        const val NOTIFICATION_ID = 1001

        /** The UIDT job's copy of the ongoing notification; the system removes it when the job ends. */
        const val JOB_NOTIFICATION_ID = 1002
        private const val REQUEST_ONGOING = 0
        private const val REQUEST_PAUSE = 1

        /** Stable per-transfer id, never colliding with the ongoing notification. */
        fun resultId(transferId: String): Int = (transferId.hashCode() and 0x7fffffff) or 0x10000
    }
}
