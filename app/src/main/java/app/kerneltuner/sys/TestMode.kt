package app.kerneltuner.sys

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.kerneltuner.core.ActiveRecord
import app.kerneltuner.core.Plan
import app.kerneltuner.core.Planner
import app.kerneltuner.core.Snapshot
import app.kerneltuner.core.TestState
import app.kerneltuner.hw.Hardware
import app.kerneltuner.hw.HardwareDetector

/** Apply, then auto-revert to stock after N minutes unless the user taps Keep. AlarmManager only, no service. */
object TestMode {
    const val ACTION_REVERT = "app.kerneltuner.action.TEST_REVERT"

    private fun pi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, TestRevertReceiver::class.java).setAction(ACTION_REVERT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun alarms(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun start(ctx: Context, fs: app.kerneltuner.hw.SysFs, hw: Hardware, plan: Plan, minutes: Int, profileId: String?, stock: app.kerneltuner.core.Settings): ApplyResult {
        val store = Store(ctx)
        if (plan.revertOps.isEmpty() && plan.ops.isNotEmpty()) return ApplyResult(false, listOf("No revert plan available; refusing to start test"))
        val deadline = System.currentTimeMillis() + minutes * 60_000L
        // Persist + arm the revert BEFORE touching hardware.
        store.test = TestState(store.bootId(), deadline, plan.revertOps, plan.summary)
        alarms(ctx).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + minutes * 60_000L, pi(ctx))
        val r = Applier.apply(ctx, fs, hw, plan, "TEST", profileId, stock)
        if (!r.ok) revert(ctx)   // partially applied: undo immediately
        return r
    }

    fun keep(ctx: Context) {
        val store = Store(ctx)
        alarms(ctx).cancel(pi(ctx))
        store.test = null
        store.active?.let { if (it.kind == "TEST") store.active = it.copy(kind = "MANUAL") }
    }

    /** Idempotent. Safe to call from the alarm receiver, the UI countdown, or on app start after a missed alarm. */
    fun revert(ctx: Context): ApplyResult {
        val store = Store(ctx)
        alarms(ctx).cancel(pi(ctx))
        val t = store.test ?: return ApplyResult(true, emptyList())
        val fs = AppFs()
        var result = ApplyResult(true, emptyList())
        if (t.bootId == store.bootId()) {
            result = Applier.write(fs, t.revertOps)
        }
        store.test = null
        val hw = HardwareDetector.detect(fs)
        val stock = store.stock?.takeIf { it.bootId == store.bootId() }?.settings
        val stillNonStock = stock == null || Planner.diffFromStock(stock, Snapshot.read(fs, hw)).isNotEmpty()
        if (!stillNonStock && result.ok) store.active = null
        else store.active?.let { store.active = it.copy(kind = "MANUAL") }
        return result
    }

    fun pending(ctx: Context): TestState? = Store(ctx).test?.takeIf { it.bootId == Store(ctx).bootId() }
}
