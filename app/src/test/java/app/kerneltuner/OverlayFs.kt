package app.kerneltuner

import app.kerneltuner.hw.SysFs

/**
 * A fixture tree with edits on top: [files] adds or replaces a file, a null value removes it,
 * [removedDirs] hides a whole directory (a hotplugged-off core has no cpufreq directory).
 */
class OverlayFs(
    private val base: SysFs,
    private val files: Map<String, String?> = emptyMap(),
    private val removedDirs: Set<String> = emptySet(),
) : SysFs {
    private fun hidden(p: String) = removedDirs.any { p == it || p.startsWith("$it/") }

    override fun read(path: String): String? {
        if (path in files) return files[path]?.trim()
        if (hidden(path)) return null
        return base.read(path)
    }

    override fun list(path: String): List<String> {
        if (hidden(path)) return emptyList()
        val extra = files.entries.filter { it.value != null && it.key.startsWith("$path/") }.map { it.key.removePrefix("$path/").substringBefore('/') }
        return (base.list(path) + extra).distinct().sorted()
    }

    override fun exists(path: String): Boolean {
        if (path in files) return files[path] != null
        if (hidden(path)) return false
        return files.entries.any { it.value != null && it.key.startsWith("$path/") } || base.exists(path)
    }

    fun plus(more: Map<String, String?>, removed: Set<String> = emptySet()) = OverlayFs(base, files + more, removedDirs + removed)
}

object OcFixtures {
    private const val CPU = "/sys/devices/system/cpu"

    /** Samsung with the OC kernel: big table to 2.496 GHz, little to 2.0 GHz, GPU limit node, IPA. */
    fun samsungOc(): OverlayFs {
        val big = "2496000 0\n2400000 0\n2304000 0\n2200000 0\n2100000 1739\n2000000 164\n1896000 190\n1800000 277\n1704000 6015\n1600000 267\n1500000 183\n1400000 964\n800000 5"
        val little = "2000000 0\n1900000 0\n1800000 0\n1704000 0\n1600000 0\n1500000 8135\n1400000 5801\n1296000 1879\n1200000 970\n1104000 2258\n1000000 506\n900000 522\n800000 2551\n700000 136\n600000 211\n500000 837\n400000 29362"
        val m = HashMap<String, String?>()
        for (n in 4..7) { m["$CPU/cpu$n/cpufreq/stats/time_in_state"] = big; m["$CPU/cpu$n/cpufreq/cpuinfo_max_freq"] = "2496000" }
        for (n in 0..3) { m["$CPU/cpu$n/cpufreq/stats/time_in_state"] = little; m["$CPU/cpu$n/cpufreq/cpuinfo_max_freq"] = "2000000" }
        m["/sys/kernel/gpu/gpu_max_clock_limit"] = "700"
        m["/sys/power/ipa/control_temp"] = "63"
        return OverlayFs(Fixtures.fs("samsung"), m)
    }

    /** Moto with the OC kernel: four new CPU steps, GPU levels 725/700 above the 650 ceiling, step_wise trips. */
    fun motoOc(): OverlayFs {
        val m = HashMap<String, String?>()
        val p = "$CPU/cpufreq/policy0"
        m["$p/scaling_available_frequencies"] = "652800 1036800 1401600 1689600 1804800 1958400 2016000 2073600 2112000 2150400 2208000"
        m["$p/cpuinfo_max_freq"] = "2208000"
        val k = "/sys/class/kgsl/kgsl-3d0"
        m["$k/devfreq/available_frequencies"] = "133330000 216000000 320000000 400000000 510000000 560000000 650000000 700000000 725000000"
        m["$k/gpu_available_frequencies"] = "725000000 700000000 650000000 560000000 510000000 400000000 320000000 216000000 133330000"
        m["$k/devfreq/max_freq"] = "725000000"
        m["$k/max_pwrlevel"] = "2"
        for (z in 22..29) {
            val d = "/sys/class/thermal/thermal_zone$z"
            m["$d/type"] = "apc${(z - 22) / 4}-cpu${(z - 22) % 4}-step"; m["$d/policy"] = "step_wise"; m["$d/trip_point_0_temp"] = "105000"
        }
        m["/sys/class/thermal/thermal_zone30/type"] = "gpu1-step"
        m["/sys/class/thermal/thermal_zone30/policy"] = "step_wise"
        m["/sys/class/thermal/thermal_zone30/trip_point_0_temp"] = "95000"
        return OverlayFs(Fixtures.fs("moto"), m)
    }
}
