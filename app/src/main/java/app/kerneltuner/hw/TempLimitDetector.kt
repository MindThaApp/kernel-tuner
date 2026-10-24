package app.kerneltuner.hw

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
        return out
    }

    /** Current value of a group in whole degrees (first readable node). */
    fun read(fs: SysFs, g: TempGroup): Int? {
        for (p in g.paths) {
            val v = fs.read(p).firstInt() ?: continue
            return if (g.milli) Math.round(v / 1000.0).toInt() else v
        }
        return null
    }
}
