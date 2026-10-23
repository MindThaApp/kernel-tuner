package app.kerneltuner.sys

import android.content.Context
import app.kerneltuner.core.ActiveRecord
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
