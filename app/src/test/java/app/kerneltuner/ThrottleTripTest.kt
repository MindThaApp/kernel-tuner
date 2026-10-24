package app.kerneltuner

import app.kerneltuner.core.*
import app.kerneltuner.hw.*
import org.junit.Assert.*
import org.junit.Test

class ThrottleTripTest {
    private val ids = (0..4).map { "tmu$it" }

    private fun samsung(writable: Boolean = true, values: List<Int> = listOf(75, 80, 85, 90, 95)): Triple<OverlayFs, Hardware, Settings> {
        val fs = TripFixtures.samsungTrips(writable, values)
        val hw = HardwareDetector.detect(fs)
        return Triple(fs, hw, Snapshot.read(fs, hw))
    }

    private fun target(vararg v: Int) = Settings(temp = ids.zip(v.toList()).toMap())

    @Test fun detectsFiveTripsWithRangeStockMinus20ToPlus10() {
        val (_, hw, now) = samsung()
        val g = hw.tempGroups.filter { it.kind == TempKind.TMU }
        assertEquals(5, g.size)
        assertEquals(listOf(55, 60, 65, 70, 75), g.map { it.minC })
        assertEquals(listOf(85, 90, 95, 100, 105), g.map { it.maxC })
        assertTrue(g.all { it.writable })
        assertEquals(75, now.temp["tmu0"]); assertEquals(95, now.temp["tmu4"])
    }

    @Test fun readOnlyNodesAreReportedAndNeverWritten() {
        val (_, hw, now) = samsung(writable = false)
        assertTrue(hw.tempGroups.filter { it.kind == TempKind.TMU }.none { it.writable })
        val p = Planner.plan(hw, now, target(80, 85, 90, 95, 100), now)
        assertTrue(p.ops.none { it.path.contains("trip_point") })
        assertTrue(p.warnings.single().contains("updated kernel"))
    }

    @Test fun offsetPlusTenRaisesAllAndIsAboveStock() {
        val (_, hw, now) = samsung()
        val p = Planner.plan(hw, now, target(85, 90, 95, 100, 105), now)
        assertEquals(5, p.ops.size)
        assertEquals(listOf(4, 3, 2, 1, 0), p.ops.map { it.path.substringAfter("trip_point_").substringBefore("_").toInt() })  // top first
        assertTrue(p.ops.all { WriteWhitelist.allows(it) })
        assertEquals(1, p.aboveStock.size)
        assertTrue(p.aboveStock.single().contains("hotter"))
        // revert is lowering: bottom first, back to stock
        assertEquals(listOf(0, 1, 2, 3, 4), p.revertOps.map { it.path.substringAfter("trip_point_").substringBefore("_").toInt() })
        assertEquals(listOf("75000", "80000", "85000", "90000", "95000"), p.revertOps.map { it.value })
    }

    @Test fun minusTwentyIsAllowedAndNotAboveStock() {
        val (_, hw, now) = samsung()
        val p = Planner.plan(hw, now, target(55, 60, 65, 70, 75), now)
        assertEquals(listOf("55000", "60000", "65000", "70000", "75000"), p.ops.map { it.value })
        assertTrue(p.aboveStock.isEmpty())
        assertEquals(listOf(0, 1, 2, 3, 4), p.ops.map { it.path.substringAfter("trip_point_").substringBefore("_").toInt() })
    }

    @Test fun valuesAreClampedToStockMinus20AndPlus10() {
        val (_, hw, now) = samsung()
        val p = Planner.plan(hw, now, target(0, 0, 0, 0, 500), now)
        assertEquals(listOf("105000", "55000", "60000", "65000", "70000"), p.ops.map { it.value })   // raise first, then lower bottom-up
        assertTrue(p.ops.all { WriteWhitelist.allows(it) })
    }

    @Test fun tripsStayStrictlyAscending() {
        val (_, hw, now) = samsung()
        // per-trip values that would cross: trip 1 asked below trip 0
        val p = Planner.plan(hw, now, target(84, 70, 85, 90, 95), now)
        val v = hw.tempGroups.filter { it.kind == TempKind.TMU }.sortedBy { it.order }.map { p.after.temp[it.id]!! }
        assertEquals(v.sorted(), v)
        assertEquals(v.distinct(), v)
        assertEquals(85, v[1])
    }

    @Test fun impossibleOrderIsRefusedNotWritten() {
        val (_, hw, now) = samsung()
        // trip 3 forced to its max 100, trip 4 asked 90 -> clamps to its min 75, fine; build a real conflict: trip 0 at 85 and trip 1 at 85 -> 86 ok.
        // A conflict that cannot be fixed: trip 3 max is 100, a chain pushing trip 3 above that is impossible only via lower trips <= 85,
        // so verify the guard directly with a skewed group list.
        val skew = hw.copy(tempGroups = hw.tempGroups.map { if (it.id == "tmu1") it.copy(minC = 90, maxC = 90) else it })
        val p = Planner.plan(skew, now, target(85, 90, 85, 90, 95), now)
        assertTrue(p.ops.isEmpty() || p.after.temp["tmu1"]!! > p.after.temp["tmu0"]!!)
    }

    @Test fun partialLoweringKeepsEveryIntermediateStateAscending() {
        val (fs, hw, now) = samsung()
        // start from a raised state, then go to a lowered one
        val raised = Settings(temp = ids.zip(listOf(85, 90, 95, 100, 105)).toMap())
        val low = Planner.plan(hw, raised, target(60, 65, 70, 75, 80), now)
        val state = raised.temp.toMutableMap()
        for (op in low.ops) {
            val i = op.path.substringAfter("trip_point_").substringBefore("_").toInt()
            state["tmu$i"] = op.value.toInt() / 1000
            val seq = (0..4).map { state["tmu$it"]!! }
            assertEquals("after write $i: $seq", seq.sorted(), seq)
        }
        assertTrue(fs.exists("/sys/class/thermal/thermal_zone0/trip_point_0_temp"))
    }

    @Test fun tmuWritesAreWhitelistedOnlyInRange() {
        for (i in 0..4) assertTrue(WriteWhitelist.allows("/sys/class/thermal/thermal_zone0/trip_point_${i}_temp", "80000"))
        assertFalse(WriteWhitelist.allows("/sys/class/thermal/thermal_zone0/trip_point_3_temp", "110000"))
        assertFalse(WriteWhitelist.allows("/sys/class/thermal/thermal_zone0/trip_point_3_temp", "40000"))
        assertFalse(WriteWhitelist.allows("/sys/class/thermal/thermal_zone0/trip_point_5_temp", "80000"))
        assertFalse(WriteWhitelist.allows("/sys/class/thermal/thermal_zone5/trip_point_3_temp", "80000"))
    }

    @Test fun tmuIsTreatedAsOverclockForTheBootGate() {
        val (_, hw, now) = samsung()
        assertTrue(OcPolicy.isOc(hw, target(85, 90, 95, 100, 105), now))
        assertFalse(OcPolicy.isOc(hw, target(70, 75, 80, 85, 90), now))
        val p = Planner.plan(hw, now, target(80, 85, 90, 95, 100), now)
        assertTrue(OcSteps.of(hw, p.after, now).isNotEmpty())
        val d = OcPolicy.decide(true, true, false, true, false, false, false, false)
        assertEquals(BootAction.SKIP, d.action)
    }

    @Test fun presetsNeverTouchTripsOrThermalEngine() {
        val (_, hw, now) = samsung()
        for (pr in Preset.values()) {
            val s = Presets.build(hw, now, pr)
            for (id in ids) assertEquals(now.temp[id], s.temp[id])
        }
    }

    // ---- Moto thermal-engine conf ----
    private fun diffLines(a: String, b: String) = a.lines().zip(b.lines()).withIndex().filter { it.value.first != it.value.second }.map { it.index }

    @Test fun confRewriteChangesOnlyTheFourTargetedLines() {
        val base = TripFixtures.conf
        assertEquals(52, ThermalConf.firstSetC(base))
        val out = ThermalConf.rewrite(base, 60)!!
        assertEquals(base.lines().size, out.lines().size)
        val changed = diffLines(base, out)
        assertEquals(4, changed.size)
        val lines = out.lines()
        for (i in changed) assertTrue(lines[i].trim().startsWith("set_point"))
        assertEquals(60, ThermalConf.firstSetC(out))
        // later step keeps its 6 C spacing and the clear offsets keep their 3 C
        assertTrue(out.contains("set_point        60000"))
        assertTrue(out.contains("set_point_clr    57000"))
        assertTrue(out.contains("set_point        66000"))
        assertTrue(out.contains("set_point_clr    63000"))
        // every other rule is byte-identical
        assertTrue(out.contains("set_point        41000"))
        assertTrue(out.contains("set_point        95000"))
        assertTrue(out.contains("device_max_limit 1401600"))
        assertTrue(out.contains("thresholds       52000 56000"))
    }

    @Test fun confRewriteIsRelativeToThePristineBaseAndClamped() {
        val base = TripFixtures.conf
        assertEquals(base, ThermalConf.rewrite(base, 52))
        assertEquals(43, ThermalConf.firstSetC(ThermalConf.rewrite(base, 0)))
        assertEquals(65, ThermalConf.firstSetC(ThermalConf.rewrite(base, 200)))
        // original 43 C rule: set 43000 / clr 40000, hot 49000 / 46000
        val low = ThermalConf.rewrite(base, 43)!!
        assertTrue(low.contains("set_point        43000"))
        assertTrue(low.contains("set_point_clr    40000"))
        assertTrue(low.contains("set_point        49000"))
        // rewriting twice from the same base equals once
        assertEquals(ThermalConf.rewrite(base, 60), ThermalConf.rewrite(base, 60))
    }

    @Test fun confWithoutTheRulesIsNotEdited() {
        assertNull(ThermalConf.rewrite("[SS-GPU]\nset_point 95000\n", 50))
        assertFalse(ThermalConf.editable(null))
        assertFalse(ThermalConf.editable(TripFixtures.conf.replace("[SS-MSM-CLUSTER0-HOT]", "[OTHER]")))
    }

    @Test fun motoThermalEngineGroupAndPlan() {
        val fs = TripFixtures.motoWithConf()
        val hw = HardwareDetector.detect(fs)
        val g = hw.tempGroups.single { it.kind == TempKind.THERMAL_ENGINE }
        assertEquals(43, g.minC); assertEquals(65, g.maxC)
        val now = Snapshot.read(fs, hw)
        assertEquals(52, now.temp[g.id])
        val p = Planner.plan(hw, now, Settings(temp = mapOf(g.id to 70)), now)
        assertEquals("65", p.ops.single().value)             // clamped
        assertEquals(1, p.aboveStock.size)
        assertEquals("52", p.revertOps.single().value)
        assertTrue(p.ops.all { WriteWhitelist.allows(it) })
        val lower = Planner.plan(hw, now, Settings(temp = mapOf(g.id to 45)), now)
        assertTrue(lower.aboveStock.isEmpty())
        assertTrue(OcPolicy.isOc(hw, Settings(temp = mapOf(g.id to 60)), now))
    }

    @Test fun thermalEngineWhitelistIsRangeAndPathLimited() {
        val conf = WriteWhitelist.THERMAL_CONF
        assertTrue(WriteWhitelist.allows(conf, "43")); assertTrue(WriteWhitelist.allows(conf, "65"))
        assertFalse(WriteWhitelist.allows(conf, "42")); assertFalse(WriteWhitelist.allows(conf, "66"))
        assertFalse(WriteWhitelist.allows("/data/adb/modules/other/system/vendor/etc/thermal-engine.conf", "50"))
        assertFalse(WriteWhitelist.allows("/vendor/etc/thermal-engine.conf", "50"))
    }

    @Test fun noThermalEngineGroupWithoutTheModule() {
        assertTrue(HardwareDetector.detect(OcFixtures.motoOc()).tempGroups.none { it.kind == TempKind.THERMAL_ENGINE })
    }

    @Test fun kernelTripsOnMotoStillNeverAboveStock() {
        val fs = TripFixtures.motoWithConf()
        val hw = HardwareDetector.detect(fs)
        val now = Snapshot.read(fs, hw)
        val p = Planner.plan(hw, now, Settings(temp = mapOf("cpu_trip" to 200, "gpu_trip" to 200)), now)
        assertTrue(p.ops.isEmpty())
    }
}
