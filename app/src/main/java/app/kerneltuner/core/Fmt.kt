package app.kerneltuner.core

import java.util.Locale

/** Human-friendly units. Pure, so it is unit-tested. */
object Fmt {
    /** 1_500_000 kHz -> "1.5 GHz"; 652_800 kHz -> "653 MHz". */
    fun khz(khz: Int?): String {
        if (khz == null) return "-"
        return if (khz >= 1_000_000) ghz(khz / 1_000_000.0) else "${Math.round(khz / 1000.0)} MHz"
    }

    fun mhz(mhz: Int?): String {
        if (mhz == null) return "-"
        return if (mhz >= 1000) ghz(mhz / 1000.0) else "$mhz MHz"
    }

    private fun ghz(v: Double): String {
        val s = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
        return "$s GHz"
    }

    fun celsius(c: Double?): String = if (c == null) "-" else String.format(Locale.US, "%.1f °C", c)

    /** Plain-word heat level for a phone SoC/battery reading. */
    fun heat(c: Double): String = when {
        c < 38 -> "Cool"
        c < 45 -> "Warm"
        c < 55 -> "Hot"
        else -> "Very hot"
    }

    fun mem(kb: Long): String {
        val mb = kb / 1024.0
        return if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024) else String.format(Locale.US, "%.0f MB", mb)
    }

    fun bytes(b: Long): String = mem(b / 1024)

    fun countdown(seconds: Long): String = "${seconds / 60}:${"%02d".format(seconds % 60)}"
}
