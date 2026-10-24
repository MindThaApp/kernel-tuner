package app.kerneltuner.hw

object GpuDetector {
    fun detect(fs: SysFs): GpuInfo? = detectKgsl(fs) ?: detectMali(fs)

    private fun detectKgsl(fs: SysFs): GpuInfo? {
        val p = Paths.KGSL
        if (!fs.exists("$p/devfreq")) return null
        // Union of both lists: on an overclocked kernel either may be the one that lists the new top levels.
        val hz = (fs.read("$p/devfreq/available_frequencies").longs() + fs.read("$p/gpu_available_frequencies").longs())
            .filter { it >= 1_000_000L }.distinct().sorted()
        if (hz.isEmpty()) return null
        val govs = (fs.read("$p/devfreq/available_governors") ?: "").split(Regex("\\s+")).filter { it.isNotBlank() }
        val pl = "$p/max_pwrlevel".takeIf { fs.read(it).firstInt() != null }
        return GpuInfo(GpuKind.KGSL, p, hz.map { GpuFreq(Math.round(it / 1e6).toInt(), it) }.distinctBy { it.mhz }, govs, fs.read("$p/gpu_model"), pwrlevelPath = pl)
    }

    fun detectMali(fs: SysFs): GpuInfo? {
        val dirs = fs.list(Paths.DEVICES).filter { it.endsWith(".mali") }
        for (d in dirs) {
            val p = "${Paths.DEVICES}/$d"
            val table = parseMaliTable(fs.read("$p/dvfs_table"))
            if (table.isEmpty()) continue
            val (govs, _) = parseMaliGovernors(fs.read("$p/dvfs_governor"))
            // OC kernel: a root-only node raises the governor ceiling. The dvfs_table does not list the new top step.
            val limit = WriteWhitelistPaths.GPU_LIMIT.takeIf { fs.exists(it) }
            val full = if (limit != null) (table + MaliOcSteps.MALI).distinct().sorted() else table
            return GpuInfo(GpuKind.MALI, p, full.map { GpuFreq(it, it.toLong()) }, govs, fs.read("$p/gpuinfo"), limitPath = limit)
        }
        return null
    }

    /** dvfs_table is a whitespace separated MHz list, usually descending. */
    fun parseMaliTable(s: String?): List<Int> = s.ints().filter { it > 0 }.distinct().sorted()

    /** Lines before "[Current Governor] X" are the selectable governors (index = line number). */
    fun parseMaliGovernors(s: String?): Pair<List<String>, String?> {
        if (s == null) return emptyList<String>() to null
        val names = ArrayList<String>()
        var current: String? = null
        for (raw in s.lines()) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            if (l.startsWith("[Current Governor]")) current = l.removePrefix("[Current Governor]").trim() else names += l
        }
        return names to current
    }

    /** Descending MHz; position = kgsl pwrlevel index (0 = fastest). */
    fun pwrlevelTable(g: GpuInfo): List<Int> = g.freqs.map { it.mhz }.sortedDescending()

    fun readState(fs: SysFs, g: GpuInfo): GpuState {
        val p = g.path
        return when (g.kind) {
            GpuKind.KGSL -> {
                val minHz = fs.read("$p/devfreq/min_freq")?.trim()?.toLongOrNull()
                val maxHz = fs.read("$p/devfreq/max_freq")?.trim()?.toLongOrNull()
                val cur = (fs.read("$p/gpuclk") ?: fs.read("$p/devfreq/cur_freq"))?.trim()?.toLongOrNull()
                // max_pwrlevel is a hard ceiling on top of devfreq max_freq; the effective maximum is the lower one.
                val ceiling = g.pwrlevelPath?.let { fs.read(it).firstInt() }?.let { pwrlevelTable(g).getOrNull(it) }
                val devMax = maxHz?.let { nearestMhz(g, it / 1e6) } ?: g.maxMhz
                GpuState(
                    governor = fs.read("$p/devfreq/governor"),
                    minMhz = minHz?.let { nearestMhz(g, it / 1e6) } ?: g.minMhz,
                    maxMhz = if (ceiling != null) minOf(devMax, ceiling) else devMax,
                    ceilingMhz = ceiling,
                    curMhz = cur?.let { Math.round(it / 1e6).toInt() },
                    loadPct = fs.read("$p/gpu_busy_percentage").firstInt(),
                )
            }
            GpuKind.MALI -> {
                val minL = fs.read("$p/dvfs_min_lock").firstInt()
                val maxL = fs.read("$p/dvfs_max_lock").firstInt()
                val limit = g.limitPath?.let { fs.read(it).firstInt() }
                val lockTop = if (maxL == null || maxL <= 0) g.maxMhz else maxL
                GpuState(
                    governor = parseMaliGovernors(fs.read("$p/dvfs_governor")).second,
                    minMhz = if (minL == null || minL <= 0) g.minMhz else minL,
                    maxMhz = if (limit != null) minOf(lockTop, limit) else lockTop,
                    ceilingMhz = limit,
                    curMhz = fs.read("$p/clock").firstInt(),
                    loadPct = fs.read("$p/utilization").firstInt(),
                )
            }
        }
    }

    private fun nearestMhz(g: GpuInfo, mhz: Double): Int = g.freqs.minByOrNull { Math.abs(it.mhz - mhz) }!!.mhz
}

data class GpuState(val governor: String?, val minMhz: Int, val maxMhz: Int, val curMhz: Int?, val loadPct: Int?, val ceilingMhz: Int? = null)

internal fun String?.longs(): List<Long> =
    this?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() } ?: emptyList()

internal object WriteWhitelistPaths {
    const val GPU_LIMIT = "/sys/kernel/gpu/gpu_max_clock_limit"
}

internal object MaliOcSteps {
    val MALI = listOf(852)
}
