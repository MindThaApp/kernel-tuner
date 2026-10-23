package app.kerneltuner.core

import app.kerneltuner.hw.GpuDetector
import app.kerneltuner.hw.BlockDetector
import app.kerneltuner.hw.Hardware
import app.kerneltuner.hw.SysFs
import app.kerneltuner.hw.firstInt

object Snapshot {
    /** Reads every controllable value currently set in the kernel. */
    fun read(fs: SysFs, hw: Hardware): Settings {
        val cpu = hw.clusters.associate { c ->
            val p = c.readPath
            c.id to ClusterSetting(
                governor = fs.read("$p/scaling_governor"),
                minKhz = fs.read("$p/scaling_min_freq").firstInt() ?: c.hwMinKhz,
                maxKhz = fs.read("$p/scaling_max_freq").firstInt() ?: c.hwMaxKhz,
            )
        }
        val gpu = hw.gpu?.let { g ->
            val s = GpuDetector.readState(fs, g)
            GpuSetting(s.governor, s.minMhz, s.maxMhz)
        }
        val io = hw.blocks.mapNotNull { b ->
            BlockDetector.parseScheduler(fs.read("/sys/block/${b.name}/queue/scheduler")).second?.let { b.name to it }
        }.toMap()
        val mem = hw.memItems.filter { it.overridable && it.path != null }.mapNotNull { m ->
            fs.read(m.path!!)?.trim()?.toLongOrNull()?.let { m.key to it }
        }.toMap()
        return Settings(cpu, gpu, io, mem)
    }
}
