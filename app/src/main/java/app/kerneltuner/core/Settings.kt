package app.kerneltuner.core

import kotlinx.serialization.Serializable

@Serializable
data class ClusterSetting(val governor: String? = null, val minKhz: Int? = null, val maxKhz: Int? = null)

@Serializable
data class GpuSetting(val governor: String? = null, val minMhz: Int? = null, val maxMhz: Int? = null)

/** Null/absent fields mean "leave as is". A full snapshot has everything filled. */
@Serializable
data class Settings(
    val cpu: Map<String, ClusterSetting> = emptyMap(),
    val gpu: GpuSetting? = null,
    val io: Map<String, String> = emptyMap(),
    /** Only explicitly opted-in memory overrides. */
    val mem: Map<String, Long> = emptyMap(),
) {
    val isEmpty get() = cpu.isEmpty() && gpu == null && io.isEmpty() && mem.isEmpty()
}

@Serializable
data class Profile(
    val id: String,
    val name: String,
    val settings: Settings,
    /** Default OFF. At most one profile is the boot profile. */
    val applyOnBoot: Boolean = false,
    val unstable: Boolean = false,
    val unstableReason: String? = null,
)

/** Write-ahead record: something non-stock is (about to be) active in this boot. */
@Serializable
data class ActiveRecord(
    val bootId: String,
    val profileId: String?,
    /** BOOT, PROFILE, MANUAL, TEST */
    val kind: String,
    val summary: List<String>,
    val atMs: Long,
)

@Serializable
data class TestState(
    val bootId: String,
    val deadlineWallMs: Long,
    val revertOps: List<app.kerneltuner.hw.WriteOp>,
    val summary: List<String>,
)
