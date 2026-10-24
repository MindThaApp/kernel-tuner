package app.kerneltuner.core

import app.kerneltuner.hw.Hardware
import app.kerneltuner.hw.SysFs
import app.kerneltuner.hw.WriteOp
import app.kerneltuner.hw.TempKind
import app.kerneltuner.hw.TempLimitDetector
import app.kerneltuner.hw.firstInt

/**
 * Finds the writes needed to put back settings the system has quietly reset:
 *  - a CPU core that was hotplugged off and came back with the default limits,
 *  - PowerHAL rewriting Samsung's IPA temperature target when the power profile changes.
 * Pure: reads through [SysFs], returns writes. Only touches what the user explicitly set (the "held" values).
 */
object Reconciler {
    fun fixups(hw: Hardware, held: Settings, fs: SysFs): List<WriteOp> {
        val ops = ArrayList<WriteOp>()
        for ((id, want) in held.cpu) {
            val c = hw.clusters.firstOrNull { it.id == id } ?: continue
            val wantMax = want.maxKhz?.let { Planner.snap(c.freqs, it.coerceIn(c.hwMinKhz, c.hwMaxKhz)) }
            val wantMin = want.minKhz?.let { Planner.snap(c.freqs, it.coerceIn(c.hwMinKhz, c.hwMaxKhz)) }
            for (dir in c.paths) {
                val curMax = fs.read("$dir/scaling_max_freq").firstInt() ?: continue   // offline: nothing to do yet
                val curMin = fs.read("$dir/scaling_min_freq").firstInt() ?: continue
                val gov = fs.read("$dir/scaling_governor")
                val max = wantMax ?: curMax
                val min = (wantMin ?: curMin).coerceAtMost(max)
                val maxOp = if (max != curMax) listOf(WriteOp("$dir/scaling_max_freq", "$max", "${c.label} max")) else emptyList()
                val minOp = if (min != curMin) listOf(WriteOp("$dir/scaling_min_freq", "$min", "${c.label} min")) else emptyList()
                if (want.governor != null && gov != null && gov != want.governor && want.governor in c.governors) {
                    ops += WriteOp("$dir/scaling_governor", want.governor, "${c.label} governor")
                }
                if (Planner.minFirst(curMax, min)) { ops += minOp; ops += maxOp } else { ops += maxOp; ops += minOp }
            }
        }
        for ((id, wantC) in held.temp) {
            val g = hw.tempGroups.firstOrNull { it.id == id } ?: continue
            // Trips and the thermal-engine file are not reset by the system; do not touch them here.
            if (g.kind == TempKind.TMU || g.kind == TempKind.THERMAL_ENGINE) continue
            for (p in g.paths) {
                val cur = TempLimitDetector.read(fs, g) ?: continue
                if (cur != wantC) ops += WriteOp(p, (if (g.milli) wantC * 1000 else wantC).toString(), "${g.label} temperature limit")
            }
        }
        return ops
    }

    /**
     * New held state after a plan was applied: replaces the entries the plan touched, drops anything that is
     * back at stock. CPU limits and temperature limits only.
     */
    fun update(prev: Settings?, plan: Plan, targetKeysCpu: Set<String>, targetKeysTemp: Set<String>, stock: Settings): Settings {
        val cpu = HashMap(prev?.cpu ?: emptyMap())
        for (k in targetKeysCpu) {
            val v = plan.after.cpu[k]
            if (v == null || stock.cpu[k] == v) cpu.remove(k) else cpu[k] = v
        }
        val temp = HashMap(prev?.temp ?: emptyMap())
        for (k in targetKeysTemp) {
            val v = plan.after.temp[k]
            if (v == null || stock.temp[k] == v) temp.remove(k) else temp[k] = v
        }
        return Settings(cpu = cpu, temp = temp)
    }
}
