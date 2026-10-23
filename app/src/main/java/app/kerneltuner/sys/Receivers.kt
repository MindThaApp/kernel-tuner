package app.kerneltuner.sys

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.kerneltuner.core.StepDown
import app.kerneltuner.hw.HardwareDetector
import kotlin.concurrent.thread

private inline fun BroadcastReceiver.async(crossinline block: () -> Unit) {
    val pending = goAsync()
    thread(name = "kt-recv") {
        try { block() } catch (_: Throwable) { } finally { pending.finish() }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        async { BootProcessor.run(ctx.applicationContext, allowApply = true) }
    }
}

/** SHUTDOWN / REBOOT: write the clean-shutdown marker synchronously and return immediately. */
class ShutdownReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        BootProcessor.markCleanShutdown(ctx.applicationContext)
    }
}

class TestRevertReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        async { TestMode.revert(ctx.applicationContext) }
    }
}

class StableReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        BootProcessor.markStable(ctx.applicationContext)
    }
}

class NoticeActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val app = ctx.applicationContext
        val id = i.getStringExtra("profile_id")
        when (i.action) {
            Notifier.ACTION_STEP_DOWN -> async {
                val store = Store(app)
                val p = store.profiles().firstOrNull { it.id == id }
                if (p != null) {
                    val hw = HardwareDetector.detect(AppFs())
                    val (ns, notes) = StepDown.apply(hw, p.settings)
                    store.updateProfile(p.id) { it.copy(settings = ns, unstable = false, unstableReason = null, applyOnBoot = false) }
                    store.notice = null
                    Notifier.postInfo(app, "Profile stepped down", (if (notes.isEmpty()) "Nothing to step down." else notes.joinToString("; ")) + " Apply-on-boot is OFF; re-enable it in the app after testing.")
                }
                Notifier.cancelUnclean(app)
            }
            Notifier.ACTION_DISMISS -> { Store(app).notice = null; Notifier.cancelUnclean(app) }
        }
    }
}
