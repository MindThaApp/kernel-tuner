package app.kerneltuner.core

enum class Verdict { CLEAN, UNCLEAN }

data class BootProps(val sysBootReason: String?, val roBootReason: String?, val persistBootReason: String?) {
    val all get() = listOfNotNull(sysBootReason, roBootReason, persistBootReason).joinToString(" ").lowercase()
}

data class CrashEvidence(val found: Boolean, val reason: String?, val tail: List<String>)

data class Classification(val verdict: Verdict, val reason: String)

object CrashScanner {
    private val PATTERNS = listOf(
        Regex("Kernel panic"),
        Regex("Unable to handle kernel"),
        Regex("\\bOops[: ]"),
        Regex("\\bBUG: "),
        Regex("Internal error: "),
        Regex("(?i)watchdog (bite|bark)|apps_wdog_bark|wdt.*(bite|expired)"),
        Regex("SError Interrupt"),
        Regex("(?i)thermal shutdown|power-?off by thermal"),
    )

    /**
     * [files] maps a source name ("dmesg-ramoops-0", "console-ramoops-0", "last_kmsg") to its text.
     * A pstore dmesg record exists only after a panic/oops, so its mere presence is evidence.
     */
    fun scan(files: Map<String, String>, tailLines: Int = 50): CrashEvidence {
        var reason: String? = null
        val dmesg = files.keys.firstOrNull { it.startsWith("dmesg-") }
        if (dmesg != null && files[dmesg]!!.isNotBlank()) reason = "pstore record $dmesg present"
        if (reason == null) {
            for ((name, text) in files) {
                val hit = PATTERNS.firstNotNullOfOrNull { p -> p.find(text)?.value }
                if (hit != null) { reason = "'$hit' found in $name"; break }
            }
        }
        val best = files.keys.firstOrNull { it.startsWith("dmesg-") }
            ?: files.keys.firstOrNull { it.startsWith("console-") }
            ?: files.keys.firstOrNull { it == "last_kmsg" }
        val tail = best?.let { files[it]!!.lines().filter { l -> l.isNotBlank() }.takeLast(tailLines) } ?: emptyList()
        return CrashEvidence(reason != null, reason, tail)
    }
}

object BootClassifier {
    private val BAD = listOf("panic", "watchdog", "wdt", "hw_reset", "hardware_reset", "hung", "oem_sdi", "thermal", "over_current", "undervoltage", "kernel_fault")
    private val USER = listOf("userrequested", "shutdown", "shell", "recovery", "bootloader", "adb", "ota")

    /** Marker-missing is treated as unclean unless the ROM itself says the reboot was requested. */
    fun classify(markerPresent: Boolean, props: BootProps, ev: CrashEvidence): Classification {
        if (ev.found) return Classification(Verdict.UNCLEAN, ev.reason ?: "crash log evidence")
        val p = props.all
        BAD.firstOrNull { it in p }?.let { return Classification(Verdict.UNCLEAN, "boot reason '$p' ($it)") }
        if (markerPresent) return Classification(Verdict.CLEAN, "clean-shutdown marker present")
        if (USER.any { it in p }) return Classification(Verdict.CLEAN, "boot reason '$p' is user-requested")
        return Classification(Verdict.UNCLEAN, "no clean-shutdown marker (boot reason '$p')")
    }
}

data class BootDecision(
    val apply: Profile?,
    /** Profile id to mark unstable, if any. */
    val markUnstableId: String?,
    /** When non-null, post the "unclean reboot" notification with these settings. */
    val noticeSettings: List<String>?,
    val note: String,
)

object BootGuard {
    /**
     * Never applies a profile after an unclean boot that had non-stock settings active, and never
     * applies twice in a row without an intervening clean shutdown or confirmed-stable window.
     */
    fun decide(
        prevActive: ActiveRecord?,
        verdict: Verdict,
        bootProfile: Profile?,
        unconfirmedBootApplies: Int,
        rootAvailable: Boolean,
    ): BootDecision {
        if (prevActive != null && verdict == Verdict.UNCLEAN) {
            return BootDecision(null, prevActive.profileId ?: bootProfile?.id?.takeIf { prevActive.kind == "BOOT" }, prevActive.summary,
                "Unclean reboot with non-stock settings active; not applying")
        }
        if (bootProfile == null) return BootDecision(null, null, null, "No boot profile")
        if (bootProfile.unstable) return BootDecision(null, null, null, "Boot profile is marked unstable; not applying")
        if (unconfirmedBootApplies >= 1 && verdict != Verdict.CLEAN) {
            return BootDecision(null, bootProfile.id, bootProfile.settingsSummary(),
                "Previous boot-apply never confirmed stable and shutdown was not clean; not applying")
        }
        if (!rootAvailable) return BootDecision(null, null, null, "Root not available")
        return BootDecision(bootProfile, null, null, "Applying boot profile")
    }
}

fun Profile.settingsSummary(): List<String> {
    val l = ArrayList<String>()
    for ((k, v) in settings.cpu) l += "$k: ${v.governor ?: ""} ${v.minKhz?.div(1000) ?: "-"}-${v.maxKhz?.div(1000) ?: "-"} MHz".trim()
    settings.gpu?.let { l += "GPU: ${it.governor ?: ""} ${it.minMhz ?: "-"}-${it.maxMhz ?: "-"} MHz".trim() }
    for ((k, v) in settings.io) l += "I/O $k: $v"
    for ((k, v) in settings.mem) l += "$k = $v"
    return l
}
