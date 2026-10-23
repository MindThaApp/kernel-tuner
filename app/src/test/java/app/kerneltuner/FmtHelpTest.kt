package app.kerneltuner

import app.kerneltuner.core.Fmt
import app.kerneltuner.core.Help
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class FmtHelpTest {
    @Test fun frequencies() {
        assertEquals("1.5 GHz", Fmt.khz(1_500_000))
        assertEquals("2.02 GHz", Fmt.khz(2_016_000))
        assertEquals("2.1 GHz", Fmt.khz(2_100_000))
        assertEquals("653 MHz", Fmt.khz(652_800))
        assertEquals("400 MHz", Fmt.khz(400_000))
        assertEquals("772 MHz", Fmt.mhz(772))
        assertEquals("1.05 GHz", Fmt.mhz(1050))
        assertEquals("-", Fmt.khz(null))
    }

    @Test fun temperatureAndSizes() {
        assertEquals("34.5 °C", Fmt.celsius(34.5))
        assertEquals("Cool", Fmt.heat(30.0))
        assertEquals("Warm", Fmt.heat(40.0))
        assertEquals("Hot", Fmt.heat(50.0))
        assertEquals("Very hot", Fmt.heat(60.0))
        assertEquals("1.8 GB", Fmt.bytes(1_932_735_283L))
        assertEquals("512 MB", Fmt.mem(512 * 1024L))
        assertEquals("3:00", Fmt.countdown(180))
    }

    @Test fun clusterNames() {
        val maxes = mapOf("cpu0" to 1_500_000, "cpu4" to 2_100_000)
        assertTrue(Help.clusterTitle("cpu0", maxes, listOf(0, 1, 2, 3)).startsWith("Efficiency"))
        assertTrue(Help.clusterTitle("cpu4", maxes, listOf(4, 5, 6, 7)).startsWith("Performance"))
        assertEquals("CPU (cores 0-7)", Help.clusterTitle("cpu0", mapOf("cpu0" to 2_016_000), (0..7).toList()))
    }

    @Test fun everyGovernorAndMemoryKeyHasText() {
        listOf("schedutil", "interactive", "ondemand", "conservative", "performance", "powersave", "msm-adreno-tz", "Default", "Booster", "Static").forEach {
            assertTrue(Help.governor(it, it[0].isUpperCase() || it == "msm-adreno-tz").isNotBlank())
        }
        listOf("swappiness", "dirty_ratio", "dirty_background_ratio", "vfs_cache_pressure", "zram_disksize", "zram_algo", "lmk_minfree").forEach {
            assertTrue(Help.memory(it).subtitle.isNotBlank())
        }
    }
}
