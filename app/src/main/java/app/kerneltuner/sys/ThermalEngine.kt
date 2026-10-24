package app.kerneltuner.sys

import app.kerneltuner.core.ThermalConf
import app.kerneltuner.hw.TempLimitDetector
import java.util.Base64

/**
 * Moto: adjusts the two thermal-engine rules in the thermal-relax Magisk module's overlay and restarts the daemon.
 * The module's original file is copied to a backup first; every rewrite starts from that backup, so only the
 * targeted lines can ever differ. The file is written in place (same inode) so Magisk's bind mount sees it.
 */
object ThermalEngine {
    const val CONF = TempLimitDetector.TE_CONF
    const val BACKUP = TempLimitDetector.TE_BACKUP
    private const val SERVICE = "thermal-engine"

    private fun q(p: String) = "'$p'"

    private fun cat(path: String): String? {
        val (ok, out) = Root.runOk("cat ${q(path)} 2>/dev/null")
        return if (ok && out.isNotEmpty()) out.joinToString("\n") else null
    }

    private fun restart() {
        Root.run("setprop ctl.restart $SERVICE || { stop $SERVICE; start $SERVICE; }")
    }

    private fun writeConf(text: String): Boolean {
        val b64 = Base64.getEncoder().encodeToString(text.toByteArray())
        Root.run("printf %s '$b64' | base64 -d > ${q(CONF)}")
        return cat(CONF)?.trimEnd() == text.trimEnd()
    }

    /** Sets the first throttle to [firstC] degrees. Returns null on success, else a reason. */
    @Synchronized
    fun set(firstC: Int): String? {
        if (!Root.available()) return "Root not available"
        val live = cat(CONF) ?: return "thermal-engine.conf not found in the module"
        if (!ThermalConf.editable(live)) return "thermal-engine.conf has an unexpected layout"
        Root.run("[ -f ${q(BACKUP)} ] || cp ${q(CONF)} ${q(BACKUP)}")
        val base = cat(BACKUP) ?: return "could not make a backup copy"
        if (!ThermalConf.editable(base)) return "backup copy has an unexpected layout"
        val text = ThermalConf.rewrite(base, firstC) ?: return "rewrite failed"
        if (!writeConf(text)) {
            writeConf(base)
            return "write did not verify; original restored"
        }
        restart()
        return null
    }

    /** Puts the module's original file back. Returns null on success. */
    @Synchronized
    fun restoreDefault(): String? {
        if (!Root.available()) return "Root not available"
        val base = cat(BACKUP) ?: return null          // never modified by the app
        if (cat(CONF)?.trimEnd() == base.trimEnd()) return null
        if (!writeConf(base)) return "restore did not verify"
        restart()
        return null
    }

    /** The file lives on /data and survives reboots: undo any edit at boot so nothing relaxed comes back by itself. */
    fun restoreIfModified(ctx: android.content.Context) {
        if (!Store(ctx).thermalEngineTouched) return
        try { if (restoreDefault() == null) Store(ctx).thermalEngineTouched = false } catch (_: Throwable) { }
    }
}
