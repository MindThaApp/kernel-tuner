package app.kerneltuner.hw

import app.kerneltuner.core.ThermalConf

/**
 * Finds the temperature-limit controls this kernel offers:
 *  - Samsung IPA: /sys/power/ipa/control_temp (whole degrees, PI-controller target for CPU+GPU power).
 *  - Qualcomm step_wise zones: trip_point_0_temp of the CPU core zones and the GPU zone (millidegrees).
 * Trip points are only ever offered for lowering: the slider's top is the stock trip (CPU 105, GPU 95).
 */
object TempLimitDetector {
    const val IPA_NODE = "/sys/power/ipa/control_temp"
    const val IPA_ID = "ipa"
    const val CPU_TRIP_ID = "cpu_trip"
    const val GPU_TRIP_ID = "gpu_trip"

    const val IPA_MIN = 45
    const val IPA_MAX = 120
    const val CPU_TRIP_MIN = 70
    const val CPU_TRIP_STOCK = 105
    const val GPU_TRIP_MIN = 65
    const val GPU_TRIP_STOCK = 95

    const val TMU_ID_PREFIX = "tmu"
    const val TE_ID = "te_cluster0"
    const val TMU_ZONE = "/sys/class/thermal/thermal_zone0"
    /** Stock Exynos 7420 TMU trips; the updated kernel allows stock-20 .. stock+10 and ascending order. */
    val TMU_STOCK = listOf(75, 80, 85, 90, 95)
    const val TMU_DOWN = 20
    const val TMU_UP = 10
    /** Magisk module overlay for the Moto thermal-engine rules. */
    const val TE_MODULE = "/data/adb/modules/sanders_oc_thermal_relax"
    const val TE_CONF = "$TE_MODULE/system/vendor/etc/thermal-engine.conf"
    const val TE_BACKUP = "$TE_MODULE/thermal-engine.conf.kt-orig"

    private val ZONE = Regex("^thermal_zone(\\d+)$")
    private val CPU_STEP = Regex("(?i)^(apc\\d*-cpu\\d+|cpu\\d*|apc\\d*)(-|_)step$")
    private val GPU_STEP = Regex("(?i)^gpu\\d*(-|_)step$")

    fun detect(fs: SysFs): List<TempGroup> {
        val out = ArrayList<TempGroup>()
        fs.read(IPA_NODE).firstInt()?.takeIf { it in 20..150 }?.let {
            out += TempGroup(IPA_ID, "Processor and graphics", TempKind.IPA, listOf(IPA_NODE), false, IPA_MIN, IPA_MAX, it)
        }
        val cpu = ArrayList<String>()
        val gpu = ArrayList<String>()
        var cpuC = 0
        var gpuC = 0
        for (n in fs.list(Paths.THERMAL).mapNotNull { ZONE.matchEntire(it)?.groupValues?.get(1)?.toInt() }.sorted()) {
            val dir = "${Paths.THERMAL}/thermal_zone$n"
            val type = fs.read("$dir/type") ?: continue
            val isCpu = CPU_STEP.containsMatchIn(type)
            val isGpu = GPU_STEP.containsMatchIn(type)
            if (!isCpu && !isGpu) continue
            if (fs.read("$dir/policy") != "step_wise") continue
            val trip = fs.read("$dir/trip_point_0_temp").firstInt() ?: continue
            val c = trip / 1000
            // Accept anything inside the slider range (a limit we lowered earlier must stay visible), nothing else.
            if (isCpu && c in CPU_TRIP_MIN..CPU_TRIP_STOCK) { cpu += "$dir/trip_point_0_temp"; cpuC = maxOf(cpuC, c) }
            if (isGpu && c in GPU_TRIP_MIN..GPU_TRIP_STOCK) { gpu += "$dir/trip_point_0_temp"; gpuC = maxOf(gpuC, c) }
        }
        if (cpu.isNotEmpty()) out += TempGroup(CPU_TRIP_ID, "Processor", TempKind.TRIPS, cpu, true, CPU_TRIP_MIN, CPU_TRIP_STOCK, cpuC)
        if (gpu.isNotEmpty()) out += TempGroup(GPU_TRIP_ID, "Graphics", TempKind.TRIPS, gpu, true, GPU_TRIP_MIN, GPU_TRIP_STOCK, gpuC)
        out += detectTmu(fs)
        detectThermalEngine(fs)?.let { out += it }
        return out
    }

    /** Samsung hardware throttle trips: five nodes, each shown even when read-only so the UI can say why. */
    fun detectTmu(fs: SysFs): List<TempGroup> {
        if (fs.read("$TMU_ZONE/type") != "exynos-therm") return emptyList()
        val groups = ArrayList<TempGroup>()
        for ((i, stock) in TMU_STOCK.withIndex()) {
            val path = "$TMU_ZONE/trip_point_${i}_temp"
            val c = fs.read(path).firstInt()?.let { Math.round(it / 1000.0).toInt() } ?: return emptyList()
            if (c !in (stock - TMU_DOWN)..(stock + TMU_UP)) return emptyList()
            groups += TempGroup("$TMU_ID_PREFIX$i", "Throttle step ${i + 1}", TempKind.TMU, listOf(path), true,
                stock - TMU_DOWN, stock + TMU_UP, c, writable = fs.ownerWritable(path) ?: false, order = i)
        }
        return groups
    }

    /** Moto: only offered when the thermal-relax module's conf is present and has the two rules we edit. */
    fun detectThermalEngine(fs: SysFs): TempGroup? {
        val txt = fs.read(TE_CONF) ?: return null
        if (!ThermalConf.editable(txt)) return null
        val c = ThermalConf.firstSetC(txt) ?: return null
        return TempGroup(TE_ID, "Soft throttle (thermal-engine)", TempKind.THERMAL_ENGINE, listOf(TE_CONF), false,
            ThermalConf.MIN_C, ThermalConf.MAX_C, c)
    }

    /** Current value of a group in whole degrees (first readable node). */
    fun read(fs: SysFs, g: TempGroup): Int? {
        if (g.kind == TempKind.THERMAL_ENGINE) return ThermalConf.firstSetC(fs.read(g.paths.first()))
        for (p in g.paths) {
            val v = fs.read(p).firstInt() ?: continue
            return if (g.milli) Math.round(v / 1000.0).toInt() else v
        }
        return null
    }
}
