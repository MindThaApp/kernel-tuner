package app.kerneltuner.sys

import android.content.Context
import app.kerneltuner.core.ActiveRecord
import app.kerneltuner.core.ClusterSetting
import app.kerneltuner.core.HeldState
import app.kerneltuner.core.PendingApply
import app.kerneltuner.hw.CpuCluster
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import app.kerneltuner.core.Profile
import app.kerneltuner.core.Settings
import app.kerneltuner.core.TestState
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StockSnapshot(val bootId: String, val settings: Settings)

@Serializable
data class Notice(
    val atMs: Long,
    val reason: String,
    val settings: List<String>,
    val profileId: String?,
    val profileName: String?,
)

/** All persistent state. Safety-critical writes use commit() so they are on disk before we touch hardware. */
class Store(ctx: Context) {
    private val app = ctx.applicationContext
    private val sp = app.getSharedPreferences("kt_state", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun <T> get(key: String, dec: (String) -> T): T? =
        sp.getString(key, null)?.let { try { dec(it) } catch (e: Exception) { null } }

    fun bootId(): String = try {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    } catch (e: Exception) {
        "unknown"
    }

    @Synchronized fun profiles(): List<Profile> =
        get("profiles") { json.decodeFromString(ListSerializer(Profile.serializer()), it) } ?: emptyList()

    @Synchronized fun saveProfiles(l: List<Profile>) {
        sp.edit().putString("profiles", json.encodeToString(ListSerializer(Profile.serializer()), l)).commit()
    }

    @Synchronized fun updateProfile(id: String, f: (Profile) -> Profile) =
        saveProfiles(profiles().map { if (it.id == id) f(it) else it })

    /** Only one profile may be the boot profile. */
    @Synchronized fun setBootProfile(id: String?) =
        saveProfiles(profiles().map { it.copy(applyOnBoot = it.id == id) })

    fun bootProfile(): Profile? = profiles().firstOrNull { it.applyOnBoot }

    var active: ActiveRecord?
        get() = get("active") { json.decodeFromString(ActiveRecord.serializer(), it) }
        set(v) { sp.edit().apply { if (v == null) remove("active") else putString("active", json.encodeToString(ActiveRecord.serializer(), v)) }.commit() }

    var test: TestState?
        get() = get("test") { json.decodeFromString(TestState.serializer(), it) }
        set(v) { sp.edit().apply { if (v == null) remove("test") else putString("test", json.encodeToString(TestState.serializer(), v)) }.commit() }

    var stock: StockSnapshot?
        get() = get("stock") { json.decodeFromString(StockSnapshot.serializer(), it) }
        set(v) { sp.edit().apply { if (v == null) remove("stock") else putString("stock", json.encodeToString(StockSnapshot.serializer(), v)) }.commit() }

    /** CPU and temperature limits the app keeps enforcing this boot (see Reconciler). */
    var held: HeldState?
        get() = get("held") { json.decodeFromString(HeldState.serializer(), it) }?.takeIf { it.bootId == bootId() }
        set(v) { sp.edit().apply { if (v == null || v.settings.isEmpty) remove("held") else putString("held", json.encodeToString(HeldState.serializer(), v)) }.commit() }

    /** Clusters seen in earlier detections: hotplugged-off cores hide their cpufreq files. */
    var clusterCache: List<CpuCluster>
        get() = get("cluster_cache") { json.decodeFromString(ListSerializer(CpuCluster.serializer()), it) } ?: emptyList()
        set(v) { sp.edit().putString("cluster_cache", json.encodeToString(ListSerializer(CpuCluster.serializer()), v)).apply() }

    /** Last known stock CPU limits per cluster, kept across reboots to fill in clusters that were offline at boot. */
    var stockCpuCache: Map<String, ClusterSetting>
        get() = get("stock_cpu_cache") { json.decodeFromString(MapSerializer(String.serializer(), ClusterSetting.serializer()), it) } ?: emptyMap()
        set(v) { sp.edit().putString("stock_cpu_cache", json.encodeToString(MapSerializer(String.serializer(), ClusterSetting.serializer()), v)).apply() }

    /** Steps ("cpu:cpu4:2400000", "gpu:852") that crashed this phone. Never cleared by itself. */
    var unstableSteps: Set<String>
        get() = sp.getStringSet("unstable_steps", emptySet()) ?: emptySet()
        set(v) { sp.edit().putStringSet("unstable_steps", v).commit() }

    /** Consecutive boots that ended in a crash with an overclock active. */
    var ocCrashStreak: Int
        get() = sp.getInt("oc_streak", 0)
        set(v) { sp.edit().putInt("oc_streak", v).commit() }

    /** Set after two such crashes in a row: no automatic applying of anything until the user re-enables it. */
    var autoApplyDisabled: Boolean
        get() = sp.getBoolean("auto_disabled", false)
        set(v) { sp.edit().putBoolean("auto_disabled", v).commit() }

    /** Wall-clock moments of the last boots (newest last). */
    var bootTimes: List<Long>
        get() = (sp.getString("boot_times", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }
        set(v) { sp.edit().putString("boot_times", v.takeLast(10).joinToString(",")).commit() }

    var pendingApply: PendingApply?
        get() = get("pending_apply") { json.decodeFromString(PendingApply.serializer(), it) }
        set(v) { sp.edit().apply { if (v == null) remove("pending_apply") else putString("pending_apply", json.encodeToString(PendingApply.serializer(), v)) }.commit() }

    var notice: Notice?
        get() = get("notice") { json.decodeFromString(Notice.serializer(), it) }
        set(v) { sp.edit().apply { if (v == null) remove("notice") else putString("notice", json.encodeToString(Notice.serializer(), v)) }.commit() }

    /** Boot id that the last boot-processing pass handled. */
    var lastBootId: String?
        get() = sp.getString("last_boot", null)
        set(v) { sp.edit().putString("last_boot", v).commit() }

    /** Boot id during which a clean shutdown/reboot broadcast was seen. */
    var markerBootId: String?
        get() = sp.getString("marker_boot", null)
        set(v) { sp.edit().apply { if (v == null) remove("marker_boot") else putString("marker_boot", v) }.commit() }

    /** Boot-applies not yet followed by a confirmed-stable window or a clean shutdown. */
    var unconfirmed: Int
        get() = sp.getInt("unconfirmed", 0)
        set(v) { sp.edit().putInt("unconfirmed", v).commit() }

    var testMinutes: Int
        get() = sp.getInt("test_minutes", 3).coerceIn(1, 60)
        set(v) { sp.edit().putInt("test_minutes", v.coerceIn(1, 60)).apply() }

    /** Whether the one-time "how test mode works" explanation has been shown. */
    var testExplained: Boolean
        get() = sp.getBoolean("test_explained", false)
        set(v) { sp.edit().putBoolean("test_explained", v).apply() }

    private fun crashFile() = File(app.filesDir, "crash/last-crash.txt")

    fun saveCrashLog(header: String, lines: List<String>) {
        try {
            val f = crashFile()
            f.parentFile?.mkdirs()
            f.writeText(header + "\n\n" + lines.joinToString("\n") + "\n")
        } catch (_: Exception) {
        }
    }

    fun crashLog(): String? = try { crashFile().takeIf { it.isFile }?.readText() } catch (e: Exception) { null }
    fun clearCrashLog() { try { crashFile().delete() } catch (_: Exception) { } }
}
