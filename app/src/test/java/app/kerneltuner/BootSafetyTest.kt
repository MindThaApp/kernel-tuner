package app.kerneltuner

import app.kerneltuner.core.*
import org.junit.Assert.*
import org.junit.Test

class BootSafetyTest {
    private val none = CrashEvidence(false, null, emptyList())
    private fun props(sys: String? = null, ro: String? = null, persist: String? = null) = BootProps(sys, ro, persist)
    private val prof = Profile("p1", "Perf", Settings(cpu = mapOf("cpu4" to ClusterSetting(maxKhz = 2100000))), applyOnBoot = true)
    private val active = ActiveRecord("b1", "p1", "BOOT", listOf("CPU 4-7 max 2100 MHz"), 0)

    @Test fun markerMeansClean() {
        assertEquals(Verdict.CLEAN, BootClassifier.classify(true, props("cold,powerkey", "power_key_press"), none).verdict)
        assertEquals(Verdict.CLEAN, BootClassifier.classify(true, props("reboot", "reboot"), none).verdict)
    }

    @Test fun missingMarkerIsUncleanUnlessUserRequested() {
        assertEquals(Verdict.UNCLEAN, BootClassifier.classify(false, props("cold,powerkey", "power_key_press"), none).verdict)
        assertEquals(Verdict.UNCLEAN, BootClassifier.classify(false, props("reboot", "reboot"), none).verdict)
        assertEquals(Verdict.UNCLEAN, BootClassifier.classify(false, props(), none).verdict)
        assertEquals(Verdict.CLEAN, BootClassifier.classify(false, props("reboot,userrequested"), none).verdict)
    }

    @Test fun crashReasonsOverrideMarker() {
        for (r in listOf("kernel_panic", "reboot,watchdog", "hw_reset", "reboot,hung", "shutdown,thermal"))
            assertEquals(r, Verdict.UNCLEAN, BootClassifier.classify(true, props(r), none).verdict)
        val ev = CrashEvidence(true, "Kernel panic", emptyList())
        assertEquals(Verdict.UNCLEAN, BootClassifier.classify(true, props("reboot,userrequested"), ev).verdict)
    }

    @Test fun scannerFindsPanicsButNotNoise() {
        val noisy = "[    1.0] watchdog: BUG: soft lockup\n[ 2.0] normal line\nCall trace: foo"
        // "BUG: " matches, ordinary watchdog/warn text must not
        assertTrue(CrashScanner.scan(mapOf("last_kmsg" to noisy)).found)
        assertFalse(CrashScanner.scan(mapOf("last_kmsg" to "[ 1.0] watchdog: registered\n[ 2.0] WARNING: CPU: 0\nCall trace:")).found)
        assertTrue(CrashScanner.scan(mapOf("dmesg-ramoops-0" to "Panic#1 Part1\nfoo")).found)
        assertFalse(CrashScanner.scan(mapOf("dmesg-ramoops-0" to "  ")).found)
        assertTrue(CrashScanner.scan(mapOf("console-ramoops-0" to "Kernel panic - not syncing: x")).found)
    }

    @Test fun scannerKeepsLastFiftyLines() {
        val text = (1..200).joinToString("\n") { "line $it" }
        val ev = CrashScanner.scan(mapOf("last_kmsg" to text))
        assertEquals(50, ev.tail.size)
        assertEquals("line 200", ev.tail.last())
        assertEquals("line 151", ev.tail.first())
    }

    @Test fun uncleanWithNonStockActiveNeverApplies() {
        val d = BootGuard.decide(active, Verdict.UNCLEAN, prof, 0, true)
        assertNull(d.apply)
        assertEquals("p1", d.markUnstableId)
        assertEquals(active.summary, d.noticeSettings)
    }

    @Test fun cleanBootAppliesBootProfile() {
        assertEquals(prof, BootGuard.decide(active, Verdict.CLEAN, prof, 1, true).apply)
    }

    @Test fun uncleanWithNothingActiveStillApplies() {
        assertEquals(prof, BootGuard.decide(null, Verdict.UNCLEAN, prof, 0, true).apply)
    }

    @Test fun unstableProfileNeverApplies() {
        assertNull(BootGuard.decide(null, Verdict.CLEAN, prof.copy(unstable = true), 0, true).apply)
    }

    @Test fun loopBreakerStopsSecondUnconfirmedApplyEvenIfActiveRecordLost() {
        val d = BootGuard.decide(null, Verdict.UNCLEAN, prof, 1, true)
        assertNull(d.apply)
        assertEquals("p1", d.markUnstableId)
    }

    @Test fun noRootOrNoProfile() {
        assertNull(BootGuard.decide(null, Verdict.CLEAN, prof, 0, false).apply)
        assertNull(BootGuard.decide(null, Verdict.CLEAN, null, 0, true).apply)
    }

    /** Simulates repeated boots: a profile that crashes the device must be applied at most once. */
    @Test fun simulatedBootLoopAppliesOnce() {
        var p = prof
        var prevActive: ActiveRecord? = null
        var unconfirmed = 0
        var applies = 0
        repeat(10) { boot ->
            // every boot is unclean (kernel panics shortly after apply, no marker written)
            val d = BootGuard.decide(prevActive, Verdict.UNCLEAN, p, unconfirmed, true)
            prevActive = null
            if (d.markUnstableId == p.id) p = p.copy(unstable = true)
            if (d.apply != null) {
                applies++; unconfirmed++
                prevActive = ActiveRecord("b$boot", p.id, "BOOT", emptyList(), 0)
            }
        }
        assertEquals(1, applies)
        assertTrue(p.unstable)
    }
}
