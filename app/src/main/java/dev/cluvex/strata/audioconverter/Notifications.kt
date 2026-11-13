package dev.cluvex.strata.audioconverter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * The progress notification is ongoing and, if the user swipes it away anyway (Android 14+ allows that for
 * foreground services), it is posted again. The finished notification is ongoing too and only leaves through
 * its Open button.
 */
object Notifications {
    const val PROGRESS_ID = 1001
    const val DONE_ID = 1002
    const val FAILED_ID = 1003

    const val ACTION_CANCEL = "dev.cluvex.strata.audioconverter.action.CANCEL"
    const val ACTION_REPOST_PROGRESS = "dev.cluvex.strata.audioconverter.action.REPOST_PROGRESS"
    const val ACTION_REPOST_DONE = "dev.cluvex.strata.audioconverter.action.REPOST_DONE"

    private const val CH_PROGRESS = "convert_progress"
    private const val CH_DONE = "convert_done"

    private fun manager(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = manager(ctx)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, ctx.getString(R.string.notif_channel_progress), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, ctx.getString(R.string.notif_channel_done), NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun broadcast(ctx: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, code,
            Intent(ctx, NotificationActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun open(ctx: Context, finished: Boolean): PendingIntent =
        PendingIntent.getActivity(
            ctx, if (finished) 11 else 10,
            Intent(ctx, OpenResultActivity::class.java).putExtra(OpenResultActivity.EXTRA_FINISHED, finished),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun progress(ctx: Context, fileName: String, fraction: Float): Notification {
        val pct = (fraction * 100).toInt().coerceIn(0, 100)
        return NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.converting, pct))
            .setContentText(fileName)
            .setProgress(100, pct, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open(ctx, false))
            .setDeleteIntent(broadcast(ctx, ACTION_REPOST_PROGRESS, 20))
            .addAction(0, ctx.getString(R.string.cancel), broadcast(ctx, ACTION_CANCEL, 22))
            .build()
    }

    fun notifyProgress(ctx: Context, fileName: String, fraction: Float) {
        manager(ctx).notify(PROGRESS_ID, progress(ctx, fileName, fraction))
    }

    fun postDone(ctx: Context, name: String) {
        ensureChannels(ctx)
        val text = ctx.getString(R.string.notif_done_text, name)
        val n = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.notif_done_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(open(ctx, true))
            .setDeleteIntent(broadcast(ctx, ACTION_REPOST_DONE, 21))
            .addAction(0, ctx.getString(R.string.notif_open), open(ctx, true))
            .build()
        manager(ctx).notify(DONE_ID, n)
    }

    fun postFailed(ctx: Context, message: String) {
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.notif_failed_title))
            .setContentText(message)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(open(ctx, false))
            .build()
        manager(ctx).notify(FAILED_ID, n)
    }

    fun cancelDone(ctx: Context) = manager(ctx).cancel(DONE_ID)

    fun cancelAll(ctx: Context) {
        manager(ctx).apply { cancel(DONE_ID); cancel(FAILED_ID) }
    }
}
