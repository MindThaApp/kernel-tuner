package app.kerneltuner.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.kerneltuner.core.Plan
import app.kerneltuner.core.Planner
import app.kerneltuner.core.Profile
import app.kerneltuner.core.Settings
import app.kerneltuner.core.Snapshot
import app.kerneltuner.core.StepDown
import app.kerneltuner.core.TestState
import app.kerneltuner.hw.GpuDetector
import app.kerneltuner.hw.GpuState
import app.kerneltuner.hw.Hardware
import app.kerneltuner.hw.HardwareDetector
import app.kerneltuner.hw.MemInfo
import app.kerneltuner.hw.Memory
import app.kerneltuner.hw.ThermalDetector
import app.kerneltuner.hw.ThermalZone
import app.kerneltuner.hw.firstInt
import app.kerneltuner.sys.AppFs
import app.kerneltuner.sys.Applier
import app.kerneltuner.sys.BootProcessor
import app.kerneltuner.sys.Notice
import app.kerneltuner.sys.Notifier
import app.kerneltuner.sys.Root
import app.kerneltuner.sys.StockSnapshot
import app.kerneltuner.sys.Store
import app.kerneltuner.sys.TestMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

data class CoreInfo(val cpu: Int, val online: Boolean, val khz: Int?)
data class ClusterLive(val label: String, val governor: String?, val minKhz: Int?, val maxKhz: Int?)
data class Dash(
    val cores: List<CoreInfo>,
    val clusters: List<ClusterLive>,
    val gpu: GpuState?,
    val temps: List<Pair<ThermalZone, Double>>,
    val mem: MemInfo?,
    val zramRatio: Pair<Long, Long>?,
)

/** A pending action that needs the user's confirmation first. */
data class Confirm(val title: String, val body: String, val action: () -> Unit)

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val fs = AppFs()

    var hw by mutableStateOf<Hardware?>(null); private set
    var rootOk by mutableStateOf<Boolean?>(null); private set
    var dash by mutableStateOf<Dash?>(null); private set
    var current by mutableStateOf(Settings()); private set
    var draft by mutableStateOf(Settings())
    var stock by mutableStateOf<Settings?>(null); private set
    var profiles by mutableStateOf(listOf<Profile>()); private set
    var test by mutableStateOf<TestState?>(null); private set
    var notice by mutableStateOf<Notice?>(null); private set
    var crashLog by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null)
    var memValues by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var busy by mutableStateOf(false); private set
    var confirm by mutableStateOf<Confirm?>(null)
    var testMinutes by mutableStateOf(store.testMinutes)
    /** Opted-in memory overrides: key -> value. Empty means the ROM's values are left alone. */
    val memOverrides = mutableStateMapOf<String, Long>()
    var memText = mutableStateMapOf<String, String>()
    var nowMs by mutableStateOf(System.currentTimeMillis())

    fun init() {
        viewModelScope.launch(Dispatchers.IO) {
            rootOk = Root.available()
            try { BootProcessor.run(app, allowApply = false) } catch (_: Throwable) { }
            hw = HardwareDetector.detect(fs)
            ensureStock()
            reload()
            checkTestExpiry()
        }
    }

    private fun ensureStock() {
        val h = hw ?: return
        val boot = store.bootId()
        val s = store.stock
        if (s != null && s.bootId == boot) { stock = s.settings; return }
        val snap = Snapshot.read(fs, h)
        store.stock = StockSnapshot(boot, snap)
        stock = snap
    }

    fun reload() {
        val h = hw ?: return
        current = Snapshot.read(fs, h)
        draft = current
        profiles = store.profiles()
        test = TestMode.pending(app)
        notice = store.notice
        crashLog = store.crashLog()
        memValues = h.memItems.mapNotNull { m ->
            val v = if (m.path != null) fs.read(m.path) else readProp(m.prop!!)
            v?.let { m.key to it }
        }.toMap()
    }

    private fun readProp(k: String): String? = try {
        val p = ProcessBuilder("getprop", k).start()
        p.inputStream.bufferedReader().readText().trim().ifEmpty { null }
    } catch (e: Exception) { null }

    fun refreshOnShow() { if (hw != null) viewModelScope.launch(Dispatchers.IO) { reload() } }

    fun redetect() {
        viewModelScope.launch(Dispatchers.IO) { hw = HardwareDetector.detect(fs); reload() }
    }

    /** Called by the visible Dashboard only. */
    fun sample() {
        val h = hw ?: return
        val cores = (0 until h.cpuCount).map { n ->
            val d = "/sys/devices/system/cpu/cpu$n"
            val online = n == 0 || fs.read("$d/online") != "0"
            CoreInfo(n, online, if (online) fs.read("$d/cpufreq/scaling_cur_freq").firstInt() else null)
        }
        val cl = h.clusters.map { c ->
            ClusterLive(c.label, fs.read("${c.readPath}/scaling_governor"),
                fs.read("${c.readPath}/scaling_min_freq").firstInt(), fs.read("${c.readPath}/scaling_max_freq").firstInt())
        }
        val temps = h.thermal.mapNotNull { z -> ThermalDetector.parseTempC(fs.read("${z.path}/temp"))?.let { z to it } }
        val z = Memory.parseZramRatio(fs.read("/sys/block/zram0/mm_stat"))
        dash = Dash(cores, cl, h.gpu?.let { GpuDetector.readState(fs, it) }, temps, Memory.parseMeminfo(fs.read("/proc/meminfo")), z)
    }

    fun tick() {
        nowMs = System.currentTimeMillis()
        checkTestExpiry()
    }

    private fun checkTestExpiry() {
        val t = test ?: return
        if (nowMs >= t.deadlineWallMs && !busy) {
            viewModelScope.launch(Dispatchers.IO) {
                TestMode.revert(app); reload(); message = "Test period ended: reverted to stock."
            }
        }
    }

    private fun stockOrCurrent() = stock ?: current

    fun planFor(target: Settings): Plan? {
        val h = hw ?: return null
        return Planner.plan(h, current, target, stockOrCurrent())
    }

    fun draftTarget(): Settings = Settings(draft.cpu, draft.gpu, draft.io, emptyMap())

    /** Entry point for Apply / Test buttons. Above-stock changes are forced into test mode after confirmation. */
    fun requestApply(target: Settings, asTest: Boolean, profileId: String? = null) {
        val plan = planFor(target) ?: return
        if (plan.isEmpty) { message = "Nothing to change." + warn(plan); return }
        if (plan.aboveStock.isNotEmpty()) {
            confirm = Confirm(
                "Above stock limits",
                "This raises:\n" + plan.aboveStock.joinToString("\n") { "- $it" } +
                    "\n\nIt may overheat or freeze the device. It will be applied in TEST mode and automatically reverted after $testMinutes min unless you tap Keep.",
            ) { runApply(plan, true, profileId) }
        } else runApply(plan, asTest, profileId)
    }

    private fun warn(p: Plan) = if (p.warnings.isEmpty()) "" else " (" + p.warnings.joinToString("; ") + ")"

    private fun runApply(plan: Plan, asTest: Boolean, profileId: String?) {
        val h = hw ?: return
        viewModelScope.launch(Dispatchers.IO) {
            busy = true
            val st = stockOrCurrent()
            val r = if (asTest) TestMode.start(app, fs, h, plan, testMinutes, profileId, st)
            else Applier.apply(app, fs, h, plan, if (profileId != null) "PROFILE" else "MANUAL", profileId, st)
            reload()
            message = (if (r.ok) (if (asTest) "Applied in test mode ($testMinutes min)." else "Applied.") else "Problems: " + r.failed.joinToString("; ")) + warn(plan)
            busy = false
        }
    }

    fun keepTest() = viewModelScope.launch(Dispatchers.IO) { TestMode.keep(app); reload(); message = "Kept until next reboot." }

    fun revertTest() = viewModelScope.launch(Dispatchers.IO) { busy = true; TestMode.revert(app); reload(); message = "Reverted to stock."; busy = false }

    fun revertToStock() {
        val s = stock ?: return
        val h = hw ?: return
        viewModelScope.launch(Dispatchers.IO) {
            busy = true
            if (test != null) TestMode.revert(app)
            reload()
            val plan = Planner.plan(h, current, s, s, withRevert = false)
            val r = Applier.apply(app, fs, h, plan, "MANUAL", null, s)
            reload()
            message = if (r.ok) "Back at boot-time values." else "Problems: " + r.failed.joinToString("; ")
            busy = false
        }
    }

    fun updateTestMinutes(m: Int) { testMinutes = m.coerceIn(1, 60); store.testMinutes = testMinutes }

    // ---- profiles ----
    fun saveProfile(name: String) {
        val mem = memOverrides.toMap()
        val p = Profile(UUID.randomUUID().toString(), name.ifBlank { "Profile ${profiles.size + 1}" }, Settings(draft.cpu, draft.gpu, draft.io, mem))
        viewModelScope.launch(Dispatchers.IO) { store.saveProfiles(store.profiles() + p); reload(); message = "Saved \"${p.name}\"." }
    }

    fun deleteProfile(p: Profile) = viewModelScope.launch(Dispatchers.IO) { store.saveProfiles(store.profiles().filter { it.id != p.id }); reload() }

    fun applyProfile(p: Profile, asTest: Boolean) = requestApply(p.settings, asTest, p.id)

    fun setBoot(p: Profile, on: Boolean) {
        if (!on) { viewModelScope.launch(Dispatchers.IO) { store.setBootProfile(null); reload() }; return }
        val plan = planFor(p.settings)
        val extra = if (plan != null && plan.aboveStock.isNotEmpty()) "\n\nThis profile exceeds stock limits:\n" + plan.aboveStock.joinToString("\n") { "- $it" } else ""
        confirm = Confirm(
            "Apply \"${p.name}\" on boot?",
            "Only this profile will be applied after each boot, as long as the previous shutdown was clean. After a crash or unclean reboot it is NOT applied and is marked unstable.$extra",
        ) { viewModelScope.launch(Dispatchers.IO) { store.setBootProfile(p.id); reload() } }
    }

    fun clearUnstable(p: Profile) = viewModelScope.launch(Dispatchers.IO) {
        store.updateProfile(p.id) { it.copy(unstable = false, unstableReason = null, applyOnBoot = false) }; reload()
    }

    fun stepDown(p: Profile) {
        val h = hw ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val (ns, notes) = StepDown.apply(h, p.settings)
            store.updateProfile(p.id) { it.copy(settings = ns, unstable = false, unstableReason = null, applyOnBoot = false) }
            reload()
            message = if (notes.isEmpty()) "Already at the lowest steps." else "Stepped down: ${notes.joinToString("; ")}. Apply-on-boot is OFF."
        }
    }

    fun dismissNotice() = viewModelScope.launch(Dispatchers.IO) { store.notice = null; Notifier.cancelUnclean(app); reload() }
    fun clearCrashLog() = viewModelScope.launch(Dispatchers.IO) { store.clearCrashLog(); reload() }

    // ---- memory ----
    fun applyMemory() {
        val t = Settings(mem = memOverrides.toMap())
        if (t.mem.isEmpty()) { message = "No overrides enabled."; return }
        requestApply(t, false)
    }
}
