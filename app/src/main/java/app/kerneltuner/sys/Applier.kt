package app.kerneltuner.sys

import android.content.Context
import app.kerneltuner.core.ActiveRecord
import app.kerneltuner.core.Plan
import app.kerneltuner.core.Planner
import app.kerneltuner.core.Snapshot
import app.kerneltuner.hw.BlockDetector
import app.kerneltuner.hw.GpuDetector
import app.kerneltuner.hw.Hardware
import app.kerneltuner.hw.SysFs
import app.kerneltuner.hw.Verify
import app.kerneltuner.hw.WriteOp

data class ApplyResult(val ok: Boolean, val failed: List<String>)

object Applier {
    private val SAFE_VALUE = Regex("^-?[A-Za-z0-9_.+\\-]{1,48}$")
    private val SAFE_PATH = Regex("^/(sys|proc)/[A-Za-z0-9_./:\\-@]+$")

    private fun q(p: String) = "'$p'"

    /** Runs the ops through su and verifies by reading back. Caller handles the active-record write-ahead. */
    fun write(fs: SysFs, ops: List<WriteOp>): ApplyResult {
        if (ops.isEmpty()) return ApplyResult(true, emptyList())
        if (!Root.available()) return ApplyResult(false, listOf("Root not available"))
        val failed = ArrayList<String>()
        val script = ArrayList<String>()
        for (op in ops) {
            if (!SAFE_VALUE.matches(op.value) || !SAFE_PATH.matches(op.path)) {
                failed += "${op.label}: rejected unsafe path/value"
                continue
            }
            script += "echo ${q(op.value)} > ${q(op.path)} 2>/dev/null || echo FAIL"
        }
        // Run one op at a time so we know which one failed, in one shell session.
        for ((i, line) in script.withIndex()) {
            val out = Root.run(line)
            if (out.any { it.contains("FAIL") }) failed += "write failed: " + line.substringAfter("> ").substringBefore(" 2>")
        }
        for (op in ops) {
            if (!SAFE_VALUE.matches(op.value) || !SAFE_PATH.matches(op.path)) continue
            if (!verify(fs, op)) failed += "${op.label}: value did not stick"
        }
        return ApplyResult(failed.isEmpty(), failed.distinct())
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
        val r = write(fs, plan.ops)
        val diff = Planner.diffFromStock(stock, Snapshot.read(fs, hw))
        if (diff.isEmpty()) store.active = null
        return r
    }
}
