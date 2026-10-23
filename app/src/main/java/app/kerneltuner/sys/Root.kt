package app.kerneltuner.sys

import app.kerneltuner.hw.DiskFs
import app.kerneltuner.hw.SysFs
import com.topjohnwu.superuser.Shell
import java.util.concurrent.ConcurrentHashMap

/** Root through libsu's single persistent `su` shell. Never call from the main thread. */
object Root {
    @Volatile private var state: Boolean? = null

    /** Opens (and may prompt for) root once; later calls are free. */
    fun available(): Boolean {
        state?.let { return it }
        val r = try { Shell.getShell().isRoot } catch (e: Throwable) { false }
        state = r
        return r
    }

    fun known(): Boolean? = state

    fun run(vararg cmds: String): List<String> = try {
        Shell.cmd(*cmds).exec().out
    } catch (e: Throwable) {
        emptyList()
    }

    fun runOk(vararg cmds: String): Pair<Boolean, List<String>> = try {
        val r = Shell.cmd(*cmds).exec()
        r.isSuccess to r.out
    } catch (e: Throwable) {
        false to emptyList()
    }
}

/** Reads directly when allowed, falls back to root per path (SELinux may hide sysfs from apps). */
class AppFs : SysFs {
    private val direct = DiskFs()
    private val viaRoot = ConcurrentHashMap.newKeySet<String>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    private fun q(p: String) = "'" + p.replace("'", "'\\''") + "'"

    override fun read(path: String): String? {
        if (path in missing) return null
        if (path !in viaRoot) {
            direct.read(path)?.let { return it }
            if (Root.known() != true) return null
        }
        val (ok, out) = Root.runOk("cat ${q(path)} 2>/dev/null")
        if (!ok) {
            viaRoot.remove(path); missing.add(path)
            return null
        }
        viaRoot.add(path)
        return out.joinToString("\n").trim()
    }

    override fun list(path: String): List<String> {
        val d = direct.list(path)
        if (d.isNotEmpty() || Root.known() != true) return d
        return Root.run("ls -1 ${q(path)} 2>/dev/null").map { it.trim() }.filter { it.isNotEmpty() }.sorted()
    }

    override fun exists(path: String): Boolean {
        if (direct.exists(path)) return true
        if (Root.known() != true) return false
        return Root.runOk("[ -e ${q(path)} ]").first
    }
}
