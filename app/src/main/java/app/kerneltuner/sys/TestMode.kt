package app.kerneltuner.sys

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.kerneltuner.core.ActiveRecord
import app.kerneltuner.core.HeldState
import app.kerneltuner.core.OcPolicy
import app.kerneltuner.core.OcSteps
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
        store.test = TestState(store.bootId(), deadline, plan.revertOps, plan.summary, profileId, System.currentTimeMillis(), minutes * 60_000L)
        alarms(ctx).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + minutes * 60_000L, pi(ctx))
        val r = Applier.apply(ctx, fs, hw, plan, "TEST", profileId, stock)
        if (!r.ok) revert(ctx)   // partially applied: undo immediately
        return r
    }

    /** Returns true when this Keep also confirmed a profile (a full test run, then Keep). */
    fun keep(ctx: Context): Boolean {
        val store = Store(ctx)
        alarms(ctx).cancel(pi(ctx))
        val t = store.test
        var confirmed = false
        if (t != null && t.bootId == store.bootId()) {
            // Only now do the limits become "held" for this boot.
            t.pendingHeld?.let { if (!it.isEmpty) { store.held = HeldState(t.bootId, it); Reconcile.schedule(ctx) } }
            val pid = t.profileId
            if (pid != null && OcPolicy.isFullTest(t.startedWallMs, t.durationMs, System.currentTimeMillis())) {
                store.updateProfile(pid) { it.copy(confirmedSettings = it.settings) }
                confirmed = store.profiles().any { it.id == pid }
            }
            // The marker stays until the overclock has been stable for a while (or the phone shuts down cleanly).
            if (OcMarker.read(ctx) != null) BootProcessor.scheduleOcStable(ctx)
        }
        store.test = null
        store.active?.let { if (it.kind == "TEST") store.active = it.copy(kind = "MANUAL") }
        return confirmed
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
        store.held = null          // the revert plan put the touched values back to stock
        Reconcile.cancel(ctx)
        val hw = HwProbe.detect(ctx, fs)
        val stock = store.stock?.takeIf { it.bootId == store.bootId() }?.settings
        val stillNonStock = stock == null || Planner.diffFromStock(stock, Snapshot.read(fs, hw)).isNotEmpty()
        if (stock != null && OcSteps.of(hw, Snapshot.read(fs, hw), stock).isEmpty()) OcMarker.clear(ctx)
        if (!stillNonStock && result.ok) store.active = null
        else store.active?.let { store.active = it.copy(kind = "MANUAL") }
        return result
    }

    fun pending(ctx: Context): TestState? = Store(ctx).test?.takeIf { it.bootId == Store(ctx).bootId() }
}
