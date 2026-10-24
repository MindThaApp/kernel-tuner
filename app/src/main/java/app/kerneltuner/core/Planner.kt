package app.kerneltuner.core

import app.kerneltuner.hw.CpuCluster
import app.kerneltuner.hw.GpuDetector
import app.kerneltuner.hw.GpuKind
import app.kerneltuner.hw.TempKind
import app.kerneltuner.hw.Hardware
import app.kerneltuner.hw.Verify
import app.kerneltuner.hw.WriteOp

data class Plan(
    val ops: List<WriteOp>,
    val revertOps: List<WriteOp>,
    /** Human-readable list of settings that exceed stock max. */
    val aboveStock: List<String>,
    val summary: List<String>,
    val warnings: List<String>,
    val after: Settings,
    /** Cluster / temperature-group ids the target asked for (even if already at that value). */
    val touchedCpu: Set<String> = emptySet(),
    val touchedTemp: Set<String> = emptySet(),
) {
    val isEmpty get() = ops.isEmpty()
}

/**
 * Turns "wanted settings" into an ordered list of sysfs writes. Pure: everything it needs
 * (hardware, current values, stock values) is passed in. Values are clamped to the hardware
 * tables here, so nothing outside the detected ranges can ever be emitted.
 */
object Planner {
    private val SAFE_VALUE = Regex("^[A-Za-z0-9_.+\\-]{1,48}$")

    private fun ocSuffix(l: OcLevel) = when (l) {
        OcLevel.STOCK -> ""
        OcLevel.OVERCLOCK -> " - overclock"
        OcLevel.UNSTABLE -> " - overclock, likely unstable"
    }

    fun snap(table: List<Int>, v: Int): Int = table.minByOrNull { Math.abs(it - v) } ?: v

    /** Order in which to write (min, max) so the kernel never sees min > max. */
    fun minFirst(curMax: Int, newMin: Int) = newMin <= curMax

    fun plan(
        hw: Hardware,
        current: Settings,
        target: Settings,
        stock: Settings,
        withRevert: Boolean = true,
    ): Plan {
        val ops = ArrayList<WriteOp>()
        val above = ArrayList<String>()
        val summary = ArrayList<String>()
        val warn = ArrayList<String>()
        val afterCpu = HashMap(current.cpu)
        var afterGpu = current.gpu
        val afterIo = HashMap(current.io)
        val afterMem = HashMap(current.mem)
        val afterTemp = HashMap(current.temp)
        val touchedCpu = HashSet<String>()
        val touchedTemp = HashSet<String>()

        for ((id, t) in target.cpu) {
            val c = hw.clusters.firstOrNull { it.id == id }
            if (c == null) {
                warn += "Unknown CPU cluster $id ignored"
                continue
            }
            val cur = current.cpu[id]
            val curGov = cur?.governor
            val curMin = cur?.minKhz ?: c.hwMinKhz
            val curMax = cur?.maxKhz ?: c.hwMaxKhz
            touchedCpu += id
            var gov = curGov
            if (t.governor != null) {
                if (t.governor in c.governors && SAFE_VALUE.matches(t.governor)) gov = t.governor
                else warn += "${c.label}: governor '${t.governor}' not available, ignored"
            }
            var newMax = t.maxKhz?.let { snap(c.freqs, it.coerceIn(c.hwMinKhz, c.hwMaxKhz)) } ?: curMax
            var newMin = t.minKhz?.let { snap(c.freqs, it.coerceIn(c.hwMinKhz, c.hwMaxKhz)) } ?: curMin
            if (newMin > newMax) newMin = newMax
            if (gov != null && gov != curGov) {
                for (p in c.paths) ops += WriteOp("$p/scaling_governor", gov, "${c.label} governor")
            }
            val rangeOps = ArrayList<WriteOp>()
            val minOps = if (newMin != curMin) c.paths.map { WriteOp("$it/scaling_min_freq", "$newMin", "${c.label} min") } else emptyList()
            val maxOps = if (newMax != curMax) c.paths.map { WriteOp("$it/scaling_max_freq", "$newMax", "${c.label} max") } else emptyList()
            if (minFirst(curMax, newMin)) {
                rangeOps += minOps; rangeOps += maxOps
            } else {
                rangeOps += maxOps; rangeOps += minOps
            }
            ops += rangeOps
            // Judged against the lower of the boot-time value and the vendor cap, so a cluster that was
            // offline at boot (no snapshot) or an already-raised snapshot cannot hide an overclock.
            if (newMax != curMax) {
                val stockMax = OcInfo.cpuEffectiveStock(c, stock.cpu[id]?.maxKhz)
                if (newMax > stockMax) above += "${c.label} max ${newMax / 1000} MHz (stock ${stockMax / 1000})" + ocSuffix(OcInfo.cpuLevel(c, newMax, stockMax))
            }
            if (gov != curGov || newMin != curMin || newMax != curMax) {
                summary += "${c.label}: ${gov ?: "?"} ${newMin / 1000}-${newMax / 1000} MHz"
            }
            afterCpu[id] = ClusterSetting(gov, newMin, newMax)
        }

        val g = hw.gpu
        val tg = target.gpu
        if (g != null && tg != null) {
            val cur = current.gpu
            val curMin = cur?.minMhz ?: g.minMhz
            val curMax = cur?.maxMhz ?: g.maxMhz
            val curGov = cur?.governor
            val table = g.freqs.map { it.mhz }
            var gov = curGov
            if (tg.governor != null) {
                if (tg.governor in g.governors && SAFE_VALUE.matches(tg.governor)) gov = tg.governor
                else warn += "GPU: governor '${tg.governor}' not available, ignored"
            }
            val newMax = tg.maxMhz?.let { snap(table, it.coerceIn(g.minMhz, g.maxMhz)) } ?: curMax
            var newMin = tg.minMhz?.let { snap(table, it.coerceIn(g.minMhz, g.maxMhz)) } ?: curMin
            if (newMin > newMax) newMin = newMax
            val limited = g.kind == GpuKind.MALI && g.limitPath != null
            fun raw(mhz: Int, isMin: Boolean): String = when (g.kind) {
                GpuKind.KGSL -> g.freqs.first { it.mhz == mhz }.raw.toString()
                GpuKind.MALI -> when {
                    isMin -> if (mhz == g.minMhz) "-1" else mhz.toString()
                    // With the OC node "700" is the stock governor ceiling, so no lock is needed there either.
                    limited -> if (mhz == g.maxMhz || mhz == OcInfo.MALI_STOCK_LIMIT) "-1" else mhz.toString()
                    else -> if (mhz == g.maxMhz) "-1" else mhz.toString()
                }
            }
            val minPath = if (g.kind == GpuKind.KGSL) "${g.path}/devfreq/min_freq" else "${g.path}/dvfs_min_lock"
            val maxPath = if (g.kind == GpuKind.KGSL) "${g.path}/devfreq/max_freq" else "${g.path}/dvfs_max_lock"
            if (gov != null && gov != curGov) {
                if (g.kind == GpuKind.KGSL) ops += WriteOp("${g.path}/devfreq/governor", gov, "GPU governor")
                else ops += WriteOp("${g.path}/dvfs_governor", g.governors.indexOf(gov).toString(), "GPU governor", Verify.MALI_GOV, gov)
            }
            val minOp = if (newMin != curMin) listOf(WriteOp(minPath, raw(newMin, true), "GPU min")) else emptyList()

            // Maximum: the plain max file, plus the kernel-side ceiling on kernels that have one.
            val raising = newMax > curMax
            val ceilOps = ArrayList<WriteOp>()
            var afterCeiling = cur?.ceilingMhz
            if (limited) {
                val want = if (newMax > OcInfo.MALI_STOCK_LIMIT) newMax else OcInfo.MALI_STOCK_LIMIT
                if (want != (cur?.ceilingMhz ?: OcInfo.MALI_STOCK_LIMIT)) {
                    ceilOps += WriteOp(g.limitPath!!, want.toString(), "GPU max limit (overclock)")
                }
                afterCeiling = want
            } else if (g.kind == GpuKind.KGSL && g.pwrlevelPath != null) {
                val idx = GpuDetector.pwrlevelTable(g).indexOf(newMax)
                if (idx >= 0 && newMax != cur?.ceilingMhz) {
                    ceilOps += WriteOp(g.pwrlevelPath, idx.toString(), "GPU highest allowed level")
                    afterCeiling = newMax
                }
            }
            val maxFileOp = if (newMax != curMax) listOf(WriteOp(maxPath, raw(newMax, false), "GPU max")) else emptyList()
            // Raise the ceiling before the max file; lower the max file before the ceiling.
            val maxOps = if (raising) ceilOps + maxFileOp else maxFileOp + ceilOps
            if (minFirst(curMax, newMin)) { ops += minOp; ops += maxOps } else { ops += maxOps; ops += minOp }
            if (newMax != curMax) {
                val stockMax = stock.gpu?.maxMhz ?: g.maxMhz
                if (newMax > stockMax) above += "GPU max $newMax MHz (stock $stockMax)" + ocSuffix(OcInfo.gpuLevel(g, newMax, stockMax))
            }
            if (gov != curGov || newMin != curMin || newMax != curMax) summary += "GPU: ${gov ?: "?"} $newMin-$newMax MHz"
            afterGpu = GpuSetting(gov, newMin, newMax, afterCeiling)
        }

        for ((id, want0) in target.temp) {
            val tgp = hw.tempGroups.firstOrNull { it.id == id }
            if (tgp == null) { warn += "Temperature limit $id is not available, ignored"; continue }
            touchedTemp += id
            val stockC = stock.temp[id] ?: tgp.detectedC
            // Trip points may only be made stricter than stock; the IPA target may go above stock (up to its range) in test mode.
            val top = if (tgp.kind == TempKind.TRIPS) minOf(tgp.maxC, stockC) else tgp.maxC
            val want = want0.coerceIn(minOf(tgp.minC, top), top)
            val now = current.temp[id]
            if (now != want) {
                for (p in tgp.paths) ops += WriteOp(p, (if (tgp.milli) want * 1000 else want).toString(), "${tgp.label} temperature limit")
                summary += "${tgp.label} temperature limit: $want °C"
                if (want > stockC) above += "${tgp.label} temperature limit $want °C (stock $stockC)"
            }
            afterTemp[id] = want
        }

        for ((dev, s) in target.io) {
            val d = hw.blocks.firstOrNull { it.name == dev }
            if (d == null || s !in d.schedulers || !SAFE_VALUE.matches(s)) {
                warn += "I/O $dev: scheduler '$s' not available, ignored"
                continue
            }
            if (current.io[dev] != s) {
                ops += WriteOp("/sys/block/$dev/queue/scheduler", s, "I/O $dev scheduler", Verify.SCHED)
                summary += "I/O $dev: $s"
            }
            afterIo[dev] = s
        }

        for ((key, v0) in target.mem) {
            val item = hw.memItems.firstOrNull { it.key == key }
            if (item == null || !item.overridable || item.path == null) {
                warn += "Memory item $key is read-only, ignored"
                continue
            }
            val v = v0.coerceIn(item.min, item.max)
            if (current.mem[key] != v) {
                ops += WriteOp(item.path, v.toString(), "Memory ${item.label}")
                summary += "${item.label} = $v (override)"
            }
            afterMem[key] = v
        }

        val after = Settings(afterCpu, afterGpu, afterIo, afterMem, afterTemp)
        val revert = if (!withRevert || ops.isEmpty()) emptyList() else {
            val restrictedCpu = target.cpu.keys.mapNotNull { k -> stock.cpu[k]?.let { k to it } }.toMap()
            val restricted = Settings(
                restrictedCpu,
                if (tg != null) stock.gpu else null,
                target.io.keys.mapNotNull { k -> stock.io[k]?.let { k to it } }.toMap(),
                target.mem.keys.mapNotNull { k -> stock.mem[k]?.let { k to it } }.toMap(),
                target.temp.keys.mapNotNull { k -> stock.temp[k]?.let { k to it } }.toMap(),
            )
            plan(hw, after, restricted, stock, withRevert = false).ops
        }
        return Plan(ops, revert, above, summary, warn, after, touchedCpu, touchedTemp)
    }

    /** Settings that differ from stock (used to decide whether anything non-stock remains active). */
    fun diffFromStock(stock: Settings, now: Settings): List<String> {
        val d = ArrayList<String>()
        for ((k, s) in stock.cpu) if (now.cpu[k] != null && now.cpu[k] != s) d += "cpu $k"
        if (stock.gpu != null && now.gpu != null && stock.gpu != now.gpu) d += "gpu"
        for ((k, s) in stock.io) if (now.io[k] != null && now.io[k] != s) d += "io $k"
        for ((k, s) in stock.mem) if (now.mem[k] != null && now.mem[k] != s) d += "mem $k"
        for ((k, s) in stock.temp) if (now.temp[k] != null && now.temp[k] != s) d += "temp $k"
        return d
    }

    fun clusterById(hw: Hardware, id: String): CpuCluster? = hw.clusters.firstOrNull { it.id == id }
}

/** One notch down in the frequency tables, for settings of an unstable profile. */
object StepDown {
    fun apply(hw: Hardware, s: Settings): Pair<Settings, List<String>> {
        val notes = ArrayList<String>()
        fun lower(table: List<Int>, v: Int): Int? = table.filter { it < v }.maxOrNull()
        val cpu = s.cpu.mapValues { (id, cs) ->
            val c = hw.clusters.firstOrNull { it.id == id } ?: return@mapValues cs
            val nMax = cs.maxKhz?.let { lower(c.freqs, it) ?: it }
            val nMin = cs.minKhz?.let { m -> val l = lower(c.freqs, m) ?: m; if (nMax != null && l > nMax) nMax else l }
            if (nMax != cs.maxKhz || nMin != cs.minKhz) notes += "${c.label}: ${nMin?.div(1000)}-${nMax?.div(1000)} MHz"
            cs.copy(minKhz = nMin, maxKhz = nMax)
        }
        var gpu = s.gpu
        val g = hw.gpu
        if (g != null && gpu != null) {
            val table = g.freqs.map { it.mhz }
            val nMax = gpu.maxMhz?.let { lower(table, it) ?: it }
            val nMin = gpu.minMhz?.let { m -> val l = lower(table, m) ?: m; if (nMax != null && l > nMax) nMax else l }
            if (nMax != gpu.maxMhz || nMin != gpu.minMhz) notes += "GPU: $nMin-$nMax MHz"
            gpu = gpu.copy(minMhz = nMin, maxMhz = nMax)
        }
        return Settings(cpu, gpu, s.io, s.mem, s.temp) to notes
    }
}
