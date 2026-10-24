package app.kerneltuner.core

/** Plain-English explanations shown under each setting and in the "?" sheets. */
data class Info(
    val title: String,
    /** One line: what it does and the trade-off. */
    val subtitle: String,
    val details: String,
    /** "Safe default" hint, if there is one. */
    val hint: String? = null,
)

object Help {
    val cpuGovernor = Info(
        "CPU governor",
        "Decides how quickly the CPU speeds up. Snappier choices use more battery.",
        "The governor is the rule the kernel follows to pick a CPU speed between the minimum and maximum you set. " +
            "Some react instantly to any load (fast, but warmer and hungrier), some ramp up gradually (smoother battery, slight delay).",
        "Safe default: keep the stock governor. Switch only if you want to test a different feel.",
    )
    val cpuMin = Info(
        "Minimum CPU speed",
        "The slowest the CPU may run. Higher is more responsive but drains battery even when idle.",
        "When nothing is happening the CPU idles at this speed. Raising it makes the phone react a little faster and wastes power. " +
            "Lowering it saves a little battery but the first moment of a touch may feel slower.",
        "Safe default: stock minimum.",
    )
    val cpuMax = Info(
        "Maximum CPU speed",
        "The fastest the CPU may run. Lower saves battery and heat but games and heavy apps get slower.",
        "This is a ceiling. Capping it is the easiest way to run cooler and last longer. " +
            "Steps above the stock maximum are marked \"Overclock\" and only exist on kernels built for it.",
        "Safe default: stock maximum. For battery, try one or two steps below it.",
    )
    val gpuGovernor = Info(
        "GPU governor",
        "Decides how fast the graphics chip ramps up. Snappier means smoother games but more heat.",
        "The GPU draws the screen and runs games. Its governor picks the GPU speed from the current load, between the limits below.",
        "Safe default: keep the stock governor.",
    )
    val gpuMin = Info(
        "Minimum GPU speed",
        "The slowest the graphics chip may run. Raising it can smooth animations but costs battery.",
        "Keeping a higher floor avoids the GPU having to ramp up from slow, which can reduce stutter, at the price of constant extra power.",
        "Safe default: stock minimum.",
    )
    val gpuMax = Info(
        "Maximum GPU speed",
        "The fastest the graphics chip may run. Lower runs cooler but lowers frame rates in games.",
        "A ceiling for graphics speed. Phones often throttle by themselves when the GPU gets hot, so a lower cap can give steadier frame rates in long games.",
        "Safe default: stock maximum.",
    )
    val overclock = Info(
        "Overclock steps",
        "Speeds above what the phone shipped with. Faster, hotter, and may crash. Try them one step at a time.",
        "Steps marked \"Overclock\" are above the stock maximum. They only appear on a kernel that was built to support them, and every chip is different: " +
            "a step that works on one phone can freeze another.\n\n" +
            "Steps marked \"likely unstable\" are the ones the kernel authors expect to fail on many chips, because the voltage they need is higher than the kernel allows.\n\n" +
            "How to try one safely:\n" +
            "1. Go up exactly one step from the last one that worked.\n" +
            "2. Press \"Try for 3 min\", never plain Apply. Use the phone normally, or run a game or benchmark, while it counts down.\n" +
            "3. If it still works when the time is up, you can try the next step.\n\n" +
            "What a crash looks like: the screen freezes, the phone restarts by itself, or it shuts off. Nothing is lost: a restart always returns to stock, and the next start shows a red notice naming what was active. " +
            "A frozen phone can be restarted by holding the power button for about ten seconds.\n\n" +
            "It reverts by itself: when the test time ends everything goes back to stock unless you tap Keep. Overclocked values are always forced into test mode, and Apply on boot refuses a profile that crashed.",
        "Tip: also check the Temperature limit below. A hotter limit plus an overclock is the fastest way to find a step's edge.",
    )
    val tempIpa = Info(
        "Temperature limit",
        "How warm the phone may get before it slows itself down. Lower is cooler and slower; higher is faster for longer and hotter.",
        "This sets the temperature the phone tries to hold the processor and graphics at while you use it. Above it the phone reduces speed little by little.\n\n" +
            "Lowering it keeps the phone cooler and the battery happier at the cost of speed in long games. Raising it lets the phone stay fast longer but it gets hotter. " +
            "This value only shapes the gentle, gradual slowdown. Above roughly 95 \u00B0C it no longer matters: the kernel's own temperature trips (75, 80, 85, 90 and 95 \u00B0C) still slow the chip and protect it, whatever you set. " +
            "The stock value is whatever the phone had when it started (some power modes use 120).\n\n" +
            "Android's power manager rewrites this value when it changes power mode (battery saver 45, normal 63, performance 65). Kernel Tuner puts your value back every few minutes while it is applied, " +
            "and within seconds while the app is open, but the value can briefly return to the system's choice in between. Applying a value above stock counts as an overclock and runs in test mode.",
        "Safe default: stock. Going lower is always safe.",
    )
    val tempTrips = Info(
        "Temperature limit",
        "The temperature at which the kernel starts slowing the processor or graphics. Lower means it slows down earlier and stays cooler.",
        "Your phone's kernel slows the chip when a sensor passes a set temperature (stock: processor 105 \u00B0C, graphics 95 \u00B0C). " +
            "You can only make this stricter than stock: the slider never goes above the stock value, so it cannot let the phone run hotter than the factory limit.\n\n" +
            "Note that Android also has its own thermal service (thermal-engine) which slows the phone earlier, at much lower temperatures, based on skin and battery temperature. " +
            "That is separate and keeps working; a custom module can relax it, this app does not touch it.\n\n" +
            "The value is lost at restart. While it is applied, Kernel Tuner puts it back if something resets it.",
        "Safe default: stock (the far right). Lower it only if the phone gets too hot for you.",
    )
    val tmu = Info(
        "Hardware throttle limits",
        "The five temperatures at which the chip itself is forced to slow down. Moving them up means hotter; down means cooler.",
        "The processor has five built-in temperature steps (stock 75, 80, 85, 90 and 95 \u00B0C). Each step cuts speed harder. This control moves all five together by an offset, " +
            "from 20 \u00B0C stricter to 10 \u00B0C looser, and the advanced section sets each one. The kernel keeps them in ascending order and inside that range.\n\n" +
            "Looser than stock lets the chip get hotter before it is slowed: more heat, faster battery wear and a shorter chip life. It is treated like an overclock: it runs in test mode, " +
            "is recorded by the crash marker, is never applied automatically after an unclean restart, and applies on boot only for a profile you tested and confirmed.\n\n" +
            "It needs a kernel that makes these files writable; on other kernels the control says so and does nothing. Stricter than stock is always safe.",
        "Safe default: stock (offset 0). Try +5 first, never leave it hot unattended.",
    )
    val thermalEngine = Info(
        "Soft throttle (thermal service)",
        "Where Android's thermal service starts slowing the processor. Later means faster for longer and a hotter phone.",
        "Android has a thermal service (thermal-engine) that slows the processor early, based on skin-temperature readings. Its first rule starts at 43 \u00B0C on this phone. " +
            "The thermal-relax module already moves it to 52 \u00B0C. This slider changes only that rule and the harder step above it, keeping their spacing; no other rule is touched.\n\n" +
            "The range is 43 to 65 \u00B0C. A copy of the module's original file is kept, and \"Restore module default\" puts it back. The kernel's own limits (105 \u00B0C processor, 95 \u00B0C graphics) still apply, and the rule that protects the battery stays.\n\n" +
            "Above the module default counts as above stock: it runs in test mode and, at every restart, the file is put back unless a confirmed profile applies it after the 90 s delay.",
        "Safe default: the module default.",
    )
    val presets = Info(
        "Quick presets",
        "One-tap starting points that stay inside the stock range.",
        "Presets fill in the controls below; nothing is applied until you press \"Try\".\n\n" +
            "Battery saver: top speeds about 60% of stock and a cooler temperature limit.\n" +
            "Balanced (stock): exactly what the phone had at boot.\n" +
            "Performance: stock top speeds with a higher idle floor, for a snappier feel. It does not overclock and does not raise the temperature limit.",
        "Safe default: Balanced.",
    )

    val ioScheduler = Info(
        "Storage scheduler",
        "Decides the order storage reads and writes are done. Mostly affects app-loading smoothness.",
        "When many apps use storage at once, the scheduler orders their requests. Simple ones (noop, none) have the least overhead on fast flash storage. " +
            "Fairer ones (cfq, bfq) can feel smoother when several things run at once but use slightly more CPU.",
        "Safe default: stock choice. Differences are small on modern phones.",
    )
    val swappiness = Info(
        "Swappiness",
        "How eagerly Android moves idle app memory into compressed RAM. Higher keeps more apps alive but uses more CPU.",
        "Compressed RAM (zram) acts like extra memory. A high value pushes idle data there sooner, so more apps stay open in the background, at the cost of some CPU time. " +
            "A low value keeps data in normal RAM, so there is less compression work but apps may be closed sooner.",
        "Safe default: leave it alone. The ROM chose this value on purpose.",
    )
    val dirtyRatio = Info(
        "Write cache limit",
        "How much unsaved data may pile up in RAM before apps must wait for storage. Higher is faster but riskier on power loss.",
        "Writes are first collected in RAM and flushed to storage later. This is the point at which a busy app is forced to wait for the flush (percent of RAM).",
        "Safe default: leave it alone.",
    )
    val dirtyBg = Info(
        "Background write start",
        "How much unsaved data collects before Android quietly starts saving it. Lower saves sooner, with more frequent small writes.",
        "When this share of RAM holds unsaved data, a background flush begins. It must stay below the write cache limit.",
        "Safe default: leave it alone.",
    )
    val vfsCache = Info(
        "File cache pressure",
        "How quickly Android forgets the file lists it remembers. Lower keeps them longer for faster file access.",
        "100 is balanced. Lower values keep directory and file information in memory longer, which makes file browsing quicker but holds on to memory. Higher values free it sooner.",
        "Safe default: leave it alone.",
    )
    val zramSize = Info("Compressed RAM size", "Size of the compressed-RAM swap area. Read-only here.", "This is set by the ROM when the phone boots and cannot be changed safely while running.")
    val zramAlgo = Info("Compression method", "How compressed RAM squeezes data. Read-only here.", "Fast methods (lz4) use little CPU; stronger ones (zstd) save more memory but use more CPU. Fixed at boot by the ROM.")
    val lmk = Info("Background app killer", "Android's rules for closing background apps when memory runs low. Read-only here.", "Controlled by the ROM at boot. Changing it at runtime can cause lag or apps being killed unexpectedly, so it is shown for information only.")

    fun memory(key: String): Info = when (key) {
        "swappiness" -> swappiness
        "dirty_ratio" -> dirtyRatio
        "dirty_background_ratio" -> dirtyBg
        "vfs_cache_pressure" -> vfsCache
        "zram_disksize" -> zramSize
        "zram_algo" -> zramAlgo
        else -> lmk
    }

    /** Friendly names for the raw memory keys. */
    fun memoryTitle(key: String, fallback: String): String = when (key) {
        "swappiness" -> "Swappiness"
        "dirty_ratio" -> "Write cache limit"
        "dirty_background_ratio" -> "Background write start"
        "vfs_cache_pressure" -> "File cache pressure"
        "zram_disksize" -> "Compressed RAM size"
        "zram_algo" -> "Compression method"
        "lmk_minfree" -> "Background app killer levels"
        "lmk_psi", "lmk_heaviest", "lmk_use_minfree" -> "Background app killer ($fallback)"
        else -> fallback
    }

    val applyOnBoot = Info(
        "Apply on boot",
        "Re-applies one chosen profile after every restart. Off by default.",
        "Normally every change disappears when the phone restarts, which is the safest behaviour. Apply on boot lets one profile come back automatically. " +
            "If the phone ever restarts unexpectedly while a profile was active, Kernel Tuner will not apply it again and will tell you, so a bad setting cannot trap you in a boot loop.",
        "Recommended: test a profile for a few days with Test mode first, then turn this on.",
    )
    val testMode = Info(
        "Test mode",
        "Try settings for a few minutes; they undo themselves unless you tap Keep.",
        "Test mode applies your changes and starts a countdown. When it ends, everything goes back to the values from boot. " +
            "Tap Keep to keep them until the next restart. If the phone freezes or restarts, the changes are gone anyway and nothing is re-applied automatically.",
        "Recommended: always use Test mode first.",
    )

    fun governor(name: String, gpu: Boolean): String = when (name.lowercase()) {
        "schedutil" -> "Follows the scheduler's load estimate. Smooth and efficient; the modern default."
        "interactive" -> if (gpu) "Raises GPU speed quickly when load appears. Snappy, warmer." else "Jumps to a higher speed as soon as you touch the screen. Snappy, warmer."
        "ondemand" -> "Speeds up when load passes a threshold, slows down when idle. Balanced."
        "conservative" -> "Ramps up and down in small steps. Saves battery, feels slower to react."
        "powersave" -> "Always stays at the minimum speed. Longest battery, slowest."
        "performance" -> "Always runs at the maximum speed. Fastest, hottest, drains battery."
        "userspace" -> "Speed is set by another program. Not useful for normal use."
        "msm-adreno-tz" -> "The built-in Qualcomm GPU governor. Matches speed to load; stock default."
        "simple_ondemand" -> "Raises GPU speed when it is busy, lowers it when idle. Balanced."
        "default" -> "Samsung's standard GPU behaviour. Balanced; stock default."
        "static" -> "Keeps the GPU at one fixed speed. Steady but wastes power when idle."
        "booster" -> "Boosts the GPU more aggressively. Smoother games, hotter and thirstier."
        else -> if (gpu) "A GPU speed rule provided by the kernel." else "A CPU speed rule provided by the kernel."
    }

    fun scheduler(name: String): String = when (name.lowercase()) {
        "noop", "none" -> "No reordering. Lowest overhead; good for fast flash storage."
        "cfq" -> "Shares storage fairly between apps. Smooth with many apps, slightly more overhead."
        "deadline", "mq-deadline" -> "Makes sure no request waits too long. Good all-rounder."
        "bfq" -> "Fair sharing tuned for responsiveness. Smooth, slightly more CPU."
        "kyber" -> "Lightweight, aims at low delay on fast storage."
        "row" -> "Favours reads over writes. Quick app opening."
        "fiops", "sio", "zen", "maple" -> "Simple low-overhead scheduler favouring quick reads."
        else -> "A storage scheduler provided by the kernel."
    }

    /** Titles for CPU clusters in plain words. [maxes] are (clusterId -> hardware max kHz). */
    fun clusterTitle(id: String, maxes: Map<String, Int>, cpus: List<Int>): String {
        val range = if (cpus.size == 1) "core ${cpus[0]}" else "cores ${cpus.first()}-${cpus.last()}"
        if (maxes.size < 2) return "CPU ($range)"
        val sorted = maxes.entries.sortedBy { it.value }.map { it.key }
        val i = sorted.indexOf(id)
        val kind = when {
            i == 0 -> "Efficiency cores (battery)"
            i == sorted.lastIndex -> "Performance cores (speed)"
            else -> "Middle cores"
        }
        return "$kind - $range"
    }
}
