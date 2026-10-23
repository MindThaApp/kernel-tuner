package app.kerneltuner.hw

object GpuDetector {
    fun detect(fs: SysFs): GpuInfo? = detectKgsl(fs) ?: detectMali(fs)

    private fun detectKgsl(fs: SysFs): GpuInfo? {
        val p = Paths.KGSL
        if (!fs.exists("$p/devfreq")) return null
        val hz = (fs.read("$p/devfreq/available_frequencies") ?: fs.read("$p/gpu_available_frequencies"))
            .longs().filter { it > 0 }.distinct().sorted()
        if (hz.isEmpty()) return null
        val govs = (fs.read("$p/devfreq/available_governors") ?: "").split(Regex("\\s+")).filter { it.isNotBlank() }
        return GpuInfo(GpuKind.KGSL, p, hz.map { GpuFreq(Math.round(it / 1e6).toInt(), it) }, govs, fs.read("$p/gpu_model"))
    }

    fun detectMali(fs: SysFs): GpuInfo? {
        val dirs = fs.list(Paths.DEVICES).filter { it.endsWith(".mali") }
        for (d in dirs) {
            val p = "${Paths.DEVICES}/$d"
            val table = parseMaliTable(fs.read("$p/dvfs_table"))
            if (table.isEmpty()) continue
            val (govs, _) = parseMaliGovernors(fs.read("$p/dvfs_governor"))
            return GpuInfo(GpuKind.MALI, p, table.map { GpuFreq(it, it.toLong()) }, govs, fs.read("$p/gpuinfo"))
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

    fun readState(fs: SysFs, g: GpuInfo): GpuState {
        val p = g.path
        return when (g.kind) {
            GpuKind.KGSL -> {
                val minHz = fs.read("$p/devfreq/min_freq")?.trim()?.toLongOrNull()
                val maxHz = fs.read("$p/devfreq/max_freq")?.trim()?.toLongOrNull()
                val cur = (fs.read("$p/gpuclk") ?: fs.read("$p/devfreq/cur_freq"))?.trim()?.toLongOrNull()
                GpuState(
                    governor = fs.read("$p/devfreq/governor"),
                    minMhz = minHz?.let { nearestMhz(g, it / 1e6) } ?: g.minMhz,
                    maxMhz = maxHz?.let { nearestMhz(g, it / 1e6) } ?: g.maxMhz,
                    curMhz = cur?.let { Math.round(it / 1e6).toInt() },
                    loadPct = fs.read("$p/gpu_busy_percentage").firstInt(),
                )
            }
            GpuKind.MALI -> {
                val minL = fs.read("$p/dvfs_min_lock").firstInt()
                val maxL = fs.read("$p/dvfs_max_lock").firstInt()
                GpuState(
                    governor = parseMaliGovernors(fs.read("$p/dvfs_governor")).second,
                    minMhz = if (minL == null || minL <= 0) g.minMhz else minL,
                    maxMhz = if (maxL == null || maxL <= 0) g.maxMhz else maxL,
                    curMhz = fs.read("$p/clock").firstInt(),
                    loadPct = fs.read("$p/utilization").firstInt(),
                )
            }
        }
    }

    private fun nearestMhz(g: GpuInfo, mhz: Double): Int = g.freqs.minByOrNull { Math.abs(it.mhz - mhz) }!!.mhz
}

data class GpuState(val governor: String?, val minMhz: Int, val maxMhz: Int, val curMhz: Int?, val loadPct: Int?)

internal fun String?.longs(): List<Long> =
    this?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() } ?: emptyList()
