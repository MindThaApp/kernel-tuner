package app.kerneltuner.hw

import java.io.File

/** Minimal read-only view of the file system, so detection can be unit-tested with fixtures. */
interface SysFs {
    /** Trimmed file content, or null when missing/unreadable/empty-on-error. */
    fun read(path: String): String?
    fun list(path: String): List<String>
    fun exists(path: String): Boolean
    /** Owner-write permission bit of the file, or null when unknown. */
    fun ownerWritable(path: String): Boolean? = null
}

/** Plain java.io implementation. [root] lets tests point at a fixture directory. */
class DiskFs(private val root: File? = null) : SysFs {
    private fun f(path: String) = if (root == null) File(path) else File(root, path.trimStart('/'))
    override fun read(path: String): String? = try {
        val file = f(path)
        if (file.isFile) file.readText().trim() else null
    } catch (e: Exception) {
        null
    }

    override fun list(path: String): List<String> = try {
        f(path).list()?.sorted() ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    override fun exists(path: String): Boolean = try {
        f(path).exists()
    } catch (e: Exception) {
        false
    }

    override fun ownerWritable(path: String): Boolean? = try {
        java.nio.file.Files.getPosixFilePermissions(f(path).toPath()).contains(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)
    } catch (e: Exception) {
        null
    }
}

object Paths {
    const val CPU = "/sys/devices/system/cpu"
    const val CPUFREQ = "/sys/devices/system/cpu/cpufreq"
    const val KGSL = "/sys/class/kgsl/kgsl-3d0"
    const val THERMAL = "/sys/class/thermal"
    const val BLOCK = "/sys/block"
    const val DEVICES = "/sys/devices"
    const val VM = "/proc/sys/vm"
}

/** Small helpers shared by the parsers. */
internal fun String?.ints(): List<Int> =
    this?.split(Regex("\\s+"))?.mapNotNull { it.toIntOrNull() } ?: emptyList()

internal fun String?.firstInt(): Int? =
    this?.let { Regex("-?\\d+").find(it)?.value?.toIntOrNull() }
