package app.kerneltuner.hw

data class MemInfo(val totalKb: Long, val availKb: Long, val swapTotalKb: Long, val swapFreeKb: Long) {
    val usedKb get() = totalKb - availKb
    val swapUsedKb get() = swapTotalKb - swapFreeKb
}

object Memory {
    val items: List<MemItem> = listOf(
        MemItem("swappiness", "vm.swappiness", "${Paths.VM}/swappiness", null, true, 0, 200),
        MemItem("dirty_ratio", "vm.dirty_ratio", "${Paths.VM}/dirty_ratio", null, true, 1, 100),
        MemItem("dirty_background_ratio", "vm.dirty_background_ratio", "${Paths.VM}/dirty_background_ratio", null, true, 0, 100),
        MemItem("vfs_cache_pressure", "vm.vfs_cache_pressure", "${Paths.VM}/vfs_cache_pressure", null, true, 1, 1000),
        // Read-only forever: changing zram needs swapoff/reset and lmkd reads props at start.
        MemItem("zram_disksize", "zram0 disksize (bytes)", "/sys/block/zram0/disksize", null, false),
        MemItem("zram_algo", "zram0 compression", "/sys/block/zram0/comp_algorithm", null, false),
        MemItem("lmk_minfree", "sys.lmk.minfree_levels", null, "sys.lmk.minfree_levels", false),
        MemItem("lmk_psi", "ro.lmk.use_psi", null, "ro.lmk.use_psi", false),
        MemItem("lmk_heaviest", "ro.lmk.kill_heaviest_task", null, "ro.lmk.kill_heaviest_task", false),
        MemItem("lmk_use_minfree", "ro.lmk.use_minfree_levels", null, "ro.lmk.use_minfree_levels", false),
    )

    /** Items whose backing file exists, or props (resolved lazily by the caller). */
    fun available(fs: SysFs): List<MemItem> = items.filter { it.prop != null || (it.path != null && fs.exists(it.path)) }

    fun parseMeminfo(text: String?): MemInfo? {
        if (text == null) return null
        val m = HashMap<String, Long>()
        for (l in text.lines()) {
            val p = l.split(Regex("[:\\s]+")).filter { it.isNotEmpty() }
            if (p.size >= 2) p[1].toLongOrNull()?.let { m[p[0]] = it }
        }
        val total = m["MemTotal"] ?: return null
        return MemInfo(total, m["MemAvailable"] ?: m["MemFree"] ?: 0, m["SwapTotal"] ?: 0, m["SwapFree"] ?: 0)
    }

    /** zram mm_stat: orig_data_size compr_data_size mem_used_total ... (bytes). */
    fun parseZramRatio(mmStat: String?): Pair<Long, Long>? {
        val v = mmStat.longs()
        return if (v.size >= 2) v[0] to v[1] else null
    }
}
