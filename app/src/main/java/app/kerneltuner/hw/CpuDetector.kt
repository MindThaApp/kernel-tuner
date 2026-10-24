package app.kerneltuner.hw

object CpuDetector {
    private val POLICY = Regex("^policy\\d+$")
    private val CPUN = Regex("^cpu(\\d+)$")

    fun cpuCount(fs: SysFs): Int =
        fs.list(Paths.CPU).count { CPUN.matches(it) }

    fun detect(fs: SysFs, cached: List<CpuCluster> = emptyList()): List<CpuCluster> =
        mergeCached(fs, detectLive(fs), cached)

    /**
     * A cluster whose cores are all hotplugged off has no cpufreq files, and a partly online one lists only
     * its online members. Fill both from an earlier detection. The live frequency table always wins.
     */
    fun mergeCached(fs: SysFs, live: List<CpuCluster>, cached: List<CpuCluster>): List<CpuCluster> {
        if (cached.isEmpty()) return live
        val present = fs.list(Paths.CPU).mapNotNull { CPUN.matchEntire(it)?.groupValues?.get(1)?.toInt() }.toSet()
        val out = ArrayList<CpuCluster>()
        val used = HashSet<CpuCluster>()
        for (c in live) {
            val hit = cached.firstOrNull { k -> k.layout == CpuLayout.PER_CPU && c.layout == CpuLayout.PER_CPU && k.cpus.any { it in c.cpus } }
            if (hit == null) { out += c; continue }
            used += hit
            val cpus = (c.cpus + hit.cpus.filter { it in present }).distinct().sorted()
            out += c.copy(id = "cpu${cpus.first()}", cpus = cpus, paths = cpus.map { "${Paths.CPU}/cpu$it/cpufreq" })
        }
        for (k in cached) {
            if (k in used || k.layout != CpuLayout.PER_CPU) continue
            if (out.any { o -> o.cpus.any { it in k.cpus } }) continue
            if (k.cpus.none { it in present }) continue
            out += k
        }
        return out.sortedBy { it.cpus.first() }
    }

    private fun detectLive(fs: SysFs): List<CpuCluster> {
        val policies = fs.list(Paths.CPUFREQ).filter { POLICY.matches(it) }
            .sortedBy { it.removePrefix("policy").toInt() }
        val out = ArrayList<CpuCluster>()
        if (policies.isNotEmpty()) {
            for (p in policies) {
                val dir = "${Paths.CPUFREQ}/$p"
                val cpus = (fs.read("$dir/related_cpus") ?: fs.read("$dir/affected_cpus")).ints().sorted()
                    .ifEmpty { listOf(p.removePrefix("policy").toInt()) }
                build(fs, cpus, CpuLayout.POLICY, listOf(dir))?.let { out += it }
            }
            return out
        }
        // Legacy layout: cpuN/cpufreq, group CPUs that share a policy via related_cpus.
        val groups = LinkedHashMap<List<Int>, MutableList<String>>()
        val cpuIds = fs.list(Paths.CPU).mapNotNull { CPUN.matchEntire(it)?.groupValues?.get(1)?.toInt() }.sorted()
        for (n in cpuIds) {
            val dir = "${Paths.CPU}/cpu$n/cpufreq"
            if (!fs.exists(dir)) continue
            val rel = (fs.read("$dir/related_cpus") ?: fs.read("$dir/affected_cpus")).ints().sorted().ifEmpty { listOf(n) }
            groups.getOrPut(rel) { ArrayList() }.add(dir)
        }
        for ((cpus, dirs) in groups) build(fs, cpus, CpuLayout.PER_CPU, dirs)?.let {
            // Write to every member, including cores that are offline right now (skipped while offline, re-applied later).
            val all = cpus.filter { n -> n in cpuIds }.map { "${Paths.CPU}/cpu$it/cpufreq" }
            out += it.copy(paths = (all + dirs).distinct())
        }
        return out.sortedBy { it.cpus.first() }
    }

    private fun build(fs: SysFs, cpus: List<Int>, layout: CpuLayout, dirs: List<String>): CpuCluster? {
        val base = dirs.first()
        val infoMin = fs.read("$base/cpuinfo_min_freq").firstInt()
        val infoMax = fs.read("$base/cpuinfo_max_freq").firstInt()
        val freqs = frequencies(fs, base, infoMin, infoMax)
        if (freqs.isEmpty()) return null
        val govs = (fs.read("$base/scaling_available_governors") ?: "").split(Regex("\\s+")).filter { it.isNotBlank() }
        return CpuCluster(
            id = "cpu${cpus.first()}",
            cpus = cpus,
            layout = layout,
            paths = dirs,
            freqs = freqs,
            governors = govs,
            hwMinKhz = infoMin ?: freqs.first(),
            hwMaxKhz = infoMax ?: freqs.last(),
        )
    }

    /** scaling_available_frequencies, else stats/time_in_state, else cpuinfo min/max. Ascending, distinct. */
    fun frequencies(fs: SysFs, base: String, infoMin: Int?, infoMax: Int?): List<Int> {
        val avail = fs.read("$base/scaling_available_frequencies").ints()
        if (avail.isNotEmpty()) return avail.filter { it > 0 }.distinct().sorted()
        val tis = fs.read("$base/stats/time_in_state")
        if (tis != null) {
            val f = tis.lines().mapNotNull { it.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull() }.filter { it > 0 }
            if (f.isNotEmpty()) return f.distinct().sorted()
        }
        return listOfNotNull(infoMin, infoMax).filter { it > 0 }.distinct().sorted()
    }
}
