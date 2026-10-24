package app.kerneltuner.core

import app.kerneltuner.hw.Hardware
import kotlinx.serialization.Serializable

/** Persisted write-ahead record: an above-stock write is about to happen / is active. Present at next boot = it crashed. */
@Serializable
data class OcMarkerData(val bootId: String, val profileId: String?, val steps: List<String>, val atMs: Long)

/** A boot-time overclock apply that is waiting out its delay. Valid only for [bootId]. */
@Serializable
data class PendingApply(val bootId: String, val profileId: String, val dueWallMs: Long)

enum class BootAction { APPLY_NOW, APPLY_DELAYED, SKIP }

data class BootApplyDecision(val action: BootAction, val reason: String?)

/**
 * The hard rule for automatic applying after a reboot. Nothing above stock is written unless the user enabled
 * apply-on-boot, the exact profile passed a test run and was confirmed with Keep, and the last shutdown was clean.
 * Pure, so every branch is unit-tested.
 */
object OcPolicy {
    const val DELAY_MS = 90_000L
    const val STABLE_MS = 10 * 60_000L
    const val STREAK_LIMIT = 2
    const val BLOCKED_TITLE = "Overclock not re-applied after reboot"
    const val REBOOT_WINDOW_MS = 5 * 60_000L
    const val REBOOT_LIMIT = 2

    fun decide(
        isOc: Boolean,
        applyOnBoot: Boolean,
        confirmedByTest: Boolean,
        previousShutdownClean: Boolean,
        previousOcCrashed: Boolean,
        autoApplyDisabled: Boolean,
        rapidReboots: Boolean,
        hitsUnstableStep: Boolean,
    ): BootApplyDecision {
        if (autoApplyDisabled) return BootApplyDecision(BootAction.SKIP, "Automatic applying is switched off after repeated crashes with an overclock. Re-enable it on the Safety tab.")
        if (rapidReboots) return BootApplyDecision(BootAction.SKIP, "The phone restarted $REBOOT_LIMIT or more times within 5 minutes, so nothing was applied.")
        if (!isOc) return BootApplyDecision(BootAction.APPLY_NOW, null)
        if (!applyOnBoot) return BootApplyDecision(BootAction.SKIP, "Apply on boot is not enabled for this profile.")
        if (!confirmedByTest) return BootApplyDecision(BootAction.SKIP, "This profile has not passed a full test run that you confirmed with Keep.")
        if (!previousShutdownClean) return BootApplyDecision(BootAction.SKIP, "The last shutdown was not clean.")
        if (previousOcCrashed) return BootApplyDecision(BootAction.SKIP, "The previous overclock crashed the phone.")
        if (hitsUnstableStep) return BootApplyDecision(BootAction.SKIP, "It contains a step that crashed this phone before.")
        return BootApplyDecision(BootAction.APPLY_DELAYED, null)
    }

    /** Consecutive boots that ended in a crash with an overclock active. */
    fun nextStreak(prev: Int, markerPresent: Boolean, crashed: Boolean, verdictClean: Boolean): Int = when {
        crashed -> prev + 1
        !markerPresent && verdictClean -> 0
        else -> prev
    }

    /** The marker is evidence of a crash unless the shutdown was clean (broadcast seen) and the boot looks clean. */
    fun crashedWithMarker(markerPresent: Boolean, cleanShutdownSeen: Boolean, verdictClean: Boolean): Boolean =
        markerPresent && !(cleanShutdownSeen && verdictClean)

    /** [bootTimesMs]: wall-clock boot moments including the current one. */
    fun rapidReboots(bootTimesMs: List<Long>, nowMs: Long): Boolean =
        bootTimesMs.count { nowMs - it in 0..REBOOT_WINDOW_MS } >= REBOOT_LIMIT + 1

    /** Test-mode values are never kept as "held": only a confirmed Keep promotes them. */
    fun persistAsHeld(kind: String) = kind != "TEST"

    /** A test run counts as full only when Keep came after most of the countdown. */
    fun isFullTest(startedMs: Long, durationMs: Long, nowMs: Long) = durationMs > 0 && nowMs - startedMs >= durationMs * 8 / 10

    fun isOc(hw: Hardware, settings: Settings, stock: Settings): Boolean =
        Planner.plan(hw, stock, settings, stock, withRevert = false).aboveStock.isNotEmpty()
}

object OcSteps {
    /** Keys of every above-stock step in [after], e.g. "cpu:cpu4:2400000", "gpu:852", "temp:ipa:120". */
    fun of(hw: Hardware, after: Settings, stock: Settings): List<String> {
        val out = ArrayList<String>()
        for ((id, s) in after.cpu) {
            val c = hw.clusters.firstOrNull { it.id == id } ?: continue
            val max = s.maxKhz ?: continue
            if (max > OcInfo.cpuEffectiveStock(c, stock.cpu[id]?.maxKhz)) out += "cpu:$id:$max"
        }
        val g = hw.gpu
        val gm = after.gpu?.maxMhz
        if (g != null && gm != null && gm > (stock.gpu?.maxMhz ?: OcInfo.gpuStockCap(g) ?: g.maxMhz)) out += "gpu:$gm"
        for ((id, v) in after.temp) {
            val tg = hw.tempGroups.firstOrNull { it.id == id } ?: continue
            if (v > (stock.temp[id] ?: tg.detectedC)) out += "temp:$id:$v"
        }
        return out
    }
}
