package app.kerneltuner.hw

import kotlinx.serialization.Serializable

@Serializable
enum class CpuLayout { POLICY, PER_CPU }

@Serializable
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

/** First readable value for [file] across the member directories (some cores may be offline). */
fun CpuCluster.readFirst(fs: SysFs, file: String): String? {
    for (p in paths) fs.read("$p/$file")?.let { return it }
    return null
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
    /** Samsung OC kernel: root-only node that sets the governor ceiling (700 = stock, 772, 852). */
    val limitPath: String? = null,
    /** Adreno: kgsl max_pwrlevel (0 = fastest level). Lists every level, so index = position in the descending table. */
    val pwrlevelPath: String? = null,
) {
    val minMhz get() = freqs.first().mhz
    val maxMhz get() = freqs.last().mhz
}

/** What a temperature-limit slider controls. */
enum class TempKind { IPA, TRIPS }

/**
 * One temperature-limit control. IPA: a single node holding the power-budget target in whole degrees.
 * TRIPS: the kernel's step_wise trip point 0 of several thermal zones, in millidegrees; only ever lowered.
 */
data class TempGroup(
    val id: String,
    val label: String,
    val kind: TempKind,
    val paths: List<String>,
    val milli: Boolean,
    /** Slider range in degrees C. maxC is also the hard ceiling the planner and the write whitelist enforce. */
    val minC: Int,
    val maxC: Int,
    /** Value the kernel had when detected (used only when no boot snapshot exists). */
    val detectedC: Int,
)

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
    val tempGroups: List<TempGroup> = emptyList(),
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
