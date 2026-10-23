package app.kerneltuner

import app.kerneltuner.core.*
import app.kerneltuner.hw.*
import org.junit.Assert.*
import org.junit.Test

class PlannerTest {
    private val samFs = Fixtures.fs("samsung")
    private val motoFs = Fixtures.fs("moto")
    private val sam = HardwareDetector.detect(samFs)
    private val moto = HardwareDetector.detect(motoFs)
    private val samNow = Snapshot.read(samFs, sam)
    private val motoNow = Snapshot.read(motoFs, moto)

    @Test fun snapshotReadsBothLayouts() {
        assertEquals("interactive", samNow.cpu["cpu0"]!!.governor)
        assertEquals(2100000, samNow.cpu["cpu4"]!!.maxKhz)
        assertEquals("schedutil", motoNow.cpu["cpu0"]!!.governor)
        assertEquals("cfq", samNow.io["sda"])
        assertEquals(100L, motoNow.mem["swappiness"])
    }

    @Test fun legacyLayoutWritesEveryClusterMember() {
        val t = Settings(cpu = mapOf("cpu4" to ClusterSetting(maxKhz = 1500000)))
        val p = Planner.plan(sam, samNow, t, samNow)
        assertEquals(sam.clusters[1].paths.size, p.ops.size)
        assertTrue(p.ops.all { it.path.endsWith("scaling_max_freq") && it.value == "1500000" })
        assertTrue(p.aboveStock.isEmpty())
        // revert restores stock
        assertTrue(p.revertOps.all { it.value == "2100000" })
        assertEquals(p.ops.size, p.revertOps.size)
    }

    @Test fun policyLayoutWritesOnePath() {
        val t = Settings(cpu = mapOf("cpu0" to ClusterSetting(governor = "powersave", maxKhz = 1401600)))
        val p = Planner.plan(moto, motoNow, t, motoNow)
        assertEquals(2, p.ops.size)
        assertTrue(p.ops[0].path.endsWith("policy0/scaling_governor"))
    }

    @Test fun clampsAndSnaps() {
        val t = Settings(cpu = mapOf("cpu0" to ClusterSetting(minKhz = 1, maxKhz = 99_999_999)))
        val p = Planner.plan(moto, motoNow, t, motoNow)
        assertTrue(p.ops.isEmpty())   // clamps to the values already set
        val t2 = Settings(cpu = mapOf("cpu0" to ClusterSetting(maxKhz = 1450000)))
        val p2 = Planner.plan(moto, motoNow, t2, motoNow)
        assertEquals("1401600", p2.ops.single().value)
    }

    @Test fun invalidGovernorAndUnknownClusterIgnored() {
        val t = Settings(cpu = mapOf("cpu0" to ClusterSetting(governor = "evil; reboot"), "cpu99" to ClusterSetting(maxKhz = 1)))
        val p = Planner.plan(moto, motoNow, t, motoNow)
        assertTrue(p.ops.isEmpty())
        assertEquals(2, p.warnings.size)
    }

    @Test fun rangeWriteOrderNeverInvertsMinMax() {
        // cpu0 on Samsung currently 400-1500; move to 1000-1100 then (from 1000-1100) to 400-500
        val up = Planner.plan(sam, samNow, Settings(cpu = mapOf("cpu0" to ClusterSetting(minKhz = 1000000, maxKhz = 1100000))), samNow)
        val ups = up.ops.filter { it.path.contains("cpu0/") }.map { it.label }
        assertEquals(listOf("CPU 0-3 min", "CPU 0-3 max"), ups)   // min 1000 <= curMax 1500: min first
        val low = Settings(cpu = mapOf("cpu0" to ClusterSetting(minKhz = 400000, maxKhz = 500000)))
        val down = Planner.plan(sam, up.after, low, samNow)
        assertEquals("CPU 0-3 min", down.ops.first().label)      // 400 <= curMax 1100
        val jump = Planner.plan(sam, up.after, Settings(cpu = mapOf("cpu0" to ClusterSetting(minKhz = 1500000, maxKhz = 1500000))), samNow)
        assertEquals("CPU 0-3 max", jump.ops.first().label)       // newMin 1500 > curMax 1100: max first
    }

    @Test fun aboveStockDetected() {
        val stock = samNow.copy(cpu = samNow.cpu + ("cpu4" to samNow.cpu["cpu4"]!!.copy(maxKhz = 1800000)))
        val cur = stock
        val p = Planner.plan(sam, cur, Settings(cpu = mapOf("cpu4" to ClusterSetting(maxKhz = 2100000))), stock)
        assertEquals(1, p.aboveStock.size)
        assertTrue(p.aboveStock[0].contains("2100"))
    }

    @Test fun maliWrites() {
        val g = Settings(gpu = GpuSetting(governor = "Booster", minMhz = 420, maxMhz = 600))
        val p = Planner.plan(sam, samNow, g, samNow)
        val byLabel = p.ops.associateBy { it.label }
        assertEquals("3", byLabel["GPU governor"]!!.value)
        assertEquals(Verify.MALI_GOV, byLabel["GPU governor"]!!.verify)
        assertEquals("Booster", byLabel["GPU governor"]!!.expect)
        assertEquals("420", byLabel["GPU min"]!!.value)
        assertEquals("600", byLabel["GPU max"]!!.value)
        assertTrue(byLabel["GPU max"]!!.path.endsWith("dvfs_max_lock"))
        // revert unlocks with -1
        assertEquals(setOf("Default", "Interactive").size, 2)
        val rev = p.revertOps.associateBy { it.label }
        assertEquals("-1", rev["GPU max"]!!.value)
        assertEquals("-1", rev["GPU min"]!!.value)
        assertEquals("1", rev["GPU governor"]!!.value)   // Interactive is index 1
        // setting max back to table max is an unlock
        val back = Planner.plan(sam, p.after, Settings(gpu = GpuSetting(maxMhz = 772)), samNow)
        assertEquals("-1", back.ops.single().value)
    }

    @Test fun kgslWritesRawHz() {
        val p = Planner.plan(moto, motoNow, Settings(gpu = GpuSetting(maxMhz = 400)), motoNow)
        assertEquals("400000000", p.ops.single().value)
        assertTrue(p.ops.single().path.endsWith("devfreq/max_freq"))
        val bad = Planner.plan(moto, motoNow, Settings(gpu = GpuSetting(maxMhz = 9999)), motoNow)
        assertTrue(bad.ops.isEmpty())   // clamped to 650 = already set
    }

    @Test fun ioScheduler() {
        val p = Planner.plan(sam, samNow, Settings(io = mapOf("sda" to "noop", "sdb" to "bfq", "nope" to "noop")), samNow)
        assertEquals(1, p.ops.size)
        assertEquals(Verify.SCHED, p.ops[0].verify)
        assertEquals(2, p.warnings.size)
    }

    @Test fun memoryOverridesOnlyForOptedInWritableItems() {
        val p = Planner.plan(moto, motoNow, Settings(mem = mapOf("swappiness" to 500L, "zram_disksize" to 1L)), motoNow)
        assertEquals("200", p.ops.single().value)
        assertEquals(1, p.warnings.size)
        // an empty profile never touches memory
        assertTrue(Planner.plan(moto, motoNow, Settings(), motoNow).ops.isEmpty())
    }

    @Test fun stepDownMovesOneNotch() {
        val s = Settings(cpu = mapOf("cpu4" to ClusterSetting(maxKhz = 2100000)), gpu = GpuSetting(maxMhz = 772))
        val (n, notes) = StepDown.apply(sam, s)
        assertEquals(2000000, n.cpu["cpu4"]!!.maxKhz)
        assertEquals(700, n.gpu!!.maxMhz)
        assertEquals(2, notes.size)
        // at the bottom of the table nothing changes
        val (m, _) = StepDown.apply(sam, Settings(cpu = mapOf("cpu4" to ClusterSetting(maxKhz = 800000))))
        assertEquals(800000, m.cpu["cpu4"]!!.maxKhz)
    }

    @Test fun diffFromStock() {
        assertTrue(Planner.diffFromStock(samNow, samNow).isEmpty())
        val p = Planner.plan(sam, samNow, Settings(io = mapOf("sda" to "noop")), samNow)
        assertEquals(listOf("io sda"), Planner.diffFromStock(samNow, p.after))
    }
}
