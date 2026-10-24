package app.kerneltuner.sys

import android.content.Context
import app.kerneltuner.core.ActiveRecord
import app.kerneltuner.core.Plan
import app.kerneltuner.core.HeldState
import app.kerneltuner.core.OcMarkerData
import app.kerneltuner.core.OcPolicy
import app.kerneltuner.core.OcSteps
import app.kerneltuner.core.Planner
import app.kerneltuner.core.Reconciler
import app.kerneltuner.core.WriteWhitelist
import app.kerneltuner.core.Snapshot
import app.kerneltuner.hw.BlockDetector
import app.kerneltuner.hw.GpuDetector
import app.kerneltuner.hw.Hardware
import app.kerneltuner.hw.SysFs
import app.kerneltuner.hw.Verify
import app.kerneltuner.hw.WriteOp

/** [skipped]: writes to cores that are offline right now. Not a failure; Reconcile applies them when the core returns. */
data class ApplyResult(val ok: Boolean, val failed: List<String>, val skipped: List<String> = emptyList())

object Applier {
    private val OFFLINE_CPU = Regex("^/sys/devices/system/cpu/cpu(\\d+)/cpufreq/")
    private val SAFE_VALUE = Regex("^-?[A-Za-z0-9_.+\\-]{1,48}$")
    private val SAFE_PATH = Regex("^/(sys|proc)/[A-Za-z0-9_./:\\-@]+$")

    private fun q(p: String) = "'$p'"

    /** Runs the ops through su and verifies by reading back. Caller handles the active-record write-ahead. */
    fun write(fs: SysFs, ops: List<WriteOp>): ApplyResult {
        if (ops.isEmpty()) return ApplyResult(true, emptyList())
        if (!Root.available()) return ApplyResult(false, listOf("Root not available"))
        val failed = ArrayList<String>()
        val skipped = ArrayList<String>()
        val todo = ArrayList<WriteOp>()
        for (op in ops) {
            if (op.path == WriteWhitelist.THERMAL_CONF) {
                // Not a sysfs echo: a checked rewrite of two lines in the module's conf.
                val n = op.value.toIntOrNull()
                if (n == null || !WriteWhitelist.allows(op)) { failed += "${op.label}: rejected unsafe path/value"; continue }
                ThermalEngine.set(n)?.let { failed += "${op.label}: $it" }
                continue
            }
            if (!SAFE_VALUE.matches(op.value) || !SAFE_PATH.matches(op.path) || !WriteWhitelist.allows(op)) {
                failed += "${op.label}: rejected unsafe path/value"
                continue
            }
            // A core that is hotplugged off has no cpufreq directory: not an error.
            val off = OFFLINE_CPU.find(op.path)
            if (off != null && !fs.exists(op.path.substringBeforeLast('/'))) {
                skipped += "CPU core ${off.groupValues[1]} is offline"
                continue
            }
            todo += op
        }
        // One op at a time so we know which one failed.
        for (op in todo) {
            val out = Root.run("echo ${q(op.value)} > ${q(op.path)} 2>/dev/null || echo FAIL")
            if (out.any { it.contains("FAIL") }) failed += "write failed: ${op.path}"
        }
        for (op in todo) if (!verify(fs, op)) failed += "${op.label}: value did not stick"
        return ApplyResult(failed.isEmpty(), failed.distinct(), skipped.distinct())
    }

    private fun verify(fs: SysFs, op: WriteOp): Boolean {
        val now = fs.read(op.path) ?: return false
        return when (op.verify) {
            Verify.NONE -> true
            Verify.EXACT -> now.trim() == op.value || (now.trim().toLongOrNull() != null && now.trim().toLongOrNull() == op.value.toLongOrNull())
            Verify.SCHED -> BlockDetector.parseScheduler(now).second == op.value
            Verify.MALI_GOV -> GpuDetector.parseMaliGovernors(now).second == op.expect
        }
    }

    /**
     * Applies a plan with the write-ahead active record: the record that "something non-stock is active"
     * is committed to disk BEFORE the first write, so a crash mid-apply is still detected next boot.
     */
    fun apply(
        ctx: Context,
        fs: SysFs,
        hw: Hardware,
        plan: Plan,
        kind: String,
        profileId: String?,
        stock: app.kerneltuner.core.Settings,
    ): ApplyResult {
        val store = Store(ctx)
        if (plan.ops.isEmpty()) return ApplyResult(true, emptyList())
        val prev = store.active
        val bootId = store.bootId()
        val prevSame = prev?.takeIf { it.bootId == bootId }
        store.active = ActiveRecord(
            bootId = bootId,
            profileId = profileId ?: prevSame?.profileId,
            kind = if (prevSame?.kind == "BOOT" && kind == "MANUAL") "BOOT" else kind,
            summary = ((prevSame?.summary ?: emptyList()) + plan.summary).distinct(),
            atMs = System.currentTimeMillis(),
        )
        // Write-ahead: the "overclock being applied" marker is on disk (fsynced) before the first above-stock write.
        val ocSteps = OcSteps.of(hw, plan.after, stock)
        if (ocSteps.isNotEmpty()) {
            val old = OcMarker.read(ctx)?.takeIf { it.bootId == bootId }
            OcMarker.write(ctx, OcMarkerData(bootId, profileId ?: old?.profileId, ((old?.steps ?: emptyList()) + ocSteps).distinct(), System.currentTimeMillis()))
        }
        if (plan.ops.any { it.path == WriteWhitelist.THERMAL_CONF }) store.thermalEngineTouched = true
        val r = write(fs, plan.ops)
        // Remember CPU/temperature limits so they can be put back if the system resets them.
        val prevHeld = store.held?.settings
        val held = Reconciler.update(prevHeld, plan, plan.touchedCpu, plan.touchedTemp, stock)
        if (OcPolicy.persistAsHeld(kind)) {
            store.held = HeldState(bootId, held)
            if (!held.isEmpty) Reconcile.schedule(ctx) else Reconcile.cancel(ctx)
        } else {
            // Test mode: never held. Promoted only if the user taps Keep.
            store.test = store.test?.copy(pendingHeld = held)
        }
        val now = Snapshot.read(fs, hw)
        val diff = Planner.diffFromStock(stock, now)
        if (OcSteps.of(hw, now, stock).isEmpty()) OcMarker.clear(ctx)
        if (diff.isEmpty() && held.isEmpty) store.active = null
        return r
    }
}
