package app.kerneltuner.sys

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.kerneltuner.core.BootAction
import app.kerneltuner.core.OcMarkerData
import app.kerneltuner.core.OcPolicy
import app.kerneltuner.core.OcSteps
import app.kerneltuner.core.PendingApply
import app.kerneltuner.core.BootClassifier
import app.kerneltuner.core.BootGuard
import app.kerneltuner.core.BootProps
import app.kerneltuner.core.CrashScanner
import app.kerneltuner.core.Planner
import app.kerneltuner.core.Snapshot
import app.kerneltuner.core.Verdict
import app.kerneltuner.core.CrashEvidence
import app.kerneltuner.hw.HardwareDetector
import java.text.DateFormat
import java.util.Date

object BootProcessor {
    const val ACTION_DELAYED = "app.kerneltuner.action.BOOT_DELAYED_APPLY"
    const val ACTION_OC_STABLE = "app.kerneltuner.action.OC_STABLE"
    const val ACTION_STABLE = "app.kerneltuner.action.BOOT_STABLE"
    private const val STABLE_AFTER_MS = 5 * 60_000L

    private fun prop(k: String): String? = try {
        val p = ProcessBuilder("getprop", k).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().trim().ifEmpty { null }.also { p.waitFor() }
    } catch (e: Exception) {
        null
    }

    fun bootProps() = BootProps(prop("sys.boot.reason"), prop("ro.boot.bootreason"), prop("persist.sys.boot.reason"))

    /** pstore + last_kmsg need root to read. Output is bounded. */
    private fun readCrashFiles(): Map<String, String> {
        val out = Root.run(
            "for f in /sys/fs/pstore/*; do [ -f \"\$f\" ] && { echo \"####\$f\"; tail -c 200000 \"\$f\" 2>/dev/null; }; done",
            "[ -f /proc/last_kmsg ] && { echo '####/proc/last_kmsg'; tail -c 200000 /proc/last_kmsg 2>/dev/null; }",
        )
        val m = LinkedHashMap<String, StringBuilder>()
        var cur: StringBuilder? = null
        for (l in out) {
            if (l.startsWith("####")) cur = StringBuilder().also { m[l.removePrefix("####").substringAfterLast('/')] = it }
            else cur?.append(l)?.append('\n')
        }
        return m.mapValues { it.value.toString() }
    }

    /**
     * Runs once per boot (guarded by boot_id). Order matters for safety:
     * 1) classify previous shutdown, 2) persist the new boot state (clears the old active record),
     * 3) only then possibly apply the boot profile, with a write-ahead record.
     */
    @Synchronized
    fun run(ctx: Context, allowApply: Boolean) {
        val store = Store(ctx)
        val cur = store.bootId()
        if (store.lastBootId == cur) return
        val prevBoot = store.lastBootId
        val prevActive = store.active
        val bootProfile = store.bootProfile()
        val markerPresent = prevBoot != null && store.markerBootId == prevBoot
        val ocMarker = OcMarker.read(ctx)

        val needRoot = prevActive != null || (allowApply && bootProfile != null)
        val root = needRoot && Root.available()
        val files = if (root) readCrashFiles() else emptyMap()
        val ev: CrashEvidence = if (files.isEmpty()) CrashEvidence(false, null, emptyList()) else CrashScanner.scan(files)
        val cls = BootClassifier.classify(markerPresent, bootProps(), ev)
        val verdict = if (prevBoot == null) Verdict.CLEAN else cls.verdict

        // Overclock write-ahead marker still present = the previous boot ended with an overclock active.
        val crashedOc = ocMarker != null && ocMarker.bootId != cur &&
            OcPolicy.crashedWithMarker(true, markerPresent, verdict == Verdict.CLEAN)
        val bootWall = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val times = (store.bootTimes + bootWall).distinct()
        val rapid = OcPolicy.rapidReboots(times, System.currentTimeMillis())

        val decision = BootGuard.decide(prevActive, verdict, bootProfile, store.unconfirmed, root)

        // Persist the new boot state first.
        store.lastBootId = cur
        store.markerBootId = null
        store.active = null
        store.test = null
        if (verdict == Verdict.CLEAN) store.unconfirmed = 0
        // Everything "held" is per boot: nothing carries over a reboot.
        store.held = null
        store.pendingApply = null
        Reconcile.cancel(ctx)
        Notifier.cancelPendingApply(ctx)
        if (store.thermalEngineTouched && Root.available()) ThermalEngine.restoreIfModified(ctx)
        store.bootTimes = times
        OcMarker.clear(ctx)
        val streak = OcPolicy.nextStreak(store.ocCrashStreak, ocMarker != null, crashedOc, verdict == Verdict.CLEAN)
        store.ocCrashStreak = streak
        if (streak >= OcPolicy.STREAK_LIMIT) store.autoApplyDisabled = true
        if (crashedOc) {
            val steps = ocMarker!!.steps
            store.unstableSteps = store.unstableSteps + steps
            ocMarker.profileId?.let { id -> store.updateProfile(id) { it.copy(applyOnBoot = false, unstable = true, unstableReason = "an overclock crashed the phone") } }
            Notifier.postInfo(ctx, OcPolicy.BLOCKED_TITLE,
                "The phone restarted without a clean shutdown while an overclock was active (${steps.joinToString(", ")}). " +
                    "Apply on boot is switched off for that profile and the step is marked unstable on this phone." +
                    if (store.autoApplyDisabled) " Automatic applying is now off for everything." else "")
        }

        if (prevActive != null && verdict == Verdict.UNCLEAN || decision.noticeSettings != null) {
            val tail = ev.tail
            val df = DateFormat.getDateTimeInstance().format(Date())
            store.saveCrashLog(
                "Unclean reboot detected at $df\nReason: ${cls.reason}\nActive settings: ${prevActive?.summary?.joinToString("; ") ?: "-"}" +
                    if (tail.isEmpty()) "\n(no readable crash log)" else "\nLast ${tail.size} lines of crash log:",
                tail,
            )
        }
        decision.markUnstableId?.let { id ->
            store.updateProfile(id) { it.copy(unstable = true, unstableReason = cls.reason) }
        }
        decision.noticeSettings?.let { settings ->
            val prof = decision.markUnstableId?.let { id -> store.profiles().firstOrNull { it.id == id } }
            val n = Notice(System.currentTimeMillis(), cls.reason, settings, prof?.id, prof?.name)
            store.notice = n
            Notifier.postUnclean(ctx, n)
        }

        val prof = decision.apply
        if (prof == null) {
            // Rapid reboots / disabled automatic applying still deserve a note when a boot profile exists.
            if (bootProfile != null && (rapid || store.autoApplyDisabled) && decision.noticeSettings == null) {
                Notifier.postInfo(ctx, OcPolicy.BLOCKED_TITLE,
                    OcPolicy.decide(true, true, true, true, false, store.autoApplyDisabled, rapid, false).reason ?: "")
            }
            return
        }
        if (!allowApply) return
        val fs = AppFs()
        val hw = HwProbe.detect(ctx, fs)
        val now = HwProbe.withStockCache(ctx, Snapshot.read(fs, hw))
        store.stock = StockSnapshot(cur, now)           // boot state before any of our writes is stock
        val plan = Planner.plan(hw, now, prof.settings, now)
        val oc = plan.aboveStock.isNotEmpty()
        val d = OcPolicy.decide(
            isOc = oc,
            applyOnBoot = prof.applyOnBoot,
            confirmedByTest = prof.confirmedSettings != null && prof.confirmedSettings == prof.settings,
            previousShutdownClean = verdict == Verdict.CLEAN,
            previousOcCrashed = crashedOc,
            autoApplyDisabled = store.autoApplyDisabled,
            rapidReboots = rapid,
            hitsUnstableStep = OcSteps.of(hw, plan.after, now).any { it in store.unstableSteps },
        )
        when (d.action) {
            BootAction.SKIP -> Notifier.postInfo(ctx, OcPolicy.BLOCKED_TITLE, "\"${prof.name}\": ${d.reason}")
            BootAction.APPLY_NOW -> {
                store.unconfirmed = store.unconfirmed + 1
                Applier.apply(ctx, fs, hw, plan, "BOOT", prof.id, now)
                scheduleStable(ctx)
            }
            BootAction.APPLY_DELAYED -> {
                val due = System.currentTimeMillis() + OcPolicy.DELAY_MS
                store.pendingApply = PendingApply(cur, prof.id, due)
                scheduleDelayed(ctx)
                Notifier.postPendingApply(ctx, (OcPolicy.DELAY_MS / 1000).toInt(), prof.name)
            }
        }
    }

    private fun delayedPi(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 4, Intent(ctx, BootApplyReceiver::class.java).setAction(ACTION_DELAYED),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun scheduleDelayed(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + OcPolicy.DELAY_MS, delayedPi(ctx))
    }

    fun cancelPendingApply(ctx: Context) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(delayedPi(ctx))
        Store(ctx).pendingApply = null
        Notifier.cancelPendingApply(ctx)
    }

    /** The 90 s alarm. Re-checks everything: a changed profile, a disabled switch or a new crash all cancel it. */
    @Synchronized
    fun runDelayedApply(ctx: Context) {
        val store = Store(ctx)
        val pending = store.pendingApply
        store.pendingApply = null
        Notifier.cancelPendingApply(ctx)
        if (pending == null || pending.bootId != store.bootId()) return
        if (System.currentTimeMillis() < pending.dueWallMs - 5_000) return        // alarm fired early / clock jump: do nothing
        val prof = store.profiles().firstOrNull { it.id == pending.profileId } ?: return
        if (!prof.applyOnBoot || prof.unstable || store.autoApplyDisabled) return
        if (prof.confirmedSettings == null || prof.confirmedSettings != prof.settings) return
        if (!Root.available()) return
        val fs = AppFs()
        val hw = HwProbe.detect(ctx, fs)
        val stock = store.stock?.takeIf { it.bootId == pending.bootId }?.settings ?: return
        val now = Snapshot.read(fs, hw)
        val plan = Planner.plan(hw, now, prof.settings, stock)
        if (OcSteps.of(hw, plan.after, stock).any { it in store.unstableSteps }) return
        store.unconfirmed = store.unconfirmed + 1
        Applier.apply(ctx, fs, hw, plan, "BOOT", prof.id, stock)
        scheduleStable(ctx)
        scheduleOcStable(ctx)
    }

    private fun ocStablePi(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 5, Intent(ctx, OcStableReceiver::class.java).setAction(ACTION_OC_STABLE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun scheduleOcStable(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + OcPolicy.STABLE_MS, ocStablePi(ctx))
    }

    fun onOcStable(ctx: Context) { OcMarker.clear(ctx) }

    private fun stablePi(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 2, Intent(ctx, StableReceiver::class.java).setAction(ACTION_STABLE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun scheduleStable(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + STABLE_AFTER_MS, stablePi(ctx))
    }

    fun markStable(ctx: Context) {
        val s = Store(ctx)
        if (s.lastBootId == s.bootId()) s.unconfirmed = 0
    }

    /** Called from the shutdown/reboot broadcast. Synchronous commit. */
    fun markCleanShutdown(ctx: Context) {
        val s = Store(ctx)
        s.markerBootId = s.bootId()
    }
}
