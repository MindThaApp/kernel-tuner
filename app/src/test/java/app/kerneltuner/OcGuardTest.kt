package app.kerneltuner

import app.kerneltuner.core.*
import app.kerneltuner.hw.HardwareDetector
import org.junit.Assert.*
import org.junit.Test

class OcGuardTest {
    private val samFs = OcFixtures.samsungOc()
    private val sam = HardwareDetector.detect(samFs)
    private val now = Snapshot.read(samFs, sam)

    private fun d(
        oc: Boolean = true, boot: Boolean = true, confirmed: Boolean = true, clean: Boolean = true,
        crashed: Boolean = false, auto: Boolean = false, rapid: Boolean = false, unstable: Boolean = false,
    ) = OcPolicy.decide(oc, boot, confirmed, clean, crashed, auto, rapid, unstable)

    @Test fun allConditionsMetAppliesOnlyAfterDelay() {
        assertEquals(BootAction.APPLY_DELAYED, d().action)
        assertEquals(90_000L, OcPolicy.DELAY_MS)
    }

    @Test fun eachMissingConditionWritesNothing() {
        for (x in listOf(d(boot = false), d(confirmed = false), d(clean = false), d(crashed = true), d(unstable = true), d(auto = true), d(rapid = true))) {
            assertEquals(BootAction.SKIP, x.action)
            assertNotNull(x.reason)
        }
    }

    @Test fun nonOcProfilesStillApplyUnlessAutomaticApplyIsOff() {
        assertEquals(BootAction.APPLY_NOW, d(oc = false, confirmed = false).action)
        assertEquals(BootAction.SKIP, d(oc = false, rapid = true).action)
        assertEquals(BootAction.SKIP, d(oc = false, auto = true).action)
    }

    @Test fun rapidRebootsNeedThreeBootsInFiveMinutes() {
        val now = 10_000_000L
        assertFalse(OcPolicy.rapidReboots(listOf(now - 60_000, now), now))
        assertTrue(OcPolicy.rapidReboots(listOf(now - 200_000, now - 100_000, now), now))
        assertFalse(OcPolicy.rapidReboots(listOf(now - 900_000, now - 600_000, now), now))
    }

    @Test fun markerMeansCrashUnlessShutdownWasClean() {
        assertTrue(OcPolicy.crashedWithMarker(true, cleanShutdownSeen = false, verdictClean = false))
        assertTrue(OcPolicy.crashedWithMarker(true, cleanShutdownSeen = false, verdictClean = true))
        assertTrue(OcPolicy.crashedWithMarker(true, cleanShutdownSeen = true, verdictClean = false))
        assertFalse(OcPolicy.crashedWithMarker(true, cleanShutdownSeen = true, verdictClean = true))
        assertFalse(OcPolicy.crashedWithMarker(false, false, false))
    }

    @Test fun twoConsecutiveOcCrashesReachTheLimit() {
        var s = OcPolicy.nextStreak(0, markerPresent = true, crashed = true, verdictClean = false)
        assertEquals(1, s); assertTrue(s < OcPolicy.STREAK_LIMIT)
        s = OcPolicy.nextStreak(s, true, true, false)
        assertTrue(s >= OcPolicy.STREAK_LIMIT)
        assertEquals(0, OcPolicy.nextStreak(s, markerPresent = false, crashed = false, verdictClean = true))
        assertEquals(1, OcPolicy.nextStreak(1, markerPresent = false, crashed = false, verdictClean = false))
    }

    @Test fun testModeValuesAreNeverHeld() {
        assertFalse(OcPolicy.persistAsHeld("TEST"))
        assertTrue(OcPolicy.persistAsHeld("MANUAL"))
        assertTrue(OcPolicy.persistAsHeld("BOOT"))
    }

    @Test fun fullTestMeansKeepAfterMostOfTheCountdown() {
        assertFalse(OcPolicy.isFullTest(0, 180_000, 20_000))
        assertTrue(OcPolicy.isFullTest(0, 180_000, 150_000))
        assertFalse(OcPolicy.isFullTest(0, 0, 999_999))
    }

    @Test fun ocStepsAreListedForMarkerAndUnstableMemory() {
        val t = Settings(cpu = mapOf("cpu4" to ClusterSetting(maxKhz = 2400000)), gpu = GpuSetting(maxMhz = 852), temp = mapOf("ipa" to 70))
        val p = Planner.plan(sam, now, t, now)
        assertEquals(listOf("cpu:cpu4:2400000", "gpu:852", "temp:ipa:70"), OcSteps.of(sam, p.after, now))
        assertTrue(OcSteps.of(sam, now, now).isEmpty())
        assertTrue(OcPolicy.isOc(sam, t, now))
        assertFalse(OcPolicy.isOc(sam, Presets.build(sam, now, Preset.PERFORMANCE), now))
    }

    @Test fun confirmationIsTiedToTheExactSettings() {
        val s = Settings(cpu = mapOf("cpu4" to ClusterSetting(maxKhz = 2200000)))
        val p = Profile("a", "x", s, applyOnBoot = true, confirmedSettings = s)
        assertTrue(p.confirmedSettings == p.settings)
        val edited = p.copy(settings = s.copy(cpu = mapOf("cpu4" to ClusterSetting(maxKhz = 2304000))))
        assertFalse(edited.confirmedSettings == edited.settings)
        assertNull(Profile("b", "y", s).confirmedSettings)
    }

    @Test fun markerAndPendingStateRoundTrip() {
        val json = kotlinx.serialization.json.Json
        val m = OcMarkerData("boot1", "p", listOf("gpu:852"), 5)
        assertEquals(m, json.decodeFromString(OcMarkerData.serializer(), json.encodeToString(OcMarkerData.serializer(), m)))
        val t = TestState("b", 1, emptyList(), emptyList())
        assertNull(t.pendingHeld)
    }
}
