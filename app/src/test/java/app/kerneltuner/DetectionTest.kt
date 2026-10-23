package app.kerneltuner

import app.kerneltuner.hw.*
import org.junit.Assert.*
import org.junit.Test

class DetectionTest {
    private val moto = Fixtures.fs("moto")
    private val sam = Fixtures.fs("samsung")

    @Test fun motoCpuIsSinglePolicyWithTable() {
        val c = CpuDetector.detect(moto)
        assertEquals(1, c.size)
        val p = c[0]
        assertEquals(CpuLayout.POLICY, p.layout)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), p.cpus)
        assertEquals(listOf(652800, 1036800, 1401600, 1689600, 1804800, 1958400, 2016000), p.freqs)
        assertTrue("schedutil" in p.governors)
        assertEquals(2016000, p.hwMaxKhz)
        assertEquals("cpu0", p.id)
    }

    @Test fun samsungCpuIsLegacyTwoClustersFromTimeInState() {
        val c = CpuDetector.detect(sam)
        assertEquals(2, c.size)
        assertTrue(c.all { it.layout == CpuLayout.PER_CPU })
        assertEquals(listOf(0, 1, 2, 3), c[0].cpus)
        assertEquals(listOf(4, 5, 6, 7), c[1].cpus)
        assertEquals(400000, c[0].freqs.first())
        assertEquals(1500000, c[0].freqs.last())
        assertEquals(12, c[0].freqs.size)
        assertEquals(2100000, c[1].freqs.last())
        assertEquals(800000, c[1].freqs.first())
        assertEquals(listOf("userspace", "interactive", "performance"), c[0].governors)
        assertTrue(c[0].paths.all { it.endsWith("/cpufreq") })
    }

    @Test fun frequencyFallbackToCpuinfoWhenNoTables() {
        val fs = object : SysFs by moto {
            override fun read(path: String): String? =
                if (path.endsWith("scaling_available_frequencies") || path.endsWith("time_in_state")) null else moto.read(path)
        }
        assertEquals(listOf(652800, 2016000), CpuDetector.frequencies(fs, "/sys/devices/system/cpu/cpufreq/policy0", 652800, 2016000))
    }

    @Test fun motoGpuIsKgsl() {
        val g = GpuDetector.detect(moto)!!
        assertEquals(GpuKind.KGSL, g.kind)
        assertEquals(7, g.freqs.size)
        assertEquals(133, g.minMhz)
        assertEquals(650, g.maxMhz)
        assertEquals(133330000L, g.freqs.first().raw)
        assertTrue("msm-adreno-tz" in g.governors)
        val s = GpuDetector.readState(moto, g)
        assertEquals("msm-adreno-tz", s.governor)
        assertEquals(650, s.maxMhz)
        assertEquals(49, s.loadPct)
        assertEquals(320, s.curMhz)
    }

    @Test fun samsungGpuIsMali() {
        val g = GpuDetector.detect(sam)!!
        assertEquals(GpuKind.MALI, g.kind)
        assertEquals(listOf(266, 350, 420, 544, 600, 700, 772), g.freqs.map { it.mhz })
        assertEquals(listOf("Default", "Interactive", "Static", "Booster"), g.governors)
        val s = GpuDetector.readState(sam, g)
        assertEquals("Interactive", s.governor)
        assertEquals(266, s.minMhz)   // lock -1 => unlocked => table extremes
        assertEquals(772, s.maxMhz)
    }

    @Test fun maliGovernorParsing() {
        val (n, cur) = GpuDetector.parseMaliGovernors("Default\nInteractive\nStatic\nBooster\n[Current Governor] Static")
        assertEquals(4, n.size)
        assertEquals("Static", cur)
        assertEquals(emptyList<Int>(), GpuDetector.parseMaliTable(null))
    }

    @Test fun thermalPicksRealSensorsOnly() {
        val m = ThermalDetector.detect(moto)
        val types = m.map { it.type }
        assertTrue("apc1-cpu0-usr" in types)
        assertTrue("gpu0-usr" in types)
        assertTrue("battery" in types)
        assertTrue(types.none { it.endsWith("-step") || it.endsWith("-lowf") })
        assertFalse("xo-therm-adc" in types)   // mode=disabled / unreadable
        assertFalse("bms" in types)
        val s = ThermalDetector.detect(sam).map { it.type }
        assertEquals(listOf("exynos-therm", "battery"), s)
    }

    @Test fun thermalUnits() {
        assertEquals(34.0, ThermalDetector.parseTempC("34000")!!, 0.01)
        assertEquals(36.5, ThermalDetector.parseTempC("365")!!, 0.01)
        assertEquals(40.0, ThermalDetector.parseTempC("40")!!, 0.01)
        assertNull(ThermalDetector.parseTempC(""))
        assertEquals(ThermalCategory.GPU, ThermalDetector.categorize("gpu1-usr"))
    }

    @Test fun blockDevices() {
        val m = BlockDetector.detect(moto).map { it.name }
        assertEquals(listOf("mmcblk0", "mmcblk1"), m)   // no rpmb, loop, ram, dm, zram
        val s = BlockDetector.detect(sam)
        assertEquals(listOf("sda", "sdb", "sdc", "sdd"), s.map { it.name })
        assertEquals(listOf("noop", "cfq"), s[0].schedulers)
        assertEquals("cfq", BlockDetector.parseScheduler("noop [cfq] ").second)
        assertEquals("mq-deadline", BlockDetector.parseScheduler("[mq-deadline] kyber none").second)
    }

    @Test fun memoryParsing() {
        val mi = Memory.parseMeminfo(moto.read("/proc/meminfo"))!!
        assertTrue(mi.totalKb > 1_000_000)
        assertTrue(mi.availKb in 1..mi.totalKb)
        assertEquals(100L, moto.read("/proc/sys/vm/swappiness")!!.toLong())
        assertTrue(Memory.items.filter { it.overridable }.all { it.path!!.startsWith("/proc/sys/vm/") })
        assertTrue(Memory.items.filter { it.key.startsWith("zram") || it.key.startsWith("lmk") }.none { it.overridable })
    }

    @Test fun fullDetectionBothDevices() {
        val a = HardwareDetector.detect(moto)
        val b = HardwareDetector.detect(sam)
        assertEquals(8, a.cpuCount)
        assertEquals(8, b.cpuCount)
        assertNotNull(a.gpu); assertNotNull(b.gpu)
        assertNotEquals(a.gpu!!.kind, b.gpu!!.kind)
    }
}
