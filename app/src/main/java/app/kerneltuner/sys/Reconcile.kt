package app.kerneltuner.sys

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.kerneltuner.core.Reconciler
import kotlin.concurrent.thread

/**
 * Puts back CPU limits and temperature limits that the system reset behind our back (a core coming back
 * online, PowerHAL switching profile). Runs from the app's own 5 s tick while it is on screen, and from a
 * slow inexact alarm (about every 3 min, only while something is held, and only while the device is awake).
 */
object Reconcile {
    const val ACTION = "app.kerneltuner.action.RECONCILE"
    private const val EVERY_MS = 3 * 60_000L

    private fun pi(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 3, Intent(ctx, ReconcileReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + EVERY_MS, pi(ctx))
    }

    fun cancel(ctx: Context) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi(ctx))
    }

    /** Returns the number of values that were put back. Never call from the main thread. */
    @Synchronized
    fun run(ctx: Context): Int {
        val store = Store(ctx)
        val held = store.held ?: return 0
        if (!Root.available()) return 0
        val fs = AppFs()
        val hw = HwProbe.detect(ctx, fs)
        val ops = Reconciler.fixups(hw, held.settings, fs)
        if (ops.isEmpty()) return 0
        Applier.write(fs, ops)
        return ops.size
    }
}

class ReconcileReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val app = ctx.applicationContext
        val pending = goAsync()
        thread(name = "kt-reconcile") {
            try {
                Reconcile.run(app)
                if (Store(app).held != null) Reconcile.schedule(app)
            } catch (_: Throwable) {
            } finally {
                pending.finish()
            }
        }
    }
}
