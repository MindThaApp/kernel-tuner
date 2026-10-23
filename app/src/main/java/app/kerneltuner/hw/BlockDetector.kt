package app.kerneltuner.hw

object BlockDetector {
    private val REAL = Regex("^(sd[a-z]+|mmcblk\\d+|nvme\\d+n\\d+|vd[a-z]+)$")

    fun detect(fs: SysFs): List<BlockDevice> = fs.list(Paths.BLOCK).filter { REAL.matches(it) }.mapNotNull { n ->
        val (list, _) = parseScheduler(fs.read("${Paths.BLOCK}/$n/queue/scheduler"))
        if (list.size < 2) null else BlockDevice(n, list)
    }

    /** "noop [cfq] " -> ([noop, cfq], cfq). */
    fun parseScheduler(s: String?): Pair<List<String>, String?> {
        if (s == null) return emptyList<String>() to null
        val toks = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val cur = toks.firstOrNull { it.startsWith("[") && it.endsWith("]") }?.trim('[', ']')
        return toks.map { it.trim('[', ']') } to cur
    }
}
