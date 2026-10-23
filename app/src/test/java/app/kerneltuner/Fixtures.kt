package app.kerneltuner

import app.kerneltuner.hw.DiskFs
import app.kerneltuner.hw.SysFs
import java.io.File

/** Fixture trees captured read-only from real devices (see resources/fixtures). */
object Fixtures {
    fun fs(name: String): SysFs {
        val url = Fixtures::class.java.getResource("/fixtures/$name/root")!!
        return DiskFs(File(url.toURI()))
    }
}
