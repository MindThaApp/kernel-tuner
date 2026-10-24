package app.kerneltuner.sys

import android.content.Context
import app.kerneltuner.core.ClusterSetting
import app.kerneltuner.core.Settings
import app.kerneltuner.hw.Hardware
import app.kerneltuner.hw.HardwareDetector
import app.kerneltuner.hw.SysFs

/** Hardware detection that remembers CPU clusters across runs, so a hotplugged-off big cluster does not disappear. */
object HwProbe {
    fun detect(ctx: Context, fs: SysFs, forget: Boolean = false): Hardware {
        val store = Store(ctx)
        val hw = HardwareDetector.detect(fs, if (forget) emptyList() else store.clusterCache)
        if (hw.clusters.isNotEmpty()) store.clusterCache = hw.clusters
        return hw
    }

    /**
     * Fills clusters missing from a boot snapshot (offline at boot) from the remembered stock values, and
     * remembers what this boot's snapshot says. Never overwrites a value that was actually read.
     */
    fun withStockCache(ctx: Context, snap: Settings): Settings {
        val store = Store(ctx)
        val cache = store.stockCpuCache
        val merged = cache + snap.cpu
        if (merged != cache) store.stockCpuCache = merged
        return if (merged.size == snap.cpu.size) snap else snap.copy(cpu = merged)
    }

    /** A cluster that was offline at boot shows up later with default limits: remember it as stock unless we changed it. */
    fun learnLateClusters(ctx: Context, stock: Settings, current: Settings, held: Settings?): Settings {
        val late = current.cpu.filterKeys { it !in stock.cpu && held?.cpu?.containsKey(it) != true }
        if (late.isEmpty()) return stock
        val store = Store(ctx)
        store.stockCpuCache = store.stockCpuCache + late
        return stock.copy(cpu = stock.cpu + late)
    }
}
