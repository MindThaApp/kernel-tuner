package app.kerneltuner.core

import app.kerneltuner.hw.WriteOp

/**
 * The complete list of files the app may ever write, each with the values it may receive.
 * Anything else is rejected before a shell is touched, whatever the planner produced.
 */
object WriteWhitelist {
    const val GPU_LIMIT_NODE = "/sys/kernel/gpu/gpu_max_clock_limit"
    const val THERMAL_CONF = "/data/adb/modules/sanders_oc_thermal_relax/system/vendor/etc/thermal-engine.conf"
    const val IPA_CONTROL_TEMP = "/sys/power/ipa/control_temp"

    /** Absolute ceilings for temperature limits, whatever the stock value is. */
    const val IPA_MIN_C = 40
    const val IPA_MAX_C = 120
    const val TRIP_MIN_C = 50
    const val TRIP_MAX_C = 105   // the stock CPU trip; the GPU zone is held to 95 by the planner

    private val NAME = Regex("^[A-Za-z0-9_.+\\-]{1,48}$")
    private val NUM = Regex("^-?\\d{1,9}$")

    private class Entry(val path: Regex, val value: (String) -> Boolean)

    private fun num(lo: Long, hi: Long): (String) -> Boolean = { v -> NUM.matches(v) && v.toLong() in lo..hi }

    private val entries = listOf(
        // CPU frequency policy (modern and legacy layout)
        Entry(Regex("^/sys/devices/system/cpu/cpufreq/policy\\d{1,2}/scaling_governor$")) { NAME.matches(it) },
        Entry(Regex("^/sys/devices/system/cpu/cpufreq/policy\\d{1,2}/scaling_(min|max)_freq$"), num(1, 5_000_000)),
        Entry(Regex("^/sys/devices/system/cpu/cpu\\d{1,2}/cpufreq/scaling_governor$")) { NAME.matches(it) },
        Entry(Regex("^/sys/devices/system/cpu/cpu\\d{1,2}/cpufreq/scaling_(min|max)_freq$"), num(1, 5_000_000)),
        // Adreno
        Entry(Regex("^/sys/class/kgsl/kgsl-3d0/devfreq/governor$")) { NAME.matches(it) },
        Entry(Regex("^/sys/class/kgsl/kgsl-3d0/devfreq/(min|max)_freq$"), num(1, 2_000_000_000)),
        Entry(Regex("^/sys/class/kgsl/kgsl-3d0/max_pwrlevel$"), num(0, 15)),
        // Mali (stock sysfs files)
        Entry(Regex("^/sys/devices/[A-Za-z0-9_.\\-]{1,24}\\.mali/dvfs_governor$"), num(0, 15)),
        Entry(Regex("^/sys/devices/[A-Za-z0-9_.\\-]{1,24}\\.mali/dvfs_(min|max)_lock$"), num(-1, 2000)),
        // Samsung OC kernel governor ceiling: 700 = stock, 772, 852 and nothing else
        Entry(Regex("^" + Regex.escape(GPU_LIMIT_NODE) + "$")) { it in OcInfo.MALI_LIMIT_VALUES.map(Int::toString) },
        // Samsung IPA target temperature (whole degrees)
        Entry(Regex("^" + Regex.escape(IPA_CONTROL_TEMP) + "$"), num(IPA_MIN_C.toLong(), IPA_MAX_C.toLong())),
        // Moto: step_wise trip point 0 only, millidegrees, never above the stock CPU trip
        Entry(Regex("^/sys/class/thermal/thermal_zone\\d{1,3}/trip_point_0_temp$"), num(TRIP_MIN_C * 1000L, TRIP_MAX_C * 1000L)),
        // Samsung TMU trips 1..4 (trip 0 is covered above): stock-20 .. stock+10, so 55..105 C overall
        Entry(Regex("^/sys/class/thermal/thermal_zone0/trip_point_[1-4]_temp$"), num(55_000, 105_000)),
        // Moto: first thermal-engine rule inside the module's overlay, whole degrees 43..65. Written by ThermalEngine, not by echo.
        Entry(Regex("^" + Regex.escape(THERMAL_CONF) + "$"), num(ThermalConf.MIN_C.toLong(), ThermalConf.MAX_C.toLong())),
        // Storage scheduler and the four plain vm sysctls
        Entry(Regex("^/sys/block/[A-Za-z0-9_\\-]{1,24}/queue/scheduler$")) { NAME.matches(it) },
        Entry(Regex("^/proc/sys/vm/(swappiness|dirty_ratio|dirty_background_ratio|vfs_cache_pressure)$"), num(0, 1000)),
    )

    fun allows(path: String, value: String): Boolean =
        entries.any { it.path.matches(path) && it.value(value) }

    fun allows(op: WriteOp): Boolean = allows(op.path, op.value)
}
