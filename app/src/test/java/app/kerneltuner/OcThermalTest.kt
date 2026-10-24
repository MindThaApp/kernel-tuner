package app.kerneltuner

import app.kerneltuner.core.*
import app.kerneltuner.hw.*
import org.junit.Assert.*
import org.junit.Test

class OcThermalTest {
    private val samFs = OcFixtures.samsungOc()
    private val motoFs = OcFixtures.motoOc()
    private val sam = HardwareDetector.detect(samFs)
    private val moto = HardwareDetector.detect(motoFs)
    private val samNow = Snapshot.read(samFs, sam)
    private val motoNow = Snapshot.read(motoFs, moto)

    private fun allOpsWhitelisted(p: Plan) = (p.ops + p.revertOps).all { WriteWhitelist.allows(it) }

    // ---- Samsung CPU: steps come from time_in_state ----
    @Test fun samsungOcStepsFromTimeInState() {
        val little = sam.clusters[0]; val big = sam.clusters[1]
        assertEquals(2000000, little.freqs.last())
        assertTrue(1700000 in little.freqs.map { it / 1000 * 1000 } || 1704000 in little.freqs)
        assertEquals(listOf(2200000, 2304000, 2400000, 2496000), big.freqs.filter { it > 2100000 })
        assertEquals(2496000, big.hwMaxKhz)
        // boot limits are the stock ones
        assertEquals(2100000, samNow.cpu["cpu4"]!!.maxKhz)
        assertEquals(1500000, samNow.cpu["cpu0"]!!.maxKhz)
    }

    @Test fun samsungLabels() {
        val little = sam.clusters[0]; val big = sam.clusters[1]
        assertEquals(OcLevel.STOCK, OcInfo.cpuLevel(big, 2100000))
        assertEquals(OcLevel.OVERCLOCK, OcInfo.cpuLevel(big, 2200000))
        assertEquals(OcLevel.OVERCLOCK, OcInfo.cpuLevel(big, 2304000))
        assertEquals(OcLevel.UNSTABLE, OcInfo.cpuLevel(big, 2400000))
        assertEquals(OcLevel.UNSTABLE, OcInfo.cpuLevel(big, 2496000))
        assertEquals(OcLevel.STOCK, OcInfo.cpuLevel(little, 1500000))
        assertEquals(OcLevel.OVERCLOCK, OcInfo.cpuLevel(little, 1600000))
        assertEquals(OcLevel.OVERCLOCK, OcInfo.cpuLevel(little, 1704000))
        for (f in listOf(1800000, 1900000, 2000000)) assertEquals(OcLevel.UNSTABLE, OcInfo.cpuLevel(little, f))
    }

    @Test fun samsungOcMaxIsFlaggedAndRevertible() {
        val t = Settings(cpu = mapOf("cpu4" to ClusterSetting(maxKhz = 2400000)))
        val p = Planner.plan(sam, samNow, t, samNow)
        assertEquals(4, p.ops.size)
        assertTrue(p.aboveStock.single().contains("likely unstable"))
        assertTrue(p.revertOps.all { it.value == "2100000" })
        assertTrue(allOpsWhitelisted(p))
    }

    // ---- Moto CPU ----
    @Test fun motoOcStepsAndLabels() {
        val c = moto.clusters.single()
        assertEquals(2208000, c.hwMaxKhz)
        assertEquals(listOf(2073600, 2112000, 2150400, 2208000), c.freqs.filter { it > 2016000 })
        assertEquals(2016000, motoNow.cpu["cpu0"]!!.maxKhz)
        assertEquals(OcLevel.STOCK, OcInfo.cpuLevel(c, 2016000))
        assertEquals(OcLevel.OVERCLOCK, OcInfo.cpuLevel(c, 2073600))
        assertEquals(OcLevel.OVERCLOCK, OcInfo.cpuLevel(c, 2112000))
        assertEquals(OcLevel.UNSTABLE, OcInfo.cpuLevel(c, 2150400))
        assertEquals(OcLevel.UNSTABLE, OcInfo.cpuLevel(c, 2208000))
        val p = Planner.plan(moto, motoNow, Settings(cpu = mapOf("cpu0" to ClusterSetting(maxKhz = 2150400))), motoNow)
        assertEquals(1, p.ops.size)
        assertTrue(p.aboveStock.single().contains("overclock, likely unstable"))
        assertTrue(allOpsWhitelisted(p))
    }

    @Test fun overclockIsJudgedAgainstVendorCapEvenWithoutSnapshot() {
        val c = sam.clusters[1]
        // cluster never seen at boot: stock map empty
        val p = Planner.plan(sam, samNow, Settings(cpu = mapOf("cpu4" to ClusterSetting(maxKhz = 2200000))), Settings())
        assertEquals(1, p.aboveStock.size)
        assertEquals(2100000, OcInfo.cpuEffectiveStock(c, null))
    }

    // ---- Samsung GPU ----
    @Test fun samsungGpuGetsOcStepAndLimitState() {
        val g = sam.gpu!!
        assertEquals(WriteWhitelist.GPU_LIMIT_NODE, g.limitPath)
        assertEquals(852, g.maxMhz)
        val s = GpuDetector.readState(samFs, g)
        assertEquals(700, s.maxMhz)
        assertEquals(700, s.ceilingMhz)
        assertEquals(OcLevel.OVERCLOCK, OcInfo.gpuLevel(g, 852))
        assertEquals(OcLevel.STOCK, OcInfo.gpuLevel(g, 772))
    }

    @Test fun samsungGpu852WritesLimitNodeFirstThenUnlocks() {
        val t = Settings(gpu = GpuSetting(maxMhz = 852))
        val p = Planner.plan(sam, samNow, t, samNow)
        assertEquals(listOf(WriteWhitelist.GPU_LIMIT_NODE, "/sys/devices/14ac0000.mali/dvfs_max_lock"), p.ops.map { it.path })
        assertEquals(listOf("852", "-1"), p.ops.map { it.value })
        assertTrue(p.aboveStock.single().contains("852"))
        // revert goes the other way and returns the node to 700
        assertEquals(listOf("-1", "700"), p.revertOps.map { it.value })
        assertEquals(WriteWhitelist.GPU_LIMIT_NODE, p.revertOps.last().path)
        assertEquals(852, p.after.gpu!!.ceilingMhz)
        assertTrue(allOpsWhitelisted(p))
    }

    @Test fun samsungGpu772AndStock() {
        val p772 = Planner.plan(sam, samNow, Settings(gpu = GpuSetting(maxMhz = 772)), samNow)
        assertEquals(listOf("772", "772"), p772.ops.map { it.value })
        // 700 means stock: nothing to write from the stock state
        assertTrue(Planner.plan(sam, samNow, Settings(gpu = GpuSetting(maxMhz = 700)), samNow).ops.isEmpty())
        // lowering below stock does not touch the node
        val low = Planner.plan(sam, samNow, Settings(gpu = GpuSetting(maxMhz = 600)), samNow)
        assertEquals(listOf("/sys/devices/14ac0000.mali/dvfs_max_lock"), low.ops.map { it.path })
        assertTrue(low.aboveStock.isEmpty())
    }

    @Test fun gpuNodeNeverAppearsOnStockSamsungKernel() {
        val stockSam = HardwareDetector.detect(Fixtures.fs("samsung"))
        assertNull(stockSam.gpu!!.limitPath)
        assertEquals(772, stockSam.gpu!!.maxMhz)
    }

    // ---- Moto GPU ----
    @Test fun motoGpuUsesMaxPwrlevel() {
        val g = moto.gpu!!
        assertEquals(725, g.maxMhz)
        assertNotNull(g.pwrlevelPath)
        val s = GpuDetector.readState(motoFs, g)
        assertEquals(650, s.maxMhz)           // max_pwrlevel 2 caps the effective maximum
        assertEquals(650, s.ceilingMhz)
        assertEquals(OcLevel.OVERCLOCK, OcInfo.gpuLevel(g, 700))
        assertEquals(OcLevel.OVERCLOCK, OcInfo.gpuLevel(g, 725))
        assertEquals(OcLevel.STOCK, OcInfo.gpuLevel(g, 650))
        val p = Planner.plan(moto, motoNow, Settings(gpu = GpuSetting(maxMhz = 725)), motoNow)
        assertEquals(listOf("/sys/class/kgsl/kgsl-3d0/max_pwrlevel", "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq"), p.ops.map { it.path })
        assertEquals("0", p.ops[0].value)
        val p700 = Planner.plan(moto, motoNow, Settings(gpu = GpuSetting(maxMhz = 700)), motoNow)
        assertEquals("1", p700.ops[0].value)
        // reset lowers the max file first, then the level
        assertEquals(listOf("devfreq/max_freq", "max_pwrlevel"), p.revertOps.map { it.path.substringAfter("kgsl-3d0/") })
        assertEquals("2", p.revertOps.last().value)
        assertTrue(allOpsWhitelisted(p) && allOpsWhitelisted(p700))
    }

    @Test fun motoGpuStockStateNeedsNoWrites() {
        assertTrue(Planner.plan(moto, motoNow, Settings(gpu = GpuSetting(maxMhz = 650)), motoNow).ops.isEmpty())
    }

    @Test fun motoGpuWithoutPwrlevelNodeKeepsOldBehaviour() {
        val fs = Fixtures.fs("moto")
        val hw = HardwareDetector.detect(fs)
        assertNull(hw.gpu!!.pwrlevelPath)
        val now = Snapshot.read(fs, hw)
        val p = Planner.plan(hw, now, Settings(gpu = GpuSetting(maxMhz = 400)), now)
        assertEquals(1, p.ops.size)
        assertTrue(p.ops[0].path.endsWith("devfreq/max_freq"))
    }

    // ---- offline big cluster ----
    private fun offlineBig() = samFs.plus(emptyMap(), (4..7).map { "/sys/devices/system/cpu/cpu$it/cpufreq" }.toSet())

    @Test fun hotpluggedBigClusterComesFromCache() {
        val fs = offlineBig()
        val noCache = HardwareDetector.detect(fs)
        assertEquals(1, noCache.clusters.size)
        val cached = HardwareDetector.detect(fs, sam.clusters)
        assertEquals(2, cached.clusters.size)
        val big = cached.clusters[1]
        assertEquals("cpu4", big.id)
        assertEquals(4, big.paths.size)
        assertEquals(2496000, big.hwMaxKhz)
        val snap = Snapshot.read(fs, cached)
        assertNull(snap.cpu["cpu4"])
        assertNotNull(snap.cpu["cpu0"])
    }

    @Test fun partlyOnlineClusterStillWritesToEveryMember() {
        val fs = samFs.plus(emptyMap(), setOf(5, 6, 7).map { "/sys/devices/system/cpu/cpu$it/cpufreq" }.toSet())
        val hw = HardwareDetector.detect(fs, sam.clusters)
        assertEquals(4, hw.clusters[1].paths.size)
        val now = Snapshot.read(fs, hw)
        val p = Planner.plan(hw, now, Settings(cpu = mapOf("cpu4" to ClusterSetting(maxKhz = 2200000))), now)
        assertEquals(4, p.ops.size)
    }

    @Test fun offlineClusterOverclockStillNeedsConfirmation() {
        val hw = HardwareDetector.detect(offlineBig(), sam.clusters)
        val now = Snapshot.read(offlineBig(), hw)
        val p = Planner.plan(hw, now, Settings(cpu = mapOf("cpu4" to ClusterSetting(maxKhz = 2400000))), now)
        assertEquals(1, p.aboveStock.size)
        assertEquals(setOf("cpu4"), p.touchedCpu)
    }

    // ---- reconcile ----
    @Test fun reconcilePutsMaxBackWhenCoreReturnsWithDefaults() {
        val held = Settings(cpu = mapOf("cpu4" to ClusterSetting(null, 800000, 2400000)))
        // cpu4 and cpu5 are back with the default 2100000; cpu6/7 are still offline
        val fs = samFs.plus(emptyMap(), setOf(6, 7).map { "/sys/devices/system/cpu/cpu$it/cpufreq" }.toSet())
        val ops = Reconciler.fixups(sam, held, fs)
        assertEquals(setOf("cpu4", "cpu5"), ops.map { it.path.substringAfter("cpu/").substringBefore("/") }.toSet())
        assertTrue(ops.all { it.path.endsWith("scaling_max_freq") && it.value == "2400000" })
        assertTrue(ops.all { WriteWhitelist.allows(it) })
    }

    @Test fun reconcileDoesNothingWhenValuesStuck() {
        val m = (4..7).associate { "/sys/devices/system/cpu/cpu$it/cpufreq/scaling_max_freq" to "2400000" }
        assertTrue(Reconciler.fixups(sam, Settings(cpu = mapOf("cpu4" to ClusterSetting(null, 800000, 2400000))), samFs.plus(m)).isEmpty())
    }

    @Test fun reconcileRestoresIpaAfterPowerHalReset() {
        val held = Settings(temp = mapOf("ipa" to 55))
        val ops = Reconciler.fixups(sam, held, samFs)   // PowerHAL left it at 63
        assertEquals(listOf("55"), ops.map { it.value })
        assertEquals("/sys/power/ipa/control_temp", ops.single().path)
        assertTrue(Reconciler.fixups(sam, held, samFs.plus(mapOf("/sys/power/ipa/control_temp" to "55"))).isEmpty())
    }

    @Test fun reconcileMinNeverAboveMax() {
        val held = Settings(cpu = mapOf("cpu4" to ClusterSetting(null, 2000000, 2100000)))
        val fs = samFs.plus((4..7).associate { "/sys/devices/system/cpu/cpu$it/cpufreq/scaling_max_freq" to "1500000" })
        val ops = Reconciler.fixups(sam, held, fs).filter { it.path.contains("cpu4/") }
        assertTrue(ops.indexOfFirst { it.path.endsWith("max_freq") } < ops.indexOfFirst { it.path.endsWith("min_freq") })
    }

    @Test fun heldStateDropsEntriesBackAtStock() {
        val t = Settings(cpu = mapOf("cpu4" to ClusterSetting(maxKhz = 2400000)), temp = mapOf("ipa" to 55))
        val p = Planner.plan(sam, samNow, t, samNow)
        val held = Reconciler.update(null, p, p.touchedCpu, p.touchedTemp, samNow)
        assertEquals(2400000, held.cpu["cpu4"]!!.maxKhz)
        assertEquals(55, held.temp["ipa"])
        val back = Planner.plan(sam, p.after, samNow.copy(io = emptyMap(), mem = emptyMap(), gpu = null), samNow)
        assertTrue(Reconciler.update(held, back, back.touchedCpu, back.touchedTemp, samNow).isEmpty)
    }

    // ---- temperature limits ----
    @Test fun samsungIpaGroup() {
        val g = sam.tempGroups.single()
        assertEquals("ipa", g.id)
        assertEquals(63, samNow.temp["ipa"])
        val p = Planner.plan(sam, samNow, Settings(temp = mapOf("ipa" to 50)), samNow)
        assertEquals("50", p.ops.single().value)
        assertTrue(p.aboveStock.isEmpty())
        assertEquals("63", p.revertOps.single().value)
        assertTrue(allOpsWhitelisted(p))
    }

    @Test fun samsungIpaAboveStockIsFlaggedAndClamped() {
        val p = Planner.plan(sam, samNow, Settings(temp = mapOf("ipa" to 120)), samNow)
        assertEquals("75", p.ops.single().value)
        assertEquals(1, p.aboveStock.size)
        val low = Planner.plan(sam, samNow, Settings(temp = mapOf("ipa" to 5)), samNow)
        assertEquals("45", low.ops.single().value)
    }

    @Test fun motoTripGroupsAreCpuAndGpu() {
        assertEquals(listOf("cpu_trip", "gpu_trip"), moto.tempGroups.map { it.id })
        assertEquals(8, moto.tempGroups[0].paths.size)
        assertEquals(1, moto.tempGroups[1].paths.size)
        assertEquals(mapOf("cpu_trip" to 105, "gpu_trip" to 95), motoNow.temp)
    }

    @Test fun motoTripsCanOnlyBeLowered() {
        for (want in listOf(0, 40, 70, 85, 104, 105, 106, 150, 200, 10000)) {
            val p = Planner.plan(moto, motoNow, Settings(temp = mapOf("cpu_trip" to want, "gpu_trip" to want)), motoNow)
            assertTrue("want $want", p.aboveStock.isEmpty())
            for (op in p.ops) {
                val v = op.value.toInt()
                val cap = if (op.path.contains("zone30")) 95000 else 105000
                assertTrue("want $want -> ${op.value}", v <= cap && v >= 65000)
                assertTrue(WriteWhitelist.allows(op))
            }
        }
        val p = Planner.plan(moto, motoNow, Settings(temp = mapOf("cpu_trip" to 85)), motoNow)
        assertEquals(8, p.ops.size)
        assertTrue(p.ops.all { it.value == "85000" && it.path.endsWith("trip_point_0_temp") })
        assertTrue(p.revertOps.all { it.value == "105000" })
        // asking for stock or higher is a no-op, never a raise
        assertTrue(Planner.plan(moto, motoNow, Settings(temp = mapOf("cpu_trip" to 200)), motoNow).ops.isEmpty())
    }

    @Test fun motoTripLimitStaysLimitedByBootStockEvenIfLowerThanKernelCap() {
        val lowered = Settings(temp = mapOf("cpu_trip" to 90, "gpu_trip" to 95))
        val p = Planner.plan(moto, motoNow, Settings(temp = mapOf("cpu_trip" to 100)), lowered)
        assertTrue(p.ops.all { it.value == "90000" } || p.ops.isEmpty())
    }

    @Test fun redetectionAfterLoweringStillFindsTripZones() {
        val lowered = motoFs.plus((22..29).associate { "/sys/class/thermal/thermal_zone$it/trip_point_0_temp" to "80000" })
        assertEquals(8, HardwareDetector.detect(lowered).tempGroups[0].paths.size)
    }

    @Test fun noTempGroupsOnStockFixtures() {
        assertTrue(HardwareDetector.detect(Fixtures.fs("moto")).tempGroups.isEmpty())
        assertTrue(HardwareDetector.detect(Fixtures.fs("samsung")).tempGroups.isEmpty())
    }

    // ---- write whitelist ----
    @Test fun whitelistAcceptsOnlyKnownNodesAndValues() {
        assertTrue(WriteWhitelist.allows("/sys/kernel/gpu/gpu_max_clock_limit", "852"))
        assertTrue(WriteWhitelist.allows("/sys/kernel/gpu/gpu_max_clock_limit", "700"))
        assertFalse(WriteWhitelist.allows("/sys/kernel/gpu/gpu_max_clock_limit", "900"))
        assertFalse(WriteWhitelist.allows("/sys/kernel/gpu/gpu_max_clock_limit", "0"))
        assertFalse(WriteWhitelist.allows("/sys/kernel/gpu/gpu_max_clock", "772"))
        assertFalse(WriteWhitelist.allows("/sys/kernel/gpu/gpu_min_clock", "266"))
        assertTrue(WriteWhitelist.allows("/sys/power/ipa/control_temp", "63"))
        assertFalse(WriteWhitelist.allows("/sys/power/ipa/control_temp", "120"))
        assertFalse(WriteWhitelist.allows("/sys/power/ipa/tdp", "5000"))
        assertTrue(WriteWhitelist.allows("/sys/class/thermal/thermal_zone22/trip_point_0_temp", "85000"))
        assertFalse(WriteWhitelist.allows("/sys/class/thermal/thermal_zone22/trip_point_0_temp", "106000"))
        assertFalse(WriteWhitelist.allows("/sys/class/thermal/thermal_zone22/trip_point_1_temp", "85000"))
        assertFalse(WriteWhitelist.allows("/sys/class/thermal/thermal_zone22/mode", "disabled"))
        assertTrue(WriteWhitelist.allows("/sys/class/kgsl/kgsl-3d0/max_pwrlevel", "1"))
        assertFalse(WriteWhitelist.allows("/sys/class/kgsl/kgsl-3d0/max_pwrlevel", "99"))
        assertTrue(WriteWhitelist.allows("/sys/devices/system/cpu/cpu4/cpufreq/scaling_max_freq", "2400000"))
        assertFalse(WriteWhitelist.allows("/sys/devices/system/cpu/cpu4/online", "0"))
        assertFalse(WriteWhitelist.allows("/sys/devices/system/cpu/cpu4/cpufreq/scaling_max_freq", "1; reboot"))
        assertFalse(WriteWhitelist.allows("/data/local/tmp/x", "1"))
        assertFalse(WriteWhitelist.allows("/sys/block/sda/queue/../../../x", "1"))
    }

    @Test fun everyPlannedWriteOnBothOcDevicesPassesTheWhitelist() {
        val targets = listOf(
            Settings(cpu = mapOf("cpu4" to ClusterSetting("interactive", 800000, 2496000), "cpu0" to ClusterSetting(null, 400000, 2000000)),
                gpu = GpuSetting("Interactive", 266, 852), io = mapOf("sda" to "noop"), mem = mapOf("swappiness" to 60L), temp = mapOf("ipa" to 70)),
            Settings(cpu = mapOf("cpu4" to ClusterSetting(null, 800000, 800000)), gpu = GpuSetting(null, 266, 266), temp = mapOf("ipa" to 45)),
        )
        for (t in targets) assertTrue(allOpsWhitelisted(Planner.plan(sam, samNow, t, samNow)))
        val mt = Settings(cpu = mapOf("cpu0" to ClusterSetting("performance", 652800, 2208000)), gpu = GpuSetting("msm-adreno-tz", 133, 725), temp = mapOf("cpu_trip" to 80, "gpu_trip" to 70))
        assertTrue(allOpsWhitelisted(Planner.plan(moto, motoNow, mt, motoNow)))
    }

    // ---- presets ----
    @Test fun presetsStayInsideStockRange() {
        for ((hw, now) in listOf(sam to samNow, moto to motoNow)) {
            for (preset in Preset.values()) {
                val s = Presets.build(hw, now, preset)
                for ((id, c) in s.cpu) {
                    val cl = hw.clusters.first { it.id == id }
                    assertTrue("$preset $id", (c.maxKhz ?: 0) <= OcInfo.cpuEffectiveStock(cl, now.cpu[id]?.maxKhz))
                    assertEquals(OcLevel.STOCK, OcInfo.cpuLevel(cl, c.maxKhz!!, now.cpu[id]?.maxKhz))
                    assertTrue(c.minKhz!! <= c.maxKhz!!)
                }
                s.gpu?.let { assertTrue("$preset gpu", it.maxMhz!! <= now.gpu!!.maxMhz!!) }
                for ((id, c) in s.temp) assertTrue("$preset $id", c <= now.temp[id]!!)
                // and the planner agrees: nothing above stock, nothing outside the whitelist
                val p = Planner.plan(hw, now, s, now)
                assertTrue("$preset", p.aboveStock.isEmpty())
                assertTrue(allOpsWhitelisted(p))
            }
        }
    }

    @Test fun balancedIsStockAndBatteryIsLower() {
        for ((hw, now) in listOf(sam to samNow, moto to motoNow)) {
            assertTrue(Planner.plan(hw, now, Presets.build(hw, now, Preset.BALANCED), now).ops.isEmpty())
            val b = Presets.build(hw, now, Preset.BATTERY)
            for ((id, c) in b.cpu) assertTrue(c.maxKhz!! < now.cpu[id]!!.maxKhz!!)
            assertTrue(b.gpu!!.maxMhz!! < now.gpu!!.maxMhz!!)
            for ((id, c) in b.temp) assertTrue(c < now.temp[id]!!)
            val perf = Presets.build(hw, now, Preset.PERFORMANCE)
            for ((id, c) in perf.cpu) assertEquals(now.cpu[id]!!.maxKhz, c.maxKhz)
            for ((id, c) in perf.temp) assertEquals(now.temp[id], c)
        }
    }

    @Test fun settingsWithoutTempStillDecodeFromOlderProfiles() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val s = json.decodeFromString(Settings.serializer(), """{"cpu":{},"gpu":{"maxMhz":700}}""")
        assertTrue(s.temp.isEmpty())
        assertNull(s.gpu!!.ceilingMhz)
    }
}
