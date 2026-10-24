package app.kerneltuner.sys

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
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

        val needRoot = prevActive != null || (allowApply && bootProfile != null)
        val root = needRoot && Root.available()
        val files = if (root) readCrashFiles() else emptyMap()
        val ev: CrashEvidence = if (files.isEmpty()) CrashEvidence(false, null, emptyList()) else CrashScanner.scan(files)
        val cls = BootClassifier.classify(markerPresent, bootProps(), ev)
        val verdict = if (prevBoot == null) Verdict.CLEAN else cls.verdict

        val decision = BootGuard.decide(prevActive, verdict, bootProfile, store.unconfirmed, root)

        // Persist the new boot state first.
        store.lastBootId = cur
        store.markerBootId = null
        store.active = null
        store.test = null
        if (verdict == Verdict.CLEAN) store.unconfirmed = 0

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

        val prof = decision.apply ?: return
        if (!allowApply) return
        val fs = AppFs()
        val hw = HwProbe.detect(ctx, fs)
        val now = HwProbe.withStockCache(ctx, Snapshot.read(fs, hw))
        store.stock = StockSnapshot(cur, now)           // boot state before any of our writes is stock
        store.unconfirmed = store.unconfirmed + 1
        val plan = Planner.plan(hw, now, prof.settings, now)
        Applier.apply(ctx, fs, hw, plan, "BOOT", prof.id, now)
        scheduleStable(ctx)
    }

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
