package app.kerneltuner.core

import app.kerneltuner.hw.Hardware
import app.kerneltuner.hw.TempKind

enum class Preset(val title: String, val blurb: String) {
    BATTERY("Battery saver", "Lower top speeds and a cooler limit. Longest battery, slower in heavy apps."),
    BALANCED("Balanced (stock)", "Exactly what the phone had at boot."),
    PERFORMANCE("Performance", "Full stock top speed with a higher idle floor. No overclocking, no hotter limit."),
}

/**
 * Presets only ever use values inside the stock range: nothing above the boot-time maximum or the
 * vendor cap, and no temperature limit above stock. They fill the Tune controls; nothing is applied until the user presses a button.
 */
object Presets {
    /** Smallest table entry at or above [frac] of [top], never above [top]. */
    private fun step(table: List<Int>, top: Int, frac: Double): Int {
        val inRange = table.filter { it <= top }.sorted()
        if (inRange.isEmpty()) return top
        val want = top * frac
        return inRange.firstOrNull { it >= want } ?: inRange.last()
    }

    fun build(hw: Hardware, stock: Settings, preset: Preset): Settings {
        if (preset == Preset.BALANCED) {
            return Settings(
                cpu = stock.cpu.filterKeys { k -> hw.clusters.any { it.id == k } },
                gpu = stock.gpu,
                temp = stock.temp,
            )
        }
        val battery = preset == Preset.BATTERY
        val cpu = HashMap<String, ClusterSetting>()
        for (c in hw.clusters) {
            val s = stock.cpu[c.id]
            val top = OcInfo.cpuEffectiveStock(c, s?.maxKhz)
            val stockMin = s?.minKhz ?: c.hwMinKhz
            val max = if (battery) step(c.freqs, top, 0.6) else top
            val min = if (battery) stockMin.coerceAtMost(max) else step(c.freqs, top, 0.5).coerceAtLeast(stockMin).coerceAtMost(max)
            cpu[c.id] = ClusterSetting(s?.governor, min, max)
        }
        val gpu = hw.gpu?.let { g ->
            val s = stock.gpu
            val table = g.freqs.map { it.mhz }
            val top = s?.maxMhz ?: OcInfo.gpuStockCap(g) ?: g.maxMhz
            val stockMin = s?.minMhz ?: g.minMhz
            val max = if (battery) step(table, top, 0.6) else top
            val min = if (battery) stockMin.coerceAtMost(max) else step(table, top, 0.5).coerceAtLeast(stockMin).coerceAtMost(max)
            GpuSetting(s?.governor, min, max)
        }
        val temp = HashMap<String, Int>()
        for (g in hw.tempGroups) {
            val stockC = stock.temp[g.id] ?: g.detectedC
            val lower = when (g.kind) {
                TempKind.IPA -> stockC - 10
                TempKind.TRIPS -> stockC - 20
            }
            temp[g.id] = if (battery) lower.coerceIn(g.minC, stockC) else stockC
        }
        return Settings(cpu, gpu, emptyMap(), emptyMap(), temp)
    }
}
