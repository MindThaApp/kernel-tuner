package app.kerneltuner.core

import app.kerneltuner.hw.GpuDetector
import app.kerneltuner.hw.BlockDetector
import app.kerneltuner.hw.Hardware
import app.kerneltuner.hw.SysFs
import app.kerneltuner.hw.TempLimitDetector
import app.kerneltuner.hw.firstInt
import app.kerneltuner.hw.readFirst

object Snapshot {
    /** Reads every controllable value currently set in the kernel. */
    fun read(fs: SysFs, hw: Hardware): Settings {
        // A cluster whose cores are all offline has no readable files: leave it out instead of inventing values.
        val cpu = hw.clusters.mapNotNull { c ->
            val max = c.readFirst(fs, "scaling_max_freq").firstInt() ?: return@mapNotNull null
            c.id to ClusterSetting(
                governor = c.readFirst(fs, "scaling_governor"),
                minKhz = c.readFirst(fs, "scaling_min_freq").firstInt() ?: c.hwMinKhz,
                maxKhz = max,
            )
        }.toMap()
        val gpu = hw.gpu?.let { g ->
            val s = GpuDetector.readState(fs, g)
            GpuSetting(s.governor, s.minMhz, s.maxMhz, s.ceilingMhz)
        }
        val io = hw.blocks.mapNotNull { b ->
            BlockDetector.parseScheduler(fs.read("/sys/block/${b.name}/queue/scheduler")).second?.let { b.name to it }
        }.toMap()
        val mem = hw.memItems.filter { it.overridable && it.path != null }.mapNotNull { m ->
            fs.read(m.path!!)?.trim()?.toLongOrNull()?.let { m.key to it }
        }.toMap()
        val temp = hw.tempGroups.mapNotNull { g -> TempLimitDetector.read(fs, g)?.let { g.id to it } }.toMap()
        return Settings(cpu, gpu, io, mem, temp)
    }
}
