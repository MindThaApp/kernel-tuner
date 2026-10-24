package app.kerneltuner.core

import app.kerneltuner.hw.CpuCluster
import app.kerneltuner.hw.GpuInfo
import app.kerneltuner.hw.GpuKind

enum class OcLevel { STOCK, OVERCLOCK, UNSTABLE }

/**
 * Knowledge about which speed steps are above what the vendor shipped, and which of those the kernel
 * authors flagged as likely unstable. Matched on the shape of the frequency table, never on a device name,
 * so an unknown device simply has no labels (and falls back to the boot-time maximum).
 */
object OcInfo {
    /** Samsung Mali OC kernel: governor ceiling that means "stock". */
    const val MALI_STOCK_LIMIT = 700
    val MALI_LIMIT_VALUES = listOf(700, 772, 852)
    /** The only extra Mali step the OC kernel adds. */
    val MALI_OC_STEPS = listOf(852)

    private data class Rule(val stockCapKhz: Int, val unstable: (Int) -> Boolean)

    private fun rule(c: CpuCluster): Rule? {
        val f = c.freqs
        val top = c.hwMaxKhz
        return when {
            // Exynos big cluster: stock 2.1 GHz, OC table to 2.496 GHz; 2.4 sits at the voltage ceiling.
            2100000 in f && top in 2200000..2500000 -> Rule(2100000) { it >= 2400000 }
            // Exynos little cluster: stock 1.5 GHz, OC table to 2.0 GHz.
            1500000 in f && top in 1600000..2000000 && c.hwMinKhz <= 500000 -> Rule(1500000) { it in 1800000..2000000 }
            // Snapdragon 625: stock 2.016 GHz, OC steps to 2.208 GHz.
            2016000 in f && 652800 in f -> Rule(2016000) { it == 2150400 || it == 2208000 }
            else -> null
        }
    }

    /** Highest speed the vendor kernel allowed by default, or null if this table is not recognised. */
    fun cpuStockCap(c: CpuCluster): Int? = rule(c)?.stockCapKhz

    fun cpuLevel(c: CpuCluster, khz: Int, stockMaxKhz: Int? = null): OcLevel {
        val r = rule(c)
        val cap = r?.stockCapKhz ?: stockMaxKhz ?: return OcLevel.STOCK
        if (khz <= cap) return OcLevel.STOCK
        return if (r != null && r.unstable(khz)) OcLevel.UNSTABLE else OcLevel.OVERCLOCK
    }

    fun gpuStockCap(g: GpuInfo): Int? = when {
        g.kind == GpuKind.MALI && g.limitPath != null -> 772
        g.kind == GpuKind.KGSL && g.pwrlevelPath != null && 650 in g.freqs.map { it.mhz } && g.maxMhz > 650 -> 650
        else -> null
    }

    fun gpuLevel(g: GpuInfo, mhz: Int, stockMaxMhz: Int? = null): OcLevel {
        val cap = gpuStockCap(g) ?: stockMaxMhz ?: return OcLevel.STOCK
        return if (mhz > cap) OcLevel.OVERCLOCK else OcLevel.STOCK
    }

    /** Effective stock ceiling for a cluster: the lower of the boot-time value and the known vendor cap. */
    fun cpuEffectiveStock(c: CpuCluster, bootMaxKhz: Int?): Int =
        listOfNotNull(bootMaxKhz, cpuStockCap(c)).minOrNull() ?: c.hwMaxKhz

    fun label(l: OcLevel): String? = when (l) {
        OcLevel.STOCK -> null
        OcLevel.OVERCLOCK -> "Overclock"
        OcLevel.UNSTABLE -> "Overclock - likely unstable"
    }
}
