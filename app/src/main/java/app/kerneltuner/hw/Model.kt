package app.kerneltuner.hw

import kotlinx.serialization.Serializable

enum class CpuLayout { POLICY, PER_CPU }

data class CpuCluster(
    /** Stable key, "cpu<firstCpu>". */
    val id: String,
    val cpus: List<Int>,
    val layout: CpuLayout,
    /** Directories whose scaling_* files must all be written (one for POLICY, every online member for PER_CPU). */
    val paths: List<String>,
    /** Ascending kHz. */
    val freqs: List<Int>,
    val governors: List<String>,
    val hwMinKhz: Int,
    val hwMaxKhz: Int,
) {
    val label: String get() = if (cpus.size == 1) "CPU ${cpus[0]}" else "CPU ${cpus.first()}-${cpus.last()}"
    val readPath: String get() = paths.first()
}

enum class GpuKind { KGSL, MALI }

data class GpuFreq(val mhz: Int, val raw: Long)

data class GpuInfo(
    val kind: GpuKind,
    val path: String,
    /** Ascending. */
    val freqs: List<GpuFreq>,
    val governors: List<String>,
    val model: String?,
) {
    val minMhz get() = freqs.first().mhz
    val maxMhz get() = freqs.last().mhz
}

enum class ThermalCategory { CPU, GPU, BATTERY, OTHER }

data class ThermalZone(val path: String, val type: String, val category: ThermalCategory)

data class BlockDevice(val name: String, val schedulers: List<String>)

data class MemItem(
    val key: String,
    val label: String,
    /** sysfs/procfs file, or null when [prop] is used. */
    val path: String?,
    val prop: String?,
    /** Only true for plain sysctls we can safely write; never for zram/lmk. */
    val overridable: Boolean,
    val min: Long = 0,
    val max: Long = 0,
)

data class Hardware(
    val clusters: List<CpuCluster>,
    val gpu: GpuInfo?,
    val thermal: List<ThermalZone>,
    val blocks: List<BlockDevice>,
    val memItems: List<MemItem>,
    val cpuCount: Int,
)

@Serializable
data class WriteOp(
    val path: String,
    val value: String,
    val label: String,
    val verify: Verify = Verify.EXACT,
    /** For MALI_GOV: the governor name expected on read-back. */
    val expect: String? = null,
)

@Serializable
enum class Verify { EXACT, SCHED, MALI_GOV, NONE }
