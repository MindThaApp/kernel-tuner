package app.kerneltuner.sys

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.kerneltuner.ui.MainActivity

object Notifier {
    const val ACTION_STEP_DOWN = "app.kerneltuner.action.STEP_DOWN"
    const val ACTION_CANCEL_APPLY = "app.kerneltuner.action.CANCEL_APPLY"
    const val ACTION_DISMISS = "app.kerneltuner.action.DISMISS_NOTICE"
    private const val CHANNEL = "stability"
    private const val ID_UNCLEAN = 100

    private fun nm(ctx: Context): NotificationManager? {
        val m = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        m.createNotificationChannel(NotificationChannel(CHANNEL, "Stability warnings", NotificationManager.IMPORTANCE_HIGH))
        return if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) m else null
    }

    private fun open(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(ctx: Context, act: String, id: String?, code: Int) = PendingIntent.getBroadcast(
        ctx, code, Intent(ctx, NoticeActionReceiver::class.java).setAction(act).putExtra("profile_id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun postUnclean(ctx: Context, n: Notice) {
        val m = nm(ctx) ?: return
        val what = n.settings.joinToString("; ").ifEmpty { "non-stock settings" }
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Unclean reboot: tuning not applied")
            .setContentText(what)
            .setStyle(Notification.BigTextStyle().bigText("The device did not shut down cleanly while these were active: $what\n${n.reason}\n" +
                (n.profileName?.let { "Profile \"$it\" is marked unstable and will not apply on boot." } ?: "")))
            .setContentIntent(open(ctx))
            .setAutoCancel(true)
        if (n.profileId != null) {
            b.addAction(Notification.Action.Builder(null, "Step down one notch", action(ctx, ACTION_STEP_DOWN, n.profileId, 11)).build())
        }
        b.addAction(Notification.Action.Builder(null, "Dismiss", action(ctx, ACTION_DISMISS, n.profileId, 12)).build())
        m.notify(ID_UNCLEAN, b.build())
    }

    fun postInfo(ctx: Context, title: String, text: String) {
        val m = nm(ctx) ?: return
        m.notify(101, Notification.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open(ctx)).setAutoCancel(true).build())
    }

    private const val ID_PENDING = 102

    fun postPendingApply(ctx: Context, seconds: Int, profileName: String) {
        val m = nm(ctx) ?: return
        m.notify(ID_PENDING, Notification.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Applying overclock in $seconds s")
            .setContentText("Profile \"$profileName\". Tap Cancel to skip it for this boot.")
            .setContentIntent(open(ctx)).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Cancel", action(ctx, ACTION_CANCEL_APPLY, null, 13)).build()).build())
    }

    fun cancelPendingApply(ctx: Context) {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_PENDING)
    }

    fun cancelUnclean(ctx: Context) {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_UNCLEAN)
    }
}
