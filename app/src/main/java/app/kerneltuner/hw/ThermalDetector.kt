package app.kerneltuner.hw

object ThermalDetector {
    private val ZONE = Regex("^thermal_zone(\\d+)$")

    /** Virtual/derived zones (thermal-engine "step"/"lowf" copies), duplicated battery/charger probes. */
    private val NOT_A_SENSOR = Regex("(?i)(-|_)(step|lowf)$|virtual|^ac$|^usb|^bms$|fuelgauge|^case-therm|^pa-therm|^xo-therm")

    fun detect(fs: SysFs): List<ThermalZone> {
        val zones = fs.list(Paths.THERMAL).mapNotNull { ZONE.matchEntire(it)?.groupValues?.get(1)?.toInt() }.sorted()
        val seen = HashSet<String>()
        val out = ArrayList<ThermalZone>()
        for (n in zones) {
            val dir = "${Paths.THERMAL}/thermal_zone$n"
            val type = fs.read("$dir/type") ?: continue
            if (NOT_A_SENSOR.containsMatchIn(type)) continue
            if (fs.read("$dir/mode") == "disabled") continue
            val c = parseTempC(fs.read("$dir/temp")) ?: continue
            if (c <= 0.0 || c > 150.0) continue
            if (!seen.add(type)) continue
            out += ThermalZone(dir, type, categorize(type))
        }
        return out
    }

    fun categorize(type: String): ThermalCategory {
        val t = type.lowercase()
        return when {
            "gpu" in t -> ThermalCategory.GPU
            "batt" in t -> ThermalCategory.BATTERY
            "cpu" in t || "apc" in t || "exynos" in t || "soc" in t || "cluster" in t -> ThermalCategory.CPU
            else -> ThermalCategory.OTHER
        }
    }

    /** Kernels report millidegrees (usual), deci-degrees, or plain degrees. */
    fun parseTempC(s: String?): Double? {
        val v = s?.trim()?.toLongOrNull() ?: return null
        val a = Math.abs(v)
        return when {
            a >= 1000 -> v / 1000.0
            a > 200 -> v / 10.0
            else -> v.toDouble()
        }
    }
}
